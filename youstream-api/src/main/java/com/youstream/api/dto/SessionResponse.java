package com.youstream.api.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** Returned by login and refresh; the refresh token itself travels only in an HttpOnly cookie. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SessionResponse {
    private String accessToken;
    private Instant expiresAt;
    private UserDto user;
}
