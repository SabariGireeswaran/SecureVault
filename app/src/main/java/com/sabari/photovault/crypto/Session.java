package com.sabari.photovault.crypto;

import android.graphics.Bitmap;
import android.util.LruCache;

import javax.crypto.SecretKey;

/**
 * Holds the master key in memory ONLY while the vault is open.
 * When the app goes to the background or the screen turns off, lock() throws it away.
 */
public final class Session {

    private static volatile SecretKey masterKey;

    /** Decrypted thumbnails, kept in RAM only (never written to disk). Cleared on lock. */
    public static final LruCache<String, Bitmap> THUMBS = new LruCache<String, Bitmap>(24 * 1024 * 1024) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
    };

    private Session() {}

    public static void unlock(SecretKey key) { masterKey = key; }

    public static boolean isUnlocked() { return masterKey != null; }

    public static SecretKey key() {
        SecretKey k = masterKey;
        if (k == null) throw new IllegalStateException("Vault is locked");
        return k;
    }

    public static void lock() {
        masterKey = null;
        THUMBS.evictAll();
    }
}
