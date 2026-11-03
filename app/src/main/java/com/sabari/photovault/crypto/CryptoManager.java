package com.sabari.photovault.crypto;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * All the encryption math lives here.
 *
 * Algorithm: AES-256-GCM.
 *  - AES-256 = the same cipher banks and governments use. 256-bit key.
 *  - GCM mode = encrypts AND detects tampering. If even one byte of a file
 *    is changed, decryption fails instead of giving garbage.
 *
 * Every encrypted blob is stored as:  [12-byte random IV][ciphertext + 16-byte tag]
 */
public final class CryptoManager {

    private static final String AES_GCM = "AES/GCM/NoPadding";
    public static final int IV_LEN = 12;
    public static final int TAG_BITS = 128;

    /** PBKDF2 rounds. Makes every PIN guess slow (about 1 second on a phone). */
    private static final int PBKDF2_ITERATIONS = 210_000;

    private static final SecureRandom RANDOM = new SecureRandom();

    private CryptoManager() {}

    public static byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        RANDOM.nextBytes(b);
        return b;
    }

    /** The master key that actually encrypts the photos. Created once, at setup. */
    public static SecretKey newMasterKey() throws GeneralSecurityException {
        KeyGenerator kg = KeyGenerator.getInstance("AES");
        kg.init(256, RANDOM);
        return kg.generateKey();
    }

    /** Turns your PIN + a random salt into a 256-bit key. Slow on purpose. */
    public static SecretKey deriveKeyFromPin(char[] pin, byte[] salt) throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(pin, salt, PBKDF2_ITERATIONS, 256);
        try {
            SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            byte[] raw = f.generateSecret(spec).getEncoded();
            SecretKey key = new SecretKeySpec(raw, "AES");
            Arrays.fill(raw, (byte) 0);
            return key;
        } finally {
            spec.clearPassword();
        }
    }

    // ---------- small blobs in memory (keys, metadata, thumbnails) ----------

    public static byte[] encrypt(SecretKey key, byte[] plain, String aad) throws GeneralSecurityException {
        byte[] iv = randomBytes(IV_LEN);
        Cipher c = Cipher.getInstance(AES_GCM);
        c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
        if (aad != null) c.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
        byte[] ct = c.doFinal(plain);
        return concat(iv, ct);
    }

    public static byte[] decrypt(SecretKey key, byte[] blob, String aad) throws GeneralSecurityException {
        if (blob == null || blob.length < IV_LEN + 16) throw new GeneralSecurityException("Corrupt data");
        Cipher c = Cipher.getInstance(AES_GCM);
        c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, blob, 0, IV_LEN));
        if (aad != null) c.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
        return c.doFinal(blob, IV_LEN, blob.length - IV_LEN);
    }

    // ---------- files ----------

    /** Encrypts bytes into a file. Writes to a temp file first, so a crash never leaves a half file. */
    public static void encryptToFile(SecretKey key, byte[] plain, String aad, File out)
            throws GeneralSecurityException, IOException {
        byte[] blob = encrypt(key, plain, aad);
        File tmp = new File(out.getParentFile(), out.getName() + ".tmp");
        try (FileOutputStream fos = new FileOutputStream(tmp)) {
            fos.write(blob);
            fos.getFD().sync();
        }
        if (!tmp.renameTo(out)) {
            tmp.delete();
            throw new IOException("Could not save file");
        }
    }

    public static byte[] decryptFile(SecretKey key, File in, String aad)
            throws GeneralSecurityException, IOException {
        return decrypt(key, readAll(new FileInputStream(in)), aad);
    }

    // ---------- helpers ----------

    public static byte[] readAll(InputStream in) throws IOException {
        try (InputStream is = in; ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }

    public static byte[] concat(byte[] a, byte[] b) {
        byte[] r = new byte[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    public static String toHex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
