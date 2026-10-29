package com.youstream.media;

import org.junit.jupiter.api.Assumptions;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/** Locates FFmpeg for tests ({@code FFMPEG}/{@code FFPROBE} env vars or PATH) and generates sample clips. */
public final class MediaTestSupport {

    public static final String FFMPEG = env("FFMPEG", "ffmpeg");
    public static final String FFPROBE = env("FFPROBE", "ffprobe");

    private MediaTestSupport() {
    }

    public static MediaConfig config() {
        return MediaConfig.builder().ffmpeg(FFMPEG).ffprobe(FFPROBE).segmentSeconds(2).preset("ultrafast").build();
    }

    public static void assumeFfmpeg() {
        Assumptions.assumeTrue(runs(FFMPEG) && runs(FFPROBE), "FFmpeg not found; set FFMPEG/FFPROBE or add it to PATH");
    }

    /** A synthetic test-pattern clip with a sine tone. */
    public static Path generateClip(Path file, int width, int height, int seconds, boolean audio) throws Exception {
        List<String> cmd = new java.util.ArrayList<>(List.of(FFMPEG, "-hide_banner", "-loglevel", "error", "-y",
            "-f", "lavfi", "-i", "testsrc2=size=" + width + "x" + height + ":rate=25:duration=" + seconds));
        if (audio) {
            cmd.addAll(List.of("-f", "lavfi", "-i", "sine=frequency=440:duration=" + seconds, "-c:a", "aac"));
        }
        // uploads have no extension in storage, so name the container explicitly
        cmd.addAll(List.of("-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", "-f", "mp4", file.toString()));
        java.nio.file.Files.createDirectories(file.toAbsolutePath().getParent());
        ProcessRunner.Result r = new ProcessRunner().run(cmd, Duration.ofMinutes(1));
        if (r.exitCode() != 0) {
            throw new IllegalStateException("clip generation failed: " + r.stderrTail());
        }
        return file;
    }

    private static boolean runs(String exe) {
        try {
            return new ProcessRunner().run(List.of(exe, "-version"), Duration.ofSeconds(20)).exitCode() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v;
    }
}
