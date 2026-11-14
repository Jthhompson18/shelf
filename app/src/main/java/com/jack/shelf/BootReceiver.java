package com.jack.shelf;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Opens Shelf when the projector finishes starting up. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        Intent open = new Intent(c, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        try {
            c.startActivity(open);
        } catch (Exception ignored) {
            // Newer Android may block this; the "Shelf home button" accessibility switch covers it.
        }
    }
}
