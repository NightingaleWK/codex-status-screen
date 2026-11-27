package local.codex.statusscreen;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;

import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.function.BiConsumer;

@SuppressWarnings("deprecation")
final class Discovery {
    private final NsdManager manager;
    private final WifiManager.MulticastLock wifiLock;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final BiConsumer<String, String> onAddress;
    private final ArrayDeque<NsdServiceInfo> pending = new ArrayDeque<>();
    private NsdManager.DiscoveryListener listener;
    private boolean resolving;

    Discovery(Context context, BiConsumer<String, String> onAddress) {
        manager = context.getSystemService(NsdManager.class);
        WifiManager wifi = context.getApplicationContext().getSystemService(WifiManager.class);
        wifiLock = wifi == null ? null : wifi.createMulticastLock("codex-status-dnssd");
        if (wifiLock != null) wifiLock.setReferenceCounted(false);
        this.onAddress = onAddress;
    }

    void start() {
        if (manager == null || listener != null) return;
        listener = new NsdManager.DiscoveryListener() {
            public void onDiscoveryStarted(String type) {}
            public void onDiscoveryStopped(String type) {}
            public void onStartDiscoveryFailed(String type, int code) { handler.post(Discovery.this::stop); }
            public void onStopDiscoveryFailed(String type, int code) {}
            public void onServiceLost(NsdServiceInfo service) {}
            public void onServiceFound(NsdServiceInfo service) {
                handler.post(() -> {
                    if (listener == null) return;
                    pending.add(service);
                    resolveNext();
                });
            }
        };
        try {
            if (wifiLock != null) wifiLock.acquire();
            manager.discoverServices("_codexstatus._tcp.", NsdManager.PROTOCOL_DNS_SD, listener);
        } catch (RuntimeException error) { stop(); }
    }

    void stop() {
        if (listener != null) {
            NsdManager.DiscoveryListener active = listener;
            listener = null;
            try { manager.stopServiceDiscovery(active); } catch (RuntimeException ignored) {}
        }
        pending.clear();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
    }

    private void resolveNext() {
        if (listener == null || resolving || pending.isEmpty()) return;
        resolving = true;
        try {
            manager.resolveService(pending.remove(), new NsdManager.ResolveListener() {
                public void onResolveFailed(NsdServiceInfo service, int errorCode) {
                    handler.post(Discovery.this::finished);
                }
                public void onServiceResolved(NsdServiceInfo service) {
                    handler.post(() -> {
                        try {
                            byte[] id = service.getAttributes().get("id");
                            InetAddress host = service.getHost();
                            if (listener != null && id != null && host != null) {
                                String url = new URI("https", null, host.getHostAddress(), service.getPort(),
                                        null, null, null).toString();
                                onAddress.accept(new String(id, StandardCharsets.UTF_8), url);
                            }
                        } catch (Exception ignored) {
                        } finally { finished(); }
                    });
                }
            });
        } catch (RuntimeException ignored) { finished(); }
    }

    private void finished() {
        resolving = false;
        resolveNext();
    }
}
