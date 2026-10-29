package com.youstream.common.model;

/** Lifecycle of an uploaded video. */
public enum VideoStatus {
    UPLOADING,
    UPLOADED,
    PROCESSING,
    READY,
    FAILED
}
