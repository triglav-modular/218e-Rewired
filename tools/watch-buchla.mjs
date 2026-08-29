// Watching buchla.com for a new stock firmware.
//
// This repository is a patch onto a named stock version, so a new one is work
// rather than only news - and nothing about buchla.com announces it.  The
// 218e v3 download is a version-less filename, and the page linking it prints
// no version either, so a release is the same URL answering with different
// bytes and nothing about the link says so.
//
// What says so is the answer to a HEAD: etag, last-modified and
// content-length all move when the file is replaced.  That is the whole poll,
// and on the ordinary day it transfers no body at all.
//
// Only once something has moved is the zip opened, and even then not
// downloaded.  The origin sends accept-ranges, and a zip keeps its index in
// its last bytes, so 64 KB off the end carries the central directory of all
// 81 entries - which gives both the .hex names and where changelog.txt sits.
// A second range read of about 2.5 KB brings the changelog itself.  The 48 MB
// of firmware is never fetched to find out that the firmware changed.
//
// The other modules are easier, and are watched from the download page: their
// filenames carry the version, so 218Ev309.zip -> 218Ev311.zip is a new link
// rather than new bytes behind an old one.
//
// Where the notice goes is deliberately not decided here.  watch() is handed
// somewhere to keep the baseline and something to send with, so the same
// checked logic can sit behind a GitHub issue, a mail, or anything else,
// without any of it being rewritten to move.
export const ZIP = 'https://buchla.com/firmwarefiles/218ev3-Firmware-Flashing.zip';
export const PAGE = 'https://buchla.com/download/';

// The end of a zip, and enough of it to hold the central directory of a file
// with this many entries.  11 KB covers the 81 it has now, with room to grow.
const WINDOW = 65536;

// How long a run of failed checks may pass in silence.  A watch that quietly
// stopped working is the one failure this must not have, because it looks
// exactly like "Buchla has released nothing" - which is also what it looks
// like when it is working perfectly.
export const QUIET = 7;

// buchla.com sits behind Flywheel, which turns down requests that arrive
// looking like nothing in particular - a datacenter address with a bare
// runtime's default user-agent answered 422 where the same code from a laptop
// got 200.  Saying plainly what this is and where it comes from is both what
// got it through and the polite thing for something that calls once a day.
const HEADERS = {
  'user-agent': 'buchla-firmware-watch/1 (+https://github.com/triglav-modular/218e-Rewired; daily check for new stock firmware)',
  'accept': '*/*',
  'accept-language': 'en',
};

