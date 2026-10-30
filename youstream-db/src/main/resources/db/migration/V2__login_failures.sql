-- Failed password logins (web and SFTP), shared by every server process so the throttle holds across replicas.
-- Keys combine client IP and username; rows older than a day are removed by the maintenance job.
CREATE TABLE login_failures (
    throttle_key text        NOT NULL,
    failed_at    timestamptz NOT NULL
);

CREATE INDEX login_failures_key_time ON login_failures (throttle_key, failed_at);
CREATE INDEX login_failures_time ON login_failures (failed_at);
