// The beacon route is public and unauthenticated - it has to be, the page is
// public - so what protects the dataset is that every field is checked against
// what the page can actually send.  This exercises that: the shapes the page
// produces must be recorded exactly, and hostile ones must never reach the
// dataset as themselves.
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));
const source = readFileSync(join(here, '..', 'deploy', 'worker.js'), 'utf8');
// The worker is an ES module written for Cloudflare, not a package here.
const worker = (await import(
  'data:text/javascript;base64,' + Buffer.from(source).toString('base64')
)).default;

const BEACON = 'https://triglavmodular.hu/mods/218e-Rewired/beacon';
let failures = 0;

function check(name, ok, detail) {
  console.log(`  ${ok ? 'ok  ' : 'FAIL'}  ${name}${ok || !detail ? '' : ' - ' + detail}`);
  if (!ok) failures++;
}

// A dataset that remembers what it was told, standing in for the binding.
function fakeEnv() {
  const written = [];
  return { written, BUILDS: { writeDataPoint: (p) => written.push(p) } };
}

async function post(body, env) {
  return worker.fetch(new Request(BEACON, {
    method: 'POST',
    body: typeof body === 'string' ? body : JSON.stringify(body)
  }), env);
}

const REAL = {
  platform: 'win', version: '1.1.0', volts_per_octave: 1.2,
  latching_arp: true, remap_knobs: true, pressure_fix: true,
  pressure_portamento: false, alternate_tunings: 3, pitch_correction: true
};

{
  const env = fakeEnv();
  const res = await post(REAL, env);
  const p = env.written[0];
  check('a real download is recorded', env.written.length === 1);
  check('its answer carries no body', res.status === 204, `status ${res.status}`);
  check('the options land in order',
        p && p.blobs.join(',') === 'win,1.1.0,1.2'
        && p.doubles.join(',') === '1,1,1,0,3,1',
        p && `${p.blobs} / ${p.doubles}`);
}

{
  // The other platform, and the other pitch law, so neither is only ever
  // tested in its default.
  const env = fakeEnv();
  await post({ ...REAL, platform: 'mac', volts_per_octave: 1 }, env);
  check('1 V/oct is not confused with 1.2',
        env.written[0].blobs.join(',') === 'mac,1.1.0,1', env.written[0].blobs.join(','));
}

{
  // Everything a crafted body might try to put in the dataset.
  const env = fakeEnv();
  await post({
    platform: '"; DROP TABLE builds; --', version: '<script>alert(1)</script>',
    volts_per_octave: '1; DROP', latching_arp: 'yes',
    remap_knobs: 1, pressure_fix: null, pressure_portamento: {},
    alternate_tunings: 99999, pitch_correction: 'true'
  }, env);
  const p = env.written[0];
  check('hostile strings never reach the dataset',
        p.blobs.join(',') === 'other,other,other', p.blobs.join(','));
  check('a truthy non-boolean is not counted as chosen',
        p.doubles.slice(0, 4).join(',') === '0,0,0,0', p.doubles.join(','));
  check('a slot count out of range is marked, not stored',
        p.doubles[4] === -1, String(p.doubles[4]));
}

{
  const env = fakeEnv();
  await post('not json at all{{{', env);
  check('a body that is not JSON writes nothing', env.written.length === 0);
}

{
  const env = fakeEnv();
  await post(JSON.stringify(REAL) + ' '.repeat(4000), env);
  check('an oversized body writes nothing', env.written.length === 0);
}

{
  // The cap is on the body, so a single enormous field is dropped by it
  // before any field check has to deal with the size.
  const env = fakeEnv();
  await post({ ...REAL, version: 'x'.repeat(600) }, env);
  check('one enormous field is dropped with the body', env.written.length === 0);
}

{
  const env = fakeEnv();
  const res = await worker.fetch(new Request(BEACON), env);
  check('a GET is refused', res.status === 405, `status ${res.status}`);
  check('and records nothing', env.written.length === 0);
}

{
  // The binding is configured by hand in a dashboard, so its absence is a
  // real state.  It must cost the page nothing.
  const res = await post(REAL, {});
  check('no binding means no error', res.status === 204, `status ${res.status}`);
}

{
  // And the way it is actually got wrong: added as a runtime text variable
  // rather than a dataset binding, so BUILDS is the string "builds".  Truthy,
  // and with nothing to write a data point with.
  const res = await post(REAL, { BUILDS: 'builds' });
  check('a text variable named BUILDS does not throw',
        res.status === 204, `status ${res.status}`);
}

console.log(failures ? `\n  ${failures} failure(s)` : '\n  the beacon only records what the page can send');
process.exit(failures ? 1 : 0);
