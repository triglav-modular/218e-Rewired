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

// Where the page reports a download.  Needs an Analytics Engine dataset bound
// as BUILDS in the dashboard (Settings, Variables, Analytics Engine dataset);
// without the binding this route answers 204 and writes nothing, which is the
// right way round - a missing binding must not break the page.
const BEACON = PUBLIC + '/beacon';

// Nothing here is trusted: it arrives from anyone who can reach the route.
// Every value is checked against what the page can actually send and dropped
// otherwise, so a crafted body can add noise to a count but cannot put
// arbitrary strings into the dataset.
const PLATFORMS = ['mac', 'win'];
const VOLTS = ['1', '1.2'];

function flag(value) {
  return value === true ? 1 : 0;
}

async function record(request, env) {
  // No IP, no user-agent, no header of any kind: what is not written cannot
  // later turn an option set into a person.
  if (request.method !== 'POST') return new Response(null, { status: 405 });
  if (!env || !env.BUILDS) return new Response(null, { status: 204 });
  let body;
  try {
    // The page's body is a couple of hundred bytes.  Refused rather than
    // truncated: slicing and then parsing would accept whatever the first
    // 512 bytes of a much larger body happened to spell.
    const text = await request.text();
    if (text.length > 512) return new Response(null, { status: 204 });
    body = JSON.parse(text);
  } catch (e) {
    return new Response(null, { status: 204 });
  }
  if (!body || typeof body !== 'object') return new Response(null, { status: 204 });

  const platform = PLATFORMS.includes(body.platform) ? body.platform : 'other';
  const volts = VOLTS.includes(String(body.volts_per_octave))
    ? String(body.volts_per_octave) : 'other';
  // A version string is ours, but it arrives from the page like everything
  // else, so it is held to the shape a version has.
  const version = /^[0-9]{1,3}(\.[0-9]{1,3}){0,2}$/.test(String(body.version))
    ? String(body.version) : 'other';
  const tunings = Number.isInteger(body.alternate_tunings)
    && body.alternate_tunings >= 0 && body.alternate_tunings <= 3
    ? body.alternate_tunings : -1;

  env.BUILDS.writeDataPoint({
    // One index, which Analytics Engine samples on.
    indexes: [platform],
    blobs: [platform, version, volts],
    doubles: [flag(body.latching_arp), flag(body.remap_knobs),
              flag(body.pressure_fix), flag(body.pressure_portamento),
              tunings, flag(body.pitch_correction)]
  });
  return new Response(null, { status: 204 });
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);

    if (url.pathname === BEACON) return record(request, env);

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
