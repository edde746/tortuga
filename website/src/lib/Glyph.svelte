<!-- Small feature glyphs built from the icon's rounded play triangle, in the current text colour. -->
<script lang="ts">
	let { name }: { name: "download" | "together" | "sync" | "server" | "tracker" | "profile" } = $props();
</script>

<svg class="glyph" viewBox="0 0 32 32" aria-hidden="true" focusable="false">
	<g fill="currentColor" stroke="currentColor" stroke-width="3" stroke-linejoin="round" stroke-linecap="round">
		{#if name === "download"}
			<path d="M9 7h14l-7 12z" />
			<path d="M7 25.5h18" fill="none" />
		{:else if name === "together"}
			<path d="M5 8v16l11-8z" opacity=".5" />
			<path d="M14 8v16l11-8z" />
		{:else if name === "sync"}
			<path d="M5 11h15" fill="none" />
			<path d="M20 6v10l7-5z" />
			<path d="M27 21H12" fill="none" />
			<path d="M12 16v10l-7-5z" />
		{:else if name === "server"}
			<path d="M7 8h18M7 16h18M7 24h18" fill="none" stroke-width="5" />
		{:else if name === "tracker"}
			<path d="M6 22h20" fill="none" stroke-width="4" opacity=".4" />
			<path d="M6 22h11" fill="none" stroke-width="4" />
			<path d="M10 5v9l7.5-4.5z" />
		{:else}
			<circle cx="16" cy="11" r="4.5" />
			<path d="M8 26a8 7 0 0 1 16 0z" />
		{/if}
	</g>
</svg>

<style>
	.glyph {
		width: 32px;
		height: 32px;
	}
</style>
