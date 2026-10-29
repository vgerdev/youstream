package com.youstream.db.repo;

import com.jcabi.jdbc.ListOutcome;
import com.jcabi.jdbc.Outcome;
import com.youstream.common.model.JobStatus;
import com.youstream.common.model.TranscodeJob;
import com.youstream.db.Db;
import com.youstream.db.Rows;
import lombok.RequiredArgsConstructor;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;

/** Database-backed job queue; safe for several workers and JVMs via FOR UPDATE SKIP LOCKED. */
@RequiredArgsConstructor
public class TranscodeJobRepository {

    private static final String COLUMNS = "id, video_id, status, progress, attempts, max_attempts, locked_by, locked_at,"
        + " error, run_after, created_at, finished_at";

    private final Db db;

    public TranscodeJob enqueue(long videoId) {
        return db.run(s -> s
            .sql("INSERT INTO transcode_jobs (video_id) VALUES (?) RETURNING " + COLUMNS)
            .set(videoId)
            .select(new ListOutcome<>(TranscodeJobRepository::map))
            .getFirst());
    }

    /** Atomically takes the oldest runnable job, or empty if none. */
    public Optional<TranscodeJob> claimNext(String workerId) {
        return db.run(s -> s
            .sql("UPDATE transcode_jobs SET status = 'RUNNING', locked_by = ?, locked_at = now(), attempts = attempts + 1,"
                + " progress = 0, error = NULL"
                + " WHERE id = (SELECT id FROM transcode_jobs WHERE status = 'QUEUED' AND run_after <= now()"
                + "             ORDER BY run_after, id FOR UPDATE SKIP LOCKED LIMIT 1)"
                + " RETURNING " + COLUMNS)
            .set(workerId)
            .select(new ListOutcome<>(TranscodeJobRepository::map))
            .stream().findFirst());
    }

    public void updateProgress(long jobId, float progress) {
        db.run(s -> s
            .sql("UPDATE transcode_jobs SET progress = ? WHERE id = ? AND status = 'RUNNING'")
            .set(Math.max(0f, Math.min(1f, progress)))
            .set(jobId)
            .update(Outcome.VOID));
    }

    public void complete(long jobId) {
        db.run(s -> s
            .sql("UPDATE transcode_jobs SET status = 'DONE', progress = 1, finished_at = now(), locked_by = NULL,"
                + " locked_at = NULL WHERE id = ?")
            .set(jobId)
            .update(Outcome.VOID));
    }

    /**
     * Records a failure: requeues the job at {@code retryAt} while attempts remain, otherwise marks it FAILED.
     *
     * @return the resulting status
     */
    public JobStatus fail(long jobId, String error, Instant retryAt) {
        return db.run(s -> s
            .sql("UPDATE transcode_jobs SET error = ?, locked_by = NULL, locked_at = NULL,"
                + " status = CASE WHEN attempts < max_attempts THEN 'QUEUED' ELSE 'FAILED' END,"
                + " run_after = CASE WHEN attempts < max_attempts THEN ? ELSE run_after END,"
                + " finished_at = CASE WHEN attempts < max_attempts THEN NULL ELSE now() END"
                + " WHERE id = ? RETURNING status")
            .set(error)
            .set(Rows.ts(retryAt))
            .set(jobId)
            .select(new ListOutcome<>(rs -> JobStatus.valueOf(rs.getString(1))))
            .getFirst());
    }

    /** Marks the job FAILED without retrying (the input can never succeed). */
    public void failPermanently(long jobId, String error) {
        db.run(s -> s
            .sql("UPDATE transcode_jobs SET status = 'FAILED', error = ?, locked_by = NULL, locked_at = NULL,"
                + " finished_at = now() WHERE id = ?")
            .set(error)
            .set(jobId)
            .update(Outcome.VOID));
    }

    /** Puts RUNNING jobs whose lock is older than {@code lockedBefore} back in the queue (worker crashed). */
    public int requeueStale(Instant lockedBefore) {
        return db.run(s -> s
            .sql("UPDATE transcode_jobs SET status = 'QUEUED', locked_by = NULL, locked_at = NULL, run_after = now()"
                + " WHERE status = 'RUNNING' AND locked_at < ?")
            .set(Rows.ts(lockedBefore))
            .update(Outcome.UPDATE_COUNT));
    }

    /**
     * Requeues RUNNING jobs whose worker id starts with {@code prefix}: on startup, jobs a previous process on
     * this host left behind when it crashed.
     */
    public int requeueByWorkerPrefix(String prefix) {
        return db.run(s -> s
            .sql("UPDATE transcode_jobs SET status = 'QUEUED', locked_by = NULL, locked_at = NULL, run_after = now()"
                + " WHERE status = 'RUNNING' AND starts_with(locked_by, ?)")
            .set(prefix)
            .update(Outcome.UPDATE_COUNT));
    }

    /** Puts this worker's RUNNING jobs back in the queue (graceful shutdown). */
    public int release(String workerId) {
        return db.run(s -> s
            .sql("UPDATE transcode_jobs SET status = 'QUEUED', locked_by = NULL, locked_at = NULL, attempts = attempts - 1,"
                + " run_after = now() WHERE status = 'RUNNING' AND locked_by = ?")
            .set(workerId)
            .update(Outcome.UPDATE_COUNT));
    }

    public Optional<TranscodeJob> findLatestForVideo(long videoId) {
        return db.run(s -> s
            .sql("SELECT " + COLUMNS + " FROM transcode_jobs WHERE video_id = ? ORDER BY id DESC LIMIT 1")
            .set(videoId)
            .select(new ListOutcome<>(TranscodeJobRepository::map))
            .stream().findFirst());
    }

    static TranscodeJob map(ResultSet rs) throws SQLException {
        return TranscodeJob.builder()
            .id(rs.getLong("id"))
            .videoId(rs.getLong("video_id"))
            .status(Rows.enumValue(rs, "status", JobStatus.class))
            .progress(rs.getFloat("progress"))
            .attempts(rs.getInt("attempts"))
            .maxAttempts(rs.getInt("max_attempts"))
            .lockedBy(rs.getString("locked_by"))
            .lockedAt(Rows.instant(rs, "locked_at"))
            .error(rs.getString("error"))
            .runAfter(Rows.instant(rs, "run_after"))
            .createdAt(Rows.instant(rs, "created_at"))
            .finishedAt(Rows.instant(rs, "finished_at"))
            .build();
    }
}
