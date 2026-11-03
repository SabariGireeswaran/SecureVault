package com.sabari.photovault.ui;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.sabari.photovault.R;
import com.sabari.photovault.VaultApp;
import com.sabari.photovault.backup.BackupFormat;
import com.sabari.photovault.backup.BackupManager;
import com.sabari.photovault.crypto.KeyStoreHelper;
import com.sabari.photovault.crypto.Session;
import com.sabari.photovault.crypto.VaultKeys;
import com.sabari.photovault.data.PhotoItem;
import com.sabari.photovault.data.VaultStore;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.crypto.Cipher;

/** The gallery: a grid of encrypted photos. */
public class VaultActivity extends SecureActivity {

    private static final int REQ_PICK = 42;
    private static final int REQ_BACKUP = 43;
    private static final int REQ_RESTORE = 44;

    /** Backup password waiting while the user picks where to save the file. Wiped right after use. */
    private char[] pendingPassword;

    private VaultStore store;
    private VaultKeys keys;
    private final List<PhotoItem> items = new ArrayList<>();
    private GridAdapter adapter;
    private TextView empty;

    private final ExecutorService thumbLoader = Executors.newFixedThreadPool(2);
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!Session.isUnlocked()) return;
        setContentView(R.layout.activity_vault);
        setTitle("Vault");

        store = new VaultStore(this);
        keys = new VaultKeys(this);
        empty = findViewById(R.id.empty);
        GridView grid = findViewById(R.id.grid);
        adapter = new GridAdapter();
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((p, v, pos, id) -> {
            Intent i = new Intent(this, ViewerActivity.class);
            i.putExtra(ViewerActivity.EXTRA_ID, items.get(pos).id);
            startActivity(i);
        });
        findViewById(R.id.add).setOnClickListener(v -> pickPhotos());
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Session.isUnlocked() && store != null) reload();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        thumbLoader.shutdownNow();
        worker.shutdownNow();
        wipePending();
    }

    private void reload() {
        items.clear();
        items.addAll(store.list());
        adapter.notifyDataSetChanged();
        empty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
        setTitle("Vault  ·  " + items.size());
    }

    // ------------------------------------------------------------------ menu

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.vault_menu, menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        MenuItem bio = menu.findItem(R.id.m_bio);
        bio.setVisible(Fingerprint.isAvailable(this));
        bio.setChecked(keys != null && keys.isBioEnabled());
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.m_lock) {
            Session.lock();
            goToLock();
        } else if (id == R.id.m_backup) {
            backupPasswordDialog();
        } else if (id == R.id.m_restore) {
            pickBackupFile();
        } else if (id == R.id.m_bio) {
            toggleFingerprint();
        } else if (id == R.id.m_pin) {
            changePinDialog();
        } else if (id == R.id.m_about) {
            aboutDialog();
        }
        return true;
    }

    // ------------------------------------------------------------------ add photos

    private void pickPhotos() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        VaultApp.allowPicker();   // don't auto-lock while the picker is open
        startActivityForResult(i, REQ_PICK);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_BACKUP) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null && Session.isUnlocked()) {
                runBackup(data.getData());
            } else {
                wipePending();
            }
            return;
        }
        if (requestCode == REQ_RESTORE) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null && Session.isUnlocked()) {
                restorePasswordDialog(data.getData());
            }
            return;
        }
        if (requestCode != REQ_PICK || resultCode != RESULT_OK || data == null) return;
        if (!Session.isUnlocked()) return;

        List<Uri> uris = new ArrayList<>();
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int k = 0; k < clip.getItemCount(); k++) uris.add(clip.getItemAt(k).getUri());
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        if (uris.isEmpty()) return;

        new AlertDialog.Builder(this)
                .setTitle("Hide " + uris.size() + (uris.size() == 1 ? " photo" : " photos"))
                .setMessage("Delete the original from your phone's gallery after it is safely encrypted?\n\n"
                        + "(Recommended. Also check Google Photos / any cloud backup, which may keep its own copy.)")
                .setPositiveButton("Encrypt & delete original", (d, w) -> runImport(uris, true))
                .setNegativeButton("Encrypt, keep original", (d, w) -> runImport(uris, false))
                .show();
    }

    private void runImport(List<Uri> uris, boolean deleteOriginals) {
        AlertDialog progress = new AlertDialog.Builder(this)
                .setTitle("Encrypting…")
                .setMessage("0 of " + uris.size())
                .setCancelable(false)
                .show();

        worker.execute(() -> {
            int ok = 0, failed = 0, notDeleted = 0;
            for (int k = 0; k < uris.size(); k++) {
                final int n = k + 1;
                main.post(() -> progress.setMessage(n + " of " + uris.size()));
                try {
                    store.importPhoto(uris.get(k));
                    ok++;
                    if (deleteOriginals && !store.deleteOriginal(uris.get(k))) notDeleted++;
                } catch (Exception e) {
                    failed++;
                    if (!Session.isUnlocked()) break;   // vault was locked mid-way (screen off)
                }
            }
            final int fOk = ok, fFailed = failed, fNotDeleted = notDeleted;
            main.post(() -> {
                progress.dismiss();
                if (!Session.isUnlocked()) { goToLock(); return; }
                reload();
                StringBuilder msg = new StringBuilder(fOk + " photo(s) encrypted.");
                if (fFailed > 0) msg.append("\n").append(fFailed).append(" could not be added.");
                if (fNotDeleted > 0) msg.append("\n\n").append(fNotDeleted)
                        .append(" original(s) could not be deleted automatically. Please delete them from your Gallery app yourself.");
                new AlertDialog.Builder(this).setMessage(msg.toString()).setPositiveButton("OK", null).show();
            });
        });
    }

    // ------------------------------------------------------------------ backup & restore

    private void backupPasswordDialog() {
        if (items.isEmpty()) {
            Toast.makeText(this, "The vault is empty — nothing to back up", Toast.LENGTH_SHORT).show();
            return;
        }
        LinearLayout box = dialogBox();
        TextView info = new TextView(this);
        info.setText("Choose a BACKUP PASSWORD. It protects the backup file on its own, "
                + "even on another phone or laptop.\n\n"
                + "• At least " + BackupFormat.MIN_PASSWORD + " characters, letters + numbers\n"
                + "• Different from your PIN\n"
                + "• Write it down and keep it somewhere safe.\n"
                + "  Forget it = the backup can never be opened.");
        info.setTextColor(Color.parseColor("#8B95A3"));
        EditText p1 = passwordField("Backup password"), p2 = passwordField("Confirm backup password");
        box.addView(info);
        box.addView(p1);
        box.addView(p2);

        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("Create encrypted backup")
                .setView(box)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Next", null)
                .show();
        // Custom click so the dialog stays open when the password is not good enough.
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            char[] a = toChars(p1), b = toChars(p2);
            String problem = BackupFormat.passwordProblem(a);
            if (problem == null && !Arrays.equals(a, b)) problem = "Passwords do not match";
            Arrays.fill(b, '\0');
            if (problem != null) {
                Arrays.fill(a, '\0');
                Toast.makeText(this, problem, Toast.LENGTH_LONG).show();
                return;
            }
            dlg.dismiss();
            wipePending();
            pendingPassword = a;
            chooseBackupLocation();
        });
    }

    private void chooseBackupLocation() {
        String date = new SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(new Date());
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/octet-stream");
        i.putExtra(Intent.EXTRA_TITLE, "SecureVault-backup_" + date + ".svb");
        VaultApp.allowPicker();
        startActivityForResult(i, REQ_BACKUP);
    }

    private void runBackup(Uri target) {
        char[] password = pendingPassword;
        pendingPassword = null;
        if (password == null) return;
        AlertDialog progress = new AlertDialog.Builder(this)
                .setTitle("Creating backup…")
                .setMessage("Preparing (this takes a few seconds)…")
                .setCancelable(false)
                .show();
        worker.execute(() -> {
            try {
                int count = new BackupManager(this).backup(target, password,
                        (done, total) -> main.post(() -> progress.setMessage(done + " of " + total + " photos")));
                main.post(() -> {
                    progress.dismiss();
                    new AlertDialog.Builder(this)
                            .setTitle("Backup complete")
                            .setMessage(count + " photos saved into one encrypted file.\n\n"
                                    + "Now copy this file to a pendrive or laptop. A backup that stays only "
                                    + "on this phone won't help if the phone is lost.\n\n"
                                    + "To restore: install this app on the new phone, create a PIN, "
                                    + "then Menu → Restore from backup file.")
                            .setPositiveButton("OK", null)
                            .show();
                });
            } catch (Exception e) {
                main.post(() -> {
                    progress.dismiss();
                    if (!Session.isUnlocked()) { goToLock(); return; }
                    showMessage("Backup failed", "The backup could not be finished. Please try again.\n\n("
                            + e.getMessage() + ")");
                });
            } finally {
                Arrays.fill(password, '\0');
            }
        });
    }

    private void pickBackupFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");   // .svb files have no standard type, so show all files
        VaultApp.allowPicker();
        startActivityForResult(i, REQ_RESTORE);
    }

    private void restorePasswordDialog(Uri source) {
        LinearLayout box = dialogBox();
        EditText p = passwordField("Backup password");
        box.addView(p);
        new AlertDialog.Builder(this)
                .setTitle("Restore from backup")
                .setMessage("Enter the password you chose when the backup was created.")
                .setView(box)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Restore", (d, w) -> runRestore(source, toChars(p)))
                .show();
    }

    private void runRestore(Uri source, char[] password) {
        AlertDialog progress = new AlertDialog.Builder(this)
                .setTitle("Restoring…")
                .setMessage("Checking password…")
                .setCancelable(false)
                .show();
        worker.execute(() -> {
            try {
                BackupManager.Result r = new BackupManager(this).restore(source, password,
                        (done, total) -> main.post(() -> progress.setMessage(done + " photos processed")));
                main.post(() -> {
                    progress.dismiss();
                    reload();
                    StringBuilder m = new StringBuilder();
                    m.append(r.restored).append(" photos restored.");
                    if (r.skipped > 0) m.append("\n").append(r.skipped).append(" were already in the vault (skipped).");
                    if (r.failed > 0) m.append("\n").append(r.failed).append(" could not be restored.");
                    m.append("\n\nThe backup file was checked and is not damaged.");
                    showMessage("Restore complete", m.toString());
                });
            } catch (BackupFormat.WrongPasswordException e) {
                main.post(() -> { progress.dismiss(); showMessage("Wrong password", "That is not the password of this backup file."); });
            } catch (Exception e) {
                main.post(() -> {
                    progress.dismiss();
                    reload();
                    if (!Session.isUnlocked()) { goToLock(); return; }
                    showMessage("Restore stopped", e.getMessage() == null ? "This file could not be read." : e.getMessage());
                });
            } finally {
                Arrays.fill(password, '\0');
            }
        });
    }

    private void wipePending() {
        if (pendingPassword != null) { Arrays.fill(pendingPassword, '\0'); pendingPassword = null; }
    }

    private void showMessage(String title, String msg) {
        new AlertDialog.Builder(this).setTitle(title).setMessage(msg).setPositiveButton("OK", null).show();
    }

    private LinearLayout dialogBox() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        return box;
    }

    private EditText passwordField(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        return e;
    }

    // ------------------------------------------------------------------ settings

    private void toggleFingerprint() {
        if (keys.isBioEnabled()) {
            keys.disableBio();
            Toast.makeText(this, "Fingerprint unlock turned off", Toast.LENGTH_SHORT).show();
            invalidateOptionsMenu();
            return;
        }
        try {
            KeyStoreHelper.createBioKey();
            Cipher c = KeyStoreHelper.bioEncryptCipher();
            Fingerprint.prompt(this, "Confirm your fingerprint", c, new Fingerprint.Callback() {
                @Override public void onSuccess(Cipher approved) {
                    try {
                        keys.enableBio(approved, Session.key());
                        Toast.makeText(VaultActivity.this, "Fingerprint unlock turned on", Toast.LENGTH_SHORT).show();
                    } catch (Exception e) {
                        KeyStoreHelper.deleteBioKey();
                    }
                    invalidateOptionsMenu();
                }
                @Override public void onFailed(String m) {
                    KeyStoreHelper.deleteBioKey();
                    invalidateOptionsMenu();
                }
            });
        } catch (Exception e) {
            Toast.makeText(this, "Fingerprint not available", Toast.LENGTH_SHORT).show();
        }
    }

    private void changePinDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        EditText cur = pinField("Current PIN"), n1 = pinField("New PIN"), n2 = pinField("Confirm new PIN");
        box.addView(cur);
        box.addView(n1);
        box.addView(n2);

        new AlertDialog.Builder(this)
                .setTitle("Change PIN")
                .setView(box)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Change", (d, w) -> {
                    char[] c = toChars(cur), a = toChars(n1), b = toChars(n2);
                    if (a.length < VaultKeys.MIN_PIN || !Arrays.equals(a, b)) {
                        Toast.makeText(this, "New PINs must match and be at least " + VaultKeys.MIN_PIN + " digits", Toast.LENGTH_LONG).show();
                        wipe(c, a, b);
                        return;
                    }
                    worker.execute(() -> {
                        try {
                            keys.unlockWithPin(c);          // prove you know the current PIN
                            keys.changePin(Session.key(), a);
                            main.post(() -> Toast.makeText(this, "PIN changed", Toast.LENGTH_SHORT).show());
                        } catch (VaultKeys.WrongPinException e) {
                            keys.recordFailure();
                            main.post(() -> Toast.makeText(this, "Current PIN is wrong", Toast.LENGTH_SHORT).show());
                        } catch (Exception e) {
                            main.post(() -> Toast.makeText(this, "Could not change PIN", Toast.LENGTH_SHORT).show());
                        } finally {
                            wipe(c, a, b);
                        }
                    });
                })
                .show();
    }

    private void aboutDialog() {
        new AlertDialog.Builder(this)
                .setTitle("How your photos are protected")
                .setMessage("• Every photo is encrypted with AES-256-GCM before it is saved.\n\n"
                        + "• The key is locked by your PIN AND by this phone's security chip. "
                        + "Copying the files to another device is useless.\n\n"
                        + "• 5 wrong PINs → waiting time that doubles each time (up to 1 hour).\n\n"
                        + "• The app has NO internet permission. It cannot send anything anywhere.\n\n"
                        + "• Screenshots, screen recording and the recent-apps preview are blocked.\n\n"
                        + "• The vault locks the moment you leave the app or turn off the screen.\n\n"
                        + "• Google/cloud backup is disabled, so photos never go to Google Drive.\n\n"
                        + "• Your own backup: Menu → Backup to file. It creates one encrypted .svb file "
                        + "that you can keep on a pendrive. It opens on any phone with this app (or on a laptop) "
                        + "with your backup password.\n\n"
                        + "⚠ Lost PIN, lost phone or uninstalled app = photos gone, UNLESS you have a "
                        + "backup file and its password.")
                .setPositiveButton("OK", null)
                .show();
    }

    private EditText pinField(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        return e;
    }

    private static char[] toChars(EditText e) {
        char[] out = new char[e.getText().length()];
        e.getText().getChars(0, out.length, out, 0);
        e.getText().clear();
        return out;
    }

    private static void wipe(char[]... arrays) {
        for (char[] a : arrays) Arrays.fill(a, '\0');
    }

    // ------------------------------------------------------------------ grid

    private class GridAdapter extends BaseAdapter {
        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int p) { return items.get(p); }
        @Override public long getItemId(int p) { return p; }

        @Override
        public View getView(int pos, View convert, ViewGroup parent) {
            ImageView iv = (ImageView) convert;
            if (iv == null) {
                iv = new ImageView(VaultActivity.this);
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                iv.setBackgroundColor(Color.parseColor("#1A1F27"));
            }
            int side = ((GridView) parent).getColumnWidth();
            iv.setLayoutParams(new AbsListView.LayoutParams(side, side));

            String id = items.get(pos).id;
            iv.setTag(id);
            Bitmap cached = Session.THUMBS.get(id);
            if (cached != null) {
                iv.setImageBitmap(cached);
            } else {
                iv.setImageDrawable(null);
                final ImageView target = iv;
                thumbLoader.execute(() -> {
                    try {
                        Bitmap b = store.loadThumbnail(id);
                        if (b == null) return;
                        Session.THUMBS.put(id, b);
                        main.post(() -> { if (id.equals(target.getTag())) target.setImageBitmap(b); });
                    } catch (Exception ignored) { }
                });
            }
            return iv;
        }
    }
}
