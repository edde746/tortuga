<!-- Title, description and social tags. A page without `path` (the 404) gets no canonical and is not indexed. -->
<script lang="ts">
	let { title, description, path }: { title: string; description: string; path?: string } = $props();

	const site = "https://usetortuga.app";
</script>

<svelte:head>
	<title>{title}</title>
	<meta name="description" content={description} />
	{#if path}
		<link rel="canonical" href="{site}{path}" />
		<meta property="og:url" content="{site}{path}" />
	{:else}
		<meta name="robots" content="noindex" />
	{/if}
	<meta property="og:type" content="website" />
	<meta property="og:site_name" content="Tortuga" />
	<meta property="og:title" content={title} />
	<meta property="og:description" content={description} />
	<meta property="og:image" content="{site}/og.png" />
	<meta property="og:image:width" content="1200" />
	<meta property="og:image:height" content="630" />
	<meta property="og:image:alt" content="Tortuga's turtle, its shell the app's play button, beside the name Tortuga." />
	<meta name="twitter:card" content="summary_large_image" />
</svelte:head>
