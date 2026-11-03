package com.sabari.photovault.ui;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.sabari.photovault.VaultApp;
import com.sabari.photovault.backup.BackupFormat;
import com.sabari.photovault.backup.BackupManager;
import com.sabari.photovault.crypto.Session;
import com.sabari.photovault.data.AppSettings;

import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Backup + restore screens, written once and used by both Home and Settings (composition).
 * The activity must forward onActivityResult() and onDestroy() to this object.
 */
public class BackupFlow {

    public static final int REQ_BACKUP = 43;
    public static final int REQ_RESTORE = 44;

    private final SecureActivity a;
    private final AppTheme t;
    private final Runnable onChanged;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private char[] pendingPassword;

    public BackupFlow(SecureActivity activity, AppTheme theme, Runnable onChanged) {
        this.a = activity;
        this.t = theme;
        this.onChanged = onChanged;
    }

    // ----------------------------------------------------------------- backup

    public void startBackup(int photoCount) {
        if (photoCount == 0) {
            Toast.makeText(a, "The vault is empty — nothing to back up", Toast.LENGTH_SHORT).show();
            return;
        }
        LinearLayout box = box();
        TextView info = Ui.text(a, "This password protects the backup file on its own, "
                + "so it can be opened on another phone or a laptop.\n\n"
                + "• At least " + BackupFormat.MIN_PASSWORD + " characters, letters + numbers\n"
                + "• Different from your PIN\n"
                + "• Write it on paper. Forget it = backup can't be opened.", 13, t.muted, false);
        EditText p1 = field("Backup password"), p2 = field("Confirm password");
        box.addView(info);
        box.addView(p1);
        box.addView(p2);

        AlertDialog dlg = Ui.show(Ui.dialog(a)
                .setTitle("Create encrypted backup")
                .setView(box)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Choose location", null), t);
        // Custom click so the dialog stays open if the password is not good enough.
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            char[] x = chars(p1), y = chars(p2);
            String problem = BackupFormat.passwordProblem(x);
            if (problem == null && !Arrays.equals(x, y)) problem = "Passwords do not match";
            Arrays.fill(y, '\0');
            if (problem != null) {
                Arrays.fill(x, '\0');
                Toast.makeText(a, problem, Toast.LENGTH_LONG).show();
                return;
            }
            dlg.dismiss();
            wipePending();
            pendingPassword = x;
            String date = new SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(new Date());
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("application/octet-stream");
            i.putExtra(Intent.EXTRA_TITLE, "SecureVault-backup_" + date + ".svb");
            VaultApp.allowPicker();
            a.startActivityForResult(i, REQ_BACKUP);
        });
    }

    private void runBackup(Uri target) {
        char[] password = pendingPassword;
        pendingPassword = null;
        if (password == null) return;
        AlertDialog progress = Ui.show(Ui.dialog(a).setTitle("Creating backup…")
                .setMessage("Preparing (a few seconds)…").setCancelable(false), t);
        worker.execute(() -> {
            try {
                int count = new BackupManager(a).backup(target, password,
                        (done, total) -> main.post(() -> progress.setMessage(done + " of " + total + " photos")));
                new AppSettings(a).setLastBackup(System.currentTimeMillis());
                main.post(() -> {
                    progress.dismiss();
                    onChanged.run();
                    Ui.show(Ui.dialog(a).setTitle("✅ Backup complete")
                            .setMessage(count + " photos saved into one encrypted file.\n\n"
                                    + "Now copy it to a pendrive or laptop. A backup that stays only on this "
                                    + "phone won't help if the phone is lost.\n\n"
                                    + "To restore: install this app, create a PIN, then Settings → Restore.")
                            .setPositiveButton("OK", null), t);
                });
            } catch (Exception e) {
                main.post(() -> {
                    progress.dismiss();
                    if (!Session.isUnlocked()) { a.goToLock(); return; }
                    message("Backup failed", "The backup could not be finished. Please try again.\n\n(" + e.getMessage() + ")");
                });
            } finally {
                Arrays.fill(password, '\0');
            }
        });
    }

    // ----------------------------------------------------------------- restore

    public void startRestore() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");   // .svb has no standard type, so show all files
        VaultApp.allowPicker();
        a.startActivityForResult(i, REQ_RESTORE);
    }

    private void askRestorePassword(Uri source) {
        LinearLayout box = box();
        EditText p = field("Backup password");
        box.addView(p);
        Ui.show(Ui.dialog(a)
                .setTitle("Restore from backup")
                .setMessage("Enter the password you chose when the backup was made.")
                .setView(box)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Restore", (d, w) -> runRestore(source, chars(p))), t);
    }

    private void runRestore(Uri source, char[] password) {
        AlertDialog progress = Ui.show(Ui.dialog(a).setTitle("Restoring…")
                .setMessage("Checking password…").setCancelable(false), t);
        worker.execute(() -> {
            try {
                BackupManager.Result r = new BackupManager(a).restore(source, password,
                        (done, total) -> main.post(() -> progress.setMessage(done + " photos processed")));
                main.post(() -> {
                    progress.dismiss();
                    onChanged.run();
                    StringBuilder m = new StringBuilder().append(r.restored).append(" photos restored.");
                    if (r.skipped > 0) m.append("\n").append(r.skipped).append(" were already in the vault (skipped).");
                    if (r.failed > 0) m.append("\n").append(r.failed).append(" could not be restored.");
                    m.append("\n\nThe backup file was checked and is not damaged.");
                    message("✅ Restore complete", m.toString());
                });
            } catch (BackupFormat.WrongPasswordException e) {
                main.post(() -> { progress.dismiss(); message("Wrong password", "That is not the password of this backup file."); });
            } catch (Exception e) {
                main.post(() -> {
                    progress.dismiss();
                    onChanged.run();
                    if (!Session.isUnlocked()) { a.goToLock(); return; }
                    message("Restore stopped", e.getMessage() == null ? "This file could not be read." : e.getMessage());
                });
            } finally {
                Arrays.fill(password, '\0');
            }
        });
    }

    // ----------------------------------------------------------------- plumbing

    /** Returns true if the result belonged to the backup flow. */
    public boolean onActivityResult(int req, int res, Intent data) {
        if (req == REQ_BACKUP) {
            if (res == SecureActivity.RESULT_OK && data != null && data.getData() != null && Session.isUnlocked()) runBackup(data.getData());
            else wipePending();
            return true;
        }
        if (req == REQ_RESTORE) {
            if (res == SecureActivity.RESULT_OK && data != null && data.getData() != null && Session.isUnlocked()) askRestorePassword(data.getData());
            return true;
        }
        return false;
    }

    public void destroy() {
        wipePending();
        worker.shutdownNow();
    }

    private void wipePending() {
        if (pendingPassword != null) { Arrays.fill(pendingPassword, '\0'); pendingPassword = null; }
    }

    private void message(String title, String msg) {
        Ui.show(Ui.dialog(a).setTitle(title).setMessage(msg).setPositiveButton("OK", null), t);
    }

    private LinearLayout box() {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(a, 22);
        box.setPadding(pad, pad / 2, pad, 0);
        return box;
    }

    private EditText field(String hint) {
        EditText e = new EditText(a);
        e.setHint(hint);
        e.setTextColor(t.text);
        e.setHintTextColor(t.muted);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        return e;
    }

    static char[] chars(EditText e) {
        char[] out = new char[e.getText().length()];
        e.getText().getChars(0, out.length, out, 0);
        e.getText().clear();
        return out;
    }
}
