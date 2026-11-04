#!/usr/bin/env python3
"""A local Stremio addon for the apps' tests: one movie whose streams point at the media server, and the titles below.

Usage: tools/media/test_addon.py [--port 8766] [--media http://127.0.0.1:8765/matrix] [--all-movies] [--catalog]
                                      [--slow 25] [--tour] [--downloads] [--download-rate 256] [--link-expire 60]
                                      [--link-bytes 8388608] [--download-hold <name>] [--download-hold-lazy <name>]
                                      [--title <type>:<tt id>:<name>]... [--search] [--pages] [--live] [--guide]
                                      [--similar] [--collections] [--relay-media <url>] [--tls <cert.pem> <key.pem>]
Routes (the addon protocol; ids are URL-decoded):
  /manifest.json                     meta for ids prefixed "tortuga-", streams for those and Cinemeta's tt0111161
  /meta/movie/tortuga-remux.json     the movie, its poster served by this addon
  /art/<poster|landscape|square|logo|still>/<id>.png  every title's, episode's and channel's own artwork (each URL its
                                     own: art()), a gradient picked from <id>, so a title looks the same in every run and
                                     no two alike (art_png): posters 300x450, backgrounds and episode thumbnails 1280x720,
                                     channels 512x512, logos the hero's wide bars in the title's colour, stills
                                     1280x720 with the episode's number in large white digits; never the app's icon
  /art/gif/<id>.gif                  the same gradient's colours as a two-frame looping GIF, 480x360 (art_gif)
  /stream/movie/tortuga-remux.json   remux.mkv (4K HEVC HDR10 REMUX), h264-sdr.mkv, a peer-to-peer stream the app
                                     can't play, and three links it must never open (UNSAFE_LINKS: file:, search-ms:, a
                                     share's path), which it counts with the peer-to-peer one as streams it can't play
  /stream/movie/tt0111161.json       the same streams for a Cinemeta movie (Detail → Streams)
  /meta/movie/tortuga-4x3.json       a 4:3 movie (the player's fit modes and zoom), its poster served by this addon
  /stream/movie/tortuga-4x3.json     h264-4x3.mkv (H.264 1440x1080 with an English SRT line throughout)
  /meta/movie/tortuga-portrait.json  a movie taller than wide (the iPhone's player follows the device for it)
  /stream/movie/tortuga-portrait.json h264-portrait.mkv (H.264 720x1280, a phone's upright video)
  /meta/series/tortuga-portrait-series.json  two episodes: the first taller than wide; the second's binge-group stream
  /stream/series/tortuga-portrait-series:1:<n>.json  a missing file (404), then a playable one, so the next episode's
                                     error (with Try next stream) shows in the upright player the first left (iPhone)
  /meta/movie/tortuga-scripts.json   a movie for subtitle fonts, its poster served by this addon
  /stream/movie/tortuga-scripts.json scripts.mkv (one forced SRT cue throughout, in nine scripts: CJK, Arabic, Hebrew,
                                     Thai, Devanagari, Cyrillic, Greek), which a dev session shows with no menu pick
  /meta/movie/tortuga-hdr.json       a movie in three dynamic ranges (the Mac's AVFoundation output: PlayerTests)
  /stream/movie/tortuga-hdr.json     h264-sdr.mkv, hevc-hdr10.mkv and dv-p81.mkv (Dolby Vision 8.1), each with this
                                     addon's English SRT (/subs/tortuga-hdr/eng.srt) as the stream's subtitles
  /meta/series/tortuga-ranges.json   a series of three episodes in one binge group: HDR10 at 23.976 fps, SDR at 24
  /stream/series/tortuga-ranges:1:<n>.json   fps, HDR10 again (av1-hdr10.mkv, error-slate-10s.mp4, av1-hdr10.mkv):
                                     up next from one dynamic range and frame rate to another and back (tvOS
                                     PlayerTests)
  /meta/movie/tortuga-headers.json   a movie whose one stream has proxyHeaders (X-Tortuga-Test and a User-Agent), the
  /stream/movie/tortuga-headers.json headers the player must send (the proxy-header tests): remux.mkv through /headers/
  /meta/movie/tortuga-quoted.json    the same with a `"` in its title and both header values (and backslashes in the
  /stream/movie/tortuga-quoted.json  title and the User-Agent), which a player app's Windows command line must carry
                                     whole (the desktop player-app test)
  /headers/<file>                    --media's <file> through this addon (with the Range asked for), each request's
                                     headers logged and kept for /proxied.json: whether it carried the stream's headers
  /meta/movie/tortuga-hls.json       a movie whose one stream is an HLS playlist at a URL without an extension, served
  /stream/movie/tortuga-hls.json     as application/octet-stream, with proxyHeaders: the test header and a User-Agent
                                     to send, and a response Content-Type that names it HLS (mpv opens it as HLS only
                                     when told: demuxer-lavf-format)
  /hls/master, /hls/media            that playlist (one variant) and its media playlist, whose one segment is
                                     h264-sdr.mkv through /headers/; each request's headers logged as /headers/ logs them
  /meta/movie/tortuga-hls-fail.json  tortuga-hls with a failing segment: each /stream answer mints a token (?fail=<n>,
  /stream/movie/tortuga-hls-fail.json  carried from the playlists to the segment), and the segment's first request
                                     with it answers 502, so mpv's HLS open fails and it falls back to the playlist's
                                     entries; later requests are served (the proxy-header tests)
  /proxied.json                      every request to /headers/ and /hls/ so far, as {"requests": [{file,
                                     X-Tortuga-Test ("missing" when not sent), User-Agent, Range, headers (the names
                                     sent), status (502, for one failed on purpose)}]}: the proxy-header tests read it
  /handoff?url=<url>                 a small page, its query logged ("handoff <query>"): a custom URL-template player
                                     app's hand-off (the iOS proof's template: http://<lan>:<port>/handoff?url={url})
  /catalog/movie/tortuga.json        with --catalog: a "Tortuga Test" catalog listing the movie, so a Board shows a row
                                     from this addon (the sync tests: the synced addon on each device)
  /catalog/<type>/tortuga-search/[skip=<n>&]search=matrix.json
                                     with --search: "Tortuga Search" catalogs (one of each type, a search required,
                                     skip declared), answering "matrix" with six titles, so Search's results run past an
                                     iPhone's screen (the iOS TabsTests), and every page after the first with the same
                                     six, as Cinemeta's search answers a skip, 6 s late (SEARCH_PAGE_HOLD)
  /catalog/movie/tortuga-pages[/skip=<n>][&search=tortugapages].json
                                     with --pages: a "Tortuga Pages" catalog (search and skip optional) of 60 titles
                                     in pages of 20, then an empty page, alone and for a search for "tortugapages" (a
                                     query Cinemeta finds nothing for, so its row is Search's only one), and nothing
                                     for any other search: a Home row and a Search row that load their next pages as
                                     they scroll (the browse tests)
  /meta/movie/tortuga-page-<n>.json  with --pages: each of those titles (1–60), so a library (--dev-library) or Continue
                                     Watching (--dev-progress) can hold more of them than a screen shows (BrowseTests)
  /meta/movie/tortuga-slow-meta.json       a movie whose meta answers --slow seconds late (a screen still loading)
  /meta/movie/tortuga-slow-streams.json    a movie whose meta answers at once
  /stream/movie/tortuga-slow-streams.json  and whose streams (the same as above) answer --slow seconds late
  /meta/movie/tortuga-slow-play.json       a movie whose one stream, h264-sdr.mkv, comes at 4 KiB/s through the
  /stream/movie/tortuga-slow-play.json     media server's ?rate=: the player stays loading (PlayerTests)
  /meta/movie/tortuga-frames.json          a movie of two still frames, each a stream: a bright one (an overcast sky's
  /stream/movie/tortuga-frames.json        whites) and a dark one (near black), the player's controls over each
  /frame/<bright|dark>.png                 those frames, 1920x1080 vertical gradients (PlayerTests' glass shots)
  /meta/series/tortuga-frames-series.json  a series of one episode whose one stream is the dark frame: the title and the
  /stream/series/tortuga-frames-series:1:1.json  episode line over near black (PlayerTests' white controls)
  /meta/series/tortuga-unnumbered.json     a series of three episodes without season or episode numbers, as some
  /stream/series/tortuga-unnumbered:<n>.json  addons' are, with Cast & crew under them: no season chips over them
                                           (tvOS DetailTests), and no streams for any ({"streams": []}: the empty
                                           list's notice, tvOS StreamsTests)
  /meta/movie/tortuga-missing.json         a movie whose one stream, missing.mkv, the media server answers with a
  /stream/movie/tortuga-missing.json       404: the player's error (PlayerTests)
  /meta/movie/tortuga-expired.json         a movie whose one stream is a resolved link with a made-up key in its
  /stream/movie/tortuga-expired.json       path (/expired/<FAKE_API_KEY>/h264-sdr.mkv) that expired between the app's
  /expired/<FAKE_API_KEY>/<file>           resolve and the player's open: the resolver's closed ranges get the media
                                           server's <file>, the player's open-ended requests a 403, so mpv logs "Failed
                                           to open <url>." and the player shows its error (PlayerTests; the desktop
                                           expired-link test: no log may hold the key)
  /meta/series/tortuga-series.json         a series of three episodes, its poster served by this addon
  /stream/series/tortuga-series:1:<n>.json h264-sdr.mkv without a binge group, whose episode's end asks for the next
                                           episode's stream (the Streams sheet over the player; the Apple
                                           StreamsTests), then h264-sdr.mkv in a binge group, whose next episode
                                           plays on without asking
  /meta/series/tortuga-bidi.json           "Tortuga Bidi Test", a series of two episodes whose Latin titles end in
  /stream/series/tortuga-bidi:1:<n>.json   punctuation ("What If...?", "Who Are You?"), with Latin cast names: each
                                           whole and in order inside the app's numbered title, episode line and cast
                                           line in Arabic (the right-to-left Detail and player tests); h264-sdr.mkv
                                           without a binge group, as tortuga-series's first stream
  /meta/series/tortuga-meta-streams.json   a series of two episodes whose meta carries each one's stream (h264-sdr.mkv,
                                           "carried in the meta"), with a share link and a website: the Streams list's
                                           group of the meta's own streams, before this addon's /stream answer
  /stream/series/tortuga-meta-streams:1:<n>.json   h264-sdr.mkv without a binge group, as for tortuga-series, and the
                                           three UNSAFE_LINKS
  /meta/series/tortuga-errvid.json         "Tortuga Error Video Test", a 24-min anime-like series of three episodes whose
                                           streams look like a hosted-link addon's (release file names, a size, one
                                           binge group); episode 1's lazy link (serve.py's /resolve/, a 302 to an
                                           expiring /t/ link) answers with its host's 2-min still error video instead of
                                           the episode, as a host does for a link it refuses ("Wrong IP"): the player
                                           must not take it for the episode (tools/media/make-error-video.sh makes the
                                           slates).
                                           Its videos have IMDb episode ids of a made-up title, tt9900001:1:<n>, as some
                                           anime addons' do, so the trackers know them (a Trakt scrobble names it)
  /stream/series/tt9900001:1:<n>.json      episode 1 error-slate.mp4 (120 s) through /resolve/, its file name
                                           ….S01E01.1080p.WEB.H264.mkv and size 1 GiB; episode 2 h264-sdr.mkv (the 1080p
                                           stream of tortuga-series, its binge group shared with episode 1's, so up next
                                           and auto-play pick it); episode 3 error-slate-10s.mp4 (10 s) through
                                           /resolve/, named and sized as episode 1's, in that binge group too. With
                                           --errvid-unsized the slates' streams have no size, as an addon's that sends
                                           none: the player tells a slate by its length alone, once the engine opened it
  /meta/series/tortuga-cut.json            "Tortuga Cut Link Test", a series of two episodes whose streams share one
  /stream/series/tortuga-cut:1:<n>.json    binge group: episode 1 tracks-next.mkv (30 s) through serve.py's /resolve/
                                           at 256 KiB/s, each CDN link it mints dropping its transfer after 1.2 MB
                                           (about 10 s of the file) and answering 403 from then on, as a link that
                                           expires while the player holds what it read: the end of the file the player
                                           then reaches is the stream failing, not the episode's end (the Apple
                                           PlayerTests); episode 2 the bound h264-sdr.mkv (20 s)
  /meta/series/tortuga-hero-wide.json      the Detail hero's titles (the Apple DetailTests), each with the bright
  /meta/movie/tortuga-hero-tall.json       frame as its backdrop, a metadata line (2026–, 24 min, Animation, Comedy) and
  /meta/movie/tortuga-hero-plain.json      an IMDb rating: a series with a wide, short logo, answered 3 s late, a movie
  /meta/movie/tortuga-hero-long.json       with a tall, narrow one, a movie without one and a movie without one whose name
                                           takes four lines or more in a narrow desktop window, more on a phone
  /hero/<wide|tall>.png                    those logos, amber bars on a transparent canvas: wide.png as fanart.tv's clear
                                           logos (Cinemeta's) are, 800x310 with a fifth of it transparent above and below
                                           the art; tall.png 400x520, 40 px transparent above and below
  /meta/series/tortuga-seasons.json        a series of 24 seasons of one episode each, the wide logo over the bright
                                           frame: a season row that runs far past a wide Detail page's episodes
                                           column (the Apple DetailTests)
  /meta/series/tortuga-spoilers.json       a series of eight episodes whose stills show their numbers in large digits
                                           (the "still" artwork), the last two not out until 2099: the blur of
                                           unwatched episodes' stills, with --dev-watched and --dev-progress marking
                                           some watched or started
  /catalog/<type>/tortuga-similar/similar=<id>.json
                                           with --similar: "Tortuga Similar" catalogs (one of each type, the `similar`
                                           extra required, as some addons declare it), Detail's "More like this"
                                           (the Apple DetailTests): for tortuga-related the bright-backdrop
                                           movie and Cinemeta's The Godfather, which the fake Trakt relates to it too;
                                           for tortuga-series two series of this addon; for tortuga-related-empty and
                                           tortuga-related-none none; for tortuga-related-loading none, held
                                           RELATED_HOLD s; 404 for any other title
  /meta/movie/tortuga-related.json         with --similar: a movie whose IMDb id (imdb_id) is 12 Angry Men's, which the
                                           fake Trakt (test_trackers.py) relates titles to
  /meta/movie/tortuga-related-empty.json   with --similar: a movie without one, whose similar titles are none
  /meta/series/tortuga-related-none.json   with --similar: series with cast and a director whose More like this has no
  /meta/series/tortuga-related-loading.json  card: none found at once; loading while the app waits (tvOS DetailTests)
  /meta/movie/tortuga-formatted.json       a movie whose streams copy an addon's custom formatting: names padded with
  /stream/movie/tortuga-formatted.json     no-break spaces and zero-width joiners for Stremio's name column, symbol-led
                                           description lines (remux.mkv, h264-sdr.mkv; the Streams list's text tidying)
  /meta/movie/tortuga-sorted.json          a movie whose seven streams are releases to sort and filter (2160p remux and
  /stream/movie/tortuga-sorted.json        WEB-DL DV, 1080p Blu-ray and WEB-DL, 720p HDTV, a DVDRip and a WEBRip, each
                                           sized in its text), out of order; every one plays h264-sdr.mkv
  /meta/movie/tortuga-real-streams.json    a movie whose streams are a long list's texts: a film's styled-text and
  /stream/movie/tortuga-real-streams.json  plain-text lists (shared's fixtures/streams, invented titles), the
                                           styled rows first; every one plays h264-sdr.mkv
  /meta/movie/tortuga-real-long.json       the styled-text list with the longest descriptions (six symbol-led lines
  /stream/movie/tortuga-real-long.json     each, small capitals, super- and subscripts); every one plays h264-sdr.mkv
  /meta/series/tortuga-real-anime.json     an anime series of one episode whose streams are an anime episode's
  /stream/series/tortuga-real-anime:1:1.json  styled-text list; every one plays h264-sdr.mkv
  /meta/movie/tortuga-common-streams.json  a movie whose streams are texts most people see: emoji-labelled facts, a
  /stream/movie/tortuga-common-streams.json  line per emoji-led group, plain labelled text, a plain addon's two lines,
                                           then the first four rows of a film's plain-text list; every one plays
                                           h264-sdr.mkv
  /meta/series/tortuga-chapters.json       a series of two 60 s episodes with named chapters (intro, recap and credits
                                           skipping), its poster served by this addon
  /stream/series/tortuga-chapters:1:<n>.json  chapters.mkv in a binge group (Recap 0–4 s, Opening 4–12, Episode
                                           12–44, Ending 44–54, Preview 54–60): episode 1's credits lead to episode
                                           2, whose credits (no next episode) run to the end
  /meta/movie/tortuga-chapters-movie.json  a movie whose end credits are followed by a post-credits scene
  /stream/movie/tortuga-chapters-movie.json  chapters-postcredits.mkv (Opening Credits 0–8 s, Story 8–40, End
                                           Credits 40–52, Post-credits Scene 52–60)
  /meta/movie/tortuga-chapters-numbered.json  a 7-minute title whose chapters are numbered only (intro and credits by
  /stream/movie/tortuga-chapters-numbered.json  the chapter classifier): chapters-numbered.mkv (Chapter 1 0–30 s,
                                           Chapter 2 30–120, Chapter 3 120–330, Chapter 4 330–420)
  /meta/series/tortuga-tracks.json         a series of two 30 s episodes with several audio and subtitle tracks, its
                                           poster served by this addon
  /stream/series/tortuga-tracks:1:<n>.json  in one binge group: episode 1 remux.mkv, episode 2 tracks-next.mkv (the
                                           remux's languages in another order and other codecs): the tracks picked
                                           in one episode carry to the other (PlayerTests, the desktop tracks test); its
                                           episodes' subtitles (below) are English alone, answered 6 s late, after the
                                           file opened, as a slow OpenSubtitles does (TRACKS_SUBTITLE_DELAY)
  /meta/movie/tortuga-subtitles.json       a movie with subtitles in many languages (the player's More subtitles)
  /stream/movie/tortuga-subtitles.json     h264-sdr.mkv with its own English and French SRTs; its subtitles (below)
                                           are five English, Spanish, German, Italian and Japanese ones, and one whose
                                           lang is the text "Português" rather than a code
  /meta/movie/tortuga-matrix.json          a movie for the playback info's formats, its poster served by this addon
  /stream/movie/tortuga-matrix.json        remux.mkv (HEVC HDR10, TrueHD 5.1 first), dv-p81.mkv (Dolby Vision 8.1),
                                           dts-hdma.mkv (DTS-HD MA 7.1)
  /meta/movie/tortuga-stale-link.json      a movie whose one stream's link holds for the latest /stream answer alone,
  /stream/movie/tortuga-stale-link.json    as a hosted link bound to the address that asked for it does (an addon's
                                           /playback/ links): each answer hands out a new link,
                                           /fresh/<n>/h264-sdr.mkv (logged "fresh link <n> handed out"), for the same
                                           release (the same filename and videoSize every time)
  /fresh/<n>/<file>                        --media's <file> through this addon for the latest link (logged "fresh link
                                           <n>: <file>"); an older link answers with the error video, h264-4x3.mkv (a
                                           4:3 picture with a subtitle line throughout), as a host answers a link
                                           asked for from another address with its "Wrong IP" video (logged "stale
                                           link <n> (the latest is <m>): h264-4x3.mkv for <file>")
  /configurable/manifest.json              a second addon on this server, "Tortuga Configurable Test": configurable
                                           (behaviorHints.configurable), its background its own art, streams for
                                           no item; its Configure opens
  /configurable/configure                  a small page (on an Apple TV, which has no browser: as a QR code to open on
                                           a phone; the Apple SettingsTests)
  /configurable/<config>/manifest.json     the same addon and page under a configuration, as a configured addon's
  /configurable/<config>/configure         link carries one (<config> of any length: a link too long for a QR code)
  /signin/v2/info                          a fake of the hosted sync server's sign-in options (Apple and Google: fewer
                                           than api.usetortuga.app's, which lists GitHub too, so the sign-in screen's
                                           room for the hosted server's buttons meets an answer short of it), for the
                                           app's --dev-sign-in-server http://<host>:<port>/signin: held until
                                           /signin/release, and again from /signin/hold on (at most SIGNIN_HOLD_LIMIT
                                           seconds each), so a UI test sees the sign-in screen while its options load
                                           for as long as it looks, then lets them come (the Apple SignInTests)
  /signin/hold, /signin/release            hold the answers from now on; answer the held ones and the next at once
With --all-movies, every Cinemeta movie (any tt id) has those streams, so whichever card a Board shows first plays
(the Android tests). Alone, it declares movie streams only: the series above get theirs without it, or with
--tour or --downloads.
With --tour (the screenshot tours: the player's error and up-next screens), every movie's streams end with two more:
missing.mkv, which the media server answers with a 404, and h264-sdr.mkv again without a binge group, whose episode's
end asks for the next episode's stream (the Streams sheet over the player); and the episodes of Cinemeta's Chernobyl
(tt7366338:1:1 to tt7366338:1:5) have the movie's streams, so an episode's end offers the next one:
  /stream/series/tt7366338:1:<n>.json
With --title <type>:<tt id>:<name> (repeatable; the live trackers test's titles), that Cinemeta movie, or every episode
of that Cinemeta series, has the movie's streams, and a "Tortuga Exit Movie" or "Tortuga Exit Series" catalog lists it,
so a product run (which has no --dev-open) reaches its Detail from a Board row:
  /stream/movie/<id>.json, /stream/series/<id>:<season>:<episode>.json, /catalog/<type>/tortuga-exit.json

The download proofs' items, always served. Their streams use tools/media/serve.py's routes on --media's server, so
that server must be this repo's serve.py. Each item's poster, background, logo and episode thumbnails are URLs of
their own (/art/, each its own image), so a download saves each as its own file:
  /meta/movie/tortuga-dl-slow.json         a movie whose stream is remux.mkv at --download-rate KiB/s (?rate=): a
  /stream/movie/tortuga-dl-slow.json       download slow enough to pause, resume, or kill the app partway (each
                                           download movie's answer also holds the three UNSAFE_LINKS)
  /meta/movie/tortuga-dl-lazy.json         a movie whose stream is a lazy hosted link (/resolve/matrix/remux.mkv,
  /stream/movie/tortuga-dl-lazy.json       throttled the same): a 302 to a CDN link (/t/<token>/…) that answers 403
                                           --link-expire seconds after it was handed out; resolving the stream again
                                           mints a fresh one (a pause past the expiry, then resume)
  /meta/movie/tortuga-dl-cut.json          the same lazy link (throttled the same), its CDN link dropping its transfer
  /stream/movie/tortuga-dl-cut.json        after --link-bytes bytes and answering 403 from then on (an expiry
                                           mid-transfer; the next CDN link serves the rest)
  /meta/movie/tortuga-dl-redirect.json     a movie whose stream reaches h264-sdr.mkv through three 302s with relative
  /stream/movie/tortuga-dl-redirect.json   Locations (/redirect/3/matrix/h264-sdr.mkv)
  /meta/series/tortuga-dl-season.json      a series of eight episodes over two seasons (S01E01–S01E04, S02E01–S02E04)
  /stream/series/tortuga-dl-season:<s>:<e>.json  h264-sdr.mkv (1080p, binge group tortuga-dl-season-1080p), then
                                           remux.mkv (2160p, binge group tortuga-dl-season-2160p), each with the
                                           episode's file name and size; descriptions read "⚡ Ready", except S01E03's
                                           1080p one, which reads "⏳ Not ready" (StreamBadgeParser: not ready), so a
                                           season pick skips it
  /meta/series/tortuga-dl-slow-season.json  a series of two episodes (S01E01, S01E02) whose one stream each is
  /stream/series/tortuga-dl-slow-season:1:<e>.json  remux.mkv at --download-rate KiB/s, as tortuga-dl-slow's: an
                                           episode's download slow enough to pause and resume from its menu
  /subtitles/<type>/<id>[/<extra>].json    for the items above and the tracks series (and their episodes), whatever the
                                           extra (videoHash, videoSize, filename): English, French and Spanish (eng,
                                           fre, spa) SRTs
  /subs/<video id>/<lang>[-<n>].srt        those SRTs: a line every 5 s for a minute, naming the video and language
With --downloads, the manifest declares the subtitles resource for ids prefixed "tortuga-dl-" too (without it, for the
tracks series alone), a "Tortuga Downloads" catalog of each type (the four movies; the series) and series streams (with
--all-movies too, where other series get an empty list), so the app saves those subtitles with a download and a Board
shows the items. The links' settings:
--download-rate (KiB/s, default 256: remux.mkv takes about a minute) for the slow, lazy and cut links, --link-expire
(seconds, default 60) for the lazy link's CDN links, --link-bytes (default 8 MiB, past half of remux.mkv) for the cut
link's, and --download-hold <name> and --download-hold-lazy <name>, which hold the slow link's and the lazy link's
transfers (serve.py's ?hold=<name>, which /resolve/ keeps on the CDN links it mints): they go only as far as the test
lets them (serve.py's /hold/<name>), so the download is partway at whatever step needs it so.

With --live, a live TV addon's shape: the manifest declares the tv type (catalogs, meta, streams), its channels'
streams the media server's live HLS (tools/media/serve.py's /<path>.live.m3u8 of matrix/h264-sdr.m3u8, made by
tools/media/make-live.sh), each a landscape card whose releaseInfo reads "LIVE" or "24/7":
  /catalog/tv/tortuga-live-now[/genre=<Sports|News>].json  "Tortuga Live Now": the match and the news, by genre
  /catalog/tv/tortuga-247.json             "Tortuga 24/7": the channel
  /catalog/movie/tortuga-full-matches.json "Tortuga Full Matches": the final in full
  /catalog/series/tortuga-replays.json     "Tortuga Replays": the match's replays
  /meta/tv/tortuga-live-247.json           an endless channel (the playlist's 12-s sliding window)
  /stream/tv/tortuga-live-247.json         three streams: one that needs proxyHeaders.request (a Referer and a
                                           User-Agent, LIVE_REFERER and LIVE_USER_AGENT: the media server answers 403
                                           without them), one plain, one broken (missing.live.m3u8: 404)
  /meta/tv/tortuga-live-match.json         a live match whose playlist keeps a rewind window (LIVE_MATCH_WINDOW s)
  /stream/tv/tortuga-live-match.json
  /meta/tv/tortuga-live-news.json          a live bulletin that ends: its playlist gets #EXT-X-ENDLIST
  /stream/tv/tortuga-live-news.json        LIVE_NEWS_LENGTH s after its first request, each answer a bulletin of its
                                           own (?n=<answer>)
  /meta/series/tortuga-replays.json        the match's replays: a series of dated episodes (plain VOD, h264-sdr.mkv)
  /stream/series/tortuga-replays:1:<n>.json
  /meta/movie/tortuga-match-full.json      the final in full: a plain VOD movie (h264-sdr.mkv, 20 s)
  /stream/movie/tortuga-match-full.json    one stream, "Tortuga Test\nNo Range": serve.py's /norange/ (a server
                                           without Range support, whose file mpv reads unseekable, as a live one's)

With --guide, a TV guide's addon (the addon SDK's docs/epg.md): the manifest says behaviorHints.epgProvider and declares
the tv type and a catalog with the date extra; its channels play the media server's live HLS, as --live's do:
  /catalog/tv/tortuga-guide.json           "Tortuga Guide": the channels, plain metas (no date: a channel list)
  /catalog/tv/tortuga-guide/date=<YYYY-MM-DD>.json  the channels with that UTC day's programmes (metasDetailed), each
                                           channel's back to back in slots of its own length (GUIDE_CHANNELS), the
                                           last channel's every other slot (gaps), titled from GUIDE_TITLES; one across
                                           midnight is in both days' answers
  /meta/tv/tortuga-guide-<n>.json          a channel with today's programmes (hasScheduledVideos)
  /stream/tv/tortuga-guide-<n>.json        one stream: the live HLS, by the channel's id

With --collections, a Nuvio collection pack made for this addon (Nuvio's format: a JSON array of collections) and the
catalogs its folders show; every cover is this addon's art (art(), each folder's own) at the address the client
reached it at, the way the pack's other URLs are:
  /collections.json                        two collections. "Studios" (TABBED_GRID, the All tab on, a backdrop): six
                                           folders of this addon's studio catalogs, their covers of mixed aspects
                                           whatever the tile: Ember Pictures (poster tile and cover: movies and series),
                                           Tide Studio (landscape: movies and series, its sources naming this addon's
                                           address too, addonBaseUrl), Moss Works (square: movies), Gull Records
                                           (landscape tile, its cover a transparent logo: Tide's movies), Fern Reels
                                           (poster tile, its cover a 4:3 animated GIF: Ember's series) and Dusk House
                                           (poster: series, its title hidden). "Mixed" (ROWS): Mixed Sources
                                           (landscape: Moss Works' movies, a catalog of an addon that isn't installed,
                                           org.example.missing "Missing Addon", and a Letterboxd list), Trakt List
                                           (poster: test_trackers.py's public list 1234, its movies) and TMDB Only
                                           (square: a TMDB discover and a TMDB collection): an import lists the TMDB and
                                           Letterboxd sources as unsupported and leaves TMDB Only out
  /catalog/<type>/tortuga-studio-<studio>[/skip=<n>].json
                                           the studio catalogs (ember and tide of each type, moss movies, dusk series;
                                           skip declared): 60 titles each in pages of 20, then an empty page
  /meta/<type>/tortuga-studio-<studio>-<type>-<n>.json  each of those titles (1-60)

Four more stream addons on this server, always served, for a Streams list with several addons (the streams-bar
tests on the desktop and Android): each streams h264-sdr.mkv and h264-4x3.mkv for every movie and
series id prefixed "tortuga-", the answers numbered and kept an hour (cacheMaxAge), so a list asked again past the app's
cache shows the next number:
  /sources/second/manifest.json            "Tortuga Second", answering at once
  /sources/slow/manifest.json              "Tortuga Slow", answering 6 s late
  /sources/broken/manifest.json            "Tortuga Broken", whose streams answer 500
  /sources/extra/manifest.json             "Tortuga Extra Sources", answering at once (a longer name: with it the row
                                           outgrows the desktop's widest sheet, 640 dp)
  /sources/<key>/stream/<type>/<id>.json   their streams (the movie's and series' ids above)
A catalog addon on this server too, always served, whose catalog a UI test holds while it moves focus, then lets come
(the tvOS BrowseTests: Home's and Discover's first focus once the user has moved focus):
  /late/manifest.json                      "Tortuga Late Catalog": one movie catalog, "Tortuga Late", with one genre
  /late/catalog/movie/tortuga-late.json    the remux movie (its Home row)
  /late/catalog/movie/tortuga-late/genre=Late.json
                                           the same (Discover opened on the genre:
                                           stremio:///discover/<its manifest>/movie/tortuga-late?genre=Late)
                                           both held until /late/release, and again from /late/hold on (at most
                                           LATE_HOLD_LIMIT seconds each)
  /late/hold, /late/release                hold the catalog's answers from now on; answer the held ones and the next
                                           at once
A catalog addon whose catalogs fail, always served (the tvOS BrowseTests: the remote reaching a failed
shelf's Retry and Discover's Retry under its posters):
  /failing/manifest.json                   "Tortuga Failing Catalogs": two movie catalogs, "Tortuga Failing More" then
                                           "Tortuga Failing"
  /failing/catalog/movie/tortuga-failing-more.json
                                           the --pages catalog's first page (20 titles); its next pages answer 500
  /failing/catalog/movie/tortuga-failing.json
                                           500 (a Home row that failed)
An addon whose answers are lists of malformed items only, always served (an answer the app can't read: a Home row's
and a Streams group's error with Retry, never kept, though each answer says to keep it an hour):
  /malformed/manifest.json                 "Tortuga Malformed": one movie catalog, "Tortuga Malformed", and streams for
                                           every movie and series id prefixed "tortuga-"
  /malformed/catalog/movie/tortuga-malformed.json
                                           three metas, none with an id
  /malformed/stream/<type>/<id>.json       two streams, neither with a source (a url or any other link field the
                                           protocol has)
A watch-activity addon, always served, which records the player and library events it receives (the activity
tests on the desktop and Android, and the Apple UI tests of an addon's watch activity):
  /activity/manifest.json                  "Tortuga Watch Activity" (app.tortuga.test.activity): one movie catalog,
                                           "Tortuga Activity", and the player and library resources for movie and
                                           series ids prefixed "tortuga-"
  /activity/catalog/movie/tortuga-activity.json
                                           the remux movie, at once (its Home row)
  /activity/player/<type>/<video id>/<extra>.json, /activity/library/<type>/<id>/<extra>.json
                                           recorded, answered 200 {}
  /activity/received.json                  {"requests": [<each recorded path after /activity, URL-decoded>, …]}, in
                                           the order they came
  /activity/reset                          forgets the recorded ones
activity_checks holds what the activity tests check of the recorded requests.
An addon whose manifest changes, always served (the desktop manifest-refresh test: an installed addon's update
reaches Home without a reinstall):
  /updating/manifest.json                  "Tortuga Updating" (app.tortuga.test.updating): its first request (the
                                           install) one movie catalog, "Tortuga Before", version 1.0.0; every later one
                                           (a refresh) a second, "Tortuga Added", too, version 1.1.0; each request
                                           logged with its number
  /updating/catalog/movie/tortuga-before.json, /updating/catalog/movie/tortuga-added.json
                                           the remux movie (their Home rows)
  /updating/requests.json                  {"requests": <its manifest requests so far>}
  /updating/reset                          the next manifest request counts as the first again
An episodes addon, always served, for the calendar and the new-episode badges (the Android calendar test): two
series catalogs that take the library's ids, as Cinemeta's do (stremio-core asks the catalogs taking calendarVideosIds or
lastVideosIds for the library's series, up to optionsLimit ids a request), with the main addon's "Tortuga Calendar
Test" (/meta/series/tortuga-calendar.json) and its episodes:
  /episodes/manifest.json                  "Tortuga Episodes": the catalogs calendar-videos and last-videos
  /episodes/catalog/series/calendar-videos/calendarVideosIds=<ids>.json
  /episodes/catalog/series/last-videos/lastVideosIds=<ids>.json
                                           metasDetailed: Tortuga Calendar Test with its episodes when <ids> (comma-
                                           separated) has it; episodes 1-3 came out in January 2026, episode 4 when
                                           /episodes/out says (no date before that: not out), episode 5 a day after
                                           that (or after now); the series has a background and a 46 min runtime,
                                           each episode a description and a still, episode 5's a 404 (a still not
                                           made yet, as Cinemeta's are before an episode airs)
  /episodes/out[?in=<seconds>]             episode 4 comes out now, or <seconds> from now, listed ahead of its release
                                           (the badge's episode: out after the series was last watched; the one that
                                           brings a caught-up series back to Continue Watching)
  /meta/series/tortuga-calendar.json       the series' meta, the same episodes, for its Detail and its library item
--relay-media (default: --media) is the media server as this addon reaches it, for the files it serves itself
(/headers/, /fresh/, /expired/), where its clients reach the server at another address (an Android device through
adb reverse, the addon on the device's host).
With --tls, every route is served over HTTPS with that certificate and key (PEM), as the app fetches a stremio:// install
link (the desktop links test, whose run trusts its certificate).
"""
import argparse
import colorsys
import datetime
import functools
import hashlib
import http.server
import itertools
import json
import math
import pathlib
import re
import shutil
import ssl
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import zlib

