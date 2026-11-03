package com.sabari.photovault.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.WindowManager;

import com.sabari.photovault.crypto.Session;

/**
 * Parent class for every screen (OOP inheritance: write the security rules once, reuse everywhere).
 *  - FLAG_SECURE: blocks screenshots, screen recording, and hides the app in the "recent apps" view.
 *  - If the vault got locked while this screen was hidden, jump back to the PIN screen.
 *  - Loads the colour theme the user picked.
 */
public abstract class SecureActivity extends Activity {

    protected AppTheme t;
    private int themeAtCreate;

    /**
     * Phones let users make all text bigger (Settings → Display → Font size).
     * Very big text no longer fits inside round keys and cards, so we allow
     * up to 15% bigger text, and no more. Everything stays readable and aligned.
     */
    private static final float MAX_FONT_SCALE = 1.15f;

    @Override
    protected void attachBaseContext(android.content.Context base) {
        android.content.res.Configuration c = new android.content.res.Configuration(base.getResources().getConfiguration());
        if (c.fontScale > MAX_FONT_SCALE) {
            c.fontScale = MAX_FONT_SCALE;
            base = base.createConfigurationContext(c);
        }
        super.attachBaseContext(base);
    }

    /** Screens that need the vault to be open return true. */
    protected boolean requiresUnlock() { return true; }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);
        t = AppTheme.current(this);
        themeAtCreate = indexOf(t);
        Ui.applyWindow(this, t);
        getWindow().setBackgroundDrawable(Ui.background(t));
        if (requiresUnlock() && !Session.isUnlocked()) goToLock();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (requiresUnlock() && !Session.isUnlocked()) { goToLock(); return; }
        // Theme changed in Settings? Rebuild this screen with the new colours.
        if (indexOf(AppTheme.current(this)) != themeAtCreate) recreate();
    }

    public void goToLock() {
        Intent i = new Intent(this, LockActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(i);
        finish();
    }

    private static int indexOf(AppTheme theme) {
        for (int i = 0; i < AppTheme.ALL.length; i++) if (AppTheme.ALL[i] == theme) return i;
        return 0;
    }
}
