package com.gathin.tvhome;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * The panel the remote's Settings button opens when {@code settings_key_panel} is on, in the manner of Google TV's
 * quick settings: a translucent side panel on the right with the time, a grid of tiles (network, inputs, picture,
 * sound, game mode, accessibility, apps, free memory) and the full TV and launcher settings below. Translucent and in
 * a task of its own, so the app behind keeps running and Back returns to it. Pressing Settings again closes it.
 */
public final class QuickSettingsActivity extends Activity {
    /** TCL's picture and sound menus answer these; other TVs fall back to the stock screens. */
    private static final String ACTION_SOUND = "com.android.tv.action.VIEW_SOUND_SETTINGS";
    private static final String ACTION_GAME_MODE = "com.android.tv.settings.gamemode";

    private float dp;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_quick_settings);
        dp = getResources().getDisplayMetrics().density;
        findViewById(R.id.scrim).setOnClickListener(v -> finish());

        GridLayout tiles = findViewById(R.id.tiles);
        addTile(tiles, R.drawable.ic_wifi, getString(R.string.quick_network), networkName(),
                new Intent(Settings.ACTION_WIFI_SETTINGS));
        addTile(tiles, R.drawable.ic_input, getString(R.string.inputs), null,
                new Intent("com.android.tv.action.VIEW_INPUTS"));
        addTile(tiles, R.drawable.ic_picture, getString(R.string.quick_picture), null,
                new Intent(Settings.ACTION_DISPLAY_SETTINGS));
        addTile(tiles, R.drawable.ic_sound, getString(R.string.quick_sound), null, soundIntent());
        addTile(tiles, R.drawable.ic_gamepad, getString(R.string.quick_game_mode), null, new Intent(ACTION_GAME_MODE));
        addTile(tiles, R.drawable.ic_accessibility, getString(R.string.quick_accessibility), null,
                new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        addTile(tiles, R.drawable.ic_apps, getString(R.string.quick_apps), getString(R.string.quick_apps_hint),
                new Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS));
        View free = addTile(tiles, R.drawable.ic_memory, getString(R.string.quick_free_memory), null, null);
        free.setOnClickListener(v -> freeMemory());

        LinearLayout rows = findViewById(R.id.rows);
        addRow(rows, R.drawable.ic_settings, R.string.tv_settings, new Intent(Settings.ACTION_SETTINGS));
        // Through Home, so it rebuilds with the new choices when Launcher settings closes.
        addRow(rows, R.drawable.ic_home_settings, R.string.launcher_settings,
                new Intent(this, MainActivity.class).setAction(MainActivity.ACTION_LAUNCHER_SETTINGS));
        tiles.getChildAt(0).requestFocus();

        View panel = findViewById(R.id.panel);
        panel.setTranslationX(340 * dp);
        panel.animate().translationX(0).setDuration(220).start();
    }

    /** Settings pressed again while the panel is up. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        finish();
    }

    /** Anything else taking the screen (Home, an app, a screensaver) closes the panel. */
    @Override
    protected void onPause() {
        super.onPause();
        finish();
        overridePendingTransition(0, 0);
    }

    private Intent soundIntent() {
        Intent tcl = new Intent(ACTION_SOUND);
        return tcl.resolveActivity(getPackageManager()) != null ? tcl : new Intent(Settings.ACTION_SOUND_SETTINGS);
    }

    @SuppressWarnings("deprecation")
    private String networkName() {
        try {
            WifiManager wifi = getApplicationContext().getSystemService(WifiManager.class);
            WifiInfo info = wifi == null ? null : wifi.getConnectionInfo();
            String ssid = info == null ? null : info.getSSID();
            if (ssid == null) return null;
            ssid = ssid.replace("\"", "").trim();
            return ssid.isEmpty() || ssid.contains("unknown") ? null : ssid;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Same as the Guard button: the panel goes first, so the frozen screen behind the progress card is clean. */
    private void freeMemory() {
        InputKeyService service = InputKeyService.current();
        if (service == null) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
            return;
        }
        finish();
        new Handler(Looper.getMainLooper()).postDelayed(() -> RecentAppsCloser.start(service), 350);
    }

    @SuppressLint("ResourceType")   // pill_text is a colour selector kept in drawable/
    private View addTile(GridLayout grid, int icon, String title, String subtitle, Intent target) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER_VERTICAL);
        tile.setBackgroundResource(R.drawable.qs_tile_bg);
        tile.setFocusable(true);
        int pad = Math.round(12 * dp);
        tile.setPadding(pad, 0, pad, 0);

        ImageView image = new ImageView(this);
        image.setImageResource(icon);
        image.setImageTintList(getColorStateList(R.drawable.pill_text));
        image.setDuplicateParentStateEnabled(true);
        tile.addView(image, new LinearLayout.LayoutParams(Math.round(20 * dp), Math.round(20 * dp)));

        TextView name = text(title, 13, R.drawable.pill_text, R.font.outfit_medium);
        name.setPadding(0, Math.round(5 * dp), 0, 0);
        tile.addView(name);
        if (subtitle != null) tile.addView(text(subtitle, 11, R.drawable.qs_subtitle, R.font.outfit_regular));

        GridLayout.LayoutParams lp = new GridLayout.LayoutParams(
                GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f));
        lp.width = 0;
        lp.height = Math.round(64 * dp);
        int m = Math.round(4 * dp);
        lp.setMargins(m, m, m, m);
        grid.addView(tile, lp);
        if (target != null) tile.setOnClickListener(v -> open(target));
        return tile;
    }

    @SuppressLint("ResourceType")   // pill_text is a colour selector kept in drawable/
    private void addRow(LinearLayout rows, int icon, int title, Intent target) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.qs_tile_bg);
        row.setFocusable(true);
        int pad = Math.round(12 * dp);
        row.setPadding(pad, 0, pad, 0);
        ImageView image = new ImageView(this);
        image.setImageResource(icon);
        image.setImageTintList(getColorStateList(R.drawable.pill_text));
        image.setDuplicateParentStateEnabled(true);
        row.addView(image, new LinearLayout.LayoutParams(Math.round(18 * dp), Math.round(18 * dp)));
        TextView name = text(getString(title), 12, R.drawable.pill_text, R.font.outfit_medium);
        name.setPadding(Math.round(8 * dp), 0, 0, 0);
        row.addView(name);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.round(42 * dp));
        int m = Math.round(4 * dp);
        lp.setMargins(m, m, m, m);
        rows.addView(row, lp);
        row.setOnClickListener(v -> open(target));
    }

    private TextView text(String value, int sp, int color, int font) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(getColorStateList(color));
        t.setTypeface(getResources().getFont(font));
        t.setSingleLine(true);
        t.setDuplicateParentStateEnabled(true);
        return t;
    }

    private void open(Intent target) {
        try {
            startActivity(target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
        finish();
    }
}
