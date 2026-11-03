-- Refresh-token reuse ends the session (Sessions.refresh, RFC 9700 §4.14.2). The previous refresh token is now usable
-- once, until previous_refresh_valid_until (a grace period after its rotation), for a device whose refresh response
-- was lost; NULL once that retry is spent. Two more spent tokens are kept: the one before the previous
-- (retired_refresh_hash) and the current token a retry replaced (replaced_refresh_hash). Presenting either, or the
-- previous token outside its retry, ends the session.
-- Rows from before have no grace left: their previous token now ends the session, their current one works as before.
ALTER TABLE sessions ADD COLUMN previous_refresh_valid_until timestamptz;
ALTER TABLE sessions ADD COLUMN retired_refresh_hash bytea UNIQUE;
ALTER TABLE sessions ADD COLUMN replaced_refresh_hash bytea UNIQUE;
