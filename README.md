# youstream

Self-hosted video sharing: upload in the browser or over SFTP, adaptive HLS playback, channels, comments and playlists.
Java 25 / Jooby on Undertow / Apache MINA SSHD backend, SolidJS frontend. See [PLAN.md](PLAN.md) for the design.

## Run locally

Requirements: JDK 25, Node.js 22, FFmpeg on `PATH`, a PostgreSQL database.

1. Copy `conf/local.conf.example` to `conf/local.conf` and fill in the database, JWT secret and (optionally) Entra ID settings.
2. Start the development LDAP directory (users `alice`/`alice-pass` (admin), `bob`/`bob-pass`, `carol`/`carol-pass`):

   ```bash
   ./gradlew :youstream-devldap:run
   ```

3. Build and start YouStream from the repository root (it reads `conf/`):

   ```bash
   ./gradlew installDist
   youstream-server/build/install/youstream/bin/youstream
   ```

4. Open http://localhost:8080.

For frontend work, run `npm run dev` in `youstream-web/` (Vite on http://localhost:3000, proxying `/api` to :8080) and set
`auth.frontendUrl = "http://localhost:3000"` in `conf/local.conf` so "Sign in with Microsoft" returns to Vite.

## Tests

```bash
./gradlew build
```

Database tests run against `db.testSchema` of the database in `conf/local.conf` (the schema is wiped on each run) and are
skipped when it is not configured.
