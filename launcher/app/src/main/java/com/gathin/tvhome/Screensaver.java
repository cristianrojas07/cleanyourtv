package com.gathin.tvhome;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.provider.Settings;

/**
 * Starts the TV's own screensaver (Google Photos ambient mode, Clock, whatever is chosen) from the top bar.
 * An app cannot ask Android to dream: that takes WRITE_DREAM_STATE, which only system apps such as the stock
 * Google TV home hold, and SystemUI's public Somnambulator shortcut is a stub that just finishes on Google TV.
 * So this lowers the idle timeout to its minimum and lets Android start the screensaver itself, about ten
 * seconds after the last key press (Android never goes below its 10 s floor).
 *
 * The owner's timeout is put back as soon as the screensaver starts, when they press a key first, or after
 * {@link #FALLBACK_MS}. The saved value is kept in preferences, so a process death in between is repaired at the
 * next start. Needs WRITE_SETTINGS, granted over adb: {@code appops set com.gathin.tvhome WRITE_SETTINGS allow}.
 */
final class Screensaver {

    private static final String PREFS = "screensaver";
    private static final String KEY_SAVED = "saved_timeout";
    /** Below Android's floor (config_minimumScreenOffTimeout, 10 s on TVs); the system rounds it up. */
    private static final int SHORTEST_MS = 1000;
    /** Long enough for the screensaver to start, short enough that a missed broadcast costs little. */
    private static final long FALLBACK_MS = 30_000;

    private final Context ctx;
    private final Handler handler;
    private final SharedPreferences prefs;
    private final Runnable fallback = this::restore;
    private final BroadcastReceiver started = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            restore();
        }
    };

    Screensaver(Context ctx, Handler handler) {
        this.ctx = ctx;
        this.handler = handler;
        this.prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Listens for the screensaver starting, and repairs a timeout left short by an earlier process. */
    void register() {
        IntentFilter filter = new IntentFilter(Intent.ACTION_DREAMING_STARTED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ctx.registerReceiver(started, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            ctx.registerReceiver(started, filter);
        }
        restore();
    }

    void unregister() {
        ctx.unregisterReceiver(started);
        restore();
    }

    /** A start is waiting for the screensaver: the owner's timeout is saved and the short one is in place. */
    boolean pending() {
        return prefs.contains(KEY_SAVED);
    }

    /** False when the launcher may not write system settings, or the TV has no timeout to change. */
    boolean start() {
        if (!Settings.System.canWrite(ctx)) return false;
        ContentResolver cr = ctx.getContentResolver();
        try {
            if (!pending()) {
                int current = Settings.System.getInt(cr, Settings.System.SCREEN_OFF_TIMEOUT, -1);
                if (current <= 0) return false;
                prefs.edit().putInt(KEY_SAVED, current).commit();
            }
            Settings.System.putInt(cr, Settings.System.SCREEN_OFF_TIMEOUT, SHORTEST_MS);
        } catch (SecurityException e) {
            restore();
            return false;
        }
        handler.removeCallbacks(fallback);
        handler.postDelayed(fallback, FALLBACK_MS);
        return true;
    }

    /** Puts the owner's timeout back; does nothing when no start is pending. */
    void restore() {
        handler.removeCallbacks(fallback);
        if (!pending()) return;
        int saved = prefs.getInt(KEY_SAVED, -1);
        try {
            Settings.System.putInt(ctx.getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT, saved);
        } catch (SecurityException e) {
            return;   // permission gone: keep the saved value for when it comes back
        }
        prefs.edit().remove(KEY_SAVED).commit();
    }
}
