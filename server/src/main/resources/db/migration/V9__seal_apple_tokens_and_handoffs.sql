-- Apple's refresh tokens and what the web sign-ins' return pages hold for the app, sealed with AES-256-GCM under the
-- data key as GitHub tokens (V4) and tracker connections (V2) are, so this database, a dump or a backup holds none of
-- them.
--
-- An Apple identity's refresh token now goes to sealed_apple_refresh_token, bound to the row's account and identity id.
-- SQL can't seal: the server's first start after this migration, once the data key's check has accepted its key, seals
-- each token still in apple_refresh_token into the new column and empties the old one (sealPlaintextAppleTokens in
-- service/Accounts.kt). Nothing writes apple_refresh_token any more.
ALTER TABLE identities ADD COLUMN sealed_apple_refresh_token bytea;

-- A web sign-in's result, sealed and bound to its flow's state: Apple's ID token, code and user JSON together, GitHub's
-- code. A result waiting in plain text is deleted: that sign-in starts again. A flow that has none yet goes on.
DELETE FROM apple_handoffs WHERE proof_hash IS NOT NULL;
ALTER TABLE apple_handoffs
    DROP COLUMN id_token,
    DROP COLUMN authorization_code,
    DROP COLUMN user_json,
    ADD COLUMN sealed_result bytea;

DELETE FROM github_handoffs WHERE proof_hash IS NOT NULL;
ALTER TABLE github_handoffs
    DROP COLUMN code,
    ADD COLUMN sealed_code bytea;
