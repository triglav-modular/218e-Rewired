// The watch on buchla.com, exercised without touching buchla.com.
//
// What it has to get right is not the happy path - that was read off the live
// site before any of it was written - but the days either side of it: the
// ordinary day when nothing moved and no notice is owed, the release, the
// re-upload that is not a release, and every way the check can fail without
// the failure passing for silence.  A watch that speaks every day is as broken
// as one that never says anything, so both directions are pinned here.
import { deflateRawSync, crc32 } from 'node:zlib';
import { watch, SEED as SHIPPED } from './watch-buchla.mjs';

let failures = 0;
function check(name, ok, detail) {
  console.log(`  ${ok ? 'ok  ' : 'FAIL'}  ${name}${ok || !detail ? '' : ' - ' + detail}`);
  if (!ok) failures++;
}

// A real zip, built here rather than mocked, so the central directory the
// worker walks is one an unzip would also accept.  The parser reads the file
// from its end and trusts the offsets it finds there; a hand-waved fixture
// would agree with a wrong parser just as readily as with a right one.
function makeZip(files) {
  const locals = [];
  const centrals = [];
  let offset = 0;
  for (const [name, content] of Object.entries(files)) {
    const raw = Buffer.from(content, 'utf8');
    const body = deflateRawSync(raw);
    const crc = crc32(raw);
    const nameBytes = Buffer.from(name, 'utf8');

    const local = Buffer.alloc(30);
    local.writeUInt32LE(0x04034b50, 0);
    local.writeUInt16LE(20, 4); local.writeUInt16LE(0, 6);
    local.writeUInt16LE(8, 8);                       // deflate
    local.writeUInt32LE(crc, 14);
    local.writeUInt32LE(body.length, 18);
    local.writeUInt32LE(raw.length, 22);
    local.writeUInt16LE(nameBytes.length, 26);
    local.writeUInt16LE(0, 28);
    locals.push(local, nameBytes, body);

    const central = Buffer.alloc(46);
    central.writeUInt32LE(0x02014b50, 0);
    central.writeUInt16LE(20, 4); central.writeUInt16LE(20, 6);
    central.writeUInt16LE(8, 10);
    central.writeUInt32LE(crc, 16);
    central.writeUInt32LE(body.length, 20);
    central.writeUInt32LE(raw.length, 24);
    central.writeUInt16LE(nameBytes.length, 28);
    central.writeUInt32LE(offset, 42);
    centrals.push(central, nameBytes);

    offset += local.length + nameBytes.length + body.length;
  }
  const directory = Buffer.concat(centrals);
  const end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054b50, 0);
  end.writeUInt16LE(Object.keys(files).length, 8);
  end.writeUInt16LE(Object.keys(files).length, 10);
  end.writeUInt32LE(directory.length, 12);
  end.writeUInt32LE(offset, 16);
  return Buffer.concat([...locals, directory, end]);
}

const CHANGELOG_369 = `v36.8 - March 2, 2024
--------------------------
- something older

v36.9 - May 9, 2024
--------------------------
- the version this repository is patched onto
`;

const CHANGELOG_370 = CHANGELOG_369 + `
v37.0 - June 1, 2026
--------------------------
- the release this watch exists to catch
- and a second line of it
`;

function zipFor(version, changelog) {
  const d = version.replace('.', '');
  return makeZip({
    '218ev3-Firmware-Flashing/changelog.txt': changelog,
    [`218ev3-Firmware-Flashing/mac/firmware/218eV3_v${d}_DFU.hex`]: ':00000001FF\n',
    [`__MACOSX/218ev3-Firmware-Flashing/mac/firmware/._218eV3_v${d}_DFU.hex`]: 'x',
  });
}

function pageWith(names) {
  return `<html><body>${names.map(n =>
    `<a href="../firmwarefiles/${n}">${n}</a>`).join('\n')}</body></html>`;
}

const FILES_53 = Array.from({ length: 52 }, (_, i) => `mod${i}.zip`)
  .concat(['218ev3-Firmware-Flashing.zip']).sort();

// The site, as far as the worker can tell.  Ranges are served off the zip
// buffer exactly as the origin serves them, 206 and all, so the worker's
// window arithmetic is exercised rather than assumed.
function site({ zip, files, etag, modified, length, headStatus = 200, pageStatus = 200 }) {
  const calls = { head: 0, page: 0, ranges: 0, bytes: 0 };
  globalThis.fetch = async (url, init) => {
    const u = String(url);
    if (u.includes('/download/')) {
      calls.page++;
      return new Response(pageStatus === 200 ? pageWith(files) : 'no',
                          { status: pageStatus });
    }
    if ((init && init.method) === 'HEAD') {
      calls.head++;
      return new Response(null, { status: headStatus, headers: {
        etag, 'last-modified': modified,
        'content-length': String(length === undefined ? zip.length : length) } });
    }
    const range = /bytes=(\d+)-(\d+)/.exec((init.headers || {}).range || '');
    calls.ranges++;
    const from = Number(range[1]);
    const to = Math.min(Number(range[2]), zip.length - 1);
    calls.bytes += to - from + 1;
    return new Response(zip.subarray(from, to + 1), { status: 206 });
  };
  return calls;
}

