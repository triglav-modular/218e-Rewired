// The buchla.com watch as it runs in CI: a file for the baseline, a GitHub
// issue for the notice.  Everything it actually checks is in
// watch-buchla.mjs; this only says where the answer goes.
//
// An issue rather than a mail because GitHub already mails what it opens, and
// that is one fewer credential, one fewer service, and one fewer thing that
// can quietly stop working than any mail path this repository could own.
import { readFile, writeFile } from 'node:fs/promises';
import { watch } from './watch-buchla.mjs';

// Where the baseline sits.  Given rather than assumed, because the schedule
// that runs this does not live in the same repository as the code: a public
// repository's schedule is switched off after 60 days without activity, and a
// watch that exists for the quiet years cannot be one of the things that goes
// quiet.  See "Watching buchla.com" in docs/BUILD.md.
const STATE = new URL(process.env.WATCH_STATE || 'buchla-watch.json',
                      `file://${process.cwd()}/`);

const state = {
  async read() {
    try { return JSON.parse(await readFile(STATE, 'utf8')); }
    // No file is the first run, which is what the seeded baseline is for.
    catch (e) { return e.code === 'ENOENT' ? null : Promise.reject(e); }
  },
  async write(value) {
    await writeFile(STATE, JSON.stringify(value, null, 2) + '\n');
  },
};

async function issue(title, body) {
  const repo = process.env.GITHUB_REPOSITORY;
  const token = process.env.GITHUB_TOKEN;
  if (!repo || !token) throw new Error('GITHUB_REPOSITORY and GITHUB_TOKEN are required');
  const res = await fetch(`https://api.github.com/repos/${repo}/issues`, {
    method: 'POST',
    headers: {
      authorization: `Bearer ${token}`,
      accept: 'application/vnd.github+json',
      'x-github-api-version': '2022-11-28',
      'content-type': 'application/json',
    },
    // Fenced, because the body is a changelog and a column of headers, and
    // reflowing either of those helps nobody.
    body: JSON.stringify({ title, body: '```\n' + body + '```\n', labels: ['firmware'] }),
  });
  if (!res.ok) {
    throw new Error(`opening the issue answered ${res.status}: ${(await res.text()).slice(0, 300)}`);
  }
  console.log(`opened: ${(await res.json()).html_url}`);
}

// A failed check must not fail the job.  GitHub mails the owner every time a
// scheduled workflow fails, so exiting non-zero on a bad afternoon at
// buchla.com would send a mail a day and drown the one notice that matters -
// which is exactly what the seven-day alarm inside watch() exists to avoid.
// The count is carried in the committed baseline, and the alarm opens an issue
// of its own once a run of failures is long enough to be real.
try {
  const said = await watch({ state, notify: issue });
  console.log(said || 'nothing has changed');
} catch (e) {
  console.log(`the check failed: ${e.message}`);
  console.log('counted in the baseline; an issue is opened once it has run for'
            + ' a week.');
}
