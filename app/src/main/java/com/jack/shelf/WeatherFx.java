package com.jack.shelf;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.SystemClock;
import android.view.View;

import java.util.Random;

/** Lightweight live weather layer drawn over the photo: rain streaks and lightning flashes. */
public class WeatherFx extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Random rnd = new Random();
    private float[] x = new float[0], y = new float[0], len = new float[0], speed = new float[0];
    private int drops;
    private boolean storm;
    private boolean running;
    private long lastFrame;
    private long nextFlash;
    private float flash;

    public WeatherFx(Context c) {
        super(c);
        paint.setStrokeCap(Paint.Cap.ROUND);
        setWillNotDraw(false);
    }

    /** @param intensity 0 = off, 1 = drizzle, 2 = rain, 3 = heavy/storm */
    public void setRain(int intensity, boolean thunder) {
        int n = intensity <= 0 ? 0 : intensity == 1 ? 55 : intensity == 2 ? 110 : 170;
        storm = thunder;
        if (n != drops) {
            drops = n;
            x = new float[n];
            y = new float[n];
            len = new float[n];
            speed = new float[n];
            for (int i = 0; i < n; i++) seed(i, true);
        }
        nextFlash = SystemClock.uptimeMillis() + 4000 + rnd.nextInt(8000);
        setVisibility(drops > 0 || storm ? VISIBLE : GONE);
        invalidate();
    }

    public void setRunning(boolean r) {
        running = r;
        lastFrame = 0;
        if (r) invalidate();
    }

    private void seed(int i, boolean anywhere) {
        float w = Math.max(1, getWidth()), h = Math.max(1, getHeight());
        float d = getResources().getDisplayMetrics().density;
        x[i] = rnd.nextFloat() * (w + h * 0.25f);
        y[i] = anywhere ? rnd.nextFloat() * h : -rnd.nextFloat() * h * 0.3f;
        len[i] = (14 + rnd.nextFloat() * 22) * d;
        speed[i] = (700 + rnd.nextFloat() * 500) * d; // px per second
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        for (int i = 0; i < drops; i++) seed(i, true);
    }

    @Override
    protected void onDraw(Canvas c) {
        if (drops == 0 && !storm) return;
        long now = SystemClock.uptimeMillis();
        float dt = lastFrame == 0 ? 0 : Math.min(0.05f, (now - lastFrame) / 1000f);
        lastFrame = now;
        float d = getResources().getDisplayMetrics().density;
        float h = getHeight();

        paint.setStrokeWidth(1.4f * d);
        for (int i = 0; i < drops; i++) {
            y[i] += speed[i] * dt;
            x[i] -= speed[i] * dt * 0.18f;
            if (y[i] - len[i] > h) seed(i, false);
            int a = (int) (60 + (len[i] / (36 * d)) * 70);
            paint.setColor((Math.min(a, 140) << 24) | 0xDDE6F0);
            c.drawLine(x[i], y[i], x[i] + len[i] * 0.18f, y[i] - len[i], paint);
        }

        if (storm) {
            if (now >= nextFlash) {
                flash = 1f;
                nextFlash = now + 5000 + rnd.nextInt(12000);
            }
            if (flash > 0) {
                c.drawColor(((int) (flash * 90) << 24) | 0xFFFFFF);
                flash = Math.max(0, flash - dt * 3.5f);
            }
        }
        if (running) postInvalidateOnAnimation();
    }
}
