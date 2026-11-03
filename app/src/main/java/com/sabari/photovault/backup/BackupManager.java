package com.sabari.photovault.backup;

import android.content.Context;
import android.net.Uri;

import com.sabari.photovault.data.PhotoItem;
import com.sabari.photovault.data.VaultStore;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.List;

/**
 * Connects the .svb file format to the vault.
 * The file can be saved anywhere Android lets you pick: Downloads, SD card, a pendrive (USB OTG), etc.
 */
public class BackupManager {

    public interface Progress { void update(int done, int total); }

    public static class Result {
        public int restored, skipped, failed;
    }

    private final Context ctx;
    private final VaultStore store;

    public BackupManager(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.store = new VaultStore(ctx);
    }

    /** Writes every photo in the vault into one encrypted backup file. Returns the photo count. */
    public int backup(Uri target, char[] password, Progress p) throws IOException, GeneralSecurityException {
        List<PhotoItem> items = store.list();
        OutputStream raw = ctx.getContentResolver().openOutputStream(target, "wt");
        if (raw == null) throw new IOException("Cannot write to that location");
        try (OutputStream os = new BufferedOutputStream(raw, 256 * 1024)) {
            BackupFormat.Writer w = new BackupFormat.Writer(os, password);
            for (int i = 0; i < items.size(); i++) {
                String id = items.get(i).id;
                byte[] data = store.loadPhotoBytes(id);
                w.addPhoto(id, store.loadMetaJson(id), data);
                Arrays.fill(data, (byte) 0);
                p.update(i + 1, items.size());
            }
            w.finish();
        }
        return items.size();
    }

    /** Reads a backup file and adds its photos to this vault. Photos already in the vault are skipped. */
    public Result restore(Uri source, char[] password, Progress p)
            throws IOException, GeneralSecurityException, BackupFormat.WrongPasswordException {
        Result r = new Result();
        InputStream raw = ctx.getContentResolver().openInputStream(source);
        if (raw == null) throw new IOException("Cannot open that file");
        try (InputStream is = new BufferedInputStream(raw, 256 * 1024)) {
            BackupFormat.Reader reader = new BackupFormat.Reader(is, password);
            BackupFormat.Entry e;
            int n = 0;
            while ((e = reader.next()) != null) {
                n++;
                if (VaultStore.isValidId(e.id) && store.exists(e.id)) {
                    r.skipped++;
                } else {
                    try {
                        store.saveRestored(e.id, e.metaJson, e.data);
                        r.restored++;
                    } catch (IOException bad) {
                        r.failed++;
                    }
                }
                Arrays.fill(e.data, (byte) 0);
                p.update(n, -1);
            }
        }
        return r;
    }
}
