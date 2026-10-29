package com.youstream.media;

import com.youstream.common.model.JobStatus;
import com.youstream.common.model.TranscodeJob;
import com.youstream.common.model.Video;
import com.youstream.common.model.VideoRendition;
import com.youstream.common.model.VideoStatus;
import com.youstream.common.storage.StorageService;
import com.youstream.db.Db;
import com.youstream.db.repo.TranscodeJobRepository;
import com.youstream.db.repo.VideoRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Background workers that take jobs from the database queue and transcode them. Output is written to
 * {@code hls/{publicId}.tmp} and moved into place only when complete, so players never see partial renditions.
 */
public class TranscodeWorkerPool implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(TranscodeWorkerPool.class);
    private static final Duration PROGRESS_EVERY = Duration.ofSeconds(2);

    private final StorageService storage;
    private final Ffprobe ffprobe;
    private final HlsTranscoder transcoder;
    private final ProcessRunner runner;
    private final MediaConfig config;
    private final Clock clock;
    private final VideoRepository videos;
    private final TranscodeJobRepository jobs;
    private final String workerPrefix;
    private final List<Thread> threads = new ArrayList<>();
    private volatile boolean closing;

    public TranscodeWorkerPool(Db db, StorageService storage, MediaConfig config, ProcessRunner runner, Clock clock) {
        this.storage = storage;
        this.config = config;
        this.runner = runner;
        this.clock = clock;
        this.ffprobe = new Ffprobe(config.getFfprobe(), runner);
        this.transcoder = new HlsTranscoder(config, runner);
        this.videos = new VideoRepository(db);
        this.jobs = new TranscodeJobRepository(db);
        this.workerPrefix = hostname() + ":";
    }

    /** Requeues jobs abandoned by a crashed process on this host, then starts the worker threads. */
    public void start() {
        int abandoned = jobs.requeueByWorkerPrefix(workerPrefix);
        if (abandoned > 0) {
            log.warn("Requeued {} transcode job(s) left running by a previous process", abandoned);
        }
        String id = workerPrefix + ProcessHandle.current().pid();
        for (int i = 0; i < config.getWorkers(); i++) {
            String workerId = id + ":" + i;
            threads.add(Thread.ofPlatform().name("transcode-" + i).daemon(true).start(() -> loop(workerId)));
        }
        log.info("Started {} transcode worker(s) using {}", config.getWorkers(), config.getFfmpeg());
    }

    private void loop(String workerId) {
        Instant lastStaleCheck = Instant.EPOCH;
        while (!closing) {
            try {
                if (Duration.between(lastStaleCheck, clock.instant()).toMinutes() >= 10) {
                    jobs.requeueStale(clock.instant().minus(config.getStaleJobAfter()));
                    lastStaleCheck = clock.instant();
                }
                if (!runOnce(workerId)) {
                    Thread.sleep(config.getPollInterval().toMillis());
                }
            } catch (InterruptedException e) {
                return;
            } catch (RuntimeException e) {
                log.error("Transcode worker {} error", workerId, e);
                sleepQuietly();
            }
        }
    }

    /**
     * Claims and processes at most one job.
     *
     * @return false if the queue was empty
     */
    public boolean runOnce(String workerId) throws InterruptedException {
        Optional<TranscodeJob> claimed = jobs.claimNext(workerId);
        if (claimed.isEmpty()) {
            return false;
        }
        TranscodeJob job = claimed.get();
        Optional<Video> found = videos.findById(job.getVideoId());
        if (found.isEmpty()) {
            jobs.complete(job.getId());
            return true;
        }
        Video video = found.get();
        log.info("Transcoding {} (job {}, attempt {})", video.getPublicId(), job.getId(), job.getAttempts());
        try {
            transcode(job, video);
            jobs.complete(job.getId());
            log.info("Transcoded {}", video.getPublicId());
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            if (closing) {
                throw new InterruptedException("shutting down");
            }
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            if (message.length() > 1000) {
                message = message.substring(0, 1000);
            }
            JobStatus status;
            if (e instanceof InvalidMediaException) {
                // retrying will not help
                jobs.failPermanently(job.getId(), message);
                status = JobStatus.FAILED;
            } else {
                status = jobs.fail(job.getId(), message, clock.instant().plus(backoff(job.getAttempts())));
            }
            videos.updateStatus(video.getId(), status == JobStatus.FAILED ? VideoStatus.FAILED : VideoStatus.UPLOADED, message);
            log.warn("Transcoding {} failed ({}): {}", video.getPublicId(), status, message);
        }
        return true;
    }

    private void transcode(TranscodeJob job, Video video) throws Exception {
        videos.updateStatus(video.getId(), VideoStatus.PROCESSING, null);
        String pid = video.getPublicId();
        Path input = storage.localPath(video.getOriginalPath());
        ProbeResult probe = ffprobe.probe(input);

        String tmpKey = "hls/" + pid + ".tmp";
        storage.deleteTree(tmpKey);
        Instant[] lastUpdate = {Instant.EPOCH};
        List<Ladder.Rung> rungs = transcoder.transcode(input, probe, storage.localPath(tmpKey), p -> {
            Instant now = clock.instant();
            if (Duration.between(lastUpdate[0], now).compareTo(PROGRESS_EVERY) >= 0) {
                lastUpdate[0] = now;
                jobs.updateProgress(job.getId(), (float) (p * 0.95));
            }
        });
        if (probe.getDurationMs() <= 0) {
            // recordings without a duration header: measure it from the produced playlist
            probe = probe.toBuilder().durationMs(HlsTranscoder.playlistDurationMs(
                storage.localPath(tmpKey + "/" + rungs.getFirst().name() + "/index.m3u8"))).build();
        }
        long durationMs = probe.getDurationMs();
        transcoder.poster(input, probe, storage.localPath("thumbs/" + pid + "/poster.jpg"));
        storage.move(tmpKey, "hls/" + pid);

        List<VideoRendition> renditions = rungs.stream().map(r -> VideoRendition.builder()
            .height(r.height())
            .width(r.width())
            .bitrateKbps(r.videoKbps())
            .playlistPath("hls/" + pid + "/" + r.name() + "/index.m3u8")
            .build()).toList();
        videos.markReady(video.getId(), durationMs, probe.getWidth(), probe.getHeight(), renditions, clock.instant());
    }

    /** 30s, 2min, 8min, ... */
    private static Duration backoff(int attempt) {
        return Duration.ofSeconds(30L << Math.min(10, 2 * Math.max(0, attempt - 1)));
    }

    @Override
    public void close() {
        closing = true;
        threads.forEach(Thread::interrupt);
        runner.killAll();
        for (Thread t : threads) {
            try {
                t.join(10_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        for (int i = 0; i < threads.size(); i++) {
            jobs.release(workerPrefix + ProcessHandle.current().pid() + ":" + i);
        }
    }

    private static void sleepQuietly() {
        try {
            Thread.sleep(5_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String hostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "localhost";
        }
    }
}
