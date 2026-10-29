package com.youstream.media;

/** The uploaded file is not a playable video (no video stream, unreadable container, ...). */
public class InvalidMediaException extends RuntimeException {

    public InvalidMediaException(String message) {
        super(message);
    }

    public InvalidMediaException(String message, Throwable cause) {
        super(message, cause);
    }
}
