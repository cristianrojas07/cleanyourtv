package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Keeps the weather card's forecast: fetched from Open-Meteo on a background thread at most once an hour, and
 * only while Home is in front (start/stop from onResume/onPause), so a TV that sits on an app all evening asks
 * nothing. The last good answer is kept in preferences and shown at once after a restart or while offline.
 */
final class Weather {

    interface Listener {
        void onForecast(Forecast forecast, long fetchedAt);
    }

    static final long REFRESH_MS = 60 * 60_000L;
    private static final long RETRY_MS = 5 * 60_000L;
    private static final int TIMEOUT_MS = 10_000;
    private static final int MAX_BYTES = 64 * 1024;
    private static final String PREFS = "weather";

    private final Handler main;
    private final SharedPreferences prefs;
    private final String url;
    private final Listener listener;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Runnable tick = this::tick;
    private boolean started, fetching;

    Weather(Context ctx, Handler main, double latitude, double longitude, Listener listener) {
        this.main = main;
        this.prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.url = Forecast.url(latitude, longitude, 4);
        this.listener = listener;
    }

    /** Shows the saved forecast for this place, then fetches a new one when it is an hour old. */
    void start() {
        started = true;
        if (url.equals(prefs.getString("url", null))) {
            try {
                listener.onForecast(Forecast.parseCsv(prefs.getString("csv", null)), prefs.getLong("at", 0));
            } catch (IllegalArgumentException ignored) {
            }
        }
        main.removeCallbacks(tick);
        tick();
    }

    void stop() {
        started = false;
        main.removeCallbacks(tick);
    }

    void shutdown() {
        stop();
        io.shutdownNow();
    }

    private void tick() {
        if (!started) return;
        long age = System.currentTimeMillis() - prefs.getLong("at", 0);
        boolean saved = url.equals(prefs.getString("url", null));
        if (saved && age >= 0 && age < REFRESH_MS) {
            main.postDelayed(tick, REFRESH_MS - age);
            return;
        }
        if (fetching) return;
        fetching = true;
        io.execute(() -> {
            String csv = null;
            Forecast forecast = null;
            try {
                csv = get(url);
                forecast = Forecast.parseCsv(csv);
            } catch (Exception ignored) {
                // Offline or a bad answer: keep showing the saved forecast and try again soon.
            }
            final String body = csv;
            final Forecast result = forecast;
            final long now = System.currentTimeMillis();
            main.post(() -> {
                fetching = false;
                if (result != null) {
                    prefs.edit().putString("url", url).putString("csv", body).putLong("at", now).apply();
                    listener.onForecast(result, now);
                }
                if (started) main.postDelayed(tick, result != null ? REFRESH_MS : RETRY_MS);
            });
        });
    }

    /** A small GET (the forecast, a place search); throws on anything but HTTP 200 or over 64 KB. */
    static String get(String address) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
        c.setConnectTimeout(TIMEOUT_MS);
        c.setReadTimeout(TIMEOUT_MS);
        c.setRequestProperty("User-Agent", "CleanYourTV-launcher");
        try {
            if (c.getResponseCode() != 200) throw new IllegalStateException("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    if (out.size() > MAX_BYTES) throw new IllegalStateException("too large");
                }
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            c.disconnect();
        }
    }
}