# The addon's manifest id (a collection pack's addon sources name it).
ADDON_ID = "com.edde746.tortuga.test"
MOVIE_ID = "tortuga-remux"
# A Cinemeta movie the addon has streams for, so a Detail screen from Cinemeta can open them.
CINEMETA_MOVIE_ID = "tt0111161"
# Movies whose meta, or streams, answer late: the app's screens while they load (the Apple BrowseTests; the
# iOS StreamsTests' Resume, busy while the stream is found again).
SLOW_META_ID = "tortuga-slow-meta"
SLOW_STREAMS_ID = "tortuga-slow-streams"
# A movie whose one stream comes at a trickle: the player stays loading, its text up with the controls (PlayerTests).
SLOW_PLAY_ID = "tortuga-slow-play"
SLOW_PLAY_RATE = 4
# Two still frames as streams (mpv shows an image as a one-frame video and stays on it): the player's controls over
# bright and over dark video. Each a vertical gradient from its top colour to its bottom one.
FRAMES_ID = "tortuga-frames"
FRAMES = {"bright": ((250, 250, 248), (214, 222, 232)), "dark": ((8, 10, 14), (34, 36, 44))}
# A series of one episode on the dark frame: the player's title with its episode line over near black.
FRAMES_SERIES_ID = "tortuga-frames-series"
# A series whose episodes carry no season or episode numbers, with cast and a director, and whose streams answer none.
UNNUMBERED_ID = "tortuga-unnumbered"
UNNUMBERED_EPISODES = 3
# A movie whose one stream the media server answers with a 404: the player's error (PlayerTests).
MISSING_ID = "tortuga-missing"
# A movie whose one stream's link answers the app's resolver and then the player 403, a hosted link that expired in
# between; the link carries an API key in its path, as an addon's resolve links can: a made-up one, shaped as no key
# pattern knows it (only the Redactor's URL rule hides it), for the checks that grep the app's logs for it.
EXPIRED_ID = "tortuga-expired"
FAKE_API_KEY = "TortugaFakeApiKey0042"
# A movie whose streams are formatted the way an addon's custom formatters write them.
FORMATTED_ID = "tortuga-formatted"
# A movie whose streams are releases out of order, for the Streams list's sort and filters.
SORTED_ID = "tortuga-sorted"
# A movie whose streams are a long list's texts (shared's fixtures/streams), for stream suggestions over a long list.
REAL_STREAMS_ID = "tortuga-real-streams"
# The styled-text lists with the longest descriptions (a movie) and an anime episode's, and the texts most people
# see: the Streams list's row text.
REAL_LONG_ID = "tortuga-real-long"
REAL_ANIME_ID = "tortuga-real-anime"
COMMON_STREAMS_ID = "tortuga-common-streams"
# A movie whose stream carries proxyHeaders (behaviorHints.proxyHeaders.request), served through /headers/, which logs
# what each request sent: Tortuga's own player must send them (the proxy-header tests).
HEADERS_ID = "tortuga-headers"
TEST_HEADER = ("X-Tortuga-Test", "proxy-headers")
HEADERS_USER_AGENT = "TortugaProxyHeaders/1.0"
# The same movie with a `"` in its title and in both header values, the title also with backslashes before its quotes
# and at its end: a player app's command line on Windows must carry each whole (the desktop player-app test's headers
# phase checks mpv's window title and the headers the player sent). The User-Agent is a valid one, a product and a
# comment holding a `\"` and a `"` (RFC 9110 §10.1.5), since VLC 3 sends none that isn't (vlc_http_is_agent in its
# modules/access/http/message.c): the one value with a `"` that VLC sends.
QUOTED_ID = "tortuga-quoted"
QUOTED_TITLE = 'Tortuga \\"Quoted\\" Test\\'
QUOTED_HEADERS = {TEST_HEADER[0]: 'proxy"headers', "User-Agent": 'TortugaProxyHeaders/1.0 (proxy\\"headers")'}
# A movie whose stream is HLS at an extensionless URL, its type given in proxyHeaders.response (StreamHeaders.kt).
HLS_ID = "tortuga-hls"
# The same stream whose one segment answers 502 to its first request (a fresh token per /stream answer, so each play
# fails once): mpv's HLS open fails and it opens the playlist's entries as files of their own (the proxy-header tests).
HLS_FAIL_ID = "tortuga-hls-fail"
# A movie whose stream's link holds for the latest /stream answer alone (/fresh/<n>/), as a hosted link bound to the
# address that asked for it does: an older link plays the error video, another file (the desktop stale-link test).
STALE_LINK_ID = "tortuga-stale-link"
STALE_LINK_FILE = "h264-sdr.mkv"
STALE_LINK_ERROR = "h264-4x3.mkv"
HANDOFF_PAGE = b"<!doctype html><title>Tortuga hand-off</title><p>The test addon got the hand-off.</p>\n"

# --live (the module's docstring): the live catalogs, their channels, the replays and the final in full.
LIVE_NOW_CATALOG_ID = "tortuga-live-now"
LIVE_247_CATALOG_ID = "tortuga-247"
REPLAYS_ID = "tortuga-replays"
FULL_MATCHES_CATALOG_ID = "tortuga-full-matches"
MATCH_FULL_ID = "tortuga-match-full"
LIVE_GENRES = ("Sports", "News")
LIVE_CHANNEL_ID = "tortuga-live-247"
LIVE_MATCH_ID = "tortuga-live-match"
LIVE_NEWS_ID = "tortuga-live-news"
# id: name, releaseInfo, genre, description.
LIVE_ITEMS = {
    LIVE_CHANNEL_ID: ("Tortuga 24/7", "24/7", None, "An endless test channel: the same 20 seconds, looping live."),
    LIVE_MATCH_ID: ("Tortuga Cup Final", "LIVE", "Sports", "A live test match whose stream keeps 90 seconds to rewind."),
    LIVE_NEWS_ID: ("Tortuga Evening News", "LIVE", "News", "A live test bulletin that ends 90 seconds after it starts."),
}
LIVE_REFERER = "http://tortuga-live.test/"
LIVE_USER_AGENT = "TortugaLive/1.0"
LIVE_MATCH_WINDOW = 90
LIVE_NEWS_LENGTH = 90
REPLAY_DATES = ("2026-10-04", "2026-10-11", "2026-10-18")
# --guide (the module's docstring): its catalog, its channels (id, name, a programme's minutes), the programmes'
# titles, each channel's in turn, and where the slots are counted from: a day, each channel GUIDE_STAGGER minutes later
# than the one before.
GUIDE_CATALOG_ID = "tortuga-guide"
GUIDE_CHANNELS = (("tortuga-guide-1", "Tortuga One", 30), ("tortuga-guide-2", "Tortuga Two", 60),
                  ("tortuga-guide-3", "Tortuga Three", 90), ("tortuga-guide-4", "Tortuga Four", 120))
GUIDE_TITLES = ("Morning Briefing", "The Turtle Report", "Matrix Live", "Codec Corner", "Test Pattern Hour",
                "Frames per Second", "Night Desk", "The Long Take")
GUIDE_ANCHOR = datetime.datetime(2026, 1, 1, tzinfo=datetime.timezone.utc)
GUIDE_STAGGER = 20

# The episodes addon (calendar and new-episode badges): its path, its catalogs' ids and extras, and its series.
EPISODES_PATH = "/episodes"
EPISODES_CATALOGS = {"calendar-videos": "calendarVideosIds", "last-videos": "lastVideosIds"}
CALENDAR_SERIES_ID = "tortuga-calendar"
CALENDAR_EPISODES_OUT = 3

