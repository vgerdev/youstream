package com.youstream.server;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigValueType;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * What one process runs. A single local process runs everything; in Kubernetes each Deployment runs one role
 * ({@code roles = [web]} or {@code YOUSTREAM_ROLES=sftp}) and they share the database and the media volume.
 */
public enum Roles {
    /** HTTP API, single page app and media serving. */
    WEB,
    /** SFTP upload server. */
    SFTP,
    /** FFmpeg transcode workers. */
    TRANSCODE;

    /** Reads {@code roles}, either a list or a comma-separated string (from an environment variable). */
    static Set<Roles> from(Config conf) {
        List<String> names = conf.getValue("roles").valueType() == ConfigValueType.LIST
            ? conf.getStringList("roles")
            : Arrays.asList(conf.getString("roles").split(","));
        Set<Roles> roles = EnumSet.noneOf(Roles.class);
        for (String name : names) {
            if (!name.isBlank()) {
                try {
                    roles.add(valueOf(name.trim().toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException e) {
                    throw new IllegalStateException("Unknown role '" + name.trim() + "' in roles; use web, sftp, transcode");
                }
            }
        }
        if (roles.isEmpty()) {
            throw new IllegalStateException("roles is empty; use any of web, sftp, transcode");
        }
        return roles;
    }
}
