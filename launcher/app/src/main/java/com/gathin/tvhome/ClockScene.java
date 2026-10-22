package com.gathin.tvhome;

import android.animation.ArgbEvaluator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The clock screensaver's scene (layout dream_clock): the thin clock and date on black over a slow ambient
 * glow. Every minute the clock drifts to a new spot and the glow shifts colour, so nothing burns in.
 * Shared by ClockDream (the system screensaver) and ClockActivity (Home left idle).
 * With dream_wallpapers the theme's wallpapers take the glow's place: one every dream_wallpaper_seconds, darker
 * than on Home, faded in over the last.
 */
final class ClockScene {

    private static final long MOVE_MS = 60_000L;
    private static final long PHOTO_FADE_MS = 2000L;
    /** The pictures are darker than on Home, at least this much (percent): the screensaver runs in dark rooms. */
    private static final int PHOTO_MIN_DIM = 60;
    /** The glow's colour change: 16 steps over 8 s. Animated per frame it redrew the full-screen gradient ~480 times. */
    private static final int GLOW_STEPS = 16;
    private static final long GLOW_STEP_MS = 500L;
    /** Deep, low-brightness glow colours; the glow is drawn at partial alpha on black. */
    private static final int[] PALETTE = {0xFF2B5CFF, 0xFF00A39A, 0xFF8A3FFC, 0xFFE0661B, 0xFFD6336C};

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private final View content;
    private final GradientDrawable glow;
    private int colorIndex;
    private int glowColor;

    /** dream_wallpapers: the pictures, and two layers to fade the next one in over the one on screen. */
    private final List<File> photos = new ArrayList<>();
    /** Every picture once, in a shuffled order, before any comes back. */
    private final List<File> tour = new ArrayList<>();
    private long photoMs;
    private Bitmap.Config photoConfig;
    private int photoDim;
    private ExecutorService decoder;
    private ImageView photoBack;
    private ImageView photoFront;
    private Bitmap photo;
    private String photoName = "";

