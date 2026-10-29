package com.youstream.auth.entra;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.oauth2.sdk.AuthorizationCode;
import com.nimbusds.oauth2.sdk.AuthorizationCodeGrant;
import com.nimbusds.oauth2.sdk.ErrorObject;
import com.nimbusds.oauth2.sdk.ParseException;
import com.nimbusds.oauth2.sdk.ResponseType;
import com.nimbusds.oauth2.sdk.Scope;
import com.nimbusds.oauth2.sdk.TokenRequest;
import com.nimbusds.oauth2.sdk.TokenResponse;
import com.nimbusds.oauth2.sdk.auth.ClientSecretBasic;
import com.nimbusds.oauth2.sdk.auth.Secret;
import com.nimbusds.oauth2.sdk.id.ClientID;
import com.nimbusds.oauth2.sdk.id.Issuer;
import com.nimbusds.oauth2.sdk.id.State;
import com.nimbusds.oauth2.sdk.pkce.CodeChallengeMethod;
import com.nimbusds.oauth2.sdk.pkce.CodeVerifier;
import com.nimbusds.openid.connect.sdk.AuthenticationRequest;
import com.nimbusds.openid.connect.sdk.Nonce;
import com.nimbusds.openid.connect.sdk.OIDCTokenResponse;
import com.nimbusds.openid.connect.sdk.OIDCTokenResponseParser;
import com.nimbusds.openid.connect.sdk.claims.IDTokenClaimsSet;
import com.nimbusds.openid.connect.sdk.validators.IDTokenValidator;
import com.youstream.auth.AuthException;
import com.youstream.auth.AuthException.Reason;
import com.youstream.auth.ExternalIdentity;
import com.youstream.auth.token.LoginState;
import com.youstream.common.model.IdentityProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * "Sign in with Microsoft": OpenID Connect authorization code flow with PKCE against a single Entra ID tenant.
 * The Entra tokens never leave the server; only the validated ID token claims are used.
 */
public class EntraOidcService {

    public record Authorization(URI uri, LoginState state) {
    }

    private static final Logger log = LoggerFactory.getLogger(EntraOidcService.class);

    private final EntraConfig config;
    private final ClientID clientId;
    private final URI redirectUri;
    private final URI authorizeEndpoint;
    private final URI tokenEndpoint;
    private final IDTokenValidator validator;

    public EntraOidcService(EntraConfig config) {
        if (isBlank(config.getTenantId()) || isBlank(config.getClientId()) || isBlank(config.getClientSecret())
            || isBlank(config.getRedirectUri())) {
            throw new IllegalArgumentException("entra.tenantId, clientId, clientSecret and redirectUri are required");
        }
        this.config = config;
        this.clientId = new ClientID(config.getClientId());
        this.redirectUri = URI.create(config.getRedirectUri());
        String tenantBase = config.getAuthority().replaceAll("/+$", "") + "/" + config.getTenantId();
        this.authorizeEndpoint = URI.create(tenantBase + "/oauth2/v2.0/authorize");
        this.tokenEndpoint = URI.create(tenantBase + "/oauth2/v2.0/token");
        try {
            URL jwks = URI.create(tenantBase + "/discovery/v2.0/keys").toURL();
            this.validator = new IDTokenValidator(new Issuer(tenantBase + "/v2.0"), clientId, JWSAlgorithm.RS256, jwks);
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException("invalid entra authority " + config.getAuthority(), e);
        }
    }

    /** Builds the Microsoft sign-in URL; the returned state must be kept (signed cookie) until the callback. */
    public Authorization begin(String returnTo, Long linkUserId) {
        State state = new State();
        Nonce nonce = new Nonce();
        CodeVerifier verifier = new CodeVerifier();
        URI uri = new AuthenticationRequest.Builder(ResponseType.CODE, Scope.parse(config.getScopes()), clientId, redirectUri)
            .endpointURI(authorizeEndpoint)
            .state(state)
            .nonce(nonce)
            .codeChallenge(verifier, CodeChallengeMethod.S256)
            .build()
            .toURI();
        return new Authorization(uri, LoginState.builder()
            .state(state.getValue())
            .nonce(nonce.getValue())
            .codeVerifier(verifier.getValue())
            .returnTo(returnTo)
            .linkUserId(linkUserId)
            .build());
    }

    /**
     * Handles the redirect back from Microsoft: checks state, redeems the code and validates the ID token.
     *
     * @throws AuthException INVALID_CREDENTIALS for any protocol or validation failure, UNAVAILABLE if Entra is unreachable
     */
    public ExternalIdentity complete(String code, String returnedState, LoginState expected) {
        if (isBlank(code) || returnedState == null || expected == null || !constantTimeEquals(returnedState, expected.getState())) {
            throw new AuthException(Reason.INVALID_CREDENTIALS, "invalid sign-in state");
        }
        OIDCTokenResponse tokens = redeem(code, expected.getCodeVerifier());
        IDTokenClaimsSet claims;
        try {
            claims = validator.validate(tokens.getOIDCTokens().getIDToken(), new Nonce(expected.getNonce()));
        } catch (BadJOSEException | JOSEException e) {
            log.warn("Rejected Entra ID token: {}", e.getMessage());
            throw new AuthException(Reason.INVALID_CREDENTIALS, "invalid ID token", e);
        }
        return toIdentity(claims);
    }

    private OIDCTokenResponse redeem(String code, String codeVerifier) {
        TokenRequest request = new TokenRequest(tokenEndpoint,
            new ClientSecretBasic(clientId, new Secret(config.getClientSecret())),
            new AuthorizationCodeGrant(new AuthorizationCode(code), redirectUri, new CodeVerifier(codeVerifier)));
        TokenResponse response;
        try {
            response = OIDCTokenResponseParser.parse(request.toHTTPRequest().send());
        } catch (IOException e) {
            throw new AuthException(Reason.UNAVAILABLE, "Microsoft sign-in is unavailable", e);
        } catch (ParseException e) {
            throw new AuthException(Reason.INVALID_CREDENTIALS, "unexpected token response", e);
        }
        if (!response.indicatesSuccess()) {
            ErrorObject error = response.toErrorResponse().getErrorObject();
            log.warn("Entra token request failed: {} {}", error.getCode(), error.getDescription());
            throw new AuthException(Reason.INVALID_CREDENTIALS, "Microsoft rejected the sign-in");
        }
        return (OIDCTokenResponse) response.toSuccessResponse();
    }

    private ExternalIdentity toIdentity(IDTokenClaimsSet claims) {
        String oid = claims.getStringClaim("oid");
        String tid = claims.getStringClaim("tid");
        if (isBlank(oid) || (tid != null && !tid.equalsIgnoreCase(config.getTenantId()))) {
            throw new AuthException(Reason.INVALID_CREDENTIALS, "ID token is missing oid or is from another tenant");
        }
        String preferred = claims.getStringClaim("preferred_username");
        String email = claims.getStringClaim("email");
        String name = claims.getStringClaim("name");
        List<String> roles = claims.getStringListClaim("roles");
        return ExternalIdentity.builder()
            .provider(IdentityProvider.ENTRA)
            .subject(oid)
            .username(preferred != null ? preferred : email)
            .displayName(name)
            .email(email != null ? email : (preferred != null && preferred.contains("@") ? preferred : null))
            .admin(roles != null && roles.contains(config.getAdminRole()))
            .build();
    }

    private static boolean constantTimeEquals(String a, String b) {
        return b != null && MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
