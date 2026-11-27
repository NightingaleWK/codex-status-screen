package local.codex.statusscreen;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

final class Glyph extends Drawable {
    enum Icon { ROTATE, SETTINGS, BACK, CHEVRON, DEVICE, CHECK, DISCONNECT, PLUS, SUN, MOON, ACTIVITY, SOUND }
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Icon icon;

    Glyph(Icon icon, int color) {
        this.icon = icon;
        paint.setColor(color);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.75f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
    }

    @Override public void draw(Canvas canvas) {
        Rect bounds = getBounds();
        float unit = Math.min(bounds.width(), bounds.height()) / 24f;
        canvas.save();
        canvas.translate(bounds.exactCenterX() - 12 * unit, bounds.exactCenterY() - 12 * unit);
        canvas.scale(unit, unit);
        switch (icon) {
            case ROTATE:
                canvas.drawRoundRect(4, 8, 18, 19, 2.3f, 2.3f, paint);
                canvas.drawArc(10, 3, 21, 14, -112, 102, false, paint);
                trace(canvas, 17, 8, 21, 8, 21, 4);
                break;
            case SETTINGS:
                path.reset();
                for (int i = 0; i < 32; i++) {
                    double a = Math.PI * (i - 2) / 16;
                    float radius = i % 4 == 1 || i % 4 == 2 ? 9.1f : 7.3f;
                    float x = 12 + radius * (float) Math.cos(a), y = 12 + radius * (float) Math.sin(a);
                    if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
                }
                path.close();
                canvas.drawPath(path, paint);
                canvas.drawCircle(12, 12, 2.6f, paint);
                break;
            case BACK:
                trace(canvas, 15, 5, 8, 12, 15, 19);
                break;
            case CHEVRON:
                trace(canvas, 7, 9.5f, 12, 14.5f, 17, 9.5f);
                break;
            case DEVICE:
                canvas.drawRoundRect(3.5f, 4.5f, 20.5f, 16, 2, 2, paint);
                trace(canvas, 12, 16, 12, 20);
                trace(canvas, 8, 20, 16, 20);
                break;
            case CHECK:
                trace(canvas, 5, 12, 10, 17, 19, 7);
                break;
            case DISCONNECT:
                trace(canvas, 4, 4, 20, 20);
                canvas.drawArc(3, 6, 21, 24, -103, 54, false, paint);
                canvas.drawArc(6, 10, 18, 22, -155, 24, false, paint);
                canvas.drawCircle(12, 18, 1, paint);
                break;
            case PLUS:
                trace(canvas, 12, 5, 12, 19);
                trace(canvas, 5, 12, 19, 12);
                break;
            case SUN:
                canvas.drawCircle(12, 12, 4, paint);
                for (int i = 0; i < 8; i++) {
                    double a = Math.PI * i / 4;
                    float x = (float) Math.cos(a), y = (float) Math.sin(a);
                    canvas.drawLine(12 + 7 * x, 12 + 7 * y, 12 + 9 * x, 12 + 9 * y, paint);
                }
                break;
            case MOON:
                path.reset();
                path.moveTo(20, 15);
                path.cubicTo(9, 19, 6, 8, 13, 4);
                path.cubicTo(5, 3, 2, 12, 6, 17);
                path.cubicTo(10, 22, 17, 21, 20, 15);
                path.close();
                canvas.drawPath(path, paint);
                break;
            case ACTIVITY:
                trace(canvas, 2.5f, 13, 7, 13, 10, 6, 13, 18, 16, 11, 21.5f, 11);
                break;
            case SOUND:
                trace(canvas, 4, 9, 8, 9, 12, 5, 12, 19, 8, 15, 4, 15, 4, 9);
                canvas.drawArc(10, 8, 18, 16, -48, 96, false, paint);
                canvas.drawArc(8, 5, 22, 19, -46, 92, false, paint);
                break;
        }
        canvas.restore();
    }

    private void trace(Canvas canvas, float... points) {
        path.reset();
        path.moveTo(points[0], points[1]);
        for (int i = 2; i < points.length; i += 2) path.lineTo(points[i], points[i + 1]);
        canvas.drawPath(path, paint);
    }

    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
