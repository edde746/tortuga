import adapter from "@sveltejs/adapter-static";

/** @type {import('@sveltejs/kit').Config} */
const config = {
  kit: {
    adapter: adapter(),
    // Relative asset and page paths, so the build also works under a path prefix (preview hosts).
    paths: { relative: true },
    // Stylesheets stay linked files: when SvelteKit inlines one, its relative font url()s break.
    // The 404 page is linked from nowhere; prerender it as build/404.html, served at any unknown path.
    prerender: { entries: ["*", "/404"] },
  },
};

export default config;
