package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.List;

/** Remote-friendly settings owned by the launcher; system settings stay one row away. */
public final class LauncherSettingsActivity extends Activity {
    private SharedPreferences choices;
    private final TextView[] columnChoices = new TextView[4];
    private final TextView[] rowChoices = new TextView[4];
    private TextView wallpaperChoice;
    private TextView dockChoice;
    private TextView animationsChoice;
    private TextView advancedChoice;
    private TextView followThemeChoice;
    private TextView weatherChoice;
    private TextView weatherPlaceChoice;
    private TextView weatherStyleChoice, weatherSizeChoice, weatherDaysChoice;
    private int columns;
    private int rowsBelowDock;
    private boolean animations;
    private boolean advancedEffects;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_launcher_settings);
        choices = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
        Config config = Config.load(this);
        columns = config.columns;
        rowsBelowDock = config.rowsBelowDock;
        animations = config.animations;
        advancedEffects = config.animations && config.advancedEffects;

        int[] ids = {R.id.columns4, R.id.columns5, R.id.columns6, R.id.columns7};
        for (int i = 0; i < ids.length; i++) {
            final int value = i + 4;
            columnChoices[i] = findViewById(ids[i]);
            columnChoices[i].setOnClickListener(v -> {
                if (columns == value) return;
                columns = value;
                choices.edit().putInt("columns", value).apply();
                setResult(RESULT_OK);
                refresh();
            });
        }
        int[] rowIds = {R.id.rows0, R.id.rows1, R.id.rows2, R.id.rows3};
        for (int i = 0; i < rowIds.length; i++) {
            final int value = i;
            rowChoices[i] = findViewById(rowIds[i]);
            rowChoices[i].setOnClickListener(v -> {
                if (rowsBelowDock == value) return;
                rowsBelowDock = value;
                choices.edit().putInt(Config.ROWS_BELOW_DOCK_KEY, value).apply();
                setResult(RESULT_OK);
                refresh();
            });
        }
        // Keep the numeric choices increasing in the Hebrew reading direction. Reorder the
        // actual views so D-pad focus follows their positions without changing the saved values.
        if (getResources().getBoolean(R.bool.rtl_app_grid)) {
            reverseRow(columnChoices);
            reverseRow(rowChoices);
        }
        wallpaperChoice = findViewById(R.id.wallpaperChoice);
        wallpaperChoice.setOnClickListener(v -> showWallpaperPicker());
        dockChoice = findViewById(R.id.dockChoice);
        dockChoice.setOnClickListener(v -> showDockPicker());
        animationsChoice = findViewById(R.id.animationsChoice);
        animationsChoice.setOnClickListener(v -> {
            animations = !animations;
            if (!animations) advancedEffects = false;
            choices.edit().putBoolean("animations", animations)
                    .putBoolean("advanced_effects", advancedEffects).apply();
            setResult(RESULT_OK);
            refresh();
        });
        advancedChoice = findViewById(R.id.advancedChoice);
        advancedChoice.setOnClickListener(v -> {
            if (advancedEffects) {
                setAdvanced(false);
            } else {
                new AlertDialog.Builder(this)
                        .setTitle(R.string.advanced_effects)
                        .setMessage(R.string.advanced_effects_warning)
                        .setPositiveButton(R.string.enable, (dialog, which) -> setAdvanced(true))
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            }
        });
        weatherChoice = findViewById(R.id.weatherChoice);
        weatherChoice.setOnClickListener(v -> {
            Config current = Config.load(this);
            // Turning it on with no place yet goes straight to the search; picking a place turns it on.
            if (!current.weatherOn && !hasWeatherPlace(current)) {
                showWeatherSearch();
                return;
            }
            choices.edit().putBoolean(Config.WEATHER_ON_KEY, !current.weatherOn).apply();
            setResult(RESULT_OK);
            refresh();
        });
        weatherPlaceChoice = findViewById(R.id.weatherPlaceChoice);
        weatherPlaceChoice.setOnClickListener(v -> showWeatherSearch());
        weatherStyleChoice = findViewById(R.id.weatherStyleChoice);
        weatherStyleChoice.setOnClickListener(v -> pickWeather(R.string.weather_style_label,
                new int[]{R.string.dock_solid, R.string.dock_translucent, R.string.dock_glass_style},
                indexOf(Config.DOCK_STYLES, choices.getString(Config.WEATHER_STYLE_KEY, Config.DOCK_GLASS)),
                which -> choices.edit().putString(Config.WEATHER_STYLE_KEY, Config.DOCK_STYLES[which]).apply()));
        weatherSizeChoice = findViewById(R.id.weatherSizeChoice);
        weatherSizeChoice.setOnClickListener(v -> pickWeather(R.string.weather_size_label,
                new int[]{R.string.weather_size_normal, R.string.weather_size_compact},
                choices.getBoolean(Config.WEATHER_COMPACT_KEY, true) ? 1 : 0,
                which -> choices.edit().putBoolean(Config.WEATHER_COMPACT_KEY, which == 1).apply()));
        weatherDaysChoice = findViewById(R.id.weatherDaysChoice);
        weatherDaysChoice.setOnClickListener(v -> pickWeather(R.string.weather_days_label,
                new int[]{R.string.weather_days_1, R.string.weather_days_2, R.string.weather_days_3, R.string.weather_days_4},
                choices.getInt(Config.WEATHER_DAYS_KEY, 4) - 1,
                which -> choices.edit().putInt(Config.WEATHER_DAYS_KEY, which + 1).apply()));
        followThemeChoice = findViewById(R.id.followThemeChoice);
        followThemeChoice.setOnClickListener(v -> {
            if (!hasOnTvChoices()) return;
            choices.edit().remove("columns").remove(Config.ROWS_BELOW_DOCK_KEY).remove("animations")
                    .remove("advanced_effects").remove(Config.WALLPAPER_CHOICE_KEY)
                    .remove(Config.DOCK_STYLE_KEY).commit();
            Config current = Config.load(this);
            columns = current.columns;
            rowsBelowDock = current.rowsBelowDock;
            animations = current.animations;
            advancedEffects = current.animations && current.advancedEffects;
            setResult(RESULT_OK);
            refresh();
        });
        findViewById(R.id.defaultHomeChoice).setOnClickListener(v -> {
            Toast.makeText(this, R.string.home_settings_help, Toast.LENGTH_LONG).show();
            open(new Intent(Settings.ACTION_HOME_SETTINGS));
        });
        findViewById(R.id.tvSettingsChoice).setOnClickListener(v ->
                open(new Intent(Settings.ACTION_SETTINGS)));
        refresh();
        columnChoices[Math.max(0, Math.min(columns - 4, 3))].requestFocus();
    }

    /** One of the weather card's looks, picked from a list; Home rebuilds with it when settings close. */
    private void pickWeather(int title, int[] labels, int checked, java.util.function.IntConsumer save) {
        String[] names = new String[labels.length];
        for (int i = 0; i < labels.length; i++) names[i] = getString(labels[i]);
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setSingleChoiceItems(names, checked, (dialog, which) -> {
                    dialog.dismiss();
                    save.accept(which);
                    setResult(RESULT_OK);
                    refresh();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static int indexOf(String[] values, String value) {
        for (int i = 0; i < values.length; i++) if (values[i].equals(value)) return i;
        return -1;
    }

    private static boolean hasWeatherPlace(Config c) {
        return !c.weatherPlace.isEmpty() && !Double.isNaN(c.weatherLatitude) && !Double.isNaN(c.weatherLongitude);
    }

    /** City name in, a short list of matching places out (Open-Meteo geocoding), OK on one saves it. */
    private void showWeatherSearch() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint(R.string.weather_search_hint);
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        String current = Config.load(this).weatherPlace;
        input.setText(current);
        input.setSelection(current.length());
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.weather_search_title)
                .setView(input)
                .setPositiveButton(R.string.weather_search, (d, which) -> searchPlaces(input.getText().toString()))
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        input.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId != EditorInfo.IME_ACTION_SEARCH && actionId != EditorInfo.IME_ACTION_DONE) return false;
            dialog.dismiss();
            searchPlaces(input.getText().toString());
            return true;
        });
        dialog.show();
    }

    private void searchPlaces(String query) {
        String q = query.trim();
        if (q.isEmpty()) return;
        String url = Places.url(q, java.util.Locale.getDefault().getLanguage());
        new Thread(() -> {
            List<Places.Place> found;
            try {
                found = Places.parse(Weather.get(url));
            } catch (Exception e) {
                found = null;
            }
            final List<Places.Place> places = found;
            runOnUiThread(() -> showPlaces(q, places));
        }, "place-search").start();
    }

    private void showPlaces(String query, List<Places.Place> places) {
        if (isFinishing() || isDestroyed()) return;
        if (places == null) {
            Toast.makeText(this, R.string.weather_offline, Toast.LENGTH_LONG).show();
            return;
        }
        if (places.isEmpty()) {
            Toast.makeText(this, getString(R.string.weather_no_results, query), Toast.LENGTH_LONG).show();
            return;
        }
        String[] labels = new String[places.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = places.get(i).label();
        new AlertDialog.Builder(this)
                .setTitle(R.string.weather_search_title)
                .setItems(labels, (d, which) -> {
                    Places.Place p = places.get(which);
                    choices.edit().putBoolean(Config.WEATHER_ON_KEY, true)
                            .putString(Config.WEATHER_PLACE_KEY, p.name)
                            .putString(Config.WEATHER_LABEL_KEY, p.label())
                            .putString(Config.WEATHER_LAT_KEY, Double.toString(p.latitude))
                            .putString(Config.WEATHER_LON_KEY, Double.toString(p.longitude))
                            .apply();
                    setResult(RESULT_OK);
                    refresh();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void reverseRow(TextView[] views) {
        LinearLayout row = (LinearLayout) views[0].getParent();
        row.removeAllViews();
        for (int i = views.length - 1; i >= 0; i--) row.addView(views[i]);
    }

    private void showWallpaperPicker() {
        File dir = Config.dir(this);
        List<File> images = new java.util.ArrayList<>(WallpaperRotation.images(
                dir == null ? null : new File(dir, "wallpapers")));
        // A theme with a single wallpaper.jpg offers that picture too (Config maps the choice back to it).
        File single = dir == null ? null : new File(dir, Config.WALLPAPER_NAME);
        if (single != null && single.isFile()) images.add(0, single);
        String[] labels = new String[images.size() + 1];
        for (int i = 0; i < images.size(); i++) labels[i] = images.get(i).getName();
        labels[images.size()] = getString(R.string.wallpaper_none);
        String current = choices.getString(Config.WALLPAPER_CHOICE_KEY, null);
        int checked = "none".equals(current) ? images.size()
                : indexOf(images, current);
        new AlertDialog.Builder(this)
                .setTitle(R.string.wallpaper_label)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    dialog.dismiss();
                    if (which == images.size()) {
                        choices.edit().putString(Config.WALLPAPER_CHOICE_KEY, "none").apply();
                    } else {
                        choices.edit().putString(Config.WALLPAPER_CHOICE_KEY,
                                images.get(which).getName()).apply();
                    }
                    setResult(RESULT_OK);
                    Toast.makeText(this, R.string.wallpaper_applied, Toast.LENGTH_SHORT).show();
                    refresh();
                })
                .setNeutralButton(R.string.wallpaper_theme_default, (dialog, which) -> {
                    choices.edit().remove(Config.WALLPAPER_CHOICE_KEY).apply();
                    setResult(RESULT_OK);
                    refresh();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** The dock band's look: solid, translucent, clear or frosted glass; the theme's own look by default. */
    private void showDockPicker() {
        String current = choices.getString(Config.DOCK_STYLE_KEY, null);
        String[] labels = new String[Config.DOCK_STYLES.length];
        int checked = -1;
        for (int i = 0; i < labels.length; i++) {
            labels[i] = getString(dockStyleName(Config.DOCK_STYLES[i]));
            if (Config.DOCK_STYLES[i].equals(current)) checked = i;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.dock_style_label)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    dialog.dismiss();
                    choices.edit().putString(Config.DOCK_STYLE_KEY, Config.DOCK_STYLES[which]).apply();
                    setResult(RESULT_OK);
                    refresh();
                })
                .setNeutralButton(R.string.wallpaper_theme_default, (dialog, which) -> {
                    choices.edit().remove(Config.DOCK_STYLE_KEY).apply();
                    setResult(RESULT_OK);
                    refresh();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static int dockStyleName(String style) {
        switch (style) {
            case Config.DOCK_SOLID:
                return R.string.dock_solid;
            case Config.DOCK_GLASS:
                return R.string.dock_glass_style;
            default:
                return R.string.dock_translucent;
        }
    }

    private static int indexOf(List<File> images, String name) {
        if (name == null) return -1;
        for (int i = 0; i < images.size(); i++) {
            if (images.get(i).getName().equals(name)) return i;
        }
        return -1;
    }

    private void setAdvanced(boolean enabled) {
        advancedEffects = enabled;
        choices.edit().putBoolean("advanced_effects", enabled).apply();
        setResult(RESULT_OK);
        refresh();
    }

    private void refresh() {
        for (int i = 0; i < columnChoices.length; i++) {
            columnChoices[i].setSelected(columns == i + 4);
        }
        for (int i = 0; i < rowChoices.length; i++) {
            rowChoices[i].setSelected(rowsBelowDock == i);
        }
        animationsChoice.setText(getString(R.string.animations_state,
                getString(animations ? R.string.on : R.string.off)));
        advancedChoice.setText(getString(R.string.advanced_effects_state,
                getString(advancedEffects ? R.string.on : R.string.off)));
        advancedChoice.setEnabled(animations);
        advancedChoice.setAlpha(animations ? 1f : 0.5f);
        String wallpaperCurrent = choices.getString(Config.WALLPAPER_CHOICE_KEY, null);
        wallpaperChoice.setText(getString(R.string.wallpaper_state,
                "none".equals(wallpaperCurrent) ? getString(R.string.wallpaper_none)
                        : wallpaperCurrent != null ? wallpaperCurrent
                        : getString(R.string.wallpaper_theme_default)));
        String dockCurrent = choices.getString(Config.DOCK_STYLE_KEY, null);
        dockChoice.setText(getString(R.string.dock_style_state, dockCurrent != null
                ? getString(dockStyleName(dockCurrent)) : getString(R.string.wallpaper_theme_default)));
        followThemeChoice.setSelected(!hasOnTvChoices());
        Config current = Config.load(this);
        weatherChoice.setText(getString(R.string.weather_state,
                getString(current.weatherOn && hasWeatherPlace(current) ? R.string.on : R.string.off)));
        weatherPlaceChoice.setText(getString(R.string.weather_place_state,
                hasWeatherPlace(current) ? current.weatherLabel : getString(R.string.weather_place_none)));
        int style = indexOf(Config.DOCK_STYLES, current.weatherStyle);
        weatherStyleChoice.setText(getString(R.string.weather_style_state, getString(
                style == 0 ? R.string.dock_solid : style == 2 ? R.string.dock_glass_style : R.string.dock_translucent)));
        weatherSizeChoice.setText(getString(R.string.weather_size_state, getString(
                current.weatherCompact ? R.string.weather_size_compact : R.string.weather_size_normal)));
        int[] dayNames = {R.string.weather_days_1, R.string.weather_days_2, R.string.weather_days_3, R.string.weather_days_4};
        weatherDaysChoice.setText(getString(R.string.weather_days_state, getString(dayNames[current.weatherDays - 1])));
        boolean weatherRows = current.weatherOn && hasWeatherPlace(current);
        for (View row : new View[]{weatherStyleChoice, weatherSizeChoice, weatherDaysChoice}) {
            row.setVisibility(weatherRows ? View.VISIBLE : View.GONE);
        }
    }

    private boolean hasOnTvChoices() {
        return choices.contains("columns") || choices.contains(Config.ROWS_BELOW_DOCK_KEY)
                || choices.contains("animations") || choices.contains("advanced_effects")
                || choices.contains(Config.WALLPAPER_CHOICE_KEY) || choices.contains(Config.DOCK_STYLE_KEY);
    }

    private void open(Intent intent) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_LONG).show();
        }
    }
}
