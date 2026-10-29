package com.youstream.common.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.regex.Pattern;

/** {@link StorageService} on a local directory. */
public class LocalDiskStorage implements StorageService {

    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9_-][A-Za-z0-9._-]*(/[A-Za-z0-9_-][A-Za-z0-9._-]*)*");

    private final Path root;

    public LocalDiskStorage(Path root) throws IOException {
        this.root = Files.createDirectories(root).toRealPath();
    }

    public Path root() {
        return root;
    }

    @Override
    public OutputStream create(String key) throws IOException {
        Path path = path(key);
        Files.createDirectories(path.getParent());
        return Files.newOutputStream(path);
    }

    @Override
    public void write(String key, long offset, InputStream in, long length) throws IOException {
        Path path = path(key);
        Files.createDirectories(path.getParent());
        try (FileChannel out = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             ReadableByteChannel src = Channels.newChannel(in)) {
            ByteBuffer buf = ByteBuffer.allocate(256 * 1024);
            long position = offset;
            long remaining = length;
            while (remaining > 0) {
                buf.clear().limit((int) Math.min(buf.capacity(), remaining));
                int n = src.read(buf);
                if (n < 0) {
                    throw new IOException("stream ended " + remaining + " bytes early");
                }
                buf.flip();
                while (buf.hasRemaining()) {
                    position += out.write(buf, position);
                }
                remaining -= n;
            }
        }
    }

    @Override
    public InputStream open(String key) throws IOException {
        return Files.newInputStream(path(key));
    }

    @Override
    public boolean exists(String key) {
        return Files.exists(path(key));
    }

    @Override
    public long size(String key) throws IOException {
        return Files.size(path(key));
    }

    @Override
    public void move(String from, String to) throws IOException {
        Path target = path(to);
        Files.createDirectories(target.getParent());
        if (Files.isDirectory(target)) {
            deleteTree(to);
        }
        Files.move(path(from), target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    @Override
    public void delete(String key) throws IOException {
        Files.deleteIfExists(path(key));
    }

    @Override
    public void deleteTree(String prefix) throws IOException {
        Path start = path(prefix);
        if (!Files.exists(start)) {
            return;
        }
        Files.walkFileTree(start, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                if (exc != null && !(exc instanceof NoSuchFileException)) {
                    throw exc;
                }
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    @Override
    public Path localPath(String key) {
        return path(key);
    }

    /** Validates the key and resolves it strictly inside the root. */
    Path path(String key) {
        if (key == null || !KEY.matcher(key).matches() || key.contains("..")) {
            throw new IllegalArgumentException("invalid storage key: " + key);
        }
        Path path = root.resolve(key).normalize();
        if (!path.startsWith(root)) {
            throw new IllegalArgumentException("storage key escapes root: " + key);
        }
        return path;
    }
}
