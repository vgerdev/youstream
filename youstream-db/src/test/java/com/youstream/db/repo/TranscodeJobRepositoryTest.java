package com.youstream.db.repo;

import com.youstream.common.model.JobStatus;
import com.youstream.common.model.TranscodeJob;
import com.youstream.common.model.User;
import com.youstream.db.Db;
import com.youstream.db.TestDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

class TranscodeJobRepositoryTest {

    private Db db;
    private TranscodeJobRepository jobs;
    private User owner;

    @BeforeEach
    void setUp() {
        db = TestDatabase.db();
        TestDatabase.truncate();
        jobs = new TranscodeJobRepository(db);
        owner = Fixtures.user(db, "owner");
    }

    @Test
    void claimsOldestJobOnce() {
        TranscodeJob first = jobs.enqueue(Fixtures.video(db, owner, "a").getId());
        TranscodeJob second = jobs.enqueue(Fixtures.video(db, owner, "b").getId());

        TranscodeJob claimed = jobs.claimNext("w1").orElseThrow();
        assertThat(claimed.getId()).isEqualTo(first.getId());
        assertThat(claimed.getStatus()).isEqualTo(JobStatus.RUNNING);
        assertThat(claimed.getAttempts()).isEqualTo(1);
        assertThat(claimed.getLockedBy()).isEqualTo("w1");

        assertThat(jobs.claimNext("w2")).map(TranscodeJob::getId).contains(second.getId());
        assertThat(jobs.claimNext("w3")).isEmpty();
    }

    @Test
    void concurrentWorkersNeverClaimTheSameJob() throws Exception {
        for (int i = 0; i < 6; i++) {
            jobs.enqueue(Fixtures.video(db, owner, "v" + i).getId());
        }
        List<Callable<Optional<TranscodeJob>>> workers = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            String id = "w" + i;
            workers.add(() -> jobs.claimNext(id));
        }

        List<Long> claimed = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(4)) {
            for (int round = 0; round < 2; round++) {
                for (Future<Optional<TranscodeJob>> f : pool.invokeAll(workers)) {
                    f.get().ifPresent(j -> claimed.add(j.getId()));
                }
            }
        }
        assertThat(claimed).hasSize(6).doesNotHaveDuplicates();
    }

    @Test
    void failedJobIsRetriedUntilAttemptsRunOut() {
        TranscodeJob job = jobs.enqueue(Fixtures.video(db, owner, "a").getId());
        Instant past = Instant.now().minusSeconds(1);

        for (int attempt = 1; attempt <= 2; attempt++) {
            jobs.claimNext("w").orElseThrow();
            assertThat(jobs.fail(job.getId(), "boom " + attempt, past)).isEqualTo(JobStatus.QUEUED);
        }
        jobs.claimNext("w").orElseThrow();
        assertThat(jobs.fail(job.getId(), "boom 3", past)).isEqualTo(JobStatus.FAILED);
        assertThat(jobs.claimNext("w")).isEmpty();

        TranscodeJob failed = jobs.findLatestForVideo(job.getVideoId()).orElseThrow();
        assertThat(failed.getAttempts()).isEqualTo(3);
        assertThat(failed.getError()).isEqualTo("boom 3");
        assertThat(failed.getFinishedAt()).isNotNull();
    }

    @Test
    void retryWaitsUntilRunAfter() {
        TranscodeJob job = jobs.enqueue(Fixtures.video(db, owner, "a").getId());
        jobs.claimNext("w").orElseThrow();
        jobs.fail(job.getId(), "transient", Instant.now().plusSeconds(3600));

        assertThat(jobs.claimNext("w")).isEmpty();
    }

    @Test
    void progressCompletionReleaseAndStaleRequeue() {
        TranscodeJob job = jobs.enqueue(Fixtures.video(db, owner, "a").getId());
        jobs.claimNext("w1").orElseThrow();
        jobs.updateProgress(job.getId(), 0.5f);
        assertThat(jobs.findLatestForVideo(job.getVideoId()).orElseThrow().getProgress()).isEqualTo(0.5f);

        assertThat(jobs.release("w1")).isEqualTo(1);
        TranscodeJob released = jobs.findLatestForVideo(job.getVideoId()).orElseThrow();
        assertThat(released.getStatus()).isEqualTo(JobStatus.QUEUED);
        assertThat(released.getAttempts()).isZero();

        jobs.claimNext("w2").orElseThrow();
        assertThat(jobs.requeueStale(Instant.now().minusSeconds(3600))).isZero();
        assertThat(jobs.requeueStale(Instant.now().plusSeconds(60))).isEqualTo(1);

        jobs.claimNext("w3").orElseThrow();
        jobs.complete(job.getId());
        TranscodeJob done = jobs.findLatestForVideo(job.getVideoId()).orElseThrow();
        assertThat(done.getStatus()).isEqualTo(JobStatus.DONE);
        assertThat(done.getProgress()).isEqualTo(1f);
    }
}
