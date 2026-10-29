package com.youstream.common.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;

/**
 * Blob storage addressed by slash-separated keys such as {@code originals/abc123/source.mp4}.
 * Keys may only contain {@code [A-Za-z0-9._-]} segments; {@code ..} and absolute paths are rejected.
 */
public interface StorageService {

    /** Creates or replaces a blob. */
    OutputStream create(String key) throws IOException;

    /** Writes {@code length} bytes from {@code in} at {@code offset}, creating the blob if needed (resumable uploads). */
    void write(String key, long offset, InputStream in, long length) throws IOException;

    InputStream open(String key) throws IOException;

    boolean exists(String key);

    long size(String key) throws IOException;

    /** Moves a blob or directory, replacing the target. */
    void move(String from, String to) throws IOException;

    void delete(String key) throws IOException;

    /** Deletes a key and everything below it; no-op if absent. */
    void deleteTree(String prefix) throws IOException;

    /**
     * Local filesystem path for tools that need one (FFmpeg). Local storage returns the real file;
     * a remote implementation would stage to a temporary file.
     */
    Path localPath(String key);
}
