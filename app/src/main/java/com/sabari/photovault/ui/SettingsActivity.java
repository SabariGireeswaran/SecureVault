package com.sabari.photovault.ui;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.sabari.photovault.R;
import com.sabari.photovault.crypto.KeyStoreHelper;
import com.sabari.photovault.crypto.Session;
import com.sabari.photovault.crypto.VaultKeys;
import com.sabari.photovault.data.AppSettings;
import com.sabari.photovault.data.VaultStore;

import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.crypto.Cipher;

/** Settings: theme, security, backup, about. */
public class SettingsActivity extends SecureActivity {

    private VaultKeys keys;
    private AppSettings settings;
    private BackupFlow backup;
    private Switch bioSwitch;
    private TextView wipeValue, backupSub;
    private boolean ignoreSwitch = false;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!Session.isUnlocked()) return;
        keys = new VaultKeys(this);
        settings = new AppSettings(this);
        backup = new BackupFlow(this, t, this::refresh);
        setContentView(buildUi());
        refresh();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        worker.shutdownNow();
        if (backup != null) backup.destroy();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (backup != null) backup.onActivityResult(req, res, data);
    }

    // =================================================================== layout

    private View buildUi() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Ui.dp(this, 4), Ui.dp(this, 8), Ui.dp(this, 16), 0);
        bar.addView(Ui.iconButton(this, R.drawable.ic_back, t.text, v -> finish()));
        TextView title = Ui.text(this, "Settings", 24, t.text, true);
        Ui.gradientText(title, t.accent, t.accent2);
        bar.addView(title);
        page.addView(bar, new LinearLayout.LayoutParams(-1, Ui.dp(this, 64)));

        ScrollView scroll = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), Ui.dp(this, 32));
        scroll.addView(col);
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        // ---------- Appearance ----------
        col.addView(sectionLabel("APPEARANCE"));
        LinearLayout themeCard = card();
        themeCard.addView(rowHeader(R.drawable.ic_palette, 0xFFF783AC, "Colour theme", "Now: " + t.name));
        LinearLayout swatches = new LinearLayout(this);
        swatches.setPadding(0, Ui.dp(this, 14), 0, Ui.dp(this, 4));
        for (int i = 0; i < AppTheme.ALL.length; i++) swatches.addView(swatch(i));
        themeCard.addView(swatches);
        col.addView(themeCard);

        // ---------- Security ----------
        col.addView(sectionLabel("SECURITY"));
        LinearLayout sec = card();
        if (Fingerprint.isAvailable(this)) {
            bioSwitch = new Switch(this);
            bioSwitch.setThumbTintList(ColorStateList.valueOf(t.accent));
            bioSwitch.setTrackTintList(ColorStateList.valueOf(Ui.alpha(t.accent, 0.4f)));
            bioSwitch.setOnCheckedChangeListener((b, on) -> { if (!ignoreSwitch) toggleFingerprint(on); });
            sec.addView(row(R.drawable.ic_lock_small, 0xFF38D9A9, "Fingerprint unlock", "Unlock with your finger", bioSwitch, null));
            sec.addView(divider());
        }
        sec.addView(row(R.drawable.ic_shield, 0xFF9775FA, "Change PIN", "Your PIN never leaves this phone", null, v -> changePin()));
        sec.addView(divider());
        wipeValue = Ui.text(this, "", 14, t.accent, true);
        sec.addView(row(R.drawable.ic_delete, t.danger, "Auto-erase", "Erase vault after too many wrong PINs", wipeValue, v -> pickWipe()));
        sec.addView(divider());
        sec.addView(row(R.drawable.ic_lock_small, 0xFFFFD43B, "Lock now", "Close the vault immediately", null,
                v -> { Session.lock(); goToLock(); }));
        col.addView(sec);

        // ---------- Backup ----------
        col.addView(sectionLabel("BACKUP"));
        LinearLayout bk = card();
        backupSub = Ui.text(this, "", 13, t.muted, false);
        bk.addView(row(R.drawable.ic_backup, 0xFF4DABF7, "Backup to file", null, null,
                v -> backup.startBackup(new VaultStore(this).list().size()), backupSub));
        bk.addView(divider());
        bk.addView(row(R.drawable.ic_download, 0xFF69DB7C, "Restore from backup", "Bring photos back from a .svb file", null,
                v -> backup.startRestore()));
        col.addView(bk);

        // ---------- About ----------
        col.addView(sectionLabel("ABOUT"));
        LinearLayout ab = card();
        ab.addView(row(R.drawable.ic_info, 0xFFFFA94D, "How my photos are protected", "Simple explanation", null, v -> about()));
        ab.addView(divider());
        ab.addView(row(R.drawable.ic_shield, 0xFF38D9A9, "SecureVault 1.2", "Offline · No internet permission · Open source", null, null));
        col.addView(ab);
        return page;
    }

    private void refresh() {
        if (bioSwitch != null) {
            ignoreSwitch = true;
            bioSwitch.setChecked(keys.isBioEnabled());
            ignoreSwitch = false;
        }
        int w = settings.wipeAfter();
        wipeValue.setText(w == 0 ? "Off" : w + " tries");
        long last = settings.lastBackup();
        backupSub.setText(last == 0 ? "No backup yet" : "Last backup " + Ui.ago(last));
    }

    // =================================================================== actions

    private void toggleFingerprint(boolean on) {
        if (!on) {
            keys.disableBio();
            toast("Fingerprint unlock turned off");
            return;
        }
        try {
            KeyStoreHelper.createBioKey();
            Fingerprint.prompt(this, "Confirm your fingerprint", KeyStoreHelper.bioEncryptCipher(), new Fingerprint.Callback() {
                @Override public void onSuccess(Cipher approved) {
                    try {
                        keys.enableBio(approved, Session.key());
                        toast("Fingerprint unlock turned on");
                    } catch (Exception e) {
                        KeyStoreHelper.deleteBioKey();
                    }
                    refresh();
                }
                @Override public void onFailed(String m) { KeyStoreHelper.deleteBioKey(); refresh(); }
            });
        } catch (Exception e) {
            toast("Fingerprint not available");
            refresh();
        }
    }

    private void pickWipe() {
        String[] labels = {"Off (recommended if you have no backup)", "After 10 wrong PINs", "After 20 wrong PINs"};
        int[] values = {0, 10, 20};
        int cur = settings.wipeAfter() == 10 ? 1 : settings.wipeAfter() == 20 ? 2 : 0;
        Ui.show(Ui.dialog(this)
                .setTitle("Auto-erase")
                .setSingleChoiceItems(labels, cur, (d, which) -> {
                    settings.setWipeAfter(values[which]);
                    refresh();
                    d.dismiss();
                    if (values[which] > 0) {
                        Ui.show(Ui.dialog(this).setTitle("⚠ Auto-erase is ON")
                                .setMessage("If someone (or you) types a wrong PIN " + values[which] + " times in a row, "
                                        + "ALL photos in the vault are destroyed forever.\n\n"
                                        + "Make sure you have a recent backup file.")
                                .setPositiveButton("I understand", null), t);
                    }
                })
                .setNegativeButton("Cancel", null), t);
    }

    private void changePin() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 22);
        box.setPadding(pad, pad / 2, pad, 0);
        EditText cur = pinField("Current PIN"), n1 = pinField("New PIN (6–16 digits)"), n2 = pinField("Confirm new PIN");
        box.addView(cur);
        box.addView(n1);
        box.addView(n2);
        Ui.show(Ui.dialog(this)
                .setTitle("Change PIN")
                .setView(box)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Change", (d, w) -> {
                    char[] c = BackupFlow.chars(cur), a = BackupFlow.chars(n1), b = BackupFlow.chars(n2);
                    if (a.length < VaultKeys.MIN_PIN || !Arrays.equals(a, b)) {
                        toast("New PINs must match and be at least " + VaultKeys.MIN_PIN + " digits");
                        wipe(c, a, b);
                        return;
                    }
                    worker.execute(() -> {
                        try {
                            keys.unlockWithPin(c);          // prove you know the current PIN
                            keys.changePin(Session.key(), a);
                            main.post(() -> toast("✅ PIN changed"));
                        } catch (VaultKeys.WrongPinException e) {
                            keys.recordFailure();
                            main.post(() -> toast("Current PIN is wrong"));
                        } catch (Exception e) {
                            main.post(() -> toast("Could not change PIN"));
                        } finally {
                            wipe(c, a, b);
                        }
                    });
                }), t);
    }

    private void about() {
        Ui.show(Ui.dialog(this)
                .setTitle("How your photos are protected")
                .setMessage("🔐  Every photo is encrypted with AES-256-GCM before it is saved.\n\n"
                        + "📱  The key is locked by your PIN AND by this phone's security chip. "
                        + "Copying the files to another device is useless.\n\n"
                        + "⏳  5 wrong PINs → waiting time that doubles each time (up to 1 hour).\n\n"
                        + "⌨️  The PIN pad is built into the app, so keyboard apps can't see your PIN.\n\n"
                        + "🚫  NO internet permission. The app cannot send anything anywhere.\n\n"
                        + "📵  Screenshots, screen recording and the recent-apps preview are blocked.\n\n"
                        + "🔒  The vault locks the moment you leave the app or turn off the screen.\n\n"
                        + "💾  Your own backup: one encrypted .svb file for a pendrive or laptop, "
                        + "protected by a separate backup password.\n\n"
                        + "⚠  Lost PIN, lost phone or uninstalled app = photos gone, UNLESS you have "
                        + "a backup file and its password.")
                .setPositiveButton("OK", null), t);
    }

    // =================================================================== building blocks

    private View swatch(int i) {
        AppTheme th = AppTheme.ALL[i];
        boolean active = th == t;
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout dot = new FrameLayout(this);
        GradientDrawable g = Ui.circle(th.accent, th.accent2);
        if (active) g.setStroke(Ui.dp(this, 3), 0xFFFFFFFF);
        dot.setBackground(g);
        if (active) {
            ImageView ok = new ImageView(this);
            ok.setImageResource(R.drawable.ic_check);
            ok.setImageTintList(ColorStateList.valueOf(0xFFFFFFFF));
            dot.addView(ok, new FrameLayout.LayoutParams(Ui.dp(this, 20), Ui.dp(this, 20), Gravity.CENTER));
        }
        c.addView(dot, new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40)));
        TextView n = Ui.text(this, th.name, 10, active ? t.text : t.muted, active);
        n.setPadding(0, Ui.dp(this, 6), 0, 0);
        n.setSingleLine(true);
        c.addView(n);
        c.setOnClickListener(v -> {
            settings.setThemeIndex(i);
            recreate();
        });
        c.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        return c;
    }

    private TextView sectionLabel(String s) {
        TextView tv = Ui.text(this, s, 12, t.muted, true);
        tv.setLetterSpacing(0.14f);
        tv.setPadding(Ui.dp(this, 6), Ui.dp(this, 22), 0, Ui.dp(this, 8));
        return tv;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 14);
        c.setPadding(p, p, p, p);
        GradientDrawable bg = Ui.round(Ui.alpha(t.surface, 0.92f), Ui.dp(this, 22));
        bg.setStroke(Ui.dp(this, 1), Ui.alpha(t.accent, 0.15f));
        c.setBackground(bg);
        return c;
    }

    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(Ui.alpha(t.muted, 0.15f));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, Ui.dp(this, 1));
        p.setMargins(Ui.dp(this, 52), Ui.dp(this, 4), 0, Ui.dp(this, 4));
        v.setLayoutParams(p);
        return v;
    }

    private View rowHeader(int icon, int color, String title, String sub) {
        return row(icon, color, title, sub, null, null);
    }

    private View row(int icon, int color, String title, String sub, View right, View.OnClickListener click) {
        return row(icon, color, title, sub, right, click, null);
    }

    private View row(int icon, int color, String title, String sub, View right, View.OnClickListener click, TextView subView) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 6));
        FrameLayout bubble = new FrameLayout(this);
        bubble.setBackground(Ui.round(Ui.alpha(color, 0.18f), Ui.dp(this, 12)));
        ImageView ic = new ImageView(this);
        ic.setImageResource(icon);
        ic.setImageTintList(ColorStateList.valueOf(color));
        bubble.addView(ic, new FrameLayout.LayoutParams(Ui.dp(this, 22), Ui.dp(this, 22), Gravity.CENTER));
        r.addView(bubble, new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40)));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 8), 0);
        texts.addView(Ui.text(this, title, 16, t.text, true));
        if (subView != null) texts.addView(subView);
        else if (sub != null) texts.addView(Ui.text(this, sub, 13, t.muted, false));
        r.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));
        if (right != null) r.addView(right);
        else if (click != null) r.addView(Ui.text(this, "›", 22, t.muted, false));
        if (click != null) {
            r.setBackground(Ui.ripple(Ui.round(0x00000000, Ui.dp(this, 14)), Ui.alpha(t.accent, 0.2f)));
            r.setOnClickListener(click);
        }
        return r;
    }

    private EditText pinField(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setTextColor(t.text);
        e.setHintTextColor(t.muted);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        return e;
    }

    private static void wipe(char[]... arrays) { for (char[] a : arrays) Arrays.fill(a, '\0'); }

    private void toast(String m) { Toast.makeText(this, m, Toast.LENGTH_SHORT).show(); }
}
