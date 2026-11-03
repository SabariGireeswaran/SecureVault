package com.sabari.photovault.ui;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;

import com.sabari.photovault.data.AppSettings;

/**
 * DISGUISE MODE: switches the home-screen icon between "SecureVault" and "Calculator".
 *
 * How it works: the manifest has two "activity-alias" entries that both open the lock screen.
 * We enable one and disable the other. The NEW one is enabled FIRST, so there is never
 * a moment with no icon at all.
 */
public final class Disguise {

    private static final String DEFAULT = "com.sabari.photovault.LauncherDefault";
    private static final String CALC = "com.sabari.photovault.LauncherCalculator";

    private Disguise() {}

    public static void set(Context c, boolean disguised) {
        PackageManager pm = c.getPackageManager();
        ComponentName on = new ComponentName(c, disguised ? CALC : DEFAULT);
        ComponentName off = new ComponentName(c, disguised ? DEFAULT : CALC);
        pm.setComponentEnabledSetting(on, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
        pm.setComponentEnabledSetting(off, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
        new AppSettings(c).setDisguised(disguised);
    }
}
