<!-- Written to build/404.html and served at any unknown path, so its links are root-absolute (see hooks.server.ts). -->
<script lang="ts">
	import Meta from "$lib/Meta.svelte";
	import Turtle from "$lib/Turtle.svelte";
</script>

<Meta title="Page not found · Tortuga" description="There is no page at this address." />

<section class="lost char" aria-labelledby="lost-title">
	<div class="wrap lost-grid">
		<div class="lost-art" aria-hidden="true"><Turtle swim /></div>
		<div>
			<p class="tag">404</p>
			<h1 id="lost-title">This page swam off.</h1>
			<p class="lede">There is nothing at this address. The home page and the privacy policy are where they were.</p>
			<div class="actions">
				<a class="button primary" href="/">Go to the home page</a>
				<a class="button" href="/privacy/">Privacy policy</a>
			</div>
		</div>
	</div>
</section>

<style>
	.lost {
		position: relative;
		overflow: hidden;
		background:
			radial-gradient(50% 70% at 25% 45%, rgb(35 196 176 / 0.12), transparent 70%),
			var(--char);
	}
	.lost::before {
		content: "";
		position: absolute;
		inset: 0;
		background: var(--scutes);
		-webkit-mask: radial-gradient(60% 90% at 25% 45%, #000 10%, transparent 75%);
		mask: radial-gradient(60% 90% at 25% 45%, #000 10%, transparent 75%);
	}
	.lost-grid {
		position: relative;
		display: grid;
		gap: 2rem;
		align-items: center;
		min-height: min(70vh, 640px);
		padding-block: clamp(40px, 8vw, 96px);
	}
	.lost-art {
		width: clamp(140px, 30vw, 340px);
		/* Swimming away, against the play direction. */
		transform: scaleX(-1);
	}
	h1 {
		margin-top: 1.25rem;
		max-width: 12ch;
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
	@media (min-width: 800px) {
		.lost-grid {
			grid-template-columns: minmax(0, 0.8fr) minmax(0, 1.2fr);
		}
		.lost-art {
			justify-self: center;
		}
	}
</style>
