// Every page is prerendered and ships no Svelte runtime. The one script, a title link's page reading its own address
// (routes/t), is inline and named in the privacy policy, which promises no other.
export const prerender = true;
export const csr = false;
export const trailingSlash = "always";
