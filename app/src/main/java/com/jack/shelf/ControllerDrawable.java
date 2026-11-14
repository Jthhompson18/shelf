package com.jack.shelf;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/** A simple, original game-controller glyph (not any company's logo). */
class ControllerDrawable extends Drawable {
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cut = new Paint(Paint.ANTI_ALIAS_FLAG);

    ControllerDrawable(int color, int cutColor) {
        fill.setColor(color);
        cut.setColor(cutColor);
    }

    @Override
    public void draw(Canvas c) {
        float w = getBounds().width(), h = getBounds().height();
        float ox = getBounds().left, oy = getBounds().top;
        float bw = w * 0.82f, bh = bw * 0.52f;
        float l = ox + (w - bw) / 2f, t = oy + (h - bh) / 2f;

        Path p = new Path();
        // body
        p.addRoundRect(new RectF(l, t, l + bw, t + bh * 0.72f), bh * 0.36f, bh * 0.36f, Path.Direction.CW);
        // grips
        float gr = bh * 0.30f;
        p.addCircle(l + bw * 0.20f, t + bh * 0.68f, gr, Path.Direction.CW);
        p.addCircle(l + bw * 0.80f, t + bh * 0.68f, gr, Path.Direction.CW);
        c.drawPath(p, fill);

        // d-pad
        float cx = l + bw * 0.27f, cy = t + bh * 0.38f, a = bh * 0.20f, th = bh * 0.07f;
        c.drawRoundRect(new RectF(cx - a / 2, cy - th, cx + a / 2, cy + th), th / 2, th / 2, cut);
        c.drawRoundRect(new RectF(cx - th, cy - a / 2, cx + th, cy + a / 2), th / 2, th / 2, cut);

        // face buttons
        float bx = l + bw * 0.73f, by = t + bh * 0.38f, d = bh * 0.11f, r = bh * 0.055f;
        c.drawCircle(bx, by - d, r, cut);
        c.drawCircle(bx, by + d, r, cut);
        c.drawCircle(bx - d, by, r, cut);
        c.drawCircle(bx + d, by, r, cut);
    }

    @Override
    public void setAlpha(int alpha) {
        fill.setAlpha(alpha);
        cut.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        fill.setColorFilter(cf);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
