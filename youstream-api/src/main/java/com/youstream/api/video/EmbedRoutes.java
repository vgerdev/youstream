package com.youstream.api.video;

import com.youstream.api.AppServices;
import com.youstream.common.model.User;
import com.youstream.common.model.Video;
import com.youstream.common.model.VideoStatus;
import com.youstream.common.model.Visibility;
import com.youstream.db.repo.UserRepository;
import com.youstream.db.repo.VideoRepository;
import io.jooby.Context;
import io.jooby.Jooby;
import io.jooby.StatusCode;
import io.jooby.exception.StatusCodeException;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Embedding: framing rules for every response (only {@code /embed/*} may be shown in other sites' iframes)
 * and an oEmbed endpoint so pasted links unfurl in chat and CMS tools.
 */
public final class EmbedRoutes {

    private static final Pattern VIDEO_URL = Pattern.compile("/(?:watch|embed)/([0-9A-Za-z]{11})(?:[/?#].*)?");
    private static final int DEFAULT_WIDTH = 560;
    private static final int DEFAULT_HEIGHT = 315;

    private final AppServices s;
    private final VideoRepository videos;
    private final UserRepository users;

    public EmbedRoutes(AppServices services) {
        this.s = services;
        this.videos = services.getDb() == null ? null : new VideoRepository(services.getDb());
        this.users = services.getDb() == null ? null : new UserRepository(services.getDb());
    }

    /**
     * Content-Security-Policy for everything served: only this origin's scripts, media and API, no plugins.
     * Inline styles stay allowed because Solid sets style attributes (progress bars). hls.js plays through
     * {@code blob:} MediaSource URLs.
     */
    static final String CSP = "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline';"
        + " img-src 'self' data: blob:; media-src 'self' blob:; connect-src 'self'; worker-src 'self' blob:;"
        + " font-src 'self'; object-src 'none'; base-uri 'self'; form-action 'self'";

    /** Security headers; must be installed before the routes they apply to. */
    public static void installHeaders(Jooby app) {
        app.use(next -> ctx -> {
            ctx.setResponseHeader("X-Content-Type-Options", "nosniff");
            ctx.setResponseHeader("Referrer-Policy", "strict-origin-when-cross-origin");
            if (ctx.getRequestPath().startsWith("/embed/")) {
                ctx.setResponseHeader("Content-Security-Policy", CSP + "; frame-ancestors *");
            } else {
                ctx.setResponseHeader("Content-Security-Policy", CSP + "; frame-ancestors 'none'");
                ctx.setResponseHeader("X-Frame-Options", "DENY");
            }
            return next.apply(ctx);
        });
    }

    public void install(Jooby app) {
        app.get("/oembed", this::oembed);
    }

    /** https://oembed.com: public and unlisted videos only. */
    private Map<String, Object> oembed(Context ctx) {
        String url = ctx.query("url").value("");
        String format = ctx.query("format").value("json");
        if (!"json".equals(format)) {
            throw new StatusCodeException(StatusCode.NOT_IMPLEMENTED, "only format=json is supported");
        }
        Video video = videoFromUrl(url);
        int width = bounded(ctx.query("maxwidth").intValue(DEFAULT_WIDTH), DEFAULT_WIDTH);
        int height = Math.min(bounded(ctx.query("maxheight").intValue(DEFAULT_HEIGHT), DEFAULT_HEIGHT), width * 9 / 16);
        User owner = users.findById(video.getOwnerId()).orElseThrow();
        String base = s.getPublicUrl();
        Map<String, Object> rsp = new LinkedHashMap<>();
        rsp.put("type", "video");
        rsp.put("version", "1.0");
        rsp.put("title", video.getTitle());
        rsp.put("author_name", owner.getDisplayName());
        rsp.put("author_url", base + "/c/" + owner.getHandle());
        rsp.put("provider_name", "YouStream");
        rsp.put("provider_url", base);
        if (video.getVisibility() == Visibility.PUBLIC) {
            rsp.put("thumbnail_url", base + "/thumbs/" + video.getPublicId() + "/pub/poster.jpg");
        }
        rsp.put("width", width);
        rsp.put("height", height);
        rsp.put("html", embedHtml(base, video, width, height));
        return rsp;
    }

    public static String embedHtml(String base, Video video, int width, int height) {
        return "<iframe width=\"" + width + "\" height=\"" + height + "\" src=\"" + base + "/embed/" + video.getPublicId()
            + "\" title=\"" + escape(video.getTitle()) + "\" frameborder=\"0\" allow=\"autoplay; fullscreen; picture-in-picture\""
            + " allowfullscreen></iframe>";
    }

    private Video videoFromUrl(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new StatusCodeException(StatusCode.NOT_FOUND, "not a YouStream video URL");
        }
        Matcher m = VIDEO_URL.matcher(uri.getRawPath() == null ? "" : uri.getRawPath());
        if (!m.matches()) {
            throw new StatusCodeException(StatusCode.NOT_FOUND, "not a YouStream video URL");
        }
        return videos.findByPublicId(m.group(1))
            .filter(v -> v.getVisibility() != Visibility.PRIVATE && v.getStatus() == VideoStatus.READY)
            .orElseThrow(() -> new StatusCodeException(StatusCode.NOT_FOUND, "video not found"));
    }

    private static int bounded(int requested, int max) {
        return Math.max(200, Math.min(requested, max));
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
