// The watch on buchla.com, exercised without touching buchla.com.
//
// What it has to get right is not the happy path - that was read off the live
// site before any of it was written - but the days either side of it: the
// ordinary day when nothing moved and no mail is owed, the release, the
// re-upload that is not a release, and every way the check can fail without
// the failure passing for silence.  A watch that mails every day is as broken
// as one that never mails, so both directions are pinned here.
import { readFileSync } from 'node:fs';
import { deflateRawSync, crc32 } from 'node:zlib';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));
const source = readFileSync(join(here, '..', 'deploy', 'worker.js'), 'utf8');
const worker = (await import(
  'data:text/javascript;base64,' + Buffer.from(source).toString('base64')
)).default;

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
function site({ zip, files, etag, modified, headStatus = 200, pageStatus = 200 }) {
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
        etag, 'last-modified': modified, 'content-length': String(zip.length) } });
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

// The namespace, and the mail the worker thinks it sent.
function fakeEnv(stored) {
  const sent = [];
  const kv = new Map(stored ? [[
    'watch:buchla', JSON.stringify(stored)]] : []);
  return {
    sent, kv,
    WATCH_TO: 'owner@example.invalid',
    EMAIL: { send: async (m) => { sent.push(m); } },
    COUNTS: {
      get: async (k, t) => {
        const v = kv.get(k);
        return v === undefined ? null : (t === 'json' ? JSON.parse(v) : v);
      },
      put: async (k, v) => { kv.set(k, v); },
    },
  };
}
const state = (env) => JSON.parse(env.kv.get('watch:buchla'));
const tick = (env) => worker.scheduled({}, env, { waitUntil: () => {} });

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
  // The release.
  const zip = zipFor('37.0', CHANGELOG_370);
  const env = fakeEnv(SEED);
  const calls = site({ zip, files: FILES_53, etag: '"new"',
                       modified: 'Mon, 01 Jun 2026 09:00:00 GMT' });
  await tick(env);
  check('a new firmware sends one mail', env.sent.length === 1);
  const mail = env.sent[0] || {};
  check('the subject carries both versions',
        mail.subject === 'Buchla 218e v3 firmware 36.9 -> 37.0', mail.subject);
  check('the body quotes only the new changelog section',
        mail.text.includes('the release this watch exists to catch')
        && !mail.text.includes('something older'));
  check('and says the patch has to be rebased',
        /rebasing it/.test(mail.text));
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
  // Nothing is emailed twice.  The second day sees the baseline the first day
  // wrote, not the seed.
  const zip = zipFor('37.0', CHANGELOG_370);
  const env = fakeEnv(SEED);
  site({ zip, files: FILES_53, etag: '"new"', modified: 'Mon, 01 Jun 2026 09:00:00 GMT' });
  await tick(env);
  await tick(env);
  check('the same release is not mailed twice', env.sent.length === 1,
        `${env.sent.length} mails`);
}

{
  // A send that failed must not be recorded as a send that worked, or the
  // release is swallowed by the baseline and never mentioned again.
  const zip = zipFor('37.0', CHANGELOG_370);
  const env = fakeEnv(SEED);
  env.EMAIL.send = async () => { throw new Error('the mail bounced'); };
  site({ zip, files: FILES_53, etag: '"new"', modified: 'Mon, 01 Jun 2026 09:00:00 GMT' });
  await tick(env).catch(() => {});
  check('a failed send leaves the old baseline in place',
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
        `${env.sent.length} mails`);
  // But it does say it again a week later.  The alarm is the only thing
  // standing between a broken watch and years of silence, so it must not be
  // spent on a single delivery that might itself have failed.
  for (let day = 10; day <= 14; day++) await tick(env).catch(() => {});
  check('the alarm comes back a week later', env.sent.length === 2,
        `${env.sent.length} mails after 14 failed days`);
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
  check('an origin that ignores range still produces a mail', env.sent.length === 1);
  check('and the mail says the version could not be read',
        /could not be\s+read/.test((env.sent[0].text || '').replace(/\n/g, ' ')),
        env.sent[0] && env.sent[0].subject);
}

{
  // Both bindings are configured by hand outside this repository, so their
  // absence is a real state - and each has to fail loudly rather than quietly
  // do nothing, because quietly doing nothing is indistinguishable from
  // working.
  const env = fakeEnv(SEED);
  site({ zip: SEED_ZIP, files: FILES_53, etag: '"new"', modified: 'x' });
  delete env.COUNTS;
  let threw = null;
  await tick(env).catch(e => { threw = e; });
  check('no namespace fails the run rather than mailing daily for ever',
        threw !== null && /baseline/.test(threw.message), threw && threw.message);

  const env2 = fakeEnv(SEED);
  site({ zip: zipFor('37.0', CHANGELOG_370), files: FILES_53,
         etag: '"new"', modified: 'x' });
  delete env2.WATCH_TO;
  threw = null;
  await tick(env2).catch(e => { threw = e; });
  check('no recipient fails the run', threw !== null && /WATCH_TO/.test(threw.message));
  check('and does not record the change as reported',
        state(env2).version === '36.9');
}

console.log(failures ? `\n${failures} failed` : '\nall passed');
process.exit(failures ? 1 : 0);
