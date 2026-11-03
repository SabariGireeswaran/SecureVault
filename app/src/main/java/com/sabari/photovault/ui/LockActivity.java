package com.sabari.photovault.ui;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.security.keystore.KeyPermanentlyInvalidatedException;
import android.text.Editable;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.sabari.photovault.R;
import com.sabari.photovault.crypto.KeyStoreHelper;
import com.sabari.photovault.crypto.Session;
import com.sabari.photovault.crypto.VaultKeys;

import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;

/** The first screen. Two modes: "Create PIN" (first run) and "Unlock". */
public class LockActivity extends SecureActivity {

    private VaultKeys keys;
    private boolean setupMode;
    private boolean bioPromptShown = false;

    private TextView title, subtitle, error;
    private EditText pin, pin2;
    private Button go, bio;
    private ProgressBar progress;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable countdown = this::refreshLockout;

    @Override protected boolean requiresUnlock() { return false; }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Session.lock(); // arriving here always means "locked"
        setContentView(R.layout.activity_lock);
        keys = new VaultKeys(this);

        title = findViewById(R.id.title);
        subtitle = findViewById(R.id.subtitle);
        error = findViewById(R.id.error);
        pin = findViewById(R.id.pin);
        pin2 = findViewById(R.id.pin2);
        go = findViewById(R.id.go);
        bio = findViewById(R.id.bio);
        progress = findViewById(R.id.progress);

