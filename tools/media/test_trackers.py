#!/usr/bin/env python3
"""A fake Trakt for the trackers tests (the Apple TrackersTests, the desktop and Android trackers tests): the app's
development session points Trakt's API here (--dev-trackers <this server>, on Android inside the `dev` extra), connects by device
code, scrobbles, marks watched and reads the watchlist. It also stands in for the Tortuga account server's tracker
connections (under /tortuga, below): the session stores its sign-ins there and takes its access tokens from there.

Usage: tools/media/test_trackers.py [--port 8772] [--approve-after 5] [--user tortuga-tester]
       [--anilist-secret <secret>] [--agerating-db <agerating-ids.db>]
Routes (Trakt's API, docs.trakt.tv; only what the app calls):
  POST /oauth/device/code            a device code whose verification URL is this server's /activate
  POST /oauth/device/token           400 (pending) until the code was polled --approve-after times, then the tokens:
                                     the fake approves on its own, as a user entering the code would
  POST /oauth/token                  a refresh: new tokens (Trakt rotates the refresh token)
  POST /oauth/revoke                 a revoke, counted
  GET  /users/settings               the account's username (--user)
  POST /scrobble/{start,pause,stop}  recorded; a stop at 80% or more also counts as a watch, as Trakt's rule does
  POST /sync/history[/remove]        recorded watched and unwatched marks; 503 while writes fail (/_fail)
  GET  /sync/watchlist               the watchlist (below): at first three titles (a movie and two shows) with
                                     posters served here
  GET  /users/me/lists, /recommendations/*, /movies/trending, /shows/trending: empty lists
  GET  /activate                     a page saying the code is approved automatically
  GET  /movies/tt0050083/related     Trakt's related titles of 12 Angry Men (test_addon.py's tortuga-related, Detail's
                                     More like this), posters served here: The Godfather, Cool Hand Luke, Goodfellas
                                     and 12 Angry Men itself; any other title's (also /shows/<id>/related): none, an
                                     empty list, as Trakt answers a title it doesn't know
  GET  /lists/1234                   a public list (test_addon.py --collections' Trakt List folder): its name, ids
                                     and item count; any other list id 404, as Trakt answers one it doesn't know
  GET  /lists/1234/items/<movie,show|movie|show>[?page=<n>&limit=<n>]
                                     its titles of those types (PUBLIC_LIST: Cinemeta movies and shows, posters
                                     served here), ranked, a page of `limit` (default 10) with Trakt's pagination
                                     headers (X-Pagination-Page-Count), as the app reads a list without signing in
  GET  /poster/<id>.png              <id>'s poster: test_addon.py's gradient for it (art_png), which this script
                                     imports from beside it
  GET  /_config                      what the app's development session has to agree with, checked at its start:
                                     {account: ACCOUNT, prefixes: every service's path prefix here and AGERATING,
                                     user: --user, tokenPrefix: what the access tokens taken as signed in start with}
  GET  /_log                         what the fake received, for the tests: requests (method, path, status, whether the
                                     client id and a bearer token came; not the account routes' calls), the
                                     device-code polls, scrobbles, watches, revokes, connections: the account routes'
                                     calls (op store, list, token or delete, service, profile, status, whether a
                                     rejected token came), refreshes: how many tokens those calls refreshed, and
                                     grants: the services' authorizations and code exchanges (below; whether the PKCE
                                     verifier or the client secret matched, never their values)
  POST /_fail?writes=1|0[&status=<code>]
                                     from now on, watched and unwatched marks fail or go through again: a mark the
                                     app couldn't send waits in its queue
  POST /_fail?ratings=<service>[,…][&status=<code>]
                                     from now on, those services' rating writes fail; empty: none does. Without
                                     writes= the marks fail or go through as before. A failing write answers status:
                                     503 without it (the service down, which the app retries without counting an
                                     attempt); a 4xx is an answer about the write, which counts one, and the app gives
                                     the write up after its fifth
  POST /_rating?service=<trakt|simkl|mdblist>&imdb=<tt>[&kind=movies|shows][&rating=<1-10>]
                                     the user's rating of the title on that service, as if given there (no rating:
                                     none); not a write the app made
  POST /_fail?watchlist=<service>[,…][&status=<code>]
                                     from now on, those services' watchlist writes fail (status: as above); empty: none
                                     does
Each service's watchlist (Trakt's and MDBList's watchlist, Simkl's plan to watch, MyAnimeList's plan-to-watch, AniList's
PLANNING list) is kept in memory: the reads list it, the app's adds and removes change it, and /_log has every write
(watchlistWrites: service, op add or remove, the title's IMDb id or anime id, status) and what each holds (watchlists):
  POST /sync/watchlist[/remove]                 Trakt's, by the titles' ids
  POST /simkl/sync/add-to-list                  Simkl's: an item `to` plantowatch goes on it
  POST /simkl/sync/history/remove               Simkl's: the item leaves it (and its history, which the fake hasn't)
  POST /mdblist/watchlist/items/{add,remove}    MDBList's
  GET  /mdblist/watchlist/items                 MDBList's list
  GET  /simkl/sync/all-items/{movies|shows|anime}/plantowatch  Simkl's list, by type
  PUT, DELETE /mal/v2/anime/<id>/my_list_status MyAnimeList's: status plan_to_watch puts the entry on it
  GET  /mal/v2/anime/<id>?fields=my_list_status the entry's status (plan_to_watch while on it)
  POST /anilist                                 AniList's SaveMediaListEntry (status PLANNING), Media's mediaListEntry
                                                and DeleteMediaListEntry
The Tortuga account server's tracker connection routes (shared/…/trackers/connections/TrackerConnectionsApi.kt), in
memory, under /tortuga: any `Authorization: Bearer` passes (none: 401), errors are the server's {"code", "message"}.
  GET    /tortuga/v2/trackers/connections  the stored connections, oldest first, without tokens
  POST   /tortuga/v2/trackers/connections  stores a sign-in, replacing the profile's connection to that service (its
                                           connectedAtMillis after the replaced one's); 400 invalid_request for an
                                           unknown service or no access token
  POST   /tortuga/v2/trackers/token        the stored access token and its seconds left (null: it doesn't expire), new
                                           fake tokens first when the request names it rejected or it expires within
                                           300 s; 410 tracker_disconnected when the connection is gone or the request's
                                           connectedAtMillis names another sign-in
  DELETE /tortuga/v2/trackers/connections/<profile>/<service>
                                           204; forgets the connection and counts a revoke when there was one, as the
                                           account server revokes at the service
With the app's --dev-trackers-connected every service is connected to the fake without
signing in (stored at /tortuga at the start, access token fake-access-dev, no expiry), each under its own prefix here,
and these answer Detail's ratings row for the RATINGS titles:
  GET  /mdblist/imdb/{movie|show}/<tt>  MDBList's media lookup: Rotten Tomatoes, IMDb, TMDB, Metacritic, Letterboxd, Trakt
  GET  /{movies|shows}/<tt>/ratings     Trakt's rating (0-10) and votes
  GET  /simkl/redirect?imdb=<tt>        301 to the title's Simkl page (unknown: to the search), then
  GET  /simkl/{movies|anime}/<id>       its simkl, imdb and mal ratings
  GET  /mal/v2/anime/<mal id>           MyAnimeList's mean and scoring users
  POST /anilist                         AniList's Media averageScore by id or idMal
  and empty lists for the other services' catalog rows (MDBList lists and watchlist, MyAnimeList's airing ranking
  unless --airing, which lists One Piece there, Simkl's plan to watch: /simkl/sync/all-items/{movies|shows|anime}/plantowatch).
The user's own ratings (Detail's "Rate"), per service in memory and in /_log (ratingWrites: each title a write named,
userRatings: what each service holds):
  GET  /sync/ratings/{movies|shows}                    Trakt's: that kind's ratings, as rated items
  GET  /simkl/sync/ratings/{movies|shows|anime}/<scores>  Simkl's: that type's ratings, under its name
  GET  /mdblist/sync/ratings                           MDBList's: every kind's
  POST /sync/ratings[/remove], /simkl/sync/ratings[/remove], /mdblist/sync/ratings[/remove]
                                                       a rating given or removed, by the titles' ids; 503 while the
                                                       service's rating writes fail (/_fail?ratings=)
The anime rows list sequels, a second cour and a film (ANIME_LIST), which the app opens as the Cinemeta titles Fribb
maps them to:
  GET  /mal/v2/users/@me/animelist      MyAnimeList's plan-to-watch list
  POST /anilist                         AniList's Viewer (the fake's user: its id and name) and its PLANNING
                                        MediaListCollection
The services' sign-in, as the Tortuga server's tracker OAuth relay reaches it on a development stack (server/README.md,
Development: TORTUGA_DEV_TRACKER_OAUTH_URL for the code exchange, TORTUGA_DEV_TRACKER_OAUTH_BROWSER_URL for the
browser; server/compose.dev.yaml runs this fake as its `trackers` service), each path the service's under its prefix
here. An authorization is approved at once, as a person who signed in and allowed it would be; nothing shows.
  GET  /mal/v1/oauth2/authorize          302 to redirect_uri with a one-time code and the state; MyAnimeList's PKCE
                                         `plain` code_challenge required (else 400)
  POST /mal/v1/oauth2/token              authorization_code: the tokens, once, for a code with its client_id, its
                                         redirect_uri and a code_verifier equal to its challenge, else 400 invalid_grant;
                                         refresh_token: new tokens (MyAnimeList's refreshes, the server's and the app's)
  GET  /anilist/api/v2/oauth/authorize   302 to redirect_uri with a one-time code and the state
  POST /anilist/api/v2/oauth/token       the tokens, once, for a code with its client_id and redirect_uri and the client
                                         secret (--anilist-secret), else 400 invalid_grant or 401 invalid_client
  GET  /mal/v2/users/@me                 MyAnimeList's user (--user), which the app looks up once signed in
The token endpoints take forms or JSON.
A kids profile's age database, agerating's release (github.com/edde746/agerating) under AGERATING: its packed list of
AGES (the kids tests' titles: suggested 0, 6 and 12), or of the uncompressed small database --agerating-db names,
packed as agerating's pipeline/pack_ids.py does; any other file is 404:
  GET  /github/edde746/agerating/releases/latest  302 to its one release's tag page, …/releases/tag/AGERATING_TAG
  GET  /github/edde746/agerating/releases/download/AGERATING_TAG/agerating-ids.bin.gz  the packed list, gzip'd
  GET  /github/edde746/agerating/releases/download/AGERATING_TAG/SHA256SUMS  its line `<sha256 hex>  agerating-ids.bin.gz`
Tokens are fake and per run; nothing here talks to the real services.
"""
import argparse
import gzip
import hashlib
import http.server
import json
import re
import sqlite3
import threading
import time
import urllib.parse

