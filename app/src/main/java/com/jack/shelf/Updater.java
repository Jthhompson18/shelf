package com.jack.shelf;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Checks GitHub for a newer Shelf build and installs it in place. */
final class Updater {

    private static final String API = "https://api.github.com/repos/Jthhompson18/shelf/releases/latest";
    private static final String APK = "https://github.com/Jthhompson18/shelf/releases/latest/download/shelf.apk";
    private static boolean busy;

    private Updater() {
    }

    /** Returns the newer release tag (e.g. "v7"), or null if this is the latest. Off the UI thread. */
    static String newerTag(Context c) {
        try {
            HttpURLConnection h = (HttpURLConnection) new URL(API).openConnection();
            h.setConnectTimeout(8000);
            h.setReadTimeout(8000);
            h.setRequestProperty("Accept", "application/vnd.github+json");
            h.setRequestProperty("User-Agent", "Shelf");
            String body;
            try (InputStream in = h.getInputStream()) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] b = new byte[8192];
                int r;
                while ((r = in.read(b)) > 0) bo.write(b, 0, r);
                body = bo.toString("UTF-8");
            }
            String tag = new JSONObject(body).getString("tag_name");
            int latest = Integer.parseInt(tag.replaceAll("[^0-9]", ""));
            @SuppressWarnings("deprecation")
            int mine = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionCode;
            return latest > mine ? tag : null;
        } catch (Throwable t) {
            return null;
        }
    }

    static void install(final Context c) {
        if (Build.VERSION.SDK_INT >= 26 && !c.getPackageManager().canRequestPackageInstalls()) {
            Toast.makeText(c, "Allow Shelf to install updates, then press Back and select Update again",
                    Toast.LENGTH_LONG).show();
            Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + c.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                c.startActivity(i);
            } catch (Exception ignored) {
            }
            return;
        }
        if (busy) return;
        busy = true;
        Toast.makeText(c, "Downloading update…", Toast.LENGTH_SHORT).show();
        final Context app = c.getApplicationContext();
        new Thread(() -> {
            String err = null;
            try {
                PackageInstaller pi = app.getPackageManager().getPackageInstaller();
                PackageInstaller.SessionParams sp =
                        new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                sp.setAppPackageName(app.getPackageName());
                int id = pi.createSession(sp);
                try (PackageInstaller.Session s = pi.openSession(id)) {
                    HttpURLConnection h = (HttpURLConnection) new URL(APK).openConnection();
                    h.setInstanceFollowRedirects(true);
                    h.setConnectTimeout(15000);
                    h.setReadTimeout(30000);
                    try (InputStream in = h.getInputStream(); OutputStream out = s.openWrite("shelf.apk", 0, -1)) {
                        byte[] b = new byte[65536];
                        int r;
                        while ((r = in.read(b)) > 0) out.write(b, 0, r);
                        s.fsync(out);
                    }
                    int flags = PendingIntent.FLAG_UPDATE_CURRENT;
                    if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
                    PendingIntent pend = PendingIntent.getBroadcast(app, id,
                            new Intent(app, InstallReceiver.class), flags);
                    s.commit(pend.getIntentSender());
                }
            } catch (Throwable t) {
                err = t.getMessage();
            }
            busy = false;
            final String e = err;
            if (e != null) {
                new Handler(Looper.getMainLooper()).post(() ->
                        Toast.makeText(app, "Update failed: " + e, Toast.LENGTH_LONG).show());
            }
        }).start();
    }
}
