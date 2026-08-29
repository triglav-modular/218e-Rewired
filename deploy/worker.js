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
//
// It also carries the daily watch on buchla.com, which has nothing to do with
// the proxy beyond being the one thing already deployed that runs without
// this Mac being awake.  See "Watching buchla.com" further down.
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

// ---------------------------------------------------------------------------
// Watching buchla.com for a new stock firmware.
//
// The 218e v3 download is a version-less filename - 218ev3-Firmware-Flashing
// .zip - and the page that links it prints no version either.  The version is
// only inside the zip: in the name of the .hex it carries
// (218eV3_v369_DFU.hex) and in its changelog.txt.  So a new release looks
// like the same URL answering with different bytes, and nothing about the
// link says so.
//
// What says so is the answer to a HEAD.  The origin sends etag, last-modified
// and content-length, and all three move when the file is replaced.  That is
// the whole poll: no body at all on the ordinary day when nothing happened.
//
// Only once something has moved is the zip opened, and even then not
// downloaded.  The origin sends accept-ranges, and a zip keeps its index in
// the last bytes of the file, so 64 KB off the end carries the central
// directory of all 81 entries - which gives both the .hex names and where
// changelog.txt sits.  A second range read of about 2.5 KB brings the
// changelog itself.  The 48 MB of firmware is never fetched to find out that
// the firmware changed.
//
// The other modules are easier, and are watched from the download page: their
// filenames carry the version, so 218Ev309.zip -> 218Ev311.zip is a new link
// rather than new bytes behind an old one.
const WATCH_ZIP = 'https://buchla.com/firmwarefiles/218ev3-Firmware-Flashing.zip';
const WATCH_PAGE = 'https://buchla.com/download/';
// The baseline lives in the namespace the beacon already writes to, under a
// key that cannot be mistaken for one.  Downloads are "b:<iso>:<rand>" and are
// read back a month at a time by prefix, so a "watch:" key falls in no range
// that reader asks for - and unlike those, this one never expires.
const WATCH_KEY = 'watch:buchla';
const WATCH_FROM = 'firmware-watch@triglavmodular.hu';

// The end of a zip, and enough of it to hold the central directory of a file
// with this many entries.  10 KB covers the 81 it has now with room to grow.
const WATCH_WINDOW = 65536;

// How long a run of failed checks may pass in silence.  A watch that quietly
// stopped working is the one failure this must not have, because it looks
// exactly like "Buchla has released nothing" - which is also what it looks
// like when it is working perfectly.  The site being restructured, or the
// file moving, ends as a mail rather than as years of nothing.
const WATCH_QUIET = 7;

// Where the watch starts from, read from the live site on 29 August 2026.
// Written out rather than left for the first run to discover, because a
// baseline the first run invents cannot report a change that happened before
// it ran: seeded this way, a release between this deploy and the first tick
// is still caught.  The file list is a digest here because naming which of
// the 53 appeared is what the stored state is for - the seed only has to
// notice that they are not the same 53.
const WATCH_SEED = {
  etag: '"67f86744-2dd741a"',
  modified: 'Fri, 11 Apr 2025 00:50:12 GMT',
  length: '48067610',
  version: '36.9',
  digest: '3831bac3117bd9f7ec11a0a2bd6e9ef8fd584c4267281eb9c8e708ee4ad7b534',
  failures: 0,
};

async function sha256(text) {
  const hash = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(text));
  return [...new Uint8Array(hash)].map(b => b.toString(16).padStart(2, '0')).join('');
}

// A range read.  The origin answers 206 with exactly the bytes asked for;
// anything else is a failed read rather than something to parse, because a
// 200 here is the whole 48 MB arriving where a few KB were expected.
async function watchSlice(url, from, to) {
  const res = await fetch(url, { headers: { range: `bytes=${from}-${to}` } });
  if (res.status !== 206) throw new Error(`range read answered ${res.status}`);
  return new Uint8Array(await res.arrayBuffer());
}

