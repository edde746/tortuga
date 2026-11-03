<!--
A title's link from the apps' Share: https://usetortuga.app/t/<type>/<id>[?name=<name>]. Where Tortuga is installed the
system opens such links in the app (Universal Links, App Links: static/.well-known/), so this page shows where it
isn't, and on Windows and Linux. static/_redirects serves it at every /t/… path, and hooks.server.ts makes its links
root-absolute. Its one script reads the page's own address to show the name and make the Open in Tortuga link
(tortuga://detail?type=<type>&id=<id>); it sends nothing anywhere. The page fetches nothing about the title.
-->
<script lang="ts">
	import Meta from "$lib/Meta.svelte";
	import Turtle from "$lib/Turtle.svelte";

	// Plain JavaScript, run as the page loads (the site ships no Svelte runtime). A malformed escape in the address
	// reads as a link without its title.
	const script = `<script>
(() => {
	const parts = location.pathname.split("/").filter(Boolean);
	let type = "", id = "";
	if (parts.length === 3 && parts[0] === "t") {
		try {
			type = decodeURIComponent(parts[1]);
			id = decodeURIComponent(parts[2]);
		} catch (error) {
			if (!(error instanceof URIError)) throw error;
		}
	}
	const page = document.getElementById("title");
	if (!type || !id) {
		page.dataset.state = "broken";
		return;
	}
	const name = new URLSearchParams(location.search).get("name")?.trim();
	if (name) {
		document.getElementById("title-name").textContent = name;
		document.title = name + " · Tortuga";
	}
	const open = document.getElementById("open");
	open.href = "tortuga://detail?type=" + encodeURIComponent(type) + "&id=" + encodeURIComponent(id);
	page.dataset.state = "ready";
})();
<\/script>`;
</script>

<Meta title="Open in Tortuga" description="A title shared from Tortuga. Open it in the app, or see where to get Tortuga." />

<section id="title" class="shared char" data-state="loading" aria-label="Shared title">
	<div class="wrap shared-grid">
		<div class="shared-art" aria-hidden="true"><Turtle swim /></div>
		<div>
			<p class="tag">Shared from Tortuga</p>
			<h1 id="title-name" class="ready-only">A title shared from Tortuga</h1>
			<h1 class="broken-only">This link is missing its title.</h1>
			<p class="lede ready-only">
				Open it in Tortuga on this device. Tortuga shows it from the addons on your profile.
			</p>
			<p class="lede broken-only">Ask for the link again, or see where to get Tortuga.</p>
			<noscript>
				<h1>A title shared from Tortuga</h1>
				<p class="lede">Open this link on a device with Tortuga installed to see the title.</p>
			</noscript>
			<div class="actions">
				<a id="open" class="button primary ready-only" href="#title">Open in Tortuga</a>
				<a class="button" href="/#platforms">Get Tortuga</a>
			</div>
			<p class="note ready-only">
				On iPhone, iPad, Mac and Android, the link opens Tortuga by itself once it is installed.
			</p>
		</div>
	</div>
</section>

{@html script}

<style>
	.shared {
		position: relative;
		overflow: hidden;
		background:
			radial-gradient(50% 70% at 25% 45%, rgb(35 196 176 / 0.12), transparent 70%),
			var(--char);
	}
	.shared::before {
		content: "";
		position: absolute;
		inset: 0;
		background: var(--scutes);
		-webkit-mask: radial-gradient(60% 90% at 25% 45%, #000 10%, transparent 75%);
		mask: radial-gradient(60% 90% at 25% 45%, #000 10%, transparent 75%);
	}
	.shared-grid {
		position: relative;
		display: grid;
		gap: 2rem;
		align-items: center;
		min-height: min(70vh, 640px);
		padding-block: clamp(40px, 8vw, 96px);
	}
	.shared-art {
		width: clamp(140px, 30vw, 340px);
	}
	h1 {
		margin-top: 1.25rem;
		max-width: 16ch;
		overflow-wrap: anywhere;
	}
	.lede {
		max-width: 40ch;
		margin-top: 1.25rem;
	}
	.actions {
		display: flex;
		flex-wrap: wrap;
		gap: 0.75rem;
		margin-top: 2rem;
	}
	.note {
		max-width: 46ch;
		margin-top: 1.5rem;
		color: var(--muted);
	}
	/* Until the script has read the address (and without it) only what holds for every link shows. The script sets
	   the state, which Svelte doesn't see: global, so it keeps the rules. */
	:global([data-state="loading"]) .ready-only,
	:global([data-state="loading"]) .broken-only,
	:global([data-state="ready"]) .broken-only,
	:global([data-state="broken"]) .ready-only {
		display: none;
	}
	@media (min-width: 800px) {
		.shared-grid {
			grid-template-columns: minmax(0, 0.8fr) minmax(0, 1.2fr);
		}
		.shared-art {
			justify-self: center;
		}
	}
</style>
