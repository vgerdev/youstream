package com.youstream.api.video;

import com.youstream.api.AppServices;
import com.youstream.auth.token.PlaybackTokens;
import io.jooby.Context;
import io.jooby.Jooby;
import io.jooby.MediaType;
import io.jooby.StatusCode;
import io.jooby.exception.StatusCodeException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * Serves HLS files and posters: {@code /media/{publicId}/{token}/...} and {@code /thumbs/{publicId}/{token}/poster.jpg}.
 * The token is {@code pub} for public videos (checked against the database, cached briefly) or a signed playback token.
 */
public final class MediaRoutes {

    private static final Pattern HLS_FILE = Pattern.compile("(master\\.m3u8|[0-9]{2,4}p/(index\\.m3u8|init(_[0-9]+)?\\.mp4|seg_[0-9]{5}\\.m4s))");
    private static final MediaType M3U8 = MediaType.valueOf("application/vnd.apple.mpegurl");
    private static final MediaType M4S = MediaType.valueOf("video/iso.segment");
    private static final MediaType MP4 = MediaType.valueOf("video/mp4");

    private final AppServices s;
    private final PublicVideoCache publicVideos;

    public MediaRoutes(AppServices services, PublicVideoCache publicVideos) {
        this.s = services;
        this.publicVideos = publicVideos;
    }

    public void install(Jooby app) {
        app.get("/media/{publicId}/{token}/*", this::media);
        app.get("/thumbs/{publicId}/{token}/poster.jpg", this::poster);
    }

    private Context media(Context ctx) {
        String pid = ctx.path("publicId").value();
        String token = ctx.path("token").value();
        String file = ctx.path("*").value("");
        if (!HLS_FILE.matcher(file).matches()) {
            throw notFound();
        }
        authorize(pid, token);
        MediaType type = file.endsWith(".m3u8") ? M3U8 : file.endsWith(".m4s") ? M4S : MP4;
        // segments never change; playlists may be replaced by a re-transcode
        boolean immutable = !file.endsWith(".m3u8");
        return send(ctx, s.getStorage().localPath("hls/" + pid + "/" + file), type, token, immutable);
    }

    private Context poster(Context ctx) {
        String pid = ctx.path("publicId").value();
        String token = ctx.path("token").value();
        authorize(pid, token);
        return send(ctx, s.getStorage().localPath("thumbs/" + pid + "/poster.jpg"), MediaType.valueOf("image/jpeg"), token, false);
    }

    private void authorize(String pid, String token) {
        boolean ok = PlaybackTokens.PUBLIC.equals(token)
            ? publicVideos.isPublicAndReady(pid)
            : s.getPlaybackTokens().verify(pid, token);
        if (!ok) {
            throw notFound();
        }
    }

    private static Context send(Context ctx, Path file, MediaType type, String token, boolean immutable) {
        if (!Files.isRegularFile(file)) {
            throw notFound();
        }
        String scope = PlaybackTokens.PUBLIC.equals(token) ? "public" : "private";
        ctx.setResponseHeader("Cache-Control", scope + (immutable ? ", max-age=31536000, immutable" : ", max-age=60"));
        ctx.setResponseType(type);
        return ctx.send(file);
    }

    private static StatusCodeException notFound() {
        return new StatusCodeException(StatusCode.NOT_FOUND, "not found");
    }

    /** Exposed for tests. */
    static boolean isHlsFile(String path) {
        return HLS_FILE.matcher(path).matches();
    }
}
