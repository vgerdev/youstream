package com.youstream.media;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/** Inspects media files with ffprobe; the file content decides whether it is a video, never its name. */
public class Ffprobe {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final String executable;
    private final ProcessRunner runner;

    public Ffprobe(String executable, ProcessRunner runner) {
        this.executable = executable;
        this.runner = runner;
    }

    /** @throws InvalidMediaException if the file has no decodable video stream */
    public ProbeResult probe(Path file) throws IOException, InterruptedException {
        ProcessRunner.Result result = runner.run(List.of(executable, "-v", "error", "-print_format", "json",
            "-show_format", "-show_streams", file.toAbsolutePath().toString()), Duration.ofMinutes(2));
        if (result.exitCode() != 0) {
            throw new InvalidMediaException("not a readable media file: " + firstLine(result.stderrTail()));
        }
        return parse(result.stdout());
    }

    static ProbeResult parse(String json) {
        JsonNode root;
        try {
            root = JSON.readTree(json);
        } catch (JacksonException e) {
            throw new InvalidMediaException("unexpected ffprobe output", e);
        }
        JsonNode video = null;
        boolean audio = false;
        for (JsonNode stream : root.path("streams")) {
            String type = stream.path("codec_type").asString("");
            // attached pictures (cover art) are "video" streams too
            boolean coverArt = stream.path("disposition").path("attached_pic").asInt(0) == 1;
            if (type.equals("video") && !coverArt && video == null) {
                video = stream;
            } else if (type.equals("audio")) {
                audio = true;
            }
        }
        if (video == null || video.path("width").asInt(0) <= 0 || video.path("height").asInt(0) <= 0) {
            throw new InvalidMediaException("file has no video stream");
        }
        double seconds = root.path("format").path("duration").asDouble(0);
        if (seconds <= 0) {
            seconds = video.path("duration").asDouble(0);
        }
        // browser/screen recordings (MediaRecorder WebM) often carry no duration; 0 = unknown, measured after transcoding
        int width = video.path("width").asInt();
        int height = video.path("height").asInt();
        if (Math.abs(rotation(video)) % 180 == 90) {
            int t = width;
            width = height;
            height = t;
        }
        return ProbeResult.builder()
            .durationMs(Math.round(seconds * 1000))
            .width(width)
            .height(height)
            .audio(audio)
            .videoCodec(video.path("codec_name").asString(""))
            .format(root.path("format").path("format_name").asString(""))
            .build();
    }

    /** Phone videos store rotation as display-matrix side data (or the legacy "rotate" tag). */
    private static int rotation(JsonNode video) {
        for (JsonNode side : video.path("side_data_list")) {
            if (side.has("rotation")) {
                return side.path("rotation").asInt(0);
            }
        }
        return video.path("tags").path("rotate").asInt(0);
    }

    private static String firstLine(String s) {
        int nl = s.indexOf('\n');
        return nl < 0 ? s : s.substring(0, nl);
    }
}