    private final Runnable nextPhoto = new Runnable() {
        @Override
        public void run() {
            showNextPhoto();
            handler.postDelayed(this, photoMs);
        }
    };

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            moveClock();
            if (photos.isEmpty()) shiftGlow();
            handler.postDelayed(this, MOVE_MS);
        }
    };

    /** root is the inflated dream_clock layout. */
    ClockScene(View root) {
        content = root.findViewById(R.id.dreamContent);
        View ambient = root.findViewById(R.id.dreamAmbient);
        glow = new GradientDrawable();
        glow.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        ambient.setBackground(glow);
        ambient.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) ->
                glow.setGradientRadius((r - l) * 0.55f));
        colorIndex = random.nextInt(PALETTE.length);
        setGlow(PALETTE[colorIndex]);

        Context context = root.getContext();
        Config config = Config.load(context);
        if (config.dreamWallpapers && config.wallpaperEnabled && !config.wallpapers.isEmpty()) {
            photos.addAll(config.wallpapers);
            photoMs = config.dreamWallpaperSeconds * 1000L;
            photoConfig = config.wallpaperTrueColor ? Bitmap.Config.ARGB_8888 : Bitmap.Config.RGB_565;
            photoDim = Math.max(PHOTO_MIN_DIM, config.wallpaperDim) * 255 / 100 << 24;
            ambient.setVisibility(View.GONE);
            ViewGroup parent = (ViewGroup) ambient.getParent();
            photoBack = photoLayer(context);
            photoFront = photoLayer(context);
            parent.addView(photoBack, 0, new FrameLayout.LayoutParams(-1, -1));
            parent.addView(photoFront, 1, new FrameLayout.LayoutParams(-1, -1));
        }
    }

    private static ImageView photoLayer(Context context) {
        ImageView view = new ImageView(context);
        view.setScaleType(ImageView.ScaleType.CENTER_CROP);
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return view;
    }

    void start() {
        content.setAlpha(0f);
        content.post(() -> {
            placeClock();
            content.animate().alpha(1f).setDuration(1200).start();
        });
        handler.removeCallbacks(tick);
        handler.postDelayed(tick, MOVE_MS);
        if (!photos.isEmpty() && decoder == null) {
            decoder = Executors.newSingleThreadExecutor();
            handler.post(nextPhoto);
        }
    }

    void stop() {
        handler.removeCallbacksAndMessages(null);   // the tick, the next picture and any glow steps still queued
        content.animate().cancel();
        if (decoder == null) return;
        decoder.shutdownNow();
        decoder = null;
        photoFront.animate().cancel();
        photoFront.setImageDrawable(null);
        photoBack.setImageDrawable(null);
        if (photo != null) {
            Bitmap old = photo;
            photo = null;
            handler.postDelayed(old::recycle, 500);   // after the cleared layers' last frame
        }
    }

    private void moveClock() {
        content.animate().alpha(0f).setDuration(900).withLayer().withEndAction(() -> {
            placeClock();
            content.animate().alpha(1f).setDuration(1200).withLayer().start();
        }).start();
    }

    /** A random spot that keeps the whole clock on screen, with the glow centred behind it. */
    private void placeClock() {
        View parent = (View) content.getParent();
        int maxX = Math.max(0, parent.getWidth() - content.getWidth());
        int maxY = Math.max(0, parent.getHeight() - content.getHeight());
        float x = maxX * (0.1f + 0.8f * random.nextFloat());
        float y = maxY * (0.1f + 0.8f * random.nextFloat());
        content.setX(x);
        content.setY(y);
        if (parent.getWidth() > 0 && parent.getHeight() > 0) {
            glow.setGradientCenter((x + content.getWidth() / 2f) / parent.getWidth(),
                    (y + content.getHeight() / 2f) / parent.getHeight());
        }
    }

    /** Decodes the next picture of the tour off the UI thread, then fades it in. */
    private void showNextPhoto() {
        if (tour.isEmpty()) {
            tour.addAll(photos);
            Collections.shuffle(tour, random);
            // A new round doesn't open with the picture the last one ended on.
            if (tour.size() > 1 && tour.get(0).getName().equals(photoName)) Collections.swap(tour, 0, tour.size() - 1);
        }
        if (tour.isEmpty()) return;
        File next = tour.remove(0);
        DisplayMetrics metrics = content.getResources().getDisplayMetrics();
        int width = metrics.widthPixels, height = metrics.heightPixels;
        ExecutorService running = decoder;
        running.execute(() -> {
            Bitmap bitmap = WallpaperPlayer.decode(next, width, height, photoConfig, photoDim);
            handler.post(() -> {
                if (decoder != running) {   // stopped meanwhile
                    if (bitmap != null) bitmap.recycle();
                    return;
                }
                if (bitmap == null) {
                    photos.remove(next);   // unreadable: the next turn picks another
                    return;
                }
                photoName = next.getName();
                fadeIn(bitmap);
            });
        });
    }

    /**
     * The new picture fades in on the front layer over the one on screen, then moves to the back layer and the old
     * one is released: two pictures in memory only during the fade.
     */
    private void fadeIn(Bitmap bitmap) {
        Bitmap old = photo;
        photo = bitmap;
        photoFront.setImageBitmap(bitmap);
        photoFront.setAlpha(0f);
        photoFront.animate().alpha(1f).setDuration(PHOTO_FADE_MS).withEndAction(() -> {
            photoBack.setImageBitmap(bitmap);
            photoFront.setImageDrawable(null);
            photoFront.setAlpha(0f);
            if (old != null) handler.postDelayed(old::recycle, 500);
        }).start();
    }

    private void shiftGlow() {
        int from = glowColor;
        colorIndex = (colorIndex + 1) % PALETTE.length;
        int to = PALETTE[colorIndex];
        ArgbEvaluator mix = new ArgbEvaluator();
        for (int i = 1; i <= GLOW_STEPS; i++) {
            float f = (float) i / GLOW_STEPS;
            handler.postDelayed(() -> setGlow((int) mix.evaluate(f, from, to)), i * GLOW_STEP_MS);
        }
    }

    private void setGlow(int color) {
        glowColor = color;
        int c = color & 0x00FFFFFF;
        glow.setColors(new int[]{0x40000000 | c, 0x14000000 | c, 0x00000000 | c});
    }
}
