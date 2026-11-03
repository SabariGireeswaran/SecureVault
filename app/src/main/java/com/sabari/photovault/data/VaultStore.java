package com.sabari.photovault.data;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.OpenableColumns;

import com.sabari.photovault.crypto.CryptoManager;
import com.sabari.photovault.crypto.Session;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Reads and writes encrypted photos.
 *
 * Location: /data/data/com.sabari.photovault/files/vault/   (app-private; other apps can't read it)
 * For each photo 3 files, all encrypted with the master key:
 *   <id>.enc   the original photo (full quality, EXIF kept)
 *   <id>.thm   a small thumbnail for the grid
 *   <id>.meta  original file name, type, date
 * AAD ("photo:"+id etc.) ties each file to its id, so files can't be swapped around.
 */
public class VaultStore {

    private static final int THUMB_SIZE = 320;
    private final File dir;
    private final Context ctx;

    public VaultStore(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        dir = new File(this.ctx.getFilesDir(), "vault");
        if (!dir.exists()) dir.mkdirs();
    }

    public List<PhotoItem> list() {
        List<PhotoItem> items = new ArrayList<>();
        File[] files = dir.listFiles((d, name) -> name.endsWith(".enc"));
        if (files != null) {
            for (File f : files) {
                String id = f.getName().substring(0, f.getName().length() - 4);
                items.add(new PhotoItem(id, f.lastModified()));
            }
        }
        Collections.sort(items, (a, b) -> Long.compare(b.addedAt, a.addedAt)); // newest first
        return items;
    }

    /** Copies a picked photo into the vault, encrypted. Returns the new id. */
    public String importPhoto(Uri uri) throws IOException, GeneralSecurityException {
        ContentResolver cr = ctx.getContentResolver();
        InputStream in = cr.openInputStream(uri);
        if (in == null) throw new IOException("Cannot open photo");
        byte[] data = CryptoManager.readAll(in);

        String id = CryptoManager.toHex(CryptoManager.randomBytes(16));
        String mime = cr.getType(uri);
        JSONObject meta = new JSONObject();
        try {
            meta.put("name", displayName(cr, uri));
            meta.put("mime", mime == null ? "image/jpeg" : mime);
            meta.put("added", System.currentTimeMillis());
        } catch (JSONException ignored) { }

        byte[] thumb = BitmapUtils.makeThumbnail(data, THUMB_SIZE);
        if (thumb == null) throw new IOException("Not a supported image");

        CryptoManager.encryptToFile(Session.key(), meta.toString().getBytes(StandardCharsets.UTF_8),
                "meta:" + id, new File(dir, id + ".meta"));
        CryptoManager.encryptToFile(Session.key(), thumb, "thumb:" + id, new File(dir, id + ".thm"));
        // .enc is written last: a photo only "exists" in the grid once all parts are saved.
        CryptoManager.encryptToFile(Session.key(), data, "photo:" + id, new File(dir, id + ".enc"));
        return id;
    }

    /** Tries to remove the original from the phone gallery. Returns false if Android refused. */
    public boolean deleteOriginal(Uri uri) {
        try {
            return DocumentsContract.deleteDocument(ctx.getContentResolver(), uri);
        } catch (Exception e) {
            return false;
        }
    }

    public Bitmap loadThumbnail(String id) throws IOException, GeneralSecurityException {
        byte[] jpg = CryptoManager.decryptFile(Session.key(), new File(dir, id + ".thm"), "thumb:" + id);
        return BitmapFactory.decodeByteArray(jpg, 0, jpg.length);
    }

    public byte[] loadPhotoBytes(String id) throws IOException, GeneralSecurityException {
        return CryptoManager.decryptFile(Session.key(), new File(dir, id + ".enc"), "photo:" + id);
    }

    public JSONObject loadMeta(String id) {
        try {
            byte[] b = CryptoManager.decryptFile(Session.key(), new File(dir, id + ".meta"), "meta:" + id);
            return new JSONObject(new String(b, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    public void delete(String id) {
        new File(dir, id + ".enc").delete();
        new File(dir, id + ".thm").delete();
        new File(dir, id + ".meta").delete();
        Session.THUMBS.remove(id);
    }

    /** Puts a decrypted copy back into the normal gallery (Pictures/Restored). */
    public void exportToGallery(String id) throws IOException, GeneralSecurityException {
        JSONObject meta = loadMeta(id);
        String name = meta.optString("name", "photo_" + id.substring(0, 8) + ".jpg");
        String mime = meta.optString("mime", "image/jpeg");
        byte[] data = loadPhotoBytes(id);

        ContentValues v = new ContentValues();
        v.put(MediaStore.Images.Media.DISPLAY_NAME, name);
        v.put(MediaStore.Images.Media.MIME_TYPE, mime);
        v.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Restored");
        v.put(MediaStore.Images.Media.IS_PENDING, 1);
        ContentResolver cr = ctx.getContentResolver();
        Uri out = cr.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), v);
        if (out == null) throw new IOException("Could not create gallery file");
        try (OutputStream os = cr.openOutputStream(out)) {
            if (os == null) throw new IOException("Could not write gallery file");
            os.write(data);
        }
        v.clear();
        v.put(MediaStore.Images.Media.IS_PENDING, 0);
        cr.update(out, v, null, null);
    }

    private static String displayName(ContentResolver cr, Uri uri) {
        try (Cursor c = cr.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) { }
        return "photo.jpg";
    }
}
