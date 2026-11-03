-- Tracker connections (protocol in shared/…/trackers/connections/TrackerConnectionsApi.kt):
-- a profile's sign-in to a tracker service (Trakt, Simkl, MDBList, MyAnimeList, AniList), held for every device of the
-- account. The server is the only one that refreshes these tokens.
--
-- The service's tokens, their expiry, the client id they were issued to and the account's username at the service are
-- sealed together in `sealed_session` with AES-256-GCM under the data key (as records' payloads, V1), bound to the
-- row's account, profile id and service, so this database, a dump or a backup holds none of them. The profile id and
-- the service name are stored as they are.

CREATE TABLE tracker_connections (
    account_id     uuid        NOT NULL REFERENCES accounts (id) ON DELETE CASCADE,
    -- The synced profile's id, as in its record id `profile/<id>`.
    profile_id     text        NOT NULL,
    -- A TrackerService id: trakt, simkl, mdblist, mal or anilist.
    service        text        NOT NULL,
    -- JSON of the client id and the session (access token, refresh token, expiry, username), sealed.
    sealed_session bytea       NOT NULL,
    -- When this sign-in was stored, to the millisecond; it names the sign-in to the apps, so a reconnect moves it
    -- (always forward, also when the clock went back).
    connected_at   timestamptz NOT NULL,
    PRIMARY KEY (account_id, profile_id, service)
);
