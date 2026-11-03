package com.sabari.photovault.data;

import android.content.Context;

import com.sabari.photovault.crypto.KeyStoreHelper;
import com.sabari.photovault.crypto.Session;

import java.io.File;

/**
 * AUTO-ERASE. Called when too many wrong PINs are entered (only if the user turned it on).
 * Destroying the hardware keys is what really matters: without them the encrypted data is
 * useless forever, even if some bytes are still left on the flash memory.
 */
public final class VaultWiper {

    private VaultWiper() {}

    public static void wipe(Context c) {
        Session.lock();
        KeyStoreHelper.deleteAllKeys();
        c.getSharedPreferences("vault_keys", Context.MODE_PRIVATE).edit().clear().commit();
        deleteTree(new File(c.getFilesDir(), "vault"));
        new AppSettings(c).setLastBackup(0);
    }

    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }
}
