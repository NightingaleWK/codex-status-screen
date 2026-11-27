package local.codex.statusscreen;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.hardware.Camera;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.ReaderException;
import com.google.zxing.common.HybridBinarizer;
import java.util.Collections;

@SuppressWarnings("deprecation")
public final class ScanActivity extends Activity implements SurfaceHolder.Callback {
    private Camera camera;
    private long lastFrame;
    private final MultiFormatReader reader = new MultiFormatReader();

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (getSharedPreferences("settings", MODE_PRIVATE).getBoolean("awake", true)) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        reader.setHints(Collections.singletonMap(DecodeHintType.POSSIBLE_FORMATS,
                Collections.singletonList(BarcodeFormat.QR_CODE)));
        FrameLayout content = new FrameLayout(this);
        SurfaceView preview = new SurfaceView(this);
        preview.getHolder().addCallback(this);
        content.addView(preview, new FrameLayout.LayoutParams(-1, -1));
        TextView hint = new TextView(this);
        hint.setText("扫描 Windows 采集器的配对码\n点这里返回");
        hint.setTextColor(Color.WHITE);
        hint.setTextSize(17);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(24, 40, 24, 40);
        hint.setBackgroundColor(0xC0151A1B);
        hint.setOnClickListener(view -> finish());
        content.addView(hint, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        setContentView(content);
    }

    public void surfaceCreated(SurfaceHolder holder) {
        try {
            camera = Camera.open();
            camera.setDisplayOrientation(90);
            Camera.Parameters parameters = camera.getParameters();
            if (parameters.getSupportedFocusModes().contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
                parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
                camera.setParameters(parameters);
            }
            Camera.Size size = camera.getParameters().getPreviewSize();
            camera.setPreviewDisplay(holder);
            camera.setPreviewCallback((bytes, source) -> scan(bytes, size.width, size.height));
            camera.startPreview();
        } catch (Exception error) {
            Toast.makeText(this, "摄像头不可用，可以手动配对", Toast.LENGTH_LONG).show();
            finish();
        }
    }

    private void scan(byte[] frame, int width, int height) {
        long now = SystemClock.elapsedRealtime();
        if (frame == null || now - lastFrame < 160 || isFinishing()) return;
        lastFrame = now;
        try {
            byte[] luma = frame;
            PlanarYUVLuminanceSource image =
                    new PlanarYUVLuminanceSource(luma, width, height, 0, 0, width, height, false);
            String text = reader.decodeWithState(new BinaryBitmap(new HybridBinarizer(image))).getText();
            setResult(RESULT_OK, new Intent().putExtra("pairing", text));
            finish();
        } catch (ReaderException ignored) {
        } finally { reader.reset(); }
    }

    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}
    public void surfaceDestroyed(SurfaceHolder holder) { release(); }
    @Override protected void onPause() { release(); super.onPause(); }

    private void release() {
        if (camera == null) return;
        camera.setPreviewCallback(null);
        camera.stopPreview();
        camera.release();
        camera = null;
    }
}
