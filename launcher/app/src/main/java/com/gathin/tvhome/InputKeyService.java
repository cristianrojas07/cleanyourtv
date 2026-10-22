package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import java.util.Collections;
import java.util.Map;

/**
 * Maps the remote's Input button to the system input switcher, from any app. Remotes such as the Chromecast voice
 * remote send KEYCODE_TV for it, others (KTC and Kalley Realtek Google TV remotes) KEYCODE_TV_INPUT; on Google TV
 * the stock launcher handles both, so with the stock launcher disabled the button does nothing. Buttons listed in
 * input_key_keycodes (config.xml or a theme) open the input switcher the same way, and app_keys start an app (a
 * YouTube button that only the stock launcher answered). Every other key passes through untouched.
 * Opt-in: INPUT_KEY_SERVICE=1 build-and-install.sh (or -InputKeyService on Windows) enables it.
 */
public class InputKeyService extends AccessibilityService {

    static final String ACTION_VIEW_INPUTS = "com.android.tv.action.VIEW_INPUTS";

    private int[] inputKeycodes = new int[0];
    private int[] closeAppsKeycodes = new int[0];
    /** The running service, for the quick panel's Free memory tile (same process). */
    private static InputKeyService instance;
    /** settings_key_panel: the remote's gear (KEYCODE_SETTINGS or settings_key_keycodes) opens the quick panel. */
    private boolean settingsPanel;
    private int[] settingsKeycodes = new int[0];
    private Map<Integer, String> appKeys = Collections.emptyMap();
    private Map<Integer, String> appScancodes = Collections.emptyMap();

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        ServiceRepair.connected(this, getClass());
        instance = this;
        RecentAppsCloser.restoreClicks(this);   // a run cut short by a crash left them off
        // From config.xml or a theme's config.json; theme.sh rebinds this service after a push.
        Config config = Config.load(this);
        inputKeycodes = config.inputKeycodes;
        closeAppsKeycodes = config.closeAppsKeycodes;
        settingsPanel = config.settingsKeyPanel;
        settingsKeycodes = config.settingsKeycodes;
        appKeys = RemoteKeys.parseAppKeys(config.appKeys);
        appScancodes = RemoteKeys.parseAppScancodes(config.appKeys);
        // tcl_remote_keys: the TV answers the buttons (RemoteKeyActivity), so stop filtering keys and let a held
        // volume key repeat again. The service stays on for Free memory and Force stop.
        if (config.tclRemoteKeys) {
            AccessibilityServiceInfo info = getServiceInfo();
            info.flags &= ~AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
            setServiceInfo(info);
        }
    }

    private boolean isSettingsKey(int keyCode) {
        if (keyCode == KeyEvent.KEYCODE_SETTINGS) return true;
        for (int code : settingsKeycodes) if (keyCode == code) return true;
        return false;
    }

    private boolean isInputKey(int keyCode) {
        if (keyCode == KeyEvent.KEYCODE_TV || keyCode == KeyEvent.KEYCODE_TV_INPUT) return true;
        for (int code : inputKeycodes) if (keyCode == code) return true;
        return false;
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (settingsPanel && isSettingsKey(event.getKeyCode())) {
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                startActivity(new Intent(this, QuickSettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
            return true;
        }
        for (int code : closeAppsKeycodes) {
            if (event.getKeyCode() != code) continue;
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) RecentAppsCloser.start(this);
            return true;
        }
        // A scancode entry is more specific than any keycode, so it goes first.
        String scanApp = appScancodes.get(event.getScanCode());
        if (scanApp != null) {
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) RemoteKeys.launch(this, scanApp);
            return true;
        }
        String app = appKeys.get(event.getKeyCode());
        if (app == null && !isInputKey(event.getKeyCode())) return false;
        if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
            if (app != null) {
                RemoteKeys.launch(this, app);
            } else {
                try {
                    startActivity(new Intent(ACTION_VIEW_INPUTS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (ActivityNotFoundException ignored) {
                }
            }
        }
        return true;
    }

    /** Unbound by Android: the user turned the helper off, so Home doesn't put it back (ServiceRepair). */
    @Override
    public boolean onUnbind(Intent intent) {
        ServiceRepair.disconnected(this, getClass());
        return super.onUnbind(intent);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    static InputKeyService current() {
        return instance;
    }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        super.onDestroy();
    }

    @Override
    public void onInterrupt() {
    }
}
