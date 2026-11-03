<script lang="ts">
	import { resolve } from "$app/paths";
	import Glyph from "$lib/Glyph.svelte";
	import Mark from "$lib/Mark.svelte";
	import Meta from "$lib/Meta.svelte";
	import Shot from "$lib/Shot.svelte";
	import Turtle from "$lib/Turtle.svelte";

	const github = "https://github.com/edde746/tortuga";

	const platforms = [
		{ name: "iPhone", minimum: "iOS 17 or later" },
		{ name: "iPad", minimum: "iPadOS 17 or later" },
		{ name: "Mac", minimum: "macOS 14 or later, Apple silicon" },
		{ name: "Apple TV", minimum: "tvOS 17 or later" },
		{ name: "Android", minimum: "Phones and tablets, Android 8.0 or later" },
		{ name: "Android TV", minimum: "Google TV, Fire TV, NVIDIA SHIELD; Android 8.0 or later" },
		{ name: "Windows", minimum: "64-bit (x64)" },
		{ name: "Linux", minimum: "Wayland desktops" },
	];
</script>

<Meta
	title="Tortuga: a video player that works with Stremio addons"
	description="Tortuga is a video player that works with Stremio addons, native on iPhone, iPad, Mac, Apple TV, Android, Android TV, Windows and Linux. Coming soon."
	path="/"
/>

<section class="hero char" aria-labelledby="hero-title">
	<div class="wrap hero-grid">
		<div class="hero-copy">
			<p class="tag">Coming soon</p>
			<h1 id="hero-title">A video player that works with Stremio addons.</h1>
			<p class="lede">
				Native on iPhone, iPad, Mac, Apple TV, Android, Android TV, Windows and Linux. Your addons bring the catalogs
				and the streams. Tortuga plays them.
			</p>
			<div class="actions">
				<a class="button primary" href="#platforms">See the platforms</a>
				<a class="button" href={github}>Source on GitHub</a>
			</div>
		</div>
		<div class="hero-art" aria-hidden="true">
			<Turtle swim />
		</div>
	</div>
	<div class="wrap hero-shots">
		<Shot
			class="shot tv"
			name="tv-home"
			eager
			sizes="(min-width: 1300px) 990px, 84vw"
			alt="Tortuga's Home on Apple TV: Continue Watching with Dune: Part Two focused, popular movies below."
		/>
		<Shot
			class="shot phone"
			name="iphone-home"
			eager
			sizes="(min-width: 1300px) 290px, (min-width: 720px) 24vw, 34vw"
			alt="Tortuga's Home on iPhone: Continue Watching, then popular movies and series."
		/>
	</div>
</section>

<section id="how" class="how ink" aria-labelledby="how-title">
	<div class="wrap">
		<p class="eyebrow">How it works</p>
		<h2 id="how-title">Your addons supply the streams. Tortuga plays them.</h2>
		<ol class="steps">
			<li>
				<span class="step-mark" aria-hidden="true">1</span>
				<h3>Add your addons</h3>
				<p>
					Install any Stremio addon by its link. Catalogs, search results, detail pages and streams all come from
					your addons.
				</p>
			</li>
			<li>
				<span class="step-mark" aria-hidden="true">2</span>
				<h3>Browse and search</h3>
				<p>
					Home shows Continue Watching and your addons’ catalogs. Discover filters by type and genre. Search covers
					every addon.
				</p>
			</li>
			<li>
				<span class="step-mark" aria-hidden="true">3</span>
				<h3>Pick a stream</h3>
				<p>
					The streams for a movie or an episode come filtered and grouped by addon. Pick one, and Tortuga plays it
					with mpv.
				</p>
			</li>
		</ol>
		<p class="note">
			<Mark class="note-mark" />
			<span>Tortuga provides no content of its own. Your requests go straight to the addons you install.</span>
		</p>
	</div>
</section>