// The zip's own index, read from the end of the file.  Only what is needed to
// find an entry and then read it: the compression method, the compressed
// size, and where the local header sits.
function watchIndex(tail, base) {
  const view = new DataView(tail.buffer, tail.byteOffset, tail.byteLength);
  // The end-of-central-directory record is last, behind a comment that is
  // almost always empty, so it is found by scanning back for its signature.
  let eocd = -1;
  for (let i = tail.length - 22; i >= 0; i--) {
    if (view.getUint32(i, true) === 0x06054b50) { eocd = i; break; }
  }
  if (eocd < 0) throw new Error('no end-of-central-directory record');
  const size = view.getUint32(eocd + 12, true);
  const start = view.getUint32(eocd + 16, true) - base;
  const end = start + size;
  // A directory beginning before the window means the tail was cut too short.
  // That is a failed read, not something to parse the surviving half of.
  if (start < 0 || end > tail.length) throw new Error('central directory outside the window');

  const entries = new Map();
  const text = new TextDecoder();
  let p = start;
  while (p < end && view.getUint32(p, true) === 0x02014b50) {
    const name = view.getUint16(p + 28, true);
    const extra = view.getUint16(p + 30, true);
    const comment = view.getUint16(p + 32, true);
    entries.set(text.decode(tail.subarray(p + 46, p + 46 + name)), {
      method: view.getUint16(p + 10, true),
      compressed: view.getUint32(p + 20, true),
      header: view.getUint32(p + 42, true),
    });
    p += 46 + name + extra + comment;
  }
  return entries;
}

// One entry's contents.  The local header repeats the name and carries an
// extra field of its own, whose length is the one thing the central directory
// does not record - so the header is read along with the data and stepped
// over rather than guessed at.
async function watchEntry(url, entry) {
  const raw = await watchSlice(url, entry.header,
                               entry.header + entry.compressed + 128);
  const view = new DataView(raw.buffer, raw.byteOffset, raw.byteLength);
  if (view.getUint32(0, true) !== 0x04034b50) throw new Error('not a local file header');
  const start = 30 + view.getUint16(26, true) + view.getUint16(28, true);
  const body = raw.subarray(start, start + entry.compressed);
  if (entry.method === 0) return new TextDecoder().decode(body);
  // Deflate as a zip stores it, with no zlib wrapper around it.
  return await new Response(new Blob([body]).stream()
    .pipeThrough(new DecompressionStream('deflate-raw'))).text();
}

// v369 in a .hex name is version 36.9: the last digit is the minor and the
// rest the major, which is how the changelog writes them too - v33.9, v34.0.
function watchVersion(names) {
  for (const name of names) {
    if (!/\.hex$/i.test(name) || name.includes('__MACOSX')) continue;
    const digits = /_v(\d{2,4})_/.exec(name);
    if (digits) return `${Number(digits[1].slice(0, -1))}.${digits[1].slice(-1)}`;
  }
  return null;
}

