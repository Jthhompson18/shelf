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
            "stan", "binge", "kayo", "spotify", "soundcloud", "abc iview", "sbs on demand", "plex"
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
    private FrameLayout appsPanel;
    private LinearLayout appsGrid;
    private android.widget.ScrollView appsScroll;
    private boolean appsOpen;
    private View wxCard, newsCard, surfCard, npCard, upCard;
    private ImageView npArt;
    private TextView npTitle, npArtist, npSource, upWhen, upTitle;
    private boolean npVisible;
    private List<Cal.Event> calEvents;
    private FrameLayout todayPanel;
    private LinearLayout todayBody;
    private boolean todayOpen;
    private final LinkSetup linkSetup = new LinkSetup();
    private final Runnable mediaTick = new Runnable() {
        @Override
        public void run() {
            updateNowPlaying();
            updateUpNext();
            handler.postDelayed(this, 3000);
        }
    };
    private TextView surfMain, surfSub;
    private View helloBlock, sideBlock, rowsBlock, bottomShadeView;
    private boolean dreaming;
    private static final long IDLE_MS = 3 * 60 * 1000L;
    private final Runnable idleRunnable = new Runnable() {
        @Override
        public void run() {
            startDream();
        }
    };
    private HorizontalScrollView favScroll, allScroll;
    private static final String NAME = "Jack";
    private WeatherFx fx;
    private String mood = "";
    private Glance.Weather lastWx;
    private FrameLayout splash;
    private TextView splashHello, splashName, splashSub, splashDate;
    private View splashHelloRow;
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
        wake();
        handler.removeCallbacks(mediaTick);
        mediaTick.run();
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(clockTick);
        handler.removeCallbacks(wallTick);
        handler.removeCallbacks(glanceTick);
        handler.removeCallbacks(weatherRetry);
        handler.removeCallbacks(headlineTick);
        handler.removeCallbacks(idleRunnable);
        handler.removeCallbacks(mediaTick);
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
        if (appsOpen) closeApps(false);
        if (todayOpen) closeToday();
        goToStart();
    }

    @Override
    public void onBackPressed() {
        if (appsOpen) closeApps(true);
        else goToStart();
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
        bottomShadeView = bottomShade;

        // Top left: big clock, greeting, date
        LinearLayout hello = new LinearLayout(this);
        hello.setOrientation(LinearLayout.VERTICAL);
        clock = text(68, 0xFFFFFFFF, tfLight);
        clock.setShadowLayer(px(14), 0, px(2), 0x80000000);
        clock.setPadding(0, 0, px(10), px(2)); // room for the last digit and its shadow
        clock.setSingleLine(true);
        clock.setFontFeatureSettings("tnum"); // equal-width digits so the clock never changes size
        clock.setMinWidth(Math.round(clock.getPaint().measureText("88:88")) + px(14));
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
        helloBlock = hello;

        // Top right: weather + headlines in frosted cards
        LinearLayout side = new LinearLayout(this);
        side.setOrientation(LinearLayout.VERTICAL);

        LinearLayout np = glass();
        np.setOrientation(LinearLayout.HORIZONTAL);
        np.setGravity(Gravity.CENTER_VERTICAL);
        npArt = new ImageView(this);
        npArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
        final float artR = px(8);
        npArt.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), artR);
            }
        });
        npArt.setClipToOutline(true);
        np.addView(npArt, new LinearLayout.LayoutParams(px(54), px(54)));
        LinearLayout npText = new LinearLayout(this);
        npText.setOrientation(LinearLayout.VERTICAL);
        npSource = text(11, 0xCCFFFFFF, tfSemi);
        npSource.setLetterSpacing(0.12f);
        npText.addView(npSource);
        npTitle = text(16, 0xFFFFFFFF, tfMedium);
        npTitle.setSingleLine(true);
        npTitle.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams ntl = new LinearLayout.LayoutParams(-1, -2);
        ntl.topMargin = px(4);
        npText.addView(npTitle, ntl);
        npArtist = text(13, 0xBFFFFFFF, tfRegular);
        npArtist.setSingleLine(true);
        npArtist.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams nal = new LinearLayout.LayoutParams(-1, -2);
        nal.topMargin = px(2);
        npText.addView(npArtist, nal);
        LinearLayout.LayoutParams ntxl = new LinearLayout.LayoutParams(0, -2, 1f);
        ntxl.leftMargin = px(14);
        np.addView(npText, ntxl);
        side.addView(np, new LinearLayout.LayoutParams(px(300), -2));
        npCard = np;

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
        wxCard = wx;

        LinearLayout surf = glass();
        TextView surfLabel = text(12, 0xCCFFFFFF, tfSemi);
        surfLabel.setLetterSpacing(0.12f);
        surfLabel.setText("BONDI SURF");
        surf.addView(surfLabel);
        surfMain = text(22, 0xFFFFFFFF, tfLight);
        surfMain.setText("--");
        LinearLayout.LayoutParams sml = new LinearLayout.LayoutParams(-2, -2);
        sml.topMargin = px(6);
        surf.addView(surfMain, sml);
        surfSub = text(13, 0xBFFFFFFF, tfRegular);
        LinearLayout.LayoutParams ssl2 = new LinearLayout.LayoutParams(-2, -2);
        ssl2.topMargin = px(4);
        surf.addView(surfSub, ssl2);
        LinearLayout.LayoutParams sfl = new LinearLayout.LayoutParams(px(300), -2);
        sfl.topMargin = px(12);
        side.addView(surf, sfl);
        surfCard = surf;

        LinearLayout up = glass();
        TextView upLabel = text(12, 0xCCFFFFFF, tfSemi);
        upLabel.setLetterSpacing(0.12f);
        upLabel.setText("UP NEXT");
        up.addView(upLabel);
        upTitle = text(16, 0xFFFFFFFF, tfMedium);
        upTitle.setSingleLine(true);
        upTitle.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams utl = new LinearLayout.LayoutParams(-1, -2);
        utl.topMargin = px(7);
        up.addView(upTitle, utl);
        upWhen = text(13, 0xBFFFFFFF, tfRegular);
        LinearLayout.LayoutParams uwl = new LinearLayout.LayoutParams(-2, -2);
        uwl.topMargin = px(3);
        up.addView(upWhen, uwl);
        side.addView(up, new LinearLayout.LayoutParams(px(300), -2));
        upCard = up;

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
        newsCard = news;
        applyCardPrefs();

        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        slp.topMargin = px(32);
        slp.rightMargin = px(52);
        root.addView(side, slp);
        sideBlock = side;

        // Rows at the bottom
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.BOTTOM);
        col.setPadding(px(44), 0, 0, px(34));
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
        allScroll.setVisibility(View.GONE); // replaced by the Apps grid

        root.addView(col, new FrameLayout.LayoutParams(-1, -1));
        rowsBlock = col;

        // Full-screen Apps grid
        appsPanel = new FrameLayout(this);
        appsPanel.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xE6080A0F, 0xF2050608}));
        LinearLayout ap = new LinearLayout(this);
        ap.setOrientation(LinearLayout.VERTICAL);
        ap.setPadding(px(58), px(34), px(40), 0);
        ap.setClipChildren(false);
        ap.setClipToPadding(false);
        TextView appsTitle = text(30, 0xFFFFFFFF, tfSemi);
        appsTitle.setText("Apps");
        ap.addView(appsTitle);
        appsScroll = new android.widget.ScrollView(this);
        appsScroll.setVerticalScrollBarEnabled(false);
        appsScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        appsScroll.setClipChildren(false);
        appsScroll.setClipToPadding(false);
        appsScroll.setPadding(0, px(6), 0, px(40));
        appsGrid = new LinearLayout(this);
        appsGrid.setOrientation(LinearLayout.VERTICAL);
        appsGrid.setClipChildren(false);
        appsGrid.setClipToPadding(false);
        appsScroll.addView(appsGrid);
        ap.addView(appsScroll, new LinearLayout.LayoutParams(-1, -1));
        appsPanel.addView(ap, new FrameLayout.LayoutParams(-1, -1));
        appsPanel.setVisibility(View.GONE);
        root.addView(appsPanel, new FrameLayout.LayoutParams(-1, -1));

        // Welcome screen shown when the projector is switched on
        splash = new FrameLayout(this);
        splash.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xB3000000, 0x99000000, 0xCC000000}));
        LinearLayout sc = new LinearLayout(this);
        sc.setOrientation(LinearLayout.VERTICAL);
        sc.setGravity(Gravity.CENTER_HORIZONTAL);
        splashDate = text(13, 0xB3FFFFFF, tfSemi);
        splashDate.setLetterSpacing(0.28f);
        splashDate.setGravity(Gravity.CENTER);
        sc.addView(splashDate);

        LinearLayout helloRow = new LinearLayout(this);
        helloRow.setOrientation(LinearLayout.HORIZONTAL);
        helloRow.setGravity(Gravity.BOTTOM);
        splashHello = text(60, 0xFFFFFFFF, tfLight);
        splashHello.setLetterSpacing(-0.01f);
        helloRow.addView(splashHello);
        splashName = text(60, 0xFFFFFFFF, tfSemi);
        splashName.setLetterSpacing(-0.01f);
        splashName.setText(NAME);
        helloRow.addView(splashName);
        LinearLayout.LayoutParams hrl = new LinearLayout.LayoutParams(-2, -2);
        hrl.topMargin = px(14);
        sc.addView(helloRow, hrl);
        splashHelloRow = helloRow;

        View rule = new View(this);
        rule.setBackgroundColor(0x59FFFFFF);
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(px(56), Math.max(1, px(1)));
        rl.topMargin = px(22);
        sc.addView(rule, rl);

        splashSub = text(18, 0xE6FFFFFF, tfRegular);
        splashSub.setGravity(Gravity.CENTER);
        splashSub.setLetterSpacing(0.02f);
        LinearLayout.LayoutParams ssl = new LinearLayout.LayoutParams(-2, -2);
        ssl.topMargin = px(20);
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

    static String fmt(String pattern, long t) {
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
        String cond = w.condition == null || w.condition.isEmpty() ? "" : "   \u00B7   " + w.condition;
        return w.temp + "\u00B0" + cond + "   \u00B7   Bondi";
    }

    private void showWelcome() {
        if (splash == null) return;
        int hour = sydneyHour();
        splashHello.setText(greetingFor(hour));
        splashDate.setText(fmt("EEEE d MMMM", System.currentTimeMillis()).toUpperCase(Locale.ENGLISH));
        splashSub.setText(lastWx != null ? weatherLine(lastWx) : "Bondi");
        splash.animate().cancel();
        splash.setVisibility(View.VISIBLE);
        splash.setAlpha(0f);
        splashHelloRow.setTranslationY(px(14));
        splashHelloRow.setAlpha(0f);
        splashSub.setAlpha(0f);
        splash.animate().alpha(1f).setDuration(600).start();
        splashHelloRow.animate().translationY(0).alpha(1f).setStartDelay(150).setDuration(900)
                .setInterpolator(new DecelerateInterpolator(1.6f)).start();
        splashSub.animate().alpha(1f).setStartDelay(650).setDuration(800).start();
        handler.removeCallbacks(hideSplash);
        handler.postDelayed(hideSplash, 4500);
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
        if (dreaming) {
            if (e.getAction() == KeyEvent.ACTION_UP) wake();
            return true;
        }
        if (todayOpen) {
            int k = e.getKeyCode();
            if (e.getAction() == KeyEvent.ACTION_UP && (k == KeyEvent.KEYCODE_BACK
                    || k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER)) {
                closeToday();
            }
            resetIdle();
            return true;
        }
        if (e.getAction() == KeyEvent.ACTION_DOWN) resetIdle();
        if (splash != null && splash.getVisibility() == View.VISIBLE && splash.getAlpha() > 0.5f) {
            if (e.getAction() == KeyEvent.ACTION_UP) dismissWelcome();
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    private String greetingFor(int hour) {
        if (hour >= 5 && hour < 12) return "Good morning, ";
        if (hour >= 12 && hour < 17) return "Good afternoon, ";
        return "Good evening, ";
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
            final Glance.Surf sf = Glance.surf();
            final List<Cal.Event> ev = Cal.upcoming(prefs.getString("icsUrl", null));
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
                if (sf != null) showSurf(sf, w != null ? w : lastWx);
                if (ev != null) {
                    calEvents = ev;
                    updateUpNext();
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
        favRow.addView(todayTile());
        if (!prefs.getBoolean("noAirplay", false)) favRow.addView(airplayTile());
        if (!prefs.getBoolean("noXbox", false)) favRow.addView(xboxTile());
        favRow.addView(sleepTile());
        favRow.addView(appsTile());
        favRow.addView(settingsTile());

        if (appsOpen) {
            fillApps(keep);
            return;
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
        final View shine = new View(this);
        shine.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0x00FFFFFF, 0x38FFFFFF, 0x00FFFFFF}));
        shine.setRotation(18f);
        shine.setVisibility(View.INVISIBLE);
        final int tw = ((LinearLayout.LayoutParams) c.tile.getLayoutParams()).width;
        final int th = ((LinearLayout.LayoutParams) c.tile.getLayoutParams()).height;
        final boolean shineOn = false; // removed at Jack's request; kept switchable
        if (shineOn) c.tile.addView(shine, new FrameLayout.LayoutParams(Math.round(tw * 0.45f), th * 2, Gravity.CENTER_VERTICAL));
        c.tile.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean has) {
                shine.animate().cancel();
                if (has && shineOn) {
                    shine.setTranslationX(-tw * 0.6f);
                    shine.setVisibility(View.VISIBLE);
                    shine.animate().translationX(tw * 1.1f).setStartDelay(120).setDuration(750)
                            .setInterpolator(new DecelerateInterpolator(1.2f))
                            .withEndAction(new Runnable() {
                                @Override
                                public void run() {
                                    shine.setVisibility(View.INVISIBLE);
                                }
                            }).start();
                } else {
                    shine.setVisibility(View.INVISIBLE);
                }
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

    /** Netflix looks cleaner as its own app icon on black than as its boxed TV banner. */
    private boolean iconStyle(App a) {
        Set<String> on = prefs.getStringSet("iconStyle", null);
        if (on != null) return on.contains(a.pkg);
        return a.pkg.startsWith("com.netflix");
    }

    private void setIconStyle(App a, boolean v) {
        Set<String> cur = prefs.getStringSet("iconStyle", null);
        Set<String> on = new HashSet<>();
        if (cur != null) on.addAll(cur);
        else for (App x : apps.values()) if (x.pkg.startsWith("com.netflix")) on.add(x.pkg);
        if (v) on.add(a.pkg);
        else on.remove(a.pkg);
        prefs.edit().putStringSet("iconStyle", on).apply();
    }

    private View appTile(final App a, final boolean big) {
        loadVisuals(a);
        final String key = (big ? "fav:" : "all:") + a.pkg;
        final Cell c = cell(big, key, a.label);
        FrameLayout t = c.tile;
        int h = ((LinearLayout.LayoutParams) t.getLayoutParams()).height;

        if (iconStyle(a)) {
            t.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{0xFF1A1A1A, 0xFF050505}));
            int s = Math.round(h * 0.70f);
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setImageDrawable(a.icon);
            t.addView(iv, new FrameLayout.LayoutParams(s, s, Gravity.CENTER));
        } else if (a.banner != null) {
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
        final Cell c = cell(true, "fav:__xbox__", "Xbox");
        c.tile.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF22A03F, 0xFF0A4E18}));
        ImageView glyph = new ImageView(this);
        glyph.setImageDrawable(new ControllerDrawable(0xFFFFFFFF, 0xFF167A2C));
        int gh = Math.round(((LinearLayout.LayoutParams) c.tile.getLayoutParams()).height * 0.62f);
        c.tile.addView(glyph, new FrameLayout.LayoutParams(Math.round(gh * 1.5f), gh, Gravity.CENTER));
        wireFocus(c, "fav:__xbox__");
        View root = c.root;
        View tile = c.tile;
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

    // ---------------------------------------------------------------- Apps grid

    private View appsTile() {
        View root = textTile("fav:__apps__", "All apps", "Apps", 0xFF454A57, 0xFF1C1F27);
        root.findViewWithTag("tile").setOnClickListener(v -> openApps());
        return root;
    }

    private void openApps() {
        appsOpen = true;
        favScroll.setDescendantFocusability(android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        appsPanel.setVisibility(View.VISIBLE);
        appsPanel.setAlpha(0f);
        appsPanel.animate().alpha(1f).setDuration(220).start();
        appsScroll.scrollTo(0, 0);
        fillApps(null);
    }

    private void closeApps(boolean animate) {
        appsOpen = false;
        favScroll.setDescendantFocusability(android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS);
        if (animate) {
            appsPanel.animate().alpha(0f).setDuration(200).withEndAction(new Runnable() {
                @Override
                public void run() {
                    appsPanel.setVisibility(View.GONE);
                    appsGrid.removeAllViews();
                }
            }).start();
        } else {
            appsPanel.setVisibility(View.GONE);
            appsGrid.removeAllViews();
        }
        View t = findTile("fav:__apps__");
        if (t != null) t.requestFocus();
    }

    private void fillApps(String keep) {
        appsGrid.removeAllViews();
        Set<String> hid = hidden();
        List<App> list = new ArrayList<>();
        for (App a : sortedApps()) if (!hid.contains(a.pkg)) list.add(a);
        int perRow = 5;
        LinearLayout row = null;
        for (int i = 0; i < list.size(); i++) {
            if (i % perRow == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setClipChildren(false);
                row.setClipToPadding(false);
                row.setPadding(px(10), px(18), 0, px(2));
                appsGrid.addView(row);
            }
            row.addView(appTile(list.get(i), false));
        }
        View target = null;
        if (keep != null) {
            for (int r = 0; r < appsGrid.getChildCount() && target == null; r++) {
                LinearLayout rr = (LinearLayout) appsGrid.getChildAt(r);
                for (int c = 0; c < rr.getChildCount(); c++) {
                    if (keep.equals(rr.getChildAt(c).getTag())) {
                        target = rr.getChildAt(c).findViewWithTag("tile");
                        break;
                    }
                }
            }
        }
        if (target == null && appsGrid.getChildCount() > 0) {
            target = ((LinearLayout) appsGrid.getChildAt(0)).getChildAt(0).findViewWithTag("tile");
        }
        final View t = target;
        if (t != null) handler.post(new Runnable() {
            @Override
            public void run() {
                t.requestFocus();
            }
        });
    }

    // ---------------------------------------------------------------- weather / news switches

    private void applyCardPrefs() {
        boolean hasUp = upTitle.getText().length() > 0;
        // Priority if space is short: playing > weather > up next > surf > news (max three cards)
        View[] priority = {npCard, wxCard, upCard, surfCard, newsCard};
        boolean[] want = {npVisible, !prefs.getBoolean("hideWeather", false), hasUp,
                !prefs.getBoolean("hideSurf", false), !prefs.getBoolean("hideNews", false)};
        int shown = 0;
        for (int i = 0; i < priority.length; i++) {
            boolean on = want[i] && shown < 3;
            if (on) shown++;
            priority[i].setVisibility(on ? View.VISIBLE : View.GONE);
        }
        boolean first = true;
        for (View v : new View[]{npCard, wxCard, surfCard, upCard, newsCard}) {
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) v.getLayoutParams();
            lp.topMargin = (v.getVisibility() == View.VISIBLE && !first) ? px(12) : 0;
            v.setLayoutParams(lp);
            if (v.getVisibility() == View.VISIBLE) first = false;
        }
    }

    // ---------------------------------------------------------------- now playing

    private void updateNowPlaying() {
        boolean was = npVisible;
        npVisible = false;
        try {
            android.media.session.MediaSessionManager m = (android.media.session.MediaSessionManager)
                    getSystemService(Context.MEDIA_SESSION_SERVICE);
            List<android.media.session.MediaController> list =
                    m.getActiveSessions(new ComponentName(this, MediaListener.class));
            for (android.media.session.MediaController c : list) {
                android.media.session.PlaybackState st = c.getPlaybackState();
                android.media.MediaMetadata md = c.getMetadata();
                if (st == null || md == null || st.getState() != android.media.session.PlaybackState.STATE_PLAYING) {
                    continue;
                }
                String title = md.getString(android.media.MediaMetadata.METADATA_KEY_TITLE);
                if (title == null || title.isEmpty()) continue;
                String artist = md.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST);
                if (artist == null) artist = md.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
                Bitmap art = md.getBitmap(android.media.MediaMetadata.METADATA_KEY_ALBUM_ART);
                if (art == null) art = md.getBitmap(android.media.MediaMetadata.METADATA_KEY_ART);
                if (art == null) art = md.getBitmap(android.media.MediaMetadata.METADATA_KEY_DISPLAY_ICON);
                String src = c.getPackageName();
                App a = apps.get(src);
                npSource.setText("NOW PLAYING" + (a != null ? "  \u00B7  " + a.label.toUpperCase(Locale.ROOT) : ""));
                npTitle.setText(title);
                npArtist.setText(artist == null ? "" : artist);
                if (art != null) npArt.setImageBitmap(art);
                else if (a != null) {
                    loadVisuals(a);
                    npArt.setImageDrawable(a.icon);
                } else npArt.setImageDrawable(null);
                npVisible = true;
                break;
            }
        } catch (Throwable ignored) {
            // No permission yet, or this projector doesn't allow it.
        }
        if (was != npVisible) applyCardPrefs();
    }

    // ---------------------------------------------------------------- calendar

    private String timeOf(long t) {
        return fmt(DateFormat.is24HourFormat(this) ? "HH:mm" : "h:mm a", t).replace("AM", "am").replace("PM", "pm");
    }

    private void updateUpNext() {
        String before = upTitle.getText().toString();
        String title = "", when = "";
        if (calEvents != null) {
            long now = System.currentTimeMillis();
            String today = fmt("yyyyMMdd", now);
            for (Cal.Event e : calEvents) {
                if (e.end <= now) continue;
                boolean isToday = fmt("yyyyMMdd", e.start).equals(today) || e.start < now;
                if (e.allDay) {
                    if (!isToday) continue;
                    title = e.title;
                    when = "Today \u00B7 all day";
                } else {
                    title = e.title;
                    String day = isToday ? "" : "Tomorrow \u00B7 ";
                    when = e.start <= now ? "Now \u00B7 until " + timeOf(e.end)
                            : day + timeOf(e.start) + " \u2013 " + timeOf(e.end);
                }
                break;
            }
        }
        upTitle.setText(title);
        upWhen.setText(when);
        if (!before.equals(title)) applyCardPrefs();
    }

    private void calendarSetup() {
        final String addr = linkSetup.address();
        if (addr == null) {
            Toast.makeText(this, "Connect the projector to Wi-Fi first", Toast.LENGTH_LONG).show();
            return;
        }
        final AlertDialog[] box = new AlertDialog[1];
        linkSetup.start(url -> runOnUiThread(() -> {
            prefs.edit().putString("icsUrl", url).apply();
            linkSetup.stop();
            if (box[0] != null) box[0].dismiss();
            Toast.makeText(this, "Calendar added", Toast.LENGTH_LONG).show();
            refreshGlance();
        }));
        box[0] = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Add your calendar")
                .setMessage("On your phone, connected to the same Wi-Fi, open:\n\n" + addr
                        + "\n\nThen paste your calendar link there. This box closes by itself when it's saved.")
                .setNegativeButton("Cancel", null)
                .setOnDismissListener(d -> linkSetup.stop())
                .show();
    }

    // ---------------------------------------------------------------- AirPlay (AirScreen)

    private static final String AIRSCREEN = "com.ionitech.airscreen";

    private View airplayTile() {
        final Cell c = cell(true, "fav:__airplay__", "AirPlay");
        c.tile.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF3D4452, 0xFF151821}));
        ImageView g = new ImageView(this);
        g.setImageDrawable(new CastDrawable(0xFFFFFFFF));
        int gh = Math.round(((LinearLayout.LayoutParams) c.tile.getLayoutParams()).height * 0.52f);
        c.tile.addView(g, new FrameLayout.LayoutParams(gh, gh, Gravity.CENTER));
        wireFocus(c, "fav:__airplay__");
        c.tile.setOnClickListener(v -> {
            App a = null;
            for (App x : apps.values()) {
                if (x.pkg.equals(AIRSCREEN) || Cal.lower(x.label).contains("airscreen")) {
                    a = x;
                    break;
                }
            }
            if (a != null) launch(a);
            else {
                Toast.makeText(this, "Install AirScreen to AirPlay from your iPhone", Toast.LENGTH_LONG).show();
                openStore(AIRSCREEN);
            }
        });
        c.tile.setOnLongClickListener(v -> {
            List<String> l = new ArrayList<>();
            List<Runnable> r = new ArrayList<>();
            l.add("Remove AirPlay tile");
            r.add(() -> {
                prefs.edit().putBoolean("noAirplay", true).apply();
                lastFocused = null;
                rebuildRows();
            });
            menu("AirPlay", l, r);
            return true;
        });
        return c.root;
    }

    // ---------------------------------------------------------------- Today screen

    private View todayTile() {
        final Cell c = cell(true, "fav:__today__", "Today");
        c.tile.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF3A6EA5, 0xFF143154}));
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setGravity(Gravity.CENTER);
        TextView dow = text(12, 0xFFFF6B5E, tfSemi);
        dow.setLetterSpacing(0.14f);
        dow.setText(fmt("EEEE", System.currentTimeMillis()).toUpperCase(Locale.ENGLISH));
        v.addView(dow);
        TextView num = text(38, 0xFFFFFFFF, tfLight);
        num.setText(fmt("d", System.currentTimeMillis()));
        v.addView(num);
        c.tile.addView(v, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        wireFocus(c, "fav:__today__");
        c.tile.setOnClickListener(x -> openToday());
        return c.root;
    }

    private void openToday() {
        if (todayPanel == null) {
            todayPanel = new FrameLayout(this);
            todayPanel.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{0xD9050608, 0xF2050608}));
            todayBody = new LinearLayout(this);
            todayBody.setOrientation(LinearLayout.VERTICAL);
            todayBody.setPadding(px(64), px(40), px(64), px(28));
            todayPanel.addView(todayBody, new FrameLayout.LayoutParams(-1, -1));
            ((FrameLayout) splash.getParent()).addView(todayPanel, ((FrameLayout) splash.getParent()).indexOfChild(splash),
                    new FrameLayout.LayoutParams(-1, -1));
        }
        todayOpen = true;
        favScroll.setDescendantFocusability(android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        todayPanel.setVisibility(View.VISIBLE);
        todayPanel.setAlpha(0f);
        todayPanel.animate().alpha(1f).setDuration(250).start();
        renderToday(null);
        new Thread(() -> {
            final Glance.Day d = Glance.day();
            runOnUiThread(() -> {
                if (todayOpen) renderToday(d);
            });
        }).start();
    }

    private void closeToday() {
        todayOpen = false;
        favScroll.setDescendantFocusability(android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS);
        if (todayPanel != null) {
            todayPanel.animate().alpha(0f).setDuration(200).withEndAction(new Runnable() {
                @Override
                public void run() {
                    todayPanel.setVisibility(View.GONE);
                }
            }).start();
        }
        View t = findTile("fav:__today__");
        if (t != null) t.requestFocus();
    }

    private TextView label(String s) {
        TextView t = text(12, 0xB3FFFFFF, tfSemi);
        t.setLetterSpacing(0.16f);
        t.setText(s);
        return t;
    }

    private String hm(String hhmm) {
        if (hhmm == null) return "";
        if (DateFormat.is24HourFormat(this)) return hhmm;
        int h = Integer.parseInt(hhmm.substring(0, 2));
        String m = hhmm.substring(3, 5);
        return (h % 12 == 0 ? 12 : h % 12) + ":" + m + (h < 12 ? " am" : " pm");
    }

    private void renderToday(Glance.Day d) {
        todayBody.removeAllViews();
        long now = System.currentTimeMillis();
        todayBody.addView(label("TODAY"));
        TextView title = text(38, 0xFFFFFFFF, tfLight);
        title.setText(fmt("EEEE d MMMM", now));
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-2, -2);
        tl.topMargin = px(6);
        todayBody.addView(title, tl);

        LinearLayout cols = new LinearLayout(this);
        cols.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-1, 0, 1f);
        cl.topMargin = px(26);
        todayBody.addView(cols, cl);

        // Left: weather, hourly, sun, surf
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        cols.addView(left, new LinearLayout.LayoutParams(0, -1, 1.15f));
        left.addView(label("BONDI WEATHER"));
        if (d == null) {
            TextView w = text(18, 0xCCFFFFFF, tfRegular);
            w.setText("Loading\u2026");
            LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(-2, -2);
            wl.topMargin = px(10);
            left.addView(w, wl);
        } else {
            LinearLayout big = new LinearLayout(this);
            big.setOrientation(LinearLayout.HORIZONTAL);
            big.setGravity(Gravity.BOTTOM);
            TextView temp = text(56, 0xFFFFFFFF, tfLight);
            temp.setText((lastWx != null ? lastWx.temp : d.hTemp[0]) + "\u00B0");
            big.addView(temp);
            LinearLayout bi = new LinearLayout(this);
            bi.setOrientation(LinearLayout.VERTICAL);
            TextView cond = text(18, 0xFFFFFFFF, tfMedium);
            cond.setText(lastWx != null ? lastWx.condition : Glance.describeCode(d.code));
            bi.addView(cond);
            TextView hl = text(14, 0xBFFFFFFF, tfRegular);
            hl.setText("H " + d.hi + "\u00B0   L " + d.lo + "\u00B0   \u00B7   Rain " + d.rainMax + "%   \u00B7   UV "
                    + Math.round(d.uv));
            LinearLayout.LayoutParams hll = new LinearLayout.LayoutParams(-2, -2);
            hll.topMargin = px(4);
            bi.addView(hl, hll);
            LinearLayout.LayoutParams bil = new LinearLayout.LayoutParams(-2, -2);
            bil.leftMargin = px(18);
            bil.bottomMargin = px(8);
            big.addView(bi, bil);
            LinearLayout.LayoutParams bgl = new LinearLayout.LayoutParams(-2, -2);
            bgl.topMargin = px(6);
            left.addView(big, bgl);

            // hourly strip
            LinearLayout hours = new LinearLayout(this);
            hours.setOrientation(LinearLayout.HORIZONTAL);
            for (int i = 0; i < d.n; i++) {
                LinearLayout h = new LinearLayout(this);
                h.setOrientation(LinearLayout.VERTICAL);
                h.setGravity(Gravity.CENTER_HORIZONTAL);
                TextView ht = text(13, 0xB3FFFFFF, tfMedium);
                ht.setText(i == 0 ? "Now" : hm(d.hTime[i]).replace(":00", ""));
                h.addView(ht);
                TextView hv = text(22, 0xFFFFFFFF, tfLight);
                hv.setText(d.hTemp[i] + "\u00B0");
                LinearLayout.LayoutParams hvl = new LinearLayout.LayoutParams(-2, -2);
                hvl.topMargin = px(6);
                h.addView(hv, hvl);
                TextView hr = text(12, d.hRain[i] >= 40 ? 0xFF8EC5FF : 0x80FFFFFF, tfRegular);
                hr.setText(d.hRain[i] + "%");
                LinearLayout.LayoutParams hrl = new LinearLayout.LayoutParams(-2, -2);
                hrl.topMargin = px(4);
                h.addView(hr, hrl);
                hours.addView(h, new LinearLayout.LayoutParams(0, -2, 1f));
            }
            LinearLayout.LayoutParams hsl = new LinearLayout.LayoutParams(-1, -2);
            hsl.topMargin = px(20);
            hsl.rightMargin = px(40);
            left.addView(hours, hsl);

            TextView sun = text(15, 0xE6FFFFFF, tfRegular);
            sun.setText("Sunrise " + hm(d.sunrise) + "     Sunset " + hm(d.sunset));
            LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-2, -2);
            sl.topMargin = px(22);
            left.addView(sun, sl);

            if (!Double.isNaN(d.dawnWave)) {
                TextView sf = text(15, 0xE6FFFFFF, tfRegular);
                sf.setText((d.dawnTomorrow ? "Tomorrow's dawn surf  " : "Dawn surf  ")
                        + String.format(Locale.ROOT, "%.1f m", d.dawnWave) + " from the " + compass(d.dawnDir));
                LinearLayout.LayoutParams sfl = new LinearLayout.LayoutParams(-2, -2);
                sfl.topMargin = px(8);
                left.addView(sf, sfl);
            }
        }

        // Right: calendar + headlines
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        cols.addView(right, new LinearLayout.LayoutParams(0, -1, 1f));
        if (prefs.getString("icsUrl", null) != null) {
            right.addView(label("CALENDAR"));
            int shown = 0;
            if (calEvents != null) {
                String today = fmt("yyyyMMdd", now);
                for (Cal.Event e : calEvents) {
                    if (e.end <= now || shown >= 4) continue;
                    boolean isToday = fmt("yyyyMMdd", e.start).equals(today) || e.start < now;
                    TextView ev = text(16, 0xFFFFFFFF, tfMedium);
                    ev.setSingleLine(true);
                    ev.setEllipsize(TextUtils.TruncateAt.END);
                    ev.setText(e.title);
                    TextView when = text(13, 0xB3FFFFFF, tfRegular);
                    when.setText((isToday ? "" : "Tomorrow \u00B7 ")
                            + (e.allDay ? "All day" : timeOf(e.start) + " \u2013 " + timeOf(e.end)));
                    LinearLayout.LayoutParams el = new LinearLayout.LayoutParams(-1, -2);
                    el.topMargin = px(shown == 0 ? 10 : 12);
                    right.addView(ev, el);
                    LinearLayout.LayoutParams wl2 = new LinearLayout.LayoutParams(-2, -2);
                    wl2.topMargin = px(2);
                    right.addView(when, wl2);
                    shown++;
                }
            }
            if (shown == 0) {
                TextView none = text(15, 0xB3FFFFFF, tfRegular);
                none.setText("Nothing on today or tomorrow");
                LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(-2, -2);
                nl.topMargin = px(10);
                right.addView(none, nl);
            }
        }
        TextView hlab = label("HEADLINES");
        LinearLayout.LayoutParams hlb = new LinearLayout.LayoutParams(-2, -2);
        hlb.topMargin = prefs.getString("icsUrl", null) != null ? px(26) : 0;
        right.addView(hlab, hlb);
        int maxH = prefs.getString("icsUrl", null) != null ? 3 : 6;
        for (int i = 0; i < Math.min(maxH, headlines.size()); i++) {
            TextView h = text(15, 0xF2FFFFFF, tfRegular);
            h.setMaxLines(2);
            h.setEllipsize(TextUtils.TruncateAt.END);
            h.setLineSpacing(px(2), 1f);
            h.setText(headlines.get(i));
            LinearLayout.LayoutParams hl2 = new LinearLayout.LayoutParams(-1, -2);
            hl2.topMargin = px(i == 0 ? 10 : 12);
            right.addView(h, hl2);
        }

        TextView hint = text(13, 0x80FFFFFF, tfRegular);
        hint.setText("Press OK or Back to close");
        LinearLayout.LayoutParams hil = new LinearLayout.LayoutParams(-2, -2);
        hil.topMargin = px(10);
        todayBody.addView(hint, hil);
    }

    // ---------------------------------------------------------------- surf

    private static String compass(int deg) {
        String[] d = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
        return d[Math.round(((deg % 360) + 360) % 360 / 45f) % 8];
    }

    private void showSurf(Glance.Surf sf, Glance.Weather w) {
        surfMain.setText(String.format(Locale.ROOT, "%.1f m  \u00B7  %ds  \u00B7  %s",
                sf.wave, Math.round(sf.period), compass(sf.dir)));
        StringBuilder sub = new StringBuilder();
        if (!Double.isNaN(sf.water)) sub.append("Water ").append(Math.round(sf.water)).append("\u00B0");
        if (w != null && w.wind >= 0) {
            if (sub.length() > 0) sub.append("   \u00B7   ");
            // Bondi faces roughly east-south-east, so a westerly is offshore.
            int diff = Math.abs(((w.windDir - 290) % 360 + 540) % 360 - 180);
            String kind = diff < 50 ? "offshore" : diff > 130 ? "onshore" : "cross-shore";
            sub.append(w.wind).append(" km/h ").append(compass(w.windDir)).append(" ").append(kind);
        }
        surfSub.setText(sub.toString());
    }

    // ---------------------------------------------------------------- screensaver

    private void resetIdle() {
        handler.removeCallbacks(idleRunnable);
        if (!prefs.getBoolean("noDream", false)) handler.postDelayed(idleRunnable, IDLE_MS);
    }

    private void startDream() {
        if (dreaming || appsOpen || todayOpen || splash.getVisibility() == View.VISIBLE) {
            resetIdle();
            return;
        }
        dreaming = true;
        for (View v : new View[]{sideBlock, rowsBlock, bottomShadeView}) {
            v.animate().alpha(0f).setDuration(1500).start();
        }
        helloBlock.animate().alpha(0.85f).setDuration(1500).start();
    }

    private void wake() {
        boolean was = dreaming;
        dreaming = false;
        for (View v : new View[]{sideBlock, rowsBlock, bottomShadeView, helloBlock}) {
            if (v == null) continue;
            v.animate().cancel();
            if (was) v.animate().alpha(1f).setDuration(400).start();
            else v.setAlpha(1f);
        }
        resetIdle();
    }

    // ---------------------------------------------------------------- goodnight

    private View sleepTile() {
        long at = prefs.getLong("sleepAt", 0);
        boolean set = at > System.currentTimeMillis();
        String label = set ? "Sleeping at " + fmt(DateFormat.is24HourFormat(this) ? "HH:mm" : "h:mm a", at) : "Goodnight";
        final Cell c = cell(true, "fav:__sleep__", label);
        c.tile.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF2B3360, 0xFF0E1230}));
        ImageView moon = new ImageView(this);
        moon.setImageDrawable(new MoonDrawable(0xFFF3E9C6));
        int mh = Math.round(((LinearLayout.LayoutParams) c.tile.getLayoutParams()).height * 0.46f);
        c.tile.addView(moon, new FrameLayout.LayoutParams(mh, mh, Gravity.CENTER));
        wireFocus(c, "fav:__sleep__");
        c.tile.setOnClickListener(v -> sleepMenu());
        return c.root;
    }

    private void sleepMenu() {
        List<String> l = new ArrayList<>();
        List<Runnable> r = new ArrayList<>();
        l.add("Turn off now");
        r.add(() -> {
            if (!SleepReceiver.sleepNow(this)) needAccessibility();
        });
        for (final int mins : new int[]{30, 60, 90}) {
            l.add("Turn off in " + mins + " minutes");
            r.add(() -> setSleep(mins));
        }
        if (prefs.getLong("sleepAt", 0) > System.currentTimeMillis()) {
            l.add("Cancel sleep timer");
            r.add(() -> setSleep(0));
        }
        menu("Goodnight, " + NAME, l, r);
    }

    private void setSleep(int mins) {
        android.app.AlarmManager am = (android.app.AlarmManager) getSystemService(Context.ALARM_SERVICE);
        int flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT
                | (android.os.Build.VERSION.SDK_INT >= 23 ? android.app.PendingIntent.FLAG_IMMUTABLE : 0);
        android.app.PendingIntent pi = android.app.PendingIntent.getBroadcast(this, 7,
                new Intent(this, SleepReceiver.class), flags);
        am.cancel(pi);
        if (mins <= 0) {
            prefs.edit().remove("sleepAt").apply();
            Toast.makeText(this, "Sleep timer cancelled", Toast.LENGTH_SHORT).show();
        } else {
            if (!HomeWatcher.isOn()) {
                needAccessibility();
                return;
            }
            long at = System.currentTimeMillis() + mins * 60000L;
            am.set(android.app.AlarmManager.RTC_WAKEUP, at, pi);
            prefs.edit().putLong("sleepAt", at).apply();
            Toast.makeText(this, "Projector will turn off in " + mins + " minutes", Toast.LENGTH_LONG).show();
        }
        rebuildRows();
    }

    private void needAccessibility() {
        Toast.makeText(this, "Turn on \"Shelf home button\" first \u2014 it lets Shelf switch the projector off",
                Toast.LENGTH_LONG).show();
        safeStart(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
    }

    private void toggle(String key) {
        prefs.edit().putBoolean(key, !prefs.getBoolean(key, false)).apply();
        applyCardPrefs();
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
            labels.add("Hide app");
            acts.add(() -> {
                Set<String> h = hidden();
                h.add(a.pkg);
                saveHidden(h);
                lastFocused = null;
                rebuildRows();
            });
        }
        if (a.banner != null) {
            final boolean iconNow = iconStyle(a);
            labels.add(iconNow ? "Use the app's banner" : "Use the app's icon (cleaner)");
            acts.add(() -> {
                setIconStyle(a, !iconNow);
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
        final boolean wxHidden = prefs.getBoolean("hideWeather", false);
        labels.add(wxHidden ? "Show weather" : "Hide weather");
        acts.add(() -> toggle("hideWeather"));
        final boolean hasCal = prefs.getString("icsUrl", null) != null;
        labels.add(hasCal ? "Change calendar" : "Add your calendar");
        acts.add(this::calendarSetup);
        if (hasCal) {
            labels.add("Remove calendar");
            acts.add(() -> {
                prefs.edit().remove("icsUrl").apply();
                calEvents = null;
                updateUpNext();
            });
        }
        labels.add("Set up Now Playing");
        acts.add(() -> {
            Toast.makeText(this, "Turn on \"Shelf now playing\"", Toast.LENGTH_LONG).show();
            try {
                Intent i = new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS");
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            } catch (Exception ex) {
                Toast.makeText(this, "This projector hides that setting \u2014 Now Playing can't be switched on here",
                        Toast.LENGTH_LONG).show();
            }
        });
        if (prefs.getBoolean("noAirplay", false)) {
            labels.add("Show AirPlay tile");
            acts.add(() -> {
                prefs.edit().remove("noAirplay").apply();
                rebuildRows();
            });
        }
        final boolean surfHidden = prefs.getBoolean("hideSurf", false);
        labels.add(surfHidden ? "Show surf report" : "Hide surf report");
        acts.add(() -> toggle("hideSurf"));
        final boolean dreamOff = prefs.getBoolean("noDream", false);
        labels.add(dreamOff ? "Turn screensaver on" : "Turn screensaver off");
        acts.add(() -> {
            prefs.edit().putBoolean("noDream", !dreamOff).apply();
            wake();
        });
        final boolean newsHidden = prefs.getBoolean("hideNews", false);
        labels.add(newsHidden ? "Show news headlines" : "Hide news headlines");
        acts.add(() -> toggle("hideNews"));
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
