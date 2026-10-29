package com.youstream.common;

import java.security.SecureRandom;

/** Random 11-character base62 ids used in public URLs for videos and playlists. */
public final class PublicIds {

    public static final int LENGTH = 11;

    private static final char[] ALPHABET =
        "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private PublicIds() {
    }

    public static String next() {
        char[] id = new char[LENGTH];
        for (int i = 0; i < LENGTH; i++) {
            id[i] = ALPHABET[RANDOM.nextInt(ALPHABET.length)];
        }
        return new String(id);
    }

    public static boolean isValid(String id) {
        return id != null && id.length() == LENGTH && id.chars().allMatch(c ->
            (c >= '0' && c <= '9') || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z'));
    }
}
