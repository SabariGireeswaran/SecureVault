package com.sabari.photovault.data;

/** One photo inside the vault. The id is random, so file names reveal nothing. */
public class PhotoItem {
    public final String id;
    public final long addedAt;
    public final String album;   // "" = not in any album
    public final String name;
    public final long sizeBytes;
    public final boolean favourite;

    public PhotoItem(String id, long addedAt, String album, String name, long sizeBytes, boolean favourite) {
        this.id = id;
        this.addedAt = addedAt;
        this.album = album;
        this.name = name;
        this.sizeBytes = sizeBytes;
        this.favourite = favourite;
    }
}