// Somewhere to keep the baseline, and something to send with.  Both are the
// seams the real run swaps for a file and a GitHub issue.
function fakeEnv(stored) {
  const sent = [];
  let held = stored ? JSON.parse(JSON.stringify(stored)) : null;
  return {
    sent,
    get held() { return held; },
    state: {
      read: async () => held,
      write: async (v) => { held = JSON.parse(JSON.stringify(v)); },
    },
    notify: async (subject, text) => { sent.push({ subject, text }); },
  };
}
const state = (env) => env.held;
const tick = (env) => watch({ state: env.state, notify: env.notify });

// The baseline the worker ships with, so a test can say "nothing has changed
// since the deploy" without restating it.
const SEED_ZIP = zipFor('36.9', CHANGELOG_369);
const SEED = {
  etag: '"seed"', modified: 'Fri, 11 Apr 2025 00:50:12 GMT',
  length: String(SEED_ZIP.length), version: '36.9',
  files: FILES_53, failures: 0,
};

console.log('The watch on buchla.com');

{
  // The ordinary day.  Nothing moved, so nothing is owed - and the zip is not
  // opened at all, which is the whole reason the poll is a HEAD.
  const env = fakeEnv(SEED);
  const calls = site({ zip: SEED_ZIP, files: FILES_53,
                       etag: SEED.etag, modified: SEED.modified });
  await tick(env);
  check('an unchanged day sends nothing', env.sent.length === 0);
  check('and never opens the zip', calls.ranges === 0, `${calls.ranges} range reads`);
}

{
  // The first quiet run has nothing stored yet, so it says nothing but does
  // record the file list - otherwise the seed's digest would be all there was
  // to compare against for ever, and a new file could be noticed but never
  // named.
  // With nothing stored, watch() compares against the seed the module ships -
  // which carries a digest and no filenames, so until a run writes the list
  // down a new file on the page can be noticed but never named.  Whatever the
  // first run decides to say, it has to leave that list behind.
  check('the shipped seed carries no file list', !Array.isArray(SHIPPED.files));
  const env = fakeEnv(null);
  site({ zip: SEED_ZIP, files: FILES_53, etag: SHIPPED.etag,
         modified: SHIPPED.modified, length: SHIPPED.length });
  await tick(env);
  check('the first run stores the file list',
        Array.isArray(state(env).files) && state(env).files.length === 53);
}

{
  // The release.
  const zip = zipFor('37.0', CHANGELOG_370);
  const env = fakeEnv(SEED);
  const calls = site({ zip, files: FILES_53, etag: '"new"',
                       modified: 'Mon, 01 Jun 2026 09:00:00 GMT' });
  await tick(env);
  check('a new firmware sends one notice', env.sent.length === 1);
  const notice = env.sent[0] || {};
  check('the subject carries both versions',
        notice.subject === 'Buchla 218e v3 firmware 36.9 -> 37.0', notice.subject);
  check('the body quotes only the new changelog section',
        notice.text.includes('the release this watch exists to catch')
        && !notice.text.includes('something older'));
  check('and says the patch has to be rebased',
        /rebasing it/.test(notice.text));
  check('the new version becomes the baseline', state(env).version === '37.0');
  // The point of the range reads: a 48 MB file is never fetched to learn this.
  check('the zip is read in kilobytes, not megabytes',
        calls.bytes < 200 * 1024, `${calls.bytes} bytes`);
}

{
  // The same bytes re-uploaded under a new etag.  Still worth knowing, but it
  // is not a release and must not be announced as one.
  const zip = zipFor('36.9', CHANGELOG_369);
  const env = fakeEnv(SEED);
  site({ zip, files: FILES_53, etag: '"reupload"', modified: SEED.modified });
  await tick(env);
  check('a re-upload is reported as itself',
        env.sent[0].subject === 'Buchla 218e v3 firmware re-uploaded (still 36.9)',
        env.sent[0].subject);
  check('and does not move the version', state(env).version === '36.9');
}

{
  // Another module. The version is in the filename there, so a new release is
  // a new link on the page and the zip never has to be opened.
  const env = fakeEnv(SEED);
  const calls = site({ zip: SEED_ZIP, files: [...FILES_53, '218Ev312.zip'].sort(),
                       etag: SEED.etag, modified: SEED.modified });
  await tick(env);
  check('a new file on the page is named in the subject',
        env.sent[0].subject === 'Buchla firmware downloads: 218Ev312.zip',
        env.sent[0].subject);
  check('and the 218e v3 zip is not opened for it', calls.ranges === 0);
  check('the new list is kept', state(env).files.includes('218Ev312.zip'));
}

