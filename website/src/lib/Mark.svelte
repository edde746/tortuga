<!-- The app icon's glyph (tools/brand/tortuga-icon-*.svg) without its tile, in the current text colour. -->
<script lang="ts">
	let { class: className = "" }: { class?: string } = $props();
	const id = $props.id();
</script>

<svg class={className} viewBox="305 244 474 536" aria-hidden="true" focusable="false">
	<mask id="{id}-mark" maskUnits="userSpaceOnUse" x="0" y="0" width="1024" height="1024">
		<path
			d="M343 282L343 742L741 512Z"
			fill="#fff"
			stroke="#fff"
			stroke-width="76"
			stroke-linejoin="round"
		/>
		<path d="M259 596L719 408" stroke="#000" stroke-width="28" />
		<ellipse cx="544" cy="480" rx="80" ry="63" transform="rotate(-22 544 480)" />
	</mask>
	<rect x="300" y="240" width="490" height="550" fill="currentColor" mask="url(#{id}-mark)" />
</svg>
