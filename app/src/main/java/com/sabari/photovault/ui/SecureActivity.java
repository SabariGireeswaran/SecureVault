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
 */
public abstract class SecureActivity extends Activity {

    /** Screens that need the vault to be open return true. */
    protected boolean requiresUnlock() { return true; }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);
        if (requiresUnlock() && !Session.isUnlocked()) goToLock();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (requiresUnlock() && !Session.isUnlocked()) goToLock();
    }

    protected void goToLock() {
        Intent i = new Intent(this, LockActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(i);
        finish();
    }
}
