-- YouStream initial schema.
-- Enum-like columns are text + CHECK (simple binding from jcabi-jdbc); internal keys are bigserial,
-- public URLs use an 11-character base62 public_id.

-- ---------------------------------------------------------------- users & auth

CREATE TABLE users (
    id               bigserial PRIMARY KEY,
    username         text        NOT NULL,
    display_name     text        NOT NULL,
    email            text,
    handle           text        NOT NULL,
    avatar_path      text,
    banner_path      text,
    bio              text        NOT NULL DEFAULT '',
    is_admin         boolean     NOT NULL DEFAULT false,
    subscriber_count bigint      NOT NULL DEFAULT 0,
    created_at       timestamptz NOT NULL DEFAULT now(),
    last_login_at    timestamptz,
    search           tsvector GENERATED ALWAYS AS (
                         setweight(to_tsvector('simple', handle), 'A') ||
                         setweight(to_tsvector('simple', display_name), 'A')) STORED,
    CONSTRAINT users_username_chk CHECK (username ~ '^[a-z0-9][a-z0-9._-]{1,63}$'),
    CONSTRAINT users_handle_chk CHECK (handle ~ '^[a-z0-9][a-z0-9._-]{2,29}$'),
    CONSTRAINT users_bio_len_chk CHECK (length(bio) <= 1000)
);
CREATE UNIQUE INDEX users_username_uq ON users (username);
CREATE UNIQUE INDEX users_handle_uq ON users (handle);
CREATE INDEX users_search_idx ON users USING gin (search);

-- one row per external login (LDAP entryUUID/objectGUID, Entra oid); a user may have several
CREATE TABLE user_identities (
    provider      text        NOT NULL CHECK (provider IN ('LDAP', 'ENTRA')),
    subject       text        NOT NULL,
    user_id       bigint      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    created_at    timestamptz NOT NULL DEFAULT now(),
    last_login_at timestamptz,
    PRIMARY KEY (provider, subject)
);
CREATE INDEX user_identities_user_idx ON user_identities (user_id);

CREATE TABLE refresh_tokens (
    id          bigserial PRIMARY KEY,
    user_id     bigint      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash  text        NOT NULL,
    user_agent  text,
    created_at  timestamptz NOT NULL DEFAULT now(),
    expires_at  timestamptz NOT NULL,
    revoked_at  timestamptz,
    replaced_by bigint REFERENCES refresh_tokens (id) ON DELETE SET NULL
);
CREATE UNIQUE INDEX refresh_tokens_hash_uq ON refresh_tokens (token_hash);
CREATE INDEX refresh_tokens_user_idx ON refresh_tokens (user_id);

CREATE TABLE ssh_keys (
    id           bigserial PRIMARY KEY,
    user_id      bigint      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name         text        NOT NULL CHECK (length(name) BETWEEN 1 AND 100),
    key_type     text        NOT NULL,
    fingerprint  text        NOT NULL,
    public_key   text        NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    last_used_at timestamptz
);
CREATE UNIQUE INDEX ssh_keys_fingerprint_uq ON ssh_keys (fingerprint);
CREATE INDEX ssh_keys_user_idx ON ssh_keys (user_id);

-- ---------------------------------------------------------------- videos

CREATE TABLE videos (
    id                bigserial PRIMARY KEY,
    public_id         text        NOT NULL CHECK (public_id ~ '^[0-9A-Za-z]{11}$'),
    owner_id          bigint      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    title             text        NOT NULL CHECK (length(title) BETWEEN 1 AND 100),
    description       text        NOT NULL DEFAULT '' CHECK (length(description) <= 5000),
    visibility        text        NOT NULL DEFAULT 'PRIVATE' CHECK (visibility IN ('PUBLIC', 'UNLISTED', 'PRIVATE')),
    status            text        NOT NULL DEFAULT 'UPLOADED' CHECK (status IN ('UPLOADING', 'UPLOADED', 'PROCESSING', 'READY', 'FAILED')),
    source            text        NOT NULL CHECK (source IN ('WEB', 'SFTP')),
    original_filename text        NOT NULL,
    original_path     text,
    size_bytes        bigint      NOT NULL DEFAULT 0,
    duration_ms       bigint,
    width             integer,
    height            integer,
    error             text,
    view_count        bigint      NOT NULL DEFAULT 0,
    like_count        bigint      NOT NULL DEFAULT 0,
    dislike_count     bigint      NOT NULL DEFAULT 0,
    comment_count     bigint      NOT NULL DEFAULT 0,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    published_at      timestamptz,
    search            tsvector GENERATED ALWAYS AS (
                          setweight(to_tsvector('english', title), 'A') ||
                          setweight(to_tsvector('english', description), 'B')) STORED
);
CREATE UNIQUE INDEX videos_public_id_uq ON videos (public_id);
CREATE INDEX videos_owner_idx ON videos (owner_id, created_at DESC, id DESC);
CREATE INDEX videos_public_feed_idx ON videos (published_at DESC, id DESC)
    WHERE visibility = 'PUBLIC' AND status = 'READY';