        setupMode = !keys.isSetUp();
        if (setupMode) {
            title.setText("Create your PIN");
            subtitle.setText("Use " + VaultKeys.MIN_PIN + "–" + VaultKeys.MAX_PIN + " digits.\n"
                    + "IMPORTANT: there is no \"forgot PIN\". If you forget it, nobody — "
                    + "not even you — can open the photos again.");
            pin2.setVisibility(View.VISIBLE);
            go.setText("Create vault");
            go.setOnClickListener(v -> doSetup());
            pin2.setOnEditorActionListener((v, id, e) -> { doSetup(); return true; });
        } else {
            title.setText("Vault locked");
            subtitle.setText("Enter your PIN");
            go.setText("Unlock");
            go.setOnClickListener(v -> doUnlock());
            pin.setImeOptions(EditorInfo.IME_ACTION_DONE);
            pin.setOnEditorActionListener((v, id, e) -> { doUnlock(); return true; });
            bio.setOnClickListener(v -> startBioUnlock());
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!setupMode) {
            refreshLockout();
            boolean canBio = keys.isBioEnabled() && Fingerprint.isAvailable(this);
            bio.setVisibility(canBio ? View.VISIBLE : View.GONE);
            if (canBio && !bioPromptShown) {
                bioPromptShown = true;
                startBioUnlock();
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        main.removeCallbacks(countdown);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        worker.shutdownNow();
    }

    // ------------------------------------------------------------------ setup

    private void doSetup() {
        char[] a = take(pin), b = take(pin2);
        try {
            if (a.length < VaultKeys.MIN_PIN) { showError("PIN must be at least " + VaultKeys.MIN_PIN + " digits"); return; }
            if (!Arrays.equals(a, b)) { showError("PINs do not match"); return; }
            if (isTooSimple(a)) { showError("PIN is too easy to guess (like 123456 or 111111)"); return; }
        } finally {
            Arrays.fill(b, '\0');
        }
        busy(true);
        worker.execute(() -> {
            try {
                SecretKey master = keys.setup(a);
                Session.unlock(master);
                main.post(() -> { busy(false); offerFingerprint(); });
            } catch (Exception e) {
                main.post(() -> { busy(false); showError("Setup failed: " + e.getMessage()); });
            } finally {
                Arrays.fill(a, '\0');
            }
        });
    }

    private void offerFingerprint() {
        if (!Fingerprint.isAvailable(this)) { openVault(); return; }
        new AlertDialog.Builder(this)
                .setTitle("Unlock with fingerprint?")
                .setMessage("You can still always use your PIN.")
                .setCancelable(false)
                .setPositiveButton("Turn on", (d, w) -> enableFingerprint())
                .setNegativeButton("Not now", (d, w) -> openVault())
                .show();
    }

    private void enableFingerprint() {
        try {
            KeyStoreHelper.createBioKey();
            Cipher c = KeyStoreHelper.bioEncryptCipher();
            Fingerprint.prompt(this, "Confirm your fingerprint", c, new Fingerprint.Callback() {
                @Override public void onSuccess(Cipher approved) {
                    try { keys.enableBio(approved, Session.key()); } catch (Exception ignored) { }
                    openVault();
                }
                @Override public void onFailed(String m) { KeyStoreHelper.deleteBioKey(); openVault(); }
            });
        } catch (Exception e) {
            openVault();
        }
    }

    // ------------------------------------------------------------------ unlock

    private void doUnlock() {
        if (keys.lockoutRemainingMs() > 0) return;
        char[] p = take(pin);
        if (p.length == 0) return;
        busy(true);
        worker.execute(() -> {
            try {
                SecretKey master = keys.unlockWithPin(p);
                keys.resetFailures();
                Session.unlock(master);
                main.post(this::openVault);
            } catch (VaultKeys.WrongPinException e) {
                keys.recordFailure();
                main.post(() -> {
                    busy(false);
                    showError("Wrong PIN (" + keys.failedAttempts() + " failed)");
                    refreshLockout();
                });
            } catch (Exception e) {
                main.post(() -> { busy(false); showError("Error: " + e.getMessage()); });
            } finally {
                Arrays.fill(p, '\0');
            }
        });
    }

    private void startBioUnlock() {
        Cipher c;
        try {
            c = KeyStoreHelper.bioDecryptCipher(keys.bioIv());
        } catch (KeyPermanentlyInvalidatedException e) {
            // A new fingerprint was added to the phone -> for safety, fingerprint unlock is cancelled.
            keys.disableBio();
            bio.setVisibility(View.GONE);
            showError("Fingerprints on this phone changed. Please use your PIN. You can turn fingerprint on again from the menu.");
            return;
        } catch (Exception e) {
            showError("Fingerprint unavailable. Use your PIN.");
            return;
        }
        Fingerprint.prompt(this, "Unlock Vault", c, new Fingerprint.Callback() {
            @Override public void onSuccess(Cipher approved) {
                try {
                    Session.unlock(keys.unlockWithBio(approved));
                    keys.resetFailures();
                    openVault();
                } catch (Exception e) {
                    showError("Fingerprint unlock failed. Use your PIN.");
                }
            }
            @Override public void onFailed(String m) { if (m != null) showError(m); }
        });
    }

    // ------------------------------------------------------------------ helpers

    private void openVault() {
        startActivity(new Intent(this, VaultActivity.class));
        finish();
    }

    private void refreshLockout() {
        long ms = keys.lockoutRemainingMs();
        if (ms > 0) {
            go.setEnabled(false);
            pin.setEnabled(false);
            long s = (ms + 999) / 1000;
            go.setText(String.format("Try again in %d:%02d", s / 60, s % 60));
            main.removeCallbacks(countdown);
            main.postDelayed(countdown, 1000);
        } else {
            go.setEnabled(true);
            pin.setEnabled(true);
            go.setText("Unlock");
        }
    }

    /** Reads the PIN as a char[] (not a String, so we can wipe it) and clears the box. */
    private static char[] take(EditText et) {
        Editable e = et.getText();
        char[] out = new char[e.length()];
        e.getChars(0, e.length(), out, 0);
        e.clear();
        return out;
    }

    private static boolean isTooSimple(char[] p) {
        boolean allSame = true, up = true, down = true;
        for (int i = 1; i < p.length; i++) {
            if (p[i] != p[0]) allSame = false;
            if (p[i] != p[i - 1] + 1) up = false;
            if (p[i] != p[i - 1] - 1) down = false;
        }
        return allSame || up || down;
    }

    private void showError(String m) { error.setText(m); }

    private void busy(boolean b) {
        progress.setVisibility(b ? View.VISIBLE : View.GONE);
        go.setEnabled(!b);
        if (b) error.setText("");
    }
}
