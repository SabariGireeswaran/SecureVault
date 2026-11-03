package com.sabari.photovault.data;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/** Non-secret app settings: theme, backup time, auto-erase, sort, auto-lock, wrong-PIN log, disguise. */
public class AppSettings {

    public static final int SORT_NEWEST = 0, SORT_OLDEST = 1, SORT_NAME = 2, SORT_LARGEST = 3;
    private static final int MAX_LOG = 20;

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

    public int sortMode() { return p.getInt("sort", SORT_NEWEST); }
    public void setSortMode(int m) { p.edit().putInt("sort", m).apply(); }

    /** Seconds to wait before locking after leaving the app. 0 = lock immediately. */
    public int autoLockSeconds() { return p.getInt("auto_lock", 0); }
    public void setAutoLockSeconds(int s) { p.edit().putInt("auto_lock", s).apply(); }

    public boolean disguised() { return p.getBoolean("disguised", false); }
    public void setDisguised(boolean d) { p.edit().putBoolean("disguised", d).commit(); }

    // ---------------- wrong-PIN log ("intruder alert") ----------------

    /** Remembers the time of a wrong PIN. Keeps only the newest 20. */
    public void logWrongPin(long time) {
        List<Long> list = wrongPinLog();
        list.add(0, time);
        while (list.size() > MAX_LOG) list.remove(list.size() - 1);
        StringBuilder sb = new StringBuilder();
        for (long t : list) { if (sb.length() > 0) sb.append(','); sb.append(t); }
        p.edit().putString("wrong_log", sb.toString())
                .putInt("wrong_unseen", p.getInt("wrong_unseen", 0) + 1)
                .commit();
    }

    /** Newest first. */
    public List<Long> wrongPinLog() {
        List<Long> out = new ArrayList<>();
        String s = p.getString("wrong_log", "");
        if (s.isEmpty()) return out;
        for (String part : s.split(",")) {
            try { out.add(Long.parseLong(part)); } catch (NumberFormatException ignored) { }
        }
        return out;
    }

    /** How many wrong PINs happened since the user last saw the alert. */
    public int unseenWrongPins() { return p.getInt("wrong_unseen", 0); }
    public void markWrongPinsSeen() { p.edit().putInt("wrong_unseen", 0).apply(); }
    public void clearWrongPinLog() { p.edit().remove("wrong_log").putInt("wrong_unseen", 0).apply(); }
}
