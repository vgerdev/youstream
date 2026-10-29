package com.youstream.common.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/** A resumable browser upload in progress. */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class UploadSession {
    private UUID id;
    private Long userId;
    private String filename;
    private long totalBytes;
    private long receivedBytes;
    private Instant createdAt;
    private Instant expiresAt;
}