<section id="player" class="player char swell" aria-labelledby="player-title">
	<div class="wrap">
		<p class="eyebrow">The player</p>
		<h2 id="player-title">mpv inside, on every platform.</h2>
		<p class="lede intro">Every version of Tortuga plays video with mpv (libmpv).</p>
		<div class="player-grid">
			<Shot
				class="shot ipad"
				name="ipad-player"
				sizes="(min-width: 1300px) 680px, (min-width: 960px) 54vw, 90vw"
				alt="Tortuga's player on iPad playing Weathering with You, paused at 3:15 with the controls up: contrails across a deep blue summer sky over tall grass."
			/>
			<ul class="ticks">
				<li>HDR10 and Dolby Vision where the device supports them.<sup class="fn"><a id="ref-hdr" href="#note-hdr" aria-label="Note 1">1</a></sup></li>
				<li>Audio passthrough on every platform.<sup class="fn"><a id="ref-pass" href="#note-pass" aria-label="Note 2">2</a></sup></li>
				<li>Embedded and addon subtitles, matched by OpenSubtitles hash.</li>
				<li>Two subtitles at once: a second one at the top of the picture, for learning a language.</li>
				<li>Your audio and subtitle choices carry to the next episode.</li>
				<li>Chapters on the scrub bar. Skip intros, recaps and credits with a button or automatically.</li>
				<li>Up next, previous and next episode.</li>
				<li>Playback speed, subtitle and audio delay.</li>
				<li>Picture in picture.<sup class="fn"><a id="ref-pip" href="#note-pip" aria-label="Note 3">3</a></sup></li>
			</ul>
		</div>
		<div class="elsewhere">
			<h3>Rather use another player?</h3>
			<p>
				Send the stream to VLC, mpv, Infuse, Kodi, IINA, any other player on the device or one you set up.<sup class="fn"
					><a id="ref-players" href="#note-players" aria-label="Note 4">4</a></sup
				> Progress is still recorded where that app reports it.
			</p>
		</div>
	</div>
</section>

<section class="library ink swell" aria-labelledby="library-title">
	<div class="wrap library-grid">
		<div class="library-art">
			<Shot
				class="shot phone"
				name="iphone-detail"
				sizes="(min-width: 960px) 300px, 62vw"
				alt="A show's page on iPhone: Frieren: Beyond Journey's End, Resume season 1, episode 14 at 10:30, the season's episodes with the ones before it watched."
			/>
		</div>
		<div>
			<p class="eyebrow">Your library</p>
			<h2 id="library-title">It keeps your place.</h2>
			<ul class="points">
				<li>
					<h3>Progress and watched marks</h3>
					<p>The library keeps both, and Continue Watching on Home takes you back in.</p>
				</li>
				<li>
					<h3>The stream you picked</h3>
					<p>Tortuga remembers it and plays the next episode from the same source.</p>
				</li>
				<li>
					<h3>Ratings</h3>
					<p>Detail pages show ratings from the trackers you connect.</p>
				</li>
				<li>
					<h3>Collections</h3>
					<p>Group catalogs and tracker lists into folders, shown as a row of tiles on Home.</p>
				</li>
			</ul>
		</div>
	</div>
</section>

