# Tortuga website

Source for [usetortuga.app](https://usetortuga.app): the landing page, the privacy policy, the support page, the page
a shared title's link opens and the store reviewers' demo addon. SvelteKit, exported as a static site that ships no
JavaScript but the title page's one inline script, sets no cookies and loads nothing from other hosts. The look comes
from the app icon (`tools/brand/`): near-black bands tinted with its navy, cream type and teal accents, and a turtle
whose shell is the icon's play button.

## Development

```bash
bun install
bun run dev
```

## Checks

```bash
bun run check
```

## Build

```bash
bun run build
```

The production output is written to `build/`, which is ignored by git. Every page is prerendered with relative
paths, so a build also works under a path prefix (preview hosts); `/404` is written as `build/404.html` with
root-absolute links, since it is served at any unknown path, and so is `/t/` (below).

## Title links

Detail's Share in the apps sends `https://usetortuga.app/t/<type>/<id>[?name=<name>]`, every value percent-encoded
(`TitleLink` in `shared/`). Where Tortuga is installed the system opens it in the app; otherwise this site serves
`build/t/index.html` at every `/t/…` path (`static/_redirects`, a rewrite). Its inline script reads the address,
shows the name if the link has one and points Open in Tortuga at `tortuga://detail?type=<type>&id=<id>`; it fetches
nothing. A link without a type and an ID shows that the title is missing.

The files that let the apps open the links themselves, in `static/.well-known/`:

- `apple-app-site-association`: Universal Links for `/t/*` to the App ID `G88U5B5783.com.edde746.tortuga` (iPhone,
  iPad and the Mac, whose entitlements name `applinks:usetortuga.app`), served as JSON (`static/_headers`).
- `assetlinks.json`: App Links for `/t/*`, per package and signing certificate: `com.edde746.tortuga.dev` with the
  debug certificate's SHA-256 fingerprint, and the release package `com.edde746.tortuga` with three: Google Play's
  app signing key (`86:FC:…:E8:ED`; Play Console → the app → Test and release → App integrity → App signing), the
  upload key (`90:3B:…:6E:72`, which also signs the GitHub release's APKs) and the Amazon Appstore's certificate
  (`33:F0:…:DB:5C`; Amazon Developer Console → the app → its APK files → Appstore Certificate Hashes, the same for
  every app of the account). A new signing key on any of them needs its fingerprint added here, or that build's App
  Links open the page.

## Assets

- Fonts: Fraunces (instanced at SOFT 100, WONK 0, opsz 72, weights 500–800) and Figtree, subset to Latin as WOFF2
  in `src/lib/fonts/` with their SIL OFL licences; the site serves the licences at `/fonts/LICENSE.txt`.
- Captures: `static/shots/`, AVIF and WebP at a few widths, made from the store captures (invented titles over
  blurred images: the Apple UI tests' MarketingShots) by the store screenshot pipeline's website export.
- `static/og.png` (1200×630) and the apple-touch-icon are drawn from `tools/brand/tortuga-icon-dark.svg`.
  `static/favicon.svg` is the mark alone, with no background: navy in a light browser theme, cream in a dark one.
- The demo addon: `static/demo/` (`https://usetortuga.app/demo/manifest.json`), a Stremio addon of static JSON that
  every store's review notes name: the manifest, the catalog `catalog/movie/tortuga-demo.json` and one
  `stream/movie/<IMDb id>.json` per film, each a direct HTTPS stream of a public-domain film on the Internet Archive
  or a Blender open movie (CC BY) on download.blender.org, with its size as `videoSize`. The site hosts no video.
  The manifest's `idPrefixes` are exactly the catalog's ids, so the app asks it for no other title's streams.
  `python3 store/check.py` checks the files agree, and `--urls` checks each stream's file (200, Accept-Ranges, size, a
  206 from its middle).

## Deploy

Cloudflare Workers Builds deploys every push to `main` to `https://usetortuga.app`, as a Worker named `tortuga`
that serves `build/` as static assets (`wrangler.jsonc`). The build settings live in the Cloudflare dashboard.
Workers Builds runs `bun install --frozen-lockfile` itself before the build command, and its image ships an older
Bun, so the Bun version is a build variable:

|Setting|Value|
|---|---|
|Git repository|`edde746/tortuga`, production branch `main`|
|Root directory|`website`|
|Build watch paths|include `website/*`, so app-only pushes don't rebuild the site|
|Build command|`bun run build`|
|Deploy command|`npx wrangler deploy`|
|Build variable|`BUN_VERSION` = `1.4.2`, the version CI uses|

The `usetortuga.app` zone must be in the same Cloudflare account: `wrangler.jsonc` attaches the apex as a Custom
Domain. `bun run build && bunx wrangler dev` serves the build the way Cloudflare does, `_redirects` and `_headers`
included.
