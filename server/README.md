# Tortuga sync server

Accounts, per-device sessions, device links and sync for the Tortuga apps, and the account's tracker
connections. Kotlin (Ktor on Netty) and Postgres, shipped as one Docker image. The hosted instance and self-hosted ones
run the same image. The sync protocol is specified in the KDoc of
`shared/src/commonMain/kotlin/com/edde746/tortuga/sync/api/`.

Watch together doesn't go through this server: the apps run it on Open Watch Party's relays. The
watch-together relay that older app versions used is gone: `/v1/watch/relay` and `/v1/watch/health` answer 404, and
`/v2/info` has no `watchRelayProtocol`.

The engine is Netty, not Ktor's CIO: in Ktor 3.6.0, CIO's socket writer keeps a thread spinning on every socket whose
client stops reading once the kernel's send buffer is full, and cancelling the call doesn't stop it
([KTOR-9959](https://youtrack.jetbrains.com/issue/KTOR-9959), fixed on Ktor's `release/3.x` by PR #5919 on 9 Oct 2026,
in no release yet).

## What the server sees and stores

Everything a device syncs, the server can read: records arrive as readable JSON under their natural ids (a profile,
its addons with their URLs, library items and watch progress with their Stremio ids and titles, settings). A
profile's PIN arrives only as the salted hash the apps keep in the profile record. The server also keeps provider
identities (issuer and subject, plus a verified email if the provider shares one), devices and sessions, per record a
version, a tombstone flag, its size and timestamps, and per profile its tracker connections: the service's tokens,
the client ID they were issued to and the username there ([Tracker connections](#tracker-connections)).

At rest, each record's id and payload, each tracker connection's tokens, client ID and username, the Apple and GitHub
tokens kept to revoke a sign-in, and a web sign-in's result waiting for the app are encrypted with the server's
**data key**, a file of 32 random bytes kept outside Postgres:

- the id and the payload are sealed with AES-256-GCM under a key derived from the data key (HKDF-SHA256), with a
  random nonce per write and the row's account and index as associated data, so a sealed value opens only in its own
  row; a tracker connection is sealed the same way, bound to its account, profile ID and service, an Apple or GitHub
  token bound to its account and identity, and a web sign-in's result bound to its flow's `state`;
- records are found by an HMAC-SHA256 of (account, record id) under a second derived key, never by the id itself, so
  the index reveals neither the id nor which accounts hold the same record.

So the database, a `pg_dump` or a backup holds none of an account's addon URLs, ids or titles, nor its tracker tokens
and usernames, nor its Apple and GitHub tokens, nor a web sign-in's ID token or code. It does hold the account
metadata as is (identities, emails, device names, and the profile IDs and service names of tracker connections), which
is why backups are encrypted as well (see [Backups](#backups)). Whoever has both the database and the data key can read
everything, and so can the running server.

## Run it

```sh
cp server/.env.example server/.env        # set POSTGRES_PASSWORD at least
(umask 077; set -C; openssl rand 32 > server/tortuga-data.key)
sudo chown 10001 server/tortuga-data.key  # the server's user in the container
docker compose -f server/compose.yaml up -d --build
curl http://127.0.0.1:8080/v2/info
```

The database schema migrates at startup (Flyway). Put a TLS reverse proxy in front and set
`TORTUGA_TRUST_FORWARDED_HEADERS=true`, so rate limits see client addresses: the server then takes the last
`X-Forwarded-For` entry, the address the proxy itself saw, and ignores what the client put before it. Caddy's
`reverse_proxy` sets the header so; nginx needs `proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;` (or
`$remote_addr`). Keep the server's port reachable only through the proxy (compose publishes it on 127.0.0.1). Every
request that carries a token (sign-in, account, devices, links, sync, tracker connections) counts against 300 a minute
per client, on top of its route's own limit, so a flood of bad tokens can't take the database connections real
requests need; past it the server answers 429 `rate_limited` (a browser that asks for a page, `Accept: text/html`,
gets a page saying to wait a minute). Every per-client limit (the API's rate limits and the
tracker sign-in relay's) counts an IPv6 client by its /64. `/healthz` checks the database, at most once every 5 s
however often it is called.

### The data key

The server doesn't start without its data key: `TORTUGA_DATA_KEY_FILE` names a file of exactly 32 bytes (raw
bytes, not base64 or hex text: `openssl rand 32` or `head -c 32 /dev/urandom`), and a missing, unreadable or
wrong-sized file stops it with a message saying so. `compose.yaml` mounts the key as a compose secret at
`/run/secrets/tortuga-data-key` from `server/tortuga-data.key` on the host, or from `TORTUGA_DATA_KEY_PATH`.

Nor does it start with another key than the database's. Its first start on a database stores a check, a value sealed
with the key, in the table `data_key`, and every start opens it; with another key the server stops with
`Not starting: TORTUGA_DATA_KEY_FILE holds another key than the one this database's records are sealed with`. A
database written by a server from before the check gets its check at its first start with this one, once the key has
opened the newest sealed value of each kind the database holds: a record, a tracker connection, a GitHub token, an
Apple token (a database with none of them takes the key it starts with).

- **Create it once.** `set -C` above refuses to overwrite an existing file: a new key can't read the records the old
  one sealed. There is no key rotation; the key belongs to the database for its whole life.
- **Protect it** like the database password: readable only by the server (`chmod 400`, owned by uid 10001), never in
  the image, the repository (`server/tortuga-data.key` is git-ignored and kept out of the build context) or the bucket
  that holds the backups.
- **Back it up separately.** Database backups don't contain it, and a backup restores only together with the same
  key: store a copy (a password manager, an offline medium) apart from the backups.
- **Lost for good?** Then so are the records: accounts and sessions survive, their synced data, tracker connections
  and the Apple and GitHub tokens that revoke their sign-ins don't (deleting such an account reports those revocations
  failed). Delete the check (`DELETE FROM data_key;` in psql) and start the server with a new key, which it
  then stores. Pulls leave out what the old key sealed, which still counts toward its account's quota; each device's
  next push is based on versions the server can't find (409 `unknown_base_version`), so the device pulls everything
  again and pushes what it has as new.

## Configuration

All settings are environment variables. `compose.yaml` passes every one in the table from `server/.env`, except those
marked "set by compose", `TORTUGA_HOST`, `TORTUGA_PORT` and the development stack's `TORTUGA_DEV_*`. A secret can
come from a file instead: `NAME_FILE` names it, inside the container. One form or the other: the server doesn't
start with both.
`compose.yaml` passes both forms of each secret (`TORTUGA_APPLE_PRIVATE_KEY`, `TORTUGA_GITHUB_CLIENT_SECRET`,
`TORTUGA_MAL_CLIENT_SECRET`, `TORTUGA_ANILIST_CLIENT_SECRET`, `BACKUP_S3_SECRET_ACCESS_KEY`, and the database password
as `POSTGRES_PASSWORD` or `POSTGRES_PASSWORD_FILE`, which it hands to Postgres, the server and the backup job); a
`_FILE` path needs the file mounted there, as a compose secret in an override file (`compose.hosted.yaml` mounts every
secret of the hosted instance so).
A setting the server can't use stops it at start with one line, `Not starting: ...`, that names it: a missing or
invalid value, a `_FILE` it can't read or that holds more than 16 KiB (more than any secret), a
`TORTUGA_DATABASE_DISK_PATH` that isn't a directory, a `TORTUGA_APPLE_PRIVATE_KEY` that isn't a Sign in with Apple key,
a provider's or the public address (`TORTUGA_OIDC_ISSUER`, `TORTUGA_OIDC_JWKS_URL`, `TORTUGA_GITHUB_URL`, `_API_URL`,
`_TOKEN_URL`, `TORTUGA_PUBLIC_URL`) that isn't https, where http is taken only to this machine or a private network (a
name without a dot, such as a compose service's, or a loopback, private or link-local address), a database that
doesn't let it in within a minute, a data key that isn't the database's.

| Variable | Default | |
|---|---|---|
| `TORTUGA_DATABASE_URL`, `_USER`, `_PASSWORD` | set by compose | JDBC URL and credentials (each also as `_FILE`) |
| `TORTUGA_DATABASE_POOL_SIZE` | 10 | database connections, and as many threads that run the queries; 50 requests per connection may wait for one, and a request past those or that finds none free within 2 s gets 503 `server_busy` with `Retry-After: 5`, except a sign-in or identity link once its proof is spent (the ID token used up, GitHub's token reset, Apple's code exchanged), which waits for one however long; a statement is cancelled after 10 s. While the database can't be reached, a request gets the same 503 once its connection breaks or no connection comes within 5 s, and the server logs one warning when that starts and one line when the database answers again |
| `TORTUGA_HOST`, `TORTUGA_PORT` | 0.0.0.0, 8080 | listen address inside the container; compose doesn't pass them (the image's health check calls port 8080) |
| `TORTUGA_BIND`, `TORTUGA_HTTP_PORT` | 127.0.0.1, 8080 | compose only: the host address and port the server is published on |
| `TORTUGA_IMAGE`, `TORTUGA_BACKUP_IMAGE` | `tortuga-server:local`, `tortuga-backup:local` | compose only: the images to run |
| `TORTUGA_TRUST_FORWARDED_HEADERS` | false | behind a reverse proxy: the client address is `X-Forwarded-For`'s last entry, the one the proxy added ([Run it](#run-it)); `Forwarded` isn't read |
| `TORTUGA_LOG_FORMAT`, `TORTUGA_LOG_LEVEL` | json, INFO | `json` (an object a line, the message in `formattedMessage`) or `text`; logs carry request and account ids, never emails, tokens or bodies |
| `TORTUGA_DATA_KEY_FILE` | set by compose | the data key: a file of 32 random bytes ([The data key](#the-data-key)); required |
| `TORTUGA_DATA_KEY_PATH` | `./tortuga-data.key` | compose only: the data key file on the host (relative to `server/`) |
| `TORTUGA_ACCOUNT_QUOTA_BYTES` | 67108864 | bytes an account's records may take: each record its payload (compact JSON), its sealed id and 256 bytes for its row |
| `TORTUGA_DATABASE_DISK_PATH` | set by compose | a directory on the database's file system (compose mounts Postgres's volume there, read-only); unset, its free space isn't watched |
| `TORTUGA_DATABASE_MIN_FREE_MB` | 2048 | while the database's disk has less free, every push gets 503 `server_busy` with `Retry-After: 600` and the server logs it once; 0 turns the guard off |
| `TORTUGA_ACCESS_TOKEN_TTL_SECONDS` | 3600 | |
| `TORTUGA_SESSION_IDLE_DAYS` | 180 | a device that doesn't refresh for this long is signed out |
| `TORTUGA_APPLE_ENABLED` | true | Sign in with Apple |
| `TORTUGA_APPLE_CLIENT_IDS` | `com.edde746.tortuga` | accepted audiences of native sign-ins (the apps' bundle ID; only the hosted instance gets those tokens) |
| `TORTUGA_APPLE_SERVICES_ID` | | accepted audience of Apple's web flow: your Services ID ([Sign-in](#sign-in)) |
| `TORTUGA_GOOGLE_CLIENT_IDS` | | Google sign-in; empty disables it |
| `TORTUGA_OIDC_ISSUER`, `TORTUGA_OIDC_CLIENT_IDS`, `TORTUGA_OIDC_NAME` | | your own OpenID Connect provider |
| `TORTUGA_OIDC_JWKS_URL` | discovery's `jwks_uri` | where the server fetches that provider's signing keys, when it can't reach the provider under its issuer |
| `TORTUGA_GITHUB_CLIENT_ID`, `TORTUGA_GITHUB_CLIENT_SECRET` | | Sign in with GitHub: your GitHub OAuth App's client ID and secret (both or neither: the server doesn't start with one alone) |
| `TORTUGA_GITHUB_URL` | `https://github.com` | GitHub's web address as apps and browsers reach it: the provider's issuer in `/v2/info` and the issuer GitHub identities are stored under |
| `TORTUGA_GITHUB_API_URL` | `https://api.github.com` | GitHub's REST API (token reset, email, grant deletion) |
| `TORTUGA_GITHUB_TOKEN_URL` | `TORTUGA_GITHUB_URL/login/oauth/access_token` | where the server exchanges web-flow codes |
| `TORTUGA_APPLE_TEAM_ID`, `TORTUGA_APPLE_KEY_ID`, `TORTUGA_APPLE_PRIVATE_KEY` | | the Sign in with Apple key that exchanges authorization codes and revokes sign-ins on account deletion (the hosted instance: Tortuga's) |
| `TORTUGA_APPLE_WEB_RETURN`, `TORTUGA_PUBLIC_URL` | false | serve Apple's web-flow return page at `TORTUGA_PUBLIC_URL/v2/auth/apple/return` |
| `TORTUGA_MAL_CLIENT_ID`, `TORTUGA_MAL_CLIENT_SECRET` | | hosted instance: the tracker sign-in relay's MyAnimeList client (the secret only if the app type has one) |
| `TORTUGA_ANILIST_CLIENT_ID`, `TORTUGA_ANILIST_CLIENT_SECRET` | | hosted instance: the tracker sign-in relay's AniList client (both or neither) |
| `TORTUGA_MAX_EVENT_STREAMS` | 3000 | change notice streams held at once ([Change notices](#change-notices)); past it a new one gets 503 `server_busy` with `Retry-After: 60` and the app keeps to its periodic pulls; 0 turns the streams off |
| `TORTUGA_DEV_TRACKERS_URL` | | development stacks only (`compose.dev.yaml` sets it; `compose.yaml` doesn't pass it): the fake tracker server (`tools/media/test_trackers.py`) that tracker refreshes and revokes go to instead of the services; the server warns at startup when it is set |
| `TORTUGA_DEV_TRACKER_OAUTH_URL`, `TORTUGA_DEV_TRACKER_OAUTH_BROWSER_URL` | | development stacks only (`compose.dev.yaml` sets them; both or neither): the fake tracker server as the server reaches it and as browsers reach it, standing in for MyAnimeList and AniList in the tracker sign-in relay: browsers go to its authorization pages, and the codes are exchanged there |
| `TORTUGA_REVIEW_PASSWORD_SHA256` | | the [review sign-in](#review-sign-in): the SHA-256 of the review password, 64 hex digits; unset (the default), the server has no review page |

### Sign-in

The apps sign in with a provider and send the ID token; the server checks its signature against the provider's
published keys, its issuer, audience and expiry, accepts each token once (until it expires plus the minute of clock
skew it allows), and binds it to a server-issued nonce when the token carries one; Apple's tokens must. The account is
keyed by issuer and subject. GitHub issues no ID token: its sign-in works differently (below).

**The apps use Tortuga's own OAuth clients only with the hosted instance** (`https://api.usetortuga.app`): Sign in with
Apple's native sheet (audience the apps' bundle ID) and Tortuga's Services ID, Tortuga's Google clients, and Tortuga's
GitHub OAuth App. With any other server they run only the flows whose client IDs that server lists in `/v2/info` and
that aren't Tortuga's, and never a native sheet, which can mint tokens for Tortuga's IDs alone. A token one of
Tortuga's clients issued is therefore never sent to a server but the hosted one, so a server can't replay a user's
sign-in there. A self-hosted server brings its own OAuth apps:

- **Your own OpenID Connect provider** (Authentik, Keycloak, Dex, …): register a *public* client (authorization code
  with PKCE, loopback redirect URIs such as `http://127.0.0.1:53682/callback`) and set `TORTUGA_OIDC_ISSUER` (exactly
  the provider's `issuer`, a trailing slash included, as its tokens carry it), `TORTUGA_OIDC_CLIENT_IDS` and a button
  label in `TORTUGA_OIDC_NAME`. The apps read these from `/v2/info`.
- **GitHub, through your own GitHub OAuth App.** At GitHub, Settings → Developer settings → OAuth Apps → New OAuth
  App (an OAuth App, not a GitHub App): any name and homepage, the authorization callback URL
  `<your server's URL>/v2/auth/github/return` (the address the apps use for the server), then *Enable Device Flow*
  (TVs sign in with a code) and *Generate a new client secret*. Set `TORTUGA_GITHUB_CLIENT_ID` and
  `TORTUGA_GITHUB_CLIENT_SECRET` (in `server/.env`); `/v2/info` then lists GitHub and the apps show its button.
- **Apple, through your own Services ID** (an Apple Developer account): a Services ID with Sign in with Apple, its
  return URL `<TORTUGA_PUBLIC_URL>/v2/auth/apple/return`; set `TORTUGA_APPLE_SERVICES_ID`,
  `TORTUGA_APPLE_WEB_RETURN=true` and `TORTUGA_PUBLIC_URL`. Every app but Apple TV then signs in through Apple's web
  flow. Revoking the sign-in on account deletion also needs your Sign in with Apple key (`TORTUGA_APPLE_TEAM_ID`,
  `_KEY_ID`, `_PRIVATE_KEY`); without it, deleting an account removes all of its data and the app tells the user to
  remove the app under Settings → Apple ID → Sign in with Apple (Apple TN3194).
- **Google** isn't offered by a self-hosted server: every Google flow in the apps runs one of Tortuga's Google clients.
  Leave `TORTUGA_GOOGLE_CLIENT_IDS` empty; set `TORTUGA_APPLE_ENABLED=false` without a Services ID.

The web flows, protocol in `shared/src/commonMain/kotlin/com/edde746/tortuga/sync/api/Auth.kt` (`HandoffStartRequest`,
`HandoffCollectRequest`): GitHub on phones, tablets and computers, Apple on the same devices where the native sheet
isn't used (Android, Windows and Linux with the hosted instance; all of them with yours). The app draws a secret
verifier and starts the flow (`POST /v2/auth/github/start` or `/v2/auth/apple/start`) with its hash and where it
listens: its loopback receiver, or `tortuga://signin` on iPhone, iPad and Mac. The server answers a fresh `state`,
kept ten minutes, and the browser goes to the provider with it. The provider sends the browser to the server's return
page (`GET /v2/auth/github/return` with a code, or Apple's form post to `POST /v2/auth/apple/return`). For a `state`
the server issued that hasn't returned yet, the page keeps the result, sealed with the data key, with a one-time proof
and redirects the browser (303) to the app on its own device; any other request gets a static error page without
script and writes nothing.
The return pages take 20 requests a minute per client address. The app collects the result once
(`POST /v2/auth/github/handoff` or `/v2/auth/apple/handoff`) with its verifier and the proof: a link someone else
started brings the browser back to the device it runs on, so its result never reaches them. For GitHub the server
then exchanges the code with the client secret and hands the app the access token. TVs get GitHub's token from its
device flow directly. The app then signs in (or links) with that token: the server resets it at GitHub
(`PATCH /applications/{client_id}/token`), which refuses another app's token and ends the one the app sent, reads the
primary verified email, and keys the identity on GitHub's numeric user id. It keeps only the token the reset issued,
sealed under the data key like tracker tokens, to delete the user's grant of the app when GitHub is unlinked or the
account deleted (`githubRevocation`: `revoked`, or `failed` when GitHub refused or wasn't reached, in which case the
account is deleted all the same and the app points the user at GitHub's Settings → Applications).

The hosted instance sets Tortuga's Apple key (`TORTUGA_APPLE_PRIVATE_KEY_FILE` pointing at the `.p8` file mounted
into the container), `TORTUGA_APPLE_SERVICES_ID`, `TORTUGA_APPLE_WEB_RETURN=true`, `TORTUGA_PUBLIC_URL` and
Tortuga's Google client IDs. It exchanges each Apple authorization code for Apple's refresh token, which it keeps sealed
with the data key, and revokes it when the account is deleted. Its GitHub OAuth App is Tortuga's, with the callback
`https://api.usetortuga.app/v2/auth/github/return`.

### Review sign-in

For the app stores' reviewers, who can't be handed a provider account that signs in reliably on a new device. Off by
default: with `TORTUGA_REVIEW_PASSWORD_SHA256` unset, as on a self-hosted server, `/review` doesn't exist. Set to the
SHA-256 of a review password (`printf %s '<password>' | sha256sum`; the password itself is never configured), the server
serves the review page at `/review`: a plain form that takes the review password and a code. The reviewer chooses *Link from
another device* on the signed-out device, opens the page in any browser, enters the password and the code the device
shows, and the device signs in, a TV as well as a phone or computer.

A submission checks the password's hash in constant time, then approves the open device link with that code (the code
normalized as the apps do, so case and hyphens don't matter) exactly as a signed-in device's approval does, for one
account only: the demo account, whose one identity has the provider `review`. The server makes that account at the
first approval, as a first sign-in makes an account, and again at the next approval once a reviewer has deleted it. No
app offers it as a sign-in (`/v2/info` doesn't list `review`), and the page shows nothing of the account; it answers
only that the device signs in, that the password is wrong, that no device shows the code, or that the code expired
(codes last 10 minutes). Submissions take 5 a minute per client address; past it the page says to wait a minute.

### Tracker sign-in relay

MyAnimeList and AniList hand out tokens only to a registered redirect URI (and AniList only with the client
secret), which an app on a TV can't hold. The server relays those sign-ins (protocol in
`shared/src/commonMain/kotlin/com/edde746/tortuga/trackers/relay/OAuthRelayApi.kt`):

1. The app `POST`s `/v1/trackers/oauth/start` (`{"service":"mal"}` or `"anilist"`, with its device's name and, where
   it has a browser, the loopback receiver or `tortuga://signin` its browser comes back to) and gets a poll secret, a
   URL to show as a QR code and, with a return, a second URL for its own browser.
2. The browser opens `/v1/trackers/oauth/{service}?state=…`, which redirects to the service with the relay's client
   id, its callback `TORTUGA_PUBLIC_URL/v1/trackers/oauth/{service}/callback` as `redirect_uri`, the `state` and, for
   MyAnimeList, a PKCE challenge (`plain`: MyAnimeList rejects `S256`).
3. The service returns the browser to the callback, and the relay exchanges the code and holds the tokens for the
   device that started the sign-in:
   - the app's own browser (its URL's `state`) goes back to the app's return with a one-time proof, as the Apple and
     GitHub web sign-ins do, and the app collects with the poll secret and the proof: no step for the person;
   - another device's browser (the QR code's `state`) gets a page that names the starting device ("Connect
     MyAnimeList to Tortuga on “Living room TV”?") with one button, Connect, a form `POST` to the callback; only that
     releases the tokens to the poll secret. Closing the page connects nothing.
4. Meanwhile the app long-polls `POST /v1/trackers/oauth/result` with its poll secret (held 50 seconds, 204 when
   nothing arrived yet) and receives the confirmed tokens, or an error, once.

So a sign-in link someone else started never hands the tokens of whoever opens it to that someone without a page that
names the device they go to: from their own browser's link, the browser goes back to its own device's loopback or app
return, where the starter can't collect.

Sessions live in memory for 10 minutes (a restart cancels sign-ins in progress); the janitor's round each minute drops
an expired one with the tokens it held, so none stays past 11 minutes. The browser states, the poll secret,
the proof and the confirmation are separate random values, the callback is single use, the result is handed out once,
and tokens, codes and secrets are never stored or logged. Starts and polls are rate limited per client address like
the rest of the API, and the browser's pages like the other sign-in return pages (`signInReturn`). A client address
holds at most 3 sign-ins in progress (a start past them gets 429 `rate_limited`), the server 20,000 in all (503, a
memory backstop only), and a sign-in at most 2 polls held at once (another is answered 204 at once).

Only the hosted instance needs this: register an app with each service and set the variables above. A server without
them answers starts with 503 `not_configured`. Tortuga's registrations:

- MyAnimeList (<https://myanimelist.net/apiconfig>): App Type *other*, which has no client secret, so its tokens
  refresh with the client ID alone (the same ID is built into the apps, `tortuga.trackers.malClientId`, and stored with
  each connection, [Tracker connections](#tracker-connections)) and `TORTUGA_MAL_CLIENT_SECRET` stays unset. App
  Redirect URL `https://api.usetortuga.app/v1/trackers/oauth/mal/callback`.
- AniList (<https://anilist.co/settings/developer>): Redirect URL
  `https://api.usetortuga.app/v1/trackers/oauth/anilist/callback`. Its secret is a file on the host ([Hosted
  instance](#hosted-instance)).

### Tracker connections

A profile's connection to Trakt, Simkl, MDBList, MyAnimeList or AniList belongs to the account: the server keeps the
service's tokens and is the only one that refreshes them, so two devices never spend the same single-use refresh
token (Trakt) or cut each other off (Simkl), and devices keep no refresh token. Protocol in
`shared/src/commonMain/kotlin/com/edde746/tortuga/trackers/connections/TrackerConnectionsApi.kt`. Every route needs a
session of the account and reaches only that account's connections:

- `POST /v2/trackers/connections`: a device that signed in to a service (device code, or the relay above) stores the
  tokens, the client ID they were issued to and the username there. It replaces the profile's connection to that
  service (without revoking the replaced tokens) under a new `connectedAtMillis`. 400 for an unknown service, a field
  over its limit, a blank access token, or no refresh token where the service's tokens refresh.
- `GET /v2/trackers/connections`: the account's connections, every profile's, oldest first, without tokens, with the
  account's connections counter (`version`). Adding, replacing or removing a connection moves the counter, and every
  pull reports it (`trackersVersion`), so a device lists the connections again only when it moved.
- `POST /v2/trackers/token`: the access token of one connection (`Cache-Control: no-store`), refreshed at the service
  first when it expires within 5 minutes or the device says the service rejected it. A refusal by the service (or a
  rejected AniList token, which doesn't refresh) deletes the connection: 410 `tracker_disconnected`, also for a
  connection that is gone or replaced. A token endpoint that fails or can't be reached answers 502
  `upstream_unavailable` and the connection stays.
- `DELETE /v2/trackers/connections/{profileId}/{service}`: deletes the connection, then revokes its token at the
  service (best effort: MyAnimeList and AniList have no revocation). 204, also when there was none.

Deleting the account, or a profile (its synced record's tombstone, `profile/<id>`), deletes its connections and
revokes them the same way once the deletion has committed.

One refresh per connection at a time, across requests and server instances, with no database connection held while
the service answers: a token that needs none is read and handed out; a refresh claims the connection's row for 30
seconds, asks the service, and stores the refreshed tokens (Trakt's rotated refresh token among them) only if the row
still holds the tokens it refreshed. Concurrent requests find the claim, wait for that refresh and get its result. A
refresh that finds its connection deleted meanwhile (the account, the profile, a disconnect) revokes the tokens it got.

Refreshes and revokes reach the services with the client ID stored with each connection, as the apps' own code does:
Tortuga's tracker clients are public, with no secret, and AniList's tokens don't refresh, so neither the hosted
instance nor a self-hosted one needs tracker configuration for this. The table `tracker_connections` keeps one row per
account, profile and service: the tokens, their expiry, the client ID and the username sealed with the data key
([What the server sees and stores](#what-the-server-sees-and-stores)), the profile ID, the service and the connection
time as they are. Logs name the account and the service, never a token, username or client ID.

### Change notices

An app in front holds `GET /v2/sync/events` open (Server-Sent Events, authenticated like sync; `SyncPaths.SYNC_EVENTS`
in `shared/src/commonMain/kotlin/com/edde746/tortuga/sync/api/SyncProtocol.kt`) and pulls as soon as another device
changed the account. The answer is `200 text/event-stream` with `Cache-Control: no-store, no-transform` and
`X-Accel-Buffering: no`, and carries:

- `event: changed` with `data:` the account's sync version (0 until its first push stores records), after every
  committed change that a pull answers differently: a push that stored records, a tracker connected or disconnected, a
  tracker connection deleted because its service refused its refresh. The session that made the change doesn't hear it;
  the account's other streams do. A stream that can't send yet keeps only the newest version, so every notice is worth
  one pull.
- a comment line (`:`) as soon as it opens (the app counts the stream open, and pulls, once it has the head) and then
  every 30 s, so idle streams stay open through proxies.

One stream per session: a session that opens another ends its older one. The server closes a session's stream when the
session ends (signed out, revoked from another device, ended for a new session past the account's 50, expired) or its
account is deleted, and ends it within a minute after the access token it opened with expires (the janitor's round),
so a token lasts no longer on a stream than on any other call; it resets a stream whose client stops reading for 10 s
(the engine's write timeout), and a restart drops them all. The app opens it again after a backoff, and pulls.
Refusals are JSON whatever `Accept` says: 401 as everywhere, 503 `server_busy` with `Retry-After: 5` when the server is
busy or starting, and with `Retry-After: 60` when `TORTUGA_MAX_EVENT_STREAMS` are open; meanwhile the app keeps to its
periodic pulls.

A reverse proxy in front must pass the stream on as it comes. Caddy's `reverse_proxy` does with nothing to set.
nginx buffers responses unless told otherwise: the server's `X-Accel-Buffering: no` turns that off for the stream, or
set `proxy_buffering off`. Cloudflare streams `text/event-stream` by default and drops a connection idle for 125 s,
well past the 30 s keepalive.

## Memory and load

The server refuses work rather than run out of memory: sync answers 503 `server_busy` with `Retry-After: 5`. In the
load runs (the hosted limits, in Docker on a Linux host, 15 and 16 Oct, while the server still ran the
watch-together relay) neither the heap nor the container ran out and it never restarted, but in one: 1,000 clients
each opening an 8 MiB push at once, where the kernel killed it (Risks).

**The JVM** (JDK 27, Temurin; the image's and the hosted `JAVA_OPTS`): the heap at 45 % of the container (174 MiB of
the hosted 384, two thirds of it Serial GC's old generation, 116 MiB), Netty's direct buffers at most 32 MB, the code
cache at most 64 MB; compact object headers (JDK 27's default) keep the live heap about 15 % smaller than without. The
JVM exits on its first OutOfMemoryError (Docker restarts it) and hands freed native memory back every 30 s. Metaspace,
threads and the JVM's own memory take about 80 MB idle and 120 MB after the sync load.

Under load the heap fills to its cap. Up to JDK 22, Serial followed every young collection that had grown the old
generation for what it promoted with a full collection, which kept the old generation near what was alive (265 full
collections in a profiled run on JDK 21). JDK 23 dropped that rule
([JDK-8331557](https://bugs.openjdk.org/browse/JDK-8331557), Serial: Refactor SerialHeap::do_collection; then
[JDK-8332595](https://bugs.openjdk.org/browse/JDK-8332595) removed the unused `TenuredGeneration::should_collect`): a
young collection now grows the old generation for what it promotes up to its cap, and Serial collects it whole only once
it can't take a young collection's average promotion or an allocation fails (5-58 full collections per profiled run on
JDK 27). No flag brings the old rule back: `MinHeapFreeRatio` and `MaxHeapFreeRatio` size the old generation only after
a full collection, and the young/old split (`NewRatio`) moves the cap, not the rule. So every peak counts the whole heap:
beside it the rest (metaspace, threads, the code cache, Netty's buffers, glibc's arenas, the kernel's socket buffers)
took up to 162 MiB, and after load the server holds 264 MiB of anon memory against 215 on JDK 21 (profiled runs). The
heap's share against the cgroup's `memory.peak` in each load mode, once each, unprofiled, on 16 Oct:

| Heap | Capacity: sync cycles, full pulls a second | `memory.peak`, MiB of 384: overload, notices, `BYTES`, `UPLOADS` |
|---|---|---|
| 45 %, 174 MiB (hosted) | 844, 769 | 325, 336, 333, 307 |
| 40 %, 154 MiB | 1,000, 971 | 294, 321, 318, 287 |
| 35 %, 134 MiB | 879, 725 | 272, 303, 297, 279 |
| G1, 45 % | 847, 610 | 284, 335, 351 |

45 % stays: every peak there is at least 40 MiB under the limit (48 in the notices' stall), and a smaller heap gives up
byte budget (38 MiB at 40 %, 34 at 35 %) for sync capacity the runs can't tell apart from their spread (684-1,034 sync
cycles a second over five profiled runs on JDK 27, 15 Oct). G1 starts at 198 MB resident idle against 138, answered
fewer full pulls and peaked at 351 MiB under large requests, with 347 young collections whose evacuation failed. The
final head's runs at 45 % (16 Oct) peaked at 309, 327, 337 and 287 MiB.

**Connections** (`http/Connections.kt`). The kernel charges each connection's socket buffers to the container, beside
the JVM and unseen by the byte budget, so the server bounds them: a connection's kernel buffers are held to 4 KiB for
what its client sent that the server hasn't read (set on the listening socket, so from the handshake on) and 8 KiB for
what it sent that the client hasn't acknowledged (Linux doubles each and, set this way, never grows them; left to
itself it gives 128 KiB to receive into and grows it to 6 MiB): 24 KiB at most in the kernel. While an admitted
request body comes in, its connection's receive buffer is 128 KiB (`http/BodyGate.kt`; 248 KiB more in the kernel),
out of a pool of a thirty-second of the container's memory (12 MiB hosted, 49 bodies at once); with the pool empty, a
body comes through the small buffer. Its connections, held and waiting to be accepted, are at most one per 192 KiB of
the container's memory, so that all their buffers, full, take an eighth of it: hosted, 1,920 held and 128 waiting
(48 MiB together, 60 MiB with the pool). Past the 1,920 a new connection is reset as the kernel hands it over, before a
byte of it is read; past the 128 waiting, the kernel completes no more handshakes and clients retry theirs. A connection
whose client stops reading its answer for 10 s (the engine's write timeout) is reset, so its send buffer goes at once.
The server logs the cap at startup (`maxConnections`, `connectionBacklog`) and, at most once a minute, how many it
reset.

**Notice streams** ([Change notices](#change-notices), `routes/SyncEventRoutes.kt`, `service/SyncEvents.kt`). A held
stream keeps about 15 KB alive on the heap (17 on JDK 21) and 22-26 KB resident in the JVM (Ktor's SSE call on Netty),
and 1.6 KB in the kernel. It leaves the connection cap above for its own, `TORTUGA_MAX_EVENT_STREAMS`, and its
connection's socket buffers drop to Linux's smallest: a client that stops reading holds about 8 KB there, both full. The
stream's writer runs on `Dispatchers.Default` and writes only once Netty has handed its last write to the kernel, so a
stalled client holds one notice at most (the newest version), never a queue, until the write timeout resets it. The
cap's 3,000 is sized for Caddy on the hosted host, not for the server: Caddy holds about 107-120 KB per stream (TLS,
HTTP/1.1; its Go heap and goroutine stacks), so 3,000 take about 350 MB of the 700 MB the host had available (15 Oct),
leaving it half; on the server they take about 75 MB of resident memory. Measured on 16 Oct with the hosted limits and
JDK 21.0.12.1:

| Run | Result |
|---|---|
| no streams, then 3,000 held idle | live heap 17.8 → 68.6 MB, RSS 177 → 253 MB, cgroup kernel 2.2 → 6.8 MB, threads 36 → 36; 0.012 cores with a keepalive every 30 s |
| a notice, sync at 10 cycles a second / saturated (920 a second) | p50 3.2 / 13.1 ms, p99 11.1 / 29.9 ms, each to every other stream of the account |
| the same through Caddy 2.11.4, 3,000 streams through it | p50 3.6 ms, p99 12.6 ms; 100 streams held through it 385 s, none dropped |
| 1,000 that stop reading beside 2,000 held, 1,570 pushes a second to their accounts | each reset 14-16 s after the first push; cgroup peak 329 MiB of 384, socket memory 7.8 MiB, threads 37 (as under saturated sync); 1,000 that read under the same pushers: 310 MiB |
| restart with 3,000 held | all back and pulled within 10.9 s; 522 opens answered 503 with `Retry-After: 5` while it started |
| stream 3,001 | 503 `server_busy`, `Retry-After: 60` |

On JDK 27 at 45 % (16 Oct) the same steps passed with the stall's cgroup peak at 336 MiB of 384 and the heap full beside
it, threads 41 before and during the stall, all 1,000 stalled streams reset 13.5-15.6 s after the first push, and the
3,000 back within 10.9 s of a restart; with full collections of 121-139 ms in the run, a notice's p99 was 173 ms under
saturated sync and 70 ms through Caddy (38 and 18 ms at 40 %).

**Sync.** At most `TORTUGA_DATABASE_POOL_SIZE` transactions run at once (5 hosted) and 50 per connection wait (250);
past those, or after 2 s of waiting, a request gets 503 `server_busy` with `Retry-After: 5`. An account's records take
at most 64 MiB, each its payload, its sealed id and 256 bytes for its row (migration 5 recounted the accounts stored
before), its sessions at most 50 (a new one ends the least recently seen) and its tracker connections at most 100
(413 `quota_exceeded` past them). A deleted record's tombstone counts too, and stays until the account is deleted: the
server keeps no per-device cursor, so it can't know when every device has pulled past a tombstone, and one purged early
would let a device that pushes its old copy bring the record back (a purge needs a horizon in the sync protocol). A
tombstone's payload is at most 8 KiB (400 `invalid_request` past it), so a tombstone costs at most that, its sealed id
and its row. While the database's disk has less than `TORTUGA_DATABASE_MIN_FREE_MB` free (2 GiB),
every push gets 503 `server_busy` with `Retry-After: 600`. What the server reads from Apple, the sign-in providers and
the trackers is at most 64 KiB an answer (a provider's key set 256 KiB). Overloaded at 1.5 times its capacity (the sync
cycle, 844 a second with the hosted limits on 16 Oct), the server held up to 316 requests in flight, refused about
4,700-6,100 every 5 s, answered the rest with a p99 of 103-151 ms and within 10 s after it at 2.9 ms; the cgroup's
memory peaked at 325 MiB of 384 (anon 318).

**Sync's byte budget.** Sync requests share a byte budget of a quarter of the heap (43 MiB hosted). A request reserves
8 KiB plus three times its `Content-Length` before its body is read; a body without `Content-Length` counts as its
path's limit, or 1 MiB when that is more. A push reserves seven times its longest string
(at most 256 KiB) more while its body is parsed, as a stream of tokens, never as a tree. A pull's page and a push's
stale records stop once they reach 1 MiB (the first record always goes in) and reserve twice their bytes before the
transaction ends. Everything a request reserved comes back when it ends. A push body may be 8 MiB, any other body
64 KiB. A request that finds the budget full gets 503 `server_busy` with `Retry-After: 5`; a body longer than its path
takes, or a request that alone needs more than the whole budget, gets 413 `payload_too_large`. The largest push
reserves 28.3 MiB, so the server refuses to start on a heap under 113 MiB (a container under about 251 MiB at 45 %).
Under 2 MiB pushes and full pulls of 8 accounts of 24 MiB (`BYTES=1`, 16 Oct), the server answered 4,897 requests and
refused 7,816 (7,815 with 503, 1 by a reset); its anon and socket memory peaked at 326 MiB of 384, the cgroup's memory at
333.

**Risks.**

- `memory.peak` counts page cache: a server that is first to read its JRE and distribution after something evicted
  them takes about 117 MiB of them into its own cgroup (16 Oct: a `NOTIFY=1` run with them uncached reached 384.0 MiB
  with 323 MiB anon; the kernel reclaimed the cache and killed nothing). The load runs read both before the
  server starts, so their peaks leave that cache out.
- Before a sync request is admitted, Netty reads at most one 64 KiB read of its body, which the byte budget counts
  with the call's 8 KiB (`http/BodyGate.kt`); Ktor's buffer of a body (up to 1 MiB a connection) fills only once the
  call has reserved 3 times its length. A refused call's connection closes 2 s after its answer instead of draining
  the body, and a body must be in by 10 s plus its length at 64 KiB/s, or its connection closes unanswered. What a
  held connection's client sends meanwhile waits in its 8 KiB receive buffer. With 1,000 clients each opening an 8 MiB
  push at once (`UPLOADS=1`, 15 Oct): before the connection bound, the kernel's socket memory rose from 0 to 216 MiB
  within a second of each start (182 MiB received unread over 1,004 connections) beside the JVM's 150 MiB, and the
  kernel killed the server at the 384 MiB limit 7 times in 40 s; with it (JDK 27, 16 Oct), no restart, socket memory at
  most 8.1 MiB, anon and socket memory at most 303 of 384 MiB (the cgroup's peak, page cache included, 307 MiB), 25,974
  uploads refused with 503.
- The small buffers cost push throughput (measured on JDK 21). Under 2 MiB pushes and full pulls (`BYTES=1`, 15 Oct)
  the server answered 5,606 requests against 5,165 with the kernel's own buffers and moved 7.1 GiB against 7.2, but 731 of them were
  pushes against 1,636 (1,428 MiB pushed against 3,195) and 13 full pulls of a 24 MiB account finished against 22: a
  push holds its share of the byte budget while its body comes in, so pushes get more 503s and pulls fewer. With
  every read at 8 KiB (no raised buffer) it answered 1,982, 110 pushes and no full pull. Raised buffers of 64, 128
  and 208 KiB gave 661, 725 and 716 pushes (two runs each), though at 128 KiB a 256 KiB admitted body gets all of
  itself ahead of reads the server stalls (`ConnectionsTest`): what else holds pushes back is not known. A larger
  buffer on the listening socket is not the way: every connection gets the window it sets before the server reads
  it (38 KiB got ahead with 32 KiB there, against 9 KiB). A client sending straight to the server from far away gets
  at most about 8 KiB per round trip before its call is admitted and up to 128 KiB after (40 KiB/s and 1.3 MiB/s at
  100 ms; not measured); behind a proxy on the same host, as hosted, the round trip is loopback's.
- A connection the server closes no longer counts, but the kernel keeps what the client hasn't acknowledged (up to
  16 KiB) until the client does or its retransmissions give up; those closed connections aren't capped (not measured).
- The hosted container may swap: `compose.hosted.yaml` sets no `memswap_limit` and the host has a 2 GB swap file, so
  Docker lets the server use as much swap as its limit; the load runs had none.

## Backups

The optional `backup` service dumps the database on a schedule, encrypts the dump with
[age](https://age-encryption.org) and uploads it to S3-compatible storage (AWS S3, Backblaze B2, Cloudflare R2,
MinIO, …). Synced records, tracker tokens and Apple and GitHub tokens in a dump are sealed with the data key, but
account metadata isn't (identities, emails, device names), so dumps never leave the machine unencrypted. The server
holds only the public key; keep the private key offline, away from the bucket.

**The data key is not in the backups.** A restored database is readable only with the key it was written with, so
keep a copy of `tortuga-data.key` with the age private key, not with the backups: a bucket that holds both would give
away every record.

1. Make a key pair once, on a trusted machine, outside the checkout, and store `tortuga-backup.key` safely (the root
   `.gitignore` ignores the name anywhere in the repository, as a backstop):
   ```sh
   docker compose -f server/compose.yaml build backup
   (umask 077; docker run --rm --entrypoint age-keygen tortuga-backup:local > ~/tortuga-backup.key)
   grep 'public key' ~/tortuga-backup.key      # age1…: this goes into BACKUP_AGE_RECIPIENTS
   ```
2. Set the `BACKUP_*` variables in `server/.env`: `BACKUP_S3_ENDPOINT`, `BACKUP_S3_BUCKET`,
   `BACKUP_S3_ACCESS_KEY_ID`, `BACKUP_S3_SECRET_ACCESS_KEY`, `BACKUP_S3_REGION`, `BACKUP_S3_PROVIDER` (an rclone
   S3 provider name, default `Other`), `BACKUP_S3_PREFIX` (default `tortuga/`), `BACKUP_SCHEDULE` (cron, UTC,
   default `17 3 * * *`), `BACKUP_RETENTION_DAYS` (default 30; 0 keeps everything) and `BACKUP_AGE_RECIPIENTS`
   (one or more public keys).
3. Start it: `docker compose -f server/compose.yaml --profile backup up -d`. One backup right away:
   `docker compose -f server/compose.yaml --profile backup run --rm backup backup`; list them with `… backup list`.

### Restore

With the server stopped (`docker compose -f server/compose.yaml stop server`), one command restores the newest
backup (or a named one from `backup list`) into the configured database:

```sh
docker compose -f server/compose.yaml --profile backup run --rm \
  -v "$HOME/tortuga-backup.key:/run/secrets/age-identity:ro" backup restore latest
```

The restore replaces the database's `public` schema, empty or not, in one transaction: it drops the schema with
everything in it (also tables a newer server added that the backup doesn't have), creates it again and loads the
backup, and commits only once the whole backup has been read. A restore that fails (a wrong age key, a damaged or
cut-off backup, an error in the database) leaves the database as it was and exits with `restore of NAME failed`.

Then `docker compose -f server/compose.yaml up -d server`, with the same data key as when the backup was made: with
another key the server doesn't start ([The data key](#the-data-key)). The server migrates a backup from an older
version as it starts. The database is as it was at the backup: devices whose session was refreshed since then sign in
again, and records written since come back with each device's next push (pulls tell devices the server went back in
time, and so does a push based on a version the server doesn't have: 409 `unknown_base_version`).

## Hosted instance

`api.usetortuga.app` runs this server with `compose.yaml` and `compose.hosted.yaml`, which caps memory, keeps
Postgres small and mounts every secret as a file.

## Development

- `./gradlew :server:test` runs the Ktor test application against Postgres in Testcontainers (Docker must be
  running), with fake Apple, Google and OIDC issuers and a fake GitHub (`support/FakeGitHub.kt`); the tracker
  connections' tests (`TrackerConnectionsTest`) refresh and revoke against fake Trakt and Simkl token endpoints.
  On Windows without Docker Desktop, `DOCKER_HOST=tcp://127.0.0.1:2375` points Testcontainers at a WSL distro's
  Docker Engine listening there. The tracker relay's and the data key's tests keep no database:
  `./gradlew :server:test --tests '*TrackerOAuthRelayTest' --tests '*DataKeyTest' --tests '*RecordCipherTest'` runs
  without Docker.
- `server/scripts/smoke.sh` runs the whole stack end to end: `compose.yaml` alone (with a throwaway key file), then
  with `compose.dev.yaml` (Dex as the OIDC provider, the fake GitHub, MinIO as the backup target) sign-in, push, a
  device link and pull, Sign in with GitHub (`scripts/github-e2e.py`: web flow, device flow, a second GitHub user
  linked, the account deleted with both grants), a database dump free of the synced ids, URLs and titles and of the
  data key, a backup, account deletion down to no rows, a restore into an empty database, a failed restore (a wrong
  key) that changes nothing, and a restore over the database with a table the backup doesn't have, which goes; the
  restored account syncs again with the same key. It tears everything down afterwards.
- `server/scripts/sync-e2e.sh` runs the apps' shared sync client (`SyncAccount`, `SyncEngine`) against the
  `compose.dev.yaml` stack through `:shared:syncEndToEndTest`. Extra Gradle arguments go in `GRADLE_ARGS`
  (`-Ptortuga.android=false` without an Android SDK). It tears everything down afterwards.
- `python3 server/scripts/github-e2e.py <server URL>` checks a running dev stack's GitHub sign-in end to end, from the
  stack's host, where the server and the fake GitHub answer on 127.0.0.1.
- `docker compose -f server/compose.yaml -f server/compose.dev.yaml up -d --build` for manual testing. The stack makes
  its own throwaway data key in the `dev-data-key` volume, which `down -v` removes with the database. Its review page
  (`/review`) takes the review password `tortuga-dev-review`. Dex's user is
  `alice@example.com` / `password`, its client `tortuga-dev`. Dex's issuer is `http://127.0.0.1:${DEX_HTTP_PORT}/dex`
  (default port 5556): the apps reach it on the host, and phones, the Android emulator and simulators through
  `adb reverse` and SSH tunnels to the same port; the server container checks tokens for that issuer and fetches the
  keys from Dex inside the compose network (`TORTUGA_OIDC_JWKS_URL`).
  The fake GitHub (`dev/fake_github.py`, the `github` service) is published on
  `http://127.0.0.1:${GITHUB_HTTP_PORT}` (default 5557) the same way and is the server's GitHub
  (`TORTUGA_GITHUB_URL`; the server calls its API and exchanges codes inside the compose network). Its OAuth App is
  `tortuga-dev-github` / `tortuga-dev-github-secret`, and `other-app` / `other-app-secret` mints another app's tokens.
  Its authorize page approves at once: as the `login` query's user, else the user its `fake_github_user` cookie names,
  else a fresh one (`deny` and `deny-*` decline). `POST /_fake/device` approves or denies a device code, and
  `GET /_fake/state` lists its users, live tokens and grants; its docstring documents every route.
  With `TORTUGA_DEV_TRACKERS_URL` set to the fake tracker server as the container reaches it (e.g.
  `http://host.docker.internal:8772`), the server refreshes and revokes tracker tokens there instead of at the services.
  The fake tracker server (`tools/media/test_trackers.py`, the `trackers` service) is published on
  `http://127.0.0.1:${TRACKERS_HTTP_PORT}` (default 5558) the same way and is MyAnimeList and AniList for the tracker
  sign-in relay (`TORTUGA_DEV_TRACKER_OAUTH_URL`, `TORTUGA_DEV_TRACKER_OAUTH_BROWSER_URL`; the relay's clients
  `tortuga-dev-mal` and `tortuga-dev-anilist` with `dev/anilist-client-secret`, its links on
  `TORTUGA_PUBLIC_URL=http://127.0.0.1:${TORTUGA_HTTP_PORT}`). Its authorization pages approve at once; `GET /_log`
  lists every grant (authorize, code exchange, whether the PKCE verifier and the secret matched). A debug build's
  development session signs in through it with `--dev-relay` (on Android inside the `dev` launch extra).
