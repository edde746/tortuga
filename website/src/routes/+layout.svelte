<script lang="ts">
	import "../app.css";
	import { asset, resolve } from "$app/paths";
	import Mark from "$lib/Mark.svelte";
	import Turtle from "$lib/Turtle.svelte";

	let { children } = $props();
</script>

<svelte:head>
	<link rel="icon" href={asset("/favicon.svg")} type="image/svg+xml" />
	<link rel="apple-touch-icon" href={asset("/apple-touch-icon.png")} />
</svelte:head>

<a class="skip" href="#main">Skip to content</a>

<header class="site-header char">
	<div class="wrap bar">
		<a class="brand" href={resolve("/")}><Mark class="brand-mark" /><span>Tortuga</span></a>
		<nav aria-label="Main">
			<ul>
				<li class="wide"><a href="{resolve('/')}#how">How it works</a></li>
				<li><a href="{resolve('/')}#platforms">Platforms</a></li>
				<li><a href={resolve("/privacy/")}>Privacy</a></li>
				<li><a href={resolve("/support/")}>Support</a></li>
				<li class="wide"><a href="https://github.com/edde746/tortuga">GitHub</a></li>
			</ul>
		</nav>
	</div>
</header>

<main id="main">
	{@render children()}
</main>

<footer class="site-footer ink swell">
	<div class="lane" aria-hidden="true"><span class="swimmer"><Turtle swim /></span></div>
	<div class="wrap footer-grid">
		<div>
			<a class="brand" href={resolve("/")}><Mark class="brand-mark" /><span>Tortuga</span></a>
			<p>
				Tortuga is a video player that works with Stremio addons. It is not made by, endorsed by or affiliated with
				Stremio.
			</p>
		</div>
		<ul class="footer-links">
			<li><a href={resolve("/privacy/")}>Privacy policy</a></li>
			<li><a href={resolve("/support/")}>Support</a></li>
			<li><a href="https://github.com/edde746/tortuga">Source code</a></li>
			<li><a href="https://www.gnu.org/licenses/gpl-3.0.html">Licence: GPL-3.0</a></li>
			<li><a href="https://github.com/edde746">Made by edde746</a></li>
			<li><a href={resolve("/fonts/LICENSE.txt")}>Fonts: Fraunces and Figtree (OFL)</a></li>
		</ul>
	</div>
</footer>

<style>
	/* Transparent, so the first section's backdrop (the landing hero's) runs under it. */
	.site-header {
		position: relative;
		z-index: 2;
		background: none;
	}
	.bar {
		display: flex;
		align-items: center;
		justify-content: space-between;
		gap: 1rem;
		min-height: 76px;
	}
	.brand {
		display: inline-flex;
		align-items: center;
		gap: 0.55rem;
		font: 700 1.5rem/1 var(--display);
		letter-spacing: -0.02em;
		text-decoration: none;
	}
	.brand :global(.brand-mark) {
		width: 1.15em;
		height: auto;
		color: var(--cream);
	}
	nav ul {
		display: flex;
		gap: clamp(0.25rem, 1.5vw, 1rem);
		padding: 0;
		list-style: none;
	}
	nav a {
		display: block;
		padding: 0.55em 0.8em;
		border-radius: 999px;
		font-weight: 650;
		text-decoration: none;
		color: var(--muted);
		transition:
			background-color 0.2s,
			color 0.2s;
	}
	nav a:hover {
		background: var(--fill);
		color: var(--cream);
	}
	@media (max-width: 719px) {
		.wide {
			display: none;
		}
		nav a {
			padding-inline: 0.6em;
		}
	}

	.site-footer {
		padding: 0 0 clamp(40px, 6vw, 72px);
		color: var(--muted);
		font-size: 0.9375rem;
	}
	.lane {
		position: relative;
		height: 84px;
		overflow: hidden;
	}
	.swimmer {
		position: absolute;
		top: 22px;
		left: 6%;
		width: 52px;
	}
	@media (prefers-reduced-motion: no-preference) {
		.swimmer {
			left: 0;
			animation: cross 48s linear infinite;
		}
	}
	@keyframes cross {
		from {
			transform: translateX(-64px);
		}
		to {
			transform: translateX(100vw);
		}
	}
	.footer-grid {
		display: grid;
		gap: 2rem;
	}
	.footer-grid .brand {
		color: var(--cream);
		margin-bottom: 0.9rem;
	}
	.footer-grid p {
		max-width: 46ch;
	}
	.footer-links {
		display: grid;
		gap: 0.35rem;
		padding: 0;
		list-style: none;
	}
	.footer-links a {
		color: var(--cream);
		text-decoration-color: color-mix(in srgb, var(--cream) 35%, transparent);
	}
	.footer-links a:hover {
		text-decoration-color: currentColor;
	}
	@media (min-width: 720px) {
		.footer-grid {
			grid-template-columns: 1.4fr 1fr;
			align-items: start;
		}
	}
</style>
