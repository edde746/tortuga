<!--
	The site's turtle: the app icon's glyph as its shell, swimming the way the play button points.
	Colours come from --shell, --skin and --eye; `swim` paddles the flippers unless the visitor asked for reduced motion.
-->
<script lang="ts">
	let { class: className = "", swim = false }: { class?: string; swim?: boolean } = $props();
	const id = $props.id();
</script>

<svg class="turtle {className}" class:swim viewBox="176 176 736 672" aria-hidden="true" focusable="false">
	<defs>
		<mask id="{id}-shell" maskUnits="userSpaceOnUse" x="0" y="0" width="1024" height="1024">
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
		<!-- Everything but the shell and a gap around it, so the flippers and head tuck under it. -->
		<mask id="{id}-body" maskUnits="userSpaceOnUse" x="0" y="0" width="1024" height="1024">
			<rect width="1024" height="1024" fill="#fff" />
			<path d="M343 282L343 742L741 512Z" stroke="#000" stroke-width="116" stroke-linejoin="round" />
		</mask>
	</defs>
	<g class="body" mask="url(#{id}-body)">
		<path
			class="flipper front up"
			d="M600 460C595 370 575 285 530 215C518 196 538 180 560 192C650 245 700 350 708 480Z"
		/>
		<path
			class="flipper front down"
			d="M600 564C595 654 575 739 530 809C518 828 538 844 560 832C650 779 700 674 708 544Z"
		/>
		<path
			class="flipper back up"
			d="M372 345C330 305 270 278 205 278C188 280 186 298 200 308C245 345 300 380 345 400Z"
		/>
		<path
			class="flipper back down"
			d="M372 679C330 719 270 746 205 746C188 744 186 726 200 716C245 679 300 644 345 624Z"
		/>
		<path d="M320 480L230 512L320 544Z" />
		<g class="head">
			<ellipse cx="840" cy="512" rx="60" ry="50" />
			<circle class="eye" cx="866" cy="491" r="8.5" />
			<circle class="eye" cx="866" cy="533" r="8.5" />
		</g>
	</g>
	<rect class="shell" x="300" y="240" width="490" height="550" mask="url(#{id}-shell)" />
</svg>

<style>
	.turtle {
		overflow: visible;
	}
	.body {
		fill: var(--skin, #23c4b0);
	}
	.eye {
		fill: var(--eye, #10202a);
	}
	.shell {
		fill: var(--shell, #fff1d6);
	}
	.flipper {
		transform-box: fill-box;
	}
	.front.up {
		transform-origin: 85% 92%;
	}
	.front.down {
		transform-origin: 85% 8%;
	}
	.back.up {
		transform-origin: 95% 85%;
	}
	.back.down {
		transform-origin: 95% 15%;
	}
	.head {
		transform-box: fill-box;
		transform-origin: 0% 50%;
	}

	@media (prefers-reduced-motion: no-preference) {
		.swim {
			animation: drift 7s ease-in-out infinite alternate;
		}
		.swim .front.up {
			animation: paddle-up 2.6s ease-in-out infinite alternate;
		}
		.swim .front.down {
			animation: paddle-down 2.6s ease-in-out infinite alternate;
		}
		.swim .back.up {
			animation: paddle-up 2.6s ease-in-out -1.3s infinite alternate;
		}
		.swim .back.down {
			animation: paddle-down 2.6s ease-in-out -1.3s infinite alternate;
		}
		.swim .head {
			animation: look 9s ease-in-out infinite;
		}
	}

	@keyframes drift {
		from {
			transform: translate(0, 0) rotate(-1.5deg);
		}
		to {
			transform: translate(2%, -3%) rotate(1.5deg);
		}
	}
	@keyframes paddle-up {
		from {
			transform: rotate(4deg);
		}
		to {
			transform: rotate(-16deg);
		}
	}
	@keyframes paddle-down {
		from {
			transform: rotate(-4deg);
		}
		to {
			transform: rotate(16deg);
		}
	}
	@keyframes look {
		0%,
		70%,
		100% {
			transform: rotate(0);
		}
		78%,
		88% {
			transform: rotate(-9deg);
		}
	}
</style>
