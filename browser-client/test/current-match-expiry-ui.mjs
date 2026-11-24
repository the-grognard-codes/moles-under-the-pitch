import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';
import { mkdir } from 'node:fs/promises';
import { chromium } from 'playwright';
import { createServer } from './browser-test-server.mjs';

const root = fileURLToPath(new URL('../', import.meta.url));
const publicDir = fileURLToPath(new URL('../../site/src/', import.meta.url));
const evidence = new URL('../../.tools/current-match-expiry/', import.meta.url);
const matchId = '12345678-1234-1234-1234-123456789abc';
const accountId = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa';
const pendingKey = 'ffb.intent.v2';
const server = await createServer({ root, publicDir, configFile: false, server: { host: '127.0.0.1', port: 0 } });
await server.listen();
const base = `http://127.0.0.1:${server.httpServer.address().port}`;
const browser = await chromium.launch({ channel: 'chrome', headless: true });

async function openLobby(width, clockSkew, refreshOutcome) {
  const page = await browser.newPage({ viewport: { width, height: 900 } });
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  await page.route(url => url.origin === base && ['/play', '/play/match'].includes(url.pathname), route =>
    route.fulfill({ contentType: 'text/html', body: `<!doctype html><meta charset="UTF-8"><meta name="viewport" content="width=device-width, initial-scale=1">
      <link rel="stylesheet" href="/assets/site.css"><link rel="stylesheet" href="/assets/teambuilder.css">
      <link rel="stylesheet" href="/assets/builder-layout.css"><link rel="stylesheet" href="/assets/play.css">
      <div id="app"></div><script type="module">
      import { mountPlay } from '/src/play-entry.tsx';
      mountPlay(document.getElementById('app'), { url: 'ws://127.0.0.1/unused', getToken: async () => 'test-token' });
      </script>` }));
  await page.addInitScript(({ clockSkew, refreshOutcome, matchId, accountId, pendingKey }) => {
    const wallNow = Date.now;
    Date.now = () => wallNow() + clockSkew;
    const serverNow = 1_800_000_000_000;
    const minute = 60_000;
    const hour = 60 * minute;
    const day = 24 * hour;
    const deadline = 3_000;
    const match = { matchId, lifecycle: 'ACTIVATED', phase: 'PLAY', callerRole: 'home', homeTeamName: 'Late Reavers', awayTeamName: 'Raiders' };
    const game = (suffix, homeTeamName, duration) => ({ ...match, matchId: matchId.slice(0, -2) + suffix, homeTeamName,
      ...(duration === undefined ? {} : { resumeUntil: serverNow + duration }) });
    if (location.pathname === '/play/match') sessionStorage.setItem(pendingKey, JSON.stringify({ accountId,
      request: { version: 2, type: 'setup', matchId, operation: 'action', requestId: 'stale-action' },
    }));
    window.requests = [];
    window.currentMatchesReads = [];
    window.WebSocket = class {
      readyState = 0;
      constructor() { queueMicrotask(() => { this.readyState = 1; this.onopen?.(); }); }
      close() { this.readyState = 3; this.onclose?.(); }
      send(raw) {
        const request = JSON.parse(raw);
        window.requests.push(request);
        const reply = message => queueMicrotask(() => this.onmessage?.({ data: JSON.stringify({ version: 2, requestId: request.requestId, ...message }) }));
        if (request.type === 'authenticate') reply({ type: 'authentication', code: 'ACCEPTED', accountId });
        if (request.type === 'setup' && request.operation === 'load')
          reply({ type: 'setupState', code: 'MATCH_ABANDONED', duplicate: false, state: null });
        if (request.type !== 'currentMatches') return;
        const page = (matches, details) => reply({ type: 'currentMatches', code: 'ACCEPTED', matches, next: null, ...(details ? { serverNow } : {}) });
        window.currentMatchesReads.push({ includeDetails: request.includeResumeDetails === true, at: performance.now() });
        if (refreshOutcome === 'legacy') {
          if (request.includeResumeDetails) reply({ type: 'error', code: 'MALFORMED_MESSAGE' });
          else page([match], false);
        } else if (window.currentMatchesReads.length === 1) {
          page([{ ...match, resumeUntil: serverNow + deadline },
            game('bd', 'Patient Reavers', 29 * day + 23.5 * hour), game('be', 'Evening Reavers', 22 * hour + 16.5 * minute),
            game('bf', 'Quick Reavers', 9.5 * minute), game('c0', 'Active Reavers')], true);
        } else {
          window.releaseRefresh = () => page([refreshOutcome === 'expired' ? { ...match, lifecycle: 'EXPIRED' } : match], true);
        }
      }
    };
  }, { clockSkew, refreshOutcome, matchId, accountId, pendingKey });
  await page.goto(`${base}/play`, { waitUntil: 'domcontentloaded' });
  return { page, errors };
}

