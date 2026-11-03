<!-- A capture from the app, as AVIF with a WebP fallback, from static/shots/<name>-<width>.{avif,webp}. -->
<script lang="ts">
	import { asset } from "$app/paths";
	import type { Asset } from "$app/types";

	const shots = {
		"tv-home": { widths: [640, 1024, 1600], ratio: 1080 / 1920 },
		"tv-detail": { widths: [640, 1024, 1600], ratio: 1080 / 1920 },
		"androidtv-home": { widths: [640, 1024, 1600], ratio: 1080 / 1920 },
		"ipad-player": { widths: [640, 1024, 1600], ratio: 2064 / 2752 },
		"iphone-home": { widths: [320, 640], ratio: 2736 / 1260 },
		"iphone-detail": { widths: [320, 640], ratio: 2736 / 1260 },
	};

	let {
		name,
		alt,
		sizes,
		eager = false,
		class: className = "",
	}: { name: keyof typeof shots; alt: string; sizes: string; eager?: boolean; class?: string } = $props();

	const { widths, ratio } = $derived(shots[name]);
	const files = $derived(
		widths.map((width) => ({
			width,
			avif: asset(`/shots/${name}-${width}.avif` as Asset),
			webp: asset(`/shots/${name}-${width}.webp` as Asset),
		})),
	);
	const largest = $derived(files[files.length - 1]);
</script>

<picture class={className}>
	<source type="image/avif" srcset={files.map((file) => `${file.avif} ${file.width}w`).join(", ")} {sizes} />
	<img
		src={largest.webp}
		srcset={files.map((file) => `${file.webp} ${file.width}w`).join(", ")}
		{sizes}
		{alt}
		width={largest.width}
		height={Math.round(largest.width * ratio)}
		loading={eager ? "eager" : "lazy"}
		fetchpriority={eager ? "high" : "auto"}
		decoding="async"
	/>
</picture>
