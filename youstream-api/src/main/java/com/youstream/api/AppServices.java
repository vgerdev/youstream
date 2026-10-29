package com.youstream.api;

import com.youstream.api.auth.LoginThrottle;
import com.youstream.auth.UserService;
import com.youstream.auth.entra.EntraOidcService;
import com.youstream.auth.ldap.LdapAuthenticator;
import com.youstream.auth.token.RefreshTokenService;
import com.youstream.auth.token.TokenService;
import lombok.Builder;
import lombok.Value;

import java.util.function.BooleanSupplier;

/** Services the HTTP layer depends on; built by youstream-server. */
@Value
@Builder
public class AppServices {
    @Builder.Default
    BooleanSupplier databaseHealthy = () -> false;

    /** Null when LDAP login is disabled. */
    LdapAuthenticator ldap;
    /** Null when "Sign in with Microsoft" is disabled. */
    EntraOidcService entra;

    UserService users;
    TokenService tokens;
    RefreshTokenService refreshTokens;

    @Builder.Default
    LoginThrottle loginThrottle = new LoginThrottle();
    /** Mark auth cookies Secure (browsers accept Secure cookies on http://localhost). */
    @Builder.Default
    boolean secureCookies = true;
    /** Origin the browser is sent back to after Microsoft sign-in; empty = same origin. */
    @Builder.Default
    String frontendUrl = "";
}
