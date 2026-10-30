# YouStream

Self-hosted video sharing. Upload in the browser (resumable) or over SFTP, adaptive HLS playback, channels,
subscriptions, comments, likes, search, playlists, share links and embeds. Sign in with a directory (LDAP)
account or with Microsoft Entra ID.

Java 25 · Jooby 4 on Undertow · Apache MINA SSHD · PostgreSQL (jcabi-jdbc, HikariCP, Flyway) · FFmpeg ·
SolidJS + Vite + Tailwind. The design and its history are in [PLAN.md](PLAN.md).

## Modules

| Project | Contents |
|---|---|
| `youstream-common` | Domain models (Lombok), storage abstraction (`LocalDiskStorage`), public ids |
| `youstream-db` | Connection pool, Flyway migrations, repositories, `TestDatabase` fixture |
| `youstream-auth` | LDAP and Entra ID sign-in, JWT/refresh tokens, playback tokens, SSH keys, login throttle |
| `youstream-devldap` | Embedded LDAP directory with test users, for development and tests |
| `youstream-media` | ffprobe/FFmpeg, HLS transcoding, job queue workers, ingest, quotas, maintenance jobs |
| `youstream-sftp` | Upload-only SFTP server |
| `youstream-api` | HTTP API, media serving, security headers, rate limits |
| `youstream-web` | SolidJS single-page app (built by Gradle, served by the server) |
| `youstream-server` | `main()`: reads config and starts everything |

## Run locally

Requirements: JDK 25, Node.js 22, FFmpeg on `PATH`, PostgreSQL 14+.

1. `cp conf/local.conf.example conf/local.conf` and set the database, a JWT secret (32+ bytes) and optionally Entra ID.
   The database user needs to own its schema; Flyway creates the tables on first start.
2. Start the development directory (users `alice`/`alice-pass` (admin), `bob`/`bob-pass`, `carol`/`carol-pass`):

   ```bash
   ./gradlew :youstream-devldap:run
   ```

3. Build and start YouStream from the repository root (it reads `conf/`):

   ```bash
   ./gradlew installDist
   youstream-server/build/install/youstream/bin/youstream
   ```

4. Open http://localhost:8080 and sign in. SFTP listens on port 2222.

Frontend work: `npm run dev` in `youstream-web/` (Vite on :3000, proxying `/api`, `/media`, `/thumbs` to :8080).
Set `auth.frontendUrl = "http://localhost:3000"` so "Sign in with Microsoft" returns to Vite.

## Configuration

Defaults are in [conf/application.conf](conf/application.conf); put secrets in `conf/local.conf` (git-ignored) or
environment variables.

