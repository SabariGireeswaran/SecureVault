package com.sabari.photovault.backup;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * The ".svb" (SecureVault Backup) file format.
 * Pure Java (no Android code), so the SAME logic also runs on a laptop.
 *
 * A backup file is NOT tied to any phone. It is protected only by the backup password.
 * That's why the backup password must be long and strong.
 *
 * File layout:
 *   HEADER : "SVB1" (4 bytes) | PBKDF2 iterations (int) | salt (16 bytes)
 *   RECORD : length (int) | IV (12 bytes) | AES-256-GCM ciphertext + tag
 *   ... one record per photo ...
 *   END RECORD (says how many photos there were)
 *
 * Every record is encrypted with AAD = header + record number. So nobody can
 * change, reorder, remove or add records without decryption failing.
 * The END record means a cut-off (truncated) file is detected too.
 *
 * Inside a photo record (after decryption):
 *   type=1 (byte) | id length (short) | id | meta length (int) | meta JSON | photo bytes
 */
public final class BackupFormat {

    public static final byte[] MAGIC = "SVB1".getBytes(StandardCharsets.US_ASCII);
    public static final int ITERATIONS = 310_000;
    public static final int MAX_RECORD = 300 * 1024 * 1024;
    public static final int MIN_PASSWORD = 10;

    private static final byte TYPE_PHOTO = 1;
    private static final byte TYPE_END = 2;
    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;

    private BackupFormat() {}

    public static class WrongPasswordException extends Exception {
        public WrongPasswordException() { super("Wrong backup password"); }
    }

    public static class CorruptBackupException extends IOException {
        public CorruptBackupException(String m) { super(m); }
    }

    /** One photo read back from a backup. */
    public static class Entry {
        public final String id;
        public final String metaJson;
        public final byte[] data;
        Entry(String id, String metaJson, byte[] data) { this.id = id; this.metaJson = metaJson; this.data = data; }
    }

    // =====================================================================
    // WRITER
    // =====================================================================

    public static class Writer {
        private final DataOutputStream out;
        private final SecretKey key;
        private final byte[] header;
        private final SecureRandom random = new SecureRandom();
        private int index = 0;
        private int photos = 0;

        public Writer(OutputStream os, char[] password) throws IOException, GeneralSecurityException {
            out = new DataOutputStream(os);
            byte[] salt = new byte[16];
            random.nextBytes(salt);
            header = buildHeader(ITERATIONS, salt);
            out.write(header);
            key = deriveKey(password, salt, ITERATIONS);
        }

        public void addPhoto(String id, String metaJson, byte[] data) throws IOException, GeneralSecurityException {
            byte[] idB = id.getBytes(StandardCharsets.UTF_8);
            byte[] metaB = metaJson.getBytes(StandardCharsets.UTF_8);
            ByteArrayOutputStream bos = new ByteArrayOutputStream(data.length + metaB.length + 64);
            DataOutputStream d = new DataOutputStream(bos);
            d.writeByte(TYPE_PHOTO);
            d.writeShort(idB.length);
            d.write(idB);
            d.writeInt(metaB.length);
            d.write(metaB);
            d.write(data);
            byte[] plain = bos.toByteArray();
            writeRecord(plain);
            Arrays.fill(plain, (byte) 0);
            photos++;
        }

        /** MUST be called at the end. Without it, the backup is treated as broken. */
        public void finish() throws IOException, GeneralSecurityException {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            DataOutputStream d = new DataOutputStream(bos);
            d.writeByte(TYPE_END);
            d.writeInt(photos);
            writeRecord(bos.toByteArray());
            out.flush();
        }

        private void writeRecord(byte[] plain) throws IOException, GeneralSecurityException {
            byte[] iv = new byte[IV_LEN];
            random.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            c.updateAAD(aad(header, index));
            byte[] ct = c.doFinal(plain);
            out.writeInt(IV_LEN + ct.length);
            out.write(iv);
            out.write(ct);
            index++;
        }
    }

    // =====================================================================
    // READER
    // =====================================================================

    public static class Reader {
        private final DataInputStream in;
        private final SecretKey key;
        private final byte[] header;
        private int index = 0;
        private int photosRead = 0;
        private boolean done = false;

