package com.youstream.api;

import io.jooby.Jooby;
import io.jooby.StatusCode;
import io.jooby.handler.AssetHandler;
import io.jooby.handler.AssetSource;
import io.jooby.jackson3.Jackson3Module;

import java.util.Map;

/**
 * HTTP application: JSON API under {@code /api}, and the SolidJS single page app for every other path.
 */
public class YouStreamApp extends Jooby {

    public YouStreamApp(AppServices services) {
        install(new Jackson3Module());

        path("/api", () -> {
            get("/health", ctx -> {
                boolean db = services.getDatabaseHealthy().getAsBoolean();
                ctx.setResponseCode(db ? StatusCode.OK : StatusCode.SERVICE_UNAVAILABLE);
                return Map.of("status", db ? "UP" : "DOWN", "database", db ? "UP" : "DOWN");
            });

            // unknown API paths must not fall through to the SPA
            get("/*", ctx -> ctx.send(StatusCode.NOT_FOUND));
        });

        // built frontend is packaged by youstream-web under classpath:public; client-side routes fall back to index.html
        assets("/*", new AssetHandler("index.html", AssetSource.create(getClassLoader(), "public")));
    }
}