try {
  await mkdir(evidence, { recursive: true });
  for (const [width, skew, outcome] of [[1440, 600_000, 'resumable'], [390, -600_000, 'expired']]) {
    const { page, errors } = await openLobby(width, skew, outcome);
    const late = page.getByRole('article', { name: 'Late Reavers vs. Raiders' });
    await late.getByText('0m to resume', { exact: true }).waitFor();
    for (const label of ['29d 23h to resume', '22h 16m to resume', '9m to resume'])
      assert.equal(await page.getByText(label, { exact: true }).isVisible(), true);
    const active = page.getByRole('article', { name: 'Active Reavers vs. Raiders' });
    assert.equal(await active.getByText(/to resume$/).count(), 0);
    assert.equal(await late.getByRole('link', { name: 'Resume', exact: true }).isVisible(), true);
    assert.equal(await late.getByText('0m to resume', { exact: true }).evaluate(element => element.closest('[role="status"],[aria-live]')), null);
    await late.getByRole('button', { name: 'Concede', exact: true }).click();
    await late.getByText('Checking match status', { exact: true }).waitFor();
    await page.waitForFunction(() => window.currentMatchesReads.length === 2);
    assert.deepEqual(await page.evaluate(() => window.currentMatchesReads.map(read => read.includeDetails)), [true, true]);
    assert.ok(await page.evaluate(() => window.currentMatchesReads[1].at - window.currentMatchesReads[0].at >= 3_000));
    assert.equal(await late.getByRole('link', { name: 'Resume', exact: true }).count(), 0);
    assert.equal(await late.getByRole('button', { name: /Concede|Confirm concession/ }).count(), 0);
    assert.ok(await late.evaluate(element => element.scrollWidth <= element.clientWidth));
    await page.screenshot({ path: fileURLToPath(new URL(`checking-${width}.png`, evidence)), fullPage: true });
    await page.evaluate(() => window.releaseRefresh());
    if (outcome === 'resumable') await late.getByRole('link', { name: 'Resume', exact: true }).waitFor();
    else {
      await late.getByText('Match expired', { exact: true }).waitFor();
      assert.equal(await late.getByRole('link').count(), 0);
      assert.equal(await late.getByRole('button').count(), 0);
      await page.screenshot({ path: fileURLToPath(new URL(`expired-${width}.png`, evidence)), fullPage: true });
      await page.goto(`${base}/play/match?matchId=${matchId}`);
      await page.getByRole('heading', { name: 'Match expired', exact: true }).waitFor();
      assert.equal(await page.getByText('The time to resume this match has ended.').isVisible(), true);
      assert.equal(await page.getByRole('button', { name: /Repeat retained request|Send|Resume|Reconnect/ }).count(), 0);
      assert.equal(await page.evaluate(key => sessionStorage.getItem(key), pendingKey), null);
      assert.deepEqual(await page.evaluate(() => window.requests.filter(request => ['setup', 'matchChat', 'matchTranscript'].includes(request.type))
        .map(request => request.operation)), ['load']);
      await page.screenshot({ path: fileURLToPath(new URL(`direct-${width}.png`, evidence)) });
      await page.getByRole('link', { name: 'Back to lobby' }).click();
      assert.equal(new URL(page.url()).pathname, '/play');
    }
    assert.deepEqual(errors, []);
    await page.close();
  }
  const { page, errors } = await openLobby(1440, 0, 'legacy');
  const legacy = page.getByRole('article', { name: 'Late Reavers vs. Raiders' });
  await legacy.getByRole('link', { name: 'Resume', exact: true }).waitFor();
  assert.equal(await legacy.getByText(/to resume$/).count(), 0);
  assert.deepEqual(await page.evaluate(() => window.currentMatchesReads.map(read => read.includeDetails)), [true, false]);
  await page.getByRole('button', { name: 'Refresh games', exact: true }).click();
  await page.waitForFunction(() => window.currentMatchesReads.length === 3);
  assert.deepEqual(await page.evaluate(() => window.currentMatchesReads.map(read => read.includeDetails)), [true, false, false]);
  assert.deepEqual(errors, []);
  console.log('Match expiry UI: skew-safe countdown, recheck at zero, both verdicts, expired direct link, and legacy fallback at 1440px and 390px.');
} finally {
  await browser.close();
  await server.close();
}