# The second addon on this server, configurable: its manifest and configuration page under this path.
CONFIGURABLE_PATH = "/configurable"
CONFIGURE_PAGE = b"<!doctype html><title>Tortuga Configurable Test</title><p>The configurable test addon's settings.</p>\n"
# The stream addons under /sources/<key>/: key → (name, seconds its streams answer late, whether they fail).
SOURCES = {
    "second": ("Tortuga Second", 0, False),
    "slow": ("Tortuga Slow", 6, False),
    "broken": ("Tortuga Broken", 0, True),
    "extra": ("Tortuga Extra Sources", 0, False),
}
# The catalog addon under LATE_PATH: its catalog's id, its one genre, and how long a held answer waits at most for
# /late/release.
LATE_PATH = "/late"
LATE_CATALOG_ID = "tortuga-late"
LATE_GENRE = "Late"
LATE_HOLD_LIMIT = 120
# The catalog addon under FAILING_PATH: a catalog whose next pages fail, and one that always fails.
FAILING_PATH = "/failing"
FAILING_MORE_CATALOG_ID = "tortuga-failing-more"
FAILING_CATALOG_ID = "tortuga-failing"
# The addon under MALFORMED_PATH: its catalog's id, and its answer to every stream request; each item is malformed.
MALFORMED_PATH = "/malformed"
MALFORMED_CATALOG_ID = "tortuga-malformed"
MALFORMED_STREAMS = {
    "streams": [{"name": "Tortuga Malformed\n1080p", "description": "No source"}, {"name": "Tortuga Malformed\n4:3", "url": 43}],
    "cacheMaxAge": 3600,
}
# The watch-activity addon under ACTIVITY_PATH: its catalog's id.
ACTIVITY_PATH = "/activity"
ACTIVITY_CATALOG_ID = "tortuga-activity"
# The addon under UPDATING_PATH, whose manifest changes after its first request: its catalogs' ids.
UPDATING_PATH = "/updating"
UPDATING_BEFORE_ID = "tortuga-before"
UPDATING_ADDED_ID = "tortuga-added"
# activity_checks: the remux movie's length in ms (remux.mkv's, tools/media/matrix.json) and how far a player event's
# duration may be off it; the latest currentTime of a start from the beginning; the earliest of the pause (the
# tests pause past 5 s); how far the stop's may be off the pause's (left paused).
ACTIVITY_DURATION_MS = 30058
ACTIVITY_DURATION_SLACK_MS = 1000
ACTIVITY_START_MAX_MS = 3000
ACTIVITY_PAUSE_MIN_MS = 3000
ACTIVITY_STOP_SLACK_MS = 1000
# The --catalog catalog's id, and the --title catalogs' (one of each type).
CATALOG_ID = "tortuga"
EXIT_CATALOG_ID = "tortuga-exit"
# The --search catalogs' id (one of each type), and how late they answer a page after the first.
SEARCH_CATALOG_ID = "tortuga-search"
SEARCH_PAGE_HOLD = 6
# The --pages catalog's id, its page size and its pages (then an empty one), and the query it answers.
PAGES_CATALOG_ID = "tortuga-pages"
PAGE_SIZE = 20
PAGES = 3
PAGES_QUERY = "tortugapages"
# --collections: the pack's path; the studio catalogs (key: name, the types it has a catalog of) and their titles in
# each; the Mixed collection's addon that isn't installed, and the fake Trakt's public list (test_trackers.py).
COLLECTIONS_PATH = "/collections.json"
STUDIOS = {"ember": ("Ember Pictures", ("movie", "series")), "tide": ("Tide Studio", ("movie", "series")),
           "moss": ("Moss Works", ("movie",)), "dusk": ("Dusk House", ("series",))}
STUDIO_TITLES = 60
MISSING_ADDON = ("org.example.missing", "Missing Addon")
TRAKT_LIST_ID = 1234
# The artwork's sizes by shape (art_png), as real addons' come: Cinemeta's posters, a TMDB still or backdrop, a TV
# addon's channel logo.
ART_SIZES = {"poster": (300, 450), "landscape": (1280, 720), "square": (512, 512), "still": (1280, 720)}
# The --tour series: Cinemeta's Chernobyl, whose first season has five episodes.
TOUR_SERIES_ID = "tt7366338"
TOUR_EPISODES = 5
# The tests' own series: an episode played from its stream without a binge group asks for the next one's stream.
SERIES_ID = "tortuga-series"
SERIES_EPISODES = 3
# A series whose Latin episode titles end in punctuation, its cast Latin names: in a right-to-left language each sits
# in an app string (the numbered title, the episode line, the cast line) and keeps its own order there.
BIDI_SERIES_ID = "tortuga-bidi"
BIDI_EPISODES = ("What If...?", "Who Are You?")
BIDI_CAST = ["Robert Downey Jr.", "Chris Evans", "Scarlett Johansson"]
# A series whose meta carries its episodes' streams (stremio-core `meta_streams`).
META_STREAMS_ID = "tortuga-meta-streams"
META_STREAMS_EPISODES = 2
# An addon's anime series whose episode 1 its host answers with a still error video (a 2-min slate where a 24-min
# episode should be), episode 2 a real file, episode 3 a 10-s slate; their streams share one binge group.
ERRVID_SERIES_ID = "tortuga-errvid"
# Its videos' title: a made-up IMDb id, which only the fake trackers (test_trackers.py) are asked about.
ERRVID_IMDB_ID = "tt9900001"
ERRVID_EPISODES = 3
# What the addon says each episode's file is: 1 GiB, though the slate the host sends is half a megabyte.
ERRVID_SIZE = 1 << 30
# The slates' CDN links last the run, as a host's do (serve.py's default is 60 s).
ERRVID_LINK_EXPIRE = 3600

# A series whose episode 1's CDN links die partway, each after CUT_LINK_BYTES of its file, the next episode's stream
# in its binge group.
CUT_SERIES_ID = "tortuga-cut"
CUT_EPISODES = 2
CUT_LINK_BYTES = 1_200_000
CUT_LINK_RATE = 256

# The Detail hero's titles (the Apple DetailTests): each logo's canvas and its art's box in it (left, top,
# right, bottom); the wide one is answered HERO_WIDE_DELAY seconds late (the title's name shows until it comes).
HERO_WIDE_ID = "tortuga-hero-wide"
HERO_TALL_ID = "tortuga-hero-tall"
HERO_PLAIN_ID = "tortuga-hero-plain"
HERO_LONG_ID = "tortuga-hero-long"
HERO_LONG_NAME = "Tortuga Hero Long Test: A Name Long Enough to Wrap onto Four Lines in a Narrow Window, and onto More at Large Text Sizes"
HERO_LOGOS = {"wide": ((800, 310), (9, 66, 793, 244)), "tall": ((400, 520), (60, 40, 340, 480))}
HERO_LOGO_COLOUR = (255, 176, 0)
HERO_WIDE_DELAY = 3

# Detail's "More like this" (--similar): a movie whose meta names an IMDb id, which test_trackers.py's Trakt relates
# titles to, and a movie whose similar catalog answers none.
SIMILAR_CATALOG_ID = "tortuga-similar"
RELATED_ID = "tortuga-related"
RELATED_IMDB_ID = "tt0050083"
RELATED_EMPTY_ID = "tortuga-related-empty"
# Series with Cast & crew whose More like this has no card: similar titles none at once (the row says none were
# found), and none held RELATED_HOLD seconds, past the app's 30 s for an addon's answer (the row loads until the app
# stops waiting, then goes).
RELATED_NONE_ID = "tortuga-related-none"
RELATED_LOADING_ID = "tortuga-related-loading"
RELATED_HOLD = 60

# A series of SEASONS_COUNT seasons, the wide hero logo over the bright frame: its season chips run far past a wide
# Detail page's episodes column, which scrolls them (the Apple DetailTests).
SEASONS_ID = "tortuga-seasons"
SEASONS_COUNT = 24
# A series whose episode stills show their numbers (art_png's "still"), the last SPOILERS_UPCOMING not out yet: the blur
# of unwatched episodes' stills.
SPOILERS_ID = "tortuga-spoilers"
SPOILERS_EPISODES = 8
SPOILERS_UPCOMING = 2

# Sign-in options for the fake of the hosted sync server (GET /v2/info, protocol 2: shared SyncProtocol.VERSION). The
# app runs Tortuga's own OAuth clients only with the real hosted server, and anywhere else only the server's own, so
# the fake lists client IDs of its own: Apple's web flow (phones, tablets, computers) and GitHub (TVs too, by its
# device flow); no Google, which no other server can offer. Either way fewer buttons than the hosted server's, so the
# room the screen keeps for those must hold a shorter answer. And how long a held answer waits at most for
# /signin/release.
SIGNIN_INFO = {
    "protocolVersion": 2,
    "providers": [
        {"id": "apple", "issuer": "https://appleid.apple.com", "clientIds": ["app.tortuga.test.web"]},
        {"id": "github", "issuer": "https://github.com", "clientIds": ["tortuga-test-github"]},
    ],
    "appleTokenRevocation": True,
    "appleWebReturn": True,
    "accountQuotaBytes": 67108864,
}
SIGNIN_HOLD_LIMIT = 120

# A 4:3 movie (h264-4x3.mkv): the player's fit modes and zoom differ visibly on any wide screen.
FOUR_THREE_ID = "tortuga-4x3"

# A movie taller than wide (h264-portrait.mkv): the iPhone's player releases its landscape lock for it.
PORTRAIT_ID = "tortuga-portrait"

# A series whose first episode is taller than wide (h264-portrait.mkv) and whose second's file is missing: the iPhone's
# player stays upright for the first, and the second's error shows there.
PORTRAIT_SERIES_ID = "tortuga-portrait-series"
PORTRAIT_SERIES_GROUP = "tortuga-test-portrait"

# A movie whose subtitle line is in every script (scripts.mkv): libass draws each, or boxes where it finds no font.
SCRIPTS_ID = "tortuga-scripts"

# A movie in SDR, HDR10 and Dolby Vision (h264-sdr.mkv, hevc-hdr10.mkv, dv-p81.mkv), each stream with an English SRT of
# this addon's: the Mac's AVFoundation output (the Apple PlayerTests).
HDR_ID = "tortuga-hdr"
HDR_NAME = "Tortuga HDR Test"

# Up next from one dynamic range and frame rate to another and back (the tvOS PlayerTests, Match Content and Match
# frame rate): a series of three episodes in one binge group, episode 1 on av1-hdr10.mkv (HDR10,
# 23.976 fps), episode 2 on error-slate-10s.mp4 (SDR, 24 fps: the one test file at another rate), episode 3 on
# av1-hdr10.mkv again. There and back, a plan shown with the wrong file's format (one file's plan with the other's
# format) asks the display for an extra mode in one of the two, whichever of the next file's plan and its format
# reaches the screen first. The HDR10 episodes are the 1080p AV1 file: the simulator decodes in software, and there
# the 2160p HEVC file plays too slowly to reach its end.
RANGES_SERIES_ID = "tortuga-ranges"
RANGES_FILES = (("av1-hdr10.mkv", "1080p HDR10"), ("error-slate-10s.mp4", "1080p SDR 24 fps"),
                ("av1-hdr10.mkv", "1080p HDR10"))

# The playback info's formats (PlaybackInfo): one stream per matrix file the info describes differently.
MATRIX_ID = "tortuga-matrix"

# Intro, recap and credits skipping: a series of two episodes on chapters.mkv (Recap, Opening, Episode, Ending,
# Preview), so episode 1's credits lead to the next episode and episode 2's run to the end; a movie on
# chapters-postcredits.mkv, whose end credits are followed by a post-credits scene; and a movie on
# chapters-numbered.mkv, whose chapters say nothing but their numbers.
CHAPTERS_SERIES_ID = "tortuga-chapters"
CHAPTERS_EPISODES = 2
CHAPTERS_MOVIE_ID = "tortuga-chapters-movie"
CHAPTERS_NUMBERED_ID = "tortuga-chapters-numbered"

# Tracks carried across episodes: a series of two episodes in one binge group, episode 1 on remux.mkv and episode 2 on
# tracks-next.mkv, which has the remux's languages in another order.
TRACKS_SERIES_ID = "tortuga-tracks"
TRACKS_FILES = ("remux.mkv", "tracks-next.mkv")
# Its episodes' subtitles from this addon: English alone, this many seconds late, so they arrive after the file opened.
TRACKS_SUBTITLE_DELAY = 6

# A movie with subtitles in many languages: a profile in English gets the best English ones, the rest wait in the
# player's More subtitles.
SUBS_ID = "tortuga-subtitles"
SUBS_NAME = "Tortuga Subtitles Test"
SUBS_ENGLISH = 5

# Streams whose externalUrl the app must never hand to the system: a file on this machine,
# Windows Search over a share, a share's own path. The app lists none of them and counts them as streams it can't
# play; their targets are on 127.0.0.1 and harmless should one open anyway.
UNSAFE_LINKS = [
    {"name": "Tortuga Test\nFile link", "description": "externalUrl file: (never opened)",
     "externalUrl": "file:///C:/Windows/win.ini"},
    {"name": "Tortuga Test\nSearch link", "description": "externalUrl search-ms: (never opened)",
     "externalUrl": "search-ms:query=tortuga&crumb=location:\\\\127.0.0.1\\tortuga-link-test"},
    {"name": "Tortuga Test\nShare path", "description": "externalUrl a share's path (never opened)",
     "externalUrl": "\\\\127.0.0.1\\tortuga-link-test\\setup.exe"},
]

# The download proofs' items: ids under DOWNLOADS_PREFIX get this addon's subtitles (--downloads declares them).
DOWNLOADS_PREFIX = "tortuga-dl-"
DOWNLOAD_MOVIES = {
    "tortuga-dl-slow": "Tortuga Slow Download Test",
    "tortuga-dl-lazy": "Tortuga Lazy Link Test",
    "tortuga-dl-cut": "Tortuga Expiring Link Test",
    "tortuga-dl-redirect": "Tortuga Redirect Test",
}
# The --downloads catalogs' id (one of each type).
DOWNLOADS_CATALOG_ID = "tortuga-downloads"
# A series for season downloads and Keep synced: each season's episode titles, in order.
SEASON_SERIES_ID = "tortuga-dl-season"
SEASON_SERIES_NAME = "Tortuga Season Test"
SEASON_EPISODES = {
    1: ["Low Tide", "Driftwood", "Undertow: Part 1", "Riptide"],
    2: ["Landfall", "Shoals", "Nesting", "Hatchlings"],
}
# The episode whose 1080p stream its addon marks not ready.
UNREADY_EPISODE = (1, 3)
# A series whose episodes download as slowly as tortuga-dl-slow: an episode's download to pause and resume from Detail.
SLOW_SERIES_ID = "tortuga-dl-slow-season"
SLOW_SERIES_NAME = "Tortuga Slow Season Test"
SLOW_EPISODES = ["Ebb", "Flow"]
# The matrix files' sizes, as tools/media/matrix.json records them.
SIZES = {"remux.mkv": 15296153, "h264-sdr.mkv": 1155664}
SUBTITLE_LANGUAGES = {"eng": "English", "fre": "Français", "spa": "Español", "deu": "Deutsch", "ita": "Italiano",
                      "jpn": "日本語", "por": "Português"}


def manifest(all_movies=False, catalog=False, tour=False, downloads=False, titles=(), search=False, pages=False,
             live=False, guide=False, similar=False, collections=False):
    stream_prefixes = ["tortuga-", "tt"] if all_movies else ["tortuga-", CINEMETA_MOVIE_ID]
    if tour and not all_movies:
        stream_prefixes.append(TOUR_SERIES_ID)
    if not all_movies:
        stream_prefixes += [t["id"] for t in titles] + [ERRVID_IMDB_ID]
    # With --all-movies alone "tt" would take in every Cinemeta series too, which this addon has no streams for
    # (--downloads answers those with an empty list).
    stream_types = ["movie"] if all_movies and not tour and not downloads and not titles and not live else ["movie", "series"]
    types = ["movie", "series"] + (["tv"] if live or guide else [])
    resources = [
        {"name": "meta", "types": types, "idPrefixes": ["tortuga-"]},
        {"name": "stream", "types": stream_types + (["tv"] if live or guide else []), "idPrefixes": stream_prefixes},
        {"name": "subtitles", "types": ["movie", "series"],
         "idPrefixes": [TRACKS_SERIES_ID, SUBS_ID] + ([DOWNLOADS_PREFIX] if downloads else [])},
    ]
    catalogs = [{"type": "movie", "id": CATALOG_ID, "name": "Tortuga Test"}] if catalog else []
    if downloads:
        catalogs += [{"type": kind, "id": DOWNLOADS_CATALOG_ID, "name": "Tortuga Downloads"} for kind in ("movie", "series")]
    catalogs += [{"type": kind, "id": EXIT_CATALOG_ID, "name": f"Tortuga Exit {kind.capitalize()}"}
                 for kind in ("movie", "series") if any(t["type"] == kind for t in titles)]
    if search:
        catalogs += [{"type": kind, "id": SEARCH_CATALOG_ID, "name": "Tortuga Search", "extra": [{"name": "search", "isRequired": True}, {"name": "skip"}]}
                     for kind in ("movie", "series")]
    if pages:
        catalogs.append({"type": "movie", "id": PAGES_CATALOG_ID, "name": "Tortuga Pages", "extra": [{"name": "search"}, {"name": "skip"}]})
    if live:
        catalogs += [
            {"type": "tv", "id": LIVE_NOW_CATALOG_ID, "name": "Tortuga Live Now",
             "extra": [{"name": "genre", "options": list(LIVE_GENRES)}], "genres": list(LIVE_GENRES)},
            {"type": "tv", "id": LIVE_247_CATALOG_ID, "name": "Tortuga 24/7"},
            {"type": "movie", "id": FULL_MATCHES_CATALOG_ID, "name": "Tortuga Full Matches"},
            {"type": "series", "id": REPLAYS_ID, "name": "Tortuga Replays"},
        ]
    if guide:
        catalogs.append({"type": "tv", "id": GUIDE_CATALOG_ID, "name": "Tortuga Guide", "extra": [{"name": "date"}]})
    if similar:
        catalogs += [{"type": kind, "id": SIMILAR_CATALOG_ID, "name": "Tortuga Similar", "extra": [{"name": "similar", "isRequired": True}]}
                     for kind in ("movie", "series")]
    if collections:
        catalogs += [{"type": kind, "id": f"tortuga-studio-{key}", "name": studio_catalog_name(key, kind), "extra": [{"name": "skip"}]}
                     for key, (_, kinds) in STUDIOS.items() for kind in kinds]
    return {
        "id": ADDON_ID,
        "version": "1.0.0",
        "name": "Tortuga Test",
        "description": "Harness addon: the matrix media on the local media server.",
        "resources": (["catalog"] if catalogs else []) + resources,
        "types": types,
        "idPrefixes": ["tortuga-"],
        "catalogs": catalogs,
        **({"behaviorHints": {"epgProvider": True}} if guide else {}),
    }


def configurable_manifest(base):
    """The second addon's manifest: configurable, with a background, and streams for no item (its id prefix is no
    item's)."""
    return {
        "id": "com.edde746.tortuga.test.configurable",
        "version": "1.0.0",
        "name": "Tortuga Configurable Test",
        "description": "Harness addon with settings: its configuration page and its background.",
        "resources": [{"name": "stream", "types": ["movie"], "idPrefixes": ["tortuga-configurable-"]}],
        "types": ["movie"],
        "catalogs": [],
        "background": art(base, "tortuga-configurable", "landscape"),
        "behaviorHints": {"configurable": True},
    }


def source_manifest(key):
    """A /sources/ addon's manifest: streams for every movie and series id prefixed "tortuga-"."""
    return {
        "id": f"com.edde746.tortuga.test.source.{key}",
        "version": "1.0.0",
        "name": SOURCES[key][0],
        "description": "Harness stream addon: a Streams list with several addons.",
        "resources": [{"name": "stream", "types": ["movie", "series"], "idPrefixes": ["tortuga-"]}],
        "types": ["movie", "series"],
        "catalogs": [],
    }


def late_manifest():
    """The late catalog addon's manifest: one movie catalog with one genre, LATE_GENRE, held until /late/release."""
    return {
        "id": "com.edde746.tortuga.test.late",
        "version": "1.0.0",
        "name": "Tortuga Late Catalog",
        "description": "Harness catalog addon: a catalog whose answers wait until the test lets them come.",
        "resources": ["catalog"],
        "types": ["movie"],
        "catalogs": [{"type": "movie", "id": LATE_CATALOG_ID, "name": "Tortuga Late",
                      "extra": [{"name": "genre", "options": [LATE_GENRE]}]}],
    }


def failing_manifest():
    """The failing catalog addon's manifest: a movie catalog whose first page answers and the next ones fail, then one
    that always fails."""
    return {
        "id": "com.edde746.tortuga.test.failing",
        "version": "1.0.0",
        "name": "Tortuga Failing Catalogs",
        "description": "Harness catalog addon: catalogs that answer 500.",
        "resources": ["catalog"],
        "types": ["movie"],
        "catalogs": [
            {"type": "movie", "id": FAILING_MORE_CATALOG_ID, "name": "Tortuga Failing More", "extra": [{"name": "skip"}]},
            {"type": "movie", "id": FAILING_CATALOG_ID, "name": "Tortuga Failing"},
        ],
    }


def malformed_manifest():
    """The malformed addon's manifest: a movie catalog, and streams for every movie and series id prefixed "tortuga-",
    each answering a list whose every item is malformed."""
    return {
        "id": "com.edde746.tortuga.test.malformed",
        "version": "1.0.0",
        "name": "Tortuga Malformed",
        "description": "Harness addon: answers whose every item is malformed.",
        "resources": ["catalog", {"name": "stream", "types": ["movie", "series"], "idPrefixes": ["tortuga-"]}],
        "types": ["movie", "series"],
        "catalogs": [{"type": "movie", "id": MALFORMED_CATALOG_ID, "name": "Tortuga Malformed"}],
    }


