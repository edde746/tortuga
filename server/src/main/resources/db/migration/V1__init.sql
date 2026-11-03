-- Tortuga sync server schema.
--
-- The server reads synced records but stores them encrypted at rest under its data key (TORTUGA_DATA_KEY_FILE), a
-- file outside this database and its backups (server/README.md). Each record's id and payload are sealed with
-- AES-256-GCM under a key derived from the data key, bound to the row's account and index, and records are found by
-- `records.id_index`, a keyed HMAC of (account, record id), never by the id itself. So this database, a dump or a
-- backup holds none of an account's addon URLs, ids or titles. Identities, devices and sessions are stored as they
-- are. Access and refresh tokens and device-link secrets are stored as SHA-256 hashes.

CREATE TABLE accounts (
    id           uuid PRIMARY KEY,
    created_at   timestamptz NOT NULL,
    -- Monotonic per-account counter; each accepted record write takes the next value. Never goes back.
    sync_version bigint      NOT NULL DEFAULT 0,
    -- Sum of records.payload_size, for the quota.
    stored_bytes bigint      NOT NULL DEFAULT 0
);

-- Provider identities (issuer + subject), never keyed by email.
CREATE TABLE identities (
    id                  uuid PRIMARY KEY,
    account_id          uuid        NOT NULL REFERENCES accounts (id) ON DELETE CASCADE,
    provider            text        NOT NULL,
    issuer              text        NOT NULL,
    subject             text        NOT NULL,
    -- Only when the provider marks it verified.
    email               text,
    -- Sign in with Apple refresh token and the client id it was issued to, kept to revoke on deletion (TN3194).
    apple_refresh_token text,
    apple_client_id     text,
    created_at          timestamptz NOT NULL,
    last_used_at        timestamptz NOT NULL,
    UNIQUE (issuer, subject)
);
CREATE INDEX identities_account ON identities (account_id);

-- One session per device.
CREATE TABLE sessions (
    id                    uuid PRIMARY KEY,
    account_id            uuid        NOT NULL REFERENCES accounts (id) ON DELETE CASCADE,
    device_name           text        NOT NULL,
    platform              text        NOT NULL,
    access_hash           bytea       NOT NULL UNIQUE,
    access_expires_at     timestamptz NOT NULL,
    refresh_hash          bytea       NOT NULL UNIQUE,
    -- The refresh token before the last rotation, valid until the current one is first used.
    previous_refresh_hash bytea UNIQUE,
    refresh_expires_at    timestamptz NOT NULL,
    created_at            timestamptz NOT NULL,
    last_seen_at          timestamptz NOT NULL
);
CREATE INDEX sessions_account ON sessions (account_id);

-- Synced records, sealed at rest (see the top).
CREATE TABLE records (
    account_id       uuid        NOT NULL REFERENCES accounts (id) ON DELETE CASCADE,
    -- HMAC-SHA256 of (account id, record id) under the index key: the record's lookup key.
    id_index         bytea       NOT NULL,
    -- The record id, sealed.
    sealed_record_id bytea       NOT NULL,
    version          bigint      NOT NULL,
    deleted          boolean     NOT NULL,
    -- The payload's compact JSON, sealed.
    sealed_payload   bytea       NOT NULL,
    -- Bytes of the payload's compact JSON, for the quota.
    payload_size     integer     NOT NULL,
    updated_at       timestamptz NOT NULL,
    PRIMARY KEY (account_id, id_index)
);
CREATE INDEX records_account_version ON records (account_id, version);

-- Device links: a signed-out device asks to sign in and a signed-in device answers. One row per link, deleted once
-- the new device collects the answer, or once expired.
CREATE TABLE device_links (
    id                  uuid PRIMARY KEY,
    -- The code the new device shows; unique among stored links.
    code                text        NOT NULL UNIQUE,
    -- SHA-256 of the new device's secret: only that device collects the answer.
    secret_hash         bytea       NOT NULL,
    device_name         text        NOT NULL,
    platform            text        NOT NULL,
    status              text        NOT NULL CHECK (status IN ('pending', 'approved', 'rejected')),
    -- Set on approval: the account the new device's session opens on.
    approved_account_id uuid REFERENCES accounts (id) ON DELETE CASCADE,
    created_at          timestamptz NOT NULL,
    expires_at          timestamptz NOT NULL,
    CHECK ((status = 'approved') = (approved_account_id IS NOT NULL))
);

-- Single-use sign-in nonces.
CREATE TABLE auth_nonces (
    nonce      text PRIMARY KEY,
    expires_at timestamptz NOT NULL
);

-- SHA-256 of every accepted ID token until it expires, so each signs in once. Linked to no account.
CREATE TABLE used_id_tokens (
    token_hash bytea PRIMARY KEY,
    expires_at timestamptz NOT NULL
);

-- Sign in with Apple web-flow results waiting for the app, keyed by state = SHA-256 of the app's secret.
CREATE TABLE apple_handoffs (
    state              text PRIMARY KEY,
    id_token           text        NOT NULL,
    authorization_code text,
    user_json          text,
    expires_at         timestamptz NOT NULL
);

CREATE INDEX device_links_expires ON device_links (expires_at);
CREATE INDEX auth_nonces_expires ON auth_nonces (expires_at);
CREATE INDEX used_id_tokens_expires ON used_id_tokens (expires_at);
CREATE INDEX apple_handoffs_expires ON apple_handoffs (expires_at);
CREATE INDEX sessions_refresh_expires ON sessions (refresh_expires_at);
