package com.jack.shelf;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/** Generic "send to screen" glyph: a screen outline with wireless waves from its corner. */
class CastDrawable extends Drawable {
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);

    CastDrawable(int color) {
        line.setColor(color);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        fill.setColor(color);
    }

    @Override
    public void draw(Canvas c) {
        float w = getBounds().width(), h = getBounds().height();
        float ox = getBounds().left, oy = getBounds().top;
        float s = Math.min(w, h);
        float sw = s * 0.07f;
        line.setStrokeWidth(sw);
        // screen: open at the bottom-left where the waves sit
        float l = ox + s * 0.10f, t = oy + s * 0.18f, r = ox + s * 0.90f, b = oy + s * 0.78f;
        android.graphics.Path p = new android.graphics.Path();
        p.moveTo(l, t + s * 0.22f);
        p.lineTo(l, t + s * 0.06f);
        p.quadTo(l, t, l + s * 0.06f, t);
        p.lineTo(r - s * 0.06f, t);
        p.quadTo(r, t, r, t + s * 0.06f);
        p.lineTo(r, b - s * 0.06f);
        p.quadTo(r, b, r - s * 0.06f, b);
        p.lineTo(l + s * 0.34f, b);
        c.drawPath(p, line);
        // waves
        float cx = l, cy = b;
        for (int i = 1; i <= 2; i++) {
            float rad = s * 0.13f * i + s * 0.04f;
            c.drawArc(new RectF(cx - rad, cy - rad, cx + rad, cy + rad), 270, 90, false, line);
        }
        c.drawCircle(cx + sw * 0.2f, cy - sw * 0.2f, sw * 0.9f, fill);
    }

    @Override
    public void setAlpha(int a) {
        line.setAlpha(a);
        fill.setAlpha(a);
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        line.setColorFilter(cf);
        fill.setColorFilter(cf);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
