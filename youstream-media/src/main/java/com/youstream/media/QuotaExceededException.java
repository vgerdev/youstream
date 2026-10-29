package com.youstream.media;

/** The upload would take the user over their storage quota. */
public class QuotaExceededException extends RuntimeException {

    public QuotaExceededException(long usedBytes, long quotaBytes) {
        super("storage quota exceeded: " + (usedBytes >> 20) + " MB of " + (quotaBytes >> 20) + " MB used");
    }
}