def activity_manifest():
    """The watch-activity addon's manifest: one movie catalog, and the player and library resources (the user's watch
    activity) for movie and series ids prefixed "tortuga-"."""
    return {
        "id": "app.tortuga.test.activity",
        "version": "1.0.0",
        "name": "Tortuga Watch Activity",
        "description": "Harness addon: records the player and library events it receives.",
        "resources": [
            "catalog",
            {"name": "player", "types": ["movie", "series"], "idPrefixes": ["tortuga-"]},
            {"name": "library", "types": ["movie", "series"], "idPrefixes": ["tortuga-"]},
        ],
        "types": ["movie", "series"],
        "idPrefixes": ["tortuga-"],
        "catalogs": [{"type": "movie", "id": ACTIVITY_CATALOG_ID, "name": "Tortuga Activity"}],
    }


def updating_manifest(updated):
    """The updating addon's manifest: one movie catalog, Tortuga Before; [updated], a second one too, Tortuga Added,
    and a new version."""
    catalogs = [{"type": "movie", "id": UPDATING_BEFORE_ID, "name": "Tortuga Before"}]
    if updated:
        catalogs.append({"type": "movie", "id": UPDATING_ADDED_ID, "name": "Tortuga Added"})
    return {
        "id": "app.tortuga.test.updating",
        "version": "1.1.0" if updated else "1.0.0",
        "name": "Tortuga Updating",
        "description": "Harness addon: its manifest gains a catalog after the first request.",
        "resources": ["catalog"],
        "types": ["movie"],
        "catalogs": catalogs,
    }


def activity_checks(requests):
    """The checks of what the watch-activity addon received ([requests]: /activity/received.json's) from the activity
    tests on the desktop and Android, which play the remux movie from its start,
    pause it past a few seconds, leave the player, then add the movie to the library and mark it watched: one `start`
    from the beginning, one `pause` later on, one `stop` where it paused, each with the remux's duration (in ms, as
    Stremio sends them; the start's 0 when the player didn't know the length yet, as stremio-core sends it then), one
    `libraryAdd` and one `watched` of the whole movie, in that order and nothing else. Each check a (name, ok,
    detail)."""
    def event(path):
        """The request's action and, for a player event, its currentTime and duration; no action for one that isn't
        an event of the movie."""
        m = re.fullmatch(rf"/player/movie/{MOVIE_ID}/action=(\w+)&currentTime=(\d+)&duration=(\d+)\.json", path)
        if m:
            return m.group(1), int(m.group(2)), int(m.group(3))
        m = re.fullmatch(rf"/library/movie/{MOVIE_ID}/action=(\w+)\.json", path)
        return (m.group(1) if m else None), None, None

    events = [event(p) for p in requests]

    def only(action):
        """The one event of [action] (None for none, or more than one) and the requests that were."""
        found = [(p, e) for p, e in zip(requests, events) if e[0] == action]
        return (found[0][1] if len(found) == 1 else None), [p for p, _ in found]

    def length(e):
        return abs(e[2] - ACTIVITY_DURATION_MS) <= ACTIVITY_DURATION_SLACK_MS

    (start, starts), (pause, pauses), (stop, stops) = only("start"), only("pause"), only("stop")
    (added, adds), (watched, marks) = only("libraryAdd"), only("watched")
    duration = f"duration {ACTIVITY_DURATION_MS} ± {ACTIVITY_DURATION_SLACK_MS} ms"
    order = ["start", "pause", "stop", "libraryAdd", "watched"]
    return [
        ("activity: start", start is not None and start[1] <= ACTIVITY_START_MAX_MS and (length(start) or start[2] == 0),
         f"{starts} (want one: currentTime ≤ {ACTIVITY_START_MAX_MS} ms, {duration} or 0 before the length is known)"),
        ("activity: pause", pause is not None and ACTIVITY_PAUSE_MIN_MS <= pause[1] < pause[2] and length(pause),
         f"{pauses} (want one: currentTime from {ACTIVITY_PAUSE_MIN_MS} ms to the duration, {duration})"),
        ("activity: stop", stop is not None and pause is not None and abs(stop[1] - pause[1]) <= ACTIVITY_STOP_SLACK_MS
         and length(stop), f"{stops} (want one: currentTime within {ACTIVITY_STOP_SLACK_MS} ms of the pause's, {duration})"),
        ("activity: libraryAdd", added is not None, f"{adds} (want /library/movie/{MOVIE_ID}/action=libraryAdd.json once)"),
        ("activity: watched", watched is not None, f"{marks} (want /library/movie/{MOVIE_ID}/action=watched.json once)"),
        ("activity: order", [e[0] for e in events] == order,
         f"{len(requests)} requests: {requests} (want {', '.join(order)}, nothing else)"),
    ]


def episodes_manifest():
    """The episodes addon's manifest: a series catalog per ids extra (EPISODES_CATALOGS), required, up to 100 ids, for
    the ids prefixed "tortuga-"."""
    return {
        "id": "com.edde746.tortuga.test.episodes",
        "version": "1.0.0",
        "name": "Tortuga Episodes",
        "description": "Harness catalog addon: the library's series' episodes by id, for the calendar and new-episode badges.",
        "resources": ["catalog"],
        "types": ["series"],
        "idPrefixes": ["tortuga-"],
        "catalogs": [{"type": "series", "id": catalog_id, "extra": [{"name": extra, "isRequired": True, "optionsLimit": 100}]}
                     for catalog_id, extra in EPISODES_CATALOGS.items()],
    }


def calendar_meta(base, out_at):
    """Tortuga Calendar Test, with a poster and a background of its own and 46-minute episodes, each with a description
    and a still: episodes 1 to CALENDAR_EPISODES_OUT in January 2026, then episode 4 at [out_at] (epoch seconds; undated
    while None) and episode 5 a day after that (or after now), whose still answers 404 (not made yet)."""
    def iso(seconds):
        return time.strftime("%Y-%m-%dT%H:%M:%S.000Z", time.gmtime(seconds))

    found = series_meta(base, CALENDAR_SERIES_ID, "Tortuga Calendar Test",
                        "Episodes as the calendar and the new-episode badges see them.", CALENDAR_EPISODES_OUT)
    found["meta"]["background"] = art(base, CALENDAR_SERIES_ID, "landscape")
    found["meta"]["runtime"] = "46 min"
    videos = found["meta"]["videos"]
    for episode, released in ((4, out_at), (5, (out_at or time.time()) + 86400)):
        video = {"id": f"{CALENDAR_SERIES_ID}:1:{episode}", "title": f"Episode {episode}", "season": 1, "episode": episode}
        if released is not None:
            video["released"] = iso(released)
        videos.append(video)
    for video in videos:
        number = video["episode"]
        video["thumbnail"] = f"{base}/art/still/missing/{video['id']}.png" if number == 5 else art(base, video["id"], "still")
        video["overview"] = (f"Episode {number} of the calendar test: the crew follows a lead across the harbour, and what "
                             "they find there changes the plan for the rest of the season.")
    return found

def source_streams(media, key, answer):
    """A /sources/ addon's streams, its [answer]th: h264-sdr.mkv and h264-4x3.mkv, kept an hour by the app's cache."""
    name = SOURCES[key][0]
    return {
        "streams": [
            {"name": f"{name}\n1080p", "description": f"h264-sdr.mkv\nAnswer {answer}", "url": f"{media}/h264-sdr.mkv",
             "behaviorHints": {"filename": "h264-sdr.mkv"}},
            {"name": f"{name}\n4:3", "description": f"h264-4x3.mkv\nAnswer {answer}", "url": f"{media}/h264-4x3.mkv",
             "behaviorHints": {"filename": "h264-4x3.mkv"}},
        ],
        "cacheMaxAge": 3600,
    }


def exit_title(spec):
    """`<type>:<tt id>:<name>` of --title."""
    kind, id_, name = spec.split(":", 2)
    if kind not in ("movie", "series") or not re.fullmatch(r"tt\d+", id_) or not name:
        raise argparse.ArgumentTypeError(f"--title {spec!r}: want movie|series:<tt id>:<name>")
    return {"type": kind, "id": id_, "name": name}


def exit_catalog(base, titles, kind):
    return {"metas": [{"id": t["id"], "type": kind, "name": t["name"], "poster": art(base, t["id"], "poster")} for t in titles if t["type"] == kind]}


def search_catalog(base, kind):
    return {"metas": [{"id": f"tortuga-search-{kind}-{i}", "type": kind, "name": f"Tortuga Search {i}", "poster": art(base, f"tortuga-search-{kind}-{i}", "poster")}
                      for i in range(1, 7)]}


def pages_catalog(base, skip):
    """The --pages catalog's page at `skip` (a multiple of PAGE_SIZE): its titles, none past the last page."""
    first = skip + 1
    last = min(skip + PAGE_SIZE, PAGE_SIZE * PAGES)
    return {"metas": [{"id": f"tortuga-page-{i}", "type": "movie", "name": f"Tortuga Page {i}", "poster": art(base, f"tortuga-page-{i}", "poster")}
                      for i in range(first, last + 1)]}


def studio_catalog_name(key, kind):
    return f"{STUDIOS[key][0]} {'Movies' if kind == 'movie' else 'Series'}"


def studio_titles(key, kind, skip=0, count=STUDIO_TITLES):
    """(id, name) of the studio [key]'s [kind] catalog's titles from [skip] on, up to [count] of them."""
    word = "Film" if kind == "movie" else "Show"
    return [(f"tortuga-studio-{key}-{kind}-{n}", f"{STUDIOS[key][0].split()[0]} {word} {n}")
            for n in range(skip + 1, min(skip + count, STUDIO_TITLES) + 1)]


def studio_catalog(base, key, kind, skip):
    """The studio catalog's page at `skip` (a multiple of PAGE_SIZE), none past its last title."""
    return {"metas": [{"id": i, "type": kind, "name": name, "poster": art(base, i, "poster")}
                      for i, name in studio_titles(key, kind, skip, PAGE_SIZE)]}


def studio_source(base, key, kind, by_url=False):
    """A Nuvio addon source of this addon's studio catalog, as AIOMetadata's export writes one; [by_url] names this
    addon's address too (addonBaseUrl), else null."""
    name = studio_catalog_name(key, kind)
    return {"provider": "addon", "addonId": ADDON_ID, "addonBaseUrl": base if by_url else None, "addonName": "Tortuga Test",
            "type": kind, "catalogId": f"tortuga-studio-{key}", "catalogName": name, "title": name, "genre": "None"}


def collection_folder(base, folder_id, title, shape, sources, hide_title=False, cover=None):
    """A Nuvio folder whose cover is [cover], else this addon's art for [folder_id] in its [shape] (POSTER, LANDSCAPE,
    SQUARE), its addon sources also in the legacy catalogSources, as Nuvio and AIOMetadata write them."""
    return {"id": folder_id, "title": title, "coverImageUrl": cover or art(base, folder_id, shape.lower()), "focusGifUrl": None,
            "focusGifEnabled": False, "coverEmoji": None, "tileShape": shape, "hideTitle": hide_title, "sources": sources,
            "catalogSources": [{k: v for k, v in s.items() if k != "provider"} for s in sources if s["provider"] == "addon"],
            "heroBackdropUrl": None, "heroVideoUrl": None, "titleLogoUrl": None}


def collections_pack(base):
    """--collections' pack (the module's docstring), its URLs on [base]."""
    def collection(collection_id, title, backdrop, view_mode, all_tab, folders):
        return {"id": collection_id, "title": title, "backdropImageUrl": backdrop, "pinToTop": False, "focusGlowEnabled": True,
                "viewMode": view_mode, "showAllTab": all_tab, "folders": folders}

    missing_id, missing_name = MISSING_ADDON
    return [
        collection("tortuga-studios", "Studios", art(base, "tortuga-studios", "landscape"), "TABBED_GRID", True, [
            collection_folder(base, "tortuga-folder-ember", "Ember Pictures", "POSTER",
                              [studio_source(base, "ember", "movie"), studio_source(base, "ember", "series")]),
            collection_folder(base, "tortuga-folder-tide", "Tide Studio", "LANDSCAPE",
                              [studio_source(base, "tide", "movie", True), studio_source(base, "tide", "series", True)]),
            collection_folder(base, "tortuga-folder-moss", "Moss Works", "SQUARE", [studio_source(base, "moss", "movie")]),
            collection_folder(base, "tortuga-folder-gull", "Gull Records", "LANDSCAPE", [studio_source(base, "tide", "movie")],
                              cover=art(base, "tortuga-folder-gull", "logo")),
            collection_folder(base, "tortuga-folder-fern", "Fern Reels", "POSTER", [studio_source(base, "ember", "series")],
                              cover=f"{base}/art/gif/tortuga-folder-fern.gif"),
            collection_folder(base, "tortuga-folder-dusk", "Dusk House", "POSTER", [studio_source(base, "dusk", "series")],
                              hide_title=True),
        ]),
        collection("tortuga-mixed", "Mixed", None, "ROWS", False, [
            collection_folder(base, "tortuga-folder-mixed", "Mixed Sources", "LANDSCAPE", [
                studio_source(base, "moss", "movie"),
                {"provider": "addon", "addonId": missing_id, "addonBaseUrl": None, "addonName": missing_name, "type": "movie",
                 "catalogId": "missing-top", "catalogName": "Missing Top", "title": "Missing Top", "genre": None},
                {"provider": "letterboxd", "title": "Letterboxd Watchlist", "url": "https://letterboxd.com/example/watchlist/"},
            ]),
            collection_folder(base, "tortuga-folder-trakt", "Trakt List", "POSTER", [
                {"provider": "trakt", "title": "Tortuga Trakt List", "traktListId": TRAKT_LIST_ID, "mediaType": "MOVIE",
                 "sortBy": "rank", "sortHow": "asc"},
            ]),
            collection_folder(base, "tortuga-folder-tmdb", "TMDB Only", "SQUARE", [
                {"provider": "tmdb", "tmdbSourceType": "DISCOVER", "title": "New Action Movies", "tmdbId": None,
                 "mediaType": "MOVIE", "sortBy": "popularity.desc", "filters": {"withGenres": "28"}},
                {"provider": "tmdb", "tmdbSourceType": "COLLECTION", "title": "Bad Boys Collection", "tmdbId": 14890,
                 "mediaType": "MOVIE", "sortBy": "original", "filters": {}},
            ]),
        ]),
    ]


def catalog(base):
    movie = meta(base)["meta"]
    return {"metas": [{key: movie[key] for key in ("id", "type", "name", "poster", "releaseInfo")}]}


def meta(base, movie_id=MOVIE_ID, name="Tortuga Remux Test",
         description="A 30-second HEVC HDR10 REMUX with three audio tracks, five subtitle tracks and chapters."):
    return {
        "meta": {
            "id": movie_id,
            "type": "movie",
            "name": name,
            "poster": art(base, movie_id, "poster"),
            "description": description,
            "releaseInfo": "2026",
            "runtime": "1 min",
        }
    }


def series_meta(base, series_id=SERIES_ID, name="Tortuga Series Test",
                description="Three 20-second episodes, each with a stream in a binge group and one without.",
                episodes=SERIES_EPISODES):
    return {
        "meta": {
            "id": series_id,
            "type": "series",
            "name": name,
            "poster": art(base, series_id, "poster"),
            "description": description,
            "releaseInfo": "2026",
            "videos": [
                {
                    "id": f"{series_id}:1:{episode}",
                    "title": f"Episode {episode}",
                    "season": 1,
                    "episode": episode,
                    "released": f"2026-01-0{episode}T00:00:00.000Z",
                }
                for episode in range(1, episodes + 1)
            ],
        }
    }


def meta_streams_meta(base, media):
    found = series_meta(base, META_STREAMS_ID, "Tortuga Meta Streams Test",
                        "Two 20-second episodes whose streams come in the meta itself.", META_STREAMS_EPISODES)
    for video in found["meta"]["videos"]:
        video["streams"] = [{
            "name": "Tortuga Meta\n1080p",
            "description": "h264-sdr.mkv, carried in the meta",
            "url": f"{media}/h264-sdr.mkv",
        }]
    found["meta"]["links"] = [{"name": "Tortuga Meta Streams Test", "category": "share", "url": "https://example.com/tortuga-meta-streams"}]
    found["meta"]["website"] = "https://example.com/tortuga"
    return found


def unnumbered_meta(base):
    """The series as series_meta has it, its videos without "season" and "episode", with cast and a director: on Apple
    TV, Cast & crew under the episodes makes the page move down to them (tvOS DetailTests)."""
    found = series_meta(base, UNNUMBERED_ID, "Tortuga Unnumbered Test",
                        "Three episodes without season or episode numbers.", UNNUMBERED_EPISODES)
    found["meta"]["cast"] = ["Ada Lovelace", "Alan Turing", "Grace Hopper"]
    found["meta"]["director"] = ["Edsger Dijkstra"]
    for index, video in enumerate(found["meta"]["videos"], 1):
        video["id"] = f"{UNNUMBERED_ID}:{index}"
        del video["season"], video["episode"]
    return found


def bidi_meta(base):
    """The series as series_meta has it, its episodes titled BIDI_EPISODES, BIDI_CAST its cast."""
    found = series_meta(base, BIDI_SERIES_ID, "Tortuga Bidi Test",
                        "Two 20-second episodes whose titles end in punctuation.", len(BIDI_EPISODES))
    found["meta"]["cast"] = BIDI_CAST
    for video, title in zip(found["meta"]["videos"], BIDI_EPISODES):
        video["title"] = title
    return found


def hero_meta(base, kind, hero_id, name, logo=None):
    """A Detail hero title: the bright frame as its backdrop and, where given, the /hero/ `logo`."""
    description = "A title for the Detail screen's top: its logo, metadata line and ratings over the backdrop."
    found = series_meta(base, hero_id, name, description, 1) if kind == "series" else meta(base, hero_id, name, description)
    found["meta"].update({
        "background": f"{base}/frame/bright.png",
        "releaseInfo": "2026–",
        "runtime": "24 min",
        "genres": ["Animation", "Comedy"],
        "imdbRating": "7.5",
    })
    if logo:
        found["meta"]["logo"] = f"{base}/hero/{logo}.png"
    return found


def similar_catalog(base, title_id):
    """The --similar catalog's titles like `title_id` (RELATED_ID, SERIES_ID, RELATED_EMPTY_ID, RELATED_NONE_ID or
    RELATED_LOADING_ID), best first."""
    plain = hero_meta(base, "movie", HERO_PLAIN_ID, "Tortuga Hero Plain Test")["meta"]
    titles = {
        RELATED_ID: [{key: plain[key] for key in ("id", "type", "name", "poster", "releaseInfo")},
                     {"id": "tt0068646", "type": "movie", "name": "The Godfather", "poster": art(base, "tt0068646", "poster"), "releaseInfo": "1972"}],
        SERIES_ID: [{"id": HERO_WIDE_ID, "type": "series", "name": "Tortuga Hero Wide Test", "poster": art(base, HERO_WIDE_ID, "poster")},
                    {"id": SEASONS_ID, "type": "series", "name": "Tortuga Seasons Test", "poster": art(base, SEASONS_ID, "poster")}],
        RELATED_EMPTY_ID: [],
        RELATED_NONE_ID: [],
        RELATED_LOADING_ID: [],
    }
    return {"metas": titles[title_id]}


def related_meta(base, related_id, name, imdb=None):
    """A --similar movie over the bright frame, with `imdb` as its imdb_id where given."""
    found = meta(base, related_id, name, "A title for Detail's More like this.")
    found["meta"]["background"] = f"{base}/frame/bright.png"
    if imdb:
        found["meta"]["imdb_id"] = imdb
    return found


def related_series_meta(base, series_id, name):
    """A --similar series as series_meta has it, with cast and a director: Cast & crew under More like this."""
    found = series_meta(base, series_id, name, "A series whose More like this has no card.")
    found["meta"]["cast"] = ["Ada Lovelace", "Alan Turing", "Grace Hopper"]
    found["meta"]["director"] = ["Edsger Dijkstra"]
    return found


def seasons_meta(base):
    """The SEASONS_COUNT-season series: hero_meta's wide-logo series with one episode in each season."""
    found = hero_meta(base, "series", SEASONS_ID, "Tortuga Seasons Test", "wide")
    found["meta"]["videos"] = [
        {
            "id": f"{SEASONS_ID}:{season}:1",
            "title": "Episode 1",
            "season": season,
            "episode": 1,
            "released": f"{2000 + season}-01-01T00:00:00.000Z",
        }
        for season in range(1, SEASONS_COUNT + 1)
    ]
    return found


def spoilers_meta(base):
    """The SPOILERS_EPISODES-episode series, each episode's still its number in large digits, the last
    SPOILERS_UPCOMING released in 2099."""
    return {
        "meta": {
            "id": SPOILERS_ID,
            "type": "series",
            "name": "Tortuga Spoilers Test",
            "poster": art(base, SPOILERS_ID, "poster"),
            "background": art(base, SPOILERS_ID, "landscape"),
            "description": "Eight episodes whose stills show their numbers, for the blur of unwatched episodes.",
            "releaseInfo": "2026",
            "videos": [
                {
                    "id": f"{SPOILERS_ID}:1:{episode}",
                    "title": f"Episode {episode}",
                    "season": 1,
                    "episode": episode,
                    "released": f"{2099 if episode > SPOILERS_EPISODES - SPOILERS_UPCOMING else 2026}-01-{episode:02d}T00:00:00.000Z",
                    "thumbnail": art(base, f"{SPOILERS_ID}:1:{episode}", "still"),
                    "overview": f"Episode {episode} of the spoilers test: its still shows {episode} in large digits. The "
                                f"description runs over a few lines, so a hidden one shows how many it takes.",
                }
                for episode in range(1, SPOILERS_EPISODES + 1)
            ],
        }
    }


def late(seconds, answer):
    """`answer`, given after `seconds`: the server answers each request on its own thread."""
    time.sleep(seconds)
    return answer()


