package com.sabari.photovault.data;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

/** Decodes images straight from memory (decrypted bytes never touch the disk). */
public final class BitmapUtils {

    private BitmapUtils() {}

    /** Decodes a picture, shrinking it so its longest side is about maxSide pixels. */
    public static Bitmap decode(byte[] data, int maxSide) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

        int sample = 1;
        int longest = Math.max(bounds.outWidth, bounds.outHeight);
        while (longest / (sample * 2) >= maxSide) sample *= 2;

        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        Bitmap bmp = BitmapFactory.decodeByteArray(data, 0, data.length, opts);
        if (bmp == null) return null;
        return rotateByExif(bmp, data);
    }

    /** Phones save some photos "sideways" with a rotation tag. This turns them upright. */
    private static Bitmap rotateByExif(Bitmap bmp, byte[] data) {
        int degrees = 0;
        try {
            ExifInterface exif = new ExifInterface(new ByteArrayInputStream(data));
            int o = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            if (o == ExifInterface.ORIENTATION_ROTATE_90) degrees = 90;
            else if (o == ExifInterface.ORIENTATION_ROTATE_180) degrees = 180;
            else if (o == ExifInterface.ORIENTATION_ROTATE_270) degrees = 270;
        } catch (Exception ignored) { }
        if (degrees == 0) return bmp;
        Matrix m = new Matrix();
        m.postRotate(degrees);
        Bitmap r = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
        if (r != bmp) bmp.recycle();
        return r;
    }

    /** Square-cropped thumbnail, saved as JPEG bytes (these bytes are then encrypted). */
    public static byte[] makeThumbnail(byte[] data, int size) {
        Bitmap src = decode(data, size * 2);
        if (src == null) return null;
        int side = Math.min(src.getWidth(), src.getHeight());
        int x = (src.getWidth() - side) / 2;
        int y = (src.getHeight() - side) / 2;
        Bitmap square = Bitmap.createBitmap(src, x, y, side, side);
        Bitmap scaled = Bitmap.createScaledBitmap(square, size, size, true);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        scaled.compress(Bitmap.CompressFormat.JPEG, 82, bos);
        if (scaled != square) scaled.recycle();
        if (square != src) square.recycle();
        src.recycle();
        return bos.toByteArray();
    }
}
