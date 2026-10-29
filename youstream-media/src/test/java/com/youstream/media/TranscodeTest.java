package com.youstream.media;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TranscodeTest {

    @TempDir
    Path dir;

    @Test
    void ladderNeverUpscales() {
        assertThat(Ladder.forSource(1920, 1080)).extracting(Ladder.Rung::height).containsExactly(1080, 720, 480, 360);
        assertThat(Ladder.forSource(1280, 720)).extracting(Ladder.Rung::name).containsExactly("720p", "480p", "360p");
        assertThat(Ladder.forSource(1280, 720).getFirst().width()).isEqualTo(1280);
        assertThat(Ladder.forSource(1080, 1920)).extracting(Ladder.Rung::height).containsExactly(1080, 720, 480, 360);
        assertThat(Ladder.forSource(320, 180)).singleElement()
            .satisfies(r -> assertThat(r.height()).isEqualTo(180));
    }

    @Test
    void parsesRotatedPhoneVideoAndIgnoresCoverArt() {
        ProbeResult r = Ffprobe.parse("""
            {"streams":[
              {"codec_type":"video","codec_name":"mjpeg","width":600,"height":600,"disposition":{"attached_pic":1}},
              {"codec_type":"video","codec_name":"hevc","width":1920,"height":1080,"side_data_list":[{"rotation":-90}]},
              {"codec_type":"audio","codec_name":"aac"}],
             "format":{"format_name":"mov,mp4","duration":"12.5"}}
            """);

        assertThat(r.getWidth()).isEqualTo(1080);
        assertThat(r.getHeight()).isEqualTo(1920);
        assertThat(r.getDurationMs()).isEqualTo(12_500);
        assertThat(r.isAudio()).isTrue();
        assertThat(r.getVideoCodec()).isEqualTo("hevc");
    }

    @Test
    void rejectsAudioOnlyAndGarbage() throws Exception {
        assertThatThrownBy(() -> Ffprobe.parse("{\"streams\":[{\"codec_type\":\"audio\"}],\"format\":{\"duration\":\"3\"}}"))
            .isInstanceOf(InvalidMediaException.class);

        MediaTestSupport.assumeFfmpeg();
        Path junk = Files.writeString(dir.resolve("notes.mp4"), "definitely not a video");
        Ffprobe ffprobe = new Ffprobe(MediaTestSupport.FFPROBE, new ProcessRunner());
        assertThatThrownBy(() -> ffprobe.probe(junk)).isInstanceOf(InvalidMediaException.class);
    }

    @Test
    void transcodesToMultiBitrateHlsWithPoster() throws Exception {
        MediaTestSupport.assumeFfmpeg();
        Path clip = MediaTestSupport.generateClip(dir.resolve("clip.mp4"), 854, 480, 5, true);
        ProcessRunner runner = new ProcessRunner();
        ProbeResult probe = new Ffprobe(MediaTestSupport.FFPROBE, runner).probe(clip);
        assertThat(probe.getHeight()).isEqualTo(480);
        assertThat(probe.isAudio()).isTrue();

        HlsTranscoder transcoder = new HlsTranscoder(MediaTestSupport.config(), runner);
        List<Double> progress = new ArrayList<>();
        Path out = dir.resolve("hls");
        List<Ladder.Rung> rungs = transcoder.transcode(clip, probe, out, progress::add);
        transcoder.poster(clip, probe, dir.resolve("thumbs/poster.jpg"));

        assertThat(rungs).extracting(Ladder.Rung::name).containsExactly("480p", "360p");
        String master = Files.readString(out.resolve("master.m3u8"));
        assertThat(master).contains("480p/index.m3u8", "360p/index.m3u8", "RESOLUTION=854x480");
        for (String rung : List.of("480p", "360p")) {
            String media = Files.readString(out.resolve(rung + "/index.m3u8"));
            assertThat(media).contains("seg_00000.m4s", "#EXT-X-ENDLIST").doesNotContain("\\");
            // every URI in the playlist (init segment, media segments) resolves to a file next to it
            java.util.regex.Matcher init = java.util.regex.Pattern.compile("#EXT-X-MAP:URI=\"([^\"]+)\"").matcher(media);
            assertThat(init.find()).isTrue();
            assertThat(out.resolve(rung).resolve(init.group(1))).isRegularFile();
            media.lines().filter(l -> l.endsWith(".m4s"))
                .forEach(seg -> assertThat(out.resolve(rung).resolve(seg)).isRegularFile());
        }
        assertThat(dir.resolve("thumbs/poster.jpg")).isRegularFile();
        assertThat(progress).isNotEmpty().allSatisfy(p -> assertThat(p).isBetween(0.0, 1.0));
    }

    @Test
    void handlesRecordingsWithoutDuration() throws Exception {
        MediaTestSupport.assumeFfmpeg();
        // "live" WebM has no duration in the header, like browser MediaRecorder output
        Path clip = dir.resolve("recording.webm");
        ProcessRunner runner = new ProcessRunner();
        assertThat(runner.run(List.of(MediaTestSupport.FFMPEG, "-hide_banner", "-loglevel", "error", "-y",
            "-f", "lavfi", "-i", "testsrc2=size=640x360:rate=30:duration=3", "-c:v", "libvpx", "-b:v", "1M",
            "-f", "webm", "-live", "1", clip.toString()), java.time.Duration.ofMinutes(1)).exitCode()).isZero();

        ProbeResult probe = new Ffprobe(MediaTestSupport.FFPROBE, runner).probe(clip);
        assertThat(probe.getDurationMs()).isZero();

        HlsTranscoder transcoder = new HlsTranscoder(MediaTestSupport.config(), runner);
        transcoder.transcode(clip, probe, dir.resolve("hls"), p -> { });
        transcoder.poster(clip, probe, dir.resolve("poster.jpg"));

        assertThat(HlsTranscoder.playlistDurationMs(dir.resolve("hls/360p/index.m3u8"))).isBetween(2_900L, 3_100L);
        assertThat(dir.resolve("poster.jpg")).isRegularFile();
    }

    @Test
    void transcodesSilentVideo() throws Exception {
        MediaTestSupport.assumeFfmpeg();
        Path clip = MediaTestSupport.generateClip(dir.resolve("silent.mp4"), 640, 360, 3, false);
        ProcessRunner runner = new ProcessRunner();
        ProbeResult probe = new Ffprobe(MediaTestSupport.FFPROBE, runner).probe(clip);

        new HlsTranscoder(MediaTestSupport.config(), runner).transcode(clip, probe, dir.resolve("hls"), p -> { });

        assertThat(probe.isAudio()).isFalse();
        assertThat(dir.resolve("hls/360p/index.m3u8")).isRegularFile();
    }
}
