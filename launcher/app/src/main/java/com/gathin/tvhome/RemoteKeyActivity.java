package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Bundle;

/**
 * tcl_remote_keys: TCL Google TV can start an activity of our choosing for a remote button (the CustomKeyList and
 * CustomKey_<keycode> system settings, written by examples/tcl-dormitorio/instalar-launcher.ps1). It starts this
 * one with the button's keycode, so app_keys, close_apps_keycodes and input_key_keycodes work in any app without
 * InputKeyService filtering keys. The Settings button goes straight to QuickSettingsActivity, which closes when
 * anything comes in front of it, this activity too.
 */
public class RemoteKeyActivity extends Activity {

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        int keyCode = getIntent().getIntExtra("keycode", 0);
        Config config = Config.load(this);
        String app = RemoteKeys.parseAppKeys(config.appKeys).get(keyCode);
        if (contains(config.closeAppsKeycodes, keyCode)) {
            InputKeyService service = InputKeyService.current();
            if (service != null) RecentAppsCloser.start(service);
        } else if (app != null) {
            RemoteKeys.launch(this, app);
        } else if (contains(config.inputKeycodes, keyCode)) {
            try {
                startActivity(new Intent(InputKeyService.ACTION_VIEW_INPUTS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (ActivityNotFoundException ignored) {
            }
        }
        finish();
    }

    private static boolean contains(int[] codes, int keyCode) {
        for (int code : codes) if (code == keyCode) return true;
        return false;
    }
}
