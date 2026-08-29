// The Cloudflare worker behind triglavmodular.hu/mods/218e-Rewired.
//
// Deployed with `npx wrangler deploy` from the repository root; wrangler.toml
// there carries the name, the route and the bindings.  Nothing is pasted into
// the dashboard any more - and the dashboard cannot add the dataset binding
// itself, so that file is the only way this worker gets one.
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
                 'accept', 'accept-encoding', 'user-agent', 'range',
                 // With range but not if-range, a resumed download whose file
                 // changed at the origin got a 206 of the NEW file appended
                 // to the old half.  If-range makes the origin answer 200
                 // with the whole new file instead.
                 'if-range'];

// Where the page reports a download.  Needs an Analytics Engine dataset bound
// as BUILDS - the Bindings tab, not a runtime variable: a text variable named
// BUILDS is a string, and a string has nothing to write a data point with.
// Without a usable binding this route answers 204 and writes nothing, which is
// the right way round - a missing or wrong binding must not break the page.
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

async function record(request, env, context) {
  // No IP, no user-agent, no header of any kind: what is not written cannot
  // later turn an option set into a person.
  if (request.method !== 'POST') return new Response(null, { status: 405 });
  // Not just "is it there": a dataset bound as a text variable arrives as the
  // string "builds", which is truthy and would throw on the write below.  Ask
  // for the one thing this needs from it instead.
  if (typeof env?.BUILDS?.writeDataPoint !== 'function') {
    return new Response(null, { status: 204 });
  }
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

  const point = {
    platform, version, volts,
    arp: flag(body.latching_arp),
    knobs: flag(body.remap_knobs),
    pressure: flag(body.pressure_fix),
    portamento: flag(body.pressure_portamento),
    tunings,
    calibration: flag(body.pitch_correction),
  };

  env.BUILDS.writeDataPoint({
    // One index, which Analytics Engine samples on.
    indexes: [platform],
    blobs: [platform, version, volts],
    doubles: [point.arp, point.knobs, point.pressure, point.portamento,
              tunings, point.calibration],
  });

  // And the same thing where it can be read back without a credential.  One
  // key per download rather than a counter: KV has no atomic increment, so
  // two downloads at once would read the same number and write it back twice.
  // The whole point rides in the key's metadata, which `list` returns - so
  // reading a month costs one operation, not one per download.
  if (env.COUNTS) {
    const at = new Date().toISOString();
    const key = `b:${at}:${Math.random().toString(36).slice(2, 10)}`;
    const write = env.COUNTS.put(key, '', {
      metadata: point,
      expirationTtl: 400 * 24 * 60 * 60,   // a year and a bit, then it ages out
    }).catch(() => {});                    // counting must never fail a download
    if (context && context.waitUntil) context.waitUntil(write);
  }
  return new Response(null, { status: 204 });
}

export default {
  async fetch(request, env, context) {
    const url = new URL(request.url);

    if (url.pathname === BEACON) return record(request, env, context);

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
    // A 304 is a success too: it renews whatever the browser has cached, so
    // it needs the same cache-control as the 200 it stands in for.  It also
    // carries no content-type, which is why the page rule below matches on
    // the path rather than the type - matching on type let the origin's
    // max-age=600 ride through every revalidation and overwrite no-cache.
    const ok = res.ok || res.status === 304;
    const isPage = rest === '/' || rest === '' || rest.endsWith('/') ||
                   rest.endsWith('.html');

    if (url.searchParams.has('v')) {
      // Everything the page asks for carries a hash of its own contents in
      // the URL, so this exact URL can never mean different bytes later.
      // Only when it worked: an origin 404 or 5xx stamped immutable would sit
      // in browsers for a year under the exact URL the page keeps asking for.
      if (ok) {
        out.headers.set('cache-control', 'public, max-age=31536000, immutable');
      } else {
        out.headers.set('cache-control', 'no-store');
      }
    } else if (isPage || type.includes('text/html')) {
      // The page is the one file that cannot carry a version - it is the URL
      // people type - so it is the one that has to be checked every time.
      // no-cache, not no-store: it is still kept and still revalidated, so an
      // unchanged page costs a 304 rather than a download.
      out.headers.set('cache-control', 'no-cache');
    }
    return out;
  }
};
