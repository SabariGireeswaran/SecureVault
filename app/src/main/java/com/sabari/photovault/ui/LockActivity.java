package com.sabari.photovault.ui;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.security.keystore.KeyPermanentlyInvalidatedException;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.sabari.photovault.R;
import com.sabari.photovault.crypto.KeyStoreHelper;
import com.sabari.photovault.crypto.Session;
import com.sabari.photovault.crypto.VaultKeys;
import com.sabari.photovault.data.AppSettings;
import com.sabari.photovault.data.VaultWiper;

import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;

/**
 * First screen. Three modes: CREATE a PIN, CONFIRM it, or UNLOCK.
 *
 * It uses its OWN number keypad instead of the phone keyboard. Why?
 * Keyboard apps can learn or upload what you type. Our keypad never leaves this app.
 */
public class LockActivity extends SecureActivity {

    private enum Mode { CREATE, CONFIRM, UNLOCK }

    private Mode mode;
    private VaultKeys keys;
    private AppSettings settings;

    private final char[] buf = new char[VaultKeys.MAX_PIN];
    private int len = 0;
    private char[] firstPin;           // remembered between CREATE and CONFIRM
    private boolean busy = false;
    private boolean bioPromptShown = false;

    private TextView title, subtitle, error;
    private LinearLayout dots, keypad;
    private TextView bioButton;
    private FrameLayout logo;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable countdown = this::refreshLockout;

