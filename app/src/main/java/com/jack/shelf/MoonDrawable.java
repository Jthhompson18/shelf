package com.jack.shelf;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;

/** Crescent moon glyph for the Goodnight tile. */
class MoonDrawable extends Drawable {
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

    MoonDrawable(int color) {
        p.setColor(color);
    }

    @Override
    public void draw(Canvas c) {
        float w = getBounds().width(), h = getBounds().height();
        float cx = getBounds().left + w / 2f, cy = getBounds().top + h / 2f, r = Math.min(w, h) * 0.42f;
        Path moon = new Path();
        moon.addCircle(cx, cy, r, Path.Direction.CW);
        Path bite = new Path();
        bite.addCircle(cx + r * 0.55f, cy - r * 0.35f, r * 0.88f, Path.Direction.CW);
        moon.op(bite, Path.Op.DIFFERENCE);
        c.drawPath(moon, p);
        // a couple of small stars
        c.drawCircle(cx + r * 0.95f, cy + r * 0.55f, r * 0.07f, p);
        c.drawCircle(cx + r * 0.55f, cy + r * 0.95f, r * 0.05f, p);
    }

    @Override
    public void setAlpha(int a) {
        p.setAlpha(a);
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        p.setColorFilter(cf);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
