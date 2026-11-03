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
import java.util.Map;
import java.util.TreeMap;

/**
 * Reads and writes encrypted photos.
 *
 * Location: /data/data/com.sabari.photovault/files/vault/   (app-private; other apps can't read it)
 * For each photo 3 files, all encrypted with the master key:
 *   <id>.enc   the original photo (full quality, EXIF kept)
 *   <id>.thm   a small thumbnail for the grid
 *   <id>.meta  JSON: original name, type, date, album   (album names are encrypted too)
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

    /** All photos, newest first. Decrypts each small meta file once, then uses the RAM cache. */
    public List<PhotoItem> list() {
        List<PhotoItem> items = new ArrayList<>();
        File[] files = dir.listFiles((d, name) -> name.endsWith(".enc"));
        if (files != null) {
            for (File f : files) {
                String id = f.getName().substring(0, f.getName().length() - 4);
                JSONObject m = loadMeta(id);
                items.add(new PhotoItem(id, m.optLong("added", f.lastModified()),
                        m.optString("album", ""), m.optString("name", "photo"), f.length(),
                        m.optBoolean("fav", false)));
            }
        }
        Collections.sort(items, (a, b) -> Long.compare(b.addedAt, a.addedAt));
        return items;
    }

    /** Sorts a list in place. Uses the AppSettings.SORT_... constants. */
    public static void sort(List<PhotoItem> items, int mode) {
        switch (mode) {
            case AppSettings.SORT_OLDEST:
                Collections.sort(items, (a, b) -> Long.compare(a.addedAt, b.addedAt)); break;
            case AppSettings.SORT_NAME:
                Collections.sort(items, (a, b) -> a.name.compareToIgnoreCase(b.name)); break;
            case AppSettings.SORT_LARGEST:
                Collections.sort(items, (a, b) -> Long.compare(b.sizeBytes, a.sizeBytes)); break;
            default:
                Collections.sort(items, (a, b) -> Long.compare(b.addedAt, a.addedAt));
        }
    }

    /** Album name -> number of photos, sorted A-Z. */
    public static Map<String, Integer> albums(List<PhotoItem> items) {
        Map<String, Integer> m = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (PhotoItem p : items) {
            if (!p.album.isEmpty()) m.put(p.album, m.containsKey(p.album) ? m.get(p.album) + 1 : 1);
        }
        return m;
    }

    /** Copies a picked photo into the vault, encrypted. */
    public String importPhoto(Uri uri, String album) throws IOException, GeneralSecurityException {
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
            meta.put("album", album == null ? "" : album);
        } catch (JSONException ignored) { }

        byte[] thumb = BitmapUtils.makeThumbnail(data, THUMB_SIZE);
        if (thumb == null) throw new IOException("Not a supported image");

        writeMeta(id, meta);
        CryptoManager.encryptToFile(Session.key(), thumb, "thumb:" + id, new File(dir, id + ".thm"));
        // .enc is written last: a photo only "exists" in the grid once all parts are saved.
        CryptoManager.encryptToFile(Session.key(), data, "photo:" + id, new File(dir, id + ".enc"));
        return id;
    }

    /** Moves a photo into an album ("" = remove from album). */
    public void setAlbum(String id, String album) throws IOException, GeneralSecurityException {
        updateMeta(id, "album", album == null ? "" : album.trim());
    }

    public void setFavourite(String id, boolean fav) throws IOException, GeneralSecurityException {
        updateMeta(id, "fav", fav);
    }

    public boolean isFavourite(String id) {
        return loadMeta(id).optBoolean("fav", false);
    }

    /** Renames an album on every photo inside it. newName "" = delete the album (photos stay). */
    public int renameAlbum(String oldName, String newName) throws IOException, GeneralSecurityException {
        int n = 0;
        for (PhotoItem p : list()) {
            if (p.album.equalsIgnoreCase(oldName)) { setAlbum(p.id, newName); n++; }
        }
        return n;
    }

    /** Changes one field in a photo's encrypted details file. */
    private void updateMeta(String id, String key, Object value) throws IOException, GeneralSecurityException {
        JSONObject m;
        try {
            m = new JSONObject(loadMeta(id).toString());   // copy, so the cache only changes after saving
            m.put(key, value);
        } catch (JSONException e) {
            throw new IOException("Bad photo details");
        }
        writeMeta(id, m);
    }

    private void writeMeta(String id, JSONObject meta) throws IOException, GeneralSecurityException {
        CryptoManager.encryptToFile(Session.key(), meta.toString().getBytes(StandardCharsets.UTF_8),
                "meta:" + id, new File(dir, id + ".meta"));
        Session.META.put(id, meta);
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
        JSONObject cached = Session.META.get(id);
        if (cached != null) return cached;
        try {
            byte[] b = CryptoManager.decryptFile(Session.key(), new File(dir, id + ".meta"), "meta:" + id);
            JSONObject m = new JSONObject(new String(b, StandardCharsets.UTF_8));
            Session.META.put(id, m);
            return m;
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    public long sizeOf(String id) {
        return new File(dir, id + ".enc").length();
    }

    // ---------------- used by backup / restore ----------------

    public boolean exists(String id) {
        return new File(dir, id + ".enc").exists();
    }

    public String loadMetaJson(String id) {
        return loadMeta(id).toString();
    }

    /** Only 32 hex characters are allowed as an id (blocks tricks like "../" in a fake backup). */
    public static boolean isValidId(String id) {
        return id != null && id.matches("[0-9a-f]{32}");
    }

    /** Saves a photo coming from a backup file, encrypting it with THIS phone's master key. */
    public void saveRestored(String id, String metaJson, byte[] data) throws IOException, GeneralSecurityException {
        if (!isValidId(id)) id = CryptoManager.toHex(CryptoManager.randomBytes(16));
        byte[] thumb = BitmapUtils.makeThumbnail(data, THUMB_SIZE);
        if (thumb == null) throw new IOException("Not a supported image");
        JSONObject meta;
        try { meta = new JSONObject(metaJson); } catch (JSONException e) { meta = new JSONObject(); }
        writeMeta(id, meta);
        CryptoManager.encryptToFile(Session.key(), thumb, "thumb:" + id, new File(dir, id + ".thm"));
        CryptoManager.encryptToFile(Session.key(), data, "photo:" + id, new File(dir, id + ".enc"));
    }

    public void delete(String id) {
        new File(dir, id + ".enc").delete();
        new File(dir, id + ".thm").delete();
        new File(dir, id + ".meta").delete();
        Session.THUMBS.remove(id);
        Session.META.remove(id);
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
