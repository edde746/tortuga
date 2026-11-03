-- The account's tracker connections counter (PullResponse.trackersVersion, TrackerConnectionList.version in shared):
-- every connection added, replaced or removed moves it, so the apps list the connections only when it moved.
ALTER TABLE accounts ADD COLUMN trackers_version bigint NOT NULL DEFAULT 0;

-- Until when a token refresh claims the connection (TrackerConnections): the service is asked outside any transaction,
-- and another request for the connection meanwhile waits for that refresh instead of spending the same refresh token.
ALTER TABLE tracker_connections ADD COLUMN refreshing_until timestamptz;
