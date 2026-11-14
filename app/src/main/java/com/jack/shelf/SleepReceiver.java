package com.jack.shelf;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

/** Fires when the Goodnight timer runs out. */
public class SleepReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        c.getSharedPreferences("shelf", Context.MODE_PRIVATE).edit().remove("sleepAt").apply();
        sleepNow(c);
    }

    static boolean sleepNow(Context c) {
        Toast.makeText(c, "Goodnight, Jack", Toast.LENGTH_SHORT).show();
        return HomeWatcher.lockNow();
    }
}
