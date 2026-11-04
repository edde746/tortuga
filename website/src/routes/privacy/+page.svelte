<script lang="ts">
	import { resolve } from "$app/paths";
	import Meta from "$lib/Meta.svelte";
	import Turtle from "$lib/Turtle.svelte";
</script>

<Meta
	title="Privacy policy · Tortuga"
	description="What Tortuga's apps, its hosted server and this website do with your data: no ads, no analytics, nothing sold."
	path="/privacy/"
/>

<div class="policy-head ink">
	<div class="wrap head-grid">
		<div>
			<h1>Privacy policy</h1>
			<p class="effective">Effective 4 November 2026</p>
		</div>
		<div class="head-art" aria-hidden="true"><Turtle swim /></div>
	</div>
</div>

<div class="policy char swell">
	<div class="wrap policy-grid">
		<nav class="toc" aria-label="Sections">
			<ol>
				<li><a href="#in-short">In short</a></li>
				<li><a href="#responsible">Who is responsible</a></li>
				<li><a href="#website">This website</a></li>
				<li><a href="#device">Data on your device</a></li>
				<li><a href="#account">Your Tortuga account</a></li>
				<li><a href="#trackers">Trackers</a></li>
				<li><a href="#watch-together">Watch together</a></li>
				<li><a href="#crash-reports">Crash reports</a></li>
				<li><a href="#services">Services the apps talk to</a></li>
				<li><a href="#self-hosted">Self-hosted servers</a></li>
				<li><a href="#keeping">Keeping and deleting data</a></li>
				<li><a href="#rights">Your rights</a></li>
				<li><a href="#children">Children</a></li>
				<li><a href="#security">Security</a></li>
				<li><a href="#changes">Changes and contact</a></li>
			</ol>
		</nav>
		<article>
			<section id="in-short" aria-labelledby="in-short-title">
				<h2 id="in-short-title">In short</h2>
				<ul>
					<li>Tortuga is a video player and provides no content. The addons you install, and the servers they point to, get your requests directly. Tortuga’s own server is not in that path.</li>
					<li>With a Tortuga account, Tortuga’s server keeps your sign-in identity, your devices, what you sync and your profiles’ tracker sign-ins. It can read them, and stores what you sync and the tracker sign-ins encrypted at rest. There is no end-to-end encryption.</li>
					<li>Watch together sends what you play, and your profile’s name, to the others in the room through Open Watch Party’s public relays, or another relay you choose. Open Watch Party’s privacy notice says what its relays keep.</li>
					<li>No ads and no analytics, and your data is not sold. The apps from the app stores and GitHub send a report when they crash, without your account or addon links, unless you turn that off (see <a href="#crash-reports">Crash reports</a>).</li>
				</ul>
			</section>
			<section id="responsible" aria-labelledby="responsible-title">
				<h2 id="responsible-title">Who is responsible</h2>
				<p>Tortuga is an open-source project by its developer, <a href="https://github.com/edde746">edde746</a> on GitHub, who runs Tortuga’s hosted server at <code>api.usetortuga.app</code> and is responsible (the “controller”) for the data it holds. A server that someone else runs is that operator’s responsibility; see <a href="#self-hosted">Self-hosted servers</a>.</p>
			</section>
			<section id="website" aria-labelledby="website-title">
				<h2 id="website-title">This website</h2>
				<p>usetortuga.app is a set of static pages. It sets no cookies and runs no analytics or ads. One page runs a short script: the page a shared title’s link opens, which reads its own address to show the title’s name and make its Open in Tortuga button. It sends nothing anywhere and fetches nothing about the title. The service that hosts the site may keep ordinary request logs, as any web server does.</p>
				<p>A title’s link that you share from the apps (<code>usetortuga.app/t/…</code>) carries the title’s type and its ID in your addons’ catalog, such as its IMDb ID, and its name. It says nothing about you or your profile. Where Tortuga is installed the link opens in the app; elsewhere it opens this site, which then sees it in its request logs.</p>
			</section>
			<section id="device" aria-labelledby="device-title">
				<h2 id="device-title">Data on your device</h2>
				<p>The apps store on your device:</p>
				<ul>
					<li>your profiles: name, avatar, preferences, and a PIN if you set one, as a salted hash;</li>
					<li>each profile’s addons and their order. An addon’s link can contain keys for services you set up in that addon;</li>
					<li>your library, watch progress and watched marks, and the last stream you picked for each video;</li>
					<li>while the profile’s Suggest a stream setting is on (it is off by default), the streams you pick by hand, for stream suggestions: for each pick, the streams the list showed around it as their descriptions say (resolution, codec, languages, size; never a link), the kind of device, whether the network was metered, what the screen and decoders handle, and how playback went. A profile keeps its newest 400 picks of the last year per kind of device. Turning the setting off stops new picks from being kept and leaves the ones already kept in place;</li>
					<li>profile settings, which sync, and this device’s settings and recent searches, which stay on this device;</li>
					<li>for each tracker a profile connects, the service and your username there, and watched marks still waiting to be sent to it;</li>
					<li>the watch-together rooms you used recently: their codes and, for a relay you chose, its address;</li>
					<li>your downloads, with their artwork and subtitles and the details of the title, stream and addon they came from;</li>
					<li>a cache of artwork, and, if you use an anime tracker, two public lists that map anime IDs between services. Addon responses are cached in memory only, while the app runs.</li>
				</ul>
				<p>Your session with the sync server and the trackers’ current access tokens are kept in the system’s secure storage: the Keychain on Apple devices, a file encrypted with a key held in the Android Keystore, Windows Credential Manager, or the Secret Service on Linux.</p>
				<p>The apps write diagnostic messages only to the device’s own log (the system log on Apple devices and Android, the console on Windows and Linux) and send them nowhere.</p>
				<p>A profile PIN locks the profile picker. It is a convenience, not strong protection: someone with full access to the device and its data could get around it.</p>
				<p>While a video plays, its title, episode and artwork appear in your system’s media controls (Now Playing on Apple devices, the media notification on Android, the media controls on Windows, MPRIS on Linux), which can show on the lock screen.</p>
				<p>Signing out in Settings removes your profiles, addons, library, stream picks, downloads, recent searches and tracker sign-ins from the device; this device’s settings stay. If the session ends another way (signed out from another device, unused for 180 days, or the account deleted), the device keeps its copy of your data until another account signs in on it or the app is removed.</p>
				<p>Uninstalling the app doesn’t remove everything:</p>
				<ul>
					<li>On iPhone, iPad, Apple TV and the Mac, the Keychain keeps your session with the server and the trackers’ access tokens after the app is deleted, so the app is signed in again when you reinstall it. Sign out in Settings first to remove them.</li>
					<li>On the Mac, deleting the app leaves its data in your Library folder. On Linux, Flatpak keeps the app’s data unless you uninstall it with <code>--delete-data</code>.</li>
					<li>On Windows and Linux, your session and the trackers’ access tokens stay in Windows Credential Manager or your keyring, and downloads stay in the folder they were saved to.</li>
					<li>On Android, uninstalling the app or clearing its data removes everything it stored on the device.</li>
					<li>Your account and everything the server keeps for it stay until you delete the account in Settings (see <a href="#keeping">Keeping and deleting data</a>).</li>
				</ul>
				<p>Your device’s backups can include the app’s data, as your backup settings allow: Android’s backup and its transfer to a new phone, iCloud and computer backups of an iPhone or iPad, Time Machine on the Mac, and on Windows and Linux whatever backs up your files. That data is the list above, so a backup holds your profiles, each profile’s addons with their links and any keys in them, your library and your settings. This is on purpose: a device restored from a backup has your addons back. The secure storage stays out of backups, so a new device restored from one asks you to sign in again: on Android its encrypted file is left out, since the key that opens it never leaves the device, and on Apple devices its Keychain items are never restored to another device. On Android, iPhone, iPad and the Mac, downloads and caches are left out too. Apple TV keeps the app’s data where its backups don’t reach.</p>
			</section>
			<section id="account" aria-labelledby="account-title">
				<h2 id="account-title">Your Tortuga account</h2>
				<p>Tortuga asks you to sign in the first time you open it. Unless you choose another server, your account lives on Tortuga’s hosted server at <code>api.usetortuga.app</code>.</p>
				<h3>What the server stores</h3>
				<ul>
					<li><strong>Your sign-in identity.</strong> You sign in with Apple, Google or GitHub, or with a server’s own OpenID Connect provider. Apple, Google and OpenID Connect give the server a signed token; with GitHub, the server asks GitHub for your account’s number and verified email. The server keeps the provider, the provider’s identifier for you (its issuer and subject), your email address if the provider shares a verified one (with Apple’s Hide My Email, that is a relay address), and when you added and last used that sign-in. It keeps no name, username or picture from the provider. The account is keyed by the identifier, not by the email. A second sign-in method you add in Settings is kept the same way.</li>
					<li><strong>Apple and GitHub tokens, for removing access.</strong> With Sign in with Apple, the hosted server also keeps the refresh token Apple issues; with GitHub, the server keeps a GitHub token. Both are encrypted. Each is used only to remove Tortuga’s access at Apple or GitHub when you remove that sign-in method or delete your account.</li>
					<li><strong>Your devices.</strong> Each signed-in device has its own session. The server keeps the device’s name as your system reports it (such as the name in its settings, or the computer’s name), its platform, when it signed in, when it was last active (updated at most every five minutes) and when its tokens expire. Access and refresh tokens are stored only as hashes.</li>
					<li><strong>What you sync.</strong> Profiles (name, avatar and PIN hash) and, for each profile, its addons and their order (with their links), library, watch progress and watched marks, the last stream picked for each video, the streams picked by hand while Suggest a stream is on (with what the list showed around each, the device’s kind, network, screen and decoders at the pick, and how playback went; no links), and profile settings. Device settings and recent searches never sync.</li>
					<li><strong>Tracker sign-ins.</strong> For each tracker a profile connects: the service’s access and refresh tokens, when they expire, your username there and Tortuga’s client ID at that service, all encrypted, plus which profile and service it is and when it was connected (see <a href="#trackers">Trackers</a>).</li>
					<li><strong>Bookkeeping.</strong> For each synced record, a version number, its size, whether it was deleted and when it last changed; for the account, when it was created, how much it stores, and counters that tell your devices something changed.</li>
				</ul>
				<h3>How it is protected</h3>
				<p>Everything travels over HTTPS. The server can read what you sync and your tracker sign-ins: Tortuga does not use end-to-end encryption, which is why signing in on a new device is all it takes to get your data there.</p>
				<p>At rest, the content and identifier of every synced record, tracker tokens and usernames, and Apple and GitHub tokens are encrypted (AES-256-GCM) with a key kept outside the database. A copy of the database, on its own, therefore reveals none of your addon links, library or tracker, Apple and GitHub tokens. Sign-in identities, email addresses, device names, and which trackers each profile connected are stored as they are. The running server, and whoever operates it, can read everything. If you would rather nobody else could, run your own server.</p>
				<h3>Short-lived records</h3>
				<ul>
					<li><strong>Linking a device by code or QR code.</strong> The new device’s name and platform, the code, a hash of a secret only that device holds, and your answer. Deleted as soon as the new device collects your answer, otherwise within 11 minutes.</li>
					<li><strong>Signing in with Apple or GitHub in a browser.</strong> Apple’s page, on Android, Windows and Linux, and GitHub’s page, on phones, tablets and computers, return to a page on the server. What they send back waits in the server’s database, encrypted like your tokens, until the app collects it, and is then deleted, otherwise within 11 minutes: from Apple, its sign-in token, a one-time code and, the first time, the name and email you chose to share; from GitHub, a one-time code, which the server exchanges for a token.</li>
					<li><strong>Sign-in checks.</strong> Single-use sign-in values and hashes of sign-in tokens already used, kept until shortly after they expire and linked to no account.</li>
				</ul>
				<h3 id="server-logs">Server logs</h3>
				<p>The server logs each request’s method, path, status and duration, a request ID and, for signed-in requests, the account ID; some paths contain the ID of a device, a sign-in method or a profile. It also logs events with the account ID: a sign-in and its provider, a sign-in method added, a device linked, a tracker connected, refreshed or disconnected, and an account deleted. It does not log IP addresses, email addresses, tokens or anything you sync. The hosted server keeps at most the newest 30 MB of its log and deletes older lines.</p>
				<p>To limit abuse, the server counts recent requests per IP address in memory; those counts are never stored. The hosting and network providers in front of the server see your IP address as part of carrying the connection.</p>
				<p>The web server in front of it, which also passes crash reports on to the crash-report server, logs a request only when it can’t finish passing it on, such as a sync connection that breaks off or a crash report it can’t deliver. That line holds the request’s IP address, time, path and headers, such as the app’s or crash reporter’s name and version and the language your device asks for, never a sign-in token or anything of a report. The host keeps such lines in two system logs: one deletes them after five weeks at most; the other, which has no time limit, deletes its oldest lines once it holds 500 MB.</p>
			</section>
			<section id="trackers" aria-labelledby="trackers-title">
				<h2 id="trackers-title">Trackers</h2>
				<p>Trackers are optional. A tracker you connect belongs to that profile, on every device of your account; each device can switch sending to it on or off.</p>
				<ul>
					<li><strong>What a tracker gets.</strong> Trakt, Simkl and MDBList receive your playback progress as you watch. Every connected tracker receives the titles you finish, and the ones you mark watched or unwatched, by their IDs (such as IMDb, TMDB or anime IDs), with season, episode and when you watched; never the stream or the addon. Opening a title’s page asks each tracker the profile connected for its rating, and the apps read your watchlists, lists and recommendations there to show them as rows.</li>
					<li><strong>Where the sign-in is kept.</strong> Once a device connects a tracker, it sends the service’s tokens and your username there to your Tortuga server, which keeps them encrypted (see <a href="#account">Your Tortuga account</a>). Only the server refreshes them with the service. Each device asks the server for the current access token and keeps it in its secure storage until it expires. The server talks to the trackers only to refresh and revoke tokens; it never sends them what you watch.</li>
					<li><strong>Disconnecting</strong> a tracker, or deleting its profile or your account, deletes the sign-in from the server and asks Trakt, Simkl and MDBList to revoke it. MyAnimeList and AniList offer no way to revoke, so remove Tortuga’s access in their settings yourself.</li>
					<li>Trakt, Simkl and MDBList sign in with a code on the service’s own website, between your device and the service.</li>
					<li>MyAnimeList and AniList sign in through Tortuga’s sign-in relay on <code>api.usetortuga.app</code>, which holds Tortuga’s credentials for those services. The relay hands the service’s tokens to your device once and stores nothing: a sign-in in progress, with its tokens until your device collects them, is held in memory for at most 11 minutes, only a hash of your device’s one-time secret is kept, tokens are never logged, and nothing is written to the database. Your device then sends the tokens to your Tortuga server, as above. The apps use this relay even when you sync with your own server.</li>
					<li>For anime trackers, the apps download one public ID list combining Fribb’s anime-lists and Anime-Lists (about 0.65 MB) and its checksum list from GitHub (<a href="https://github.com/edde746/anime-mappings/releases">anime-mappings</a>’ releases). Each device brings it up to date each time the app opens or comes back to the screen, on a network that isn’t metered, checks it for updates at most once a day and keeps it in the app’s cache. It is read into memory only while an anime tracker is connected.</li>
				</ul>
			</section>
			<section id="watch-together" aria-labelledby="watch-together-title">
				<h2 id="watch-together-title">Watch together</h2>
				<p>Watch together is optional. It runs through a relay: Open Watch Party’s public relays unless you choose another in the app, whatever server you sync with. Tortuga’s server is not in that path. An invite link to a room on another relay names that relay, which can be someone else’s.</p>
				<ul>
					<li><strong>Who can join.</strong> Anyone with a room’s 6-character code, up to 8 people, until the host locks the room. Rooms are not tied to your account. A room can hold people using other Open Watch Party apps, not only Tortuga.</li>
					<li><strong>What the others in the room receive.</strong> Your profile’s name, or this device’s name if the profile has none, and the app’s name and version (such as “Tortuga/1.0”). What is playing: its title, year, episode and IDs (such as IMDb and TMDB, and the addon’s own type and IDs), and the file’s name, size, length and OpenSubtitles hash, which others use to find the same title and file through their own addons or libraries. Playback: position, playing or paused, speed, buffering, whether your file matches and by how much its length differs, and your offset. Never your stream or addon links.</li>
					<li><strong>What the relay keeps.</strong> The public relays are run by the Open Watch Party project, not by Tortuga, under the <a href="https://openwatch.party/privacy">Open Watch Party privacy notice</a>, which says what they process, for how long and where. In short: they see your IP address in memory only, hold the room’s data while it lasts (at most 24 hours, saved to disk so that a room survives a restart, and in the hosting provider’s daily backup for up to a day after), and afterwards keep only daily counts per app. There is no end-to-end encryption. A relay you choose is its operator’s responsibility, and this policy does not cover it.</li>
				</ul>
			</section>
			<section id="crash-reports" aria-labelledby="crash-reports-title">
				<h2 id="crash-reports-title">Crash reports</h2>
				<p>The apps from the app stores and from GitHub’s releases send a report when they crash, so the developer can find and fix the fault. Reports go to the developer’s own crash-report server, which runs <a href="https://github.com/edde746/bugs">Bugs</a>, an open-source error tracker, and to no one else. A build you compile yourself sends none.</p>
				<ul>
					<li><strong>When.</strong> When the app crashes, on every platform, and when a fault stops one part of the app while the rest keeps running; on Android also when its native code (such as the video engine) crashes or the app stops responding, and on Apple devices also when the app hangs. A report that can’t leave as the app crashes is sent when it next starts.</li>
					<li><strong>What a report holds.</strong> The error and where in the app’s code it happened (its stack trace and threads), the app’s version, the device’s model, its operating system and version, language, time zone, memory, storage, battery and screen, whether the app was in front, and a short trail of the app’s events before the crash (such as a screen opening or the app going to the background). Every text in it passes one filter: a link keeps only its site (<code>https://addon.example.com/your-key/manifest.json</code> becomes <code>https://addon.example.com</code>), so no addon link and no key in one is sent, other key-like values are removed, and your home folder’s name becomes <code>~</code>.</li>
					<li><strong>What it never holds.</strong> Your account, an identifier for you or your device, your device’s name, screenshots, or how you use the app. The report doesn’t contain the IP address it comes from, and the crash-report server doesn’t store it. When a report can’t be passed on to that server, the web server in front of it logs the address it came from, with the time and nothing of the report; <a href="#server-logs">Server logs</a> says for how long.</li>
					<li><strong>Turning them off.</strong> Crash reports are on by default. Each device has its own switch: Settings, This device, Privacy, “Send crash reports”. While it is off the app sends nothing, and a crash meanwhile is never sent, not even after you turn it back on.</li>
					<li><strong>How long.</strong> The server keeps a report for up to 90 days.</li>
				</ul>
			</section>
			<section id="services" aria-labelledby="services-title">
				<h2 id="services-title">Services the apps talk to</h2>
				<p>Your device talks to these services directly, without Tortuga’s server in between. Each sees your IP address, as any service you connect to does, and handles your data under its own privacy policy.</p>
				<ul>
					<li><strong>Your addons.</strong> Each addon you install receives the requests for what it offers: the catalogs you browse, what you search for, and the IDs of what you open. Subtitle addons also receive the video’s file name, size and a fingerprint (the OpenSubtitles hash, computed from the file’s size and its first and last 64 KB). New profiles start with Cinemeta and OpenSubtitles v3, which Stremio runs; Cinemeta can’t be removed.</li>
					<li><strong>Stream, image and subtitle servers.</strong> The servers your addons point to. The apps request a stream when you pick it, and also on their own: when binge watching plays the next episode, when Resume or auto-play picks a stream, when a download picks an episode’s stream, and when a watch-together guest looks for the host’s file. They load posters, backgrounds and logos from wherever the addon or tracker says they are, and subtitles from wherever the subtitle addon says.</li>
					<li><strong>A poster service you choose.</strong> With a poster URL template set (Settings, under Appearance), the service it points to receives the IDs and types of the titles the apps show posters for.</li>
					<li><strong>Sign-in providers.</strong> Apple, Google, GitHub, or the OpenID Connect provider of a server you choose. On TVs, Google and GitHub sign-in use the provider’s device code page.</li>
					<li><strong>Trackers</strong> you connect, and GitHub for the anime ID list (see <a href="#trackers">Trackers</a>).</li>
					<li><strong>GitHub, for kids profiles’ suggested ages.</strong> The apps download agerating’s public age database (about 1 MB) and its checksum list from GitHub. Each device brings it up to date each time the app opens or comes back to the screen, on a network that isn’t metered, and checks it for updates at most once a day; it is kept in the app’s cache and works offline. It is read into memory only while a profile on the device is a kids profile. GitHub sees your device’s IP address. Each title is looked up in the copy on your device, so no title, profile or other data leaves the device for this.</li>
					<li><strong>YouTube and your browser.</strong> YouTube trailers open in YouTube or the browser, as do addons’ web links and configuration pages; a TV without a browser shows a QR code to open them on your phone. Other trailers play in Tortuga, from wherever the addon says they are.</li>
					<li><strong>Other players.</strong> When you send a stream to another player app, such as VLC or Infuse, that app receives the stream’s link, which can contain keys, and its subtitle links.</li>
					<li><strong>Updates.</strong> The Mac app downloaded as a disk image checks GitHub for updates with Sparkle, and shows an update’s release notes from the developer’s site <code>gh-html.edde746.dev</code>. Versions from an app store update through that store. The other versions don’t check for updates.</li>
				</ul>
			</section>
			<section id="self-hosted" aria-labelledby="self-hosted-title">
				<h2 id="self-hosted-title">Self-hosted servers</h2>
				<p>You can sign in to a server you or someone else runs instead of Tortuga’s. Its operator, not Tortuga, decides how your data is handled there, and this policy does not cover it. The server software can read what you sync and your tracker sign-ins on any server. The apps reach a server on the internet only over HTTPS; plain HTTP is accepted only for a server on your own network or your device.</p>
				<p>Such a server signs you in with the Apple, Google, GitHub or OpenID Connect sign-in its operator sets up. Tracker sign-in for MyAnimeList and AniList still goes through <code>api.usetortuga.app</code>’s relay. A server without its own Sign in with Apple key can’t revoke Sign in with Apple when you delete your account; it deletes your data and asks you to remove Tortuga from your Apple Account’s sign-in settings yourself.</p>
			</section>
			<section id="keeping" aria-labelledby="keeping-title">
				<h2 id="keeping-title">Keeping and deleting data</h2>
				<ul>
					<li><strong>Delete your account</strong> at any time in Settings, under Account, with “Delete account”, or without the app by email (see <a href="{resolve('/support/')}#delete">Support</a>). It immediately deletes the account and everything under it from the server (sign-in identities, devices, synced data, tracker sign-ins and device links) and signs out every device. It also asks Apple and GitHub to remove Tortuga’s access, where the server holds their token, and Trakt, Simkl and MDBList to revoke the tracker sign-ins. Where Apple’s or GitHub’s access couldn’t be removed, the app tells you where to remove it yourself. Log lines that name the account go as the log rotates.</li>
					<li><strong>Remove a sign-in method</strong> in Settings, under Account and Sign-ins. The server deletes it and, where it holds an Apple or GitHub token for it, asks Apple or GitHub to remove Tortuga’s access.</li>
					<li><strong>Sign out a single device</strong> in Settings, under Account and Devices. A device that hasn’t been used for 180 days is signed out automatically, and its session deleted.</li>
					<li><strong>Removing something that syncs</strong>, such as an addon or a library item, removes it from your devices. The server keeps a small deletion marker with the item’s ID, encrypted like the rest, until the account is deleted, so devices that were offline remove it too.</li>
					<li><strong>Backups.</strong> The hosted server’s database is not backed up yet. If that changes, this page will first say how backups are kept.</li>
					<li><strong>Otherwise</strong>, your account stays until you delete it, also after you remove the app from every device. The short-lived sign-in records above go within minutes.</li>
				</ul>
			</section>
			<section id="rights" aria-labelledby="rights-title">
				<h2 id="rights-title">Your rights</h2>
				<p>Wherever you live, you can:</p>
				<ul>
					<li>see and change what you sync in the app, and delete it there;</li>
					<li>delete your account and everything the server holds for it, at any time, in the app;</li>
					<li>ask for a copy of the data the hosted server holds about you, ask for it to be corrected or deleted, or object to how it is used (see <a href="#changes">contact</a>).</li>
				</ul>
				<p>In the EU, the EEA and the UK, the GDPR gives you these rights, and you can complain to your data protection authority. The hosted server processes your data to provide the account, sync and trackers you asked for, and to keep the service secure and working; you can object to the latter. Crash reports are sent in the developer’s legitimate interest in fixing the apps’ faults; you object by turning them off.</p>
			</section>
			<section id="children" aria-labelledby="children-title">
				<h2 id="children-title">Children</h2>
				<p>Tortuga is not directed at children. An account belongs to the person who signs in. Profiles for others in the household, children included, are part of that account and collect nothing extra.</p>
			</section>
			<section id="security" aria-labelledby="security-title">
				<h2 id="security-title">Security</h2>
				<p>The apps talk to the server over HTTPS. The server keeps session tokens only as hashes and encrypts synced data, tracker sign-ins and Apple and GitHub tokens at rest, and the apps keep secrets in the system’s secure storage. No system is perfectly secure. If you find a security problem, email <a href="mailto:support@usetortuga.app">support@usetortuga.app</a> rather than posting details publicly.</p>
			</section>
			<section id="changes" aria-labelledby="changes-title">
				<h2 id="changes-title">Changes and contact</h2>
				<p>Changes to this policy are posted on this page with a new effective date. For privacy questions or requests, email <a href="mailto:support@usetortuga.app">support@usetortuga.app</a>.</p>
			</section>
		</article>
	</div>
</div>

<style>
	.policy-head {
		position: relative;
		overflow: hidden;
		/* Runs up under the 76 px header. */
		margin-top: -76px;
		padding-top: 76px;
		background:
			radial-gradient(45% 90% at 85% 40%, rgb(35 196 176 / 0.12), transparent 70%),
			var(--ink);
	}
	.policy-head::before {
		content: "";
		position: absolute;
		inset: 0;
		background: var(--scutes);
		-webkit-mask: radial-gradient(60% 120% at 85% 40%, #000 10%, transparent 75%);
		mask: radial-gradient(60% 120% at 85% 40%, #000 10%, transparent 75%);
	}
	.head-grid {
		position: relative;
		display: flex;
		align-items: center;
		justify-content: space-between;
		gap: 2rem;
		padding-block: clamp(32px, 6vw, 72px) clamp(56px, 8vw, 104px);
	}
	.effective {
		margin-top: 1rem;
		font-weight: 650;
		color: var(--teal);
	}
	.head-art {
		flex: none;
		width: clamp(96px, 18vw, 220px);
	}

	.policy {
		padding-block: clamp(48px, 7vw, 96px);
	}
	.policy-grid {
		display: grid;
		gap: 2.5rem;
	}
	.toc ol {
		display: flex;
		flex-wrap: wrap;
		gap: 0.4rem;
		padding: 0;
		list-style: none;
	}
	.toc a {
		display: block;
		padding: 0.35em 0.8em;
		border-radius: 999px;
		background: var(--fill);
		font-size: 0.9375rem;
		font-weight: 650;
		text-decoration: none;
		transition: background-color 0.2s;
	}
	.toc a:hover {
		background: var(--fill-hover);
	}
	@media (min-width: 960px) {
		.policy-grid {
			grid-template-columns: 250px minmax(0, 1fr);
			gap: clamp(3rem, 6vw, 5.5rem);
			align-items: start;
		}
		.toc {
			position: sticky;
			top: 1.5rem;
		}
		.toc ol {
			display: grid;
			gap: 0.15rem;
		}
		.toc a {
			padding: 0.4em 0.9em;
			background: none;
			color: var(--muted);
		}
		.toc a:hover {
			background: var(--fill);
			color: var(--cream);
		}
	}

	article {
		max-width: 70ch;
	}
	article section,
	article h3[id] {
		scroll-margin-top: 1.5rem;
	}
	article section + section {
		margin-top: clamp(2.5rem, 5vw, 3.5rem);
	}
	article section:first-child {
		padding: clamp(1.4rem, 3vw, 2.25rem);
		border-radius: var(--radius);
		background: var(--navy-raise);
	}
	article h2 {
		font-size: clamp(1.75rem, 1.4rem + 1.2vw, 2.4rem);
		margin-bottom: 1rem;
	}
	article h3 {
		font-size: 1.25rem;
		margin: 1.75rem 0 0.6rem;
	}
	article p + p,
	article ul + p,
	article p + ul {
		margin-top: 0.9rem;
	}
	article ul {
		display: grid;
		gap: 0.6rem;
		padding: 0;
		list-style: none;
	}
	article li {
		position: relative;
		padding-left: 1.6rem;
	}
	article li::before {
		content: "";
		position: absolute;
		left: 0.1rem;
		top: 0.5em;
		width: 0.7rem;
		height: 0.7rem;
		background: var(--accent);
		-webkit-mask: var(--tri) center / contain no-repeat;
		mask: var(--tri) center / contain no-repeat;
	}
	article code {
		padding: 0.1em 0.4em;
		border-radius: 6px;
		background: var(--fill);
		white-space: nowrap;
	}
	article a {
		color: var(--teal);
		font-weight: 600;
	}
</style>
