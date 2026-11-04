# Security

Report a vulnerability privately, by email to support@usetortuga.app. Don't open an issue for it.

## Scope

- The Tortuga apps on every platform: Android, Android TV, Windows, Linux, iOS, iPadOS, macOS and tvOS.
- The sync server in `server/`, as you run it yourself.
- The hosted instance at `api.usetortuga.app`, which runs the same server image.

Addons are other people's services: report a problem in an addon to its author. A self-hosted server is its
operator's.

## What a report needs

- The platform and the version (Settings, About), or the server's commit.
- Steps that show the problem, and what an attacker gains.
- No addon URLs unless the report is about them: configured addon URLs carry keys to accounts at other services.

## What the server stores

The server can read everything a device syncs. Records arrive as JSON under their natural ids: profiles, each
profile's addons with their URLs, library items and watch progress with their Stremio ids and titles, and settings. A
profile's PIN arrives only as a salted hash. The server also keeps provider identities (issuer and subject, and a
verified email when the provider shares one), devices and sessions, each record's version, tombstone flag, size and
timestamps, and each profile's tracker connections: the service's tokens, the client ID they were issued to and the
username there.

At rest, each record's id and payload, each tracker connection's tokens, client ID and username, the Apple and GitHub
tokens kept to revoke a sign-in, and a web sign-in's result waiting for the app are sealed with AES-256-GCM under a
key derived (HKDF-SHA256) from the server's data key, a file of 32 random bytes kept outside Postgres. Each sealed
value is bound to its own row. Records are looked up by an HMAC-SHA256 of the account and the record id, so the index
reveals neither the id nor which accounts hold the same record.

The database, a dump or a backup therefore holds no addon URLs, ids, titles, tracker tokens, tracker usernames, or
Apple or GitHub tokens. It does hold account metadata as it is: identities, emails, device names, and the profile IDs
and service names of tracker connections, which is why the server's backups are encrypted with age. Whoever has both
the database and the data key can read everything, and so can the running server. Sync is not end-to-end encrypted.

[server/README.md](server/README.md) has the details, the data key's handling and the backup setup.
