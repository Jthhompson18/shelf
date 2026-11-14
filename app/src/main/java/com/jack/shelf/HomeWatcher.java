package com.jack.shelf;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Optional helper. When turned on in Accessibility settings, it watches for the
 * projector's built-in home screen and opens Shelf instead, so the remote's
 * Home button always lands on Shelf.
 */
public class HomeWatcher extends AccessibilityService {

    private static HomeWatcher instance;

    static boolean isOn() {
        return instance != null;
    }

    /** Switches the screen off (projector standby). Needs Android 9+. */
    static boolean lockNow() {
        if (instance == null || android.os.Build.VERSION.SDK_INT < 28) return false;
        return instance.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN);
    }

    private android.content.BroadcastReceiver wakeReceiver;
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());

    private void openShelf(long delay) {
        handler.postDelayed(() -> {
            Intent i = new Intent(this, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            try {
                startActivity(i);
            } catch (Exception ignored) {
            }
        }, delay);
    }

    @Override
    public void onDestroy() {
        try {
            if (wakeReceiver != null) unregisterReceiver(wakeReceiver);
        } catch (Exception ignored) {
        }
        instance = null;
        super.onDestroy();
    }

    private final Set<String> homes = new HashSet<>();
    private long last;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        refreshHomes();
        // Open Shelf whenever the projector wakes from standby...
        wakeReceiver = new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(android.content.Context c, Intent i) {
                openShelf(1200);
            }
        };
        android.content.IntentFilter f = new android.content.IntentFilter(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_USER_PRESENT);
        registerReceiver(wakeReceiver, f);
        // ...and right after a full restart.
        if (SystemClock.elapsedRealtime() < 10 * 60 * 1000L) openShelf(2500);
    }

    private void refreshHomes() {
        homes.clear();
        homes.add("com.google.android.tvlauncher");
        homes.add("com.google.android.apps.tv.launcherx");
        List<ResolveInfo> list = getPackageManager().queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0);
        for (ResolveInfo ri : list) {
            String p = ri.activityInfo.packageName;
            if (p.equals(getPackageName())) continue;
            if (p.toLowerCase().contains("settings")) continue; // FallbackHome lives in Settings
            homes.add(p);
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e == null || e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = e.getPackageName();
        if (pkg == null || !homes.contains(pkg.toString())) return;
        long now = SystemClock.elapsedRealtime();
        if (now - last < 800) return;
        last = now;
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        try {
            startActivity(i);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onInterrupt() {
    }
}
