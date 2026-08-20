// The Cloudflare worker behind triglavmodular.hu/mods/218e-Rewired.
//
// This file is the source of truth; Cloudflare holds a copy that is pasted in
// by hand.  Dashboard: the triglavmodular.hu zone, Workers Routes, the route
// matching /mods/218e-Rewired*, then Edit code.
//
// It maps the public path onto the GitHub Pages site and sets the one cache
// header that Pages cannot: see "Publishing the page, and caches" in
// docs/BUILD.md for why the page has to be revalidated and everything else
// does not.
const PUBLIC = '/mods/218e-Rewired';
const ORIGIN = 'https://triglav-modular.github.io/218e-Rewired';

// Forwarded to the origin.  Host is deliberately absent: GitHub Pages routes
// on it, and passing triglavmodular.hu would ask it for a site it does not
// have.  The conditional headers are what turn a revalidation into a 304
// instead of downloading the page again.
const FORWARD = ['if-none-match', 'if-modified-since',
                 'accept', 'accept-encoding', 'user-agent', 'range'];

export default {
  async fetch(request) {
    const url = new URL(request.url);

    // Without the trailing slash every relative asset resolves into /mods/.
    if (url.pathname === PUBLIC) {
      return Response.redirect(url.origin + PUBLIC + '/', 301);
    }

    const rest = url.pathname.slice(PUBLIC.length);   // "/style.css", "/kit/..."

    const headers = new Headers();
    for (const name of FORWARD) {
      const value = request.headers.get(name);
      if (value) headers.set(name, value);
    }

    const res = await fetch(ORIGIN + rest + url.search,
                            { method: request.method, headers,
                              redirect: 'follow' });

    // A 304 carries no body, and constructing a Response with one throws.
    const out = new Response(res.status === 304 ? null : res.body, res);
    const type = out.headers.get('content-type') || '';

    if (url.searchParams.has('v')) {
      // Everything the page asks for carries a hash of its own contents in
      // the URL, so this exact URL can never mean different bytes later.
      out.headers.set('cache-control', 'public, max-age=31536000, immutable');
    } else if (type.includes('text/html')) {
      // The page is the one file that cannot carry a version - it is the URL
      // people type - so it is the one that has to be checked every time.
      // no-cache, not no-store: it is still kept and still revalidated, so an
      // unchanged page costs a 304 rather than a download.
      out.headers.set('cache-control', 'no-cache');
    }
    return out;
  }
};
