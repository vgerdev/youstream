package com.youstream.common.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalDiskStorageTest {

    @TempDir
    Path dir;
    private LocalDiskStorage storage;

    @BeforeEach
    void setUp() throws IOException {
        storage = new LocalDiskStorage(dir.resolve("data"));
    }

    @Test
    void createOpenSizeAndDelete() throws IOException {
        try (OutputStream out = storage.create("originals/abc/source.mp4")) {
            out.write("hello".getBytes(StandardCharsets.UTF_8));
        }

        assertThat(storage.exists("originals/abc/source.mp4")).isTrue();
        assertThat(storage.size("originals/abc/source.mp4")).isEqualTo(5);
        try (InputStream in = storage.open("originals/abc/source.mp4")) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("hello");
        }
        storage.delete("originals/abc/source.mp4");
        assertThat(storage.exists("originals/abc/source.mp4")).isFalse();
    }

    @Test
    void writesChunksAtOffsets() throws IOException {
        storage.write("incoming/u1", 0, bytes("abc"), 3);
        storage.write("incoming/u1", 3, bytes("defXX"), 3);
        storage.write("incoming/u1", 0, bytes("abc"), 3); // retried chunk is idempotent

        assertThat(Files.readString(storage.localPath("incoming/u1"))).isEqualTo("abcdef");
        assertThatThrownBy(() -> storage.write("incoming/u1", 6, bytes("g"), 5)).isInstanceOf(IOException.class);
    }

    @Test
    void movesFilesAndDirectoriesAndDeletesTrees() throws IOException {
        storage.write("hls/tmp/720p/index.m3u8", 0, bytes("x"), 1);
        storage.write("hls/final/old.m3u8", 0, bytes("old"), 3);

        storage.move("hls/tmp", "hls/final");
        assertThat(storage.exists("hls/final/720p/index.m3u8")).isTrue();
        assertThat(storage.exists("hls/final/old.m3u8")).isFalse();

        storage.deleteTree("hls/final");
        storage.deleteTree("hls/missing");
        assertThat(storage.exists("hls/final")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"../etc/passwd", "a/../../b", "/abs", "a//b", "a\\b", "", ".hidden", "a/./b", "C:/x"})
    void rejectsUnsafeKeys(String key) {
        assertThatThrownBy(() -> storage.localPath(key)).isInstanceOf(IllegalArgumentException.class);
    }

    private static InputStream bytes(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }
}
