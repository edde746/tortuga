-- Web hand-offs bound to the device that started them (protocol in shared/…/sync/api/Auth.kt: HandoffStartRequest).
--
-- A row is made when the app starts the flow: a state the server issued, the SHA-256 (base64url) of the app's
-- verifier, and where the return page sends the browser back to the app. The return page fills in the provider's
-- result and the SHA-256 of the one-time proof it sends that browser back with; the app collects with both the
-- verifier and the proof. Rows from the old protocol (state = a hash the app chose) can't be collected any more.
DROP TABLE apple_handoffs;
DROP TABLE github_handoffs;

CREATE TABLE apple_handoffs (
    state              text PRIMARY KEY,
    challenge          text        NOT NULL,
    redirect_uri       text        NOT NULL,
    -- Null until the return page took Apple's post.
    proof_hash         text,
    id_token           text,
    authorization_code text,
    user_json          text,
    expires_at         timestamptz NOT NULL
);

CREATE INDEX apple_handoffs_expires ON apple_handoffs (expires_at);

CREATE TABLE github_handoffs (
    state        text PRIMARY KEY,
    challenge    text        NOT NULL,
    redirect_uri text        NOT NULL,
    -- Null until the return page took GitHub's code.
    proof_hash   text,
    code         text,
    expires_at   timestamptz NOT NULL
);

CREATE INDEX github_handoffs_expires ON github_handoffs (expires_at);
