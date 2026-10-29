package com.youstream.api.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Request and response bodies of the resumable upload API. */
public final class UploadDtos {

    private UploadDtos() {
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Create {
        private String filename;
        private long size;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Status {
        private String id;
        private String filename;
        private long totalBytes;
        private long receivedBytes;
        private long chunkBytes;
    }
}