// Every firmware the download page links, reduced to bare filenames, sorted
// and de-duplicated - so the comparison is against a set rather than against
// whatever order a WordPress page happened to render them in.
function watchFiles(html) {
  const names = new Set();
  const link = /firmwarefiles\/([^"'?#\s>]+)/gi;
  let m;
  while ((m = link.exec(html))) names.add(m[1]);
  return [...names].sort();
}

// The sections of the changelog newer than the version already known about.
// It is written oldest first, so everything after that heading is what has
// been added since.  An unrecognised version means no assumption about how
// much is new - the last section is quoted, and no more.
function watchNotes(changelog, known) {
  const heads = [...changelog.matchAll(/^v\d+\.\d+[^\n]*$/gm)];
  if (!heads.length) return '';
  const at = heads.findIndex(h => h[0].startsWith(`v${known} `)
                                  || h[0].trim() === `v${known}`);
  const from = at >= 0 && at + 1 < heads.length ? heads[at + 1].index
             : at >= 0 ? -1
             : heads[heads.length - 1].index;
  return from < 0 ? '' : changelog.slice(from).trim();
}

// One check.  Everything that talks to buchla.com is in here, so a failure
// anywhere in it is one failure rather than a half-finished comparison.
async function watchLook() {
  const head = await fetch(WATCH_ZIP, { method: 'HEAD' });
  if (!head.ok) throw new Error(`HEAD answered ${head.status}`);
  const page = await fetch(WATCH_PAGE);
  if (!page.ok) throw new Error(`the download page answered ${page.status}`);
  const files = watchFiles(await page.text());
  if (!files.length) throw new Error('the download page linked no firmware at all');
  return {
    etag: head.headers.get('etag') || '',
    modified: head.headers.get('last-modified') || '',
    length: head.headers.get('content-length') || '',
    files,
    digest: await sha256(files.join('\n')),
  };
}

// What is inside the zip, fetched only once the outside has been seen to
// move.  A zip that cannot be read does not lose the mail: the change is
// already known, and this only says how to describe it.
async function watchInside(length, known) {
  const size = Number(length);
  if (!Number.isFinite(size) || size <= 0) return {};
  const base = Math.max(0, size - WATCH_WINDOW);
  const entries = watchIndex(await watchSlice(WATCH_ZIP, base, size - 1), base);
  const names = [...entries.keys()];
  const version = watchVersion(names);
  const key = names.find(n => /changelog\.txt$/i.test(n) && !n.includes('__MACOSX'));
  let notes = '';
  if (key) {
    try {
      notes = watchNotes(await watchEntry(WATCH_ZIP, entries.get(key)), known);
    } catch (e) { notes = ''; }
  }
  return { version, notes };
}

async function watchSend(env, subject, body) {
  // The address is a secret rather than a variable: this repository is
  // public, and the one thing in this whole file worth keeping out of it is
  // where the mail goes.  Without it there is nothing to send to, and that is
  // a misconfiguration worth seeing in the logs rather than swallowing.
  if (!env.WATCH_TO) throw new Error('WATCH_TO is not set, so nothing was sent');
  if (typeof env?.EMAIL?.send !== 'function') {
    throw new Error('no EMAIL binding, so nothing was sent');
  }
  await env.EMAIL.send({
    to: env.WATCH_TO,
    from: { email: WATCH_FROM, name: 'Buchla firmware watch' },
    subject,
    text: body,
    // Plain text in a <pre>: the mail is a changelog and a few URLs, and
    // reflowing either of those helps nobody.
    html: `<pre style="font: 13px/1.5 ui-monospace, monospace; white-space: pre-wrap">`
        + body.replace(/[&<>]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;' }[c]))
        + `</pre>`,
  });
}

async function watch(env) {
  // Refused rather than run without somewhere to remember: the baseline is
  // what makes a change a change.  Without it every tick would compare
  // against the seed and mail the same release every day for ever, which is
  // worse than not watching at all.
  if (typeof env?.COUNTS?.get !== 'function') {
    throw new Error('no COUNTS binding, so there is nowhere to keep the baseline');
  }
  const was = await env.COUNTS.get(WATCH_KEY, 'json') || WATCH_SEED;

  let now;
  try {
    now = await watchLook();
  } catch (e) {
    // A check that failed says nothing about the firmware, so the baseline is
    // left exactly as it was and the next tick tries again.  Only the count
    // moves - and only a long run of them is worth a mail.
    const failures = (was.failures || 0) + 1;
    await env.COUNTS.put(WATCH_KEY, JSON.stringify({ ...was, failures }));
    // Every seventh failed day, not only the seventh.  A send that itself
    // failed on the one day the alarm was due would otherwise lose the alarm
    // for good, and a watch that is still broken a fortnight later is worth
    // saying again.
    if (failures % WATCH_QUIET === 0) {
      await watchSend(env, 'Buchla firmware watch has stopped working',
        `The check has failed ${failures} days running.  The last error was:\n\n`
        + `    ${e.message}\n\n`
        + `Nothing is being watched until this is fixed, and a release would\n`
        + `pass unnoticed.  Check that these still answer:\n\n`
        + `    ${WATCH_ZIP}\n    ${WATCH_PAGE}\n`);
    }
    throw e;
  }

  const moved = now.etag !== was.etag || now.modified !== was.modified
             || now.length !== was.length;
  // Against the stored list where there is one, and against the seed's digest
  // where there is not.  The list is the better comparison because it can also
  // say which file appeared; the digest exists so that the very first run,
  // before any list has been stored, can still tell that the page has moved.
  const listed = Array.isArray(was.files)
    ? now.files.join('\n') !== was.files.join('\n')
    : now.digest !== was.digest;

  const next = { ...now, version: was.version, failures: 0 };

  if (!moved && !listed) {
    // Nothing happened, which is almost every day.  The state is still
    // written, because the failure count has to come back to zero.
    if (was.failures) await env.COUNTS.put(WATCH_KEY, JSON.stringify(next));
    return 'unchanged';
  }

  const lines = [];
  let subject;

  if (moved) {
    const inside = await watchInside(now.length, was.version).catch(() => ({}));
    const version = inside.version || null;
    next.version = version || was.version;

    subject = version && version !== was.version
      ? `Buchla 218e v3 firmware ${was.version} -> ${version}`
      : `Buchla 218e v3 firmware re-uploaded (still ${was.version})`;

    lines.push(version && version !== was.version
      ? `The 218e v3 firmware is now v${version}, up from v${was.version}.`
      : version
        ? `The 218e v3 zip was replaced, but it still carries v${version}.`
        : `The 218e v3 zip was replaced.  The version inside it could not be`
          + ` read, so it is worth opening by hand.`);
    lines.push('', `    ${WATCH_ZIP}`, '');
    lines.push(`    last-modified  ${was.modified}  ->  ${now.modified}`);
    lines.push(`    length         ${was.length}  ->  ${now.length}`);
    lines.push(`    etag           ${was.etag}  ->  ${now.etag}`);
    if (inside.notes) lines.push('', 'From the changelog inside the zip:', '', inside.notes);
    // The repository is built on a named stock version, so a new one is work
    // rather than only news.
    if (version && version !== was.version) {
      lines.push('', `218e Rewired is patched onto v${was.version}.  A new stock`
                   + ` version means rebasing it.`);
    }
  }

  if (listed) {
    // Named only when there is a list to name them against.  The seeded
    // baseline carries a digest and no filenames, so the first change it
    // catches is reported as a change and the list is kept from then on.
    const before = Array.isArray(was.files) ? was.files : null;
    const added = before ? now.files.filter(f => !before.includes(f)) : [];
    const gone = before ? before.filter(f => !now.files.includes(f)) : [];
    if (!subject) {
      subject = added.length
        ? `Buchla firmware downloads: ${added.join(', ')}`
        : `Buchla firmware downloads changed`;
    }
    if (lines.length) lines.push('', '---', '');
    lines.push(`The download page now links ${now.files.length} firmware files,`
             + ` where it linked ${before ? before.length : was.count || '?'}.`);
    if (added.length) lines.push('', 'Added:', ...added.map(f => `    ${f}`));
    if (gone.length) lines.push('', 'Gone:', ...gone.map(f => `    ${f}`));
    if (!before) lines.push('', `The file list changed, but this watch had no`
                              + ` stored list to name them against - it does now.`);
    lines.push('', `    ${WATCH_PAGE}`);
  }

  await watchSend(env, subject, lines.join('\n') + '\n');
  // Written only after the mail is away.  A send that threw leaves the old
  // baseline in place, so tomorrow reports the same change rather than
  // swallowing it.
  await env.COUNTS.put(WATCH_KEY, JSON.stringify(next));
  return subject;
}

export default {
  // One tick a day, from the cron trigger in wrangler.toml.  Nothing routes
  // here - the schedule is the only way in.
  //
  // Awaited rather than handed to waitUntil: a check that failed should fail
  // the invocation, so it lands in the logs this worker already has turned on
  // instead of disappearing into a background promise.
  async scheduled(event, env, context) {
    await watch(env);
  },

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