{
  // A change caught against the shipped seed, which carries a digest and no
  // filenames.  It cannot name what appeared, and must not pretend to.
  const seeded = { ...SEED };
  delete seeded.files;
  seeded.digest = 'not the digest of anything below';
  const env = fakeEnv(seeded);
  site({ zip: SEED_ZIP, files: FILES_53, etag: SEED.etag, modified: SEED.modified });
  await tick(env);
  check('a change with no stored list is reported without naming files',
        env.sent.length === 1 && /no\s+stored list/.test(env.sent[0].text.replace(/\n/g, ' ')));
  check('and the list is stored from then on',
        Array.isArray(state(env).files) && state(env).files.length === 53);
}

{
  // Nothing is reported twice.  The second day sees the baseline the first day
  // wrote, not the seed.
  const zip = zipFor('37.0', CHANGELOG_370);
  const env = fakeEnv(SEED);
  site({ zip, files: FILES_53, etag: '"new"', modified: 'Mon, 01 Jun 2026 09:00:00 GMT' });
  await tick(env);
  await tick(env);
  check('the same release is not reported twice', env.sent.length === 1,
        `${env.sent.length} notices`);
}

{
  // A send that failed must not be recorded as a send that worked, or the
  // release is swallowed by the baseline and never mentioned again.
  const zip = zipFor('37.0', CHANGELOG_370);
  const env = fakeEnv(SEED);
  env.notify = async () => { throw new Error('the notice could not be delivered'); };
  site({ zip, files: FILES_53, etag: '"new"', modified: 'Mon, 01 Jun 2026 09:00:00 GMT' });
  await tick(env).catch(() => {});
  check('a notice that failed leaves the old baseline in place',
        state(env).version === '36.9' && state(env).etag === '"seed"');
}

{
  // The failure that matters: the check itself stops working.  Silence is
  // what "no new firmware" looks like too, so it cannot also be what a broken
  // watch looks like - but one bad afternoon is not worth a mail either.
  const env = fakeEnv(SEED);
  site({ zip: SEED_ZIP, files: FILES_53, etag: SEED.etag,
         modified: SEED.modified, pageStatus: 500 });
  for (let day = 1; day <= 6; day++) await tick(env).catch(() => {});
  check('six failed days stay quiet', env.sent.length === 0);
  check('but are counted', state(env).failures === 6, String(state(env).failures));
  await tick(env).catch(() => {});
  check('the seventh reports that the watch has stopped working',
        env.sent.length === 1
        && env.sent[0].subject === 'Buchla firmware watch has stopped working',
        env.sent[0] && env.sent[0].subject);
  await tick(env).catch(() => {});
  await tick(env).catch(() => {});
  check('and does not repeat every day after that', env.sent.length === 1,
        `${env.sent.length} notices`);
  // But it does say it again a week later.  The alarm is the only thing
  // standing between a broken watch and years of silence, so it must not be
  // spent on a single delivery that might itself have failed.
  for (let day = 10; day <= 14; day++) await tick(env).catch(() => {});
  check('the alarm comes back a week later', env.sent.length === 2,
        `${env.sent.length} notices after 14 failed days`);
  check('a failed check never moves the baseline',
        state(env).version === '36.9' && state(env).etag === '"seed"');
}

{
  // And recovery: the count has to come back to zero, or the next outage is
  // reported on its first day instead of its seventh.
  const env = fakeEnv({ ...SEED, failures: 6 });
  site({ zip: SEED_ZIP, files: FILES_53, etag: SEED.etag, modified: SEED.modified });
  await tick(env);
  check('a check that works again clears the failure count',
        state(env).failures === 0 && env.sent.length === 0);
}

{
  // A range read that answered 200 is the whole 48 MB arriving where a few
  // kilobytes were expected.  It must not be parsed as though it were the
  // tail - but the change is already known by then, so the mail still goes.
  const zip = zipFor('37.0', CHANGELOG_370);
  const env = fakeEnv(SEED);
  site({ zip, files: FILES_53, etag: '"new"', modified: 'Mon, 01 Jun 2026 09:00:00 GMT' });
  const ranged = globalThis.fetch;
  globalThis.fetch = async (url, init) => {
    if (init && init.headers && init.headers.range) {
      return new Response(zip, { status: 200 });
    }
    return ranged(url, init);
  };
  await tick(env);
  check('an origin that ignores range still produces a notice', env.sent.length === 1);
  check('and the notice says the version could not be read',
        /could not be\s+read/.test((env.sent[0].text || '').replace(/\n/g, ' ')),
        env.sent[0] && env.sent[0].subject);
}

{
  // A baseline that cannot be read is not an excuse to start from the seed
  // and announce a release that was already reported.  It fails the run.
  const env = fakeEnv(SEED);
  site({ zip: SEED_ZIP, files: FILES_53, etag: '"new"', modified: 'x' });
  env.state.read = async () => { throw new Error('the baseline is unreadable'); };
  let threw = null;
  await tick(env).catch(e => { threw = e; });
  check('an unreadable baseline fails the run rather than guessing',
        threw !== null && /unreadable/.test(threw.message), threw && threw.message);
  check('and sends nothing', env.sent.length === 0);
}

console.log(failures ? `\n${failures} failed` : '\nall passed');
process.exit(failures ? 1 : 0);
