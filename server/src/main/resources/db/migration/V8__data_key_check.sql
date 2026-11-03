-- The data key's check (crypto/DataKeyCheck.kt): one row, a value sealed with the data key the database's sealed
-- values were written with. The server opens it at start and doesn't start with a key that can't.
-- NULL until the first start after this migration, which first opens the newest record with its key and then stores
-- the check, so a database from before the check refuses another key too. No row: the old key is lost for good, and
-- the next start stores its own key's check.
CREATE TABLE data_key (
    id           boolean PRIMARY KEY DEFAULT true CHECK (id),
    sealed_check bytea
);
INSERT INTO data_key DEFAULT VALUES;