<section class="more ink" aria-labelledby="more-title">
	<div class="wrap">
		<p class="eyebrow">Also in the app</p>
		<h2 id="more-title">Take it offline, watch together, keep it in sync.</h2>
		<ul class="cards">
			<li>
				<Glyph name="download" />
				<h3>Downloads</h3>
				<p>
					Save movies and episodes, with their subtitles, to watch offline. Tortuga can keep a series topped up with
					the next unwatched episodes.<sup class="fn"><a id="ref-dl" href="#note-dl" aria-label="Note 5">5</a></sup>
				</p>
			</li>
			<li>
				<Glyph name="together" />
				<h3>Watch together</h3>
				<p>
					Open a room and share its 6-character code or an invite link.<sup class="fn"
						><a id="ref-invite" href="#note-invite" aria-label="Note 6">6</a></sup
					> Playback stays in sync, and each person plays the same title through their own addons, also with friends on
					other Open Watch Party apps.
				</p>
			</li>
			<li>
				<Glyph name="sync" />
				<h3>Sync</h3>
				<p>
					Sign in with Apple, Google or GitHub. Your profiles, addons, library, progress and profile settings sync
					across every device. Link a TV with a QR code or a short code.
				</p>
			</li>
			<li>
				<Glyph name="profile" />
				<h3>Profiles</h3>
				<p>
					A profile for each person in the household, with its own name and avatar. Each keeps its own addons in
					order, its library and its settings. A PIN is optional. Kids profiles have a suggested-age limit from
					agerating.
				</p>
			</li>
			<li>
				<Glyph name="tracker" />
				<h3>Trackers</h3>
				<p>
					Trakt, Simkl and MDBList: live scrobbling, watched history, ratings and watchlists, and your lists on Trakt
					and MDBList. MyAnimeList and AniList: series progress, ratings, planning lists.
				</p>
			</li>
			<li>
				<Glyph name="server" />
				<h3>Your own server</h3>
				<p>
					Sync runs on Tortuga’s hosted server at api.usetortuga.app unless you choose another. You can also run
					your own: Kotlin and Ktor with Postgres, in Docker.
				</p>
			</li>
		</ul>
		<div class="languages">
			<h3>21 languages</h3>
			<p>
				English, Spanish, Brazilian Portuguese, Arabic, French, German, Turkish, Russian, Indonesian, Hindi, Italian,
				Polish, Japanese, Korean, Vietnamese, Thai, Ukrainian, Dutch, Romanian, Czech and Swedish.
			</p>
		</div>
	</div>
</section>

<section id="screens" class="screens char swell" aria-labelledby="screens-title">
	<div class="wrap">
		<p class="eyebrow">Native on each</p>
		<h2 id="screens-title">Made for each platform, one core underneath.</h2>
		<p class="lede intro">
			SwiftUI on Apple devices. Compose on Android, Android TV, Windows and Linux. One shared core under all of them.
		</p>
		<div class="screens-grid">
			<div class="screens-shots">
				<figure class="captioned">
					<Shot
						class="shot tv"
						name="tv-detail"
						sizes="(min-width: 1300px) 700px, (min-width: 960px) 56vw, 90vw"
						alt="A show's page on Apple TV: Chernobyl's season 1, episode 4 focused with 41 min left and its summary above, the three episodes before it watched."
					/>
					<figcaption>Apple TV</figcaption>
				</figure>
				<figure class="captioned">
					<Shot
						class="shot tv"
						name="androidtv-home"
						sizes="(min-width: 1300px) 700px, (min-width: 960px) 56vw, 90vw"
						alt="Tortuga's Home on Android TV: Continue Watching with Dune: Part Two focused, popular movies below."
					/>
					<figcaption>Android TV</figcaption>
				</figure>
			</div>
			<div class="screens-copy">
				<div>
					<h3>On the TV</h3>
					<p>
						Android TV and Apple TV get full D-pad and remote navigation. On Apple TV, Tortuga also has a Top
						Shelf.
					</p>
				</div>
				<div>
					<h3>On the desktop</h3>
					<p>
						Keyboard shortcuts, media keys and the system’s media controls: SMTC on Windows, MPRIS on Linux, Now
						Playing on the Mac.
					</p>
				</div>
			</div>
		</div>
	</div>
</section>