    @Override protected boolean requiresUnlock() { return false; }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Session.lock(); // arriving here always means "locked"
        keys = new VaultKeys(this);
        settings = new AppSettings(this);
        mode = keys.isSetUp() ? Mode.UNLOCK : Mode.CREATE;
        setContentView(buildUi());
        showMode();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mode == Mode.UNLOCK) {
            refreshLockout();
            boolean canBio = keys.isBioEnabled() && Fingerprint.isAvailable(this);
            bioButton.setVisibility(canBio ? View.VISIBLE : View.INVISIBLE);
            if (canBio && !bioPromptShown) {
                bioPromptShown = true;
                startBioUnlock();
            }
        }
    }

    @Override protected void onPause() { super.onPause(); main.removeCallbacks(countdown); }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        worker.shutdownNow();
        clearBuf();
        if (firstPin != null) Arrays.fill(firstPin, '\0');
    }

    // =================================================================== UI

    private View buildUi() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(Ui.dp(this, 24), Ui.dp(this, 36), Ui.dp(this, 24), Ui.dp(this, 20));

        // Glowing gradient logo that gently "breathes".
        logo = new FrameLayout(this);
        View glow = new View(this);
        glow.setBackground(Ui.circle(Ui.alpha(t.accent, 0.25f), Ui.alpha(t.accent2, 0.25f)));
        logo.addView(glow, new FrameLayout.LayoutParams(Ui.dp(this, 112), Ui.dp(this, 112), Gravity.CENTER));
        View disc = new View(this);
        disc.setBackground(Ui.circle(t.accent, t.accent2));
        disc.setElevation(Ui.dp(this, 8));
        logo.addView(disc, new FrameLayout.LayoutParams(Ui.dp(this, 84), Ui.dp(this, 84), Gravity.CENTER));
        ImageView lockIcon = new ImageView(this);
        lockIcon.setImageResource(R.drawable.ic_lock_small);
        lockIcon.setImageTintList(ColorStateList.valueOf(0xFFFFFFFF));
        lockIcon.setElevation(Ui.dp(this, 9));
        logo.addView(lockIcon, new FrameLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40), Gravity.CENTER));
        col.addView(logo, new LinearLayout.LayoutParams(Ui.dp(this, 112), Ui.dp(this, 112)));
        ObjectAnimator breathe = ObjectAnimator.ofFloat(glow, "alpha", 0.35f, 1f);
        breathe.setDuration(1600);
        breathe.setRepeatMode(ValueAnimator.REVERSE);
        breathe.setRepeatCount(ValueAnimator.INFINITE);
        breathe.start();

        title = Ui.text(this, "", 26, t.text, true);
        title.setGravity(Gravity.CENTER);
        col.addView(title, lp(-2, -2, 14));

        subtitle = Ui.text(this, "", 14, t.muted, false);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setLineSpacing(0, 1.15f);
        col.addView(subtitle, lp(-1, -2, 6));

        dots = new LinearLayout(this);
        dots.setGravity(Gravity.CENTER);
        col.addView(dots, lp(-1, Ui.dp(this, 26), 22));

        error = Ui.text(this, "", 13, t.danger, false);
        error.setGravity(Gravity.CENTER);
        col.addView(error, lp(-1, -2, 8));

        View spacer = new View(this);
        col.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1f));

        keypad = buildKeypad();
        col.addView(keypad, lp(-2, -2, 0));

        bioButton = Ui.text(this, "  Use fingerprint  ", 15, t.accent, true);
        bioButton.setGravity(Gravity.CENTER);
        int p = Ui.dp(this, 10);
        bioButton.setPadding(p * 2, p, p * 2, p);
        GradientDrawable pill = Ui.round(Ui.alpha(t.accent, 0.12f), Ui.dp(this, 24));
        pill.setStroke(Ui.dp(this, 1), Ui.alpha(t.accent, 0.5f));
        bioButton.setBackground(Ui.ripple(pill, 0x33FFFFFF));
        bioButton.setOnClickListener(v -> startBioUnlock());
        bioButton.setVisibility(View.INVISIBLE);
        col.addView(bioButton, lp(-2, -2, 14));

        // On short phones the screen scrolls instead of squeezing things on top of each other.
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(col, new android.widget.FrameLayout.LayoutParams(-1, -2));
        return scroll;
    }

    private LinearLayout buildKeypad() {
        LinearLayout pad = new LinearLayout(this);
        pad.setOrientation(LinearLayout.VERTICAL);
        String[][] rows = {{"1", "2", "3"}, {"4", "5", "6"}, {"7", "8", "9"}, {"<", "0", "OK"}};
        String[] letters = {"", "ABC", "DEF", "GHI", "JKL", "MNO", "PQRS", "TUV", "WXYZ"};

        // Key size is calculated from the SCREEN size, so the keypad fits every phone.
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int screenH = getResources().getDisplayMetrics().heightPixels;
        int gap = Ui.dp(this, 10);
        int size = Math.min(Ui.dp(this, 76), (screenW - Ui.dp(this, 48)) / 3 - gap * 2);
        size = Math.min(size, (int) (screenH * 0.46f) / 4 - gap);
        // Text size comes from the KEY size (not the phone font setting), so numbers always stay inside the circle.
        float digitPx = size * 0.40f, letterPx = size * 0.13f;
        for (String[] row : rows) {
            LinearLayout r = new LinearLayout(this);
            r.setGravity(Gravity.CENTER);
            for (String k : row) {
                FrameLayout key = new FrameLayout(this);
                LinearLayout.LayoutParams klp = new LinearLayout.LayoutParams(size, size);
                klp.setMargins(gap, gap / 2, gap, gap / 2);
                key.setLayoutParams(klp);

                if (k.equals("<") || k.equals("OK")) {
                    boolean ok = k.equals("OK");
                    key.setBackground(Ui.ripple(ok ? Ui.circle(t.accent, t.accent2) : oval(0x00000000), 0x44FFFFFF));
                    ImageView ic = new ImageView(this);
                    ic.setImageResource(ok ? R.drawable.ic_check : R.drawable.ic_backspace);
                    ic.setImageTintList(ColorStateList.valueOf(ok ? 0xFF0B0B12 : t.text));
                    int is = (int) (size * 0.40f);
                    key.addView(ic, new FrameLayout.LayoutParams(is, is, Gravity.CENTER));
                    key.setOnClickListener(v -> { haptic(v); if (ok) submit(); else backspace(); });
                    if (!ok) key.setOnLongClickListener(v -> { clearBuf(); updateDots(); return true; });
                } else {
                    GradientDrawable bg = oval(Ui.alpha(t.surface, 0.85f));
                    bg.setStroke(Ui.dp(this, 1), Ui.alpha(t.accent, 0.18f));
                    key.setBackground(Ui.ripple(bg, Ui.alpha(t.accent, 0.45f)));
                    LinearLayout label = new LinearLayout(this);
                    label.setOrientation(LinearLayout.VERTICAL);
                    label.setGravity(Gravity.CENTER_HORIZONTAL);
                    TextView digit = Ui.text(this, k, 10, t.text, false);
                    digit.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, digitPx);
                    digit.setIncludeFontPadding(false);   // removes hidden space above/below, so it centres exactly
                    digit.setGravity(Gravity.CENTER);
                    label.addView(digit);
                    if (!k.equals("0") && !k.equals("1")) {
                        TextView sub = Ui.text(this, letters[k.charAt(0) - '1'], 10, t.muted, false);
                        sub.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, letterPx);
                        sub.setIncludeFontPadding(false);
                        sub.setLetterSpacing(0.15f);
                        sub.setPadding(0, Ui.dp(this, 2), 0, 0);
                        label.addView(sub);
                    }
                    // Wrap-content + CENTER = the label block sits exactly in the middle of the circle.
                    key.addView(label, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
                    key.setOnClickListener(v -> { haptic(v); typeDigit(k.charAt(0)); });
                }
                r.addView(key);
            }
            pad.addView(r);
        }
        return pad;
    }

    private void showMode() {
        clearBuf();
        updateDots();
        switch (mode) {
            case CREATE:
                title.setText("Create your PIN");
                subtitle.setText("Choose " + VaultKeys.MIN_PIN + "–" + VaultKeys.MAX_PIN + " digits, then tap ✓\n"
                        + "There is no \"forgot PIN\" — keep an encrypted backup.");
                break;
            case CONFIRM:
                title.setText("Confirm your PIN");
                subtitle.setText("Enter the same PIN again");
                break;
            case UNLOCK:
                title.setText("Welcome back");
                subtitle.setText(wipeWarning("Enter your PIN"));
                break;
        }
    }

    private void updateDots() {
        dots.removeAllViews();
        int shown = Math.max(VaultKeys.MIN_PIN, len);
        // Long PIN? Make the dots smaller so they always fit on one line.
        int avail = getResources().getDisplayMetrics().widthPixels - Ui.dp(this, 64);
        int per = avail / shown;
        int s = Math.min(Ui.dp(this, 14), (int) (per * 0.6f));
        int m = Math.min(Ui.dp(this, 7), (per - s) / 2);
        for (int i = 0; i < shown; i++) {
            View d = new View(this);
            if (i < len) d.setBackground(Ui.circle(t.accent, t.accent2));
            else {
                GradientDrawable g = oval(0x00000000);
                g.setStroke(Ui.dp(this, 2), Ui.alpha(t.muted, 0.6f));
                d.setBackground(g);
            }
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(s, s);
            p.setMargins(m, 0, m, 0);
            dots.addView(d, p);
            if (i == len - 1) {          // little "pop" on the newest dot
                d.setScaleX(0.4f); d.setScaleY(0.4f);
                d.animate().scaleX(1f).scaleY(1f).setDuration(140).start();
            }
        }
    }

    // =================================================================== input

    private void typeDigit(char c) {
        if (busy || keys.lockoutRemainingMs() > 0 && mode == Mode.UNLOCK) return;
        if (len < buf.length) { buf[len++] = c; error.setText(""); updateDots(); }
    }

    private void backspace() {
        if (busy || len == 0) return;
        buf[--len] = '\0';
        updateDots();
    }

    private void submit() {
        if (busy) return;
        if (len < VaultKeys.MIN_PIN) { fail("Enter at least " + VaultKeys.MIN_PIN + " digits"); return; }
        char[] pin = Arrays.copyOf(buf, len);
        clearBuf();
        switch (mode) {
            case CREATE:
                if (isTooSimple(pin)) { Arrays.fill(pin, '\0'); fail("Too easy to guess (like 123456 or 111111)"); return; }
                firstPin = pin;
                mode = Mode.CONFIRM;
                showMode();
                break;
            case CONFIRM:
                if (!Arrays.equals(pin, firstPin)) {
                    Arrays.fill(pin, '\0');
                    Arrays.fill(firstPin, '\0');
                    firstPin = null;
                    mode = Mode.CREATE;
                    showMode();
                    fail("PINs didn't match. Please start again.");
                    return;
                }
                Arrays.fill(firstPin, '\0');
                firstPin = null;
                doSetup(pin);
                break;
            case UNLOCK:
                doUnlock(pin);
                break;
        }
    }

    // =================================================================== setup

    private void doSetup(char[] pin) {
        setBusy(true, "Creating your vault…");
        worker.execute(() -> {
            try {
                SecretKey master = keys.setup(pin);
                Session.unlock(master);
                main.post(() -> { setBusy(false, null); offerFingerprint(); });
            } catch (Exception e) {
                main.post(() -> { setBusy(false, null); fail("Setup failed: " + e.getMessage()); });
            } finally {
                Arrays.fill(pin, '\0');
            }
        });
    }

    private void offerFingerprint() {
        if (!Fingerprint.isAvailable(this)) { openVault(); return; }
        Ui.show(Ui.dialog(this)
                .setTitle("Unlock with fingerprint?")
                .setMessage("Faster unlocking. You can still always use your PIN.")
                .setCancelable(false)
                .setPositiveButton("Turn on", (d, w) -> enableFingerprint())
                .setNegativeButton("Not now", (d, w) -> openVault()), t);
    }

    private void enableFingerprint() {
        try {
            KeyStoreHelper.createBioKey();
            Fingerprint.prompt(this, "Confirm your fingerprint", KeyStoreHelper.bioEncryptCipher(), new Fingerprint.Callback() {
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

    // =================================================================== unlock

    private void doUnlock(char[] pin) {
        if (keys.lockoutRemainingMs() > 0) { Arrays.fill(pin, '\0'); return; }
        setBusy(true, "Unlocking…");
        worker.execute(() -> {
            try {
                SecretKey master = keys.unlockWithPin(pin);
                keys.resetFailures();
                Session.unlock(master);
                main.post(this::openVault);
            } catch (VaultKeys.WrongPinException e) {
                keys.recordFailure();
                settings.logWrongPin(System.currentTimeMillis());   // for the "someone tried your PIN" alert
                int limit = settings.wipeAfter();
                boolean erase = limit > 0 && keys.failedAttempts() >= limit;
                if (erase) VaultWiper.wipe(this);
                main.post(() -> {
                    setBusy(false, null);
                    if (erase) { showErased(); return; }
                    subtitle.setText(wipeWarning("Enter your PIN"));
                    fail("Wrong PIN (" + keys.failedAttempts() + " failed)");
                    refreshLockout();
                });
            } catch (Exception e) {
                main.post(() -> { setBusy(false, null); fail("Error: " + e.getMessage()); });
            } finally {
                Arrays.fill(pin, '\0');
            }
        });
    }

    private void showErased() {
        Ui.show(Ui.dialog(this)
                .setTitle("Vault erased")
                .setMessage("Too many wrong PINs were entered, so the vault was erased for safety "
                        + "(auto-erase was turned on).\n\nIf you have an encrypted backup file, you can restore it "
                        + "after creating a new PIN.")
                .setCancelable(false)
                .setPositiveButton("OK", (d, w) -> recreate()), t);
    }

    private void startBioUnlock() {
        Cipher c;
        try {
            c = KeyStoreHelper.bioDecryptCipher(keys.bioIv());
        } catch (KeyPermanentlyInvalidatedException e) {
            keys.disableBio();
            bioButton.setVisibility(View.INVISIBLE);
            error.setText("Fingerprints on this phone changed. Use your PIN, then turn fingerprint on again in Settings.");
            return;
        } catch (Exception e) {
            error.setText("Fingerprint unavailable. Use your PIN.");
            return;
        }
        Fingerprint.prompt(this, "Unlock SecureVault", c, new Fingerprint.Callback() {
            @Override public void onSuccess(Cipher approved) {
                try {
                    Session.unlock(keys.unlockWithBio(approved));
                    keys.resetFailures();
                    openVault();
                } catch (Exception e) {
                    error.setText("Fingerprint unlock failed. Use your PIN.");
                }
            }
            @Override public void onFailed(String m) { if (m != null) error.setText(m); }
        });
    }

    // =================================================================== helpers

    private void openVault() {
        startActivity(new Intent(this, VaultActivity.class));
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        finish();
    }

    private void refreshLockout() {
        if (mode != Mode.UNLOCK) return;
        long ms = keys.lockoutRemainingMs();
        if (ms > 0) {
            long s = (ms + 999) / 1000;
            keypad.setAlpha(0.35f);
            title.setText(String.format("Wait %d:%02d", s / 60, s % 60));
            main.removeCallbacks(countdown);
            main.postDelayed(countdown, 1000);
        } else {
            keypad.setAlpha(1f);
            title.setText("Welcome back");
        }
    }

    private String wipeWarning(String normal) {
        int limit = settings.wipeAfter();
        if (limit <= 0) return normal;
        int left = limit - keys.failedAttempts();
        if (left <= 3) return "⚠ " + left + " wrong tries left before the vault is erased";
        return normal;
    }

    private void fail(String msg) {
        error.setText(msg);
        dots.performHapticFeedback(HapticFeedbackConstants.REJECT);
        ObjectAnimator.ofFloat(dots, "translationX", 0, 26, -26, 18, -18, 8, -8, 0).setDuration(420).start();
    }

    private void setBusy(boolean b, String msg) {
        busy = b;
        keypad.setAlpha(b ? 0.4f : 1f);
        if (b) {
            subtitle.setText(msg);
            logo.animate().rotationBy(360).setDuration(900).start();
        } else if (mode == Mode.UNLOCK) {
            subtitle.setText(wipeWarning("Enter your PIN"));
        }
    }

    private void clearBuf() { Arrays.fill(buf, '\0'); len = 0; }

    private void haptic(View v) { v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); }

    private static GradientDrawable oval(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        return g;
    }

    private LinearLayout.LayoutParams lp(int w, int h, int topDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.topMargin = Ui.dp(this, topDp);
        return p;
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
}