CREATE INDEX videos_search_idx ON videos USING gin (search);

CREATE TABLE video_renditions (
    video_id      bigint  NOT NULL REFERENCES videos (id) ON DELETE CASCADE,
    height        integer NOT NULL,
    width         integer NOT NULL,
    bitrate_kbps  integer NOT NULL,
    playlist_path text    NOT NULL,
    PRIMARY KEY (video_id, height)
);

CREATE TABLE transcode_jobs (
    id           bigserial PRIMARY KEY,
    video_id     bigint      NOT NULL REFERENCES videos (id) ON DELETE CASCADE,
    status       text        NOT NULL DEFAULT 'QUEUED' CHECK (status IN ('QUEUED', 'RUNNING', 'DONE', 'FAILED')),
    progress     real        NOT NULL DEFAULT 0 CHECK (progress BETWEEN 0 AND 1),
    attempts     integer     NOT NULL DEFAULT 0,
    max_attempts integer     NOT NULL DEFAULT 3,
    locked_by    text,
    locked_at    timestamptz,
    error        text,
    run_after    timestamptz NOT NULL DEFAULT now(),
    created_at   timestamptz NOT NULL DEFAULT now(),
    finished_at  timestamptz
);
CREATE INDEX transcode_jobs_queue_idx ON transcode_jobs (run_after, id) WHERE status = 'QUEUED';
CREATE INDEX transcode_jobs_video_idx ON transcode_jobs (video_id);

CREATE TABLE upload_sessions (
    id             uuid PRIMARY KEY,
    user_id        bigint      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    filename       text        NOT NULL,
    total_bytes    bigint      NOT NULL CHECK (total_bytes > 0),
    received_bytes bigint      NOT NULL DEFAULT 0,
    created_at     timestamptz NOT NULL DEFAULT now(),
    expires_at     timestamptz NOT NULL
);
CREATE INDEX upload_sessions_user_idx ON upload_sessions (user_id);

-- ---------------------------------------------------------------- social

CREATE TABLE comments (
    id          bigserial PRIMARY KEY,
    video_id    bigint      NOT NULL REFERENCES videos (id) ON DELETE CASCADE,
    user_id     bigint      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    parent_id   bigint REFERENCES comments (id) ON DELETE CASCADE,
    body        text        NOT NULL CHECK (length(body) BETWEEN 1 AND 10000),
    reply_count integer     NOT NULL DEFAULT 0,
    created_at  timestamptz NOT NULL DEFAULT now(),
    edited_at   timestamptz,
    deleted_at  timestamptz
);
CREATE INDEX comments_thread_idx ON comments (video_id, parent_id, created_at DESC, id DESC);

CREATE TABLE video_reactions (
    video_id   bigint      NOT NULL REFERENCES videos (id) ON DELETE CASCADE,
    user_id    bigint      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    value      smallint    NOT NULL CHECK (value IN (-1, 1)),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (video_id, user_id)
);

CREATE TABLE subscriptions (
    subscriber_id   bigint      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    channel_user_id bigint      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    created_at      timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (subscriber_id, channel_user_id),
    CHECK (subscriber_id <> channel_user_id)
);
CREATE INDEX subscriptions_channel_idx ON subscriptions (channel_user_id);

CREATE TABLE playlists (
    id          bigserial PRIMARY KEY,
    public_id   text        NOT NULL CHECK (public_id ~ '^[0-9A-Za-z]{11}$'),
    owner_id    bigint      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    title       text        NOT NULL CHECK (length(title) BETWEEN 1 AND 150),
    description text        NOT NULL DEFAULT '' CHECK (length(description) <= 5000),
    visibility  text        NOT NULL DEFAULT 'PRIVATE' CHECK (visibility IN ('PUBLIC', 'UNLISTED', 'PRIVATE')),
    item_count  integer     NOT NULL DEFAULT 0,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    search      tsvector GENERATED ALWAYS AS (
                    setweight(to_tsvector('english', title), 'A') ||
                    setweight(to_tsvector('english', description), 'B')) STORED
);
CREATE UNIQUE INDEX playlists_public_id_uq ON playlists (public_id);
CREATE INDEX playlists_owner_idx ON playlists (owner_id);
CREATE INDEX playlists_search_idx ON playlists USING gin (search);

CREATE TABLE playlist_items (
    playlist_id bigint      NOT NULL REFERENCES playlists (id) ON DELETE CASCADE,
    video_id    bigint      NOT NULL REFERENCES videos (id) ON DELETE CASCADE,
    position    integer     NOT NULL,
    added_at    timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (playlist_id, video_id)
);
CREATE INDEX playlist_items_order_idx ON playlist_items (playlist_id, position);

-- one row per viewer per video per day, so view_count is deduplicated
CREATE TABLE video_views (
    video_id   bigint NOT NULL REFERENCES videos (id) ON DELETE CASCADE,
    viewer_key text   NOT NULL,
    viewed_on  date   NOT NULL DEFAULT current_date,
    PRIMARY KEY (video_id, viewer_key, viewed_on)
);
