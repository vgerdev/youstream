package com.youstream.api;

import com.youstream.api.auth.LoginThrottle;
import com.youstream.auth.UserService;
import com.youstream.auth.entra.EntraOidcService;
import com.youstream.auth.ldap.LdapAuthenticator;
import com.youstream.auth.token.PlaybackTokens;
import com.youstream.auth.token.RefreshTokenService;
import com.youstream.auth.token.TokenService;
import com.youstream.common.storage.StorageService;
import com.youstream.db.Db;
import com.youstream.media.IngestService;
import lombok.Builder;
import lombok.Value;

import java.util.function.BooleanSupplier;

/** Services the HTTP layer depends on; built by youstream-server. */
@Value
@Builder
public class AppServices {
    @Builder.Default
    BooleanSupplier databaseHealthy = () -> false;

    Db db;
    StorageService storage;
    IngestService ingest;

    /** Null when LDAP login is disabled. */
    LdapAuthenticator ldap;
    /** Null when "Sign in with Microsoft" is disabled. */
    EntraOidcService entra;

    UserService users;
    TokenService tokens;
    RefreshTokenService refreshTokens;
    PlaybackTokens playbackTokens;

    @Builder.Default
    LoginThrottle loginThrottle = new LoginThrottle();
    /** Mark auth cookies Secure (browsers accept Secure cookies on http://localhost). */
    @Builder.Default
    boolean secureCookies = true;
    /** Origin the browser is sent back to after Microsoft sign-in; empty = same origin. */
    @Builder.Default
    String frontendUrl = "";

    @Builder.Default
    long maxUploadBytes = 10L << 30;
    @Builder.Default
    long uploadChunkBytes = 16L << 20;
}
