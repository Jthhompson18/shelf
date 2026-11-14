package com.jack.shelf;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/** Clean line-and-fill weather glyphs drawn in code: sun, moon, cloud, rain, storm, fog. */
class WeatherIcon extends Drawable {
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int code;
    private final boolean day;

    WeatherIcon(int wmoCode, boolean isDay) {
        code = wmoCode;
        day = isDay;
        fill.setColor(0xFFFFFFFF);
        stroke.setColor(0xFFFFFFFF);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
    }

    @Override
    public void draw(Canvas c) {
        float s = Math.min(getBounds().width(), getBounds().height());
        float ox = getBounds().left + (getBounds().width() - s) / 2f;
        float oy = getBounds().top + (getBounds().height() - s) / 2f;
        c.save();
        c.translate(ox, oy);
        stroke.setStrokeWidth(s * 0.065f);

        boolean rain = (code >= 51 && code <= 67) || (code >= 80 && code <= 82);
        boolean storm = code >= 95;
        boolean fog = code == 45 || code == 48;
        boolean snow = code >= 71 && code <= 77;
        boolean overcast = code == 3 || rain || storm || snow;
        boolean partly = code == 1 || code == 2;

        if (fog) {
            for (int i = 0; i < 3; i++) {
                float y = s * (0.35f + i * 0.17f);
                c.drawLine(s * (0.18f + (i % 2) * 0.08f), y, s * (0.82f - (i % 2) * 0.06f), y, stroke);
            }
        } else if (!overcast && !partly) {
            celestial(c, s / 2f, s / 2f, s * 0.22f, true);
        } else if (partly) {
            celestial(c, s * 0.62f, s * 0.36f, s * 0.17f, false);
            cloud(c, s, s * 0.08f, s * 0.14f, 0.78f);
        } else {
            cloud(c, s, 0, rain || storm || snow ? -s * 0.08f : 0, 1f);
            if (rain) {
                for (int i = 0; i < 3; i++) {
                    float x = s * (0.32f + i * 0.18f);
                    c.drawLine(x, s * 0.70f, x - s * 0.05f, s * 0.86f, stroke);
                }
            } else if (snow) {
                for (int i = 0; i < 3; i++) c.drawCircle(s * (0.32f + i * 0.18f), s * 0.80f, s * 0.04f, fill);
            } else if (storm) {
                Path bolt = new Path();
                bolt.moveTo(s * 0.54f, s * 0.60f);
                bolt.lineTo(s * 0.42f, s * 0.78f);
                bolt.lineTo(s * 0.52f, s * 0.78f);
                bolt.lineTo(s * 0.46f, s * 0.94f);
                bolt.lineTo(s * 0.62f, s * 0.72f);
                bolt.lineTo(s * 0.52f, s * 0.72f);
                bolt.close();
                c.drawPath(bolt, fill);
            }
        }
        c.restore();
    }

    /** Sun with rays by day, crescent moon by night. */
    private void celestial(Canvas c, float cx, float cy, float r, boolean rays) {
        if (day) {
            c.drawCircle(cx, cy, r, fill);
            if (rays) {
                for (int i = 0; i < 8; i++) {
                    double a = Math.PI * 2 * i / 8;
                    float x1 = cx + (float) Math.cos(a) * r * 1.45f, y1 = cy + (float) Math.sin(a) * r * 1.45f;
                    float x2 = cx + (float) Math.cos(a) * r * 1.9f, y2 = cy + (float) Math.sin(a) * r * 1.9f;
                    c.drawLine(x1, y1, x2, y2, stroke);
                }
            }
        } else {
            Path moon = new Path();
            moon.addCircle(cx, cy, r * 1.1f, Path.Direction.CW);
            Path bite = new Path();
            bite.addCircle(cx + r * 0.6f, cy - r * 0.4f, r * 0.95f, Path.Direction.CW);
            moon.op(bite, Path.Op.DIFFERENCE);
            c.drawPath(moon, fill);
        }
    }

    private void cloud(Canvas c, float s, float dx, float dy, float k) {
        c.save();
        c.translate(dx + s * (1 - k) / 2f, dy + s * (1 - k) / 2f);
        c.scale(k, k);
        Path p = new Path();
        p.addCircle(s * 0.38f, s * 0.52f, s * 0.17f, Path.Direction.CW);
        p.addCircle(s * 0.56f, s * 0.44f, s * 0.21f, Path.Direction.CW);
        p.addRoundRect(new RectF(s * 0.18f, s * 0.50f, s * 0.84f, s * 0.68f), s * 0.09f, s * 0.09f, Path.Direction.CW);
        c.drawPath(p, fill);
        c.restore();
    }

    @Override
    public void setAlpha(int a) {
        fill.setAlpha(a);
        stroke.setAlpha(a);
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        fill.setColorFilter(cf);
        stroke.setColorFilter(cf);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
