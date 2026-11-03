package com.sabari.photovault.data;

/** One photo inside the vault. The id is random, so file names reveal nothing. */
public class PhotoItem {
    public final String id;
    public final long addedAt;

    public PhotoItem(String id, long addedAt) {
        this.id = id;
        this.addedAt = addedAt;
    }
}
