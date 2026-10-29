package com.youstream.media;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.DoubleConsumer;

/**
 * Turns a source video into multi-bitrate HLS (fMP4 segments) with one FFmpeg run, plus a poster frame.
 *
 * <p>Output layout: {@code master.m3u8} and one directory per rung ({@code 720p/index.m3u8, init.mp4, seg_00001.m4s ...}).
 */
public class HlsTranscoder {

    private final MediaConfig config;
    private final ProcessRunner runner;

    public HlsTranscoder(MediaConfig config, ProcessRunner runner) {
        this.config = config;
        this.runner = runner;
    }

    /**
     * @param progress receives 0..1 as encoding advances
     * @return the rungs that were produced, highest first
     */
    public List<Ladder.Rung> transcode(Path input, ProbeResult probe, Path outDir, DoubleConsumer progress)
        throws IOException, InterruptedException {
        List<Ladder.Rung> rungs = Ladder.forSource(probe.getWidth(), probe.getHeight());
        Files.createDirectories(outDir);
        List<String> cmd = command(input, probe, outDir, rungs);
        double totalUs = probe.getDurationMs() * 1000.0;
        ProcessRunner.Result result = runner.run(cmd, timeout(probe), line -> {
            // -progress prints key=value lines; out_time_us is microseconds despite older "out_time_ms" naming
            if (line.startsWith("out_time_us=") && totalUs > 0) {
                try {
                    progress.accept(Math.min(1.0, Long.parseLong(line.substring(12).trim()) / totalUs));
                } catch (NumberFormatException ignored) {
                    // "N/A" before the first frame
                }
            }
        });
        if (result.exitCode() != 0) {
            throw new IOException("ffmpeg exited with " + result.exitCode() + ": " + lastLine(result.stderrTail()));
        }
        if (!Files.isRegularFile(outDir.resolve("master.m3u8"))) {
            throw new IOException("ffmpeg produced no master playlist");
        }
        return rungs;
    }

    /** Writes a JPEG poster frame from about 10% into the video. */
    public void poster(Path input, ProbeResult probe, Path jpeg) throws IOException, InterruptedException {
        Files.createDirectories(jpeg.getParent());
        double at = Math.max(0, Math.min(probe.getDurationMs() / 1000.0 * 0.1, probe.getDurationMs() / 1000.0 - 0.5));
        ProcessRunner.Result result = runner.run(List.of(config.getFfmpeg(), "-hide_banner", "-loglevel", "error", "-y",
            "-ss", String.format(Locale.ROOT, "%.3f", at), "-i", input.toAbsolutePath().toString(),
            "-frames:v", "1", "-vf", "scale=-2:'min(720,ih)'", "-q:v", "3", jpeg.toAbsolutePath().toString()),
            Duration.ofMinutes(2));
        if (result.exitCode() != 0 || !Files.isRegularFile(jpeg)) {
            throw new IOException("poster extraction failed: " + lastLine(result.stderrTail()));
        }
    }

    List<String> command(Path input, ProbeResult probe, Path outDir, List<Ladder.Rung> rungs) {
        int n = rungs.size();
        int seg = config.getSegmentSeconds();
        List<String> cmd = new ArrayList<>(List.of(config.getFfmpeg(), "-hide_banner", "-nostats", "-loglevel", "error",
            "-y", "-i", input.toAbsolutePath().toString()));

        StringBuilder filter = new StringBuilder("[0:v]split=").append(n);
        for (int i = 0; i < n; i++) {
            filter.append("[s").append(i).append(']');
        }
        for (int i = 0; i < n; i++) {
            filter.append(";[s").append(i).append("]scale=-2:").append(rungs.get(i).height()).append("[v").append(i).append(']');
        }
        cmd.addAll(List.of("-filter_complex", filter.toString()));

        StringBuilder streamMap = new StringBuilder();
        for (int i = 0; i < n; i++) {
            Ladder.Rung r = rungs.get(i);
            cmd.addAll(List.of("-map", "[v" + i + "]",
                "-c:v:" + i, "libx264",
                "-b:v:" + i, r.videoKbps() + "k",
                "-maxrate:v:" + i, (r.videoKbps() * 107 / 100) + "k",
                "-bufsize:v:" + i, (r.videoKbps() * 3 / 2) + "k"));
            if (probe.isAudio()) {
                cmd.addAll(List.of("-map", "0:a:0"));
            }
            streamMap.append(i > 0 ? " " : "").append("v:").append(i);
            if (probe.isAudio()) {
                streamMap.append(",a:").append(i);
            }
            streamMap.append(",name:").append(r.name());
        }
        cmd.addAll(List.of(
            "-preset", config.getPreset(),
            "-profile:v", "high",
            "-pix_fmt", "yuv420p",
            // key frame at every segment boundary so all renditions switch cleanly
            "-sc_threshold", "0",
            "-force_key_frames", "expr:gte(t,n_forced*" + seg + ")"));
        if (probe.isAudio()) {
            cmd.addAll(List.of("-c:a", "aac", "-b:a", "128k", "-ac", "2"));
        }
        cmd.addAll(List.of(
            "-f", "hls",
            "-hls_time", Integer.toString(seg),
            "-hls_playlist_type", "vod",
            "-hls_segment_type", "fmp4",
            "-hls_flags", "independent_segments",
            "-hls_fmp4_init_filename", "init.mp4",
            "-hls_segment_filename", slashes(outDir) + "/%v/seg_%05d.m4s",
            "-master_pl_name", "master.m3u8",
            "-var_stream_map", streamMap.toString(),
            "-progress", "pipe:1",
            slashes(outDir) + "/%v/index.m3u8"));
        return cmd;
    }

    /** FFmpeg copies the output path's separators into playlist URIs, so Windows backslashes must not reach it. */
    private static String slashes(Path dir) {
        return dir.toAbsolutePath().toString().replace('\\', '/');
    }

    /** Total of the {@code #EXTINF} segment durations in a media playlist. */
    public static long playlistDurationMs(Path mediaPlaylist) throws IOException {
        double seconds = 0;
        for (String line : Files.readAllLines(mediaPlaylist)) {
            if (line.startsWith("#EXTINF:")) {
                int comma = line.indexOf(',');
                seconds += Double.parseDouble(line.substring(8, comma < 0 ? line.length() : comma));
            }
        }
        return Math.round(seconds * 1000);
    }

    /** Generous upper bound: 10 minutes or 8x real time, whichever is longer; 6 hours if the length is unknown. */
    private static Duration timeout(ProbeResult probe) {
        if (probe.getDurationMs() <= 0) {
            return Duration.ofHours(6);
        }
        return Duration.ofMillis(Math.max(Duration.ofMinutes(10).toMillis(), probe.getDurationMs() * 8));
    }

    private static String lastLine(String s) {
        String trimmed = s.strip();
        int nl = trimmed.lastIndexOf('\n');
        return nl < 0 ? trimmed : trimmed.substring(nl + 1);
    }
}
