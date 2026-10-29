package com.youstream.api.auth;

import com.youstream.api.AppServices;
import com.youstream.api.dto.SshKeyDtos;
import com.youstream.auth.token.AccessClaims;
import com.youstream.common.model.IdentityProvider;
import com.youstream.common.model.User;
import com.youstream.common.model.UserIdentity;
import io.jooby.Context;
import io.jooby.Jooby;
import io.jooby.StatusCode;
import io.jooby.exception.StatusCodeException;

import java.util.List;

/** SSH keys for SFTP uploads and the connection details shown on the Account page. */
public final class SshKeyRoutes {

    /** Where the SFTP server can be reached; built by youstream-server. */
    public record SftpEndpoint(boolean enabled, String host, int port, String hostKeyFingerprint, long maxFileBytes) {
        public static SftpEndpoint disabled() {
            return new SftpEndpoint(false, null, 0, null, 0);
        }
    }

    private final AppServices s;

    public SshKeyRoutes(AppServices services) {
        this.s = services;
    }

    public void install(Jooby app) {
        app.get("/api/me/ssh-keys", ctx -> s.getSshKeys().list(CurrentUser.require(ctx).getUserId())
            .stream().map(SshKeyDtos.Key::of).toList());
        app.post("/api/me/ssh-keys", this::add);
        app.delete("/api/me/ssh-keys/{id}", this::delete);
        app.get("/api/me/sftp", this::sftp);
    }

    private SshKeyDtos.Key add(Context ctx) {
        AccessClaims caller = CurrentUser.require(ctx);
        SshKeyDtos.Add req = ctx.body(SshKeyDtos.Add.class);
        ctx.setResponseCode(StatusCode.CREATED);
        return SshKeyDtos.Key.of(s.getSshKeys().add(caller.getUserId(), req.getName(), req.getPublicKey()));
    }

    private Context delete(Context ctx) {
        AccessClaims caller = CurrentUser.require(ctx);
        long id;
        try {
            id = Long.parseLong(ctx.path("id").value());
        } catch (NumberFormatException e) {
            throw new StatusCodeException(StatusCode.NOT_FOUND, "key not found");
        }
        if (!s.getSshKeys().delete(caller.getUserId(), id)) {
            throw new StatusCodeException(StatusCode.NOT_FOUND, "key not found");
        }
        return ctx.send(StatusCode.NO_CONTENT);
    }

    private SshKeyDtos.SftpInfo sftp(Context ctx) {
        AccessClaims caller = CurrentUser.require(ctx);
        User user = s.getUsers().findById(caller.getUserId())
            .orElseThrow(() -> new StatusCodeException(StatusCode.UNAUTHORIZED, "user no longer exists"));
        List<UserIdentity> identities = s.getUsers().repository().listIdentities(user.getId());
        boolean ldapLogin = s.getLdap() != null && identities.stream().anyMatch(i -> i.getProvider() == IdentityProvider.LDAP);
        SftpEndpoint e = s.getSftp();
        return new SshKeyDtos.SftpInfo(e.enabled(), e.host(), e.port(), user.getUsername(), ldapLogin,
            e.hostKeyFingerprint(), e.maxFileBytes());
    }
}
