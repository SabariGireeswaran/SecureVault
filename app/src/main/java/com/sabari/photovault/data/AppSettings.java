package com.sabari.photovault.data;

import android.content.Context;
import android.content.SharedPreferences;

/** Non-secret app settings: theme, last backup time, auto-erase limit. */
public class AppSettings {

    private final SharedPreferences p;

    public AppSettings(Context c) {
        p = c.getApplicationContext().getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    public int themeIndex() { return p.getInt("theme", 0); }
    public void setThemeIndex(int i) { p.edit().putInt("theme", i).apply(); }

    public long lastBackup() { return p.getLong("last_backup", 0); }
    public void setLastBackup(long t) { p.edit().putLong("last_backup", t).apply(); }

    /** 0 = off. Otherwise the vault is erased after this many wrong PINs in a row. */
    public int wipeAfter() { return p.getInt("wipe_after", 0); }
    public void setWipeAfter(int n) { p.edit().putInt("wipe_after", n).apply(); }
}
