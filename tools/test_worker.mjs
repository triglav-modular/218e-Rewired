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

// Bindings that remember what they were told: the dataset, and the namespace
// the dashboard reads back without a credential.
function fakeEnv() {
  const written = [];
  const keys = [];
  return {
    written, keys,
    BUILDS: { writeDataPoint: (p) => written.push(p) },
    COUNTS: { put: async (name, value, opts) => keys.push({ name, value, opts }) },
  };
}

// waitUntil is the runtime's, not ours - awaited here so the test sees the
// write the runtime would have finished after the response went out.
const pending = [];
const ctx = { waitUntil: (p) => pending.push(p) };

async function post(body, env) {
  const res = await worker.fetch(new Request(BEACON, {
    method: 'POST',
    body: typeof body === 'string' ? body : JSON.stringify(body)
  }), env, ctx);
  await Promise.all(pending.splice(0));
  return res;
}

const REAL = {
  platform: 'win', version: '2.2.0', volts_per_octave: 1.2,
  latching_arp: true, remap_knobs: true, pressure_fix: true,
  pressure_portamento: false, alternate_tunings: 3, pitch_correction: true,
  sequencer: true, clock_divide: false, pitch_offset: false,
  knob1: 'orders', knob2: 'patterns', knob3: 'octaves', knob4: 'factory',
  arp_patterns: 22
};

// What a page from before the 2.x options sends: the first nine values and
// nothing else.
const OLD = {
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
        p && p.blobs.join(',') === 'win,2.2.0,1.2,orders,patterns,octaves,factory'
        && p.doubles.join(',') === '1,1,1,0,3,1,1,0,0,22',
        p && `${p.blobs} / ${p.doubles}`);

  // The same download, in the form the dashboard can read without a token.
  const k = env.keys[0];
  check('it is also written where it can be read back', env.keys.length === 1);
  check('keyed by the time it happened, so a window is a string compare',
        k && /^b:\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z:[a-z0-9]{4,8}$/.test(k.name),
        k && k.name);
  check('the whole point rides in the metadata, so listing is enough',
        k && k.opts.metadata.platform === 'win'
        && k.opts.metadata.tunings === 3
        && k.opts.metadata.calibration === 1
        && k.opts.metadata.sequencer === 1
        && k.opts.metadata.clock_divide === 0
        && k.opts.metadata.pitch_offset === 0
        && k.opts.metadata.knob2 === 'patterns'
        && k.opts.metadata.knob4 === 'factory'
        && k.opts.metadata.patterns === 22,
        k && JSON.stringify(k.opts.metadata));
  check('and it is small enough for KV metadata',
        k && JSON.stringify(k.opts.metadata).length <= 1024,
        k && String(JSON.stringify(k.opts.metadata).length));
  check('and it ages out rather than accumulating forever',
        k && k.opts.expirationTtl > 86400, k && String(k.opts.expirationTtl));
}

{
  // A page older than the 2.x options says nothing about them.  Nothing is
  // not "off": each has to be recorded as unreported, or every old build
  // reads as having turned the sequencer down and chosen the 208c offset.
  const env = fakeEnv();
  await post(OLD, env);
  const p = env.written[0];
  const m = env.keys[0].opts.metadata;
  check('the first nine values still land where they always did',
        p.blobs.slice(0, 3).join(',') === 'win,1.1.0,1.2'
        && p.doubles.slice(0, 6).join(',') === '1,1,1,0,3,1',
        `${p.blobs} / ${p.doubles}`);
  check('an unreported flag is -1, not off',
        p.doubles.slice(6).join(',') === '-1,-1,-1,-1'
        && m.sequencer === -1 && m.clock_divide === -1
        && m.pitch_offset === -1 && m.patterns === -1,
        `${p.doubles.slice(6)} / ${JSON.stringify(m)}`);
  check('an unreported knob role is empty, not factory',
        p.blobs.slice(3).join(',') === ',,,' && m.knob1 === '' && m.knob4 === '',
        `${JSON.stringify(p.blobs.slice(3))}`);
}

{
  // Every knob on None: the page names each factory and reports no remap.
  const env = fakeEnv();
  await post({ ...REAL, remap_knobs: false,
               knob1: 'factory', knob2: 'factory', knob3: 'factory', knob4: 'factory',
               arp_patterns: 0 }, env);
  const m = env.keys[0].opts.metadata;
  check('a knob handed back is recorded as factory',
        m.knobs === 0 && m.knob1 === 'factory' && m.knob2 === 'factory'
        && m.patterns === 0, JSON.stringify(m));
}

