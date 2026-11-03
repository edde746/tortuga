<script lang="ts">
	import { resolve } from "$app/paths";
	import Meta from "$lib/Meta.svelte";
	import Turtle from "$lib/Turtle.svelte";

	const address = "support@usetortuga.app";
	const demo = "https://usetortuga.app/demo/manifest.json";
</script>

<Meta
	title="Support · Tortuga"
	description="Email support@usetortuga.app. How to delete your Tortuga account, with or without the app, and an addon to try playback with."
	path="/support/"
/>

<div class="support-head ink">
	<div class="wrap head-grid">
		<div>
			<h1>Support</h1>
			<p class="lede">Email <a href="mailto:{address}">{address}</a>.</p>
		</div>
		<div class="head-art" aria-hidden="true"><Turtle swim /></div>
	</div>
</div>

<div class="support char swell">
	<article class="wrap">
		<section id="contact" aria-labelledby="contact-title">
			<h2 id="contact-title">Get in touch</h2>
			<p>
				Write to <a href="mailto:{address}">{address}</a> about the apps, your account, a privacy request or a
				security problem. Say which device you use and the version shown in Settings, under About.
			</p>
			<p>Addon links can hold keys to your accounts at other services, so leave them out unless asked.</p>
		</section>
		<section id="delete" aria-labelledby="delete-title">
			<h2 id="delete-title">Delete your account</h2>
			<ul>
				<li>
					<strong>In the app:</strong> Settings, then Account, then Delete account. The account is deleted at once
					and every device is signed out.
				</li>
				<li>
					<strong>Without the app:</strong> email
					<a href="mailto:{address}?subject=Delete%20my%20account">{address}</a> with the subject “Delete my account”,
					from the email address of your Apple, Google or GitHub sign-in. With Apple’s Hide My Email, name the relay
					address instead. We confirm by writing to the account’s address and delete the account within 30 days of
					your answer.
				</li>
			</ul>
			<p>
				Either way, Tortuga’s server deletes the account and everything it keeps for it: sign-in identities,
				devices, profiles, addons, library, progress, settings, tracker sign-ins and device links. Server log lines
				that name the account’s ID go as the log rotates (it keeps at most its newest 30 MB). The server has no
				backups. The <a href={resolve("/privacy/")}>privacy policy</a> has the details.
			</p>
			<p>An account on a server someone else runs is deleted by that server’s operator.</p>
		</section>
		<section id="demo" aria-labelledby="demo-title">
			<h2 id="demo-title">Something to play</h2>
			<p>
				A new profile lists films and series but has no streams. To see Tortuga play, open Settings, then Addons,
				choose Install addon and enter <code>{demo}</code>. It streams public-domain films from the Internet Archive
				and Blender’s open movies from download.blender.org.
			</p>
		</section>
	</article>
</div>

<style>
	.support-head {
		position: relative;
		overflow: hidden;
		/* Runs up under the 76 px header. */
		margin-top: -76px;
		padding-top: 76px;
		background:
			radial-gradient(45% 90% at 85% 40%, rgb(35 196 176 / 0.12), transparent 70%),
			var(--ink);
	}
	.support-head::before {
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
	.lede {
		margin-top: 1rem;
		font-size: 1.25rem;
		font-weight: 650;
	}
	.head-art {
		flex: none;
		width: clamp(96px, 18vw, 220px);
	}

	.support {
		padding-block: clamp(48px, 7vw, 96px);
	}
	article section {
		max-width: 70ch;
		scroll-margin-top: 1.5rem;
	}
	article section + section {
		margin-top: clamp(2.5rem, 5vw, 3.5rem);
	}
	article h2 {
		font-size: clamp(1.75rem, 1.4rem + 1.2vw, 2.4rem);
		margin-bottom: 1rem;
	}
	article p + p,
	article ul + p {
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
		overflow-wrap: anywhere;
	}
	a {
		color: var(--teal);
		font-weight: 600;
	}
</style>