from test_addon import art_png

# The account server's tracker connection routes (TrackerConnectionsApi.kt), under ACCOUNT here.
ACCOUNT = "/tortuga"
CONNECTIONS = "/v2/trackers/connections"
TOKEN = "/v2/trackers/token"
# TrackerService ids.
SERVICES = ("trakt", "simkl", "mdblist", "mal", "anilist")
# Each service's API under its prefix here (the app's devTrackerRedirect sends each service's host to it).
PREFIXES = {"trakt": "", "simkl": "/simkl", "mdblist": "/mdblist", "mal": "/mal", "anilist": "/anilist"}
# The access tokens this fake takes as signed in: those it issues, and the app's connected session's.
TOKEN_PREFIX = "fake-access-"
# The token route refreshes a token that expires within this many seconds (TrackerConnectionsApi.REFRESH_WINDOW_SECONDS).
REFRESH_WINDOW_SECONDS = 300
# The services' OAuth endpoints (the server's sign-in relay against this fake), each path's service.
AUTHORIZE = {"/mal/v1/oauth2/authorize": "mal", "/anilist/api/v2/oauth/authorize": "anilist"}
OAUTH_TOKEN = {"/mal/v1/oauth2/token": "mal", "/anilist/api/v2/oauth/token": "anilist"}
# The AniList client secret the development stack's server has (server/dev/anilist-client-secret).
ANILIST_SECRET = "tortuga-dev-anilist-secret"

# The watchlist: Cinemeta titles, so the app's addons open them.
WATCHLIST = [
    ("movie", "The Shawshank Redemption", 1994, "tt0111161", 278),
    ("show", "Breaking Bad", 2008, "tt0903747", 1396),
    ("show", "Chernobyl", 2019, "tt7366338", 87108),
]
# The titles a watchlist entry the app added can name, by IMDb id: (title, year, TMDB id); any other shows its id.
TITLES = {imdb: (title, year, tmdb) for _, title, year, imdb, tmdb in WATCHLIST}
TITLES.update({"tt0118688": ("Batman & Robin", 1997, 415), "tt0245429": ("Spirited Away", 2001, 129)})

# Trakt's related titles (GET /movies/<imdb>/related, the client id alone, no account) of the one title the fake knows:
# test_addon.py's tortuga-related names this IMDb id. The title itself is among them, which the app leaves out.
RELATED = {
    "tt0050083": [
        ("The Godfather", 1972, "tt0068646", 238),
        ("Cool Hand Luke", 1967, "tt0061512", 903),
        ("Goodfellas", 1990, "tt0099685", 769),
        ("12 Angry Men", 1957, "tt0050083", 389),
    ],
}

# A Trakt public list (GET /lists/<id>, its items): id, slug, name, then Cinemeta titles, (type, title, year, imdb,
# tmdb) each, more than one page of Trakt's default 10.
PUBLIC_LIST = (1234, "tortuga-public-list", "Tortuga Trakt List", [
    ("movie", "The Godfather", 1972, "tt0068646", 238),
    ("show", "Breaking Bad", 2008, "tt0903747", 1396),
    ("movie", "Goodfellas", 1990, "tt0099685", 769),
    ("movie", "Cool Hand Luke", 1967, "tt0061512", 903),
    ("show", "Chernobyl", 2019, "tt7366338", 87108),
    ("movie", "12 Angry Men", 1957, "tt0050083", 389),
    ("movie", "The Shawshank Redemption", 1994, "tt0111161", 278),
    ("movie", "Spirited Away", 2001, "tt0245429", 129),
    ("show", "The Wire", 2002, "tt0306414", 1438),
    ("movie", "Pulp Fiction", 1994, "tt0110912", 680),
    ("movie", "Seven Samurai", 1954, "tt0047478", 346),
    ("show", "Fargo", 2014, "tt2802850", 60622),
])

