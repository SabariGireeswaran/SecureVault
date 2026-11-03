package com.sabari.photovault.ui;

import android.content.Context;

import com.sabari.photovault.data.AppSettings;

/**
 * A colour theme. Each theme is one object with its own colours (OOP: data + behaviour together).
 * Add a new theme by adding one line to ALL.
 */
public final class AppTheme {

    public final String name;
    public final int bgTop, bgBottom;     // background gradient
    public final int surface;             // cards, keys
    public final int accent, accent2;     // main gradient colours
    public final int text, muted;
    public final int danger = 0xFFFF5C7A;

    private AppTheme(String name, int bgTop, int bgBottom, int surface, int accent, int accent2, int text, int muted) {
        this.name = name;
        this.bgTop = bgTop;
        this.bgBottom = bgBottom;
        this.surface = surface;
        this.accent = accent;
        this.accent2 = accent2;
        this.text = text;
        this.muted = muted;
    }

    public static final AppTheme[] ALL = {
        new AppTheme("Aurora",    0xFF0B1026, 0xFF1E0B3D, 0xFF1C1F45, 0xFF8B5CFF, 0xFF00D4FF, 0xFFF1F3FF, 0xFF9AA3C7),
        new AppTheme("Sunset",    0xFF1C0A16, 0xFF3A1020, 0xFF2E1424, 0xFFFF5F6D, 0xFFFFC371, 0xFFFFF4F0, 0xFFC9A3AC),
        new AppTheme("Ocean",     0xFF021626, 0xFF053A52, 0xFF0B2A40, 0xFF00B4FF, 0xFF00F5A0, 0xFFEFFAFF, 0xFF8FB4C7),
        new AppTheme("Emerald",   0xFF03130E, 0xFF0B3024, 0xFF0F261E, 0xFF1EE3A0, 0xFFC6F35A, 0xFFF0FFF8, 0xFF93BBA9),
        new AppTheme("Rose Gold", 0xFF1A0F12, 0xFF33191F, 0xFF2A1A1F, 0xFFF7A1B5, 0xFFF6D29A, 0xFFFFF5F2, 0xFFC7A9AE),
        new AppTheme("Neon",      0xFF07060F, 0xFF170A2B, 0xFF181430, 0xFFFF3CAC, 0xFF2B86FF, 0xFFF7F2FF, 0xFFA59BC4),
    };

    public static AppTheme current(Context c) {
        int i = new AppSettings(c).themeIndex();
        return ALL[(i >= 0 && i < ALL.length) ? i : 0];
    }

    /** Bright colours for album chips, picked by the album name so each album keeps its colour. */
    private static final int[] ALBUM_COLORS = {
        0xFFFF6B6B, 0xFFFFA94D, 0xFFFFD43B, 0xFF69DB7C, 0xFF38D9A9, 0xFF4DABF7, 0xFF9775FA, 0xFFF783AC
    };

    public static int albumColor(String album) {
        return ALBUM_COLORS[Math.abs(album.hashCode()) % ALBUM_COLORS.length];
    }
}
