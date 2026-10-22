package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.annotation.TargetApi;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.hardware.HardwareBuffer;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.os.Build;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Force-stops the apps used in the last hours the way a person would: Settings > Apps > the app > Force stop > OK.
 * A sideloaded launcher may not stop other apps itself (killBackgroundProcesses answers "Invalid packageName" on
 * recent firmware), but the TV's own Settings may. Driven by InputKeyService, which needs canRetrieveWindowContent.
 *
 * Every click is checked against the screen first: the item must be Settings' own title view reading exactly
 * "Force stop" on the details page of that very package, and OK is pressed only on a dialog titled "Force stop".
 * Anything else (another dialog, a slow screen, a firmware with other labels) skips the app or stops the run,
 * so a missed step can never land on Uninstall or Clear data.
 *
 * Meanwhile an accessibility overlay shows the screen as it was when the button was pressed, with a small progress
 * card: it takes neither focus nor touches, so Settings stays the active window the clicks go to, but none of its
 * pages show.
 */
final class RecentAppsCloser {
    private static final String SETTINGS = "com.android.tv.settings";
    private static final String TITLE_ID = "android:id/title";
    private static final String DIALOG_TITLE_ID = SETTINGS + ":id/guidance_title";
    private static final String ACTION_TITLE_ID = SETTINGS + ":id/guidedactions_item_title";
    /** "Recent": brought to the front within this window. */
    private static final long RECENT_MS = 6 * 60 * 60 * 1000L;
    /** A details page shows in about half a second; past this the app is skipped (or the run stopped). */
    private static final long STEP_TIMEOUT_MS = 2000;
    private static final long POLL_MS = 80;
    /**
     * Never stopped on any TV: Settings itself, the store and Play services. The Live TV app and the TV inputs'
     * providers are added from the TV itself (keep), whatever the firmware names them.
     */
    private static final Set<String> ALWAYS_KEEP = new HashSet<>(Arrays.asList(
            SETTINGS, "com.android.vending", "com.google.android.gms", "com.google.android.tvrecommendations"));

    /** Where a run notes that it turned the click sounds off (quietClicks), so a crash cannot leave them off. */
    private static final String PREFS = "close_apps";
    private static final String MUTED_KEY = "muted_clicks";

    private static boolean running;

    private final AccessibilityService service;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Set<String> forceStopLabels = new HashSet<>();
    private final Set<String> okLabels = new HashSet<>();
    private final List<String> queue = new ArrayList<>();
    private int index = -1;
    private int stopped;
    private long availableBeforeKb;
    private long deadline;
    private View cover;
    /** The frozen screen under the cover, recycled with it. */
    private Bitmap screenshot;
    private TextView coverStep;
    private ProgressBar coverProgress;

    private RecentAppsCloser(AccessibilityService service) {
        this.service = service;
        forceStopLabels.add(settingsString("device_apps_app_management_force_stop"));
        forceStopLabels.addAll(Arrays.asList("Forzar detención", "Force stop"));
        forceStopLabels.remove(null);
        okLabels.add(Resources.getSystem().getString(android.R.string.ok));
        okLabels.addAll(Arrays.asList("Aceptar", "OK"));
    }

    /** Starts a run unless one is going. */
    static void start(AccessibilityService service) {
        if (running) return;
        new RecentAppsCloser(service).run();
    }

    /** Force-stops one app the same way (Home's tile menu), unless a run is going. */
    static void stop(AccessibilityService service, String pkg) {
        if (running) return;
        RecentAppsCloser closer = new RecentAppsCloser(service);
        closer.queue.add(pkg);
        closer.run();
    }