# agerating's releases (GitHub, github.com under this prefix): the age database a kids profile downloads.
AGERATING = "/github"
AGERATING_REPOSITORY = AGERATING + "/edde746/agerating"
# The one release: the app resolves the latest release to its tag, then takes the files from that tag.
AGERATING_TAG = "v2026.11.02"
AGERATING_RELEASE = f"{AGERATING_REPOSITORY}/releases/download/{AGERATING_TAG}/"
AGERATING_ASSET = "agerating-ids.bin.gz"
# The small database's tables, in the packed list's order.
AGERATING_TABLES = ("imdb", "tmdb_movie", "tmdb_tv", "tvdb_series", "tvdb_movie")
# The fixture's suggested ages by IMDb id (agerating's `imdb` ids: the number without tt): Toy Story (0) and
# Paddington (6) pass the Kids cap; The Shawshank Redemption (12) doesn't.
AGES = {114709: 0, 1109624: 6, 111161: 12}


def agerating_pack(tables):
    """agerating's packed list of tables ({name: [(id, age)] in ascending id order}), as its pipeline/pack_ids.py
    writes it: "AGID", version 1, the table count, then per table its name, its row count (u32 LE), each id's gap from
    the previous one (the first from 0) as a LEB128 varint, and the ages."""
    data = bytearray(b"AGID")
    data += bytes([1, len(tables)])
    for name, rows in tables.items():
        data += bytes([len(name)]) + name.encode("ascii") + len(rows).to_bytes(4, "little")
        previous = 0
        for id_, _ in rows:
            n = id_ - previous
            while n > 0x7F:
                data.append(n & 0x7F | 0x80)
                n >>= 7
            data.append(n)
            previous = id_
        data += bytes(age for _, age in rows)
    return bytes(data)


def agerating_asset(path=None):
    """(the gzip'd packed list, its SHA256SUMS) of the small database at path, or of AGES."""
    if path:
        db = sqlite3.connect(f"file:{path}?mode=ro", uri=True)
        tables = {t: db.execute(f"SELECT id, age FROM {t} ORDER BY id").fetchall() for t in AGERATING_TABLES}
        db.close()
    else:
        tables = {t: sorted(AGES.items()) if t == "imdb" else [] for t in AGERATING_TABLES}
    asset = gzip.compress(agerating_pack(tables), compresslevel=9, mtime=0)
    return asset, f"{hashlib.sha256(asset).hexdigest()}  {AGERATING_ASSET}\n".encode()


# Detail's ratings row (--dev-trackers-connected): per Cinemeta title, what each fake service answers. Spirited Away
# has every source (MyAnimeList and AniList by its anime ids, which the app maps from the IMDb id with Fribb's
# dataset); Batman & Robin is rotten and spilled.
RATINGS = {
    "tt0245429": {
        "mdblist": {"imdb": (8.6, 900000), "metacritic": (96, 41), "trakt": (87, 52000), "tomatoes": (96, 190),
                    "popcorn": (96, 250000), "tmdb": (85, 17000), "letterboxd": (4.5, 1500000)},
        "trakt": (8.7, 52000), "simkl": ("anime", 37207, {"simkl": (8.8, 4100), "imdb": (8.6, 900000), "mal": (8.77, 1900000)}),
        "mal": 199, "anilist": 199, "mean": (8.77, 1900000), "average": 86,
    },
    "tt0118688": {
        "mdblist": {"imdb": (3.8, 270000), "metacritic": (28, 21), "trakt": (41, 9000), "tomatoes": (11, 90),
                    "popcorn": (16, 250000), "tmdb": (43, 4600), "letterboxd": (1.6, 120000)},
        "trakt": (4.1, 9000), "simkl": ("movies", 53547, {"simkl": (4.2, 900), "imdb": (3.8, 270000)}),
    },
    "tt0111161": {
        "mdblist": {"imdb": (9.3, 3207270), "metacritic": (82, 22), "trakt": (91, 43118), "tomatoes": (89, 147),
                    "popcorn": (98, 53439), "tmdb": (87, 30786), "letterboxd": (4.6, 2908116)},
        "trakt": (9.1, 43118), "simkl": ("movies", 53536, {"simkl": (8.9, 13542), "imdb": (9.3, 3207270)}),
    },
}

# The MyAnimeList and AniList plan-to-watch rows: (MyAnimeList id, AniList id, type, title, year), each its own entry as
# the services list seasons and cours. My Hero Academia 2 is Cinemeta's tt5626028 season 2; Black Lagoon: The Second
# Barrage is tt0962826's season 1 from episode 13; Your Name. is the film tt5311514. A None id: not on that service's row.
ANIME_LIST = [
    (33486, 21856, "tv", "Boku no Hero Academia 2nd Season", 2017),
    (1519, None, "tv", "Black Lagoon: The Second Barrage", 2006),
    (32281, 21519, "movie", "Kimi no Na wa.", 2016),
]
# The anime the app can add to the anime lists besides ANIME_LIST's, by MyAnimeList and AniList id (Spirited Away is 199
# on both, as Fribb's fixture maps tt0245429): (type, title, year).
ANIME_TITLES = {199: ("movie", "Sen to Chihiro no Kamikakushi", 2001)}
for _mal, _anilist, _kind, _title, _year in ANIME_LIST:
    ANIME_TITLES.setdefault(_mal, (_kind, _title, _year))
    if _anilist:
        ANIME_TITLES.setdefault(_anilist, (_kind, _title, _year))
# AniList names a list entry apart from its media: an entry's id is its media's plus this.
ANILIST_ENTRY = 900000
# MyAnimeList's airing ranking: One Piece with --airing (a series of over 1,100 episodes), else empty.
AIRING = []


def mal_nodes(entries, base):
    """MyAnimeList's `{data: [{node}], paging}` of [entries], (MyAnimeList id, type, title, year) each."""
    return {"data": [{"node": {"id": mal, "title": title, "media_type": kind, "start_season": {"year": year},
                               "main_picture": {"large": f"{base}/poster/mal-{mal}.png"}}}
                     for mal, kind, title, year in entries],
            "paging": {}}


def mal_planning(planned, base):
    """MyAnimeList's plan-to-watch list: the [planned] entries, by MyAnimeList id."""
    return mal_nodes([(mal, *ANIME_TITLES.get(mal, ("tv", str(mal), None))) for mal in planned], base)


def anilist_planning(planned, base):
    """AniList's PLANNING MediaListCollection of the [planned] entries, by AniList id, in one chunk."""
    entries = []
    for anilist in planned:
        kind, title, year = ANIME_TITLES.get(anilist, ("tv", str(anilist), None))
        entries.append({"media": {"id": anilist, "format": "MOVIE" if kind == "movie" else "TV", "seasonYear": year,
                                  "title": {"userPreferred": title},
                                  "coverImage": {"large": f"{base}/poster/anilist-{anilist}.png"}}})
    return {"data": {"MediaListCollection": {"hasNextChunk": False, "lists": [{"isCustomList": False, "entries": entries}]}}}