        public Reader(InputStream is, char[] password) throws IOException, GeneralSecurityException {
            in = new DataInputStream(is);
            byte[] magic = new byte[4];
            readFully(magic);
            if (!Arrays.equals(magic, MAGIC)) throw new CorruptBackupException("This is not a SecureVault backup file");
            int iterations = in.readInt();
            if (iterations < 100_000 || iterations > 10_000_000) throw new CorruptBackupException("Bad backup header");
            byte[] salt = new byte[16];
            readFully(salt);
            header = buildHeader(iterations, salt);
            key = deriveKey(password, salt, iterations);
        }

        /** Returns the next photo, or null when the backup is finished. */
        public Entry next() throws IOException, GeneralSecurityException, WrongPasswordException {
            if (done) return null;
            int len;
            try {
                len = in.readInt();
            } catch (EOFException e) {
                throw new CorruptBackupException("Backup file is incomplete (cut off)");
            }
            if (len < IV_LEN + 16 || len > MAX_RECORD) throw new CorruptBackupException("Backup file is damaged");
            byte[] rec = new byte[len];
            readFully(rec);

            byte[] plain;
            try {
                Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
                c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, rec, 0, IV_LEN));
                c.updateAAD(aad(header, index));
                plain = c.doFinal(rec, IV_LEN, len - IV_LEN);
            } catch (AEADBadTagException e) {
                // The very first record failing = wrong password. Later failure = file was changed.
                if (index == 0) throw new WrongPasswordException();
                throw new CorruptBackupException("Backup file is damaged or was modified");
            }
            index++;

            DataInputStream d = new DataInputStream(new java.io.ByteArrayInputStream(plain));
            byte type = d.readByte();
            if (type == TYPE_END) {
                int count = d.readInt();
                if (count != photosRead) throw new CorruptBackupException("Backup photo count mismatch");
                done = true;
                return null;
            }
            if (type != TYPE_PHOTO) throw new CorruptBackupException("Unknown record");
            byte[] idB = new byte[d.readUnsignedShort()];
            d.readFully(idB);
            int metaLen = d.readInt();
            if (metaLen < 0 || metaLen > plain.length) throw new CorruptBackupException("Bad record");
            byte[] metaB = new byte[metaLen];
            d.readFully(metaB);
            int headerLen = 1 + 2 + idB.length + 4 + metaLen;
            byte[] data = Arrays.copyOfRange(plain, headerLen, plain.length);
            Arrays.fill(plain, (byte) 0);
            photosRead++;
            return new Entry(new String(idB, StandardCharsets.UTF_8), new String(metaB, StandardCharsets.UTF_8), data);
        }

        private void readFully(byte[] b) throws IOException {
            try {
                in.readFully(b);
            } catch (EOFException e) {
                throw new CorruptBackupException("Backup file is incomplete (cut off)");
            }
        }
    }

    // =====================================================================
    // shared helpers
    // =====================================================================

    private static byte[] buildHeader(int iterations, byte[] salt) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(bos);
        d.write(MAGIC);
        d.writeInt(iterations);
        d.write(salt);
        return bos.toByteArray();
    }

    private static byte[] aad(byte[] header, int index) {
        byte[] a = Arrays.copyOf(header, header.length + 4);
        a[header.length] = (byte) (index >>> 24);
        a[header.length + 1] = (byte) (index >>> 16);
        a[header.length + 2] = (byte) (index >>> 8);
        a[header.length + 3] = (byte) index;
        return a;
    }

    private static SecretKey deriveKey(char[] password, byte[] salt, int iterations) throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, 256);
        try {
            byte[] raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            SecretKey k = new SecretKeySpec(raw, "AES");
            Arrays.fill(raw, (byte) 0);
            return k;
        } finally {
            spec.clearPassword();
        }
    }

    /** Simple strength rule: 10+ characters, and not all the same kind (only digits etc.). */
    public static String passwordProblem(char[] p) {
        if (p.length < MIN_PASSWORD) return "Use at least " + MIN_PASSWORD + " characters";
        boolean letter = false, digit = false, other = false;
        for (char c : p) {
            if (Character.isLetter(c)) letter = true;
            else if (Character.isDigit(c)) digit = true;
            else other = true;
        }
        int kinds = (letter ? 1 : 0) + (digit ? 1 : 0) + (other ? 1 : 0);
        if (kinds < 2) return "Mix letters with numbers or symbols";
        return null;
    }
}
