import type { Handle } from "@sveltejs/kit";

// Pages are prerendered with relative URLs (svelte.config.js, paths.relative). build/404.html is served at any
// unknown path, at any depth, and build/t/index.html at every title link's /t/<type>/<id> (static/_redirects), so
// their URLs are made root-absolute: `./x` on the 404 page, `../x` on the title page.
const absolute: Record<string, RegExp> = { "/404": /(["(,\s])\.\//g, "/t": /(["(,\s])\.\.\//g };

export const handle: Handle = ({ event, resolve }) => {
	const relative = event.route.id ? absolute[event.route.id] : undefined;
	return resolve(event, {
		preload: ({ type }) => type === "font",
		transformPageChunk: relative ? ({ html }) => html.replace(relative, "$1/") : undefined,
	});
};