def png(width, height, rows, alpha=False):
    """A PNG of `rows` (each row's 8-bit RGB pixels, RGBA with `alpha`, behind its filter byte 0: none)."""
    def chunk(kind, data):
        return len(data).to_bytes(4, "big") + kind + data + zlib.crc32(kind + data).to_bytes(4, "big")

    header = width.to_bytes(4, "big") + height.to_bytes(4, "big") + bytes([8, 6 if alpha else 2, 0, 0, 0])
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header) + chunk(b"IDAT", zlib.compress(bytes(rows), 9)) + chunk(b"IEND", b"")


def frame_png(top, bottom, width=1920, height=1080):
    """A PNG of a vertical gradient from the RGB colour `top` to `bottom`."""
    rows = bytearray()
    for y in range(height):
        t = y / (height - 1)
        pixel = bytes(round(a + (b - a) * t) for a, b in zip(top, bottom))
        rows += b"\x00" + pixel * width
    return png(width, height, rows)


def logo_png(size, art, colour):
    """A clear logo's PNG: transparent but for bars of the RGB `colour` standing on the bottom of the box `art` (left,
    top, right, bottom), 48 px wide and 16 apart, every other one as tall as the box, the rest two thirds of it."""
    width, height = size
    left, top, right, bottom = art
    clear, solid = bytes(4), bytes(colour) + b"\xff"
    rows = bytearray()
    for y in range(height):
        rows += b"\x00"
        for x in range(width):
            bar, offset = divmod(x - left, 64)
            bar_top = top + (bottom - top) // 3 if bar % 2 else top
            rows += solid if left <= x < right and offset < 48 and bar_top <= y < bottom else clear
    return png(width, height, rows, alpha=True)


def art_stops(pick):
    """The colour stops of the artwork whose SHA-256 is [pick]: two or three (hue, lightness, saturation), as colorsys
    has them, of mid saturation and lightness, each hue 40 to 100° on from the last."""
    hue = int.from_bytes(pick[:2], "big") / 65536
    step = (40 + pick[2] % 61) / 360 * (1 if pick[3] % 2 else -1)
    return [(hue + n * step, 0.36 + pick[4 + n] / 255 * 0.2, 0.32 + pick[7 + n] / 255 * 0.18) for n in range(2 + pick[10] % 2)]


@functools.lru_cache(maxsize=None)
def art_png(seed, shape):
    """`seed`'s artwork in `shape` (ART_SIZES), all picked from its SHA-256, so a title looks the same in every run and
    two titles differ: a diagonal gradient through its colour stops (art_stops), under a soft highlight in its upper
    half; a logo (`shape` "logo") is HERO_LOGOS' wide bars in its first colour; a still (`shape` "still") has the number
    after the seed's last colon (its episode's) over the gradient in large white seven-segment digits, a picture whose
    content a blur hides."""
    pick = hashlib.sha256(seed.encode()).digest()
    stops = art_stops(pick)
    if shape == "logo":
        size, box = HERO_LOGOS["wide"]
        return logo_png(size, box, tuple(round(c * 255) for c in colorsys.hls_to_rgb(stops[0][0] % 1, 0.62, stops[0][2])))
    width, height = ART_SIZES[shape]
    # The gradient along a strip twice the width, eased into and out of each stop; row y is the strip from its diagonal
    # offset on, down to the right or, flipped, up to the right.
    strip = bytearray()
    for i in range(2 * width):
        t = i / (2 * width - 1) * (len(stops) - 1)
        n = min(int(t), len(stops) - 2)
        f = (t - n) * (t - n) * (3 - 2 * (t - n))
        h, l, s = (a + (b - a) * f for a, b in zip(stops[n], stops[n + 1]))
        strip += bytes(round(c * 255) for c in colorsys.hls_to_rgb(h % 1, l, s))
    flip = pick[11] % 2
    # The highlight: at most a quarter of the way to white, a Gaussian around a point in the upper half, its strength
    # for each 8-px run of a row in 24 steps, each step a byte table.
    cx, cy = (0.2 + pick[12] / 255 * 0.6) * width, (0.1 + pick[13] / 255 * 0.35) * height
    sigma = 0.4 * max(width, height)
    tables = [bytes(round(v + (255 - v) * 0.25 * k / 24) for v in range(256)) for k in range(25)]
    runs = [(x, min(x + 8, width), math.exp(-((x + 4 - cx) / sigma) ** 2 / 2)) for x in range(0, width, 8)]
    rows = bytearray()
    for y in range(height):
        down = math.exp(-((y - cy) / sigma) ** 2 / 2)
        shift = round((height - 1 - y if flip else y) * width / height)
        rows += b"\x00"
        for start, end, across in runs:
            rows += strip[3 * (shift + start):3 * (shift + end)].translate(tables[round(24 * across * down)])
    if shape == "still":
        draw_number(rows, width, seed.rsplit(":", 1)[-1])
    return png(width, height, rows)


@functools.lru_cache(maxsize=None)
def art_gif(seed, width=480, height=360):
    """`seed`'s artwork as a looping two-frame GIF: its colour stops (art_stops) eased into a 256-colour palette, the
    first frame a diagonal gradient through them, the second the same one reversed, half a second each. Coded without
    compression, as GIF allows: 9-bit codes, each a pixel, a clear code every 250 of them, so the code table never
    grows."""
    stops = art_stops(hashlib.sha256(seed.encode()).digest())
    palette = bytearray()
    for i in range(256):
        t = i / 255 * (len(stops) - 1)
        n = min(int(t), len(stops) - 2)
        f = (t - n) * (t - n) * (3 - 2 * (t - n))
        h, l, s = (a + (b - a) * f for a, b in zip(stops[n], stops[n + 1]))
        palette += bytes(round(c * 255) for c in colorsys.hls_to_rgb(h % 1, l, s))

    def frame(reverse):
        bits = count = 0
        out = bytearray()

        def code(value):
            nonlocal bits, count
            bits |= value << count
            count += 9
            while count >= 8:
                out.append(bits & 0xFF)
                bits >>= 8
                count -= 8

        for y in range(height):
            for x in range(width):
                if (y * width + x) % 250 == 0:
                    code(256)
                index = round((x / (width - 1) + y / (height - 1)) / 2 * 255)
                code(255 - index if reverse else index)
        code(257)
        if count:
            out.append(bits & 0xFF)
        blocks = b"".join(bytes([len(out[i:i + 255])]) + out[i:i + 255] for i in range(0, len(out), 255))
        size = width.to_bytes(2, "little") + height.to_bytes(2, "little")
        # Graphic control: 50 hundredths of a second; then the frame's place and size, no local palette; code size 8.
        return b"\x21\xf9\x04\x00\x32\x00\x00\x00" + b"\x2c\x00\x00\x00\x00" + size + b"\x00" + b"\x08" + blocks + b"\x00"

    header = b"GIF89a" + width.to_bytes(2, "little") + height.to_bytes(2, "little") + b"\xf7\x00\x00" + bytes(palette)
    loop = b"\x21\xff\x0bNETSCAPE2.0\x03\x01\x00\x00\x00"
    return header + loop + frame(False) + frame(True) + b"\x3b"


# A seven-segment digit's segments lit for each digit: top, upper right, lower right, bottom, lower left, upper left,
# middle.
SEGMENTS = {"0": "abcdef", "1": "bc", "2": "abdeg", "3": "abcdg", "4": "bcfg", "5": "acdfg", "6": "acdefg", "7": "abc",
            "8": "abcdefg", "9": "abcdfg"}


def draw_number(rows, width, number):
    """Draws `number`'s digits in white into `rows` (a PNG's unfiltered RGB rows `width` wide), each digit 220 x 420 px
    in 50-px strokes, 60 px apart, centred."""
    digit_w, digit_h, stroke, gap = 220, 420, 50, 60
    height = len(rows) // (1 + 3 * width)
    digits = [d for d in number if d in SEGMENTS]
    left = (width - len(digits) * digit_w - (len(digits) - 1) * gap) // 2
    top = (height - digit_h) // 2

    def fill(x0, y0, x1, y1):
        for y in range(y0, y1):
            start = y * (1 + 3 * width) + 1 + 3 * x0
            rows[start:start + 3 * (x1 - x0)] = b"\xff" * (3 * (x1 - x0))

    half = top + (digit_h - stroke) // 2
    for index, digit in enumerate(digits):
        x = left + index * (digit_w + gap)
        boxes = {
            "a": (x, top, x + digit_w, top + stroke),
            "b": (x + digit_w - stroke, top, x + digit_w, half + stroke),
            "c": (x + digit_w - stroke, half, x + digit_w, top + digit_h),
            "d": (x, top + digit_h - stroke, x + digit_w, top + digit_h),
            "e": (x, half, x + stroke, top + digit_h),
            "f": (x, top, x + stroke, half + stroke),
            "g": (x, half, x + digit_w, half + stroke),
        }
        for segment in SEGMENTS[digit]:
            fill(*boxes[segment])


def streams(media, tour=False):
    listed = [
        {
            "name": "Tortuga Test\n2160p",
            "description": "remux.mkv\nHEVC HDR10 · TrueHD 5.1 · 14.6 MB",
            "url": f"{media}/remux.mkv",
            "behaviorHints": {"filename": "remux.mkv", "videoSize": 15296153, "bingeGroup": "tortuga-test-remux"},
        },
        bound(media),
        {
            "name": "Tortuga Test\nPeer-to-peer",
            "description": "Listed to exercise the unplayable-sources note",
            "infoHash": "0123456789abcdef0123456789abcdef01234567",
            "fileIdx": 0,
        },
        *UNSAFE_LINKS,
    ]
    if tour:
        # Last, so the tests' stream rows keep their places.
        listed.append({
            "name": "Tortuga Test\nMissing",
            "description": "missing.mkv\nThe media server answers 404",
            "url": f"{media}/missing.mkv",
            "behaviorHints": {"filename": "missing.mkv"},
        })
        listed.append(unbound(media))
    return {"streams": listed}


def bound(media):
    """h264-sdr.mkv in a binge group: the next episode's stream in the same group plays on without asking."""
    return {
        "name": "Tortuga Test\n1080p",
        "description": "h264-sdr.mkv\nH.264 SDR",
        "url": f"{media}/h264-sdr.mkv",
        "behaviorHints": {"filename": "h264-sdr.mkv", "bingeGroup": "tortuga-test-h264"},
    }


def unbound(media):
    """h264-sdr.mkv without a binge group: the next episode has no stream to go on with, so its end asks for one."""
    return {
        "name": "Tortuga Test\n720p",
        "description": "h264-sdr.mkv without a binge group\nThe next episode asks for its stream",
        "url": f"{media}/h264-sdr.mkv",
        "behaviorHints": {"filename": "h264-sdr.mkv"},
    }


def errvid_meta(base):
    found = series_meta(base, ERRVID_SERIES_ID, "Tortuga Error Video Test",
                        "Three 24-minute episodes from an addon whose host answers episode 1 with an error video.",
                        ERRVID_EPISODES)
    found["meta"]["runtime"] = "24 min"
    for video in found["meta"]["videos"]:
        video["id"] = f"{ERRVID_IMDB_ID}:1:{video['episode']}"
    return found


def errvid_stream(media, episode, sized):
    """Episode [episode]'s one stream of the error video series: episodes 1 and 3 a lazy hosted link to an error
    slate (2 min, 10 s) named as the episode's release and, when [sized], sized as it; episode 2 the bound h264-sdr.mkv;
    one binge group."""
    if episode == 2:
        return bound(media)
    release = f"Tortuga.Error.Video.Test.{episode_tag(1, episode)}.1080p.WEB.H264.mkv"
    return {
        "name": "Tortuga Hosted\n1080p",
        "description": f"{release}\n⚡ Ready{' · 1 GB' if sized else ''}",
        "url": served(media, "resolve", "error-slate.mp4" if episode == 1 else "error-slate-10s.mp4", {"expire": ERRVID_LINK_EXPIRE}),
        "behaviorHints": {"filename": release, "bingeGroup": bound(media)["behaviorHints"]["bingeGroup"]}
                         | ({"videoSize": ERRVID_SIZE} if sized else {}),
    }


def cut_meta(base):
    return series_meta(base, CUT_SERIES_ID, "Tortuga Cut Link Test",
                       "Two episodes, the first from links that die partway through its file.", CUT_EPISODES)


def cut_stream(media, episode):
    """Episode [episode]'s one stream of the cut-link series: episode 1 tracks-next.mkv through a lazy link whose CDN
    links each drop their transfer after CUT_LINK_BYTES, episode 2 the bound h264-sdr.mkv; one binge group."""
    if episode == 2:
        return bound(media)
    return {
        "name": "Tortuga Test\n1080p",
        "description": "tracks-next.mkv through a lazy link\nIts CDN links die after 1.2 MB",
        "url": served(media, "resolve", "tracks-next.mkv", {"bytes": CUT_LINK_BYTES, "rate": CUT_LINK_RATE}),
        "behaviorHints": {"filename": "tracks-next.mkv", "bingeGroup": bound(media)["behaviorHints"]["bingeGroup"]},
    }


def sorted_streams(media):
    """Seven releases as an addon's plain text, out of every order: their resolutions, sources and sizes read from the
    name and the description (the file name the release's); each plays h264-sdr.mkv."""
    releases = [
        ("1080p WEB-DL", "Tortuga.Sorted.Test.2026.1080p.WEB-DL.H264.AAC", "2.1 GB"),
        ("2160p WEB-DL DV", "Tortuga.Sorted.Test.2026.2160p.WEB-DL.DV.HDR10.HEVC.DDP5.1", "14.8 GB"),
        ("720p HDTV", "Tortuga.Sorted.Test.2026.720p.HDTV.x264.AAC", "900 MB"),
        ("WEBRip", "Tortuga.Sorted.Test.2026.WEBRip.x264.AAC", "1.4 GB"),
        ("2160p REMUX", "Tortuga.Sorted.Test.2026.2160p.BluRay.REMUX.HEVC.HDR10.TrueHD.7.1", "58.3 GB"),
        ("1080p BluRay", "Tortuga.Sorted.Test.2026.1080p.BluRay.x265.10bit.AAC5.1", "4.6 GB"),
        ("480p DVDRip", "Tortuga.Sorted.Test.2026.DVDRip.XviD.MP3", "700 MB"),
    ]
    return {
        "streams": [
            {
                "name": f"Tortuga Sorted\n{label}",
                "description": f"{release}\n💾 {size}",
                "url": f"{media}/h264-sdr.mkv",
                "behaviorHints": {"filename": f"{release}.mkv"},
            }
            for label, release, size in releases
        ]
    }


def recorded(media, folder, video, count=None, sized=True):
    """The stream list in `folder` (styled-texts, plain-texts) for `video` (shared's fixtures/streams file name), its
    first `count` rows (all by default), their texts as written, each playing h264-sdr.mkv; without `sized` their
    sizes (behaviorHints.videoSize) go, which would make the small file read as an addon's error video in the
    release's place."""
    # Resolved here, not at import: the trackers container imports this file alone, outside the checkout.
    fixtures = pathlib.Path(__file__).resolve().parents[2] / "shared/src/commonTest/fixtures/streams"
    streams = json.loads((fixtures / folder / f"{video}.json").read_text())["streams"][:count]
    played = [dict(stream, url=f"{media}/h264-sdr.mkv") for stream in streams]
    if not sized:
        for stream in played:
            stream["behaviorHints"] = {k: v for k, v in stream.get("behaviorHints", {}).items() if k != "videoSize"}
    return played


def real_streams(media):
    """A film's styled-text list, then its plain-text list's first 30 rows."""
    return {"streams": recorded(media, "styled-texts", "movie-ex0001", 20) + recorded(media, "plain-texts", "movie-ex0001", 30)}


def common_streams(media):
    """The descriptions most people see: emoji-labelled facts with the file name last, a line per emoji-led group,
    labelled plain text, a plain addon's two lines, then a plain-text list's first rows."""
    texts = [
        ("Example 2160p",
         "🎥 BluRay REMUX 🎞️ HEVC 🏷️ Example \n📺 DV | HDR10 🎧 Atmos | TrueHD 🔊 7.1\n"
         "📦 58.3 GB / 117 GB (54.6 Mbps)📅 8d 🔍 Library\n🌎 English 📝 English\n"
         "📁 The.Copper.Orchard.1971.UHD.BluRay.2160p.TrueHD.Atmos.7.1.DV.HEVC.REMUX.mkv"),
        ("Example 1080p",
         "🎥 WEB-DL 🎞️ AVC 🏷️ Example \n🎧 DD+ 🔊 5.1\n📦 9.8 GB 📅 3d 🔍 Library\n🌎 English | French 📝 English\n"
         "📁 The.Copper.Orchard.1971.1080p.WEB-DL.DDP5.1.H.264.mkv"),
        ("🔥4K UHD",
         "🎬 The Copper Orchard (1971) \n🎥 BluRay REMUX 📺 DV | HDR10 🎞️ HEVC \n🎧 Atmos | TrueHD 🔊 7.1 🗣️ 🇬🇧\n"
         "📦 58.3 GB 📊 54.6 Mbps \n🏷️ Example 📡 Library"),
        ("🚀 FHD",
         "🎬 The Copper Orchard (1971) \n🎥 BluRay 🎞️ AVC ⏱️ 2h 22m \n🎧 DTS-HD MA 🔊 5.1 🗣️ 🇬🇧 / 🇫🇷📝 🇬🇧\n"
         "📦 14.2 GB 📊 13.4 Mbps \n🏷️ Example 📡 Library"),
        ("Example Library (2160p)",
         "Quality: BluRay REMUX\nName: The.Copper.Orchard.1971.UHD.BluRay.2160p.REMUX.mkv\n"
         "Size: 58.3 GB | Source: Library\nLanguages: English | Subtitles: English, French"),
        ("Archive\n1080p", "The Copper Orchard (1971)\nEnglish audio, 2.1 GB"),
    ]
    streams = [{"name": name, "description": description, "url": f"{media}/h264-sdr.mkv"} for name, description in texts]
    return {"streams": streams + recorded(media, "plain-texts", "movie-ex0001", 4)}


def formatted(media):
    """remux.mkv and h264-sdr.mkv as an addon's custom formatter lists them (2026-10): each name line padded with
    no-break spaces and zero-width joiners to fill Stremio's fixed-width name column, the description's lines led by
    symbols, sizes as "file / folder"."""
    pad = "\u00a0"
    zwj = "\u200d"
    return {
        "streams": [
            {
                "name": f"{pad * 3}4K{pad}{zwj * 2}{pad * 4}{zwj * 3}⚡{zwj * 2}\n{pad * 2}〈Remux〉{zwj}{pad * 5}{zwj}\n"
                        f"{pad * 2}★★★★★{pad * 12}",
                "description": "✎  Tortuga Formatted Test (2026) \n▣  HEVC  ✦ HDR10  \n♬  TrueHD  ♯ 5.1\n"
                               "◈ 14.6 / 29.2 MB · 4.1 ᴹᵇᵖˢ · 1d\n⛉ Tortuga · Harness\n✓  ᴇɴ  · sᴜʙ  (ᴇɴ)   »   ʀᴇᴍᴜx ₆₉₀₀",
                "url": f"{media}/remux.mkv",
                "behaviorHints": {"filename": "remux.mkv", "videoSize": 15296153, "bingeGroup": "tortuga-test-formatted-remux"},
            },
            {
                "name": f"{pad * 3}1080P{pad}{zwj * 2}{pad * 4}{zwj * 3}⚡{zwj * 2}\n{pad * 2}〈Web{zwj}-{zwj}dl〉{zwj}{pad * 5}{zwj}\n"
                        f"{pad * 2}★★★☆{pad * 12}",
                "description": "✎  Tortuga Formatted Test (2026) \n▣  AVC  \n♬  AAC  ♯ 2.0\n"
                               "❖ 1.2 / 3.4 MB · 0.4 ᴹᵇᵖˢ · 3h\n⛉ Tortuga · Harness\n✓  ᴇɴ   »   ᴡᴇʙ ᴛ₁ ₄₈₈₅",
                "url": f"{media}/h264-sdr.mkv",
                "behaviorHints": {"filename": "h264-sdr.mkv", "bingeGroup": "tortuga-test-formatted-h264"},
            },
        ]
    }


def matrix_streams(media):
    """The playback info's formats, one stream each: the HDR10 remux (TrueHD 5.1 first), Dolby Vision profile 8.1,
    DTS-HD MA 7.1."""
    files = [
        ("HDR10 remux", "remux.mkv", "HEVC HDR10 · TrueHD 5.1"),
        ("Dolby Vision 8", "dv-p81.mkv", "HEVC Dolby Vision 8.1 · AAC 2.0"),
        ("DTS-HD MA", "dts-hdma.mkv", "H.264 SDR · DTS-HD MA 7.1"),
    ]
    return {
        "streams": [
            {
                "name": f"Tortuga Test\n{name}",
                "description": f"{file}\n{formats}",
                "url": f"{media}/{file}",
                "behaviorHints": {"filename": file, "bingeGroup": f"tortuga-matrix-{file}"},
            }
            for name, file, formats in files
        ]
    }


def headers_meta(base, movie_id=HEADERS_ID, name="Tortuga Headers Test"):
    found = meta(base, movie_id, name)
    found["meta"]["description"] = "A 30-second movie whose stream needs its proxyHeaders sent along."
    return found


def headers_stream(base, request=None):
    """remux.mkv through this addon (/headers/), which logs each request's headers, with proxyHeaders [request] (by
    default the test header and a User-Agent), which whatever plays it should send."""
    request = request or {TEST_HEADER[0]: TEST_HEADER[1], "User-Agent": HEADERS_USER_AGENT}
    return {
        "name": "Tortuga Test\nHeaders",
        "description": f"remux.mkv with proxyHeaders\n{TEST_HEADER[0]} and User-Agent {request['User-Agent']}",
        "url": f"{base}/headers/remux.mkv",
        "behaviorHints": {
            "filename": "remux.mkv",
            "notWebReady": True,
            "proxyHeaders": {"request": request},
        },
    }