<section id="platforms" class="platforms ink swell" aria-labelledby="platforms-title">
	<div class="wrap">
		<p class="eyebrow">Platforms</p>
		<h2 id="platforms-title">Coming soon to all of these.</h2>
		<p class="lede intro">Nothing is released yet.</p>
		<ul class="platform-grid">
			{#each platforms as platform (platform.name)}
				<li>
					<h3>{platform.name}</h3>
					<p>{platform.minimum}</p>
					<span class="tag">Coming soon</span>
				</li>
			{/each}
		</ul>
	</div>
</section>

<section class="open navy swell" aria-label="Open source and privacy">
	<div class="wrap open-grid">
		<div class="panel">
			<p class="eyebrow">Open source</p>
			<h2>Open source, under the GPL-3.0.</h2>
			<p>
				The source is on GitHub. Tortuga is made by edde746, who also makes Plezy, a Plex client.
			</p>
			<div class="actions">
				<a class="button primary" href={github}>Source on GitHub</a>
				<a class="button" href="https://www.gnu.org/licenses/gpl-3.0.html">The licence</a>
			</div>
		</div>
		<div class="panel">
			<p class="eyebrow">Privacy</p>
			<h2>No ads. No analytics.</h2>
			<p>
				Your data is not sold. Crash reports carry no account and no addon links, and one switch turns them off. This website sets no cookies and tracks nothing.
			</p>
			<div class="actions">
				<a class="button primary" href={resolve("/privacy/")}>Read the privacy policy</a>
			</div>
		</div>
	</div>
</section>

<section class="notes ink" aria-labelledby="notes-title">
	<div class="wrap">
		<h2 id="notes-title">Notes</h2>
		<ol>
			<li id="note-hdr">Not on Linux. <a href="#ref-hdr" aria-label="Back to note 1">↩</a></li>
			<li id="note-pass">
				Android and Android TV pass through what the device and the TV or receiver report taking (Dolby Digital, Dolby
				Digital Plus, TrueHD, DTS, DTS-HD); Windows and Linux all five. On Apple devices the system’s decoder takes
				Dolby Digital and Dolby Digital Plus, and on iOS and tvOS 26 also TrueHD and DTS-HD for spatial audio.
				<a href="#ref-pass" aria-label="Back to note 2">↩</a>
			</li>
			<li id="note-pip">
				iPhone, iPad, the Mac, and Android phones and tablets. <a href="#ref-pip" aria-label="Back to note 3">↩</a>
			</li>
			<li id="note-players">
				mpv: not on iPhone, iPad or Apple TV. Infuse: Apple devices. Kodi: Android and Android TV. IINA: the Mac.
				<a href="#ref-players" aria-label="Back to note 4">↩</a>
			</li>
			<li id="note-dl">Not on Apple TV. <a href="#ref-dl" aria-label="Back to note 5">↩</a></li>
			<li id="note-invite">
				An invite link opens Tortuga on Apple devices and Android; on Windows and Linux, paste it into the app.
				<a href="#ref-invite" aria-label="Back to note 6">↩</a>
			</li>
		</ol>
	</div>
</section>

<style>
	.fn {
		margin-left: 0.15em;
		font-size: 0.7em;
		line-height: 0;
	}
	.fn a {
		color: var(--muted);
		text-decoration: none;
	}
	.fn a:hover {
		color: var(--teal);
	}
	.notes {
		padding-block: clamp(40px, 6vw, 72px);
		color: var(--muted);
		font-size: 0.875rem;
	}
	.notes h2 {
		font-size: 1rem;
		color: inherit;
	}
	.notes ol {
		max-width: 72ch;
		margin: 0.75rem 0 0;
		padding-left: 1.25rem;
	}
	.notes li + li {
		margin-top: 0.4rem;
	}
	.notes a {
		color: inherit;
		text-decoration: none;
	}
	section {
		--overlap: clamp(90px, 15vw, 240px);
		--space: clamp(72px, 10vw, 136px);
		padding-block: var(--space);
	}
	h2 {
		max-width: 20ch;
	}
	.intro {
		max-width: 52ch;
		margin-top: 1.25rem;
	}
	.actions {
		display: flex;
		flex-wrap: wrap;
		gap: 0.75rem;
		margin-top: 2rem;
	}
	.hero :global(.shot img),
	section :global(.shot img) {
		width: 100%;
		background: var(--ink);
	}

	/* Hero */
	.hero {
		position: relative;
		/* Runs up under the 76 px header. */
		margin-top: -76px;
		padding-block: 76px 0;
		background:
			radial-gradient(55% 60% at 80% 28%, rgb(35 196 176 / 0.12), transparent 70%),
			var(--char);
	}
	.hero::before {
		content: "";
		position: absolute;
		inset: 0;
		background: var(--scutes);
		-webkit-mask: radial-gradient(75% 85% at 78% 30%, #000 10%, transparent 78%);
		mask: radial-gradient(75% 85% at 78% 30%, #000 10%, transparent 78%);
		pointer-events: none;
	}
	.hero-grid {
		position: relative;
		display: grid;
		gap: 1.5rem;
		align-items: center;
		padding-block: clamp(8px, 3vw, 40px) clamp(48px, 7vw, 96px);
	}
	.hero h1 {
		margin-top: 1.25rem;
		max-width: 13ch;
	}
	.hero .lede {
		max-width: 44ch;
		margin-top: 1.5rem;
	}
	.hero-art {
		order: -1;
		width: clamp(150px, 40vw, 220px);
	}
	@media (min-width: 880px) {
		.hero-grid {
			grid-template-columns: minmax(0, 1.3fr) minmax(0, 0.7fr);
		}
		.hero-art {
			order: 0;
			width: 100%;
			max-width: 440px;
			justify-self: end;
		}
	}
	.hero-shots {
		position: relative;
		z-index: 1;
		margin-bottom: calc(-1 * var(--overlap));
		padding-bottom: 6%;
	}
	.hero-shots :global(.tv) {
		width: 86%;
	}
	.hero-shots :global(.tv img) {
		border-radius: clamp(10px, 1.6vw, 22px);
		box-shadow: 0 30px 70px -20px rgb(0 0 0 / 0.6);
	}
	.hero-shots :global(.phone) {
		position: absolute;
		right: 0;
		bottom: 0;
		width: 34%;
	}
	.hero-shots :global(.phone img),
	.library-art :global(.phone img) {
		border-radius: 13% / 6%;
		box-shadow: 0 30px 60px -18px rgb(0 0 0 / 0.55);
	}
	@media (min-width: 720px) {
		.hero-shots :global(.phone) {
			width: 24%;
			right: 2%;
		}
	}

	/* How it works */
	/*
	 * The hero's captures hang into this band; its top stays the hero's char behind them, so their dark edges show,
	 * then fades to ink.
	 */
	.how {
		padding-top: calc(var(--overlap) + var(--space));
		background: linear-gradient(
			180deg,
			var(--char) calc(var(--overlap) + 16px),
			var(--ink) calc(var(--overlap) + 176px)
		);
	}
	.steps {
		display: grid;
		gap: clamp(2rem, 4vw, 3rem);
		margin-top: clamp(2.5rem, 5vw, 4rem);
		padding: 0;
		list-style: none;
	}
	.steps h3 {
		margin: 1.1rem 0 0.5rem;
	}
	.steps p {
		color: var(--muted);
		max-width: 36ch;
	}
	.step-mark {
		display: grid;
		place-items: center;
		width: 60px;
		height: 64px;
		padding-right: 14px;
		font: 700 1.6rem/1 var(--display);
		color: var(--navy);
		background: var(--teal);
		-webkit-mask: var(--tri) center / contain no-repeat;
		mask: var(--tri) center / contain no-repeat;
	}
	@media (min-width: 800px) {
		.steps {
			grid-template-columns: repeat(3, 1fr);
		}
	}
	.note {
		display: flex;
		align-items: center;
		gap: 1rem;
		margin-top: clamp(2.5rem, 5vw, 4rem);
		padding: 1.1rem 1.4rem;
		border-radius: var(--radius);
		background: var(--fill);
		font-weight: 600;
	}
	.note :global(.note-mark) {
		flex: none;
		width: 28px;
		height: auto;
		color: var(--teal);
	}

	/* Player */
	.player-grid {
		display: grid;
		gap: clamp(2rem, 4vw, 3.5rem);
		align-items: center;
		margin-top: clamp(2.5rem, 5vw, 4rem);
	}
	.player-grid :global(.ipad img) {
		border-radius: clamp(10px, 1.4vw, 18px);
		box-shadow: 0 30px 70px -24px rgb(0 0 0 / 0.7);
	}
	.captioned {
		margin: 0;
	}
	.captioned figcaption {
		margin-top: 0.75rem;
		color: var(--muted);
		font-size: 0.8125rem;
	}
	@media (min-width: 960px) {
		.player-grid {
			grid-template-columns: minmax(0, 1.4fr) minmax(0, 1fr);
		}
	}
	.ticks {
		display: grid;
		gap: 0.85rem;
		padding: 0;
		list-style: none;
	}
	.ticks li {
		position: relative;
		padding-left: 1.75rem;
	}
	.ticks li::before {
		content: "";
		position: absolute;
		left: 0;
		top: 0.42em;
		width: 0.85rem;
		height: 0.85rem;
		background: var(--accent);
		-webkit-mask: var(--tri) center / contain no-repeat;
		mask: var(--tri) center / contain no-repeat;
	}
	.elsewhere {
		display: grid;
		gap: 0.5rem 2.5rem;
		margin-top: clamp(2.5rem, 5vw, 4rem);
		padding: clamp(1.5rem, 3vw, 2.25rem);
		border-radius: var(--radius);
		background: var(--fill);
	}
	.elsewhere p {
		color: var(--muted);
		max-width: 60ch;
	}
	@media (min-width: 800px) {
		.elsewhere {
			grid-template-columns: minmax(0, 0.8fr) minmax(0, 1.6fr);
			align-items: center;
		}
	}

	/* Library */
	.library-grid {
		display: grid;
		gap: clamp(2.5rem, 6vw, 5rem);
		align-items: center;
	}
	.library-art {
		position: relative;
		justify-self: center;
		width: min(62vw, 300px);
		padding: 8% 0;
	}
	/* A quiet navy play button behind the capture, so the capture leads. */
	.library-art::before {
		content: "";
		position: absolute;
		inset: 0 -40% 0 -30%;
		background: var(--navy);
		-webkit-mask: var(--tri) center / contain no-repeat;
		mask: var(--tri) center / contain no-repeat;
	}
	.library-art :global(.phone) {
		position: relative;
		rotate: -4deg;
	}
	.points {
		display: grid;
		gap: 1.6rem;
		margin-top: 2.25rem;
		padding: 0;
		list-style: none;
	}
	.points h3 {
		margin-bottom: 0.35rem;
	}
	.points p {
		color: var(--muted);
		max-width: 44ch;
	}
	@media (min-width: 880px) {
		.library-grid {
			grid-template-columns: minmax(0, 0.9fr) minmax(0, 1.1fr);
		}
	}

	/* More */
	.more {
		padding-top: 0;
	}
	.cards {
		display: grid;
		gap: clamp(0.75rem, 1.6vw, 1.25rem);
		margin-top: clamp(2.5rem, 5vw, 3.5rem);
		padding: 0;
		list-style: none;
	}
	.cards li,
	.languages {
		padding: clamp(1.4rem, 2.6vw, 2rem);
		border-radius: var(--radius);
		background: var(--fill);
	}
	.cards :global(.glyph) {
		color: var(--accent);
	}
	.cards h3 {
		margin: 0.9rem 0 0.45rem;
	}
	.cards p,
	.languages p {
		color: var(--muted);
	}
	/* The icon's navy, so the list stands apart from the cards above it. */
	.languages {
		display: grid;
		gap: 0.5rem 2.5rem;
		margin-top: clamp(0.75rem, 1.6vw, 1.25rem);
		background: var(--navy);
	}
	@media (min-width: 640px) {
		.cards {
			grid-template-columns: repeat(2, 1fr);
		}
	}
	@media (min-width: 1000px) {
		.cards {
			grid-template-columns: repeat(3, 1fr);
		}
		.languages {
			grid-template-columns: minmax(0, 0.5fr) minmax(0, 2fr);
			align-items: baseline;
		}
	}

	/* Native on each */
	.screens-grid {
		display: grid;
		gap: clamp(2rem, 4vw, 3.5rem);
		align-items: center;
		margin-top: clamp(2.5rem, 5vw, 4rem);
	}
	.screens-grid :global(.tv img) {
		border-radius: clamp(10px, 1.4vw, 18px);
		box-shadow: 0 30px 70px -24px rgb(0 0 0 / 0.7);
	}
	.screens-shots {
		display: grid;
		gap: clamp(1.5rem, 3vw, 2.5rem);
	}
	.screens-copy {
		display: grid;
		gap: 1rem;
	}
	.screens-copy > div {
		padding: clamp(1.4rem, 2.6vw, 2rem);
		border-radius: var(--radius);
		background: var(--fill);
	}
	.screens-copy h3 {
		margin-bottom: 0.45rem;
	}
	.screens-copy p {
		color: var(--muted);
	}
	@media (min-width: 960px) {
		.screens-grid {
			grid-template-columns: minmax(0, 1.5fr) minmax(0, 1fr);
		}
	}

	/* Platforms */
	.platform-grid {
		display: grid;
		grid-template-columns: repeat(auto-fill, minmax(min(100%, 150px), 1fr));
		gap: clamp(0.75rem, 1.6vw, 1.25rem);
		margin-top: clamp(2.5rem, 5vw, 3.5rem);
		padding: 0;
		list-style: none;
	}
	@media (min-width: 900px) {
		.platform-grid {
			grid-template-columns: repeat(4, 1fr);
		}
	}
	.platform-grid li {
		position: relative;
		display: flex;
		flex-direction: column;
		gap: 0.4rem;
		min-height: 160px;
		padding: 1.5rem;
		border-radius: var(--radius);
		background: var(--fill);
		overflow: hidden;
	}
	.platform-grid li::after {
		content: "";
		position: absolute;
		right: -18px;
		bottom: -22px;
		width: 92px;
		height: 98px;
		background: var(--ink-fill-2);
		-webkit-mask: var(--tri) center / contain no-repeat;
		mask: var(--tri) center / contain no-repeat;
	}
	.platform-grid h3 {
		font-size: 1.625rem;
	}
	.platform-grid p {
		color: var(--muted);
		font-size: 0.9375rem;
		line-height: 1.45;
	}
	.platform-grid .tag {
		position: relative;
		z-index: 1;
		align-self: flex-start;
		margin-top: auto;
	}

	/* Open source and privacy */
	.open-grid {
		display: grid;
		gap: clamp(0.75rem, 1.6vw, 1.25rem);
	}
	/*
	 * A raised panel; its secondary button sits one step lighter, and lighter again on hover. The button row is pinned
	 * to the bottom, so side-by-side panels line their buttons up.
	 */
	.panel {
		--fill: var(--navy-raise-2);
		--fill-hover: var(--navy-raise-3);
		display: flex;
		flex-direction: column;
		padding: clamp(1.75rem, 4vw, 3rem);
		border-radius: calc(var(--radius) + 6px);
		background: var(--navy-raise);
	}
	.panel .actions {
		margin-top: auto;
		padding-top: 2rem;
	}
	.panel h2 {
		font-size: clamp(1.8rem, 1.4rem + 1.4vw, 2.6rem);
	}
	.panel p:not(.eyebrow) {
		margin-top: 1rem;
		color: var(--muted);
		max-width: 46ch;
	}
	@media (min-width: 880px) {
		.open-grid {
			grid-template-columns: 1fr 1fr;
		}
	}
</style>
