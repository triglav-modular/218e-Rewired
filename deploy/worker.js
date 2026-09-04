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
// The development branch, published by the same workflow into a subdirectory
// of the same Pages site, so it rides the same route and the same origin.
// Nothing under it is for search engines: the released page is the one that
// should be found.
const DEV = '/dev';

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
// The dev page posts to its own beacon, relative to itself.  Answered and
// dropped: the counts describe the released page, and a build of whatever
// the development branch held that afternoon is not one of those.
const DEV_BEACON = PUBLIC + DEV + '/beacon';

// Nothing here is trusted: it arrives from anyone who can reach the route.
// Every value is checked against what the page can actually send and dropped
// otherwise, so a crafted body can add noise to a count but cannot put
// arbitrary strings into the dataset.
const PLATFORMS = ['mac', 'win'];
const VOLTS = ['1', '1.2'];
// The roles each knob can take, as the page's own picker names them, plus
// 'factory' for a knob set to None - its preset voltage.
const KNOBS = {
  knob1: ['order', 'orders', 'factory'],
  knob2: ['spacing', 'swing', 'patterns', 'factory'],
  knob3: ['octaves', 'factory'],
  knob4: ['vibrato', 'trn', 'factory'],
};
// The most patterns the page lets into a bank.
const MAX_PATTERNS = 32;

function flag(value) {
  return value === true ? 1 : 0;
}

// For an option the page did not always send: a page older than the option
// reports nothing about it, which is not the same as reporting it off.  -1
// is "not reported", so the dashboard can count it out of the denominator
// rather than as a build that turned the option down.
function tri(value) {
  if (value === true) return 1;
  if (value === false) return 0;
  return -1;
}

// A knob role is one of the names above or it is not recorded as itself:
// '' when the page said nothing (older than the picker), 'other' when it
// said something the picker cannot say.
function role(knob, value) {
  if (value === undefined) return '';
  return KNOBS[knob].includes(value) ? value : 'other';
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
    // The page's body is a few hundred bytes.  Refused rather than
    // truncated: slicing and then parsing would accept whatever the first
    // kilobyte of a much larger body happened to spell.
    const text = await request.text();
    if (text.length > 1024) return new Response(null, { status: 204 });
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
  // The size of the pattern bank, never its contents.  Absent from a page
  // older than the bank, so like the flags it has a "not reported".
  const patterns = Number.isInteger(body.arp_patterns)
    && body.arp_patterns >= 0 && body.arp_patterns <= MAX_PATTERNS
    ? body.arp_patterns : -1;

  const point = {
    platform, version, volts,
    arp: flag(body.latching_arp),
    // Once a checkbox; the page now derives it - 1 when any knob does
    // something other than its preset voltage - so the column keeps counting
    // the same thing across the change.
    knobs: flag(body.remap_knobs),
    pressure: flag(body.pressure_fix),
    portamento: flag(body.pressure_portamento),
    tunings,
    calibration: flag(body.pitch_correction),
    // Added with firmware 2.x; every field from here on can be unreported.
    sequencer: tri(body.sequencer),
    clock_divide: tri(body.clock_divide),
    pitch_offset: tri(body.pitch_offset),
    knob1: role('knob1', body.knob1),
    knob2: role('knob2', body.knob2),
    knob3: role('knob3', body.knob3),
    knob4: role('knob4', body.knob4),
    patterns,
  };

  env.BUILDS.writeDataPoint({
    // One index, which Analytics Engine samples on.
    indexes: [platform],
    // Positional, and read back by position: the newer columns follow the
    // older ones so a row written before they existed still reads right.
    blobs: [platform, version, volts,
            point.knob1, point.knob2, point.knob3, point.knob4],
    doubles: [point.arp, point.knobs, point.pressure, point.portamento,
              tunings, point.calibration,
              point.sequencer, point.clock_divide, point.pitch_offset, patterns],
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
    if (url.pathname === DEV_BEACON) return new Response(null, { status: 204 });

    // Without the trailing slash every relative asset resolves into /mods/.
    // The dev page the same: the origin would answer its own redirect to the
    // github.io address, which fetch below follows silently, and the page
    // would come back under a URL its assets resolve wrongly against.
    if (url.pathname === PUBLIC || url.pathname === PUBLIC + DEV) {
      return Response.redirect(url.origin + url.pathname + '/', 301);
    }

    const rest = url.pathname.slice(PUBLIC.length);   // "/style.css", "/kit/..."
    // The route is PUBLIC followed by anything, so "/mods/218e-Rewiredx/.."
    // reaches here too; passed on, "x/.." was appended to the origin's
    // project name and asked GitHub Pages for a sibling project.
    if (rest !== '' && !rest.startsWith('/')) {
      return new Response('Not found', { status: 404,
                                         headers: { 'cache-control': 'no-store' } });
    }

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

    if (rest.startsWith(DEV + '/')) {
      out.headers.set('x-robots-tag', 'noindex, nofollow');
    }

    if (isPage || type.includes('text/html')) {
      // The page is the one file that cannot carry a version - it is the URL
      // people type - so it is the one that has to be checked every time.
      // no-cache, not no-store: it is still kept and still revalidated, so an
      // unchanged page costs a 304 rather than a download.  Asked first: a
      // page URL that happens to carry ?v= is still the page, and the rule
      // below would have pinned it in browsers for a year.
      out.headers.set('cache-control', 'no-cache');
    } else if (url.searchParams.has('v')) {
      // Everything the page asks for carries a hash of its own contents in
      // the URL, so this exact URL can never mean different bytes later.
      // Only when it worked: an origin 404 or 5xx stamped immutable would sit
      // in browsers for a year under the exact URL the page keeps asking for.
      if (ok) {
        out.headers.set('cache-control', 'public, max-age=31536000, immutable');
      } else {
        out.headers.set('cache-control', 'no-store');
      }
    }
    return out;
  }
};