def hls_stream(base, fail=None):
    """The /hls/ playlist, with the headers stream's request headers and an HLS Content-Type as its response header;
    with [fail] (a token), its segment's first request answers 502."""
    query = f"?fail={fail}" if fail is not None else ""
    return {
        "name": "Tortuga Test\nHLS",
        "description": "h264-sdr.mkv as HLS\nproxyHeaders request and response" + ("\nIts segment fails once" if query else ""),
        "url": f"{base}/hls/master{query}",
        "behaviorHints": {
            "notWebReady": True,
            "proxyHeaders": {
                "request": {TEST_HEADER[0]: TEST_HEADER[1], "User-Agent": HEADERS_USER_AGENT},
                "response": {"Content-Type": "application/vnd.apple.mpegurl"},
            },
        },
    }


def hls_playlist(base, name, fail=None):
    """The master playlist (one 1080p variant) or its media playlist (h264-sdr.mkv's 20 s as one segment), each link
    carrying [fail] (the failing segment's token) when given."""
    query = f"?fail={fail}" if fail is not None else ""
    if name == "master":
        return f"#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=1920x1080\n{base}/hls/media{query}\n"
    return (f"#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:20\n#EXT-X-MEDIA-SEQUENCE:0\n"
            f"#EXTINF:20.0,\n{base}/headers/h264-sdr.mkv{query}\n#EXT-X-ENDLIST\n")


def live_meta(base, item_id):
    """A --live item: a tv title of its own (no videos), a landscape card."""
    name, release, genre, description = LIVE_ITEMS[item_id]
    return {"id": item_id, "type": "tv", "name": name, "poster": art(base, item_id, "landscape"), "posterShape": "landscape",
            "background": art(base, item_id, "landscape"), "releaseInfo": release, "genres": [genre] if genre else [],
            "description": description}


def live_catalog(base, catalog_id, genre=None):
    ids = [LIVE_CHANNEL_ID] if catalog_id == LIVE_247_CATALOG_ID else [LIVE_MATCH_ID, LIVE_NEWS_ID]
    metas = [live_meta(base, i) for i in ids]
    return {"metas": [m for m in metas if genre is None or genre in m["genres"]]}


def live_streams(media, item_id, answer):
    """A --live item's streams (the module's docstring), the [answer]th asked for."""
    source = f"{media}/h264-sdr.live.m3u8"
    if item_id == LIVE_MATCH_ID:
        return {"streams": [{"name": "Tortuga Live\nHD", "description": f"Live, {LIVE_MATCH_WINDOW} s to rewind",
                             "url": f"{source}?window={LIVE_MATCH_WINDOW}"}]}
    if item_id == LIVE_NEWS_ID:
        return {"streams": [{"name": "Tortuga Live\nHD", "description": f"Live, ends after {LIVE_NEWS_LENGTH} s",
                             "url": f"{source}?window=60&ends={LIVE_NEWS_LENGTH}&n={answer}"}]}
    query = urllib.parse.urlencode({"referer": LIVE_REFERER, "agent": LIVE_USER_AGENT})
    return {"streams": [
        {"name": "Tortuga Live\nHD", "description": "Live\nNeeds its Referer and User-Agent", "url": f"{source}?{query}",
         "behaviorHints": {"notWebReady": True,
                           "proxyHeaders": {"request": {"Referer": LIVE_REFERER, "User-Agent": LIVE_USER_AGENT}}}},
        {"name": "Tortuga Live\nSD", "description": "Live\nPlain", "url": source},
        {"name": "Tortuga Live\nBackup", "description": "Live\nThe media server answers 404",
         "url": f"{media}/missing.live.m3u8"},
    ]}


def guide_channel(base, channel_id, name, day=None):
    """A --guide channel: a tv title of its own, live, with [day]'s programmes (a datetime.date, UTC) as its videos."""
    item = {"id": channel_id, "type": "tv", "name": name, "poster": art(base, channel_id, "square"), "posterShape": "square",
            "logo": art(base, channel_id, "square"), "behaviorHints": {"isLive": True}}
    if day is not None:
        item["behaviorHints"]["hasScheduledVideos"] = True
        item["videos"] = guide_programmes(base, channel_id, day)
    return item


def guide_programmes(base, channel_id, day):
    """[channel_id]'s programmes that overlap the UTC day [day]: back to back in slots of the channel's length, the
    channel's [index]th starting GUIDE_STAGGER minutes past each UTC midnight times [index] (so its programmes cross
    midnight and come in both days' answers), the last channel's every other slot (gaps); a slot's number, counted from
    GUIDE_ANCHOR, picks its title."""
    index, (_, name, minutes) = next((i, c) for i, c in enumerate(GUIDE_CHANNELS) if c[0] == channel_id)
    length = datetime.timedelta(minutes=minutes)
    day_start = datetime.datetime.combine(day, datetime.time(), datetime.timezone.utc)
    day_end = day_start + datetime.timedelta(days=1)
    anchor = GUIDE_ANCHOR + datetime.timedelta(minutes=GUIDE_STAGGER * index)
    slot = (day_start - anchor) // length
    programmes = []
    at = anchor + slot * length
    while at < day_end:
        until = at + length
        gap = index == len(GUIDE_CHANNELS) - 1 and slot % 2 == 1
        if not gap and until > day_start:
            title = GUIDE_TITLES[(slot + index) % len(GUIDE_TITLES)]
            iso = at.strftime("%Y-%m-%dT%H:%M:%S.000Z")
            programme = {"id": f"{channel_id}:epg:{iso}", "title": title, "released": iso, "startTime": iso,
                         "endTime": until.strftime("%Y-%m-%dT%H:%M:%S.000Z"),
                         "overview": f"{title} on {name}: a test programme of {minutes} minutes.",
                         "runtime": f"{minutes} min", "releaseInfo": str(at.year), "genres": ["News" if slot % 2 else "Documentary"]}
            if index == 0:
                programme["thumbnail"] = art(base, title, "landscape")
                programme["cast"] = ["Ada Turtle", "Tom Shell"]
                programme["directors"] = ["Lee Reef"]
                programme["ratings"] = [{"value": "TV-G", "system": "TVPG"}]
            programmes.append(programme)
        at, slot = until, slot + 1
    return programmes


def guide_streams(media, channel_id):
    name = next(c[1] for c in GUIDE_CHANNELS if c[0] == channel_id)
    return {"streams": [{"name": f"{name}\nLive", "description": "Live", "url": f"{media}/h264-sdr.live.m3u8"}]}


def match_full_meta(base):
    """The final in full: a plain 20-s movie."""
    return meta(base, MATCH_FULL_ID, "Tortuga Cup Final · Full match",
                "The test final in full: 20 seconds from a server without Range support.")


def replays_meta(base):
    """The match's replays: a series of dated episodes."""
    found = series_meta(base, REPLAYS_ID, "Tortuga Cup Replays", "Past test matches in full: 20 seconds each.",
                        len(REPLAY_DATES))
    for video, date in zip(found["meta"]["videos"], REPLAY_DATES):
        video["title"] = f"Matchday {video['episode']} · {date}"
        video["released"] = f"{date}T19:00:00.000Z"
    return found


def stale_link_stream(base, link):
    """STALE_LINK_FILE at its [link]th link (/fresh/), which plays while it is the latest handed out; each answer the
    same release, by its filename and size."""
    return {
        "name": "Tortuga Test\nFresh link",
        "description": f"{STALE_LINK_FILE}\nA new link in every answer; an older one plays another video",
        "url": f"{base}/fresh/{link}/{STALE_LINK_FILE}",
        "behaviorHints": {"filename": STALE_LINK_FILE, "videoSize": SIZES[STALE_LINK_FILE]},
    }


def four_three(media):
    """h264-4x3.mkv: a 4:3 picture with an English SRT line throughout, for the player's fit modes and zoom."""
    return {
        "name": "Tortuga Test\n4:3",
        "description": "h264-4x3.mkv\nH.264 SDR 1440x1080 · SRT en",
        "url": f"{media}/h264-4x3.mkv",
        "behaviorHints": {"filename": "h264-4x3.mkv"},
    }


def portrait(media):
    """h264-portrait.mkv: a picture taller than wide, a phone's upright video."""
    return {
        "name": "Tortuga Test\nPortrait",
        "description": "h264-portrait.mkv\nH.264 SDR 720x1280",
        "url": f"{media}/h264-portrait.mkv",
        "behaviorHints": {"filename": "h264-portrait.mkv"},
    }


def scripts(media):
    """scripts.mkv: one forced SRT cue throughout in nine scripts, for the subtitle fonts."""
    return {
        "name": "Tortuga Test\nScripts",
        "description": "scripts.mkv\nH.264 SDR 1080p · SRT ja (forced), nine scripts",
        "url": f"{media}/scripts.mkv",
        "behaviorHints": {"filename": "scripts.mkv"},
    }


def hdr_streams(base, media):
    """h264-sdr.mkv, hevc-hdr10.mkv and dv-p81.mkv, in that order, each with this addon's English SRT (a line every 5 s)
    as the stream's own subtitles."""
    subtitles = [{"id": f"{HDR_ID}:eng", "lang": "eng", "url": f"{base}/subs/{HDR_ID}/eng.srt"}]
    files = [
        ("1080p", "h264-sdr.mkv", "H.264 SDR"),
        ("2160p HDR10", "hevc-hdr10.mkv", "HEVC HDR10"),
        ("2160p DV", "dv-p81.mkv", "HEVC Dolby Vision 8.1"),
    ]
    return {"streams": [{
        "name": f"Tortuga Test\n{quality}",
        "description": f"{file}\n{what} · SRT en",
        "url": f"{media}/{file}",
        "behaviorHints": {"filename": file},
        "subtitles": subtitles,
    } for quality, file, what in files]}


def subtitles_stream(base, media):
    """h264-sdr.mkv with its own English and French SRTs."""
    quoted = urllib.parse.quote(SUBS_ID, safe="")
    return {
        "name": "Tortuga Test\n1080p",
        "description": "h264-sdr.mkv\nH.264 SDR · SRT en, fr",
        "url": f"{media}/h264-sdr.mkv",
        "behaviorHints": {"filename": "h264-sdr.mkv"},
        "subtitles": [{"id": f"{SUBS_ID}:own:{code}", "lang": code, "url": f"{base}/subs/{quoted}/{code}.srt"} for code in ("eng", "fre")],
    }


def chapters_stream(media):
    """chapters.mkv in a binge group: named chapters (Recap, Opening, Episode, Ending, Preview), the next episode's
    stream in the same group playing on without asking."""
    return {
        "name": "Tortuga Test\n1080p",
        "description": "chapters.mkv\nH.264 SDR · Recap, Opening, Episode, Ending, Preview",
        "url": f"{media}/chapters.mkv",
        "behaviorHints": {"filename": "chapters.mkv", "bingeGroup": "tortuga-test-chapters"},
    }


def postcredits_stream(media):
    """chapters-postcredits.mkv: named chapters whose end credits are followed by a post-credits scene."""
    return {
        "name": "Tortuga Test\n1080p",
        "description": "chapters-postcredits.mkv\nH.264 SDR · End Credits, then a Post-credits Scene",
        "url": f"{media}/chapters-postcredits.mkv",
        "behaviorHints": {"filename": "chapters-postcredits.mkv"},
    }


def numbered_chapters_stream(media):
    """chapters-numbered.mkv: chapters titled by their numbers alone (Chapter 1 to Chapter 4)."""
    return {
        "name": "Tortuga Test\n1080p",
        "description": "chapters-numbered.mkv\nH.264 SDR · Chapter 1 to Chapter 4",
        "url": f"{media}/chapters-numbered.mkv",
        "behaviorHints": {"filename": "chapters-numbered.mkv"},
    }


def tracks_stream(media, file):
    """[file] in the tracks series' binge group: the next episode plays on without asking."""
    return {
        "name": "Tortuga Test\nTracks",
        "description": f"{file}\nseveral audio and subtitle tracks",
        "url": f"{media}/{file}",
        "behaviorHints": {"filename": file, "bingeGroup": "tortuga-test-tracks"},
    }


def served(media, route, file, query=None):
    """[file] on the media server behind serve.py's [route] ("resolve", "redirect/3"; "" for the plain file), with
    [query]: --media names the matrix folder, and serve.py's routes go before it."""
    split = urllib.parse.urlsplit(media)
    url = f"{split.scheme}://{split.netloc}{'/' + route if route else ''}{split.path}/{file}"
    return f"{url}?{urllib.parse.urlencode(query)}" if query else url


def art(base, seed, shape):
    """The artwork URL of the title or episode `seed` (its id) in `shape` (ART_SIZES, or "logo"): art_png's image."""
    return f"{base}/art/{shape}/{urllib.parse.quote(seed, safe='')}.png"


def episode_tag(season, episode):
    return f"S{season:02d}E{episode:02d}"


def download_meta(base, movie_id):
    return {
        "meta": {
            "id": movie_id,
            "type": "movie",
            "name": DOWNLOAD_MOVIES[movie_id],
            "poster": art(base, movie_id, "poster"),
            "background": art(base, movie_id, "landscape"),
            "logo": art(base, movie_id, "logo"),
            "description": "A download test: its stream goes through the media server's download routes.",
            "releaseInfo": "2026",
            "runtime": "1 min",
        }
    }


def download_streams(media, rate, expire, cut, hold, lazy_hold):
    """Each download movie's one stream, on tools/media/serve.py's routes: throttled (and held by serve.py's hold
    [hold], if any), a lazy link whose CDN link expires after [expire] seconds (held by [lazy_hold], if any), one whose
    CDN link drops after [cut] bytes, and a chain of redirects."""
    remux = SIZES["remux.mkv"]
    return {
        "tortuga-dl-slow": {
            "name": "Tortuga Test\n2160p",
            "description": f"remux.mkv at {rate} KiB/s\nHEVC HDR10 · TrueHD 5.1 · 14.6 MB",
            "url": served(media, "", "remux.mkv", {"rate": rate, **({"hold": hold} if hold else {})}),
            "behaviorHints": {"filename": "Tortuga.Slow.Download.Test.2026.2160p.REMUX.mkv", "videoSize": remux},
        },
        "tortuga-dl-lazy": {
            "name": "Tortuga Test\n2160p",
            "description": f"remux.mkv through a lazy link\nIts CDN link answers 403 after {expire} s · 14.6 MB",
            "url": served(media, "resolve", "remux.mkv",
                          {"expire": expire, "rate": rate, **({"hold": lazy_hold} if lazy_hold else {})}),
            "behaviorHints": {"filename": "Tortuga.Lazy.Link.Test.2026.2160p.REMUX.mkv", "videoSize": remux},
        },
        "tortuga-dl-cut": {
            "name": "Tortuga Test\n2160p",
            "description": f"remux.mkv through a lazy link\nIts CDN link drops after {cut / (1 << 20):g} MiB · 14.6 MB",
            # A day's lifetime: the byte budget is this link's only expiry.
            "url": served(media, "resolve", "remux.mkv", {"bytes": cut, "expire": 86400, "rate": rate}),
            "behaviorHints": {"filename": "Tortuga.Expiring.Link.Test.2026.2160p.REMUX.mkv", "videoSize": remux},
        },
        "tortuga-dl-redirect": {
            "name": "Tortuga Test\n1080p",
            "description": "h264-sdr.mkv after three redirects\nRelative Location headers · 1.1 MB",
            "url": served(media, "redirect/3", "h264-sdr.mkv"),
            "behaviorHints": {"filename": "Tortuga.Redirect.Test.2026.1080p.WEB.H264.mkv", "videoSize": SIZES["h264-sdr.mkv"]},
        },
    }


def season_meta(base):
    return {
        "meta": {
            "id": SEASON_SERIES_ID,
            "type": "series",
            "name": SEASON_SERIES_NAME,
            "poster": art(base, SEASON_SERIES_ID, "poster"),
            "background": art(base, SEASON_SERIES_ID, "landscape"),
            "logo": art(base, SEASON_SERIES_ID, "logo"),
            "description": "Eight episodes over two seasons for season downloads and Keep synced, each with a 1080p "
                           "and a 2160p stream in one binge group per quality.",
            "releaseInfo": "2026",
            "videos": [
                {
                    "id": f"{SEASON_SERIES_ID}:{season}:{episode}",
                    "title": title,
                    "season": season,
                    "episode": episode,
                    "released": f"2026-{season:02d}-{episode:02d}T00:00:00.000Z",
                    "thumbnail": art(base, f"{SEASON_SERIES_ID}:{season}:{episode}", "landscape"),
                    "overview": f"{SEASON_SERIES_NAME} {episode_tag(season, episode)}: {title}.",
                }
                for season, titles in SEASON_EPISODES.items()
                for episode, title in enumerate(titles, 1)
            ],
        }
    }


def season_streams(media, season, episode):
    """The episode's h264-sdr.mkv (1080p) and remux.mkv (2160p), one binge group per quality, with the episode's file
    name; UNREADY_EPISODE's 1080p stream reads not ready, as an addon marks one its host doesn't hold yet."""
    release = f"Tortuga.Season.Test.{episode_tag(season, episode)}"
    ready = "⏳ Not ready" if (season, episode) == UNREADY_EPISODE else "⚡ Ready"
    query = {"episode": episode_tag(season, episode)}
    return {
        "streams": [
            {
                "name": "Tortuga Test\n1080p",
                "description": f"{release}.1080p.WEB.H264.mkv\n{ready} · 1.1 MB",
                "url": served(media, "", "h264-sdr.mkv", query),
                "behaviorHints": {"filename": f"{release}.1080p.WEB.H264.mkv", "videoSize": SIZES["h264-sdr.mkv"],
                                  "bingeGroup": f"{SEASON_SERIES_ID}-1080p"},
            },
            {
                "name": "Tortuga Test\n2160p",
                "description": f"{release}.2160p.REMUX.mkv\n⚡ Ready · 14.6 MB",
                "url": served(media, "", "remux.mkv", query),
                "behaviorHints": {"filename": f"{release}.2160p.REMUX.mkv", "videoSize": SIZES["remux.mkv"],
                                  "bingeGroup": f"{SEASON_SERIES_ID}-2160p"},
            },
        ]
    }


def slow_season_meta(base):
    return {
        "meta": {
            "id": SLOW_SERIES_ID,
            "type": "series",
            "name": SLOW_SERIES_NAME,
            "poster": art(base, SLOW_SERIES_ID, "poster"),
            "description": "Two episodes whose one stream each downloads slowly, to pause and resume.",
            "releaseInfo": "2026",
            "videos": [
                {
                    "id": f"{SLOW_SERIES_ID}:1:{episode}",
                    "title": title,
                    "season": 1,
                    "episode": episode,
                    "released": f"2026-01-{episode:02d}T00:00:00.000Z",
                    "thumbnail": art(base, f"{SLOW_SERIES_ID}:1:{episode}", "landscape"),
                }
                for episode, title in enumerate(SLOW_EPISODES, 1)
            ],
        }
    }


def slow_season_streams(media, rate, episode):
    """The episode's one stream: remux.mkv at [rate] KiB/s, with the episode's file name and size."""
    release = f"Tortuga.Slow.Season.Test.{episode_tag(1, episode)}"
    return {
        "streams": [
            {
                "name": "Tortuga Test\n2160p",
                "description": f"{release}.2160p.REMUX.mkv\nremux.mkv at {rate} KiB/s · 14.6 MB",
                "url": served(media, "", "remux.mkv", {"rate": rate, "episode": episode_tag(1, episode)}),
                "behaviorHints": {"filename": f"{release}.2160p.REMUX.mkv", "videoSize": SIZES["remux.mkv"]},
            },
        ]
    }


def downloads_catalog(base, kind):
    items = [download_meta(base, movie_id)["meta"] for movie_id in DOWNLOAD_MOVIES] if kind == "movie" else [season_meta(base)["meta"]]
    return {"metas": [{key: item[key] for key in ("id", "type", "name", "poster", "releaseInfo")} for item in items]}


def video_title(video_id):
    """What a download item's video (or the HDR movie, the subtitles movie, or a tracks episode) is called in its
    subtitles, or None for any other video."""
    if video_id == HDR_ID:
        return HDR_NAME
    if video_id == SUBS_ID:
        return SUBS_NAME
    if video_id in DOWNLOAD_MOVIES:
        return DOWNLOAD_MOVIES[video_id]
    match = re.fullmatch(rf"({SEASON_SERIES_ID}|{TRACKS_SERIES_ID}):(\d+):(\d+)", video_id)
    if match and match.group(1) == TRACKS_SERIES_ID and int(match.group(2)) == 1 and 1 <= int(match.group(3)) <= len(TRACKS_FILES):
        return f"Tortuga Tracks Test {episode_tag(1, int(match.group(3)))}"
    if match and match.group(1) == SEASON_SERIES_ID and 1 <= int(match.group(3)) <= len(SEASON_EPISODES.get(int(match.group(2)), [])):
        return f"{SEASON_SERIES_NAME} {episode_tag(int(match.group(2)), int(match.group(3)))}"
    return None


def subtitles(base, video_id):
    """The subtitles resource's answer for [video_id], whatever its extras: an SRT per language (eng, fre, spa); a
    tracks episode's English one alone, TRACKS_SUBTITLE_DELAY late; the subtitles movie's many (SUBS_ID)."""
    if video_title(video_id) is None:
        return {"subtitles": []}
    quoted = urllib.parse.quote(video_id, safe="")
    if video_id == SUBS_ID:
        english = [{"id": f"{video_id}:eng-{n}", "lang": "eng", "url": f"{base}/subs/{quoted}/eng-{n}.srt"} for n in range(1, SUBS_ENGLISH + 1)]
        others = [{"id": f"{video_id}:{code}", "lang": code, "url": f"{base}/subs/{quoted}/{code}.srt"} for code in ("spa", "deu", "ita", "jpn")]
        text = [{"id": f"{video_id}:por", "lang": SUBTITLE_LANGUAGES["por"], "url": f"{base}/subs/{quoted}/por.srt"}]
        return {"subtitles": english + others + text}
    tracks = video_id.startswith(f"{TRACKS_SERIES_ID}:")
    answer = {"subtitles": [{"id": f"{video_id}:{code}", "lang": code, "url": f"{base}/subs/{quoted}/{code}.srt"}
                            for code in (["eng"] if tracks else ("eng", "fre", "spa"))]}
    return late(TRACKS_SUBTITLE_DELAY, lambda: answer) if tracks else answer