    private void run() {
        if (queue.isEmpty()) queue.addAll(recentPackages());
        if (queue.isEmpty()) {
            toast(service.getString(R.string.close_apps_none));
            return;
        }
        running = true;
        quietClicks(service);
        availableBeforeKb = memAvailableKb();
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            showCover(null);
            next();
            return;
        }
        screenshotThenStart();
    }

    /** Screenshot first, so the frozen picture is the screen before any Settings page opens. */
    @TargetApi(Build.VERSION_CODES.R)
    private void screenshotThenStart() {
        service.takeScreenshot(Display.DEFAULT_DISPLAY, service.getMainExecutor(),
                new AccessibilityService.TakeScreenshotCallback() {
                    @Override
                    public void onSuccess(AccessibilityService.ScreenshotResult result) {
                        // The bitmap keeps its own reference to the buffer; ours is closed now, the bitmap's when the
                        // cover goes (hideCover), instead of a full-screen buffer waiting for the garbage collector.
                        HardwareBuffer buffer = result.getHardwareBuffer();
                        screenshot = Bitmap.wrapHardwareBuffer(buffer, result.getColorSpace());
                        buffer.close();
                        showCover(screenshot);
                        next();
                    }

                    @Override
                    public void onFailure(int errorCode) {
                        showCover(null);
                        next();
                    }
                });
    }

    private void next() {
        index++;
        if (index >= queue.size()) {
            finish();
            return;
        }
        String pkg = queue.get(index);
        if (coverStep != null) {
            coverStep.setText(service.getString(R.string.close_apps_step, label(pkg), index + 1, queue.size()));
            coverProgress.setProgress(index);
        }
        try {
            service.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK
                            | Intent.FLAG_ACTIVITY_NO_ANIMATION));
        } catch (ActivityNotFoundException e) {
            abort();
            return;
        }
        deadline = SystemClock.uptimeMillis() + STEP_TIMEOUT_MS;
        handler.postDelayed(() -> pressForceStop(pkg), POLL_MS);
    }

    /** Details page of pkg showing: press Force stop, or move on if it is greyed out (already stopped). */
    private void pressForceStop(String pkg) {
        AccessibilityNodeInfo root = settingsRoot();
        AccessibilityNodeInfo item = root == null || !shows(root, pkg) ? null : find(root, TITLE_ID, forceStopLabels);
        if (item == null) {
            if (SystemClock.uptimeMillis() < deadline) handler.postDelayed(() -> pressForceStop(pkg), POLL_MS);
            else next();   // page never showed this app's Force stop: leave the app alone
            return;
        }
        AccessibilityNodeInfo button = clickable(item);
        if (button == null || !button.isEnabled()) {
            next();
            return;
        }
        button.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        deadline = SystemClock.uptimeMillis() + STEP_TIMEOUT_MS;
        handler.postDelayed(this::confirm, POLL_MS);
    }

    /** The "Force stop" dialog: press OK. Any other dialog stops the whole run without pressing anything. */
    private void confirm() {
        AccessibilityNodeInfo root = settingsRoot();
        AccessibilityNodeInfo title = root == null ? null : find(root, DIALOG_TITLE_ID, null);
        if (title == null) {
            if (SystemClock.uptimeMillis() < deadline) handler.postDelayed(this::confirm, POLL_MS);
            else abort();
            return;
        }
        if (!forceStopLabels.contains(String.valueOf(title.getText()).trim())) {
            abort();
            return;
        }
        AccessibilityNodeInfo ok = find(root, ACTION_TITLE_ID, okLabels);
        AccessibilityNodeInfo button = ok == null ? null : clickable(ok);
        if (button == null) {
            abort();
            return;
        }
        button.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        stopped++;
        handler.postDelayed(this::next, 150);
    }

    private void finish() {
        running = false;
        restoreClicks(service);
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME);
        if (coverProgress != null) coverProgress.setProgress(queue.size());
        handler.postDelayed(() -> {
            long freedMb = Math.max(0, memAvailableKb() - availableBeforeKb) / 1024;
            report(service.getString(R.string.close_apps_done, stopped, freedMb));
        }, 1000);   // let the memory come back before measuring
    }

    private void abort() {
        running = false;
        restoreClicks(service);
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME);
        report(service.getString(R.string.close_apps_stopped, stopped));
    }

    /** The result in the card for a moment, then the cover goes (a toast when there is no cover). */
    private void report(String text) {
        if (coverStep == null) {
            hideCover();
            toast(text);
            return;
        }
        coverStep.setText(text);
        handler.postDelayed(this::hideCover, 1800);
    }

    // ---- click sounds ----------------------------------------------------------------------------

    /**
     * Force stop and OK each play the TV's click sound, two clicks per app. While a run goes the system's click
     * sounds (Settings > Sound > System sounds) are off; they come back when it ends, and the note in preferences
     * brings them back after a crash too (restoreClicks from InputKeyService and Home). Needs
     * WRITE_SECURE_SETTINGS, which the installer grants; without it the run just clicks aloud. Sounds the owner
     * had already turned off stay off.
     */
    private static void quietClicks(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        try {
            if (Settings.System.getInt(ctx.getContentResolver(), Settings.System.SOUND_EFFECTS_ENABLED, 1) == 0) return;
            prefs.edit().putBoolean(MUTED_KEY, true).commit();   // noted first, so a crash right after still restores
            Settings.System.putInt(ctx.getContentResolver(), Settings.System.SOUND_EFFECTS_ENABLED, 0);
        } catch (RuntimeException noPermission) {
            prefs.edit().remove(MUTED_KEY).apply();
        }
    }

    /** Turns the click sounds back on if a run turned them off and no run is going now. */
    static void restoreClicks(Context ctx) {
        if (running) return;
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!prefs.getBoolean(MUTED_KEY, false)) return;
        try {
            Settings.System.putInt(ctx.getContentResolver(), Settings.System.SOUND_EFFECTS_ENABLED, 1);
        } catch (RuntimeException ignored) {
        }
        prefs.edit().remove(MUTED_KEY).apply();
    }

    // ---- what to stop ----------------------------------------------------------------------------

    /** Apps with a launcher entry brought to the front in the last hours, most recent first. */
    private List<String> recentPackages() {
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        UsageStatsManager usm = service.getSystemService(UsageStatsManager.class);
        long now = System.currentTimeMillis();
        List<String> order = new ArrayList<>();
        if (usm != null) {
            UsageEvents events = usm.queryEvents(now - RECENT_MS, now);
            UsageEvents.Event e = new UsageEvents.Event();
            while (events != null && events.hasNextEvent()) {
                events.getNextEvent(e);
                if (e.getEventType() == UsageEvents.Event.ACTIVITY_RESUMED) order.add(0, e.getPackageName());
            }
        }
        Set<String> keep = keep(service.getPackageName(), Config.load(service).liveTvPackage, tvInputPackages());
        PackageManager pm = service.getPackageManager();
        for (String pkg : closable(order, keep)) {
            if (pm.getLeanbackLaunchIntentForPackage(pkg) == null && pm.getLaunchIntentForPackage(pkg) == null) continue;
            if (isStopped(pm, pkg)) continue;   // already force-stopped and not opened since: nothing to close
            seen.add(pkg);
        }
        return new ArrayList<>(seen);
    }

    /** What is never stopped: ALWAYS_KEEP, this launcher, the configured Live TV app and every TV input's provider. */
    static Set<String> keep(String self, String liveTvPackage, Collection<String> inputProviders) {
        Set<String> keep = new HashSet<>(ALWAYS_KEEP);
        keep.add(self);
        if (liveTvPackage != null && !liveTvPackage.isEmpty()) keep.add(liveTvPackage);
        keep.addAll(inputProviders);
        return keep;
    }

    /** The apps brought to the front lately, latest first and once each, less the ones kept. */
    static List<String> closable(List<String> resumedLatestFirst, Set<String> keep) {
        LinkedHashSet<String> apps = new LinkedHashSet<>();
        for (String pkg : resumedLatestFirst) if (!keep.contains(pkg)) apps.add(pkg);
        return new ArrayList<>(apps);
    }

    /** The packages that provide the TV's inputs (tuner, HDMI and the rest), as the TV lists them. */
    private Set<String> tvInputPackages() {
        Set<String> packages = new HashSet<>();
        try {
            TvInputManager tim = service.getSystemService(TvInputManager.class);
            if (tim == null) return packages;
            for (TvInputInfo input : tim.getTvInputList()) packages.add(input.getServiceInfo().packageName);
        } catch (RuntimeException ignored) {
            // No TV input framework: nothing more to keep.
        }
        return packages;
    }

    // ---- cover -----------------------------------------------------------------------------------

    /**
     * What was on screen when the button was pressed, frozen full screen, with a small progress card at the bottom
     * right. Without a screenshot (Android 10 and older, or refused) a plain black layer stands in for it.
     */
    private void showCover(Bitmap screenshot) {
        float dp = service.getResources().getDisplayMetrics().density;
        FrameLayout root = new FrameLayout(service);
        if (screenshot != null) {
            ImageView frozen = new ImageView(service);
            frozen.setScaleType(ImageView.ScaleType.FIT_XY);
            frozen.setImageBitmap(screenshot);
            root.addView(frozen, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        } else {
            root.setBackgroundColor(0xFF000000);
        }

        LinearLayout card = new LinearLayout(service);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xEB16181D);
        bg.setCornerRadius(18 * dp);
        bg.setStroke(Math.round(dp), 0x33FFFFFF);
        card.setBackground(bg);
        int padH = Math.round(22 * dp), padV = Math.round(16 * dp);
        card.setPadding(padH, padV, padH, padV);
        TextView title = new TextView(service);
        title.setText(R.string.close_apps_title);
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        title.setTypeface(service.getResources().getFont(R.font.outfit_medium));
        coverStep = new TextView(service);
        coverStep.setTextColor(0xB3FFFFFF);
        coverStep.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        coverStep.setTypeface(service.getResources().getFont(R.font.outfit_regular));
        coverStep.setPadding(0, Math.round(4 * dp), 0, Math.round(10 * dp));
        coverProgress = new ProgressBar(service, null, android.R.attr.progressBarStyleHorizontal);
        coverProgress.setMax(queue.size());
        coverProgress.setProgressTintList(ColorStateList.valueOf(0xFFFFFFFF));
        coverProgress.setProgressBackgroundTintList(ColorStateList.valueOf(0x33FFFFFF));
        card.addView(title);
        card.addView(coverStep);
        card.addView(coverProgress, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.round(4 * dp)));
        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                Math.round(320 * dp), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END);
        int margin = Math.round(40 * dp);
        cardLp.setMargins(margin, margin, margin, margin);
        root.addView(card, cardLp);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_FULLSCREEN
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        try {
            service.getSystemService(WindowManager.class).addView(root, lp);
            cover = root;
        } catch (RuntimeException e) {
            cover = null;   // no overlay: the run still works, only visibly
            coverStep = null;
            coverProgress = null;
        }
    }

    private void hideCover() {
        if (cover == null) return;
        try {
            service.getSystemService(WindowManager.class).removeView(cover);
        } catch (RuntimeException ignored) {
        }
        cover = null;
        coverStep = null;
        coverProgress = null;
        if (screenshot != null) {
            // After the overlay's last frame has gone, not under it.
            handler.postDelayed(screenshot::recycle, 500);
            screenshot = null;
        }
    }

    private String label(String pkg) {
        try {
            PackageManager pm = service.getPackageManager();
            return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    private static boolean isStopped(PackageManager pm, String pkg) {
        try {
            return (pm.getApplicationInfo(pkg, 0).flags & ApplicationInfo.FLAG_STOPPED) != 0;
        } catch (PackageManager.NameNotFoundException e) {
            return true;
        }
    }

    // ---- screen helpers --------------------------------------------------------------------------

    private AccessibilityNodeInfo settingsRoot() {
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        return root != null && SETTINGS.contentEquals(String.valueOf(root.getPackageName())) ? root : null;
    }

    /** The details page lists the package name under the version line: make sure it is this app's page. */
    private static boolean shows(AccessibilityNodeInfo root, String pkg) {
        List<AccessibilityNodeInfo> hits = root.findAccessibilityNodeInfosByText(pkg);
        for (AccessibilityNodeInfo n : hits) if (pkg.contentEquals(String.valueOf(n.getText()).trim())) return true;
        return false;
    }

    /** First node with this view id whose text is one of labels (any text when labels is null). */
    private static AccessibilityNodeInfo find(AccessibilityNodeInfo root, String viewId, Set<String> labels) {
        for (AccessibilityNodeInfo n : root.findAccessibilityNodeInfosByViewId(viewId)) {
            if (labels == null || labels.contains(String.valueOf(n.getText()).trim())) return n;
        }
        return null;
    }

    private static AccessibilityNodeInfo clickable(AccessibilityNodeInfo node) {
        for (AccessibilityNodeInfo n = node; n != null; n = n.getParent()) if (n.isClickable()) return n;
        return null;
    }

    private String settingsString(String name) {
        try {
            Resources r = service.getPackageManager().getResourcesForApplication(SETTINGS);
            int id = r.getIdentifier(name, "string", SETTINGS);
            return id == 0 ? null : r.getString(id);
        } catch (PackageManager.NameNotFoundException | Resources.NotFoundException e) {
            return null;
        }
    }

    private static long memAvailableKb() {
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/meminfo"))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("MemAvailable:")) return Long.parseLong(line.split("\\s+")[1]);
            }
        } catch (IOException | NumberFormatException | ArrayIndexOutOfBoundsException ignored) {
        }
        return 0;
    }

    private void toast(String text) {
        handler.post(() -> Toast.makeText(service, text, Toast.LENGTH_LONG).show());
    }
}
