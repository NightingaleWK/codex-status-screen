package local.codex.statusscreen;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Point;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.text.TextUtils;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.LocalDate;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@SuppressWarnings("deprecation")
public final class MainActivity extends Activity {
    private static final int SCAN = 11, CAMERA = 12, NOTIFY = 13;
    private static final long EDGE_EMPHASIS_MS = 8000;
    private static final String QUIET_STATUS_CHANNEL = "codex_media_status";
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");
    private static final AudioAttributes MEDIA_ALERT_AUDIO = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService network = Executors.newFixedThreadPool(6);
    private final ColorDrawable windowSurface = new ColorDrawable();
    private SharedPreferences preferences;
    private DeviceStore store;
    private Discovery discovery;
    private boolean visible, settings, pairing, dark, touchActive, redrawAfterTouch;
    private String scope = "all";
    private FrameLayout frame, contentFrame;
    private ScrollView scroll, leftScroll, rightScroll;
    private View ring;
    private ObjectAnimator edgeWave;
    private Notice notice;
    private Signal previewAfterPermission;
    private MediaPlayer tonePreview;
    private int surface, panel, subtle, ink, quiet, line, accent, accentPanel, amber, amberPanel;
    private final Runnable redraw = () -> {
        if (touchActive) {
            redrawAfterTouch = true;
            return;
        }
        redrawAfterTouch = false;
        render(false);
    };
    private final Runnable calmEdge = () -> {
        if (edgeWave != null) {
            edgeWave.cancel();
            edgeWave = null;
        }
        if (ring != null && notice != null) {
            ring.setAlpha(1);
            paintEdge(ring, notice.signal, false);
        }
    };

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            if (!visible) return;
            long now = SystemClock.elapsedRealtime();
            List<String> lost = new ArrayList<>();
            for (DeviceStore.Device device : store.devices) {
                if (device.events.takeOffline(now)) lost.add(device.name);
                poll(device, false);
            }
            if (!lost.isEmpty()) signal(Signal.OFFLINE,
                    lost.size() == 1 ? lost.get(0) : lost.size() + " 台设备", TextUtils.join("、", lost), false);
            if (!store.devices.isEmpty() && (!settings || !lost.isEmpty())) scheduleRender();
            main.postDelayed(this, 3000);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        preferences = getSharedPreferences("settings", MODE_PRIVATE);
        store = new DeviceStore(preferences);
        scope = preferences.getString("scope", "all");
        settings = state != null && state.getBoolean("settings");
        discovery = new Discovery(this, (id, address) -> {
            DeviceStore.Device device = store.find(id);
            if (device != null && !address.equals(device.endpoint)) {
                device.discovered = address;
                poll(device, false);
            }
        });
        initNotifications();
        applyAwake();
        getWindow().setBackgroundDrawable(windowSurface);
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams attrs = getWindow().getAttributes();
            attrs.layoutInDisplayCutoutMode = Build.VERSION.SDK_INT >= 30
                    ? WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    : WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(attrs);
        }
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 29) {
            getWindow().setStatusBarContrastEnforced(false);
            getWindow().setNavigationBarContrastEnforced(false);
        }
        frame = new FrameLayout(this);
        // The display border stays outside the inset container used by the controls.
        contentFrame = new FrameLayout(this);
        frame.addView(contentFrame, new FrameLayout.LayoutParams(-1, -1));
        setContentView(frame);
        applyDirection();
        render(true);
    }

    @Override protected void onStart() {
        super.onStart();
        visible = true;
        for (DeviceStore.Device device : store.devices) {
            device.events.startMonitoring();
            device.lastNetworkError = null;
        }
        render(false);
        discovery.start();
        main.post(refresh);
    }

    @Override protected void onStop() {
        visible = false;
        main.removeCallbacks(refresh);
        main.removeCallbacks(redraw);
        notice = null;
        clearEdge();
        touchActive = redrawAfterTouch = false;
        releaseTone();
        discovery.stop();
        super.onStop();
    }

    @Override protected void onDestroy() {
        network.shutdownNow();
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("settings", settings);
        super.onSaveInstanceState(state);
    }

    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        render(true);
    }

    @Override public void onBackPressed() {
        if (settings) showSettings(false);
        else super.onBackPressed();
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) touchActive = true;
        boolean handled = super.dispatchTouchEvent(event);
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            touchActive = false;
            if (redrawAfterTouch) scheduleRender();
        }
        return handled;
    }

    private void applyAwake() {
        int flag = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
        if (preferences.getBoolean("awake", true)) getWindow().addFlags(flag);
        else getWindow().clearFlags(flag);
    }

    private void applyDirection() {
        String value = preferences.getString("direction", "system");
        int direction = "landscape".equals(value) ? ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                : "portrait".equals(value) ? ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                : ActivityInfo.SCREEN_ORIENTATION_USER;
        if (getRequestedOrientation() != direction) setRequestedOrientation(direction);
    }

    private void changeDirection(String value) {
        preferences.edit().putString("direction", value).apply();
        applyDirection();
        if (settings) render(false);
    }

    private boolean landscape() {
        return getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
    }

    private void showSettings(boolean open) {
        settings = open;
        render(true);
    }

    private void scheduleRender() {
        main.removeCallbacks(redraw);
        main.postDelayed(redraw, 32);
    }

    private void poll(DeviceStore.Device device, boolean verbose) {
        if (device.polling || device.checkingAddress || network.isShutdown()) return;
        device.polling = true;
        String known = device.endpoint;
        String next = device.discovered;
        int revision = device.endpointRevision;
        network.execute(() -> {
            JSONObject snapshot;
            String used = next == null ? known : next;
            try {
                try {
                    snapshot = CollectorClient.status(used, device.pin, device.token, device.id);
                } catch (Exception exception) {
                    if (used.equals(known)) throw exception;
                    used = known;
                    snapshot = CollectorClient.status(used, device.pin, device.token, device.id);
                }
                JSONObject data = snapshot;
                String endpoint = used;
                main.post(() -> {
                    device.polling = false;
                    if (isDestroyed() || store.find(device.id) != device || revision != device.endpointRevision) return;
                    if (next != null && next.equals(device.discovered)) device.discovered = null;
                    acceptStatus(device, endpoint, data, verbose);
                    if (visible && verbose) toast("连接正常");
                });
            } catch (Exception error) {
                final String failure = networkFailure(error);
                main.post(() -> {
                    device.polling = false;
                    if (isDestroyed() || store.find(device.id) != device || revision != device.endpointRevision) return;
                    if (!failure.equals(device.lastNetworkError)) {
                        android.util.Log.w("CodexStatus", "Status connection failed: " + failure);
                        device.lastNetworkError = failure;
                        if (visible) scheduleRender();
                    }
                    if (verbose && !isDestroyed()) toast("连接失败，请检查采集器和网络");
                });
            }
        });
    }

    private void acceptStatus(DeviceStore.Device device, String endpoint, JSONObject data, boolean refreshSettings) {
        long now = SystemClock.elapsedRealtime();
        boolean changed = !device.online(now) || device.snapshot == null
                || device.snapshot.optBoolean("sourceReady") != data.optBoolean("sourceReady")
                || device.snapshot.optBoolean("partial") != data.optBoolean("partial");
        device.endpoint = endpoint;
        device.snapshot = data;
        device.events.received(now);
        device.lastNetworkError = null;
        store.save();
        if (visible) {
            noticeCompletions(device);
            if (!settings || refreshSettings || changed) scheduleRender();
        }
    }

    private static String networkFailure(Exception error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        return error.getClass().getSimpleName() + " / " + root.getClass().getSimpleName();
    }

    private void noticeCompletions(DeviceStore.Device device) {
        JSONObject data = device.snapshot;
        if (!data.optBoolean("sourceReady") || data.optBoolean("partial")) return;
        List<DeviceEvents.Completion> fresh = device.events.observeCompletions(completions(data));
        if (fresh.isEmpty()) return;
        fresh.sort(Comparator.comparingLong((DeviceEvents.Completion entry) -> entry.at).reversed());
        String title = fresh.size() == 1 ? fresh.get(0).title : fresh.size() + " 个任务完成";
        signal(Signal.DONE, title, device.name, false);
    }

    private static List<DeviceEvents.Completion> completions(JSONObject data) {
        List<DeviceEvents.Completion> results = new ArrayList<>();
        JSONArray list = rows(data, "lastCompleted");
        for (int i = 0; i < list.length(); i++) {
            JSONObject item = list.optJSONObject(i);
            if (item == null) continue;
            String thread = item.optString("thread"), turn = item.optString("turn");
            long at = item.optLong("at");
            if (thread.isEmpty() || turn.isEmpty() || at <= 0) continue;
            results.add(new DeviceEvents.Completion(thread, turn, item.optString("title", "Codex 任务"), at));
        }
        return results;
    }

    private enum Signal {
        DONE("codex_completed", "任务完成", "notifyCompleted", R.raw.chime, 8102),
        OFFLINE("codex_offline", "设备掉线", "notifyOffline", R.raw.disconnect, 8103);

        final String channel, label, preference;
        final int sound, id;

        Signal(String channel, String label, String preference, int sound, int id) {
            this.channel = channel;
            this.label = label;
            this.preference = preference;
            this.sound = sound;
            this.id = id;
        }
    }

    private static final class Notice {
        final Signal signal;
        final String title, detail;

        Notice(Signal signal, String title, String detail) {
            this.signal = signal;
            this.title = title;
            this.detail = detail;
        }
    }

    private void initNotifications() {
        manager().cancel(8100);
        manager().cancel(8101);
        manager().deleteNotificationChannel("codex_attention");
        AudioAttributes attributes = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build();
        for (Signal kind : Signal.values()) {
            NotificationChannel channel = new NotificationChannel(kind.channel, kind.label, NotificationManager.IMPORTANCE_DEFAULT);
            channel.setSound(Uri.parse("android.resource://" + getPackageName() + "/" + kind.sound), attributes);
            manager().createNotificationChannel(channel);
        }
    }

    private NotificationManager manager() { return getSystemService(NotificationManager.class); }

    private boolean hasNotificationPermission() {
        return Build.VERSION.SDK_INT < 33
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean usesMediaAlerts() {
        return preferences.getBoolean("mediaAlertSound", false);
    }

    private void signal(Signal kind, String title, String detail, boolean test) {
        if (!visible || (!test && !preferences.getBoolean(kind.preference, true))) return;
        boolean light = preferences.getBoolean("edge", true);
        String heading = (test ? "测试 · " : "") + kind.label;
        String message = title.equals(detail) ? title : title + " · " + detail;
        if (light || test) {
            notice = new Notice(kind, heading, message);
            scheduleRender();
        }
        if (light) startEdge(kind);
        if (!preferences.getBoolean("sound", true)) return;
        boolean media = usesMediaAlerts();
        if (media) playTone(kind);
        if (!hasNotificationPermission()) return;
        if (media && manager().getNotificationChannel(QUIET_STATUS_CHANNEL) == null) {
            NotificationChannel status = new NotificationChannel(QUIET_STATUS_CHANNEL, "完成与掉线记录",
                    NotificationManager.IMPORTANCE_LOW);
            status.setSound(null, null);
            manager().createNotificationChannel(status);
        }
        Intent launch = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent open = PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, media ? QUIET_STATUS_CHANNEL : kind.channel)
                .setSmallIcon(R.drawable.ic_status).setContentTitle(heading).setContentText(message)
                .setStyle(new Notification.BigTextStyle().bigText(message))
                .setContentIntent(open).setAutoCancel(true).setTimeoutAfter(9500).build();
        manager().notify(test ? kind.id + 10 : kind.id, notification);
    }

    private void startEdge(Signal kind) {
        if (frame == null) return;
        clearEdge();
        View edge = new View(this);
        paintEdge(edge, kind, true);
        edge.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        frame.addView(edge, new FrameLayout.LayoutParams(-1, -1));
        ring = edge;
        if (ValueAnimator.areAnimatorsEnabled()) {
            edgeWave = ObjectAnimator.ofFloat(edge, View.ALPHA, 1f, .70f, 1f, .70f, 1f);
            edgeWave.setDuration(EDGE_EMPHASIS_MS);
            edgeWave.start();
        }
        main.postDelayed(calmEdge, EDGE_EMPHASIS_MS);
    }

    private void paintEdge(View edge, Signal kind, boolean bold) {
        int tint = kind == Signal.DONE ? color("#12A765") : color("#F67529");
        GradientDrawable halo = shape(Color.TRANSPARENT, 13);
        halo.setStroke(dp(bold ? 15 : 5), Color.argb(bold ? 77 : 54, Color.red(tint), Color.green(tint), Color.blue(tint)));
        GradientDrawable stroke = shape(Color.TRANSPARENT, 13);
        stroke.setStroke(dp(bold ? 8 : 2), tint);
        LayerDrawable layers = new LayerDrawable(new GradientDrawable[]{halo, stroke});
        layers.setPaddingMode(LayerDrawable.PADDING_MODE_STACK);
        edge.setBackground(layers);
    }

    private void clearEdge() {
        main.removeCallbacks(calmEdge);
        if (edgeWave != null) {
            edgeWave.cancel();
            edgeWave = null;
        }
        if (ring != null && ring.getParent() == frame) frame.removeView(ring);
        ring = null;
    }

    private void dismissNotice() {
        notice = null;
        clearEdge();
        render(false);
    }

    private void preview(Signal kind) {
        if (preferences.getBoolean("sound", true) && !usesMediaAlerts() && !hasNotificationPermission()) {
            previewAfterPermission = kind;
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFY);
            return;
        }
        playPreview(kind);
    }

    private void playPreview(Signal kind) {
        signal(kind, kind == Signal.DONE ? "示例任务" : "示例设备", "提醒测试", true);
    }

    private void chooseTone() {
        dialog().setTitle("试听提示音")
                .setItems(new String[]{"任务完成音", "设备掉线音"}, (unused, which) ->
                        playTone(which == 0 ? Signal.DONE : Signal.OFFLINE))
                .setNegativeButton("关闭", null).show();
    }

    private void playTone(Signal kind) {
        releaseTone();
        MediaPlayer player = MediaPlayer.create(this, kind.sound, MEDIA_ALERT_AUDIO, 0);
        if (player == null) {
            toast("提示音暂时无法播放");
            return;
        }
        tonePreview = player;
        player.setOnCompletionListener(finished -> {
            if (tonePreview == finished) releaseTone();
        });
        player.setOnErrorListener((failed, what, extra) -> {
            if (tonePreview == failed) {
                releaseTone();
                toast("提示音暂时无法播放");
            }
            return true;
        });
        try { player.start(); }
        catch (IllegalStateException error) {
            releaseTone();
            toast("提示音暂时无法播放");
        }
    }

    private void releaseTone() {
        MediaPlayer player = tonePreview;
        tonePreview = null;
        if (player != null) player.release();
    }

    private void addDevice() {
        dialog().setTitle("添加设备").setItems(new String[]{"扫码配对", "手动连接"}, (unused, choice) -> {
            if (choice == 1) manualPair();
            else if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) scan();
            else requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA);
        }).show();
    }

    private void scan() { startActivityForResult(new Intent(this, ScanActivity.class), SCAN); }

    @Override protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code != SCAN || result != RESULT_OK || data == null) return;
        try {
            JSONObject qr = new JSONObject(data.getStringExtra("pairing"));
            if (qr.optInt("v") != 1) throw new IllegalArgumentException();
            connect(qr.getString("endpoint"), qr.getString("code"), qr.getString("pin"));
        } catch (Exception error) { toast("请扫描 Windows 采集器的配对二维码"); }
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] permissionsResult) {
        super.onRequestPermissionsResult(request, permissions, permissionsResult);
        boolean granted = permissionsResult.length > 0 && permissionsResult[0] == PackageManager.PERMISSION_GRANTED;
        if (request == CAMERA) {
            if (granted) scan();
            else toast("可以在添加设备中手动连接");
        }
        if (request == NOTIFY && previewAfterPermission != null) {
            Signal kind = previewAfterPermission;
            previewAfterPermission = null;
            main.post(() -> playPreview(kind));
        }
    }

    private void manualPair() {
        LinearLayout fields = column();
        fields.setPadding(dp(20), dp(8), dp(20), 0);
        EditText address = field("设备地址（IP:端口）", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        EditText code = field("8 位配对码", InputType.TYPE_CLASS_NUMBER);
        EditText pin = field("校验指纹", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        fields.addView(address); fields.addView(code); fields.addView(pin);
        ScrollView form = new ScrollView(this);
        form.addView(fields, new ScrollView.LayoutParams(-1, -2));
        AlertDialog manual = dialog().setTitle("手动连接").setView(form).setNegativeButton("取消", null)
                .setPositiveButton("连接", (unused, which) -> connect(address.getText().toString(),
                        code.getText().toString().trim(), pin.getText().toString())).create();
        manual.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        manual.show();
    }

    private void connect(String address, String code, String pin) {
        if (pairing) return;
        pairing = true;
        toast("正在连接采集器…");
        network.execute(() -> {
            try {
                CollectorClient.HostPair pair = CollectorClient.pair(address, code, pin);
                main.post(() -> {
                    pairing = false;
                    if (isDestroyed()) return;
                    DeviceStore.Device device = store.put(pair);
                    settings = false;
                    setScope("all");
                    poll(device, false);
                    toast("设备已配对");
                    if (!hasNotificationPermission() && Build.VERSION.SDK_INT >= 33
                            && !preferences.getBoolean("notificationAsked", false)) {
                        preferences.edit().putBoolean("notificationAsked", true).apply();
                        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFY);
                    }
                });
            } catch (Exception error) {
                android.util.Log.w("CodexStatus", "Pairing failed: " + networkFailure(error));
                main.post(() -> {
                    pairing = false;
                    if (!isDestroyed()) {
                        if (error instanceof javax.net.ssl.SSLException) toast("证书校验失败，请核对电脑配对页的指纹");
                        else if (error instanceof java.net.SocketTimeoutException || error instanceof java.net.ConnectException)
                            toast("采集器连接超时，请检查地址和局域网");
                        else toast("配对失败，请检查地址、配对码和校验指纹");
                    }
                });
            }
        });
    }

    private void setScope(String id) {
        scope = id;
        preferences.edit().putString("scope", id).apply();
        render(true);
    }

    private void chooseScope() {
        List<String> names = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        names.add("全部设备"); ids.add("all");
        for (DeviceStore.Device device : store.devices) {
            names.add(device.name + (device.online(SystemClock.elapsedRealtime()) ? "" : " · 离线"));
            ids.add(device.id);
        }
        dialog().setTitle("查看设备").setSingleChoiceItems(names.toArray(new String[0]), ids.indexOf(scope), (dialog, index) -> {
            dialog.dismiss();
            setScope(ids.get(index));
        }).setNegativeButton("取消", null).show();
    }

    private void manage(DeviceStore.Device device) {
        dialog().setTitle(device.name).setItems(new String[]{"修改名称", "检查连接", "修改连接地址", "移除设备"}, (unused, action) -> {
            if (action == 0) {
                EditText name = field("设备名称", InputType.TYPE_CLASS_TEXT);
                name.setText(device.name);
                dialog().setTitle("修改名称").setView(name).setNegativeButton("取消", null)
                        .setPositiveButton("保存", (ignored, which) -> {
                            String text = name.getText().toString().trim();
                            if (!text.isEmpty()) {
                                device.name = text.substring(0, Math.min(text.length(), 32));
                                store.save(); render(false);
                            }
                        }).show();
            } else if (action == 1) poll(device, true);
            else if (action == 2) editAddress(device);
            else dialog().setTitle("移除 " + device.name + "？").setNegativeButton("取消", null)
                    .setPositiveButton("移除", (ignored, which) -> {
                        store.devices.remove(device);
                        store.save();
                        if (scope.equals(device.id)) setScope("all");
                        else render(false);
                    }).show();
        }).show();
    }

    private void editAddress(DeviceStore.Device device) {
        LinearLayout form = column();
        form.setPadding(dp(20), dp(4), dp(20), 0);
        EditText address = field("设备地址（IP:端口）", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        address.setText(device.endpoint.replaceFirst("^https://", ""));
        form.addView(address);
        dialog().setTitle("修改连接地址").setView(form).setNegativeButton("取消", null)
                .setPositiveButton("验证并保存", (unused, which) -> changeAddress(device, address.getText().toString())).show();
    }

    private void changeAddress(DeviceStore.Device device, String input) {
        if (device.checkingAddress || network.isShutdown()) return;
        final String endpoint;
        try { endpoint = CollectorClient.endpoint(input); }
        catch (Exception error) { toast("请输入新的设备 IP 和端口"); return; }
        device.checkingAddress = true;
        toast("正在验证设备身份…");
        network.execute(() -> {
            try {
                JSONObject data = CollectorClient.status(endpoint, device.pin, device.token, device.id);
                main.post(() -> {
                    device.checkingAddress = false;
                    if (isDestroyed() || store.find(device.id) != device) return;
                    device.endpointRevision++;
                    device.discovered = null;
                    acceptStatus(device, endpoint, data, true);
                    if (visible) toast("连接地址已保存");
                });
            } catch (Exception error) {
                main.post(() -> {
                    device.checkingAddress = false;
                    if (visible && store.find(device.id) == device) toast("新地址未能验证原来的设备");
                });
            }
        });
    }

    private boolean wide() {
        return landscape() && getResources().getConfiguration().screenWidthDp >= 520;
    }

    private void palette() {
        String theme = preferences.getString("theme", "system");
        boolean systemDark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        dark = "dark".equals(theme) || "system".equals(theme) && systemDark;
        surface = color(dark ? "#101815" : "#EDF2EE");
        panel = color(dark ? "#18231E" : "#FFFFFF");
        subtle = color(dark ? "#202E27" : "#F2F6F3");
        ink = color(dark ? "#E9F5ED" : "#172921");
        quiet = color(dark ? "#A4B9AE" : "#4C6459");
        line = color(dark ? "#2D4136" : "#D8E4DB");
        accent = color(dark ? "#91E5B9" : "#226B4F");
        accentPanel = color(dark ? "#234135" : "#DDF1E5");
        amber = color(dark ? "#FFB697" : "#A34728");
        amberPanel = color(dark ? "#463126" : "#FFECE2");
    }

    private void render(boolean resetScroll) {
        if (isDestroyed() || frame == null) return;
        int position = resetScroll || scroll == null ? 0 : scroll.getScrollY();
        int left = resetScroll || leftScroll == null ? 0 : leftScroll.getScrollY();
        int right = resetScroll || rightScroll == null ? 0 : rightScroll.getScrollY();
        scroll = leftScroll = rightScroll = null;
        palette();
        windowSurface.setColor(surface);
        frame.setBackgroundColor(surface);
        contentFrame.removeAllViews();
        LinearLayout app = column();
        int margin = dp(wide() ? 16 : 12);
        app.setPadding(margin, 0, margin, dp(wide() ? 10 : 12));
        contentFrame.addView(app, new FrameLayout.LayoutParams(-1, -1));
        app.addView(toolbar(), new LinearLayout.LayoutParams(-1, -2));
        if (!settings && !wide() && !store.devices.isEmpty()) {
            LinearLayout devices = row();
            View selector = deviceSelector();
            devices.addView(selector, new LinearLayout.LayoutParams(0, dp(43), 1));
            LinearLayout.LayoutParams badge = new LinearLayout.LayoutParams(-2, dp(43));
            badge.leftMargin = dp(9);
            devices.addView(onlineBadge(), badge);
            add(app, devices, 0);
        }
        if (notice != null) add(app, noticeBar(), 6);
        if (settings) renderSettings(app);
        else if (store.devices.isEmpty()) renderEmpty(app);
        else renderStatus(app);
        applyWindowMode();
        restoreScroll(scroll, position);
        restoreScroll(leftScroll, left);
        restoreScroll(rightScroll, right);
    }

    private LinearLayout toolbar() {
        LinearLayout header = row();
        header.setMinimumHeight(dp(wide() ? 51 : 56));
        if (settings) {
            header.addView(icon(Glyph.Icon.BACK, "返回状态屏", () -> showSettings(false)), new LinearLayout.LayoutParams(dp(48), dp(48)));
        } else {
            ImageView brand = new ImageView(this);
            brand.setImageResource(R.drawable.ic_status);
            brand.setScaleType(ImageView.ScaleType.FIT_XY);
            brand.setBackground(shape(color("#1C2B29"), 8));
            brand.setClipToOutline(true);
            brand.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            header.addView(brand, new LinearLayout.LayoutParams(dp(30), dp(30)));
        }
        TextView title = oneLine(settings ? "设置" : "Codex 状态", wide() ? 20 : 21, ink, true);
        LinearLayout.LayoutParams label = new LinearLayout.LayoutParams(0, -2, 1);
        label.leftMargin = dp(settings ? 5 : 9);
        header.addView(title, label);
        if (settings) return header;

        if (wide() && !store.devices.isEmpty()) {
            LinearLayout.LayoutParams chooser = new LinearLayout.LayoutParams(-2, dp(40));
            chooser.rightMargin = dp(8);
            header.addView(deviceSelector(), chooser);
            LinearLayout.LayoutParams badge = new LinearLayout.LayoutParams(-2, dp(40));
            badge.rightMargin = dp(7);
            header.addView(onlineBadge(), badge);
        }
        header.addView(icon(dark ? Glyph.Icon.SUN : Glyph.Icon.MOON, dark ? "切换到浅色" : "切换到深色",
                () -> changeTheme(dark ? "light" : "dark")), new LinearLayout.LayoutParams(dp(48), dp(48)));
        header.addView(icon(Glyph.Icon.ROTATE, landscape() ? "切换到竖屏" : "切换到横屏",
                () -> changeDirection(landscape() ? "portrait" : "landscape")), new LinearLayout.LayoutParams(dp(48), dp(48)));
        header.addView(icon(Glyph.Icon.SETTINGS, "设置", () -> showSettings(true)), new LinearLayout.LayoutParams(dp(48), dp(48)));
        return header;
    }

    private void changeTheme(String mode) {
        preferences.edit().putString("theme", mode).apply();
        render(false);
    }

    private List<DeviceStore.Device> selectedDevices() {
        DeviceStore.Device chosen = store.find(scope);
        return chosen == null ? store.devices : Collections.singletonList(chosen);
    }

    private View deviceSelector() {
        DeviceStore.Device chosen = store.find(scope);
        String name = chosen == null ? "全部设备" : chosen.name;
        LinearLayout selector = row();
        selector.setPadding(dp(10), 0, dp(8), 0);
        selector.addView(glyph(Glyph.Icon.DEVICE, quiet), new LinearLayout.LayoutParams(dp(18), dp(18)));
        TextView label = oneLine(name, 13, ink, true);
        label.setMaxWidth(dp(wide() ? 129 : 156));
        LinearLayout.LayoutParams title = new LinearLayout.LayoutParams(-2, -2);
        title.leftMargin = dp(6);
        selector.addView(label, title);
        LinearLayout.LayoutParams arrow = new LinearLayout.LayoutParams(dp(18), dp(18));
        arrow.leftMargin = dp(5);
        selector.addView(glyph(Glyph.Icon.CHEVRON, quiet), arrow);
        selector.setBackground(ripple(panel, 10, true));
        selector.setContentDescription("查看设备 · " + name);
        selector.setOnClickListener(view -> chooseScope());
        return selector;
    }

    private View onlineBadge() {
        List<DeviceStore.Device> selected = selectedDevices();
        long now = SystemClock.elapsedRealtime();
        long online = selected.stream().filter(device -> device.online(now)).count();
        boolean disconnected = selected.stream().anyMatch(device -> !device.online(now)
                && (device.events.hasResponse() || device.lastNetworkError != null));
        int tint = online == selected.size() ? accent : disconnected ? amber : quiet;
        LinearLayout badge = row();
        badge.setPadding(dp(10), 0, dp(10), 0);
        badge.setBackground(shape(online == selected.size() ? accentPanel : disconnected ? amberPanel : subtle, 10));
        badge.addView(dot(tint), new LinearLayout.LayoutParams(dp(6), dp(6)));
        LinearLayout.LayoutParams weight = new LinearLayout.LayoutParams(-2, -2);
        weight.leftMargin = dp(6);
        badge.addView(oneLine(online + " / " + selected.size() + " 台在线", 12, tint, true), weight);
        return badge;
    }

    private View noticeBar() {
        boolean finished = notice.signal == Signal.DONE;
        LinearLayout alert = row();
        alert.setMinimumHeight(dp(48));
        alert.setPadding(dp(11), 0, dp(6), 0);
        alert.setBackground(shape(finished ? color("#176344") : color("#963B21"), 10));
        alert.addView(glyph(finished ? Glyph.Icon.CHECK : Glyph.Icon.DISCONNECT, Color.WHITE), new LinearLayout.LayoutParams(dp(20), dp(20)));
        LinearLayout.LayoutParams info = new LinearLayout.LayoutParams(-2, -2);
        info.leftMargin = dp(7);
        alert.addView(oneLine(notice.title, 13, Color.WHITE, true), info);
        LinearLayout.LayoutParams detail = new LinearLayout.LayoutParams(0, -2, 1);
        detail.leftMargin = dp(7);
        alert.addView(oneLine(notice.detail, 12, color("#F7F5EC"), false), detail);
        TextView acknowledge = oneLine("知道了", 12, Color.WHITE, true);
        acknowledge.setGravity(Gravity.CENTER);
        acknowledge.setPadding(dp(8), 0, dp(8), 0);
        acknowledge.setBackground(ripple(0x32FFFFFF, 7, false));
        acknowledge.setOnClickListener(view -> dismissNotice());
        LinearLayout.LayoutParams dismiss = new LinearLayout.LayoutParams(-2, dp(42));
        dismiss.leftMargin = dp(5);
        alert.addView(acknowledge, dismiss);
        alert.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        return alert;
    }

    private void applyWindowMode() {
        boolean immersive = !settings;
        frame.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                Insets safe = immersive ? insets.getInsetsIgnoringVisibility(WindowInsets.Type.displayCutout())
                        : insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                contentFrame.setPadding(safe.left, safe.top, safe.right, safe.bottom);
            } else {
                android.view.DisplayCutout cutout = Build.VERSION.SDK_INT >= 28 ? insets.getDisplayCutout() : null;
                int left = cutout == null ? 0 : cutout.getSafeInsetLeft();
                int top = cutout == null ? 0 : cutout.getSafeInsetTop();
                int right = cutout == null ? 0 : cutout.getSafeInsetRight();
                int bottom = cutout == null ? 0 : cutout.getSafeInsetBottom();
                if (!immersive) {
                    left = Math.max(left, insets.getSystemWindowInsetLeft());
                    top = Math.max(top, insets.getSystemWindowInsetTop());
                    right = Math.max(right, insets.getSystemWindowInsetRight());
                    bottom = Math.max(bottom, insets.getSystemWindowInsetBottom());
                }
                // Some older devices already letterbox this window around the cutout and bars.
                int[] origin = new int[2];
                view.getLocationOnScreen(origin);
                left = Math.max(0, left - Math.max(origin[0], 0));
                top = Math.max(0, top - Math.max(origin[1], 0));
                if (view.getWidth() > 0 && view.getHeight() > 0) {
                    Point display = new Point();
                    getWindowManager().getDefaultDisplay().getRealSize(display);
                    right = Math.max(0, right - Math.max(0, display.x - origin[0] - view.getWidth()));
                    bottom = Math.max(0, bottom - Math.max(0, display.y - origin[1] - view.getHeight()));
                }
                contentFrame.setPadding(left, top, right, bottom);
            }
            return insets;
        });
        View decor = getWindow().getDecorView();
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = decor.getWindowInsetsController();
            if (controller != null) {
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                if (immersive) controller.hide(WindowInsets.Type.systemBars());
                else {
                    controller.show(WindowInsets.Type.systemBars());
                    int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                    controller.setSystemBarsAppearance(dark ? 0 : mask, mask);
                }
            }
        } else {
            int layout = View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
            int light = dark ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            int bars = immersive ? View.SYSTEM_UI_FLAG_FULLSCREEN |
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY : light;
            decor.setSystemUiVisibility(layout | bars);
        }
        frame.requestApplyInsets();
    }

    private void renderEmpty(LinearLayout app) {
        LinearLayout empty = section();
        empty.setGravity(Gravity.CENTER);
        ImageView monitor = glyph(Glyph.Icon.DEVICE, accent);
        empty.addView(monitor, new LinearLayout.LayoutParams(dp(34), dp(34)));
        TextView title = text("还没有连接设备", 20, ink, true);
        title.setGravity(Gravity.CENTER);
        add(empty, title, 10);
        TextView instructions = text("打开 Windows 采集器的配对页，在手机上扫码或手动连接。", 14, quiet, false);
        instructions.setGravity(Gravity.CENTER);
        add(empty, instructions, 4);
        Button start = button("添加设备", this::addDevice, true);
        LinearLayout.LayoutParams action = new LinearLayout.LayoutParams(-2, -2);
        action.topMargin = dp(12);
        empty.addView(start, action);
        content(app, empty, 6);
    }

    private void renderSettings(LinearLayout app) {
        LinearLayout devices = section();
        LinearLayout top = row();
        TextView heading = text("设备与连接", 17, ink, true);
        top.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        top.addView(icon(Glyph.Icon.PLUS, "添加设备", this::addDevice), new LinearLayout.LayoutParams(dp(43), dp(46)));
        devices.addView(top);
        long now = SystemClock.elapsedRealtime();
        if (store.devices.isEmpty()) add(devices, text("还没有配对设备", 14, quiet, false), 7);
        for (DeviceStore.Device device : store.devices) add(devices, deviceRow(device, now, true), 5);

        LinearLayout display = section();
        add(display, text("显示与提醒", 17, ink, true), 5);
        add(display, text("外观", 13, quiet, true), 9);
        add(display, choices(new String[]{"系统", "浅色", "深色"}, new String[]{"system", "light", "dark"},
                preferences.getString("theme", "system"), this::changeTheme), 4);
        add(display, text("屏幕方向", 13, quiet, true), 10);
        add(display, choices(new String[]{"系统", "竖屏", "横屏"}, new String[]{"system", "portrait", "landscape"},
                preferences.getString("direction", "system"), this::changeDirection), 4);
        display.addView(toggle("保持亮屏", "awake", this::applyAwake));
        LinearLayout reminders = row();
        reminders.addView(text("声光提醒", 13, quiet, true), new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout trial = row();
        trial.setPadding(dp(8), 0, dp(9), 0);
        trial.addView(glyph(Glyph.Icon.SOUND, accent), new LinearLayout.LayoutParams(dp(17), dp(17)));
        LinearLayout.LayoutParams caption = new LinearLayout.LayoutParams(-2, -2);
        caption.leftMargin = dp(5);
        trial.addView(oneLine("试听", 12, accent, true), caption);
        trial.setBackground(ripple(accentPanel, 8, false));
        trial.setContentDescription("试听提示音");
        trial.setOnClickListener(view -> chooseTone());
        reminders.addView(trial, new LinearLayout.LayoutParams(-2, dp(44)));
        add(display, reminders, 3);
        add(display, pairedControls(toggle("任务完成", "notifyCompleted", null), toggle("设备掉线", "notifyOffline", null)), 1);
        add(display, pairedControls(toggle("提示音", "sound", null), toggle("边缘亮起", "edge", () -> {
            if (!preferences.getBoolean("edge", true)) dismissNotice();
        })), 0);
        add(display, toggle("用媒体音量提醒", "mediaAlertSound", null, false), 0);
        add(display, pairedControls(button("测试完成", () -> preview(Signal.DONE), false),
                button("测试掉线", () -> preview(Signal.OFFLINE), false)), 6);
        showColumns(app, devices, display, .43f);
    }

    private interface ChoiceAction { void select(String value); }

    private LinearLayout choices(String[] labels, String[] values, String selected, ChoiceAction action) {
        LinearLayout group = row();
        group.setPadding(dp(3), dp(3), dp(3), dp(3));
        group.setBackground(shape(subtle, 10));
        for (int i = 0; i < values.length; i++) {
            String value = values[i];
            boolean active = selected.equals(value);
            TextView choice = oneLine(labels[i], 13, active ? accent : quiet, true);
            choice.setSelected(active);
            choice.setGravity(Gravity.CENTER);
            choice.setBackground(ripple(active ? accentPanel : subtle, 8, false));
            choice.setOnClickListener(view -> action.select(value));
            group.addView(choice, new LinearLayout.LayoutParams(0, dp(39), 1));
        }
        return group;
    }

    private LinearLayout pairedControls(View left, View right) {
        LinearLayout group = row();
        group.addView(left, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams second = new LinearLayout.LayoutParams(0, -2, 1);
        second.leftMargin = dp(10);
        group.addView(right, second);
        return group;
    }

    private void renderStatus(LinearLayout app) {
        long now = SystemClock.elapsedRealtime();
        long wall = System.currentTimeMillis();
        String day = LocalDate.now().toString();
        List<DeviceStore.Device> selected = selectedDevices();
        List<Running> running = new ArrayList<>();
        Map<String, Finished> byConversation = new LinkedHashMap<>();
        boolean ready = false, doneKnown = false;
        int doneCount = 0;
        for (DeviceStore.Device device : selected) {
            JSONObject status = device.snapshot;
            if (status == null) continue;
            if (day.equals(status.optString("day")) && status.optLong("observedAt") > 0) {
                doneKnown = true;
                doneCount += status.optInt("completedCount");
                for (DeviceEvents.Completion completion : completions(status)) {
                    String key = device.id + "/" + completion.thread;
                    byConversation.computeIfAbsent(key, unused -> new Finished(device.name, completion)).add(completion);
                }
            }
            if (!device.online(now) || !status.optBoolean("sourceReady")) continue;
            ready = true;
            JSONArray tasks = rows(status, "tasks");
            for (int i = 0; i < tasks.length(); i++) {
                JSONObject task = tasks.optJSONObject(i);
                if (task != null) running.add(new Running(device.name, task));
            }
        }
        running.sort(Comparator.comparingLong((Running item) -> item.task.optLong("update")).reversed());
        List<Finished> finished = new ArrayList<>(byConversation.values());
        finished.sort(Comparator.comparingLong((Finished item) -> item.latest.at).reversed());

        LinearLayout active = section();
        active.addView(sectionHeading(Glyph.Icon.ACTIVITY, "运行中", ready ? String.valueOf(running.size()) : "—"));
        for (Running item : running) add(active, taskRow(item, wall), 6);
        if (running.isEmpty()) add(active, text(ready ? "暂无运行任务" : "等待可读的任务状态", 14, quiet, false), 10);
        addDivider(active, 11);
        add(active, text("设备状态", 13, quiet, true), 8);
        for (DeviceStore.Device device : selected) add(active, deviceRow(device, now, false), 4);

        LinearLayout done = section();
        done.addView(sectionHeading(Glyph.Icon.CHECK, "今日完成", doneKnown ? String.valueOf(doneCount) : "—"));
        if (finished.isEmpty()) add(done, text(doneKnown ? "今天还没有完成记录" : "等待完成记录", 14, quiet, false), 9);
        int limit = Math.min(finished.size(), 24);
        for (int i = 0; i < limit; i++) {
            if (i != 0) addDivider(done, 0);
            done.addView(finishedRow(finished.get(i)), new LinearLayout.LayoutParams(-1, -2));
        }
        if (finished.size() > limit) add(done, text("显示最近完成的 " + limit + " 个会话", 12, quiet, false), 7);
        showColumns(app, active, done, .54f);
    }

    private static final class Running {
        final String device;
        final JSONObject task;
        Running(String device, JSONObject task) { this.device = device; this.task = task; }
    }

    private static final class Finished {
        final String device;
        final List<Long> times = new ArrayList<>();
        DeviceEvents.Completion latest;

        Finished(String device, DeviceEvents.Completion entry) {
            this.device = device;
            this.latest = entry;
        }

        void add(DeviceEvents.Completion entry) {
            if (entry.at > latest.at) latest = entry;
            times.add(entry.at);
        }
    }

    private LinearLayout sectionHeading(Glyph.Icon symbol, String title, String count) {
        LinearLayout head = row();
        head.setMinimumHeight(dp(43));
        ImageView mark = glyph(symbol, accent);
        mark.setPadding(dp(6), dp(6), dp(6), dp(6));
        mark.setBackground(shape(accentPanel, 9));
        head.addView(mark, new LinearLayout.LayoutParams(dp(32), dp(32)));
        LinearLayout.LayoutParams name = new LinearLayout.LayoutParams(0, -2, 1);
        name.leftMargin = dp(9);
        head.addView(oneLine(title, 16, ink, true), name);
        TextView metric = oneLine(count, 30, "—".equals(count) ? quiet : accent, true);
        metric.setFontFeatureSettings("tnum");
        head.addView(metric);
        return head;
    }

    private View taskRow(Running entry, long wall) {
        JSONObject task = entry.task;
        String title = task.optString("title", "Codex 任务");
        String stage = task.optString("stage", "处理中");
        long start = task.optLong("start"), update = task.optLong("update");
        String elapsed = start > 0 ? duration(wall - start) : "";
        String lastActivity = update > 0 ? duration(wall - update) + "前更新" : "";
        LinearLayout card = column();
        card.setMinimumHeight(dp(66));
        card.setPadding(dp(10), dp(8), dp(9), dp(8));
        card.setBackground(ripple(subtle, 11, false));
        LinearLayout line1 = row();
        line1.addView(oneLine(title, 15, ink, true), new LinearLayout.LayoutParams(0, -2, 1));
        if (!elapsed.isEmpty()) {
            TextView time = oneLine("已用 " + elapsed, 13, accent, true);
            time.setFontFeatureSettings("tnum");
            LinearLayout.LayoutParams trailing = new LinearLayout.LayoutParams(-2, -2);
            trailing.leftMargin = dp(7);
            line1.addView(time, trailing);
        }
        card.addView(line1);
        LinearLayout meta = row();
        TextView badge = oneLine(stage, 11, accent, true);
        badge.setPadding(dp(6), dp(2), dp(6), dp(2));
        badge.setBackground(shape(accentPanel, 5));
        meta.addView(badge);
        LinearLayout.LayoutParams others = new LinearLayout.LayoutParams(0, -2, 1);
        others.leftMargin = dp(6);
        meta.addView(oneLine(entry.device + (lastActivity.isEmpty() ? "" : " · " + lastActivity), 11, quiet, false), others);
        add(card, meta, 5);
        card.setOnClickListener(view -> dialog().setTitle(title).setMessage(entry.device + "\n" + stage
                + (elapsed.isEmpty() ? "" : " · 已用时 " + elapsed) + (lastActivity.isEmpty() ? "" : "\n" + lastActivity))
                .setPositiveButton("关闭", null).show());
        return card;
    }

    private View finishedRow(Finished item) {
        LinearLayout record = row();
        record.setMinimumHeight(dp(43));
        record.setPadding(dp(3), dp(4), dp(3), dp(4));
        record.setBackground(ripple(panel, 8, false));
        TextView at = oneLine(clock(item.latest.at), 12, accent, true);
        at.setFontFeatureSettings("tnum");
        record.addView(at, new LinearLayout.LayoutParams(dp(46), -2));
        LinearLayout task = column();
        task.addView(oneLine(item.latest.title, 14, ink, true));
        task.addView(oneLine(item.device + (item.times.size() == 1 ? "" : " · 近期 " + item.times.size() + " 次"), 11, quiet, false));
        record.addView(task, new LinearLayout.LayoutParams(0, -2, 1));
        record.setOnClickListener(view -> {
            item.times.sort(Comparator.reverseOrder());
            List<String> history = new ArrayList<>();
            for (long timestamp : item.times) history.add(clock(timestamp));
            dialog().setTitle(item.latest.title)
                    .setMessage(item.device + "\n近期完成 · " + TextUtils.join("、", history))
                    .setPositiveButton("关闭", null).show();
        });
        return record;
    }

    private View deviceRow(DeviceStore.Device device, long now, boolean editable) {
        boolean online = device.online(now);
        boolean connecting = !device.events.hasResponse() && device.lastNetworkError == null;
        boolean sourceReady = device.snapshot != null && device.snapshot.optBoolean("sourceReady");
        String label = online ? (sourceReady ? (device.snapshot.optBoolean("partial") ? "部分日志可读" : "在线") : "等待日志")
                : connecting ? "连接中" : "离线";
        int tint = online && sourceReady ? accent : connecting ? quiet : amber;
        long updated = device.snapshot == null ? 0 : device.snapshot.optLong("observedAt");
        String sync = updated > 0 ? "同步 " + clock(updated) : "";
        LinearLayout item = row();
        item.setMinimumHeight(dp(editable ? 49 : 37));
        item.setPadding(dp(8), dp(3), dp(8), dp(3));
        item.setBackground(editable ? ripple(subtle, 9, false) : shape(subtle, 8));
        item.addView(dot(tint), new LinearLayout.LayoutParams(dp(6), dp(6)));
        LinearLayout content = column();
        content.addView(oneLine(device.name, 13, ink, true));
        if (editable) content.addView(oneLine(device.endpoint.replaceFirst("^https://", ""), 11, quiet, false));
        LinearLayout.LayoutParams info = new LinearLayout.LayoutParams(0, -2, 1);
        info.leftMargin = dp(8);
        item.addView(content, info);
        item.addView(oneLine(label + (sync.isEmpty() ? "" : " · " + sync), 11, tint, true));
        if (editable) item.setOnClickListener(view -> manage(device));
        return item;
    }

    private void showColumns(LinearLayout parent, LinearLayout left, LinearLayout right, float leftShare) {
        if (wide()) {
            LinearLayout grid = row();
            grid.setGravity(Gravity.TOP);
            leftScroll = scrollBelowHeading(left);
            rightScroll = scrollBelowHeading(right);
            grid.addView(left, new LinearLayout.LayoutParams(0, -1, leftShare));
            LinearLayout.LayoutParams second = new LinearLayout.LayoutParams(0, -1, 1 - leftShare);
            second.leftMargin = dp(10);
            grid.addView(right, second);
            content(parent, grid, 6);
        } else {
            LinearLayout stack = column();
            stack.addView(left, new LinearLayout.LayoutParams(-1, -2));
            add(stack, right, 9);
            scroll = scrolling(stack);
            content(parent, scroll, 7);
        }
    }

    private ScrollView scrollBelowHeading(LinearLayout card) {
        LinearLayout records = column();
        while (card.getChildCount() > 1) {
            View item = card.getChildAt(1);
            LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) item.getLayoutParams();
            card.removeViewAt(1);
            records.addView(item, params);
        }
        ScrollView area = scrolling(records);
        card.addView(area, new LinearLayout.LayoutParams(-1, 0, 1));
        return area;
    }

    private ScrollView scrolling(View inner) {
        ScrollView area = new ScrollView(this);
        area.addView(inner, new ScrollView.LayoutParams(-1, -2));
        return area;
    }

    private void content(LinearLayout parent, View child, int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, 0, 1);
        params.topMargin = dp(top);
        parent.addView(child, params);
    }

    private void restoreScroll(ScrollView area, int position) {
        if (area != null && position > 0) area.post(() -> area.scrollTo(0, position));
    }

    private LinearLayout section() {
        LinearLayout result = column();
        result.setPadding(dp(12), dp(8), dp(12), dp(9));
        GradientDrawable tone = shape(panel, 14);
        tone.setStroke(dp(1), line);
        result.setBackground(tone);
        return result;
    }

    private void addDivider(LinearLayout parent, int margin) {
        View rule = new View(this);
        rule.setBackgroundColor(line);
        LinearLayout.LayoutParams bounds = new LinearLayout.LayoutParams(-1, dp(1));
        bounds.topMargin = dp(margin);
        parent.addView(rule, bounds);
    }

    private View dot(int tint) {
        View marker = new View(this);
        marker.setBackground(shape(tint, 12));
        marker.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return marker;
    }

    private ImageView glyph(Glyph.Icon image, int tint) {
        ImageView view = new ImageView(this);
        view.setImageDrawable(new Glyph(image, tint));
        view.setScaleType(ImageView.ScaleType.FIT_XY);
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return view;
    }

    private ImageButton icon(Glyph.Icon image, String label, Runnable action) {
        ImageButton button = new ImageButton(this);
        button.setImageDrawable(new Glyph(image, ink));
        button.setScaleType(ImageView.ScaleType.FIT_XY);
        button.setPadding(dp(11), dp(12), dp(11), dp(12));
        button.setBackground(ripple(surface, 10, false));
        button.setContentDescription(label);
        button.setTooltipText(label);
        button.setOnClickListener(view -> action.run());
        return button;
    }

    private RippleDrawable ripple(int fill, int radius, boolean framed) {
        GradientDrawable base = shape(fill, radius);
        if (framed) base.setStroke(dp(1), line);
        int touch = Color.argb(dark ? 36 : 22, Color.red(ink), Color.green(ink), Color.blue(ink));
        return new RippleDrawable(ColorStateList.valueOf(touch), base, null);
    }

    private TextView oneLine(String value, int size, int foreground, boolean bold) {
        TextView label = text(value, size, foreground, bold);
        label.setSingleLine();
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setIncludeFontPadding(false);
        return label;
    }
    private Switch toggle(String label, String key, Runnable callback) {
        return toggle(label, key, callback, true);
    }

    private Switch toggle(String label, String key, Runnable callback, boolean defaultEnabled) {
        Switch control = new Switch(this);
        control.setText(label);
        control.setTextColor(ink);
        control.setTextSize(14);
        control.setMinHeight(dp(42));
        control.setSingleLine();
        int[][] states = {new int[]{android.R.attr.state_checked}, new int[]{}};
        control.setThumbTintList(new ColorStateList(states, new int[]{accent, quiet}));
        control.setTrackTintList(new ColorStateList(states, new int[]{accentPanel, line}));
        control.setChecked(preferences.getBoolean(key, defaultEnabled));
        control.setOnCheckedChangeListener((unused, enabled) -> {
            preferences.edit().putBoolean(key, enabled).apply();
            if (callback != null) callback.run();
        });
        return control;
    }

    private Button button(String label, Runnable action, boolean primary) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(13);
        button.setTextColor(primary ? surface : ink);
        button.setMinHeight(dp(44));
        button.setMinimumHeight(dp(44));
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(dp(10), 0, dp(10), 0);
        button.setStateListAnimator(null);
        button.setBackground(ripple(primary ? accent : subtle, 9, false));
        button.setOnClickListener(view -> action.run());
        return button;
    }

    private TextView text(String label, int size, int foreground, boolean bold) {
        TextView view = new TextView(this);
        view.setText(label);
        view.setTextSize(size);
        view.setTextColor(foreground);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private EditText field(String hint, int inputType) {
        EditText field = new EditText(new ContextThemeWrapper(this, dark ?
                android.R.style.Theme_Material : android.R.style.Theme_Material_Light));
        field.setHint(hint);
        field.setTextColor(ink);
        field.setHintTextColor(quiet);
        field.setSingleLine();
        field.setInputType(inputType);
        return field;
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private LinearLayout row() {
        LinearLayout layout = new LinearLayout(this);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    private void add(LinearLayout parent, View view, int margin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(margin);
        parent.addView(view, params);
    }

    private GradientDrawable shape(int fill, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    private AlertDialog.Builder dialog() {
        return new AlertDialog.Builder(this, dark ?
                android.R.style.Theme_Material_Dialog_Alert : android.R.style.Theme_Material_Light_Dialog_Alert);
    }

    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private static int color(String value) { return Color.parseColor(value); }
    private static JSONArray rows(JSONObject object, String key) {
        JSONArray array = object.optJSONArray(key);
        return array == null ? new JSONArray() : array;
    }
    private static String clock(long time) {
        return CLOCK.format(Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()));
    }
    private static String duration(long millis) {
        long seconds = Math.max(0, millis / 1000);
        if (seconds >= 3600) return String.format(Locale.ROOT, "%d 小时 %d 分", seconds / 3600, seconds / 60 % 60);
        if (seconds >= 60) return String.format(Locale.ROOT, "%d 分 %02d 秒", seconds / 60, seconds % 60);
        return seconds + " 秒";
    }
}
