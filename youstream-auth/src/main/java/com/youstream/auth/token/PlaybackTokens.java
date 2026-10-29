package com.youstream.auth.token;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;

/**
 * Short-lived signed tokens for media URLs ({@code /media/{publicId}/{token}/master.m3u8}). The token is a path
 * segment, so the relative segment URLs inside HLS playlists inherit it and players need no custom headers.
 * Public videos use the constant token {@link #PUBLIC}.
 */
public class PlaybackTokens {

    public static final String PUBLIC = "pub";

    private final SecretKeySpec key;
    private final Duration ttl;
    private final Clock clock;

    public PlaybackTokens(String secret, Duration ttl, Clock clock) {
        try {
            // separate key from the JWT key, derived from the same secret
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            this.key = new SecretKeySpec(mac.doFinal("youstream-playback".getBytes(StandardCharsets.UTF_8)), "HmacSHA256");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
        this.ttl = ttl;
        this.clock = clock;
    }

    public String issue(String publicId) {
        long expires = clock.instant().plus(ttl).getEpochSecond();
        return Long.toString(expires, 36) + "." + sign(publicId, expires);
    }

    public boolean verify(String publicId, String token) {
        if (token == null) {
            return false;
        }
        int dot = token.indexOf('.');
        if (dot <= 0 || dot > 12) {
            return false;
        }
        long expires;
        try {
            expires = Long.parseLong(token.substring(0, dot), 36);
        } catch (NumberFormatException e) {
            return false;
        }
        if (expires < clock.instant().getEpochSecond()) {
            return false;
        }
        byte[] expected = sign(publicId, expires).getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, token.substring(dot + 1).getBytes(StandardCharsets.US_ASCII));
    }

    private String sign(String publicId, long expires) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            byte[] sig = mac.doFinal((publicId + "|" + expires).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(sig).substring(0, 22);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
