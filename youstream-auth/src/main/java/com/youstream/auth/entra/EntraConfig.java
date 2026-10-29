package com.youstream.auth.entra;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** App registration settings for "Sign in with Microsoft" (single-tenant, confidential web client). */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class EntraConfig {
    private String tenantId;
    private String clientId;
    private String clientSecret;
    private String redirectUri;
    @Builder.Default
    private String scopes = "openid profile email";
    /** App role value that grants YouStream admin rights. */
    @Builder.Default
    private String adminRole = "YouStream.Admin";
    /** Overridable for tests. */
    @Builder.Default
    private String authority = "https://login.microsoftonline.com";
}
