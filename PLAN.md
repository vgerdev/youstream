# YouStream — Implementation Plan

A self-hosted video sharing platform: upload (browser or SFTP), transcode to adaptive HLS, browse, watch, comment, like, subscribe, search, playlists, and share/embed.

## 1. Decisions

| Area | Choice |
|---|---|
| Language / JDK | Java 25 (Gradle toolchain) |
| Build | Gradle 9.1.0 wrapper, **Groovy DSL**, multi-project; each project has its own `build.gradle` + `dependencies.gradle` |
| Web framework | Jooby 4.x running on the **Undertow** server module (`jooby-undertow`), MVC controllers via `jooby-apt` |
| SFTP | Apache MINA SSHD (`org.apache.sshd:sshd-core` + `sshd-sftp`) |
| Database | Remote PostgreSQL (you supply the credentials when Phase 0 needs them) |
| DB access | `jcabi-jdbc` (`JdbcSession`) on a **HikariCP** pool |
| Migrations | Flyway (assumption — you didn't name a tool) |
| Models | Lombok `@Data` / `@Builder` (Lombok ≥ 1.18.40 for JDK 25) |
| Auth | Two login methods, both ending in a YouStream JWT: **"Sign in with Microsoft"** (Microsoft Entra ID, OpenID Connect authorization-code flow) and **LDAP username/password** (UnboundID LDAP SDK, works with OpenLDAP/lldap/AD). SFTP accepts the LDAP password and/or SSH public keys registered in the profile (the only SFTP option for Entra users). Development and tests use an embedded LDAP server (`youstream-devldap`) |
| Media | FFmpeg/ffprobe → multi-bitrate **HLS** + thumbnails, run by a background worker |
| Storage | Local disk behind a pluggable `StorageService` interface (S3/MinIO can be added later) |
| Frontend | SolidJS + TypeScript + Vite + Tailwind CSS, `@solidjs/router`, `@tanstack/solid-query`, `hls.js` |
| Scope v1 | Upload/watch/share, comments & likes, channels & subscriptions, search & playlists, visibility (public/unlisted/private), share links, embed player |

## 2. Repository layout

```
youstream/
├── settings.gradle               # rootProject.name = 'youstream'; include all youstream-* projects
├── build.gradle                  # shared config for subprojects (toolchain 25, Lombok, JUnit, repos)
├── dependencies.gradle           # ext.versions + ext.libs (the one place versions live)
├── lombok.config
├── gradle/wrapper/…              # Gradle 9.1.0
├── conf/
│   ├── application.conf          # defaults, committed
│   ├── dev-ldap.ldif             # seed users/groups for the dev LDAP server
│   └── local.conf                # secrets (DB, Entra, JWT) — git-ignored
├── youstream-common/             # domain models, config, StorageService, utilities
├── youstream-db/                 # HikariCP, Flyway migrations, jcabi-jdbc repositories
├── youstream-auth/               # LDAP + Entra OIDC login, JWT, SSH key service, playback-token signer
├── youstream-devldap/            # embedded UnboundID LDAP server for dev/testing (gradle :youstream-devldap:run)
├── youstream-media/              # ffprobe/ffmpeg wrappers, job queue, transcode worker, IngestService
├── youstream-sftp/               # MINA SSHD server, SFTP ingest
├── youstream-api/                # Jooby routes/controllers, DTOs, error handling, media + SPA serving
├── youstream-web/                # SolidJS app; Gradle wraps the npm build and packages dist/ as a jar
└── youstream-server/             # main(): wires everything, application plugin, distribution
```

Each subproject:

```
youstream-xyz/
├── build.gradle          # plugins + `apply from: 'dependencies.gradle'`
├── dependencies.gradle   # dependencies { … } using libs.* from the root file
└── src/main/java/com/youstream/xyz/…
```

### Project dependency graph

```
common ◄── db ◄── auth ◄──┬── sftp ──┐
              ▲            │          │
              └── media ◄──┘          ├──► server (main)
                    ▲                 │
                    └──── api ◄───────┤
                                web ──┘   (static assets jar)

devldap (standalone; also a test dependency of auth and sftp)
```

- `sftp` and `api` both call `media.IngestService`, so browser and SFTP uploads go through the same path.
- `server` is the only executable project. It starts Jooby/Undertow (8080), the SSHD server (2222) and the transcode workers in one JVM.

### Build details

- **Root `dependencies.gradle`**: `ext.versions = [jooby: '4.x', sshd: '2.x', hikari: '…', jcabiJdbc: '…', postgres: '42.7.x', flyway: '11.x', unboundid: '7.x', javaJwt: '4.x', lombok: '1.18.4x', junit: '…', assertj: '…', testcontainers: '…']` and `ext.libs = [ … 'group:artifact:${versions.x}' … ]`. Exact latest versions get pinned when the files are written.
- **Root `build.gradle`**: `apply from: 'dependencies.gradle'`; a `subprojects {}` block applies `java-library`, sets `java.toolchain.languageVersion = JavaLanguageVersion.of(25)`, adds Lombok (`compileOnly` + `annotationProcessor`, and the same for tests), JUnit Platform, and `-parameters` (Jooby MVC needs it).
- **`youstream-web/build.gradle`**: `com.github.node-gradle.node` plugin using the Node.js on PATH (`download = false`). Tasks: `npmInstall` → `npmBuild` (`tsc --noEmit && vite build`); `check` runs `npmTest` (Vitest). `processResources` copies `dist/` into `public/` inside the jar.
- Logging: `logback.xml` ships on the classpath in `youstream-server/src/main/resources`.
- **`youstream-server/build.gradle`**: `application` plugin, `mainClass = 'com.youstream.server.YouStreamServer'`. `installDist`/`distZip` produce a runnable distribution with `conf/`.
- The wrapper (`gradlew`, 9.1.0) is committed. Locally Gradle is installed at `C:\opt\gradle-9.1.0`.
- Pinned versions (Phase 0): Jooby 4.6.0 (Undertow 2.4.4, `jooby-jackson3`), SSHD 2.20.1, HikariCP 7.1.0, jcabi-jdbc 0.19.0, PostgreSQL JDBC 42.7.14, Flyway 13.11.1, UnboundID 7.0.5, java-jwt 4.6.2, Lombok 1.18.48, JUnit 6.1.3; frontend Solid 1.9, Vite 8, Tailwind 4.3, TypeScript 5.9, Vitest 5 with happy-dom.

## 3. Configuration

`conf/application.conf` (HOCON, which Jooby reads natively). Every value can be overridden by an environment variable:

```hocon
db { url = ${?DB_URL}, user = ${?DB_USER}, password = ${?DB_PASSWORD}, poolSize = 10 }
ldap {
  enabled = true
  url = "ldap://localhost:10389"          # LDAP_URL; dev LDAP by default, ldaps:// in production
  mode = "search"                         # search-then-bind, or "upn" (AD: bind as user@upnDomain)
  baseDn, bindDn, bindPassword, userFilter = "(&(objectClass=inetOrgPerson)(uid={0}))"
  attributes { id = "entryUUID", username = "uid", displayName = "cn", email = "mail", memberOf = "memberOf" }
  requiredGroup = ""                      # optional: only members of this group may log in
  adminGroup = "cn=youstream-admins,…"    # optional: moderation rights
}
entra {
  enabled, tenantId, clientId, clientSecret
  redirectUri = "http://localhost:8080/api/auth/microsoft/callback"
  scopes = "openid profile email User.Read"
  adminRole = "YouStream.Admin"           # app role value that grants admin
}
auth { secureCookies = true, frontendUrl = "" }  # frontendUrl: where Microsoft sign-in returns (Vite dev: http://localhost:3000)
jwt { secret = ${?JWT_SECRET}, accessTtl = 15m, refreshTtl = 30d }
storage { root = "./data" }
upload { maxBytes = 10G, chunkBytes = 16M }
sftp { port = 2222, hostKey = "./data/ssh/host_key", maxFileBytes = 10G }
media { ffmpeg = "ffmpeg", ffprobe = "ffprobe", workers = 2, segmentSeconds = 6 }
server { port = 8080 }
```

Secrets (DB password, JWT secret, Entra client secret, LDAP bind password) go only into the git-ignored `conf/local.conf` or environment variables.

## 4. Data model (Flyway migrations in `youstream-db/src/main/resources/db/migration`)

Internal keys are `bigserial`. Public URLs use an 11-character random base62 `public_id`. Status and enum-like columns are `text` with `CHECK` constraints instead of PG enums, which keeps jcabi-jdbc parameter binding simple.

| Table | Key columns |
|---|---|
| `users` | id, username (unique; used for SFTP login), display_name, email, `handle` (unique channel slug), avatar_path, banner_path, bio, is_admin, created_at, last_login_at |
| `user_identities` | (provider `LDAP`/`ENTRA`, subject) PK, user_id, created_at. Subject is the LDAP `entryUUID`/`objectGUID` or the Entra `oid` claim. One user can have both, linked explicitly from the profile page while signed in |
| `refresh_tokens` | id, user_id, token_hash, expires_at, revoked_at, user_agent |
| `ssh_keys` | id, user_id, name, key_type, fingerprint_sha256 (unique), public_key (OpenSSH text), created_at, last_used_at |
| `videos` | id, public_id, owner_id, title, description, visibility (`PUBLIC`/`UNLISTED`/`PRIVATE`), status (`UPLOADING`/`UPLOADED`/`PROCESSING`/`READY`/`FAILED`), source (`WEB`/`SFTP`), original_filename, size_bytes, duration_ms, width, height, view_count, like_count, dislike_count, comment_count, created_at, published_at, `search tsvector GENERATED ALWAYS AS (…title A, description B…) STORED` + GIN index |
| `video_renditions` | video_id, height, bitrate_kbps, playlist_path |
| `transcode_jobs` | id, video_id, status (`QUEUED`/`RUNNING`/`DONE`/`FAILED`), progress, attempts, locked_by, locked_at, error, run_after, created_at |
| `upload_sessions` | id (uuid text), user_id, filename, total_bytes, received_bytes, created_at, expires_at |
| `comments` | id, video_id, user_id, parent_id (one reply level, as on YouTube), body, created_at, edited_at, deleted_at |
| `video_reactions` | (video_id, user_id) PK, value (+1/−1), created_at |
| `subscriptions` | (subscriber_id, channel_user_id) PK, created_at |
| `playlists` | id, public_id, owner_id, title, description, visibility, created_at |
| `playlist_items` | (playlist_id, video_id) PK, position |
| `video_views` | video_id, viewer_key (user id or hashed IP+UA), viewed_on (date). Unique per day, so view counts are deduplicated |

Counters (`like_count`, `comment_count`, `view_count`) are updated in the same transaction as the row change.

### Repository pattern (jcabi-jdbc)

```java
@RequiredArgsConstructor
public class VideoRepository {
    private final DataSource ds;

    public Optional<Video> findByPublicId(String publicId) throws SQLException {
        return new JdbcSession(ds)
            .sql("SELECT * FROM videos WHERE public_id = ?")
            .set(publicId)
            .select(new ListOutcome<>(VideoMapper::map))
            .stream().findFirst();
    }
}
```

Transactions use `new JdbcSession(ds).autocommit(false) … .commit()`. Mappers are small static `ResultSet → @Builder` functions.

## 5. Authentication & authorization (`youstream-auth`)

Both login methods produce an `ExternalIdentity(provider, subject, username, displayName, email, admin)`. `UserService.signIn(identity)` looks up `user_identities`, creates the user on first sign-in (unique `username` and `handle` derived from the identity), refreshes display name/email/admin, and issues the YouStream tokens. Everything after that is identical for both methods.

**LDAP login (`LdapAuthenticator`, `POST /api/auth/login`)**
1. `mode = search`: bind with the service account (`bindDn`), find the user with `userFilter` (value escaped) under `baseDn`, then bind as the found DN with the supplied password. `mode = upn`: bind directly as `username@upnDomain` (Active Directory), then read the user's own entry.
2. Read the mapped attributes (`id`, `username`, `displayName`, `email`, `memberOf`).
3. Optionally require membership of `requiredGroup`. `adminGroup` sets admin. For AD, nested groups are checked with the `LDAP_MATCHING_RULE_IN_CHAIN` OID `1.2.840.113556.1.4.1941`.
4. Production requires `ldaps://` or StartTLS. Plain `ldap://` is allowed only for `localhost` (the dev server).

The SFTP password authenticator uses the same class.

**Microsoft Entra ID (`EntraOidcService`)**
- Single-tenant app registration, platform **Web** (confidential client with a client secret), redirect URI `/api/auth/microsoft/callback`.
- `GET /api/auth/microsoft/login` redirects to `https://login.microsoftonline.com/{tenant}/oauth2/v2.0/authorize` with `state`, `nonce` and PKCE, all kept in a short-lived signed cookie.
- The callback exchanges the code for tokens (Nimbus OAuth 2.0 / OIDC SDK) and validates the ID token signature against the tenant's JWKS, plus issuer, audience, nonce and expiry. It maps `oid` → subject, `preferred_username`/`email`, `name`, and `roles` (contains `YouStream.Admin` → admin).
- It then sets the refresh cookie and redirects to the SPA, which calls `/api/auth/refresh` to get its access token. Entra access tokens are never passed to the browser.
- The Entra password is never seen by YouStream, so Entra-only users authenticate to SFTP with SSH keys.

**Dev LDAP server (`youstream-devldap`)**
- UnboundID `InMemoryDirectoryServer` on port 10389, seeded from the bundled `dev-ldap.ldif` (override with `--ldif <file>`): base `dc=youstream,dc=local`, a service account, users `alice` (admin), `bob` and `carol`, and the groups `youstream-users`/`youstream-admins` (with `memberOf` on users). `entryUUID` is generated by the server.
- Runs standalone with `gradle :youstream-devldap:run`. Auth and SFTP tests start it in-process on a random port.
- Optional: run [lldap](https://github.com/lldap/lldap) (single native binary, web UI) instead when you want a persistent directory to manage users by hand. The same `search` mode config works.

**Web tokens**
- `POST /api/auth/login` returns a short-lived access JWT (HS256, 15 min, claims: `sub`, `handle`, `admin`) in the body and sets a refresh token as an `HttpOnly; Secure; SameSite=Strict` cookie. Only a hash of the refresh token is stored, and it rotates on each use.
- The frontend keeps the access token in memory and calls `/api/auth/refresh` when it expires or on a 401.
- A Jooby `before` filter decodes the JWT into `ctx.getUser()`. Route-level checks: authenticated, owner of the resource, or admin.
- Login has an in-memory rate limit per IP and username. Passwords are never logged.

**Playback tokens (private/unlisted media)**
- `/media/{publicId}/{token}/master.m3u8` takes an HMAC token (video id + user id + expiry, about 6 h) as a **path segment**. Relative segment URLs inside the playlists inherit it, so hls.js needs no custom headers and playlists don't have to be rewritten.
- Public videos get a static token (`pub`).

**SSH keys**
- `GET/POST/DELETE /api/me/ssh-keys`. Keys are parsed with SSHD's `PublicKeyEntry` and only `ssh-ed25519`, `ecdsa-*`, and `rsa` ≥ 3072 are accepted.
- The SHA-256 fingerprint is stored. A key can belong to only one user.

## 6. Storage (`youstream-common`)

```java
public interface StorageService {
    OutputStream create(String key);          // e.g. "originals/abc123/source.mp4"
    InputStream open(String key);
    InputStream openRange(String key, long from, long to);
    long size(String key);
    void move(String from, String to);
    void deleteTree(String prefix);
    Path localPath(String key);               // FFmpeg needs real paths; non-local impls stage to temp
}
```

`LocalDiskStorage` normalizes keys and rejects any key that resolves outside `storage.root` (path-traversal guard).

```
data/
├── incoming/{uploadSessionId}             # chunked browser uploads in progress
├── sftp/{userId}/upload/                  # per-user SFTP inbox (virtual root)
├── originals/{publicId}/source.{ext}
├── hls/{publicId}/master.m3u8, {height}p/index.m3u8, {height}p/seg_00001.m4s …
├── thumbs/{publicId}/poster.jpg, sprite.jpg, sprite.vtt
├── avatars/{userId}.jpg
└── ssh/host_key
```

## 7. Ingest & transcoding (`youstream-media`)

**`IngestService.ingest(userId, Path file, Metadata meta, Source source)`**
1. Run `ffprobe -print_format json -show_streams -show_format`. Reject the file if it has no video stream or the container is unreadable. The file is validated by content, never by extension.
2. Move it to `originals/{publicId}/source.{ext}`, insert the `videos` row (`UPLOADED`), and enqueue a `transcode_jobs` row, all in one transaction.

**Job queue**
- Workers claim jobs with `UPDATE transcode_jobs SET status='RUNNING', locked_by=?, locked_at=now() WHERE id = (SELECT id FROM transcode_jobs WHERE status='QUEUED' AND run_after <= now() ORDER BY id FOR UPDATE SKIP LOCKED LIMIT 1) RETURNING *`.
- Several JVMs can share the queue safely.
- Polling runs every 2 s. A `LISTEN/NOTIFY` wake-up can be added later.
- At startup, `RUNNING` jobs with a stale `locked_at` go back to `QUEUED`.
- A failed job is retried up to 3 times with backoff, then marked `FAILED` along with its video.

**`TranscodeWorker`**
- Builds the ladder from the source height, never upscaling: 1080p / 5000k, 720p / 2800k, 480p / 1400k, 360p / 800k, plus AAC 128k audio.
- A single FFmpeg run produces every rendition: `-filter_complex split+scale`, `-var_stream_map`, `-f hls -hls_time 6 -hls_playlist_type vod -hls_segment_type fmp4 -master_pl_name master.m3u8`, H.264 `-preset veryfast` with GOPs aligned to the segment length.
- `-progress pipe:1` is parsed to update `transcode_jobs.progress`. The Studio UI polls this.
- Also produces a poster frame at 10 % of the duration. (Thumbnail sprite + WebVTT scrub previews: deferred to Phase 7.)
- Files without a duration header (browser/screen recordings from MediaRecorder) are accepted; progress is not reported and the duration is measured from the produced playlist.
- FFmpeg output paths are passed with forward slashes, otherwise Windows backslashes end up in the playlist URIs.
- On success, writes `video_renditions` and sets the video to `READY` with `published_at`.
- FFmpeg runs through `ProcessBuilder` with an argument list, never a shell string, and has a per-job timeout.

FFmpeg isn't installed here yet (`winget install Gyan.FFmpeg`). Its path can be set in config.

## 8. SFTP server (`youstream-sftp`)

- `SshServer.setUpDefaultServer()` on port 2222. The `SimpleGeneratorHostKeyProvider` host key is persisted to `data/ssh/host_key` (Ed25519), so clients don't see a new host key after a restart.
- **Auth**: a `PasswordAuthenticator` calls `LdapAuthenticator` (LDAP users only), and a `PublickeyAuthenticator` looks up the fingerprint in `ssh_keys`, checks that the key belongs to the login username, and updates `last_used_at`. The resolved `userId` is stored as a session attribute.
- **Lockdown**: only `SftpSubsystemFactory` is registered. There is no shell or exec channel (`setShellFactory(null)`, no `CommandFactory`), all TCP/agent/X11 forwarding is rejected, the auth timeout is short, and failed auths are rate-limited.
- **Filesystem**: a per-session `VirtualFileSystemFactory` rooted at `data/sftp/{userId}/`; the whole root is the user's inbox (subfolders allowed). Links cannot be created, and listings show owner/group `youstream` instead of the server's OS account.
- **Ed25519 keys** need BouncyCastle on the classpath (`bcprov`/`bcpkix`); SSHD alone only handles RSA/ECDSA.
- **OpenSSH compatibility**: max read/write data per SFTP packet is 255 KiB, because OpenSSH's `sftp` caps a whole message at 256 KiB.
- **Ingest trigger**: an `SftpEventListener`.
  - On `closed(...)` for a handle that was opened for writing, it schedules ingest.
  - On `moved(...)` it handles clients that upload to `*.part`/`*.tmp` and rename at the end.
  - Temp-suffixed files are ignored until they are renamed.
  - A file whose size is still changing within a short debounce window is re-checked before ingest.
- **Metadata**: an optional sidecar `name.json` (`{"title","description","visibility","playlist"}`) next to `name.mp4`. Otherwise the title is taken from the filename and visibility defaults to **PRIVATE**, so the user reviews the video in Studio before publishing.
- **Limits**: `maxFileBytes` and a per-user quota are enforced with a write-size check in a custom `SftpFileSystemAccessor`.
- After ingest, the file is moved out of the inbox, and a `name.mp4.status` text file is written back: `ACCEPTED <id>` with the watch URL, or `REJECTED <reason>` (not a video, over the size limit). Processing progress is shown in Studio.

## 9. HTTP API (`youstream-api`)

Jooby app with `jooby-undertow`, the `jooby-jackson` JSON module, MVC controllers (`@Path`, `@GET`, …) generated by `jooby-apt`, and blocking work in worker mode. A global error handler maps exceptions to `{error, message}` with the right status. `jooby-openapi` can generate the API docs.

| Area | Endpoints |
|---|---|
| Auth | `GET /api/auth/methods` (which logins are enabled), `POST /api/auth/login` (LDAP), `GET /api/auth/microsoft/login`, `GET /api/auth/microsoft/callback`, `POST /api/me/identities/microsoft` (link accounts), `POST /api/auth/refresh`, `POST /api/auth/logout`, `GET /api/me`, `PATCH /api/me` (handle, bio, avatar) |
| SSH keys | `GET/POST /api/me/ssh-keys`, `DELETE /api/me/ssh-keys/{id}` |
| Upload (resumable) | `POST /api/uploads` → `{id, chunkBytes}`; `PUT /api/uploads/{id}` with `Content-Range` (chunks in order; re-sent chunks are accepted, a gap returns 409 with the current offset); `GET /api/uploads/{id}` (bytes received); `POST /api/uploads/{id}/complete` with metadata → IngestService |
| Videos | `GET /api/videos?feed=latest\|trending&cursor=`, `GET /api/videos/{pid}`, `PATCH /api/videos/{pid}`, `DELETE /api/videos/{pid}`, `GET /api/videos/{pid}/status`, `POST /api/videos/{pid}/views`, `GET /api/me/videos` (Studio) |
| Reactions | `PUT /api/videos/{pid}/reaction {value: 1\|-1\|0}` (0 clears) |
| Comments | `GET /api/videos/{pid}/comments?cursor=&parent=`, `POST /api/videos/{pid}/comments`, `PATCH/DELETE /api/comments/{id}` |
| Channels | `GET /api/channels/{handle}`, `GET /api/channels/{handle}/videos`, `GET /api/channels/{handle}/playlists` (Phase 6), `PUT/DELETE /api/channels/{handle}/subscription`, `GET /api/feed/subscriptions`, `GET /api/me/subscriptions`, `PATCH /api/me` (handle, display name, bio). Channel pages live at `/c/{handle}` in the SPA |
| Search | `GET /api/search?q=&type=video\|channel\|playlist&cursor=`: every word becomes a prefix term (`sail:* & carib:*`, built server-side from letters/digits only), ranked by `ts_rank`, offset paging up to 1000 results |
| Playlists | `POST /api/playlists`, `GET/PATCH/DELETE /api/playlists/{pid}`, `POST /api/playlists/{pid}/items`, `PUT /api/playlists/{pid}/items` (order), `DELETE /api/playlists/{pid}/items/{videoPid}`, `GET /api/me/playlists?contains=`, `GET /api/channels/{handle}/playlists` |
| Media | `GET /media/{pid}/{token}/**` (HLS playlists and segments with long-cache headers), `GET /thumbs/{pid}/poster.jpg`, `GET /avatars/{userId}.jpg`. All support range requests |
| oEmbed | `GET /oembed?url=` (optional, so pasted links unfurl) |
| SPA | Static assets from `public/` on the classpath, and any non-`/api`, non-`/media` route falls back to `index.html` |

**Visibility rules** (enforced in one `VideoAccessPolicy` service):
- **PUBLIC**: listed in feeds and search.
- **UNLISTED**: anyone with the link can watch, but it isn't listed or searchable.
- **PRIVATE**: owner (and admin) only.

Pagination is keyset (cursor-based) everywhere. Security headers: CSP, `X-Content-Type-Options`, and `frame-ancestors 'none'` everywhere except `/embed/*`. CORS is enabled only in dev mode.

## 10. Frontend (`youstream-web`)

**Stack**: Vite, SolidJS, TypeScript (strict), Tailwind CSS, `@solidjs/router`, `@tanstack/solid-query`, `hls.js`. Tests use Vitest + `@solidjs/testing-library`. The Vite dev server proxies `/api`, `/media` and `/thumbs` to `localhost:8080`.

```
src/
├── api/            # typed fetch client, auth/refresh interceptor, query hooks per resource
├── auth/           # AuthProvider context (in-memory access token, silent refresh)
├── components/     # VideoCard, VideoGrid, Player (hls.js + quality menu + sprite previews),
│                   # CommentThread, LikeBar, SubscribeButton, ShareDialog, UploadDropzone, …
├── layouts/        # AppShell (top bar w/ search, collapsible sidebar), EmbedLayout
├── pages/
│   ├── Home.tsx            # latest / trending grid
│   ├── Watch.tsx           # player, title, like/dislike, channel + subscribe, description, comments, up-next
│   ├── Channel.tsx         # banner, avatar, tabs: Videos / Playlists / About
│   ├── Search.tsx
│   ├── Subscriptions.tsx
│   ├── Playlist.tsx
│   ├── Upload.tsx          # drag & drop, chunked resumable upload with progress, metadata form
│   ├── studio/Videos.tsx   # my videos, processing progress, edit metadata/visibility, delete
│   ├── settings/SshKeys.tsx# add/remove keys + copy-paste SFTP connection instructions
│   ├── Login.tsx           # "Sign in with Microsoft" button + LDAP username/password form
│   └── Embed.tsx           # bare player for /embed/:pid
└── main.tsx, routes.tsx, index.css (Tailwind)
```

- **Share dialog**: copy link, copy link at the current timestamp (`?t=`), and copy the embed `<iframe>` snippet.
- **Upload**: files are split into 16 MB chunks with `Blob.slice` and sent one after another (retried with backoff). The upload id is kept in `localStorage` so it can resume after a reload.
- **Theming**: light and dark themes through Tailwind's `dark:` variant.

## 11. Server wiring (`youstream-server`)

`YouStreamServer.main` does the following:
1. Load the config (`application.conf` + `local.conf` + env).
2. Build the Hikari `DataSource` and run Flyway `migrate()`.
3. Construct the repositories and services. Wiring is manual constructor injection, with no DI container, to keep startup small and explicit.
4. Start the transcode worker pool.
5. Start SSHD.
6. `Jooby.runApp(args, () -> new YouStreamApp(services))` on Undertow.

A shutdown hook stops the SSHD server, drains the workers (in-flight jobs go back to `QUEUED`) and closes Hikari.

## 12. Testing strategy

| Layer | Approach |
|---|---|
| Repositories | Testcontainers PostgreSQL (needs Docker), or a dedicated test schema on the remote DB, selected by a Gradle property |
| LDAP auth | `youstream-devldap` started in-process on a random port: good/bad password, unknown user, group gating, admin mapping, filter injection attempts |
| Entra OIDC | Unit tests with a locally generated RSA key and a stub JWKS/token endpoint (state, nonce, issuer, audience, expiry, role mapping). Manual check against the real tenant |
| Media | FFmpeg generates a 3 s `testsrc` clip at test time and the test asserts the HLS output. Skipped when FFmpeg is missing |
| SFTP | Embedded server plus the SSHD `SftpClient`: password and key auth, upload with and without `.part` rename, chroot escape attempts, and rejection of shell/exec channels |
| API | Jooby `MockRouter`/`JoobyTest` for routes, auth filters, and visibility rules |
| Frontend | Vitest unit/component tests. Optional Playwright smoke test: login → upload → watch |

`./gradlew build` runs everything, including the frontend build and tests.

## 13. Phases & milestones

| Phase | Deliverable | Done when |
|---|---|---|
| **0. Bootstrap** | Gradle 9.1.0 wrapper, `settings.gradle`, root `build.gradle` + `dependencies.gradle`, the `youstream-*` skeletons each with `build.gradle` + `dependencies.gradle`, Lombok, `.gitignore`, `conf/`. **I'll ask you for the PostgreSQL and AD connection details here.** | `./gradlew build` is green; the server starts and serves a "hello" SPA page |
| **1. DB & domain** ✅ | Hikari, Flyway V1 schema (all tables), Lombok models, `Db` session/transaction helper, repositories for users/identities, refresh tokens, SSH keys, videos/renditions, transcode jobs, upload sessions; `TestDatabase` test fixture. Social repositories (comments, reactions, subscriptions, playlists, views) arrive with their features in Phases 5–6 | Migrations apply to the remote DB; repository tests pass |
| **2. Auth** ✅ | `youstream-devldap`, LDAP authenticator, Entra OIDC, `user_identities` + user upsert, JWT + refresh rotation with reuse detection, login throttling, `/api/me`, account linking, Login and Account pages, AuthProvider | Signing in as `alice` (dev LDAP) and with a Microsoft account both work end to end |
| **3. Core vertical slice** ✅ | StorageService, resumable upload API, IngestService, job queue, TranscodeWorker, media serving with playback tokens; Upload, Studio, Home, Watch pages with the HLS player | Upload in the browser → progress → watch with adaptive quality |
| **4. SFTP** ✅ | SSHD server, LDAP password + SSH key auth, virtual inbox, event-driven ingest, sidecar metadata, status files; SSH Keys settings page | `sftp -P 2222 user@host` → `put clip.mp4` → video appears in Studio and plays |
| **5. Social** ✅ | Comments (with replies), likes/dislikes, channel pages, subscriptions, subscription feed, view counting | All features usable in the UI and covered by API tests |
| **6. Discovery & sharing** ✅ | Full-text search, playlists, visibility enforcement, share dialog, `/embed/:pid`, oEmbed | Unlisted links work, private videos return 404 to others, the embed iframe plays |
| **7. Hardening & packaging** ✅ | Rate limits, quotas, security headers, error pages, cleanup jobs (expired upload sessions, orphaned files), `installDist` distribution, optional Dockerfile, README with run instructions | Fresh clone → configure `local.conf` → `./gradlew :youstream-server:run` works |
| **8. Cluster-ready server** ✅ | Process roles (`web`, `sftp`, `transcode`) per pod; `migrate` and `maintenance` one-shot commands; proxy-aware client IPs (HTTP `X-Forwarded-For`, SFTP PROXY protocol); login throttle shared through PostgreSQL; SFTP host key loaded from a mounted secret; container JVM flags. See §16 | All tests pass; three local processes with different roles share one `data/` folder and the upload → transcode → watch flow works across them |
| **9. Cluster platform** 🛠 scripts written, not yet run | Media node pool, Gateway API + Envoy Gateway, cert-manager + Let's Encrypt, in-cluster NFS (RWX storage class), production database and role, Entra redirect URI, DNS. Scripted in `deploy/platform/` | `https://youstream.paulsdavenport3.com` answers with a valid certificate (from a placeholder backend); an RWX test PVC mounts on two nodes |
| **10. Helm charts** 🛠 written, not yet deployed | `deploy/helm/youstream` (web, sftp, transcode, migrate hook, maintenance CronJob, Gateway route, RWX PVC, network policies) and `deploy/helm/openldap` (Debian `slapd` image). Values per cluster in `deploy/helm/values/` | `helm install` from a laptop brings the site up; LDAP + Microsoft sign-in, browser upload, SFTP upload and playback work in production |
| **11. CI/CD & go-live** 🛠 workflows written, go-live pending | GitHub Actions: build + all tests (Postgres service container, FFmpeg), images to GHCR, `helm upgrade --atomic` to a `production` environment; smoke test; backups; runbook | A push to `master` deploys itself; rollback with `helm rollback` is documented and tried once |

## 14. Assumptions to confirm

1. **Flyway** for migrations.
2. **com.auth0:java-jwt** for JWTs.
3. **UnboundID LDAP SDK** for LDAP login and the dev LDAP server; **Nimbus OAuth 2.0 / OIDC SDK** for Entra ID.
4. One deployable process (`youstream-server`) runs the web app, SFTP and workers. Workers can be split out later because the job queue is DB-based.
5. Ports: HTTP 8080, SFTP 2222. Default max upload size: 10 GB. SFTP uploads default to **PRIVATE**.
6. Base Java package: `com.youstream.*`.
7. Docker is **not** installed, so repository tests run against the `youstream_test` schema of the remote `youstream` database (Flyway cleans and migrates it once per test run). The app uses schema `public` of the same database, as role `youstream`.
8. Not in v1: tags/categories, notifications, live streaming, recommendations beyond "latest/trending/same channel", and moderation beyond admins deleting videos and comments.

## 15. Notes from implementation

- **Display names** belong to the user once the account exists (editable on the Account page); later sign-ins only refresh email and admin rights from the identity provider.
- **Counters** (`like_count`, `dislike_count`, `comment_count`, `reply_count`, `subscriber_count`, `view_count`) are updated in the same transaction as the change; reactions lock the video row so concurrent likes stay exact.
- **Views** count once per viewer per video per day, after 3 s of playback (or half of a shorter video). Anonymous viewers are keyed by a SHA-256 of IP and User-Agent, so raw IPs are never stored.
- **Comments** are soft-deleted; a deleted top-level comment stays (as "deleted") while it still has replies. Authors edit; authors, the video owner and admins delete.
- **Frontend session restore** starts before the first render and every API call waits for it, so per-user fields (my reaction, subscribed, edit rights) are correct on a direct page load.
- **Playlists** show only items the viewer may watch (ready, and not someone else's private video); you can only add videos you can watch. SPA routes: `/playlist/{id}`, `/playlists`, `/watch/{id}?list={playlistId}` (side panel, auto-advance).
- **Framing**: every response carries `frame-ancestors 'none'` + `X-Frame-Options: DENY`, except `/embed/*` (`frame-ancestors *`). Also `X-Content-Type-Options: nosniff` and `Referrer-Policy: strict-origin-when-cross-origin`. Private videos cannot be embedded; unlisted ones can (anyone with the link may watch).
- **oEmbed** at `/oembed?url=` (JSON only) for public and unlisted videos; unlisted responses omit the thumbnail URL (it would need a signed token). No discovery `<link>` tag yet, since the SPA has no per-page server rendering.
- **Phase 7** added: maintenance jobs every 15 min (abandoned uploads and their files, leftover `incoming/` files, stale `hls/*.tmp`, SFTP `.status` files older than 30 days, expired refresh tokens, day-old view rows); per-user storage quota (`upload.quotaBytes`, both upload paths, shown in Studio); per-caller rate limits (429 + `Retry-After`); a full Content-Security-Policy; an access log; logging levels via `LOG_LEVEL`/`ACCESS_LOG`; a multi-stage Dockerfile (untested here: no Docker on the dev machine); README with configuration, Entra, SFTP and production notes.
- **Phase 8** added: `roles` / `YOUSTREAM_ROLES` (web, sftp, transcode; non-web processes answer only `/api/health`), commands `migrate`, `maintenance`, `hostkey`, `db.migrate` and `maintenance.every = 0` to hand both to Kubernetes Jobs; `server.trustedProxyHops` takes the client from the right end of `X-Forwarded-For` (Jooby's own trust-proxy takes the first, client-controlled entry, so it is not used); SFTP PROXY protocol v1/v2 through SSHD's `ServerProxyAcceptor` (a connection without the header is refused); the login throttle stores failures in `login_failures` (V2 migration, pruned daily); the SFTP host key may be a read-only file, and web processes read its fingerprint from it; `sftp.publicPort`; container JVM flags. Verified with three local processes sharing `data/`: an SFTP upload through a PROXY-header relay logged the relayed client IP, was transcoded by the separate transcode process and played from the web process.
- **Phase 9** wrote `deploy/platform/` (README + numbered scripts, settings in a git-ignored `cluster.env`, versions pinned in `versions.env`: Envoy Gateway v1.9.3, cert-manager v1.21.3, nfs-server-provisioner chart 1.8.0). Decisions made while writing them:
  - Client IPs on DOKS come from the network load balancer (the default since DOKS 1.33) with `externalTrafficPolicy: Local`, not PROXY protocol: DO network load balancers keep the source IP and don't support PROXY protocol. In-cluster clients such as cert-manager's self-check also keep working, which PROXY protocol would break. PROXY protocol stays the EKS path, for both the gateway and SFTP.
  - The Gateway (`gateway/public`) is shared and lives in its own namespace; the chart only attaches an HTTPRoute to listener `https-youstream`.
  - The NFS server runs on the media node, beside its heaviest client, to spare the small default pool. `youstream-rwx` retains volumes.
  - The database role gets `CONNECT`/`TEMPORARY` on `youstream_prod` and `USAGE`/`CREATE` on `public`. It does not own the database: on DigitalOcean (PostgreSQL 16+) `doadmin` cannot hand ownership to a role without first becoming a member of it. The SQL runs from a throwaway pod in the cluster, which also proves the trusted source and the CA certificate.
  - Secrets are created from temp files, never from command-line arguments. The `doadmin` password lives only in a temporary Secret.
  - The SFTP host key comes from `ssh-keygen` (PEM, EC P-256). `youstream hostkey` reports the same fingerprint for it.
  - Checked locally against stub `kubectl`/`helm`/`doctl`: every script runs to the end, and all rendered manifests parse.
- **Phase 10** wrote `deploy/helm/youstream`, `deploy/helm/openldap`, `deploy/helm/values/` and `deploy/openldap/` (image, LDIF, `ldap-user.sh`). Decisions made while writing them:
  - **Configuration:** environment variables, with Secrets coming in through `envFrom`. The ConfigMap `local.conf` carries only `extraConfig`. `MEDIA_PRESET` is a new override.
  - **Probes:** readiness uses `/api/health`, which checks the database. Liveness and startup use plain TCP, so a database outage doesn't restart every pod.
  - **Rollouts:** `transcode` and `sftp` use Recreate. A rolling update would need a second 3-CPU transcode pod on the single media node, and SFTP has one host key.
  - **Transcode memory:** the JVM gets 15 % of the transcode pod's memory, because the limit also covers FFmpeg.
  - **Database connections:** at most 20 at once across all pods (web 5 each, sftp 3, transcode 3, maintenance 2, migrate 2).
  - **Migrations:** the `migrate` hook uses only the platform's Secrets and the default ServiceAccount, because the chart's own resources don't exist yet at pre-install.
  - **Timeouts:** HTTPRoute `timeouts.request` (60 s, uploads 300 s) replaces the planned `BackendTrafficPolicy`.
  - **OpenLDAP administration:** the directory's rootDN is the container user's local-socket identity (`peercred`), so there is no admin password and `LDAP_ADMIN_PASSWORD` was dropped from `60-secrets.sh`. `ldap-user.sh` works through `kubectl exec` and sends passwords base64-encoded on stdin, which slapd's ppolicy hashes.
  - **Group checks:** the memberof and refint overlays supply `memberOf`, which YouStream reads for `requiredGroup` and `adminGroup`.
  - **Service account password:** set from the Secret by `slapmodify` on every start, so rotating the Secret only needs a pod restart.
  - **Backups:** a sidecar runs `slapcat` daily to `backups/openldap` on the media volume and keeps 14 days.
  - **Checks:** `helm lint` passes for both charts. The rendered DOKS and EKS output and the Phase 9 platform manifests (44 resources) pass `kubeconform -strict` against Kubernetes 1.34 and the Gateway API, Envoy Gateway and cert-manager CRD schemas.
  - **Not yet run:** the OpenLDAP image. There is no Docker on the development machine, so Phase 11's CI builds it and runs a smoke test.
- **Phase 11** wrote `.github/workflows/` and `deploy/README.md`:
  - **`ci.yml`:** runs the Gradle build with a PostgreSQL 17 service container and Ubuntu's FFmpeg, and fails if any JUnit test was skipped.
  - **OpenLDAP check in CI:** a second job builds the image. It runs `ldap-user.sh` through a `kubectl` shim that forwards to `docker exec`, then checks four things: `memberOf`, hashed passwords, anonymous binds refused, and service-password rotation on restart. Finally it starts the server against the directory: the LDAP sign-in returns 200, a wrong password 401, and a disabled user is refused.
  - **`deploy.yml`:** runs after a green CI on `master` and builds the commit that CI tested (`head_sha`). It pushes `youstream:<sha>` and `youstream-openldap:<hash of deploy/openldap>` (the second only when missing), runs `helm upgrade --atomic` for both releases in the `production` environment, then checks `/api/health` through the public URL.
  - **`backup.yml`:** a daily VolumeSnapshot of the NFS server's volume, kept for 7 days.
  - **Deploy account:** `deploy/platform/70-deployer.sh` creates the ServiceAccount, with full rights only in namespace `youstream` (no RBAC rights there) and snapshot rights only in `nfs`. It writes its kubeconfig and checks that the account cannot create ClusterRoleBindings.
  - **Checks:** `actionlint` and `kubeconform` pass, and the script ran against stub `kubectl`. None of the workflows has run on GitHub yet.
- **Transcode recovery in Kubernetes**: a restarted container keeps its pod name, so its jobs are requeued at start; a job of a deleted pod is requeued by the stale check after `media.staleJobAfter` (6 h).
- **Deferred** (not built): thumbnail sprite + WebVTT scrub previews (needs custom player controls), parallel chunk uploads (needs out-of-order chunk tracking), profile pictures and channel banners, notifications, moderation beyond admins deleting content.

## 16. Production deployment (Kubernetes)

Target: `https://youstream.paulsdavenport3.com`, deployed with Helm to the existing DigitalOcean Kubernetes cluster (3 × 1 vCPU / 2 GB nodes, v1.34). The charts stay cloud-neutral, so the same release can go to EKS or any other cluster; everything provider-specific lives in a per-cluster values file and in `deploy/platform/`.

### Topology

```
                      ┌──────────────── namespace youstream ───────────────────────────────┐
 HTTPS :443 ─► LB ─► Envoy Gateway ─► web ×2 (default pool)  ─┐                            │
 SFTP  :22  ─► LB ─────────────────► sftp ×1 (default pool)  ─┼─► PVC youstream-media (RWX) │
                                     transcode ×1 (media pool)┘        │                    │
                                     maintenance CronJob, migrate Job  ▼                    │
                                     openldap ×1 (StatefulSet)    NFS server (namespace nfs)│
                      └──────────────────────────────┬─────────────────────────────────────┘
                                                     ▼
                                       DO Managed PostgreSQL (database youstream_prod)
```

| Workload | Kind / replicas | Node pool | Requests → limits | Notes |
|---|---|---|---|---|
| `web` | Deployment ×2, RollingUpdate | default | 250m / 640Mi → 1Gi | HTTP API + SPA + media serving; anti-affinity across nodes; PDB `minAvailable: 1` |
| `sftp` | Deployment ×1, Recreate | default | 100m / 384Mi → 768Mi | MINA SSHD; ingest runs `ffprobe` only |
| `transcode` | Deployment ×1 | `media` (label + taint `workload=media`) | 3 CPU / 5Gi → 6.5Gi | `media.workers = 1`, one FFmpeg using every core; grace period 60 s, running jobs are released back to the queue |
| `maintenance` | CronJob `*/15 * * * *`, `concurrencyPolicy: Forbid` | default | 100m / 256Mi | Replaces the in-process timer, so it runs once per cluster instead of once per pod |
| `migrate` | Job, Helm `pre-install,pre-upgrade` hook | default | 100m / 256Mi | Flyway; a failed migration stops the rollout before any pod changes |
| `openldap` | StatefulSet ×1, 1 Gi RWO PVC | default | 50m / 64Mi | ClusterIP only; NetworkPolicy allows only `web` and `sftp` |

### Why media stays in the cluster on a shared filesystem

`web` (browser chunk uploads, media serving), `sftp` (inboxes) and `transcode` (FFmpeg output) all read and write the same files, so they need a **ReadWriteMany** volume. DigitalOcean block storage is ReadWriteOnce, so the platform runs an **in-cluster NFS server** (kubernetes-sigs `nfs-server-provisioner` / NFS-Ganesha) backed by a 100 Gi block volume and exposes it as storage class `youstream-rwx`. The chart only asks for "an RWX PVC with storage class X":

| Cluster | `persistence.storageClass` | Backing |
|---|---|---|
| DOKS | `youstream-rwx` | in-cluster NFS on `do-block-storage` (expandable) |
| EKS | `youstream-rwx` (same NFS chart on `gp3`) or `efs-sc` | EBS volume, or Amazon EFS if a managed RWX volume is preferred |

`LocalDiskStorage` keeps working unchanged: the volume is mounted at `/data` in every pod. The NFS server is a single point of failure: if its node dies, the volume reattaches on another node within a minute or two, and uploads or playback fail during that time. That is acceptable for v1 and is the trade-off for portability without Ceph/Longhorn, which don't fit 1 vCPU nodes.

### Phase 8: cluster-ready server (code)

1. **Roles.** `roles = ${?YOUSTREAM_ROLES}`, a list of `web`, `sftp`, `transcode` (default: all three, so local runs are unchanged). Every pod still serves `GET /api/health` on 8080 for probes; non-web pods serve nothing else.
2. **One-shot commands.** `youstream migrate` (Flyway only, then exit) and `youstream maintenance` (one cleanup pass, then exit). Pods start with `db.migrate = false`.
3. **Real client IPs.**
   - HTTP: `server.trustedProxyHops = 1` takes the client from the right end of `X-Forwarded-For`, which the gateway appends. Jooby's own trust-proxy mode takes the first, client-controlled entry, so it is not used. Rate limits, the login throttle and anonymous view keys depend on this.
   - SFTP: optional PROXY protocol v1/v2 (`sftp.proxyProtocol`) through SSHD's `ServerProxyAcceptor`, because a cloud load balancer hides the client address otherwise.
4. **Login throttle in PostgreSQL** (`V2__login_failures.sql`), shared by all web pods and SFTP, so spreading guesses across replicas doesn't help. The other per-route rate limits stay in memory, so their effective limit is the number of web replicas times the configured limit. The `PublicVideoCache` (30 s TTL) is also per pod; a visibility change reaches every pod within 30 s.
5. **SFTP host key from a file that may be read-only** (a Kubernetes Secret); generate it if it's missing only when the path is writable. Every cluster gets the same key, so clients don't see host-key warnings after a move.
6. **Container settings.** `JAVA_OPTS=-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError`. Read-only root filesystem with an `emptyDir` on `/tmp`. Run as UID 10001 with `fsGroup` 10001.
7. **Configuration.** The chart renders a HOCON file into a ConfigMap mounted as `conf/local.conf`. Secrets come in as environment variables from Kubernetes Secrets (`DB_PASSWORD`, `JWT_SECRET`, `ENTRA_CLIENT_SECRET`, `LDAP_BIND_PASSWORD`). Add `${?ENV}` overrides wherever one is missing.

### Phase 9: cluster platform (`deploy/platform/`, run once per cluster)

| Step | DOKS | EKS equivalent |
|---|---|---|
| Media node pool | `doctl kubernetes cluster node-pool create <cluster> --name media --size s-4vcpu-8gb --count 1 --label workload=media --taint workload=media:NoSchedule` | Managed node group (c7i.xlarge or similar) with the same label and taint |
| Gateway | Gateway API CRDs (standard channel, from Envoy Gateway's CRD chart) + Envoy Gateway (Helm, pinned). A shared `Gateway` `gateway/public`: port 80 for ACME and the HTTPS redirect, and an HTTPS listener for the host. Its Service gets a DO network load balancer with `externalTrafficPolicy: Local`, which keeps client IPs. ingress-nginx is retired (March 2026), so it is not used | Same charts; NLB with PROXY protocol v2 and a `ClientTrafficPolicy` |
| Certificates | cert-manager (Helm, pinned) with Gateway API support; `ClusterIssuer` for Let's Encrypt (staging first, then production) using HTTP-01 through the Gateway | Same |
| RWX storage | `nfs-server-provisioner` on a 100 Gi `do-block-storage` PVC, on the media node → storage class `youstream-rwx` (Retain). A two-pod job checks shared access, renames and UID 10001 | Same chart on `gp3`, or the EFS CSI driver |
| Database | Add the cluster as a trusted source of the managed database. Using `doadmin`, create database `youstream_prod` and role `youstream_prod`, with `CREATE` on schema `public`. Store the CA certificate in a Secret | RDS or another PostgreSQL 14+ |
| Entra ID | Add redirect URI `https://youstream.paulsdavenport3.com/api/auth/microsoft/callback`; create a separate production client secret | Same app registration |
| DNS | `A youstream.paulsdavenport3.com → gateway LB IP`, `A sftp.youstream.paulsdavenport3.com → SFTP LB IP` | Same names, new IPs (or CNAMEs to the AWS LB names) |
| Secrets | `kubectl create secret` for database, JWT, Entra, LDAP, SFTP host key, GHCR pull secret (only if the repository is private). External Secrets Operator can replace this later | Same |

### Phase 10: Helm charts

- **`deploy/helm/youstream`**:
  - Templates: Deployments `web`, `sftp`, `transcode`; CronJob `maintenance`; Job `migrate` (a hook); ConfigMap; PVC; Services; `HTTPRoute` (with request timeouts); NetworkPolicies; ServiceAccount (`automountServiceAccountToken: false`); PDB.
  - Timeouts: the upload route `/api/uploads/*` gets a 5-minute request timeout, because Envoy's default of 15 s is too short for 16 MB chunks on slow links.
  - SFTP: `Service` of type `LoadBalancer` maps port 22 to 2222. Its annotations come from values: on DOKS a network load balancer with `externalTrafficPolicy: Local`; on EKS an NLB with PROXY protocol and `SFTP_PROXY_PROTOCOL=true`, and the result is `sftp alice@sftp.youstream.paulsdavenport3.com`.
  - Values: `image.repository/tag`, per-role `replicas`/`resources`/`nodeSelector`/`tolerations`, `persistence.storageClass/size`, `publicUrl`, `sftp.publicHost`, `ldap.*`, `entra.*` (non-secret parts), `existingSecret` names, `gateway.parentRef`.
- **`deploy/helm/openldap`**:
  - Image: `deploy/openldap/Dockerfile` builds from `debian:trixie-slim` + `slapd` (Debian security updates, no third-party image). Bitnami's OpenLDAP image moved to a paid/legacy catalog in 2025, and `osixia/openldap` is unmaintained.
  - Bootstrap LDIF: `ou=people`, `ou=groups`, groups `youstream-users`/`youstream-admins`, read-only service account for YouStream; `ppolicy` overlay for password rules.
  - Users are managed with `deploy/openldap/ldap-user.sh add|passwd|disable|enable|admin|unadmin|delete|list` (runs `ldapmodify` through `kubectl exec`).
  - Nightly `slapcat` backup to the media volume.
  - Production sign-in: Microsoft for tenant members; OpenLDAP for everyone else. LDAP users can use SFTP passwords; Microsoft users use SSH keys.

### Phase 11: CI/CD and go-live

- **Prerequisite:** push `master` to `origin` (`vgerdev/youstream`).
- **`.github/workflows/ci.yml`** (pull requests and pushes):
  - Temurin 25, Node 22 and `apt install ffmpeg`.
  - A `postgres:16` service container supplies `db.testSchema` through a CI-only conf, so every test runs (none skipped).
  - Runs `./gradlew build`.
  - Builds both images. Starts the OpenLDAP image and checks it: the first start creates the tree, the service account binds, a user added through the local socket gets `memberOf`, and the server's LDAP sign-in works against it.
- **`.github/workflows/deploy.yml`** (push to `master`, after CI):
  - Build images with Buildx and push `ghcr.io/vgerdev/youstream:<sha>` (plus `:latest`), and `youstream-openldap`, tagged with the hash of `deploy/openldap/` so it only changes when that folder does.
  - Run `helm upgrade --install --atomic --wait --timeout 15m --set image.tag=<sha>` in the GitHub environment `production`, using a kubeconfig secret for a namespace-scoped ServiceAccount, which works the same on DOKS and EKS. Required reviewers on the environment are optional.
  - Smoke test: `curl -f https://youstream.paulsdavenport3.com/api/health`.
- **Backups:**
  - The database uses DO managed daily backups (PITR).
  - The media volume gets a daily `VolumeSnapshot` (DO CSI snapshots, AWS Backup on EKS) with 7-day retention.
  - LDAP is covered by the `slapcat` dump on the media volume.
- **Go-live checklist:**
  - Let's Encrypt staging certificate, then production.
  - Microsoft sign-in.
  - LDAP sign-in.
  - Browser upload: transcode runs on the media node, then the video plays.
  - SFTP upload with a password and with an SSH key.
  - Embed on another site.
  - Rate limit returns 429 with real client IPs in the access log.
  - `helm rollback` once.

### Monthly cost added to the existing cluster (DigitalOcean list prices)

| Item | ≈ $/month |
|---|---|
| Media node `s-4vcpu-8gb` | 48 |
| Gateway load balancer | 12 |
| SFTP load balancer | 12 (saved by putting SFTP on the gateway LB via a `TCPRoute`, which is still experimental in Gateway API) |
| 100 Gi block volume (NFS) + 1 Gi (LDAP) | 10 |
| Volume snapshots | ~1–5 |
| GHCR | 0 |
| **Total** | **~85** |

### Risks / to verify

- **Capacity of the default pool.** Two web pods, sftp, openldap, Envoy, cert-manager and the NFS server need about 1.2 CPU and 3 GB in requests on 3 × (920m / 1.5 GB) nodes, on top of what already runs there. Moving the default pool to `s-2vcpu-4gb` may be necessary; otherwise sftp, openldap and NFS can tolerate the media taint.
- NFS ownership and permissions with UID 10001 / `fsGroup`, and atomic renames over NFS. `40-storage.sh` checks both with two pods.
- **Database connections.** Small DO managed PostgreSQL plans allow roughly 20–25 connections. Phase 10 sets `DB_POOL_SIZE` per role (for example web 5, sftp 3, transcode 3, maintenance and migrate 2) instead of the default 10.
- FFmpeg in the image comes from Ubuntu noble (6.1), not the 9.0.2 used in development. The CI run in Phase 11 covers the media tests against it.
