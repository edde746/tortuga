-- The account quota now counts each record's sealed id and a fixed 256 bytes for its row (SyncRecords.ROW_BYTES)
-- beside its payload: counting payloads alone let one account store records with empty payloads and long ids without
-- end. accounts.stored_bytes is now the sum, over the account's records, of
-- payload_size + octet_length(sealed_record_id) + 256; this recounts every account's total that way. An account the
-- recount puts over its quota keeps its records; a push that would grow it further is refused.
UPDATE accounts a
SET stored_bytes = coalesce(
    (SELECT sum(r.payload_size + octet_length(r.sealed_record_id) + 256) FROM records r WHERE r.account_id = a.id),
    0
);
