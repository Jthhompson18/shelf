package com.jack.shelf;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class MainActivity extends Activity {

    static class App {
        String pkg;
        ComponentName comp;
        String label;
        boolean loaded;
        Drawable banner;
        Drawable icon;
        int color = 0xFF333344;
    }

    private static final String SETTINGS_TAG = "__settings__";
    private static final String APPLE_TV_PKG = "com.apple.atve.androidtv.appletv";

    /** Apps that are pinned to the top row automatically when they're installed (in this order). */
    private static final String[] DEFAULT_FAVS = {
            "apple tv", "netflix", "youtube", "prime video", "paramount", "apple music", "disney",
            "stan", "binge", "kayo", "spotify", "abc iview", "sbs on demand", "plex"
    };

    private static final long WALLPAPER_EVERY_MS = 10 * 60 * 1000L;

    private final Map<String, App> apps = new LinkedHashMap<>();
    private final Map<String, App> cache = new LinkedHashMap<>();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private SharedPreferences prefs;
    private float dp;
    private Typeface tfLight, tfRegular, tfMedium, tfSemi;

    private ImageView wallA, wallB;
    private boolean wallAOnTop = true;
    private final List<String> wallpapers = new ArrayList<>();
    private long wallChangedAt;

    private LinearLayout favRow, allRow;
    private HorizontalScrollView favScroll, allScroll;
    private static final String NAME = "Jack";
    private WeatherFx fx;
    private String mood = "";
    private Glance.Weather lastWx;
    private FrameLayout splash;
    private TextView splashHello, splashSub;
    private BroadcastReceiver screenReceiver;
    private final Runnable hideSplash = new Runnable() {
        @Override
        public void run() {
            dismissWelcome();
        }
    };
    private TextView clock, date, wxPlace, wxTemp, wxCond, wxRange, headline;
    private final List<String> headlines = new ArrayList<>();
    private int headlineIdx = -1;
    private String lastFocused;
    String updateTag;
    private long lastUpdateCheck;

    private final Runnable headlineTick = new Runnable() {
        @Override
        public void run() {
            if (headlines.isEmpty()) return;
            headlineIdx = (headlineIdx + 1) % headlines.size();
            final String next = headlines.get(headlineIdx);
            headline.animate().alpha(0f).setDuration(300).withEndAction(new Runnable() {
                @Override
                public void run() {
                    headline.setText(next);
                    headline.animate().alpha(1f).setDuration(400).start();
                }
            }).start();
            handler.postDelayed(this, 9000);
        }
    };

    private final Runnable weatherRetry = new Runnable() {
        @Override
        public void run() {
            refreshGlance();
        }
    };

    private final Runnable glanceTick = new Runnable() {
        @Override
        public void run() {
            refreshGlance();
            handler.postDelayed(this, 15 * 60 * 1000L);
        }
    };
    private BroadcastReceiver pkgReceiver;

    private final Runnable clockTick = new Runnable() {
        @Override
        public void run() {
            long now = System.currentTimeMillis();
            boolean h24 = DateFormat.is24HourFormat(MainActivity.this);
            clock.setText(fmt(h24 ? "HH:mm" : "h:mm", now));
            date.setText(fmt("EEEE d MMMM", now));
            handler.postDelayed(this, 5000);
        }
    };

    private final Runnable wallTick = new Runnable() {
        @Override
        public void run() {
            nextWallpaper(true);
            handler.postDelayed(this, WALLPAPER_EVERY_MS);
        }
    };

    private final Runnable reloadRunnable = new Runnable() {
        @Override
        public void run() {
            reload();
        }
    };

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        dp = getResources().getDisplayMetrics().density;
        prefs = getSharedPreferences("shelf", MODE_PRIVATE);
        loadFonts();
        findWallpapers();
        mood = moodFor(null);
        setContentView(buildUi());
        nextWallpaper(false);
        if (savedInstanceState == null) showWelcome();

        screenReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                showWelcome(); // projector woke from standby
            }
        };
        registerReceiver(screenReceiver, new IntentFilter(Intent.ACTION_SCREEN_ON));

        pkgReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                handler.removeCallbacks(reloadRunnable);
                handler.postDelayed(reloadRunnable, 600);
            }
        };
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addAction(Intent.ACTION_PACKAGE_REPLACED);
        f.addDataScheme("package");
        registerReceiver(pkgReceiver, f);
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUi();
        reload();
        handler.removeCallbacks(clockTick);
        clockTick.run();
        handler.removeCallbacks(wallTick);
        long since = System.currentTimeMillis() - wallChangedAt;
        handler.postDelayed(wallTick, Math.max(1000, WALLPAPER_EVERY_MS - since));
        handler.removeCallbacks(glanceTick);
        glanceTick.run();
        checkForUpdate(false);
        if (fx != null) fx.setRunning(true);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(clockTick);
        handler.removeCallbacks(wallTick);
        handler.removeCallbacks(glanceTick);
        handler.removeCallbacks(weatherRetry);
        handler.removeCallbacks(headlineTick);
        if (fx != null) fx.setRunning(false);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            unregisterReceiver(pkgReceiver);
            unregisterReceiver(screenReceiver);
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        goToStart();
    }

    @Override
    public void onBackPressed() {
        goToStart();
    }

    private void goToStart() {
        if (favScroll != null) favScroll.smoothScrollTo(0, 0);
        if (allScroll != null) allScroll.smoothScrollTo(0, 0);
        if (favRow != null && favRow.getChildCount() > 0) {
            View first = favRow.getChildAt(0);
            View tile = first.findViewWithTag("tile");
            (tile != null ? tile : first).requestFocus();
        }
    }

    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    private int px(float v) {
        return Math.round(v * dp);
    }

    private void loadFonts() {
        tfLight = font("Inter-Light.otf", "sans-serif-light");
        tfRegular = font("Inter-Regular.otf", "sans-serif");
        tfMedium = font("Inter-Medium.otf", "sans-serif-medium");
        tfSemi = font("Inter-SemiBold.otf", "sans-serif-medium");
    }

    private Typeface font(String file, String fallback) {
        try {
            return Typeface.createFromAsset(getAssets(), "fonts/" + file);
        } catch (Exception e) {
            return Typeface.create(fallback, Typeface.NORMAL);
        }
    }

    // ---------------------------------------------------------------- wallpaper

    private void findWallpapers() {
        wallpapers.clear();
        try {
            String[] list = getAssets().list("wallpapers");
            if (list != null) {
                for (String s : list) {
                    String l = s.toLowerCase(Locale.ROOT);
                    if (l.endsWith(".jpg") || l.endsWith(".jpeg") || l.endsWith(".png")) {
                        wallpapers.add("wallpapers/" + s);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        Collections.sort(wallpapers);
    }

    private Bitmap decode(String asset) {
        try {
            DisplayMetrics m = getResources().getDisplayMetrics();
            int target = Math.max(m.widthPixels, m.heightPixels);
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            InputStream in = getAssets().open(asset);
            BitmapFactory.decodeStream(in, null, o);
            in.close();
            int sample = 1;
            while (o.outWidth / (sample * 2) >= target) sample *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            o2.inPreferredConfig = Bitmap.Config.RGB_565;
            in = getAssets().open(asset);
            Bitmap b = BitmapFactory.decodeStream(in, null, o2);
            in.close();
            return b;
        } catch (Throwable t) {
            return null;
        }
    }

    private void nextWallpaper(boolean animate) {
        List<String> set = new ArrayList<>();
        for (String w : wallpapers) {
            if (w.startsWith("wallpapers/" + mood + "_")) set.add(w);
        }
        if (set.isEmpty()) set.addAll(wallpapers);
        if (set.isEmpty()) return;
        String key = "wall_" + mood;
        int idx = (prefs.getInt(key, -1) + 1) % set.size();
        prefs.edit().putInt(key, idx).apply();
        Bitmap b = decode(set.get(idx));
        if (b == null) return;
        wallChangedAt = System.currentTimeMillis();

        final ImageView in = wallAOnTop ? wallB : wallA;
        final ImageView out = wallAOnTop ? wallA : wallB;
        wallAOnTop = !wallAOnTop;

        in.setImageBitmap(b);
        in.bringToFront();
        in.animate().cancel();
        in.setScaleX(1f);
        in.setScaleY(1f);
        if (animate) {
            in.setAlpha(0f);
            in.animate().alpha(1f).setDuration(1500).withEndAction(new Runnable() {
                @Override
                public void run() {
                    out.setImageDrawable(null);
                    drift(in);
                }
            }).start();
        } else {
            in.setAlpha(1f);
            out.setImageDrawable(null);
            drift(in);
        }
    }

    /** Slow push-in on the photo, like Apple TV's aerial screens. */
    private void drift(ImageView v) {
        v.animate().scaleX(1.07f).scaleY(1.07f)
                .setDuration(WALLPAPER_EVERY_MS)
                .setInterpolator(new LinearInterpolator())
                .start();
    }

    // ---------------------------------------------------------------- UI

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0B0D12);

        // Two image layers so photos can crossfade.
        FrameLayout walls = new FrameLayout(this);
        wallA = wallView();
        wallB = wallView();
        walls.addView(wallA, new FrameLayout.LayoutParams(-1, -1));
        walls.addView(wallB, new FrameLayout.LayoutParams(-1, -1));
        root.addView(walls, new FrameLayout.LayoutParams(-1, -1));

        fx = new WeatherFx(this);
        fx.setVisibility(View.GONE);
        root.addView(fx, new FrameLayout.LayoutParams(-1, -1));

        if (wallpapers.isEmpty()) {
            View g = new View(this);
            g.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    new int[]{0xFF2B3550, 0xFF121722, 0xFF07080C}));
            root.addView(g, new FrameLayout.LayoutParams(-1, -1));
        }

        // Soft shading: a little at the top for the clock, more at the bottom for the rows.
        View topShade = new View(this);
        topShade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x66000000, 0x00000000}));
        root.addView(topShade, new FrameLayout.LayoutParams(-1, px(110), Gravity.TOP));
        View bottomShade = new View(this);
        bottomShade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x00000000, 0x8C000000, 0xE0000000}));
        root.addView(bottomShade, new FrameLayout.LayoutParams(-1, px(330), Gravity.BOTTOM));

        // Top left: big clock, greeting, date
        LinearLayout hello = new LinearLayout(this);
        hello.setOrientation(LinearLayout.VERTICAL);
        clock = text(68, 0xFFFFFFFF, tfLight);
        clock.setShadowLayer(px(14), 0, px(2), 0x80000000);
        clock.setLetterSpacing(-0.02f);
        hello.addView(clock);
        date = text(17, 0xE6FFFFFF, tfRegular);
        date.setShadowLayer(px(8), 0, px(1), 0x80000000);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-2, -2);
        dlp.topMargin = px(4);
        dlp.leftMargin = px(3);
        hello.addView(date, dlp);
        FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        hlp.topMargin = px(30);
        hlp.leftMargin = px(58);
        root.addView(hello, hlp);

        // Top right: weather + headlines in frosted cards
        LinearLayout side = new LinearLayout(this);
        side.setOrientation(LinearLayout.VERTICAL);

        LinearLayout wx = glass();
        wxPlace = text(12, 0xCCFFFFFF, tfSemi);
        wxPlace.setLetterSpacing(0.12f);
        wxPlace.setText("BONDI");
        wx.addView(wxPlace);
        LinearLayout wxLine = new LinearLayout(this);
        wxLine.setOrientation(LinearLayout.HORIZONTAL);
        wxLine.setGravity(Gravity.BOTTOM);
        wxTemp = text(40, 0xFFFFFFFF, tfLight);
        wxTemp.setText("--°");
        wxLine.addView(wxTemp);
        LinearLayout wxRight = new LinearLayout(this);
        wxRight.setOrientation(LinearLayout.VERTICAL);
        wxCond = text(15, 0xFFFFFFFF, tfMedium);
        wxRight.addView(wxCond);
        wxRange = text(13, 0xBFFFFFFF, tfRegular);
        LinearLayout.LayoutParams rgl = new LinearLayout.LayoutParams(-2, -2);
        rgl.topMargin = px(3);
        wxRight.addView(wxRange, rgl);
        LinearLayout.LayoutParams wrl = new LinearLayout.LayoutParams(-2, -2);
        wrl.leftMargin = px(14);
        wrl.bottomMargin = px(4);
        wxLine.addView(wxRight, wrl);
        LinearLayout.LayoutParams wll = new LinearLayout.LayoutParams(-2, -2);
        wll.topMargin = px(4);
        wx.addView(wxLine, wll);
        side.addView(wx, new LinearLayout.LayoutParams(px(300), -2));

        LinearLayout news = glass();
        TextView newsLabel = text(12, 0xCCFFFFFF, tfSemi);
        newsLabel.setLetterSpacing(0.12f);
        newsLabel.setText("ABC NEWS · JUST IN");
        news.addView(newsLabel);
        headline = text(16, 0xFFFFFFFF, tfMedium);
        headline.setMaxLines(2);
        headline.setEllipsize(TextUtils.TruncateAt.END);
        headline.setLineSpacing(px(2), 1f);
        headline.setText("Loading headlines…");
        LinearLayout.LayoutParams hll = new LinearLayout.LayoutParams(-1, -2);
        hll.topMargin = px(8);
        news.addView(headline, hll);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(px(300), -2);
        nlp.topMargin = px(12);
        side.addView(news, nlp);

        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        slp.topMargin = px(32);
        slp.rightMargin = px(52);
        root.addView(side, slp);

        // Rows at the bottom
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.BOTTOM);
        col.setPadding(px(44), 0, 0, px(8));
        col.setClipChildren(false);
        col.setClipToPadding(false);

        favScroll = scroller();
        favRow = row();
        favScroll.addView(favRow);
        col.addView(favScroll, new LinearLayout.LayoutParams(-1, -2));

        allScroll = scroller();
        allRow = row();
        allScroll.addView(allRow);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(-1, -2);
        alp.topMargin = -px(14);
        col.addView(allScroll, alp);

        root.addView(col, new FrameLayout.LayoutParams(-1, -1));

        // Welcome screen shown when the projector is switched on
        splash = new FrameLayout(this);
        splash.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xB3000000, 0x99000000, 0xCC000000}));
        LinearLayout sc = new LinearLayout(this);
        sc.setOrientation(LinearLayout.VERTICAL);
        sc.setGravity(Gravity.CENTER_HORIZONTAL);
        splashHello = text(54, 0xFFFFFFFF, tfLight);
        splashHello.setGravity(Gravity.CENTER);
        sc.addView(splashHello);
        splashSub = text(19, 0xD9FFFFFF, tfRegular);
        splashSub.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams ssl = new LinearLayout.LayoutParams(-2, -2);
        ssl.topMargin = px(14);
        sc.addView(splashSub, ssl);
        splash.addView(sc, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        splash.setVisibility(View.GONE);
        root.addView(splash, new FrameLayout.LayoutParams(-1, -1));
        return root;
    }

    /** Dark translucent rounded card with a faint edge — a stand-in for tvOS frosted glass. */
    private LinearLayout glass() {
        LinearLayout g = new LinearLayout(this);
        g.setOrientation(LinearLayout.VERTICAL);
        g.setPadding(px(18), px(14), px(18), px(16));
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x59202228, 0x73101114});
        bg.setCornerRadius(px(18));
        bg.setStroke(Math.max(1, px(1)), 0x26FFFFFF);
        g.setBackground(bg);
        return g;
    }

    static final java.util.TimeZone SYDNEY = java.util.TimeZone.getTimeZone("Australia/Sydney");

    private static String fmt(String pattern, long t) {
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat(pattern, Locale.ENGLISH);
        f.setTimeZone(SYDNEY);
        return f.format(new java.util.Date(t));
    }

    static int sydneyHour() {
        return java.util.Calendar.getInstance(SYDNEY).get(java.util.Calendar.HOUR_OF_DAY);
    }

    private String moodFor(Glance.Weather w) {
        if (w == null) {
            int h = sydneyHour();
            return (h >= 19 || h < 6) ? "night" : "day";
        }
        if (!w.isDay) return "night";
        return w.code >= 3 ? "cloud" : "day";
    }

    private void applyWeather(Glance.Weather w) {
        String m = moodFor(w);
        if (!m.equals(mood)) {
            mood = m;
            nextWallpaper(true);
        }
        int c = w.code;
        int rain = 0;
        if (c >= 51 && c <= 57) rain = 1;
        else if (c == 61 || c == 80) rain = 2;
        else if ((c >= 62 && c <= 67) || c == 81 || c == 82 || c >= 95) rain = 3;
        fx.setRain(rain, c >= 95);
        fx.setRunning(true);
        if (splash.getVisibility() == View.VISIBLE) splashSub.setText(weatherLine(w));
    }

    private String weatherLine(Glance.Weather w) {
        String cond = w.condition == null || w.condition.isEmpty() ? "" : " and " + w.condition.toLowerCase(Locale.ROOT);
        return w.temp + "\u00B0" + cond + " in Bondi";
    }

    private void showWelcome() {
        if (splash == null) return;
        int hour = sydneyHour();
        splashHello.setText(greetingFor(hour));
        splashSub.setText(lastWx != null ? weatherLine(lastWx)
                : fmt("EEEE d MMMM", System.currentTimeMillis()));
        splash.animate().cancel();
        splash.setVisibility(View.VISIBLE);
        splash.setAlpha(0f);
        splashHello.setTranslationY(px(12));
        splash.animate().alpha(1f).setDuration(500).start();
        splashHello.animate().translationY(0).setDuration(700)
                .setInterpolator(new DecelerateInterpolator()).start();
        handler.removeCallbacks(hideSplash);
        handler.postDelayed(hideSplash, 4000);
    }

    private void dismissWelcome() {
        handler.removeCallbacks(hideSplash);
        if (splash.getVisibility() != View.VISIBLE) return;
        splash.animate().cancel();
        splash.animate().alpha(0f).setDuration(700).withEndAction(new Runnable() {
            @Override
            public void run() {
                splash.setVisibility(View.GONE);
            }
        }).start();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (splash != null && splash.getVisibility() == View.VISIBLE && splash.getAlpha() > 0.5f) {
            if (e.getAction() == KeyEvent.ACTION_UP) dismissWelcome();
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    private String greetingFor(int hour) {
        if (hour >= 5 && hour < 12) return "Good morning, " + NAME;
        if (hour >= 12 && hour < 17) return "Good afternoon, " + NAME;
        if (hour >= 17 && hour < 22) return "Good evening, " + NAME;
        return "Hey " + NAME + ", it's late";
    }

    void checkForUpdate(final boolean manual) {
        long now = System.currentTimeMillis();
        if (!manual && now - lastUpdateCheck < 6 * 3600 * 1000L) return;
        lastUpdateCheck = now;
        new Thread(() -> {
            final String tag = Updater.newerTag(this);
            runOnUiThread(() -> {
                boolean changed = (tag == null) != (updateTag == null);
                updateTag = tag;
                if (changed) rebuildRows();
                if (manual) Toast.makeText(this, tag == null ? "Shelf is up to date"
                        : "Update ready \u2014 select the Update tile", Toast.LENGTH_LONG).show();
            });
        }).start();
    }

    private void refreshGlance() {
        new Thread(() -> {
            final Glance.Weather w = Glance.weather();
            final List<String> h = Glance.headlines();
            runOnUiThread(() -> {
                if (w == null) {
                    handler.removeCallbacks(weatherRetry);
                    handler.postDelayed(weatherRetry, 60 * 1000L);
                    if (lastWx == null) wxCond.setText("Updating\u2026");
                }
                if (w != null) {
                    lastWx = w;
                    applyWeather(w);
                    wxTemp.setText(w.temp + "°");
                    wxCond.setText(w.condition);
                    wxRange.setText("H " + w.hi + "°   L " + w.lo + "°");
                }
                if (!h.isEmpty()) {
                    headlines.clear();
                    headlines.addAll(h);
                    headlineIdx = -1;
                    handler.removeCallbacks(headlineTick);
                    headlineTick.run();
                } else if (headlines.isEmpty()) {
                    headline.setText("Headlines unavailable right now");
                }
            });
        }).start();
    }

    private ImageView wallView() {
        ImageView v = new ImageView(this);
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        return v;
    }

    private TextView text(float sp, int color, Typeface tf) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(tf);
        t.setIncludeFontPadding(false);
        return t;
    }

    private HorizontalScrollView scroller() {
        HorizontalScrollView s = new HorizontalScrollView(this);
        s.setHorizontalScrollBarEnabled(false);
        s.setOverScrollMode(View.OVER_SCROLL_NEVER);
        s.setClipChildren(false);
        s.setClipToPadding(false);
        s.setSmoothScrollingEnabled(true);
        return s;
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setPadding(px(14), px(20), px(60), px(4));
        r.setClipChildren(false);
        r.setClipToPadding(false);
        return r;
    }

    // ---------------------------------------------------------------- data

    private void reload() {
        loadApps();
        autoPin();
        rebuildRows();
    }

    private void loadApps() {
        apps.clear();
        PackageManager pm = getPackageManager();
        addAll(pm.queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0));
        addAll(pm.queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0));
    }

    private void addAll(List<ResolveInfo> list) {
        PackageManager pm = getPackageManager();
        for (ResolveInfo ri : list) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName()) || apps.containsKey(pkg)) continue;
            ComponentName cn = new ComponentName(pkg, ri.activityInfo.name);
            App a = cache.get(pkg);
            if (a == null || !cn.equals(a.comp)) {
                a = new App();
                a.pkg = pkg;
                a.comp = cn;
                cache.put(pkg, a);
            }
            a.label = String.valueOf(ri.loadLabel(pm)).trim();
            apps.put(pkg, a);
        }
    }

    private List<App> sortedApps() {
        List<App> list = new ArrayList<>(apps.values());
        Collections.sort(list, (x, y) -> x.label.compareToIgnoreCase(y.label));
        return list;
    }

    /**
     * Pins well-known streaming apps the first time each one is seen. Anything
     * the user later removes stays removed.
     */
    /** Pre-installed clutter that starts hidden from the bottom row (can be restored). */
    private static final String[] DEFAULT_HIDE = {
            "youcine", "unitv", "tvmarket", "emotn", "filebrowser", "file browser", "gallery",
            "musicplayer", "music player", "movieplayer", "movie player", "miracast", "settings",
            "airscreen", "aptoide", "downloader"
    };

    private void autoHide() {
        Set<String> seen = new HashSet<>(prefs.getStringSet("seenHide", Collections.<String>emptySet()));
        Set<String> hid = hidden();
        boolean changed = false;
        for (App a : apps.values()) {
            if (seen.contains(a.pkg)) continue;
            seen.add(a.pkg);
            changed = true;
            String l = a.label.toLowerCase(Locale.ROOT);
            for (String w : DEFAULT_HIDE) {
                if (l.contains(w)) {
                    hid.add(a.pkg);
                    break;
                }
            }
        }
        if (changed) {
            saveHidden(hid);
            prefs.edit().putStringSet("seenHide", seen).apply();
        }
    }

    private void autoPin() {
        if (apps.isEmpty()) return;
        autoHide();
        Set<String> seen = new HashSet<>(prefs.getStringSet("seen", Collections.<String>emptySet()));
        List<String> fav = favs();
        boolean changed = false;
        List<App> sorted = sortedApps();
        for (String w : DEFAULT_FAVS) {
            for (App a : sorted) {
                if (a.label.toLowerCase(Locale.ROOT).startsWith(w) || a.label.toLowerCase(Locale.ROOT).equals(w)) {
                    if (!seen.contains(a.pkg)) {
                        seen.add(a.pkg);
                        if (!fav.contains(a.pkg)) fav.add(a.pkg);
                        changed = true;
                    }
                    break;
                }
            }
        }
        if (changed) {
            saveFavs(fav);
            prefs.edit().putStringSet("seen", seen).apply();
        }
    }

    private List<String> favs() {
        String s = prefs.getString("fav", "");
        List<String> out = new ArrayList<>();
        if (s.isEmpty()) return out;
        for (String p : s.split(",")) {
            if (!p.isEmpty() && !out.contains(p)) out.add(p);
        }
        return out;
    }

    private void saveFavs(List<String> list) {
        prefs.edit().putString("fav", TextUtils.join(",", list)).apply();
    }

    private Set<String> hidden() {
        return new HashSet<>(prefs.getStringSet("hidden", Collections.<String>emptySet()));
    }

    private void saveHidden(Set<String> s) {
        prefs.edit().putStringSet("hidden", s).apply();
    }

    private void loadVisuals(App a) {
        if (a.loaded) return;
        a.loaded = true;
        PackageManager pm = getPackageManager();
        try {
            a.banner = pm.getActivityBanner(a.comp);
            if (a.banner == null) a.banner = pm.getApplicationBanner(a.pkg);
        } catch (Exception ignored) {
        }
        try {
            ActivityInfo ai = pm.getActivityInfo(a.comp, 0);
            int res = ai.getIconResource();
            if (res != 0) {
                Resources r = pm.getResourcesForApplication(ai.applicationInfo);
                a.icon = r.getDrawableForDensity(res, DisplayMetrics.DENSITY_XXXHIGH, null);
            }
            if (a.icon == null) a.icon = pm.getActivityIcon(a.comp);
        } catch (Exception e) {
            a.icon = pm.getDefaultActivityIcon();
        }
        a.color = dominant(a.banner != null ? a.banner : a.icon);
    }

    private static int dominant(Drawable d) {
        if (d == null) return 0xFF333344;
        try {
            int n = 24;
            Bitmap bm = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bm);
            android.graphics.Rect old = d.copyBounds();
            d.setBounds(0, 0, n, n);
            d.draw(c);
            d.setBounds(old);
            float r = 0, g = 0, b = 0, w = 0;
            float[] hsv = new float[3];
            for (int y = 0; y < n; y++) {
                for (int x = 0; x < n; x++) {
                    int p = bm.getPixel(x, y);
                    if (Color.alpha(p) < 128) continue;
                    Color.colorToHSV(p, hsv);
                    float wt = 0.15f + hsv[1] * hsv[2] * 2f;
                    r += Color.red(p) * wt;
                    g += Color.green(p) * wt;
                    b += Color.blue(p) * wt;
                    w += wt;
                }
            }
            bm.recycle();
            if (w == 0) return 0xFF333344;
            return Color.rgb((int) (r / w), (int) (g / w), (int) (b / w));
        } catch (Exception e) {
            return 0xFF333344;
        }
    }

    private static int shade(int c, float vMul, float vMin, float vMax) {
        float[] hsv = new float[3];
        Color.colorToHSV(c, hsv);
        hsv[2] = Math.max(vMin, Math.min(hsv[2] * vMul, vMax));
        return Color.HSVToColor(hsv);
    }

    private boolean installed(String pkg) {
        try {
            getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ---------------------------------------------------------------- rows & tiles

    private void rebuildRows() {
        String keep = lastFocused;
        favRow.removeAllViews();
        allRow.removeAllViews();

        if (updateTag != null) favRow.addView(updateTile());
        for (String p : favs()) {
            App a = apps.get(p);
            if (a != null) favRow.addView(appTile(a, true));
        }
        if (!installed(APPLE_TV_PKG) && !prefs.getBoolean("noAtvSuggest", false)) {
            favRow.addView(getAppleTvTile());
        }
        if (!prefs.getBoolean("noXbox", false)) favRow.addView(xboxTile());
        favRow.addView(settingsTile());

        Set<String> hid = hidden();
        Set<String> pinned = new HashSet<>(favs());
        for (App a : sortedApps()) {
            if (!hid.contains(a.pkg) && !pinned.contains(a.pkg)) allRow.addView(appTile(a, false));
        }

        View target = keep != null ? findTile(keep) : null;
        if (target == null && favRow.getChildCount() > 0) {
            target = favRow.getChildAt(0).findViewWithTag("tile");
        }
        final View t = target;
        if (t != null) handler.post(new Runnable() {
            @Override
            public void run() {
                t.requestFocus();
            }
        });
    }

    private View findTile(String key) {
        for (LinearLayout r : new LinearLayout[]{favRow, allRow}) {
            for (int i = 0; i < r.getChildCount(); i++) {
                View cell = r.getChildAt(i);
                if (key.equals(cell.getTag())) return cell.findViewWithTag("tile");
            }
        }
        return null;
    }

    /**
     * One cell = rounded tile + a name that fades in underneath when focused (tvOS style).
     */
    private static class Cell {
        LinearLayout root;
        FrameLayout tile;
        TextView name;
    }

    private Cell cell(boolean big, String key, String label) {
        int w = big ? px(214) : px(156);
        int h = w * 9 / 16;
        Cell c = new Cell();
        c.root = new LinearLayout(this);
        c.root.setOrientation(LinearLayout.VERTICAL);
        c.root.setGravity(Gravity.CENTER_HORIZONTAL);
        c.root.setClipChildren(false);
        c.root.setTag(key);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(w, -2);
        rlp.rightMargin = big ? px(30) : px(22);
        c.root.setLayoutParams(rlp);

        c.tile = new FrameLayout(this);
        c.tile.setTag("tile");
        c.tile.setFocusable(true);
        c.tile.setClickable(true);
        final float radius = big ? px(16) : px(12);
        c.tile.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
            }
        });
        c.tile.setClipToOutline(true);
        c.tile.setElevation(px(3));
        c.root.addView(c.tile, new LinearLayout.LayoutParams(w, h));

        c.name = text(big ? 15 : 13, 0xFFFFFFFF, tfMedium);
        c.name.setText(label);
        c.name.setSingleLine(true);
        c.name.setEllipsize(TextUtils.TruncateAt.END);
        c.name.setGravity(Gravity.CENTER);
        c.name.setShadowLayer(px(6), 0, px(1), 0x99000000);
        c.name.setAlpha(0f);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(w + px(30), -2);
        nlp.topMargin = big ? px(16) : px(13);
        c.root.addView(c.name, nlp);
        return c;
    }

    private void wireFocus(final Cell c, final String key) {
        c.tile.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean has) {
                v.animate()
                        .scaleX(has ? 1.13f : 1f)
                        .scaleY(has ? 1.13f : 1f)
                        .translationZ(has ? px(22) : 0)
                        .setDuration(180)
                        .setInterpolator(new DecelerateInterpolator())
                        .start();
                c.name.animate().alpha(has ? 1f : 0f)
                        .translationY(has ? px(6) : 0)
                        .setDuration(180).start();
                if (has) lastFocused = key;
            }
        });
    }

    private View appTile(final App a, final boolean big) {
        loadVisuals(a);
        final String key = (big ? "fav:" : "all:") + a.pkg;
        final Cell c = cell(big, key, a.label);
        FrameLayout t = c.tile;
        int h = ((LinearLayout.LayoutParams) t.getLayoutParams()).height;

        if (a.banner != null) {
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(a.color);
            t.setBackground(bg);
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setImageDrawable(a.banner);
            t.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        } else {
            GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{
                    shade(a.color, 1.0f, 0.32f, 0.66f),
                    shade(a.color, 0.6f, 0.14f, 0.40f)});
            t.setBackground(bg);
            int s = Math.round(h * 0.58f);
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setImageDrawable(a.icon);
            t.addView(iv, new FrameLayout.LayoutParams(s, s, Gravity.CENTER));
        }

        wireFocus(c, key);
        t.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                launch(a);
            }
        });
        t.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                appOptions(a, big);
                return true;
            }
        });
        t.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View v, int code, KeyEvent e) {
                if (code == KeyEvent.KEYCODE_MENU && e.getAction() == KeyEvent.ACTION_UP) {
                    appOptions(a, big);
                    return true;
                }
                return false;
            }
        });
        return c.root;
    }

    private View textTile(String key, String label, String inside, int top, int bottom) {
        final Cell c = cell(true, key, label);
        c.tile.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{top, bottom}));
        TextView tv = text(20, 0xFFFFFFFF, tfSemi);
        tv.setText(inside);
        tv.setGravity(Gravity.CENTER);
        c.tile.addView(tv, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        wireFocus(c, key);
        return c.root;
    }

    // ---------------------------------------------------------------- Xbox (HDMI input)

    private View xboxTile() {
        View root = textTile("fav:__xbox__", "Xbox", "Xbox", 0xFF2FA343, 0xFF0B5A1C);
        View tile = root.findViewWithTag("tile");
        tile.setOnClickListener(v -> openXbox());
        tile.setOnLongClickListener(v -> {
            List<String> l = new ArrayList<>();
            List<Runnable> r = new ArrayList<>();
            l.add("Choose which HDMI input the Xbox is on");
            r.add(this::chooseInput);
            l.add("Remove Xbox tile");
            r.add(() -> {
                prefs.edit().putBoolean("noXbox", true).apply();
                lastFocused = null;
                rebuildRows();
            });
            menu("Xbox", l, r);
            return true;
        });
        return root;
    }

    private List<android.media.tv.TvInputInfo> hdmiInputs() {
        List<android.media.tv.TvInputInfo> out = new ArrayList<>();
        try {
            android.media.tv.TvInputManager tim =
                    (android.media.tv.TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
            if (tim == null) return out;
            for (android.media.tv.TvInputInfo i : tim.getTvInputList()) {
                if (i.getType() == android.media.tv.TvInputInfo.TYPE_HDMI) out.add(i);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private void chooseInput() {
        final List<android.media.tv.TvInputInfo> ins = hdmiInputs();
        if (ins.isEmpty()) {
            Toast.makeText(this, "No HDMI inputs found on this projector", Toast.LENGTH_LONG).show();
            return;
        }
        List<String> l = new ArrayList<>();
        List<Runnable> r = new ArrayList<>();
        for (final android.media.tv.TvInputInfo i : ins) {
            CharSequence name = i.loadLabel(this);
            l.add(name == null || name.length() == 0 ? i.getId() : name.toString());
            r.add(() -> {
                prefs.edit().putString("xboxInput", i.getId()).apply();
                openXbox();
            });
        }
        menu("Xbox is plugged into\u2026", l, r);
    }

    private void openXbox() {
        String id = prefs.getString("xboxInput", null);
        List<android.media.tv.TvInputInfo> ins = hdmiInputs();
        if (id == null && ins.size() == 1) id = ins.get(0).getId();
        if (id == null && ins.size() > 1) {
            // Xbox is on HDMI 1: pick the input labelled 1, otherwise the first HDMI port.
            id = ins.get(0).getId();
            for (android.media.tv.TvInputInfo i : ins) {
                CharSequence n = i.loadLabel(this);
                String t = n == null ? "" : n.toString().toLowerCase(Locale.ROOT).replace(" ", "");
                if (t.equals("hdmi1") || t.endsWith("1")) {
                    id = i.getId();
                    break;
                }
            }
        }
        if (id != null) {
            try {
                Intent i = new Intent(Intent.ACTION_VIEW,
                        android.media.tv.TvContract.buildChannelUriForPassthroughInput(id));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                return;
            } catch (Exception ignored) {
            }
        }
        // Fall back to the projector's own input/source app
        for (App a : sortedApps()) {
            String n = a.label.toLowerCase(Locale.ROOT);
            if (n.contains("hdmi") || n.contains("source") || n.contains("input") || n.contains("signal")) {
                launch(a);
                return;
            }
        }
        Toast.makeText(this, "Couldn't switch input here \u2014 use the Source/Input button on the remote",
                Toast.LENGTH_LONG).show();
    }

    private View updateTile() {
        View root = textTile("fav:__update__", "Install update", "Update", 0xFF2F7CF6, 0xFF1550C8);
        root.findViewWithTag("tile").setOnClickListener(v -> Updater.install(this));
        return root;
    }

    private View settingsTile() {
        View root = textTile("fav:" + SETTINGS_TAG, "Settings", "Settings", 0xFF5A5D66, 0xFF2A2C31);
        View tile = root.findViewWithTag("tile");
        tile.setOnClickListener(v -> safeStart(new Intent(Settings.ACTION_SETTINGS)));
        tile.setOnLongClickListener(v -> {
            shelfOptions();
            return true;
        });
        return root;
    }

    private View getAppleTvTile() {
        View root = textTile("fav:__getatv__", "Get Apple TV", "Apple TV", 0xFF26272B, 0xFF050505);
        View tile = root.findViewWithTag("tile");
        tile.setOnClickListener(v -> openStore(APPLE_TV_PKG));
        tile.setOnLongClickListener(v -> {
            List<String> l = new ArrayList<>();
            List<Runnable> r = new ArrayList<>();
            l.add("Get it from Google Play");
            r.add(() -> openStore(APPLE_TV_PKG));
            l.add("Remove this suggestion");
            r.add(() -> {
                prefs.edit().putBoolean("noAtvSuggest", true).apply();
                lastFocused = null;
                rebuildRows();
            });
            menu("Apple TV", l, r);
            return true;
        });
        return root;
    }

    // ---------------------------------------------------------------- actions

    private void openStore(String pkg) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + pkg));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            safeStart(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/apps/details?id=" + pkg)));
        }
    }

    private void launch(App a) {
        PackageManager pm = getPackageManager();
        Intent i = null;
        try {
            i = pm.getLeanbackLaunchIntentForPackage(a.pkg);
        } catch (Exception ignored) {
        }
        if (i == null) i = pm.getLaunchIntentForPackage(a.pkg);
        if (i == null) {
            i = new Intent(Intent.ACTION_MAIN);
            i.setComponent(a.comp);
        }
        safeStart(i);
    }

    private void safeStart(Intent i) {
        try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "Couldn't open that", Toast.LENGTH_SHORT).show();
        }
    }

    private void menu(String heading, List<String> labels, final List<Runnable> actions) {
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(heading)
                .setItems(labels.toArray(new String[0]), (d, which) -> actions.get(which).run())
                .show();
    }

    private void appOptions(final App a, final boolean inTopRow) {
        List<String> labels = new ArrayList<>();
        List<Runnable> acts = new ArrayList<>();
        final List<String> f = favs();
        final int idx = f.indexOf(a.pkg);

        if (idx >= 0) {
            if (inTopRow && idx > 0) {
                labels.add("Move left");
                acts.add(() -> {
                    Collections.swap(f, idx, idx - 1);
                    saveFavs(f);
                    rebuildRows();
                });
            }
            if (inTopRow && idx < f.size() - 1) {
                labels.add("Move right");
                acts.add(() -> {
                    Collections.swap(f, idx, idx + 1);
                    saveFavs(f);
                    rebuildRows();
                });
            }
            labels.add("Remove from top row");
            acts.add(() -> {
                f.remove(a.pkg);
                saveFavs(f);
                if (inTopRow) lastFocused = null;
                rebuildRows();
            });
        } else {
            labels.add("Add to top row");
            acts.add(() -> {
                f.add(a.pkg);
                saveFavs(f);
                rebuildRows();
            });
        }
        if (!inTopRow) {
            labels.add("Hide from this row");
            acts.add(() -> {
                Set<String> h = hidden();
                h.add(a.pkg);
                saveHidden(h);
                lastFocused = null;
                rebuildRows();
            });
        }
        labels.add("App info");
        acts.add(() -> safeStart(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + a.pkg))));
        labels.add("Uninstall");
        acts.add(() -> safeStart(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + a.pkg))));
        menu(a.label, labels, acts);
    }

    private void shelfOptions() {
        List<String> labels = new ArrayList<>();
        List<Runnable> acts = new ArrayList<>();
        labels.add("Make the Home button open Shelf");
        acts.add(() -> {
            Toast.makeText(this, "Find \"Shelf home button\" and turn it on", Toast.LENGTH_LONG).show();
            safeStart(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        });
        if (wallpapers.size() > 1) {
            labels.add("Next background");
            acts.add(() -> nextWallpaper(true));
        }
        labels.add("Check for updates");
        acts.add(() -> checkForUpdate(true));
        labels.add("Show hidden apps");
        acts.add(() -> {
            saveHidden(new HashSet<String>());
            rebuildRows();
        });
        labels.add("Reset top row");
        acts.add(() -> {
            prefs.edit().putString("fav", "").remove("seen").remove("noAtvSuggest").remove("noXbox").apply();
            lastFocused = null;
            reload();
        });
        labels.add("Choose default home app");
        acts.add(() -> {
            try {
                Intent i = new Intent(Settings.ACTION_HOME_SETTINGS);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            } catch (Exception e) {
                safeStart(new Intent(Settings.ACTION_SETTINGS));
            }
        });
        menu("Shelf", labels, acts);
    }
}