{
  // The namespace is the newer half; a worker deployed before it existed, or
  // one whose binding went missing, must still record what it can.
  const env = fakeEnv();
  delete env.COUNTS;
  const res = await post(REAL, env);
  check('no namespace still writes the dataset',
        res.status === 204 && env.written.length === 1);
}

{
  // KV is a network call.  A namespace having a bad day must not turn a
  // download into an error.
  const env = fakeEnv();
  env.COUNTS = { put: async () => { throw new Error('KV is unhappy'); } };
  const res = await post(REAL, env);
  check('a namespace that refuses does not fail the download',
        res.status === 204 && env.written.length === 1);
}

{
  // The other platform, and the other pitch law, so neither is only ever
  // tested in its default.
  const env = fakeEnv();
  await post({ ...REAL, platform: 'mac', volts_per_octave: 1 }, env);
  check('1 V/oct is not confused with 1.2',
        env.written[0].blobs.slice(0, 3).join(',') === 'mac,2.2.0,1',
        env.written[0].blobs.join(','));
}

{
  // Everything a crafted body might try to put in the dataset.
  const env = fakeEnv();
  await post({
    platform: '"; DROP TABLE builds; --', version: '<script>alert(1)</script>',
    volts_per_octave: '1; DROP', latching_arp: 'yes',
    remap_knobs: 1, pressure_fix: null, pressure_portamento: {},
    alternate_tunings: 99999, pitch_correction: 'true',
    sequencer: 'on', clock_divide: 1, pitch_offset: 'false',
    knob1: '<b>', knob2: 'patterns; DROP', knob3: 'vibrato', knob4: 42,
    arp_patterns: 33
  }, env);
  const p = env.written[0];
  check('hostile strings never reach the dataset',
        p.blobs.join(',') === 'other,other,other,other,other,other,other',
        p.blobs.join(','));
  check('a truthy non-boolean is not counted as chosen',
        p.doubles.slice(0, 4).join(',') === '0,0,0,0', p.doubles.join(','));
  check('a slot count out of range is marked, not stored',
        p.doubles[4] === -1, String(p.doubles[4]));
  check('a truthy non-boolean 2.x flag is unreported, not chosen',
        p.doubles.slice(6, 9).join(',') === '-1,-1,-1', p.doubles.join(','));
  check('a role from the wrong knob is refused too',
        p.blobs[5] === 'other', p.blobs[5]);
  check('a bank bigger than the page allows is marked, not stored',
        p.doubles[9] === -1, String(p.doubles[9]));
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
  await post({ ...REAL, version: 'x'.repeat(1200) }, env);
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

// The proxy's cache rules.  Wrong ones do not fail - they quietly serve the
// wrong bytes for a very long time - so each is pinned.
{
  const realFetch = globalThis.fetch
  let origin = () => new Response('body', { status: 200 })
  globalThis.fetch = async () => origin()
  const get = (path) => worker.fetch(
    new Request('https://triglavmodular.hu/mods/218e-Rewired' + path), {})

  origin = () => new Response('ok', { status: 200 })
  let res = await get('/style.css?v=abc12345')
  check('a versioned asset that worked is immutable',
        (res.headers.get('cache-control') || '').includes('immutable'))

  // A resumed download's if-range must reach the origin, or a changed file
  // is stitched from halves of two versions.
  let sawHeaders = null
  globalThis.fetch = async (url, init) => {
    sawHeaders = init && init.headers
    return new Response('ok', { status: 200 })
  }
  await worker.fetch(new Request(
    'https://triglavmodular.hu/mods/218e-Rewired/kit/mac/Flasher.zip',
    { headers: { range: 'bytes=100-', 'if-range': '"etag123"' } }), {})
  check('range and if-range travel together',
        sawHeaders && sawHeaders.get('range') === 'bytes=100-'
        && sawHeaders.get('if-range') === '"etag123"',
        sawHeaders && JSON.stringify([...sawHeaders]))
  globalThis.fetch = async () => origin()

  origin = () => new Response('not here', { status: 404 })
  res = await get('/style.css?v=abc12345')
  check('a versioned 404 is never cached',
        res.headers.get('cache-control') === 'no-store',
        res.headers.get('cache-control'))
  origin = () => new Response('broken', { status: 502 })
  res = await get('/style.css?v=abc12345')
  check('a versioned 5xx is never cached',
        res.headers.get('cache-control') === 'no-store')

  origin = () => new Response('<html>', { status: 200,
    headers: { 'content-type': 'text/html; charset=utf-8',
               'cache-control': 'max-age=600' } })
  res = await get('/')
  check('the page revalidates every time',
        res.headers.get('cache-control') === 'no-cache')

  // GitHub Pages 304s carry no content-type but do carry max-age=600; the
  // rule has to hold by path or the browser overwrites no-cache with it.
  origin = () => new Response(null, { status: 304,
    headers: { 'cache-control': 'max-age=600', etag: '"x"' } })
  res = await get('/')
  check('a page 304 keeps no-cache rather than the origin max-age',
        res.headers.get('cache-control') === 'no-cache',
        res.headers.get('cache-control'))
  res = await get('/style.css?v=abc12345')
  check('a versioned 304 renews the immutable lifetime',
        (res.headers.get('cache-control') || '').includes('immutable'))

  // The page is the page whatever its query string says: a shared link
  // with ?v= on it must not become immutable for a year.
  origin = () => new Response('<html>', { status: 200,
    headers: { 'content-type': 'text/html; charset=utf-8' } })
  res = await get('/?v=abc12345')
  check('a page URL carrying ?v= still revalidates',
        res.headers.get('cache-control') === 'no-cache',
        res.headers.get('cache-control'))
  res = await get('/index.html?v=abc12345')
  check('so does index.html with ?v=',
        res.headers.get('cache-control') === 'no-cache',
        res.headers.get('cache-control'))

  // The route matches everything that starts with the prefix, so a path
  // that merely starts with it must be refused, not passed to the origin
  // as a sibling project.
  let fetched = 0
  globalThis.fetch = async () => { fetched++; return new Response('ok', { status: 200 }) }
  res = await worker.fetch(
    new Request('https://triglavmodular.hu/mods/218e-Rewiredx/style.css'), {})
  check('a sibling path under the route is refused',
        res.status === 404 && fetched === 0, `status ${res.status}, fetched ${fetched}`)
  res = await worker.fetch(
    new Request('https://triglavmodular.hu/mods/218e-Rewired/style.css'), {})
  check('while the real path still reaches the origin',
        res.status === 200 && fetched === 1, `status ${res.status}, fetched ${fetched}`)

  globalThis.fetch = realFetch
}

// The deploy is configured by wrangler.toml now, so the two can drift: a
// route that no longer matches what the worker answers on, or a binding
// renamed on one side only, would deploy green and record nothing.
{
  const toml = readFileSync(join(here, '..', 'wrangler.toml'), 'utf8');
  const value = (key) => (toml.match(new RegExp(`^${key}\\s*=\\s*"([^"]+)"`, 'm')) || [])[1];

  check('the deploy points at this worker',
        value('main') === 'deploy/worker.js', value('main'));
  // The dataset may be commented out - the worker copes, and serving the
  // page beats counting - but if it is declared it must carry the name the
  // code reads, or the deploy is green and nothing is ever recorded.
  const declared = /^\s*\[\[analytics_engine_datasets\]\]/m.test(toml);
  check('a declared dataset is bound under the name the worker reads',
        !declared || /^\s*binding\s*=\s*"BUILDS"/m.test(toml),
        'analytics_engine_datasets is declared but not as BUILDS');
  if (!declared) console.log('        (dataset commented out - counting is off)');

  // The route has to cover the path the beacon is posted to, or the request
  // never reaches the worker at all.
  const route = (toml.match(/pattern\s*=\s*"([^"]+)"/) || [])[1] || '';
  const prefix = (source.match(/const PUBLIC = '([^']+)'/) || [])[1];
  check('the route covers the path the worker serves',
        prefix && route.includes(prefix) && route.endsWith('*'),
        `route ${route} vs PUBLIC ${prefix}`);
}

console.log(failures ? `\n  ${failures} failure(s)` : '\n  the beacon only records what the page can send, and the deploy matches it');
process.exit(failures ? 1 : 0);
