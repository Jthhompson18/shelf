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

    private final Set<String> homes = new HashSet<>();
    private long last;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        refreshHomes();
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