// Where the watch starts from, read from the live site on 29 August 2026.
// Written out rather than left for the first run to discover, because a
// baseline the first run invents cannot report a change that happened before
// it ran: seeded this way, a release between this commit and the first tick
// is still caught.  The file list is a digest here because naming which of
// the 53 appeared is what the stored state is for - the seed only has to
// notice that they are not the same 53.
export const SEED = {
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
async function slice(url, from, to) {
  const res = await fetch(url, { headers: { ...HEADERS, range: `bytes=${from}-${to}` } });
  if (res.status !== 206) throw new Error(`range read answered ${res.status}`);
  return new Uint8Array(await res.arrayBuffer());
}

// The zip's own index, read from the end of the file.  Only what is needed to
// find an entry and then read it: the compression method, the compressed
// size, and where the local header sits.
export function index(tail, base) {
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
export async function entry(url, e) {
  const raw = await slice(url, e.header, e.header + e.compressed + 128);
  const view = new DataView(raw.buffer, raw.byteOffset, raw.byteLength);
  if (view.getUint32(0, true) !== 0x04034b50) throw new Error('not a local file header');
  const start = 30 + view.getUint16(26, true) + view.getUint16(28, true);
  const body = raw.subarray(start, start + e.compressed);
  if (e.method === 0) return new TextDecoder().decode(body);
  // Deflate as a zip stores it, with no zlib wrapper around it.
  return await new Response(new Blob([body]).stream()
    .pipeThrough(new DecompressionStream('deflate-raw'))).text();
}

// v369 in a .hex name is version 36.9: the last digit is the minor and the
// rest the major, which is how the changelog writes them too - v33.9, v34.0.
export function versionOf(names) {
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
export function filesOf(html) {
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
export function notesSince(changelog, known) {
  const heads = [...changelog.matchAll(/^v\d+\.\d+[^\n]*$/gm)];
  if (!heads.length) return '';
  const at = heads.findIndex(h => h[0].startsWith(`v${known} `)
                                  || h[0].trim() === `v${known}`);
  const from = at >= 0 && at + 1 < heads.length ? heads[at + 1].index
             : at >= 0 ? -1
             : heads[heads.length - 1].index;
  return from < 0 ? '' : changelog.slice(from).trim();
}

// One look at the site.  Everything that talks to buchla.com is in here, so a
// failure anywhere in it is one failure rather than a half-finished
// comparison.
async function look() {
  const head = await fetch(ZIP, { method: 'HEAD', headers: HEADERS });
  if (!head.ok) throw new Error(`HEAD answered ${head.status}`);
  const outside = {
    etag: head.headers.get('etag') || '',
    modified: head.headers.get('last-modified') || '',
    length: head.headers.get('content-length') || '',
  };

  // The page is a second, independent signal - it is how the other modules
  // are watched, since their version is in the filename.  It is also the half
  // more likely to be turned away, so it is allowed to fail without taking
  // the 218e v3 down with it.  Not silently: `pageError` is carried into the
  // baseline and alarms on its own schedule, because a page that has quietly
  // stopped being read looks exactly like a page where nothing is happening.
  let files = null, pageError = null;
  try {
    const page = await fetch(PAGE, { headers: HEADERS });
    if (!page.ok) throw new Error(`answered ${page.status}`);
    const found = filesOf(await page.text());
    if (!found.length) throw new Error('linked no firmware at all');
    files = found;
  } catch (e) {
    pageError = e.message;
  }

  return {
    ...outside,
    files,
    pageError,
    digest: files ? await sha256(files.join('\n')) : null,
  };
}

// What is inside the zip, fetched only once the outside has been seen to
// move.  A zip that cannot be read does not lose the notice: the change is
// already known, and this only says how to describe it.
async function inside(length, known) {
  const size = Number(length);
  if (!Number.isFinite(size) || size <= 0) return {};
  const base = Math.max(0, size - WINDOW);
  const entries = index(await slice(ZIP, base, size - 1), base);
  const names = [...entries.keys()];
  const version = versionOf(names);
  const key = names.find(n => /changelog\.txt$/i.test(n) && !n.includes('__MACOSX'));
  let notes = '';
  if (key) {
    try { notes = notesSince(await entry(ZIP, entries.get(key)), known); }
    catch { notes = ''; }
  }
  return { version, notes };
}

// One check.  `state` reads and writes the baseline, `notify` delivers the
// notice; neither knows anything about the other, and this knows nothing
// about either beyond those two calls.
export async function watch({ state, notify }) {
  const stored = await state.read();
  const was = stored || SEED;

  let now;
  try {
    now = await look();
  } catch (e) {
    // A check that failed says nothing about the firmware, so the baseline is
    // left exactly as it was and the next tick tries again.  Only the count
    // moves - and only a long run of them is worth saying out loud.
    const failures = (was.failures || 0) + 1;
    await state.write({ ...was, failures });
    // Every seventh failed day, not only the seventh.  A notice that itself
    // failed on the one day the alarm was due would otherwise lose the alarm
    // for good, and a watch still broken a fortnight later is worth repeating.
    if (failures % QUIET === 0) {
      await notify('Buchla firmware watch has stopped working',
        `The check has failed ${failures} days running.  The last error was:\n\n`
        + `    ${e.message}\n\n`
        + `Nothing is being watched until this is fixed, and a release would\n`
        + `pass unnoticed.  Check that these still answer:\n\n`
        + `    ${ZIP}\n    ${PAGE}\n`);
    }
    throw e;
  }

  const moved = now.etag !== was.etag || now.modified !== was.modified
             || now.length !== was.length;
  // Against the stored list where there is one, and against the seed's digest
  // where there is not.  The list is the better comparison because it can also
  // say which file appeared; the digest exists so that the very first run,
  // before any list has been stored, can still tell that the page has moved.
  // A page that could not be read this time compares against nothing.
  const listed = now.files !== null && (Array.isArray(was.files)
    ? now.files.join('\n') !== was.files.join('\n')
    : now.digest !== was.digest);

  // Counted separately from a check that failed outright, because this one
  // has not stopped the 218e v3 from being watched - only the other modules.
  const pageFailures = now.pageError ? (was.pageFailures || 0) + 1 : 0;

  // What the page last said survives a day it could not be read, so a blocked
  // afternoon does not throw away the list and then report all 53 as new.
  const next = {
    etag: now.etag,
    modified: now.modified,
    length: now.length,
    files: now.files || was.files,
    digest: now.files ? now.digest : was.digest,
    version: was.version,
    failures: 0,
    pageFailures,
  };

  // The page going quiet is its own alarm on its own schedule.  Silence here
  // reads exactly like "no other module has been updated", which is why it
  // cannot simply be swallowed - but it is not worth a notice on the first
  // afternoon Flywheel decides this looks like a robot.
  //
  // Once per outage, not once a week for ever: from a datacenter address this
  // is a standing condition rather than a fault, and a notice that repeats
  // until someone fixes something unfixable is one that gets filtered.  The
  // count resets on the first page that reads, so a later outage says so
  // again.  Unlike the failed-check alarm below, this one cannot be lost to a
  // failed send - the count only advances when the state is written, and that
  // happens after the notices have gone.
  const pageAlarm = now.pageError && pageFailures === QUIET
    ? [`Buchla download page has not been readable for ${pageFailures} days`,
       `The 218e v3 zip is still being watched - only the download page is `
       + `not.\n\nThat page is how every other module is watched, since their `
       + `version is\nin the filename, so those are currently unwatched.  The `
       + `last error was:\n\n    ${now.pageError}\n\n    ${PAGE}\n`]
    : null;

  if (!moved && !listed && !pageAlarm) {
    // Nothing happened, which is almost every day, and a quiet year should be
    // a quiet history - so the baseline is rewritten only when it would
    // actually differ.  The first run is the exception: the seed carries a
    // digest and no filenames, and until the list itself is stored a new file
    // on the page can be noticed but not named.
    if (!stored || was.failures || pageFailures !== (was.pageFailures || 0)) {
      await state.write(next);
    }
    return null;
  }

  const lines = [];
  let subject;

  if (moved) {
    const seen = await inside(now.length, was.version).catch(() => ({}));
    const version = seen.version || null;
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
    lines.push('', `    ${ZIP}`, '');
    lines.push(`    last-modified  ${was.modified}  ->  ${now.modified}`);
    lines.push(`    length         ${was.length}  ->  ${now.length}`);
    lines.push(`    etag           ${was.etag}  ->  ${now.etag}`);
    if (seen.notes) lines.push('', 'From the changelog inside the zip:', '', seen.notes);
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
             + ` where it linked ${before ? before.length : '53'}.`);
    if (added.length) lines.push('', 'Added:', ...added.map(f => `    ${f}`));
    if (gone.length) lines.push('', 'Gone:', ...gone.map(f => `    ${f}`));
    if (!before) lines.push('', `The file list changed, but this watch had no`
                              + ` stored list to name them against - it does now.`);
    lines.push('', `    ${PAGE}`);
  }

  if (subject) await notify(subject, lines.join('\n') + '\n');
  if (pageAlarm) await notify(pageAlarm[0], pageAlarm[1]);
  // Written only after the notice is away.  One that threw leaves the old
  // baseline in place, so tomorrow reports the same change rather than
  // swallowing it.
  await state.write(next);
  return subject || pageAlarm[0];
}