| Setting | Env | Default | Meaning |
|---|---|---|---|
| `roles` | `YOUSTREAM_ROLES` | web, sftp, transcode | What this process runs (comma-separated in the env var); see [Process roles](#process-roles-and-commands) |
| `server.port` | `HTTP_PORT` | 8080 | HTTP port (every role answers `/api/health` here) |
| `server.trustedProxyHops` | `TRUSTED_PROXY_HOPS` | 0 | Reverse proxies whose `X-Forwarded-For` entries are trusted (1 behind a gateway). Only behind a proxy: otherwise clients could claim any address |
| `app.publicUrl` | `PUBLIC_URL` | http://localhost:8080 | Public address, used in embed/oEmbed links and SFTP status files |
| `db.url`, `db.user`, `db.password` | `DB_URL`, `DB_USER`, `DB_PASSWORD` | – | PostgreSQL (JDBC URL; add `sslmode=verify-full&sslrootcert=...` for TLS) |
| `db.poolSize`, `db.migrate` | `DB_POOL_SIZE`, `DB_MIGRATE` | 10, true | Connections per process; apply migrations on start |
| `jwt.secret` | `JWT_SECRET` | – | Signing key for access, login-state and playback tokens (32+ bytes) |
| `jwt.accessTtl`, `jwt.refreshTtl` | | 15m, 30d | Token lifetimes |
| `auth.secureCookies` | | true | `Secure` flag on cookies (browsers accept it on http://localhost) |
| `ldap.*` | `LDAP_ENABLED`, `LDAP_URL`, `LDAP_BASE_DN`, `LDAP_BIND_DN`, `LDAP_BIND_PASSWORD`, `LDAP_REQUIRED_GROUP`, `LDAP_ADMIN_GROUP` | dev directory | Directory login; `mode = search` (any LDAP) or `upn` (Active Directory); `requiredGroup`, `adminGroup` |
| `entra.*` | `ENTRA_ENABLED`, `ENTRA_TENANT_ID`, `ENTRA_CLIENT_ID`, `ENTRA_CLIENT_SECRET`, `ENTRA_REDIRECT_URI` | disabled | "Sign in with Microsoft" |
| `storage.root` | `STORAGE_ROOT` | ./data | Media, SFTP inboxes, SSH host key |
| `upload.maxBytes` | | 10g | Largest single upload |
| `upload.quotaBytes` | `UPLOAD_QUOTA` | 50g | Per-user storage for originals (0 = unlimited) |
| `sftp.enabled`, `sftp.port`, `sftp.publicHost` | `SFTP_PORT`, `SFTP_PUBLIC_HOST` | true, 2222, localhost | SFTP server and the host shown to users |
| `sftp.publicPort` | `SFTP_PUBLIC_PORT` | 0 (= `sftp.port`) | Port shown to users when a load balancer maps another one (22) |
| `sftp.hostKey` | `SFTP_HOST_KEY` | `<storage.root>/ssh/host_key` | Host key; created when missing, may be a read-only mounted secret (`youstream hostkey` creates one) |
| `sftp.proxyProtocol` | `SFTP_PROXY_PROTOCOL` | false | Expect a PROXY protocol v1/v2 header from the load balancer, so logins show real client IPs |
| `media.ffmpeg`, `media.ffprobe` | `FFMPEG`, `FFPROBE` | on PATH | Executables |
| `media.workers`, `media.preset` | `MEDIA_WORKERS`, `MEDIA_PRESET` | 2, veryfast | Parallel transcodes; x264 speed/size trade-off |
| `media.playbackTokenTtl` | | 6h | Lifetime of signed media links for unlisted/private videos |
| `maintenance.every` | `MAINTENANCE_EVERY` | 15m | Cleanup interval; 0 = not in this process (run `youstream maintenance` on a schedule instead) |
| logging | `LOG_LEVEL`, `ACCESS_LOG` | INFO, INFO | `LOG_LEVEL=DEBUG` shows FFmpeg commands; `ACCESS_LOG=OFF` silences request logs |

### Microsoft Entra ID

Register a single-tenant app (platform **Web**) with redirect URI `<publicUrl>/api/auth/microsoft/callback`, create a
client secret, add the optional ID-token claims `email` and `preferred_username`, and grant admin consent for
`User.Read`. Members of the app role `YouStream.Admin` become YouStream admins. Put the tenant id, client id and
secret value in `entra { ... }` with `enabled = true`.

### SFTP

Users connect as their YouStream username (shown on the Account page) with their directory password or a registered
SSH key, and land in a private folder. Every finished file becomes a private video; `name.json` uploaded first sets
`title`, `description` and `visibility`. A `name.status` file reports `ACCEPTED <id>` or `REJECTED <reason>`.
Compatible with OpenSSH `sftp`, FileZilla, WinSCP and Cyberduck (temporary-name uploads are picked up after the rename).

### Process roles and commands

One process runs everything by default. For a cluster, run the same image several times with one role each; they
share the database and the `storage.root` volume (which must then be shared storage, e.g. an NFS/RWX volume):

| Role | Runs |
|---|---|
| `web` | HTTP API, the web app and media serving |
| `sftp` | SFTP upload server (ingest only probes files) |
| `transcode` | FFmpeg workers that take jobs from the database queue |

`bin/youstream [command]`: `serve` (default), `migrate` (apply migrations and exit), `maintenance` (one cleanup pass
and exit), `hostkey` (create the SFTP host key if missing and print its fingerprint). Password-login failures are
counted in the database, so the login throttle holds across processes; the other rate limits are per process.

## Production

- **Container**: `docker build -t youstream .` (see the header of the [Dockerfile](Dockerfile)). Mount a volume on
  `/data` and provide `conf/local.conf` or the environment variables above.
- **TLS**: put a reverse proxy (nginx, Caddy, Traefik) in front of port 8080 and set `app.publicUrl` to the https
  address. Forward port 2222 for SFTP directly (it is SSH, not HTTP). Set `ldap.url` to `ldaps://`.
- **Scaling**: one process runs the web server, the SFTP server and the transcode workers, or split them with
  `roles` (above). The job queue lives in PostgreSQL, so transcode capacity grows with `media.workers` per process
  and with more `transcode` processes (FFmpeg is CPU-bound). The Kubernetes plan is in [PLAN.md §16](PLAN.md); the cluster setup is in
  [deploy/platform](deploy/platform/README.md) and the Helm charts in [deploy/helm](deploy/helm/README.md).
- **Backups**: the database and the `storage.root` directory (originals in `originals/`; `hls/` and `thumbs/` can be
  regenerated by re-transcoding).
- **Limits**: per-caller rate limits on comments, reactions, uploads, SSH keys, search and other writes (429 with
  `Retry-After`), kept in memory per process; sign-in attempts are throttled separately, in the database.

## Tests

```bash
./gradlew build
```

Database tests use `db.testSchema` in `conf/local.conf` (the schema is wiped on each run) and are skipped when it is not
configured; media tests are skipped when FFmpeg is not found (`FFMPEG`/`FFPROBE` or `PATH`).
