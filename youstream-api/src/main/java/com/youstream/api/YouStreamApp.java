package com.youstream.api;

import com.youstream.api.auth.AuthFilter;
import com.youstream.api.auth.AuthRoutes;
import com.youstream.api.auth.SshKeyRoutes;
import com.youstream.auth.SshKeyService;
import com.youstream.api.video.MediaRoutes;
import com.youstream.api.video.PublicVideoCache;
import com.youstream.api.video.UploadRoutes;
import com.youstream.api.video.VideoRoutes;
import com.youstream.auth.AuthException;
import com.youstream.db.repo.VideoRepository;
import com.youstream.media.InvalidMediaException;
import io.jooby.Context;
import io.jooby.Jooby;
import io.jooby.MediaType;
import io.jooby.StatusCode;
import io.jooby.exception.StatusCodeException;
import io.jooby.handler.AssetHandler;
import io.jooby.handler.AssetSource;
import io.jooby.handler.CacheControl;
import io.jooby.jackson3.Jackson3Module;

import java.time.Duration;
import java.util.Map;

/**
 * HTTP application: JSON API under {@code /api}, and the SolidJS single page app for every other path.
 */
public class YouStreamApp extends Jooby {

    public YouStreamApp(AppServices services) {
        install(new Jackson3Module());

        error(this::handleError);

        if (services.getTokens() != null) {
            before(new AuthFilter(services.getTokens()));
            new AuthRoutes(services).install(this);
        }
        if (services.getSshKeys() != null) {
            new SshKeyRoutes(services).install(this);
        }
        if (services.getDb() != null) {
            PublicVideoCache publicVideos = new PublicVideoCache(new VideoRepository(services.getDb()));
            new VideoRoutes(services, publicVideos).install(this);
            new UploadRoutes(services).install(this);
            new MediaRoutes(services, publicVideos).install(this);
        }

        get("/api/health", ctx -> {
            boolean db = services.getDatabaseHealthy().getAsBoolean();
            ctx.setResponseCode(db ? StatusCode.OK : StatusCode.SERVICE_UNAVAILABLE);
            return Map.of("status", db ? "UP" : "DOWN", "database", db ? "UP" : "DOWN");
        });

        // unknown API paths must not fall through to the SPA
        get("/api/*", ctx -> {
            throw new StatusCodeException(StatusCode.NOT_FOUND, "no such endpoint");
        });

        // built frontend is packaged by youstream-web under classpath:public; client-side routes fall back to index.html
        assets("/*", new AssetHandler("index.html", AssetSource.create(getClassLoader(), "public"))
            .cacheControl(YouStreamApp::assetCaching));
    }

    /**
     * Vite's hashed /assets/* files never change, so cache them for a year; everything else (index.html) is always
     * re-fetched. Jar entries carry a fixed timestamp (reproducible builds), so Last-Modified/ETag would go stale.
     */
    static CacheControl assetCaching(String path) {
        CacheControl cache = new CacheControl().setETag(false).setLastModified(false);
        return path.contains("assets/") ? cache.setMaxAge(Duration.ofDays(365)) : cache.setNoCache();
    }

    /** JSON errors for the API: {@code {"error": "...", "message": "..."}}. */
    private void handleError(Context ctx, Throwable cause, StatusCode code) {
        StatusCode status = code;
        String error;
        String message = cause.getMessage();
        if (cause instanceof AuthException auth) {
            status = switch (auth.getReason()) {
                case INVALID_CREDENTIALS, INVALID_TOKEN -> StatusCode.UNAUTHORIZED;
                case FORBIDDEN -> StatusCode.FORBIDDEN;
                case CONFLICT -> StatusCode.CONFLICT;
                case UNAVAILABLE -> StatusCode.SERVICE_UNAVAILABLE;
            };
            error = auth.getReason().name().toLowerCase();
        } else if (cause instanceof StatusCodeException sce) {
            status = sce.getStatusCode();
            error = errorName(status);
        } else if (cause instanceof InvalidMediaException) {
            status = StatusCode.UNPROCESSABLE_ENTITY;
            error = "invalid_media";
        } else if (cause instanceof SshKeyService.InvalidKeyException) {
            status = StatusCode.BAD_REQUEST;
            error = "invalid_key";
        } else {
            error = errorName(status);
            if (status.value() >= 500) {
                getLog().error("{} {} failed", ctx.getMethod(), ctx.getRequestPath(), cause);
                message = "internal server error";
            }
        }
        ctx.setResponseType(MediaType.json)
            .setResponseCode(status)
            .render(Map.of("error", error, "message", message == null ? status.reason() : message));
    }

    private static String errorName(StatusCode status) {
        return status.reason().toLowerCase().replace(' ', '_');
    }
}