def watchlist_items(body):
    """The (kind, ids) of a Trakt-shaped watchlist body: {"movies": [{"ids": …}], "shows": […]}."""
    return [(kind, item.get("ids", {})) for kind in ("movies", "shows") for item in body.get(kind, [])]


def listed_media(entry):
    """A watchlist entry ({kind, ids}) as Simkl's and MDBList's lists show it: its title, year and ids."""
    imdb = entry["ids"].get("imdb")
    title, year, tmdb = TITLES.get(imdb, (imdb, None, entry["ids"].get("tmdb")))
    return {"title": title, "year": year, "release_year": year, "ids": {"imdb": imdb, "tmdb": tmdb}}


def watchlist_get(path, query, base, lists):
    """(status, body, headers) for a read of a service's watchlist as [lists] (State.watchlists) holds it, or None for a
    path that isn't one."""
    parts = path.strip("/").split("/")
    if path == "/sync/watchlist":
        entries = []
        for rank, entry in enumerate(lists["trakt"], start=1):
            kind = "movie" if entry["kind"] == "movies" else "show"
            imdb = entry["ids"].get("imdb")
            title, year, tmdb = TITLES.get(imdb, (imdb, None, entry["ids"].get("tmdb")))
            entries.append({"rank": rank, "listed_at": "2026-09-01T00:00:00.000Z", "type": kind,
                            kind: media(kind, title, year, imdb, tmdb, base)})
        return 200, entries, {"X-Pagination-Page": "1", "X-Pagination-Page-Count": "1"}
    if path == "/mdblist/watchlist/items":
        listed = {kind: [listed_media(e) for e in lists["mdblist"] if e["kind"] == kind] for kind in ("movies", "shows")}
        return 200, {**listed, "pagination": {"has_more": False}}, {}
    if len(parts) == 5 and parts[:3] == ["simkl", "sync", "all-items"] and parts[4] == "plantowatch":
        node = "movie" if parts[3] == "movies" else "show"
        return 200, {parts[3]: [{node: listed_media(e)} for e in lists["simkl"] if e["kind"] == parts[3]]}, {}
    if urllib.parse.unquote(path) == "/mal/v2/users/@me/animelist":
        return 200, mal_planning(lists["mal"], base), {}
    if len(parts) == 4 and parts[:3] == ["mal", "v2", "anime"] and "my_list_status" in query:
        mal = int(parts[3]) if parts[3].isdigit() else None
        status = {"my_list_status": {"status": "plan_to_watch"}} if mal in lists["mal"] else {}
        return 200, {"id": mal, **status}, {}
    return None


