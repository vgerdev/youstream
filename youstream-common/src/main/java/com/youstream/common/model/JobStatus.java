package com.youstream.common.model;

/** State of a transcode job. */
public enum JobStatus {
    QUEUED,
    RUNNING,
    DONE,
    FAILED
}
