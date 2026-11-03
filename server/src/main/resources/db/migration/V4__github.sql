-- Sign in with GitHub (protocol in shared/…/sync/api/Auth.kt: SignInRequest, GitHubHandoffRequest).
--
-- A GitHub identity's OAuth token from its newest sign-in, the one GitHub issued when the server reset the app's token,
-- kept to delete the grant when the identity is unlinked or the account deleted. Sealed with AES-256-GCM under the data
-- key (as tracker connections, V2), bound to the row's account and identity id, so this database, a dump or a backup
-- holds no GitHub token. Null for the other providers.
ALTER TABLE identities ADD COLUMN github_token bytea;

-- GitHub web-flow results waiting for the app, keyed by state = SHA-256 of the app's secret: GitHub's authorization
-- code, or null when the user declined at GitHub. The server exchanges the code when the app collects it.
CREATE TABLE github_handoffs (
    state      text PRIMARY KEY,
    code       text,
    expires_at timestamptz NOT NULL
);

CREATE INDEX github_handoffs_expires ON github_handoffs (expires_at);
