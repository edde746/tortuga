// The fonts' SIL Open Font License texts, served beside the fonts the pages load.
import figtree from "$lib/fonts/OFL-Figtree.txt?raw";
import fraunces from "$lib/fonts/OFL-Fraunces.txt?raw";

export const prerender = true;
export const trailingSlash = "never";

export function GET() {
	return new Response(`Fraunces\n\n${fraunces}\n\nFigtree\n\n${figtree}`, {
		headers: { "content-type": "text/plain; charset=utf-8" },
	});
}