def srt(title, language):
    """A line every 5 s for a minute, naming the video and the language."""
    return "\n".join(f"{n + 1}\n00:00:{n * 5:02d},000 --> 00:00:{n * 5 + 4:02d},500\n{title}\n{language}, line {n + 1}\n"
                     for n in range(12))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8766)
    ap.add_argument("--media", default="http://127.0.0.1:8765/matrix")
    ap.add_argument("--all-movies", action="store_true", help="streams for every Cinemeta movie (tt ids)")
    ap.add_argument("--catalog", action="store_true", help="a catalog listing the movie")
    ap.add_argument("--slow", type=float, default=25, help="seconds the slow movies' meta or streams take to answer")
    ap.add_argument("--tour", action="store_true", help="a 404 stream and one without a binge group last, and streams for Chernobyl's episodes")
    ap.add_argument("--downloads", action="store_true",
                    help="declare the subtitles resource, Tortuga Downloads catalogs and series streams for the download items")
    ap.add_argument("--download-rate", type=int, default=256, help="KiB/s of the slow, lazy and cut download links")
    ap.add_argument("--link-expire", type=int, default=60, help="seconds the lazy link's CDN links answer before 403")
    ap.add_argument("--link-bytes", type=int, default=8 << 20, help="bytes the cut link's CDN links serve before they drop")
    ap.add_argument("--download-hold", metavar="NAME",
                    help="the slow download link held by the media server's hold NAME (serve.py's ?hold=, /hold/)")
    ap.add_argument("--download-hold-lazy", metavar="NAME",
                    help="the lazy download link (its CDN links) held by the media server's hold NAME")
    ap.add_argument("--title", action="append", default=[], type=exit_title,
                    help="movie|series:<tt id>:<name>: a Cinemeta title whose streams (every episode's) are the movie's, listed in a catalog")
    ap.add_argument("--search", action="store_true", help="Tortuga Search catalogs answering a search for matrix")
    ap.add_argument("--pages", action="store_true", help="a Tortuga Pages catalog of three pages, alone and for a search for tortugapages")
    ap.add_argument("--live", action="store_true",
                    help="live TV catalogs and channels on the media server's live HLS, their replays and a movie from a server without Range support")
    ap.add_argument("--guide", action="store_true",
                    help="a TV guide catalog (epgProvider) of four channels with a day's programmes around any date, on the media server's live HLS")
    ap.add_argument("--relay-media", help="the media server's URL as this addon reaches it, for /headers/, /fresh/ and /expired/ (default: --media)")
    ap.add_argument("--tls", nargs=2, metavar=("CERT", "KEY"), help="serve HTTPS with this certificate and key (PEM files)")
    ap.add_argument("--errvid-unsized", action="store_true",
                    help="the error video series' slates without a size (videoSize): only their length tells them")
    ap.add_argument("--similar", action="store_true", help="Tortuga Similar catalogs and the titles Detail's More like this is shown for")
    ap.add_argument("--collections", action="store_true",
                    help="a Nuvio collection pack (/collections.json) and the studio catalogs its folders show")
    a = ap.parse_args()
    media = a.media.rstrip("/")
    relay_media = (a.relay_media or a.media).rstrip("/")
    # Each route gets this addon's own address as the client reached it (the Host header).
    routes = {
        "/manifest.json": lambda base: manifest(a.all_movies, a.catalog, a.tour, a.downloads, a.title, a.search, a.pages, a.live,
                                                a.guide, a.similar, a.collections),
        f"{CONFIGURABLE_PATH}/manifest.json": configurable_manifest,
        f"/meta/movie/{MOVIE_ID}.json": meta,
        f"/stream/movie/{MOVIE_ID}.json": lambda base: streams(media, a.tour),
        f"/stream/movie/{CINEMETA_MOVIE_ID}.json": lambda base: streams(media, a.tour),
        f"/meta/movie/{SLOW_META_ID}.json": lambda base: late(a.slow, lambda: meta(base, SLOW_META_ID, "Tortuga Slow Meta Test")),
        f"/meta/movie/{SLOW_STREAMS_ID}.json": lambda base: meta(base, SLOW_STREAMS_ID, "Tortuga Slow Streams Test"),
        f"/stream/movie/{SLOW_STREAMS_ID}.json": lambda base: late(a.slow, lambda: streams(media, a.tour)),
        f"/meta/movie/{SLOW_PLAY_ID}.json": lambda base: meta(
            base, SLOW_PLAY_ID, "Tortuga Slow Playback Test", f"h264-sdr.mkv at {SLOW_PLAY_RATE} KiB/s: the player stays loading."),
        f"/stream/movie/{SLOW_PLAY_ID}.json": lambda base: {"streams": [{
            "name": "Tortuga Test\n1080p",
            "description": f"h264-sdr.mkv at {SLOW_PLAY_RATE} KiB/s",
            "url": served(media, "", "h264-sdr.mkv", {"rate": SLOW_PLAY_RATE}),
        }]},
        f"/meta/movie/{FRAMES_ID}.json": lambda base: meta(
            base, FRAMES_ID, "Tortuga Frames Test", "Two still frames: a bright one and a dark one."),
        f"/stream/movie/{FRAMES_ID}.json": lambda base: {"streams": [
            {"name": f"Tortuga Test\n{name}", "description": f"{name}.png, a still frame", "url": f"{base}/frame/{name}.png"}
            for name in FRAMES
        ]},
        f"/meta/series/{FRAMES_SERIES_ID}.json": lambda base: series_meta(
            base, FRAMES_SERIES_ID, "Tortuga Frames Series Test", "One episode on the dark still frame.", 1),
        f"/stream/series/{FRAMES_SERIES_ID}:1:1.json": lambda base: {"streams": [
            {"name": "Tortuga Test\ndark", "description": "dark.png, a still frame", "url": f"{base}/frame/dark.png"}
        ]},
        f"/meta/series/{UNNUMBERED_ID}.json": unnumbered_meta,
        f"/meta/movie/{MISSING_ID}.json": lambda base: meta(
            base, MISSING_ID, "Tortuga Missing File Test", "missing.mkv: the media server answers 404."),
        f"/stream/movie/{MISSING_ID}.json": lambda base: {"streams": [{
            "name": "Tortuga Test\nMissing",
            "description": "missing.mkv\nThe media server answers 404",
            "url": f"{media}/missing.mkv",
        }]},
        f"/meta/movie/{EXPIRED_ID}.json": lambda base: meta(
            base, EXPIRED_ID, "Tortuga Expired Link Test", "A link that expired between the resolve and the player's open."),
        f"/stream/movie/{EXPIRED_ID}.json": lambda base: {"streams": [{
            "name": "Tortuga Test\nExpired",
            "description": "h264-sdr.mkv\nThe link answers the player 403",
            "url": f"{base}/expired/{FAKE_API_KEY}/h264-sdr.mkv",
        }]},
        f"/meta/series/{SERIES_ID}.json": series_meta,
        f"/meta/series/{BIDI_SERIES_ID}.json": bidi_meta,
        f"/meta/series/{HERO_WIDE_ID}.json": lambda base: hero_meta(base, "series", HERO_WIDE_ID, "Tortuga Hero Wide Test", "wide"),
        f"/meta/movie/{HERO_TALL_ID}.json": lambda base: hero_meta(base, "movie", HERO_TALL_ID, "Tortuga Hero Tall Test", "tall"),
        f"/meta/movie/{HERO_PLAIN_ID}.json": lambda base: hero_meta(base, "movie", HERO_PLAIN_ID, "Tortuga Hero Plain Test"),
        f"/meta/movie/{HERO_LONG_ID}.json": lambda base: hero_meta(base, "movie", HERO_LONG_ID, HERO_LONG_NAME),
        f"/meta/series/{SEASONS_ID}.json": seasons_meta,
        f"/meta/series/{SPOILERS_ID}.json": spoilers_meta,
        f"/meta/series/{META_STREAMS_ID}.json": lambda base: meta_streams_meta(base, media),
        f"/meta/series/{ERRVID_SERIES_ID}.json": errvid_meta,
        f"/meta/series/{CUT_SERIES_ID}.json": cut_meta,
        f"/meta/movie/{FORMATTED_ID}.json": lambda base: meta(base, FORMATTED_ID, "Tortuga Formatted Test"),
        f"/stream/movie/{FORMATTED_ID}.json": lambda base: formatted(media),
        f"/meta/movie/{SORTED_ID}.json": lambda base: meta(base, SORTED_ID, "Tortuga Sorted Test"),
        f"/stream/movie/{SORTED_ID}.json": lambda base: sorted_streams(media),
        f"/meta/movie/{REAL_STREAMS_ID}.json": lambda base: meta(base, REAL_STREAMS_ID, "Tortuga Real Streams Test"),
        f"/stream/movie/{REAL_STREAMS_ID}.json": lambda base: real_streams(media),
        f"/meta/movie/{REAL_LONG_ID}.json": lambda base: meta(base, REAL_LONG_ID, "Tortuga Real Long Test"),
        f"/stream/movie/{REAL_LONG_ID}.json": lambda base: {"streams": recorded(media, "styled-texts", "movie-ex0040", sized=False)},
        f"/meta/series/{REAL_ANIME_ID}.json": lambda base: series_meta(
            base, REAL_ANIME_ID, "Tortuga Real Anime Test", "One episode whose streams are an anime episode's styled-text list.", 1),
        f"/stream/series/{REAL_ANIME_ID}:1:1.json": lambda base: {"streams": recorded(media, "styled-texts", "series-ex0010-1-1", sized=False)},
        f"/meta/movie/{COMMON_STREAMS_ID}.json": lambda base: meta(base, COMMON_STREAMS_ID, "Tortuga Common Streams Test"),
        f"/stream/movie/{COMMON_STREAMS_ID}.json": lambda base: common_streams(media),
        f"/meta/movie/{FOUR_THREE_ID}.json": lambda base: meta(base, FOUR_THREE_ID, "Tortuga 4:3 Test"),
        f"/stream/movie/{FOUR_THREE_ID}.json": lambda base: {"streams": [four_three(media)]},
        f"/meta/movie/{PORTRAIT_ID}.json": lambda base: meta(
            base, PORTRAIT_ID, "Tortuga Portrait Test", "A 20-second movie taller than wide, as a phone films upright."),
        f"/stream/movie/{PORTRAIT_ID}.json": lambda base: {"streams": [portrait(media)]},
        f"/meta/series/{PORTRAIT_SERIES_ID}.json": lambda base: series_meta(
            base, PORTRAIT_SERIES_ID, "Tortuga Portrait Series Test",
            "Two episodes: the first 20 seconds taller than wide, the second's binge-group file missing.", 2),
        f"/stream/series/{PORTRAIT_SERIES_ID}:1:1.json": lambda base: {"streams": [
            portrait(media) | {"behaviorHints": {"filename": "h264-portrait.mkv", "bingeGroup": PORTRAIT_SERIES_GROUP}}
        ]},
        # The missing file in the binge group, which up next plays, then a playable one: the error offers the next.
        f"/stream/series/{PORTRAIT_SERIES_ID}:1:2.json": lambda base: {"streams": [{
            "name": "Tortuga Test\nMissing",
            "description": "missing.mkv\nThe media server answers 404",
            "url": f"{media}/missing.mkv",
            "behaviorHints": {"bingeGroup": PORTRAIT_SERIES_GROUP},
        }, portrait(media)]},
        f"/meta/movie/{SCRIPTS_ID}.json": lambda base: meta(
            base, SCRIPTS_ID, "Tortuga Scripts Test", "A 20-second movie whose subtitle line is in nine scripts."),
        f"/stream/movie/{SCRIPTS_ID}.json": lambda base: {"streams": [scripts(media)]},
        f"/meta/movie/{HDR_ID}.json": lambda base: meta(
            base, HDR_ID, HDR_NAME, "A 20-second movie in H.264 SDR, HEVC HDR10 and Dolby Vision 8.1, with English subtitles."),
        f"/stream/movie/{HDR_ID}.json": lambda base: hdr_streams(base, media),
        f"/meta/series/{RANGES_SERIES_ID}.json": lambda base: series_meta(
            base, RANGES_SERIES_ID, "Tortuga Ranges Test", "HDR10 at 23.976 fps (20 s), SDR at 24 fps (10 s), HDR10 again.",
            len(RANGES_FILES)),
        f"/meta/series/{CHAPTERS_SERIES_ID}.json": lambda base: series_meta(
            base, CHAPTERS_SERIES_ID, "Tortuga Chapters Test",
            "Two 60-second episodes with named chapters: Recap, Opening, Episode, Ending, Preview.", CHAPTERS_EPISODES),
        f"/meta/movie/{CHAPTERS_MOVIE_ID}.json": lambda base: meta(
            base, CHAPTERS_MOVIE_ID, "Tortuga Post-credits Test",
            "A 60-second movie with named chapters whose end credits are followed by a post-credits scene."),
        f"/stream/movie/{CHAPTERS_MOVIE_ID}.json": lambda base: {"streams": [postcredits_stream(media)]},
        f"/meta/movie/{CHAPTERS_NUMBERED_ID}.json": lambda base: meta(
            base, CHAPTERS_NUMBERED_ID, "Tortuga Numbered Chapters Test",
            "A 7-minute title whose chapters are numbered only: Chapter 1 to Chapter 4."),
        f"/stream/movie/{CHAPTERS_NUMBERED_ID}.json": lambda base: {"streams": [numbered_chapters_stream(media)]},
        f"/meta/series/{TRACKS_SERIES_ID}.json": lambda base: series_meta(
            base, TRACKS_SERIES_ID, "Tortuga Tracks Test",
            "Two 30-second episodes with several audio and subtitle tracks, in another order in each.", len(TRACKS_FILES)),
        f"/meta/movie/{MATRIX_ID}.json": lambda base: meta(
            base, MATRIX_ID, "Tortuga Matrix Test",
            "The playback info's formats: an HEVC HDR10 remux with TrueHD 5.1, Dolby Vision 8.1, DTS-HD MA 7.1."),
        f"/stream/movie/{MATRIX_ID}.json": lambda base: matrix_streams(media),
        f"/meta/movie/{SUBS_ID}.json": lambda base: meta(
            base, SUBS_ID, SUBS_NAME, "A 20-second movie with subtitles in seven languages: the player's More subtitles."),
        f"/stream/movie/{SUBS_ID}.json": lambda base: {"streams": [subtitles_stream(base, media)]},
    }
    for kind in ("movie", "series"):
        routes[f"/catalog/{kind}/{EXIT_CATALOG_ID}.json"] = lambda base, kind=kind: exit_catalog(base, a.title, kind)
    if a.catalog:
        routes[f"/catalog/movie/{CATALOG_ID}.json"] = catalog
    if a.search:
        for kind in ("movie", "series"):
            routes[f"/catalog/{kind}/{SEARCH_CATALOG_ID}/search=matrix.json"] = lambda base, kind=kind: search_catalog(base, kind)
    if a.similar:
        for kind, title_id in (("movie", RELATED_ID), ("series", SERIES_ID), ("movie", RELATED_EMPTY_ID), ("series", RELATED_NONE_ID)):
            routes[f"/catalog/{kind}/{SIMILAR_CATALOG_ID}/similar={title_id}.json"] = (
                lambda base, title_id=title_id: similar_catalog(base, title_id))
        routes[f"/catalog/series/{SIMILAR_CATALOG_ID}/similar={RELATED_LOADING_ID}.json"] = (
            lambda base: late(RELATED_HOLD, lambda: similar_catalog(base, RELATED_LOADING_ID)))
        routes[f"/meta/movie/{RELATED_ID}.json"] = lambda base: related_meta(base, RELATED_ID, "Tortuga Related Test", RELATED_IMDB_ID)
        routes[f"/meta/movie/{RELATED_EMPTY_ID}.json"] = lambda base: related_meta(base, RELATED_EMPTY_ID, "Tortuga Related Empty Test")
        routes[f"/meta/series/{RELATED_NONE_ID}.json"] = lambda base: related_series_meta(base, RELATED_NONE_ID, "Tortuga Related None Test")
        routes[f"/meta/series/{RELATED_LOADING_ID}.json"] = (
            lambda base: related_series_meta(base, RELATED_LOADING_ID, "Tortuga Related Loading Test"))
    if a.pages:
        for skip in range(0, PAGE_SIZE * (PAGES + 1), PAGE_SIZE):
            for search in ("", f"search={PAGES_QUERY}"):
                extra = "&".join(e for e in (f"skip={skip}" if skip else "", search) if e)
                path = f"/catalog/movie/{PAGES_CATALOG_ID}" + (f"/{extra}" if extra else "") + ".json"
                routes[path] = lambda base, skip=skip: pages_catalog(base, skip)
        for n in range(1, PAGE_SIZE * PAGES + 1):
            routes[f"/meta/movie/tortuga-page-{n}.json"] = lambda base, n=n: meta(
                base, f"tortuga-page-{n}", f"Tortuga Page {n}", f"Title {n} of the Tortuga Pages catalog.")
    if a.collections:
        routes[COLLECTIONS_PATH] = collections_pack
        for key, (_, kinds) in STUDIOS.items():
            for kind in kinds:
                for skip in range(0, STUDIO_TITLES + PAGE_SIZE, PAGE_SIZE):
                    path = f"/catalog/{kind}/tortuga-studio-{key}" + (f"/skip={skip}" if skip else "") + ".json"
                    routes[path] = lambda base, key=key, kind=kind, skip=skip: studio_catalog(base, key, kind, skip)
                for title_id, name in studio_titles(key, kind):
                    description = f"A title of the {studio_catalog_name(key, kind)} catalog."
                    routes[f"/meta/{kind}/{title_id}.json"] = (
                        lambda base, i=title_id, n=name, d=description: meta(base, i, n, d)) if kind == "movie" else (
                        lambda base, i=title_id, n=name, d=description: series_meta(base, i, n, d, 1))
    if a.tour:
        for episode in range(1, TOUR_EPISODES + 1):
            routes[f"/stream/series/{TOUR_SERIES_ID}:1:{episode}.json"] = lambda base: streams(media, True)
    for episode in range(1, UNNUMBERED_EPISODES + 1):
        routes[f"/stream/series/{UNNUMBERED_ID}:{episode}.json"] = lambda base: {"streams": []}
    for episode in range(1, SERIES_EPISODES + 1):
        routes[f"/stream/series/{SERIES_ID}:1:{episode}.json"] = lambda base: {"streams": [unbound(media), bound(media)]}
    for episode in range(1, len(BIDI_EPISODES) + 1):
        routes[f"/stream/series/{BIDI_SERIES_ID}:1:{episode}.json"] = lambda base: {"streams": [unbound(media)]}
    for episode in range(1, ERRVID_EPISODES + 1):
        routes[f"/stream/series/{ERRVID_IMDB_ID}:1:{episode}.json"] = (
            lambda base, episode=episode: {"streams": [errvid_stream(media, episode, not a.errvid_unsized)]})
    for episode in range(1, CUT_EPISODES + 1):
        routes[f"/stream/series/{CUT_SERIES_ID}:1:{episode}.json"] = lambda base, episode=episode: {"streams": [cut_stream(media, episode)]}
    for episode in range(1, META_STREAMS_EPISODES + 1):
        routes[f"/stream/series/{META_STREAMS_ID}:1:{episode}.json"] = lambda base: {"streams": [unbound(media), *UNSAFE_LINKS]}
    for episode in range(1, CHAPTERS_EPISODES + 1):
        routes[f"/stream/series/{CHAPTERS_SERIES_ID}:1:{episode}.json"] = lambda base: {"streams": [chapters_stream(media)]}
    for episode, file in enumerate(TRACKS_FILES, 1):
        routes[f"/stream/series/{TRACKS_SERIES_ID}:1:{episode}.json"] = lambda base, file=file: {"streams": [tracks_stream(media, file)]}
    for episode, (file, label) in enumerate(RANGES_FILES, 1):
        routes[f"/stream/series/{RANGES_SERIES_ID}:1:{episode}.json"] = lambda base, file=file, label=label: {"streams": [{
            "name": f"Tortuga Test\n{label}",
            "description": file,
            "url": f"{media}/{file}",
            "behaviorHints": {"filename": file, "bingeGroup": "tortuga-test-ranges"},
        }]}
    downloads = download_streams(media, a.download_rate, a.link_expire, a.link_bytes, a.download_hold, a.download_hold_lazy)
    for movie_id in DOWNLOAD_MOVIES:
        routes[f"/meta/movie/{movie_id}.json"] = lambda base, movie_id=movie_id: download_meta(base, movie_id)
        routes[f"/stream/movie/{movie_id}.json"] = lambda base, movie_id=movie_id: {"streams": [downloads[movie_id], *UNSAFE_LINKS]}
    routes[f"/meta/series/{SEASON_SERIES_ID}.json"] = season_meta
    for season, titles in SEASON_EPISODES.items():
        for episode in range(1, len(titles) + 1):
            routes[f"/stream/series/{SEASON_SERIES_ID}:{season}:{episode}.json"] = (
                lambda base, season=season, episode=episode: season_streams(media, season, episode))
    routes[f"/meta/series/{SLOW_SERIES_ID}.json"] = slow_season_meta
    for episode in range(1, len(SLOW_EPISODES) + 1):
        routes[f"/stream/series/{SLOW_SERIES_ID}:1:{episode}.json"] = (
            lambda base, episode=episode: slow_season_streams(media, a.download_rate, episode))
    if a.downloads:
        for kind in ("movie", "series"):
            routes[f"/catalog/{kind}/{DOWNLOADS_CATALOG_ID}.json"] = lambda base, kind=kind: downloads_catalog(base, kind)
    routes[f"/meta/movie/{HEADERS_ID}.json"] = headers_meta
    routes[f"/stream/movie/{HEADERS_ID}.json"] = lambda base: {"streams": [headers_stream(base)]}
    routes[f"/meta/movie/{QUOTED_ID}.json"] = lambda base: headers_meta(base, QUOTED_ID, QUOTED_TITLE)
    routes[f"/stream/movie/{QUOTED_ID}.json"] = lambda base: {"streams": [headers_stream(base, QUOTED_HEADERS)]}
    routes[f"/meta/movie/{HLS_ID}.json"] = lambda base: meta(
        base, HLS_ID, "Tortuga HLS Test", "A 20-second movie as HLS at a URL without an extension: proxyHeaders.response.")
    routes[f"/stream/movie/{HLS_ID}.json"] = lambda base: {"streams": [hls_stream(base)]}
    routes[f"/meta/movie/{HLS_FAIL_ID}.json"] = lambda base: meta(
        base, HLS_FAIL_ID, "Tortuga HLS Failing Segment Test",
        "The HLS movie whose one segment answers 502 the first time: the player falls back to the playlist's entries.")
    fail_tokens = itertools.count(1)
    routes[f"/stream/movie/{HLS_FAIL_ID}.json"] = lambda base: {"streams": [hls_stream(base, next(fail_tokens))]}
    if a.guide:
        routes[f"/catalog/tv/{GUIDE_CATALOG_ID}.json"] = lambda base: {
            "metas": [guide_channel(base, i, n) for i, n, _ in GUIDE_CHANNELS], "cacheMaxAge": 300}
        for channel_id, channel_name, _ in GUIDE_CHANNELS:
            routes[f"/meta/tv/{channel_id}.json"] = lambda base, i=channel_id, n=channel_name: {
                "meta": guide_channel(base, i, n, datetime.datetime.now(datetime.timezone.utc).date())}
            routes[f"/stream/tv/{channel_id}.json"] = lambda base, i=channel_id: guide_streams(media, i)
    if a.live:
        live_answers = itertools.count(1)
        for catalog_id in (LIVE_NOW_CATALOG_ID, LIVE_247_CATALOG_ID):
            routes[f"/catalog/tv/{catalog_id}.json"] = lambda base, catalog_id=catalog_id: live_catalog(base, catalog_id)
        for genre in LIVE_GENRES:
            routes[f"/catalog/tv/{LIVE_NOW_CATALOG_ID}/genre={genre}.json"] = (
                lambda base, genre=genre: live_catalog(base, LIVE_NOW_CATALOG_ID, genre))
        for item_id in LIVE_ITEMS:
            routes[f"/meta/tv/{item_id}.json"] = lambda base, item_id=item_id: {"meta": live_meta(base, item_id)}
            routes[f"/stream/tv/{item_id}.json"] = (
                lambda base, item_id=item_id: live_streams(media, item_id, next(live_answers)))
        routes[f"/catalog/series/{REPLAYS_ID}.json"] = lambda base: {"metas": [
            {key: value for key, value in replays_meta(base)["meta"].items() if key != "videos"}]}
        routes[f"/meta/series/{REPLAYS_ID}.json"] = replays_meta
        for episode in range(1, len(REPLAY_DATES) + 1):
            routes[f"/stream/series/{REPLAYS_ID}:1:{episode}.json"] = lambda base: {"streams": [unbound(media)]}
        routes[f"/catalog/movie/{FULL_MATCHES_CATALOG_ID}.json"] = lambda base: {"metas": [
            {key: value for key, value in match_full_meta(base)["meta"].items() if key in ("id", "type", "name", "poster", "releaseInfo")}]}
        routes[f"/meta/movie/{MATCH_FULL_ID}.json"] = match_full_meta
        routes[f"/stream/movie/{MATCH_FULL_ID}.json"] = lambda base: {"streams": [{
            "name": "Tortuga Test\nNo Range", "description": "h264-sdr.mkv from a server without Range support",
            "url": served(media, "norange", "h264-sdr.mkv"), "behaviorHints": {"filename": "h264-sdr.mkv"}}]}
    for key in SOURCES:
        routes[f"/sources/{key}/manifest.json"] = lambda base, key=key: source_manifest(key)
    # Each /sources/ addon's answers so far.
    source_answers = {key: itertools.count(1) for key in SOURCES}
    routes[f"{LATE_PATH}/manifest.json"] = lambda base: late_manifest()
    routes[f"{LATE_PATH}/catalog/movie/{LATE_CATALOG_ID}.json"] = catalog
    routes[f"{LATE_PATH}/catalog/movie/{LATE_CATALOG_ID}/genre={LATE_GENRE}.json"] = catalog
    routes[f"{FAILING_PATH}/manifest.json"] = lambda base: failing_manifest()
    routes[f"{FAILING_PATH}/catalog/movie/{FAILING_MORE_CATALOG_ID}.json"] = lambda base: pages_catalog(base, 0)
    routes[f"{MALFORMED_PATH}/manifest.json"] = lambda base: malformed_manifest()
    routes[f"{MALFORMED_PATH}/catalog/movie/{MALFORMED_CATALOG_ID}.json"] = lambda base: {
        "metas": [{"type": "movie", "name": f"Tortuga Malformed {n}"} for n in range(1, 4)], "cacheMaxAge": 3600}
    routes[f"{ACTIVITY_PATH}/manifest.json"] = lambda base: activity_manifest()
    routes[f"{ACTIVITY_PATH}/catalog/movie/{ACTIVITY_CATALOG_ID}.json"] = catalog
    routes[f"{UPDATING_PATH}/catalog/movie/{UPDATING_BEFORE_ID}.json"] = catalog
    routes[f"{UPDATING_PATH}/catalog/movie/{UPDATING_ADDED_ID}.json"] = catalog
    routes[f"{EPISODES_PATH}/manifest.json"] = lambda base: episodes_manifest()
    routes[f"/meta/series/{CALENDAR_SERIES_ID}.json"] = lambda base: calendar_meta(base, episodes_out)
    # When /episodes/out made the calendar series' episode 4 come out (epoch seconds; None: not yet).
    episodes_out = None
    routes[f"/meta/movie/{STALE_LINK_ID}.json"] = lambda base: meta(
        base, STALE_LINK_ID, "Tortuga Stale Link Test",
        "A 20-second movie whose stream gets a new link in every answer; an older link plays another video.")
    # STALE_LINK_ID's latest link (0: none handed out yet).
    stale_link_latest = 0
    stale_link_lock = threading.Lock()
    # The failing segment's tokens already failed once, and every /headers/ and /hls/ request's headers (/proxied.json).
    failed = set()
    failed_lock = threading.Lock()
    proxied = []
    # The watch-activity addon's events so far, each its path after ACTIVITY_PATH.
    activity = []
    activity_lock = threading.Lock()
    # The updating addon's manifest requests since the start or its last /updating/reset.
    updating_asked = 0
    updating_lock = threading.Lock()

    frames = {name: frame_png(top, bottom) for name, (top, bottom) in FRAMES.items()}
    logos = {name: logo_png(size, art, HERO_LOGO_COLOUR) for name, (size, art) in HERO_LOGOS.items()}
    # Set while the fake sign-in server answers at once (/signin/release), clear while it holds (/signin/hold); the same
    # for the late catalog (/late/release, /late/hold).
    signin_released = threading.Event()
    late_released = threading.Event()

    class Handler(http.server.BaseHTTPRequestHandler):
        def do_GET(self):
            nonlocal stale_link_latest, episodes_out, updating_asked
            raw = urllib.parse.urlsplit(self.path).path
            path = urllib.parse.unquote(raw)
            # The configurable addon under a configuration of its own: the same addon and page.
            path = re.sub(r"^/configurable/[^/]+/(manifest\.json|configure)$", r"/configurable/\1", path)
            base = f"{'https' if a.tls else 'http'}://{self.headers['Host']}"
            if path in ("/signin/hold", "/signin/release"):
                (signin_released.clear if path == "/signin/hold" else signin_released.set)()
                self.log_message("sign-in options %s", path.removeprefix("/signin/"))
                self.reply(200, "text/plain", b"ok")
                return
            if path == "/signin/v2/info":
                if not signin_released.wait(SIGNIN_HOLD_LIMIT):
                    self.log_message("sign-in options held past %d s: answering", SIGNIN_HOLD_LIMIT)
                self.reply(200, "application/json", json.dumps(SIGNIN_INFO).encode())
                return
            if path in (f"{LATE_PATH}/hold", f"{LATE_PATH}/release"):
                (late_released.clear if path == f"{LATE_PATH}/hold" else late_released.set)()
                self.log_message("late catalog %s", path.removeprefix(f"{LATE_PATH}/"))
                self.reply(200, "text/plain", b"ok")
                return
            if path.startswith(f"{LATE_PATH}/catalog/") and not late_released.wait(LATE_HOLD_LIMIT):
                self.log_message("late catalog held past %d s: answering", LATE_HOLD_LIMIT)
            if path.startswith(f"{FAILING_PATH}/catalog/") and path not in routes:
                self.reply(500, "application/json", json.dumps({"err": "this catalog fails on purpose"}).encode())
                return
            if path.startswith((f"{ACTIVITY_PATH}/player/", f"{ACTIVITY_PATH}/library/")):
                event = path.removeprefix(ACTIVITY_PATH)
                with activity_lock:
                    activity.append(event)
                self.log_message("activity %s", event)
                self.reply(200, "application/json", b"{}")
                return
            if path == f"{ACTIVITY_PATH}/received.json":
                with activity_lock:
                    received = list(activity)
                self.reply(200, "application/json", json.dumps({"requests": received}).encode())
                return
            if path == f"{ACTIVITY_PATH}/reset":
                with activity_lock:
                    activity.clear()
                self.log_message("activity reset")
                self.reply(200, "text/plain", b"ok")
                return
            found = re.fullmatch(r"/art/(poster|landscape|square|logo|still)/([^/]+)\.png", path)
            if found:
                self.reply(200, "image/png", art_png(found.group(2), found.group(1)))
                return
            found = re.fullmatch(r"/art/gif/([^/]+)\.gif", path)
            if found:
                self.reply(200, "image/gif", art_gif(found.group(1)))
                return
            found = re.fullmatch(r"/frame/([a-z]+)\.png", path)
            if found and found.group(1) in frames:
                self.reply(200, "image/png", frames[found.group(1)])
                return
            found = re.fullmatch(r"/hero/([a-z]+)\.png", path)
            if found and found.group(1) in logos:
                if found.group(1) == "wide":
                    time.sleep(HERO_WIDE_DELAY)
                self.reply(200, "image/png", logos[found.group(1)])
                return
            found = re.fullmatch(r"/headers/([A-Za-z0-9._-]+)", path)
            fail = urllib.parse.parse_qs(urllib.parse.urlsplit(self.path).query).get("fail", [None])[0]
            if found:
                self.proxy_media(found.group(1), fail)
                return
            found = re.fullmatch(r"/hls/(master|media)", path)
            if found:
                self.log_headers(f"hls/{found.group(1)}")
                self.reply(200, "application/octet-stream", hls_playlist(base, found.group(1), fail).encode())
                return
            if path == f"/stream/movie/{STALE_LINK_ID}.json":
                with stale_link_lock:
                    stale_link_latest += 1
                    link = stale_link_latest
                self.log_message("fresh link %d handed out", link)
                self.reply(200, "application/json", json.dumps({"streams": [stale_link_stream(base, link)]}).encode())
                return
            if path == f"{UPDATING_PATH}/manifest.json":
                with updating_lock:
                    updating_asked += 1
                    asked = updating_asked
                self.log_message("updating manifest request %d: %s", asked, "before" if asked == 1 else "updated")
                self.reply(200, "application/json", json.dumps(updating_manifest(updated=asked > 1)).encode())
                return
            if path == f"{UPDATING_PATH}/reset":
                with updating_lock:
                    updating_asked = 0
                self.log_message("updating manifest reset")
                self.reply(200, "text/plain", b"ok")
                return
            if path == f"{UPDATING_PATH}/requests.json":
                with updating_lock:
                    asked = updating_asked
                self.reply(200, "application/json", json.dumps({"requests": asked}).encode())
                return
            if path == f"{EPISODES_PATH}/out":
                later = urllib.parse.parse_qs(urllib.parse.urlsplit(self.path).query).get("in", ["0"])[0]
                episodes_out = time.time() + float(later)
                self.log_message("episode 4 of %s out at %s", CALENDAR_SERIES_ID,
                                 time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(episodes_out)))
                self.reply(200, "text/plain", b"ok")
                return
            found = re.fullmatch(rf"{EPISODES_PATH}/catalog/series/([a-z-]+)/([A-Za-z]+)=([^/]+)\.json", path)
            if found and EPISODES_CATALOGS.get(found.group(1)) == found.group(2):
                asked = found.group(3).split(",")
                metas = [calendar_meta(base, episodes_out)["meta"]] if CALENDAR_SERIES_ID in asked else []
                self.log_message("%s for %s", found.group(1), ",".join(asked))
                self.reply(200, "application/json", json.dumps({"metasDetailed": metas}).encode())
                return
            found = re.fullmatch(r"/fresh/(\d+)/([A-Za-z0-9._-]+)", path)
            if found:
                link, name, latest = int(found.group(1)), found.group(2), stale_link_latest
                if link == latest:
                    self.log_message("fresh link %d: %s", link, name)
                    self.relay(name)
                else:
                    self.log_message("stale link %d (the latest is %d): %s for %s", link, latest, STALE_LINK_ERROR, name)
                    self.relay(STALE_LINK_ERROR)
                return
            found = re.fullmatch(rf"/expired/{FAKE_API_KEY}/([A-Za-z0-9._-]+)", path)
            if found:
                # The resolver asks for closed ranges (its first 64 KiB, the hash's chunks), a player for open-ended ones.
                asked = self.headers.get("Range") or ""
                if re.fullmatch(r"bytes=\d+-\d+", asked):
                    self.log_message("expired link: %s for the resolver's %s", found.group(1), asked)
                    self.relay(found.group(1))
                else:
                    self.log_message("expired link: 403 for %s", asked or "no range")
                    self.reply(403, "text/plain", b"link expired\n")
                return
            if path == "/proxied.json":
                self.reply(200, "application/json", json.dumps({"requests": list(proxied)}).encode())
                return
            found = re.fullmatch(r"/sources/([a-z]+)/stream/(?:movie|series)/tortuga-[^/]+\.json", path)
            if found and found.group(1) in SOURCES:
                key = found.group(1)
                _, late_by, fails = SOURCES[key]
                time.sleep(late_by)
                if fails:
                    self.reply(500, "application/json", json.dumps({"err": "this source fails on purpose"}).encode())
                else:
                    self.reply(200, "application/json", json.dumps(source_streams(media, key, next(source_answers[key]))).encode())
                return
            if re.fullmatch(rf"{MALFORMED_PATH}/stream/(?:movie|series)/tortuga-[^/]+\.json", path):
                self.reply(200, "application/json", json.dumps(MALFORMED_STREAMS).encode())
                return
            if path == "/handoff":
                self.log_message("handoff %s", urllib.parse.urlsplit(self.path).query)
                self.reply(200, "text/html; charset=utf-8", HANDOFF_PAGE)
                return
            if path == f"{CONFIGURABLE_PATH}/configure":
                self.reply(200, "text/html; charset=utf-8", CONFIGURE_PAGE)
                return
            # The subtitles resource's extra (videoHash, videoSize, filename) may hold encoded slashes: matched raw.
            found = re.fullmatch(r"/subtitles/(?:movie|series)/([^/]+?)(?:/[^/]*)?\.json", raw)
            if found:
                self.reply(200, "application/json", json.dumps(subtitles(base, urllib.parse.unquote(found.group(1)))).encode())
                return
            found = re.fullmatch(r"/subs/([^/]+)/([a-z]{3})(?:-(\d+))?\.srt", raw)
            title = found and video_title(urllib.parse.unquote(found.group(1)))
            if title and found.group(2) in SUBTITLE_LANGUAGES:
                language = SUBTITLE_LANGUAGES[found.group(2)] + (f" {found.group(3)}" if found.group(3) else "")
                self.reply(200, "application/x-subrip; charset=utf-8", srt(title, language).encode())
                return
            route = routes.get(path)
            if route is None and a.all_movies and re.fullmatch(r"/stream/movie/tt\d+\.json", path):
                route = routes[f"/stream/movie/{CINEMETA_MOVIE_ID}.json"]
            if route is None and a.all_movies and a.downloads and re.fullmatch(r"/stream/series/tt\d+(?::\d+:\d+)?\.json", path):
                route = lambda base: {"streams": []}
            # Tortuga Search answers every page of "matrix" with its first, as Cinemeta's search ignores a skip, and
            # SEARCH_PAGE_HOLD late, so the group shows it loading.
            found = re.fullmatch(rf"/catalog/(movie|series)/{SEARCH_CATALOG_ID}/skip=\d+&search=matrix\.json", path)
            if route is None and a.search and found:
                route = lambda base, kind=found.group(1): late(SEARCH_PAGE_HOLD, lambda: search_catalog(base, kind))
            # Tortuga Pages finds nothing for any other search, nor Tortuga Search for any but "matrix": Search leaves
            # them out rather than showing them failed.
            if route is None and a.pages and path.startswith(f"/catalog/movie/{PAGES_CATALOG_ID}/"):
                route = lambda base: {"metas": []}
            if route is None and a.search and re.fullmatch(rf"/catalog/(?:movie|series)/{SEARCH_CATALOG_ID}/.*", path):
                route = lambda base: {"metas": []}
            found = re.fullmatch(rf"/catalog/tv/{GUIDE_CATALOG_ID}/date=(\d{{4}}-\d{{2}}-\d{{2}})\.json", path)
            if route is None and a.guide and found:
                day = datetime.date.fromisoformat(found.group(1))
                route = lambda base: {"metasDetailed": [guide_channel(base, i, n, day) for i, n, _ in GUIDE_CHANNELS],
                                      "cacheMaxAge": 300}
            found = re.fullmatch(r"/stream/(?:movie|series)/(tt\d+)(?::\d+:\d+)?\.json", path)
            if route is None and found and found.group(1) in {t["id"] for t in a.title}:
                route = lambda base: streams(media)
            self.reply(200 if route else 404, "application/json", json.dumps(route(base) if route else {"err": "not found"}).encode())

        def log_headers(self, name, status=None):
            """The request's headers for [name], kept for /proxied.json (the proxyHeaders proofs) and logged, with the
            [status] the addon answered when it failed the request on purpose."""
            sent = {"file": name, TEST_HEADER[0]: self.headers.get(TEST_HEADER[0], "missing"),
                    "User-Agent": self.headers.get("User-Agent"), "Range": self.headers.get("Range"),
                    "headers": list(self.headers.keys())}
            if status is not None:
                sent["status"] = status
            proxied.append(sent)
            self.log_message("proxied %s: %s %s, User-Agent %s, Range %s%s", name, TEST_HEADER[0], sent[TEST_HEADER[0]],
                             sent["User-Agent"], sent["Range"], "" if status is None else f", answered {status}")

        def proxy_media(self, name, fail=None):
            """--media's [name] with the Range asked for, the request's headers logged first (the proxyHeaders proofs:
            a player's own requests ask for an open-ended Range, the app's resolver for the first 64 KiB). The first
            request carrying a [fail] token answers 502 (HLS_FAIL_ID's segment)."""
            if fail is not None:
                with failed_lock:
                    first = fail not in failed
                    failed.add(fail)
                if first:
                    self.log_headers(name, 502)
                    self.reply(502, "text/plain", b"the failing segment's first request\n")
                    return
            self.log_headers(name)
            self.relay(name)

        def relay(self, name):
            """--relay-media's [name], with the Range asked for."""
            asked = {"Range": self.headers["Range"]} if self.headers.get("Range") else {}
            try:
                upstream = urllib.request.urlopen(urllib.request.Request(f"{relay_media}/{name}", headers=asked), timeout=30)
            except urllib.error.HTTPError as e:
                upstream = e
            except urllib.error.URLError as e:
                self.reply(502, "text/plain", f"media server: {e.reason}".encode())
                return
            with upstream:
                try:
                    self.send_response(upstream.status)
                    for header in ("Content-Type", "Content-Length", "Content-Range", "Accept-Ranges", "ETag", "Last-Modified"):
                        if upstream.headers.get(header):
                            self.send_header(header, upstream.headers[header])
                    self.end_headers()
                    shutil.copyfileobj(upstream, self.wfile, 64 * 1024)
                except (BrokenPipeError, ConnectionResetError):
                    # A player seeking drops the connection it was reading.
                    pass

        def reply(self, status, content_type, body):
            # A late answer's client may have given up (the app left the screen that asked).
            try:
                self.send_response(status)
                self.send_header("Content-Type", content_type)
                self.send_header("Access-Control-Allow-Origin", "*")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)
            except (BrokenPipeError, ConnectionResetError):
                self.log_message("%s: the client went away", self.path)

    server = http.server.ThreadingHTTPServer(("0.0.0.0", a.port), Handler)
    if a.tls:
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.load_cert_chain(*a.tls)
        # The handshake on the request's thread, not in accept(): a client stalled in it holds up no other.
        server.socket = context.wrap_socket(server.socket, server_side=True, do_handshake_on_connect=False)
    print(f"test addon on :{a.port}{' (HTTPS)' if a.tls else ''}, media {a.media}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