def public_list_get(path, query, base):
    """(status, body, headers) for a read of the Trakt public list (PUBLIC_LIST) or its items, or None for a path that
    isn't one."""
    list_id, slug, name, titles = PUBLIC_LIST
    found = re.fullmatch(r"/lists/([^/]+)(?:/items/(movie,show|show,movie|movie|show))?", path)
    if not found:
        return None
    if found.group(1) not in (str(list_id), slug):
        return 404, {"error": "not found"}, {}
    if found.group(2) is None:
        return 200, {"name": name, "description": "The fake Trakt's public list.", "privacy": "public", "type": "personal",
                     "item_count": len(titles), "sort_by": "rank", "sort_how": "asc",
                     "ids": {"trakt": list_id, "slug": slug}, "user": {"username": "tortuga-lists", "ids": {"slug": "tortuga-lists"}}}, {}
    kinds = found.group(2).split(",")
    entries = [{"rank": rank, "id": 9000 + rank, "listed_at": "2026-09-01T00:00:00.000Z", "notes": None, "type": kind,
                kind: media(kind, title, year, imdb, tmdb, base)}
               for rank, (kind, title, year, imdb, tmdb) in enumerate(titles, start=1) if kind in kinds]
    asked = urllib.parse.parse_qs(query)
    page, limit = (max(1, int((asked.get(k) or [d])[0])) for k, d in (("page", "1"), ("limit", "10")))
    pages = max(1, -(-len(entries) // limit))
    return 200, entries[(page - 1) * limit:page * limit], {
        "X-Pagination-Page": str(page), "X-Pagination-Limit": str(limit), "X-Pagination-Page-Count": str(pages),
        "X-Pagination-Item-Count": str(len(entries))}


def ratings_get(path, query, base):
    """(status, body, headers) for a ratings or connected-catalog GET, or None for a path that isn't one."""
    parts = path.strip("/").split("/")
    if path == "/mdblist/lists/user":
        return 200, [], {}
    if path == "/mal/v2/anime/ranking":
        return 200, mal_nodes(AIRING, base), {}
    if path == "/mal/v2/anime/season":
        return 200, {"data": [], "paging": {}}, {}
    if len(parts) == 4 and parts[0] == "mdblist" and parts[1] == "imdb":
        title = RATINGS.get(parts[3])
        if not title:
            return 404, {"error": "not found"}, {}
        return 200, {"ratings": [{"source": s, "value": v, "votes": n} for s, (v, n) in title["mdblist"].items()]}, {}
    if len(parts) == 3 and parts[0] in ("movies", "shows") and parts[2] == "ratings":
        title = RATINGS.get(parts[1])
        if not title:
            return 404, {"error": "not found"}, {}
        rating, votes = title["trakt"]
        return 200, {"rating": rating, "votes": votes, "distribution": {}}, {}
    if path == "/simkl/redirect":
        title = RATINGS.get(urllib.parse.parse_qs(query).get("imdb", [""])[0])
        location = f"https://simkl.com/{title['simkl'][0]}/{title['simkl'][1]}/fake" if title else "https://simkl.com/search/?q="
        return 301, {}, {"Location": location}
    if len(parts) == 3 and parts[0] == "simkl" and parts[1] in ("movies", "tv", "anime"):
        for title in RATINGS.values():
            kind, simkl_id, scores = title["simkl"]
            if kind == parts[1] and str(simkl_id) == parts[2]:
                return 200, {"ids": {"simkl": simkl_id}, "ratings": {k: {"rating": r, "votes": n} for k, (r, n) in scores.items()}}, {}
        return 404, {"error": "not found"}, {}
    if len(parts) == 4 and parts[:3] == ["mal", "v2", "anime"]:
        for title in RATINGS.values():
            if str(title.get("mal")) == parts[3]:
                mean, users = title["mean"]
                return 200, {"id": title["mal"], "title": "fake", "mean": mean, "num_scoring_users": users}, {}
        return 404, {"error": "not_found"}, {}
    return None


# The services whose user ratings the fake keeps (Detail's "Rate"), each with its prefix here.
RATING_PREFIXES = {service: PREFIXES[service] for service in ("trakt", "simkl", "mdblist")}


def rating_route(path):
    """(service, what follows /sync/ratings) for a user-ratings path of RATING_PREFIXES' services, or None."""
    for service, prefix in RATING_PREFIXES.items():
        route = prefix + "/sync/ratings"
        if path == route or path.startswith(route + "/"):
            return service, path[len(route):]
    return None


def ids_match(a, b):
    """Whether two `ids` objects name the same title: an id of the same name equal, as the app matches them."""
    return any(str(a[k]) == str(b[k]) for k in a if k in b)


def user_ratings_get(service, rest, entries):
    """(body, headers) of [service]'s ratings as the app reads them: Trakt's /sync/ratings/<kind> a list of rated
    items, Simkl's /sync/ratings/<type>/<scores> the same under the type's name (`user_rating`), MDBList's
    /sync/ratings every kind's."""
    def rated(kind, score_key):
        node = "movie" if kind == "movies" else "show"
        return [{node: {"ids": e["ids"]}, score_key: e["rating"], "rated_at": "2026-10-24T00:00:00.000Z"}
                for e in entries if e["kind"] == ("shows" if kind == "anime" else kind)]
    kind = rest.strip("/").split("/")[0]
    if service == "trakt":
        return rated(kind, "rating"), {"X-Pagination-Page": "1", "X-Pagination-Page-Count": "1"}
    if service == "simkl":
        return {kind: rated(kind, "user_rating")}, {}
    return {"movies": rated("movies", "rating"), "shows": rated("shows", "rating")}, {}


def store_rating(entries, kind, ids, rating):
    """[entries] with the title [ids] names rated [rating] (None: not rated)."""
    kept = [e for e in entries if not (e["kind"] == kind and ids_match(e["ids"], ids))]
    return kept + ([{"kind": kind, "ids": ids, "rating": rating}] if rating is not None else [])


def anilist_post(body, base, user, planned):
    """AniList's Viewer, its PLANNING list ([planned]), and the Media query by `id` or `idMal`: its averageScore, or
    AniList's 404."""
    query = body.get("query") or ""
    if "Viewer" in query:
        return 200, {"data": {"Viewer": {"id": 1, "name": user}}}
    if "MediaListCollection(" in query:
        return 200, anilist_planning(planned, base)
    variables = body.get("variables") or {}
    for title in RATINGS.values():
        if "anilist" in title and (variables.get("id") == title["anilist"] or variables.get("idMal") == title["mal"]):
            return 200, {"data": {"Media": {"id": title["anilist"], "averageScore": title["average"]}}}
    if "Media(" in query:
        return 404, {"errors": [{"message": "Not Found.", "status": 404}], "data": {"Media": None}}
    return 200, {"data": {}}


class State:
    def __init__(self, approve_after, user, anilist_secret):
        # Re-entered: the token route mints tokens while it holds it.
        self.lock = threading.RLock()
        self.approve_after = approve_after
        self.user = user
        self.anilist_secret = anilist_secret
        self.requests = []
        self.polls = 0
        self.approved = False
        self.tokens = 0
        self.scrobbles = []
        self.history = []
        self.watched = []
        self.revoked = 0
        self.fail_writes = False
        # What a failing mark or rating write answers (/_fail's status=).
        self.fail_status = 503
        # The user's ratings each service holds ({kind, ids, rating}), the rating writes the app made, and the
        # services whose rating writes fail.
        self.user_ratings = {service: [] for service in RATING_PREFIXES}
        self.rating_writes = []
        self.fail_ratings = set()
        # Each service's watchlist: Trakt's, Simkl's and MDBList's titles ({kind: movies|shows, ids}), MyAnimeList's and
        # AniList's entries (their ids); the watchlist writes the app made; the services whose watchlist writes fail.
        self.watchlists = {
            "trakt": [{"kind": "movies" if kind == "movie" else "shows", "ids": {"imdb": imdb, "tmdb": tmdb}}
                      for kind, _, _, imdb, tmdb in WATCHLIST],
            "simkl": [],
            "mdblist": [],
            "mal": [mal for mal, _, _, _, _ in ANIME_LIST if mal],
            "anilist": [anilist for _, anilist, _, _, _ in ANIME_LIST if anilist],
        }
        self.watchlist_writes = []
        self.fail_watchlist = set()
        # The account routes': the stored connections by (profile ID, service), the calls, the tokens they refreshed.
        self.stored = {}
        self.connections = []
        self.refreshes = 0
        # The services' sign-ins: the codes authorize issued and not yet exchanged, and what each step answered.
        self.codes = {}
        self.codes_issued = 0
        self.grants = []

    def log(self):
        with self.lock:
            return {
                "requests": list(self.requests),
                "polls": self.polls,
                "approved": self.approved,
                "tokensIssued": self.tokens,
                "scrobbles": list(self.scrobbles),
                "history": list(self.history),
                "watched": list(self.watched),
                "revoked": self.revoked,
                "failWrites": self.fail_writes,
                "failStatus": self.fail_status,
                "ratingWrites": list(self.rating_writes),
                "userRatings": {service: list(entries) for service, entries in self.user_ratings.items()},
                "failRatings": sorted(self.fail_ratings),
                "watchlistWrites": list(self.watchlist_writes),
                "watchlists": {service: list(entries) for service, entries in self.watchlists.items()},
                "failWatchlist": sorted(self.fail_watchlist),
                "connections": list(self.connections),
                "refreshes": self.refreshes,
                "grants": list(self.grants),
            }

    def watchlist_write(self, service, op, items):
        """[service]'s watchlist write ([op]: add or remove) of [items], (kind, ids) each for Trakt, Simkl and MDBList,
        entry ids for the anime lists: logged, and applied unless the service's watchlist writes fail. Returns the
        status to answer with, None when it went through."""
        with self.lock:
            failing = service in self.fail_watchlist
            entries = self.watchlists[service]
            for item in items:
                if isinstance(item, int):
                    title = str(item)
                    if not failing:
                        entries[:] = [e for e in entries if e != item] + ([item] if op == "add" else [])
                else:
                    kind, ids = item
                    title = ids.get("imdb") or json.dumps(ids, sort_keys=True)
                    if not failing:
                        kept = [e for e in entries if not (e["kind"] == kind and ids_match(e["ids"], ids))]
                        entries[:] = kept + ([{"kind": kind, "ids": ids}] if op == "add" else [])
                self.watchlist_writes.append({"service": service, "op": op, "title": title,
                                              "status": self.fail_status if failing else 200, "at": time.time()})
            return self.fail_status if failing else None


def item_ids(body):
    """The movie's or show's ids of a scrobble or history body, and the episode (season, number) if any."""
    if "movie" in body:
        return "movie", body["movie"].get("ids", {}), None
    if "show" in body:
        episode = body.get("episode") or {}
        return "episode", body["show"].get("ids", {}), (episode.get("season"), episode.get("number"))
    return None, {}, None


def listed(connection):
    """A stored connection as the account routes show it (TrackerConnection): without its tokens."""
    return {k: connection[k] for k in ("profileId", "service", "username", "connectedAtMillis")}


def media(kind, title, year, imdb, tmdb, base):
    return {
        "title": title,
        "year": year,
        "ids": {"trakt": tmdb, "slug": title.lower().replace(" ", "-"), "imdb": imdb, "tmdb": tmdb},
        "overview": f"{title} ({year}), from the fake Trakt's watchlist.",
        # Trakt sends image URLs without a scheme; the app adds https:, so these carry theirs.
        "images": {"poster": [f"{base}/poster/{imdb}.png"], "fanart": []},
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8772)
    ap.add_argument("--approve-after", type=int, default=5, help="device-code polls before the fake approves")
    ap.add_argument("--user", default="tortuga-tester")
    ap.add_argument("--anilist-secret", default=ANILIST_SECRET, help="the AniList client secret the token endpoint takes")
    ap.add_argument("--airing", action="store_true", help="MyAnimeList's airing ranking lists One Piece (mal 21)")
    ap.add_argument("--agerating-db", help="an uncompressed agerating-ids.db (the small database) whose packed list to "
                                           "serve instead of the fixture's AGES")
    a = ap.parse_args()
    if a.airing:
        AIRING.append((21, "tv", "One Piece", 1999))
    state = State(a.approve_after, a.user, a.anilist_secret)
    asset, sums = agerating_asset(a.agerating_db)
    agerating = {AGERATING_ASSET: asset, "SHA256SUMS": sums}

    class Handler(http.server.BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, fmt, *args):
            pass

        def base(self):
            return f"http://{self.headers['Host']}"

        def body(self):
            length = int(self.headers.get("Content-Length") or 0)
            raw = self.rfile.read(length) if length else b""
            # The services' token endpoints take forms; everything else here JSON.
            if (self.headers.get("Content-Type") or "").startswith("application/x-www-form-urlencoded"):
                return {k: v[0] for k, v in urllib.parse.parse_qs(raw.decode()).items()}
            try:
                return json.loads(raw) if raw else {}
            except ValueError:
                return {}

        def record(self, status):
            path = urllib.parse.urlsplit(self.path).path
            # The tests' own calls (/_log, /_fail) aren't the app's.
            if path.startswith("/_"):
                return
            with state.lock:
                state.requests.append({
                    "method": self.command,
                    "path": path,
                    "status": status,
                    "clientId": bool(self.headers.get("trakt-api-key")),
                    "bearer": (self.headers.get("Authorization") or "").startswith("Bearer " + TOKEN_PREFIX),
                    "at": time.time(),
                })

        def reply(self, status, payload=None, content_type="application/json", headers=None):
            self.record(status)
            self.send(status, payload, content_type, headers)

        def send(self, status, payload=None, content_type="application/json", headers=None):
            self.send_response(status)
            if status == 204:
                self.end_headers()
                return
            body = payload if isinstance(payload, bytes) else json.dumps(payload if payload is not None else {}).encode()
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(body)))
            for k, v in (headers or {}).items():
                self.send_header(k, v)
            self.end_headers()
            self.wfile.write(body)

        def tokens(self):
            with state.lock:
                state.tokens += 1
                n = state.tokens
            return {
                "access_token": f"{TOKEN_PREFIX}{n}",
                "refresh_token": f"fake-refresh-{n}",
                "token_type": "bearer",
                "expires_in": 7776000,
                "scope": "public",
                "created_at": int(time.time()),
            }

        def authorize(self, service, query):
            """[service]'s authorization page, approved at once: back to redirect_uri with a one-time code and the state."""
            def param(name):
                return (query.get(name) or [""])[0]
            redirect, challenge, method = param("redirect_uri"), param("code_challenge"), param("code_challenge_method")
            if not redirect.startswith(("http://", "https://")):
                problem = "no http(s) redirect_uri"
            elif param("response_type") != "code" or not param("client_id") or not param("state"):
                problem = "response_type=code, a client_id and a state are needed"
            elif service == "mal" and not (challenge and method == "plain"):
                problem = "MyAnimeList takes a plain code_challenge"
            else:
                problem = None
            with state.lock:
                code = None
                if problem is None:
                    state.codes_issued += 1
                    code = f"fake-code-{state.codes_issued}"
                    state.codes[code] = {"service": service, "clientId": param("client_id"), "redirectUri": redirect,
                                         "challenge": challenge or None}
                state.grants.append({"service": service, "op": "authorize", "clientId": param("client_id"),
                                     "pkce": method or None, "status": 400 if problem else 302, "at": time.time()})
            if problem:
                return self.reply(400, {"error": "invalid_request", "error_description": problem})
            query = urllib.parse.urlencode({"code": code, "state": param("state")})
            self.reply(302, b"", "text/plain", {"Location": redirect + ("&" if "?" in redirect else "?") + query})

        def exchange(self, service, body):
            """[service]'s token endpoint: a code authorize issued, exchanged once, or (MyAnimeList) a refresh."""
            grant = body.get("grant_type")
            if service == "mal" and grant == "refresh_token":
                valid = str(body.get("refresh_token") or "").startswith("fake-refresh-")
                with state.lock:
                    state.grants.append({"service": service, "op": "refresh", "status": 200 if valid else 400, "at": time.time()})
                return self.reply(200, self.tokens()) if valid else self.reply(400, {"error": "invalid_grant"})
            verifier, secret = body.get("code_verifier"), body.get("client_secret")
            with state.lock:
                issued = state.codes.pop(body.get("code"), None) if grant == "authorization_code" else None
                pkce = issued is not None and verifier is not None and verifier == issued["challenge"]
                if issued is None or issued["service"] != service or issued["clientId"] != body.get("client_id") \
                        or issued["redirectUri"] != body.get("redirect_uri") or (service == "mal" and not pkce):
                    status, error = 400, "invalid_grant"
                elif service == "anilist" and secret != state.anilist_secret:
                    status, error = 401, "invalid_client"
                else:
                    status, error = 200, None
                state.grants.append({"service": service, "op": "token", "status": status, "verifier": pkce,
                                     "secret": secret is not None and secret == state.anilist_secret, "at": time.time()})
            if error:
                return self.reply(status, {"error": error})
            self.reply(200, self.tokens())

        def account(self, path, body):
            """A call to the account server's tracker routes (under ACCOUNT), answered as TrackerConnectionsApi.kt
            specifies and logged in connections instead of requests."""
            route = path[len(ACCOUNT):]
            parts = route.split("/")
            if (self.command, route) == ("GET", CONNECTIONS):
                op, profile, service = "list", None, None
            elif (self.command, route) == ("POST", CONNECTIONS):
                op, profile, service = "store", body.get("profileId"), body.get("service")
            elif (self.command, route) == ("POST", TOKEN):
                op, profile, service = "token", body.get("profileId"), body.get("service")
            elif self.command == "DELETE" and route.startswith(CONNECTIONS + "/") and len(parts) == 6:
                op, profile, service = "delete", urllib.parse.unquote(parts[4]), urllib.parse.unquote(parts[5])
            else:
                return self.reply(404, {"code": "not_found", "message": f"no route {self.command} {route}"})
            key = (profile, service)
            access = body.get("accessToken")
            with state.lock:
                now = time.time()
                stored = state.stored.get(key)
                if not (self.headers.get("Authorization") or "").startswith("Bearer "):
                    status, payload = 401, {"code": "unauthorized", "message": "no session"}
                elif op != "list" and (service not in SERVICES or not profile):
                    status, payload = 400, {"code": "invalid_request", "message": f"unknown service {service!r} or no profile ID"}
                elif op == "list":
                    oldest_first = sorted(state.stored.values(), key=lambda c: c["connectedAtMillis"])
                    status, payload = 200, {"connections": [listed(c) for c in oldest_first]}
                elif op == "store" and not (isinstance(access, str) and access.strip()):
                    status, payload = 400, {"code": "invalid_request", "message": "no access token"}
                elif op == "store":
                    expires_in = body.get("expiresInSeconds")
                    state.stored[key] = {
                        "profileId": profile,
                        "service": service,
                        "username": body.get("username"),
                        # It names the sign-in: a reconnect moves it, also within the same millisecond.
                        "connectedAtMillis": max(int(now * 1000), stored["connectedAtMillis"] + 1 if stored else 0),
                        "accessToken": access,
                        "refreshToken": body.get("refreshToken"),
                        "expiresAt": None if expires_in is None else now + expires_in,
                    }
                    status, payload = 200, listed(state.stored[key])
                elif op == "token" and (stored is None or stored["connectedAtMillis"] != body.get("connectedAtMillis")):
                    status, payload = 410, {"code": "tracker_disconnected", "message": "no such connection, or another sign-in"}
                elif op == "token":
                    expiring = stored["expiresAt"] is not None and stored["expiresAt"] - now <= REFRESH_WINDOW_SECONDS
                    if body.get("rejected") == stored["accessToken"] or expiring:
                        fresh = self.tokens()
                        stored.update(accessToken=fresh["access_token"], refreshToken=fresh["refresh_token"],
                                      expiresAt=now + fresh["expires_in"])
                        state.refreshes += 1
                    left = None if stored["expiresAt"] is None else max(0, int(stored["expiresAt"] - now))
                    status, payload = 200, {"accessToken": stored["accessToken"], "expiresInSeconds": left}
                else:
                    # The account server revokes the connection it deleted at the service.
                    if state.stored.pop(key, None) is not None:
                        state.revoked += 1
                    status, payload = 204, None
                state.connections.append({"op": op, "service": service, "profile": profile, "status": status,
                                          "rejected": body.get("rejected") is not None, "at": now})
            self.send(status, payload)

        def do_GET(self):
            url = urllib.parse.urlsplit(self.path)
            path = url.path
            if path == "/_log":
                return self.reply(200, state.log())
            if path == "/_config":
                prefixes = sorted(set(PREFIXES.values()) | {AGERATING})
                return self.reply(200, {"account": ACCOUNT, "prefixes": prefixes,
                                        "user": state.user, "tokenPrefix": TOKEN_PREFIX})
            if path.startswith(ACCOUNT + "/"):
                return self.account(path, {})
            if path == "/activate" or path.startswith("/activate/"):
                page = b"<html><body><h1>Fake Trakt</h1><p>This code is approved automatically.</p></body></html>"
                return self.reply(200, page, "text/html")
            if path.startswith("/poster/"):
                return self.reply(200, art_png(path.removeprefix("/poster/").removesuffix(".png"), "poster"), "image/png")
            if path == "/users/settings":
                return self.reply(200, {"user": {"username": state.user, "private": False, "name": "Tortuga Tester", "vip": False}})
            with state.lock:
                listed = watchlist_get(path, url.query, self.base(), state.watchlists)
            if listed is not None:
                status, payload, headers = listed
                return self.reply(status, payload, headers=headers)
            listed = public_list_get(path, url.query, self.base())
            if listed is not None:
                status, payload, headers = listed
                return self.reply(status, payload, headers=headers)
            if path in ("/users/me/lists", "/movies/trending", "/shows/trending") or path.startswith("/recommendations/"):
                return self.reply(200, [], headers={"X-Pagination-Page": "1", "X-Pagination-Page-Count": "1"})
            found = re.fullmatch(r"/(movies|shows)/([^/]+)/related", path)
            if found:
                titles = RELATED.get(found.group(2), []) if found.group(1) == "movies" else []
                payload = [media("movie", title, year, imdb, tmdb, self.base()) for title, year, imdb, tmdb in titles]
                return self.reply(200, payload, headers={"X-Pagination-Page": "1", "X-Pagination-Page-Count": "1"})
            if path == AGERATING_REPOSITORY + "/releases/latest":
                return self.reply(302, b"", "text/html", headers={"Location": f"{self.base()}{AGERATING_REPOSITORY}/releases/tag/{AGERATING_TAG}"})
            if path.startswith(AGERATING_RELEASE):
                asset = agerating.get(path.removeprefix(AGERATING_RELEASE))
                if asset is None:
                    return self.reply(404, {"error": "not found"})
                return self.reply(200, asset, "application/octet-stream")
            route = rating_route(path)
            if route:
                with state.lock:
                    entries = list(state.user_ratings[route[0]])
                payload, headers = user_ratings_get(*route, entries)
                return self.reply(200, payload, headers=headers)
            if path in AUTHORIZE:
                return self.authorize(AUTHORIZE[path], urllib.parse.parse_qs(url.query))
            if urllib.parse.unquote(path) == "/mal/v2/users/@me":
                return self.reply(200, {"id": 1, "name": state.user})
            answer = ratings_get(path, url.query, self.base())
            if answer is not None:
                status, payload, headers = answer
                return self.reply(status, payload, headers=headers)
            self.reply(404, {"error": "not found"})

        def do_POST(self):
            url = urllib.parse.urlsplit(self.path)
            path = url.path
            body = self.body()
            if path == "/_fail":
                query = urllib.parse.parse_qs(url.query, keep_blank_values=True)
                with state.lock:
                    if "ratings" in query:
                        state.fail_ratings = {s for s in query["ratings"][0].split(",") if s}
                    if "watchlist" in query:
                        state.fail_watchlist = {s for s in query["watchlist"][0].split(",") if s}
                    if "writes" in query or not ({"ratings", "watchlist"} & query.keys()):
                        state.fail_writes = query.get("writes", ["1"])[0] == "1"
                    state.fail_status = int(query.get("status", ["503"])[0])
                    answer = {"failWrites": state.fail_writes, "failRatings": sorted(state.fail_ratings),
                              "failWatchlist": sorted(state.fail_watchlist), "failStatus": state.fail_status}
                return self.reply(200, answer)
            if path == "/_rating":
                query = {k: v[0] for k, v in urllib.parse.parse_qs(url.query).items()}
                service, kind = query.get("service"), query.get("kind", "movies")
                if service not in RATING_PREFIXES or kind not in ("movies", "shows") or not query.get("imdb"):
                    return self.reply(400, {"error": "service, imdb and kind (movies or shows) needed"})
                rating = int(query["rating"]) if query.get("rating") else None
                with state.lock:
                    state.user_ratings[service] = store_rating(state.user_ratings[service], kind, {"imdb": query["imdb"]}, rating)
                    answer = {"userRatings": state.user_ratings[service]}
                return self.reply(200, answer)
            if path.startswith(ACCOUNT + "/"):
                return self.account(path, body)
            if path == "/oauth/device/code":
                return self.reply(200, {
                    "device_code": "fake-device-code",
                    "user_code": "TORTUGA1",
                    "verification_url": f"{self.base()}/activate",
                    "expires_in": 600,
                    "interval": 1,
                })
            if path == "/oauth/device/token":
                with state.lock:
                    state.polls += 1
                    ready = state.polls >= state.approve_after
                    state.approved = state.approved or ready
                if not ready:
                    return self.reply(400, {})
                return self.reply(200, self.tokens())
            if path in OAUTH_TOKEN:
                return self.exchange(OAUTH_TOKEN[path], body)
            if path == "/oauth/token":
                return self.reply(200, self.tokens())
            if path == "/oauth/revoke":
                with state.lock:
                    state.revoked += 1
                return self.reply(200, {})
            if path.startswith("/scrobble/"):
                action = path.rsplit("/", 1)[1]
                kind, ids, episode = item_ids(body)
                progress = float(body.get("progress", 0))
                entry = {"action": action, "kind": kind, "imdb": ids.get("imdb"), "episode": episode, "progress": progress, "at": time.time()}
                with state.lock:
                    state.scrobbles.append(entry)
                    if action == "stop" and progress >= 80:
                        state.watched.append({"imdb": ids.get("imdb"), "kind": kind, "episode": episode, "via": "scrobble"})
                reply = {"id": len(state.scrobbles), "action": "scrobble" if action == "stop" and progress >= 80 else action, "progress": progress}
                return self.reply(201, reply)
            if path in ("/sync/history", "/sync/history/remove"):
                with state.lock:
                    failing = state.fail_writes
                    status = state.fail_status
                if failing:
                    return self.reply(status, {"error": "service unavailable" if status == 503 else "refused"})
                removed = path.endswith("/remove")
                marks = [("movie", m.get("ids", {}), None) for m in body.get("movies", [])]
                for show in body.get("shows", []):
                    for season in show.get("seasons", []):
                        for ep in season.get("episodes", []):
                            marks.append(("episode", show.get("ids", {}), (season.get("number"), ep.get("number"))))
                with state.lock:
                    for kind, ids, episode in marks:
                        state.history.append({"removed": removed, "kind": kind, "imdb": ids.get("imdb"), "episode": episode})
                        if not removed:
                            state.watched.append({"imdb": ids.get("imdb"), "kind": kind, "episode": episode, "via": "history"})
                count = {"movies": sum(1 for m in marks if m[0] == "movie"), "episodes": sum(1 for m in marks if m[0] == "episode")}
                return self.reply(200 if removed else 201, {"deleted" if removed else "added": count})
            route = rating_route(path)
            if route and route[1] in ("", "/remove"):
                service, removed = route[0], route[1] == "/remove"
                items = [(kind, item) for kind in ("movies", "shows") for item in body.get(kind, [])]
                with state.lock:
                    failing = service in state.fail_ratings
                    status = state.fail_status if failing else 200 if removed else 201
                    for kind, item in items:
                        ids = item.get("ids", {})
                        rating = None if removed else item.get("rating")
                        state.rating_writes.append({"service": service, "removed": removed, "kind": kind, "imdb": ids.get("imdb"),
                                                    "rating": rating, "status": status, "at": time.time()})
                        if not failing:
                            state.user_ratings[service] = store_rating(state.user_ratings[service], kind, ids, rating)
                if failing:
                    return self.reply(status, {"error": "service unavailable" if status == 503 else "refused"})
                count = {kind: sum(1 for k, _ in items if k == kind) for kind in ("movies", "shows")}
                return self.reply(status, {"deleted" if removed else "added": count, "not_found": {"movies": [], "shows": []}})
            watchlist = {"/sync/watchlist": ("trakt", "add"), "/sync/watchlist/remove": ("trakt", "remove"),
                         "/simkl/sync/add-to-list": ("simkl", "add"), "/simkl/sync/history/remove": ("simkl", "remove"),
                         "/mdblist/watchlist/items/add": ("mdblist", "add"),
                         "/mdblist/watchlist/items/remove": ("mdblist", "remove")}.get(path)
            if watchlist:
                service, op = watchlist
                items = watchlist_items(body)
                if path == "/simkl/sync/add-to-list":
                    items = [(kind, item.get("ids", {})) for kind in ("movies", "shows")
                             for item in body.get(kind, []) if item.get("to") == "plantowatch"]
                failing = state.watchlist_write(service, op, items)
                if failing:
                    return self.reply(failing, {"error": "service unavailable" if failing == 503 else "refused"})
                count = {kind: sum(1 for k, _ in items if k == kind) for kind in ("movies", "shows")}
                return self.reply(201 if op == "add" else 200, {"added" if op == "add" else "deleted": count})
            if path == "/anilist":
                query = body.get("query") or ""
                variables = body.get("variables") or {}
                if "SaveMediaListEntry" in query or "DeleteMediaListEntry" in query:
                    adding = "SaveMediaListEntry" in query
                    anilist = variables.get("mediaId") if adding else (variables.get("id") or 0) - ANILIST_ENTRY
                    failing = state.watchlist_write("anilist", "add" if adding else "remove", [anilist])
                    if failing:
                        return self.reply(failing, {"errors": [{"message": "refused", "status": failing}], "data": None})
                    if adding:
                        return self.reply(200, {"data": {"SaveMediaListEntry": {"id": anilist + ANILIST_ENTRY}}})
                    return self.reply(200, {"data": {"DeleteMediaListEntry": {"deleted": True}}})
                if "mediaListEntry" in query:
                    anilist = variables.get("mediaId")
                    with state.lock:
                        planned = anilist in state.watchlists["anilist"]
                    entry = {"id": anilist + ANILIST_ENTRY, "status": "PLANNING"} if planned else None
                    return self.reply(200, {"data": {"Media": {"mediaListEntry": entry}}})
                with state.lock:
                    planned = list(state.watchlists["anilist"])
                status, payload = anilist_post(body, self.base(), state.user, planned)
                return self.reply(status, payload)
            self.reply(404, {"error": "not found"})

        def mal_list_status(self):
            """MyAnimeList's /anime/<id>/my_list_status, PUT (status plan_to_watch puts the entry on the list, any other
            takes it off) or DELETE, answered; False for another path."""
            found = re.fullmatch(r"/mal/v2/anime/(\d+)/my_list_status", urllib.parse.urlsplit(self.path).path)
            if not found:
                return False
            mal = int(found.group(1))
            if self.command == "PUT":
                status = self.body().get("status")
                failing = state.watchlist_write("mal", "add" if status == "plan_to_watch" else "remove", [mal])
                if failing:
                    self.reply(failing, {"error": "refused"})
                else:
                    self.reply(200, {"status": status})
                return True
            with state.lock:
                listed = mal in state.watchlists["mal"]
            failing = state.watchlist_write("mal", "remove", [mal]) if listed else None
            if not listed:
                self.reply(404, {"error": "not_found"})
            elif failing:
                self.reply(failing, {"error": "refused"})
            else:
                self.reply(200, [])
            return True

        def do_PUT(self):
            if not self.mal_list_status():
                self.reply(404, {"error": "not found"})

        def do_DELETE(self):
            path = urllib.parse.urlsplit(self.path).path
            if path.startswith(ACCOUNT + "/"):
                return self.account(path, {})
            if not self.mal_list_status():
                self.reply(404, {"error": "not found"})

    server = http.server.ThreadingHTTPServer(("0.0.0.0", a.port), Handler)
    print(f"fake trakt on :{a.port}, approves after {a.approve_after} polls", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
