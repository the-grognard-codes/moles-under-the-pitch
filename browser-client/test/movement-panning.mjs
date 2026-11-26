import assert from 'node:assert/strict';
import { readFile, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { cpus } from 'node:os';
import { createHash } from 'node:crypto';
import { setCameraAngle } from './camera-controls.mjs';
import { distribution } from './gameplay-performance-observer.mjs';

const cases = [];
for (const [league, angle] of [['Old World Classic', 50], ['Old World Classic', 90], ['Badlands Brawl', 90]]) {
  for (const motion of ['stationary', 'player', 'ball']) cases.push({ league, angle, motion, control: 'normal' });
}
for (const control of ['hide-stationary', 'hide-ground', 'direct-stationary']) {
  cases.push({ league: 'Old World Classic', angle: 50, motion: 'player', control });
}

async function run({ page, session, url, dispatchPan }, { league, angle, motion, control }) {
  await page.goto(url + '/?' + new URLSearchParams({ movement: motion, league }));
  await page.locator('.live-marker img').first().waitFor();
  await page.addStyleTag({ content: '.live-camera-buttons {position:absolute; z-index:70}' });
  await setCameraAngle(page, String(angle));
  if (league === 'Old World Classic' && angle === 90) await page.locator('.pitch-gatehouse canvas').waitFor();
  else await page.locator('.pitch-stadium-world').waitFor({ state: 'attached' });
  const images = page.locator('.live-marker img');
  assert.equal(await images.count(), 22, 'Every marker must use player artwork');
  await images.evaluateAll(images => Promise.all([document.fonts.ready, ...images.map(image => image.decode())]));
  if (control === 'hide-stationary') await page.addStyleTag({ content: '.live-marker:not([data-player-id="home1"]) { display:none }' });
  if (control === 'hide-ground') await page.addStyleTag({ content: '.pitch-stadium-world, .live-pitch-grid { display:none }' });
  if (control === 'direct-stationary') await page.addStyleTag({ content:
    '.live-marker:not([data-player-id="home1"]), .live-marker:not([data-player-id="home1"]) img { transition: none !important }' });
  await page.waitForTimeout(250);
  const box = await page.locator('.live-pitch-viewport').boundingBox();
  const x = Math.round(box.x + box.width * .55), y = Math.round(box.y + box.height * .48);
  const trace = [];
  const collect = ({ value }) => { for (const event of value) trace.push(event); };
  session.on('Tracing.dataCollected', collect);
  await session.send('Tracing.start', { categories: 'devtools.timeline,blink.user_timing,disabled-by-default-devtools.timeline.frame' });
  await page.mouse.move(x, y);
  await page.evaluate(() => { window.idlePan.start(); performance.mark('pan-start'); window.dispatchEvent(new Event('start-motion')); });
  await page.mouse.down({ button: 'right' });
  await dispatchPan(session, x, y);
  await page.mouse.up({ button: 'right' });
  const raw = await page.evaluate(() => { performance.mark('pan-end'); return window.idlePan.finish(); });
  const complete = new Promise(resolve => session.once('Tracing.tracingComplete', resolve));
  await session.send('Tracing.end');
  await complete;
  session.off('Tracing.dataCollected', collect);
  assert.ok(raw.panRange > 1 && raw.input.some(sample => sample.commit !== undefined));
  if (motion !== 'stationary') {
    const actor = page.locator(motion === 'player' ? '[data-player-id="home1"]' : '.live-ball-marker');
    assert.equal(await actor.getAttribute(motion === 'player' ? 'data-y' : 'data-ball-y'), '11');
  }
  const start = trace.find(event => event.name === 'pan-start');
  const end = trace.find(event => event.name === 'pan-end');
  assert.ok(start && end && start.ts < end.ts, 'Missing pan interval');
  const events = trace.filter(event => event.pid === start.pid && start.ts <= event.ts && event.ts < end.ts);
  const count = name => events.filter(event => event.name === name).length;
  assert.ok(count('BeginFrame') && count('DrawFrame'), 'Missing renderer frame track');
  const markerPaint = events.filter(event => event.name === 'Paint' && event.args?.data?.nodeName?.includes('live-marker'));
  return { league, angle, motion, control, durationMs: (end.ts - start.ts) / 1000, received: raw.input.length,
    commit: distribution(raw.input.filter(i => i.commit !== undefined).map(i => i.commit - i.at)),
    frames: distribution(raw.frames), begin: count('BeginFrame'), drawn: count('DrawFrame'), dropped: count('DroppedFrame'),
    markerPaintEvents: markerPaint.length, markerPaintMs: markerPaint.reduce((us, event) => us + event.dur, 0) / 1000 };
}

export async function measureMovement({ browser, page, url, output, bundle, dispatchPan }) {
  const repeats = Number(process.env.PAN_REPEATS || 3);
  assert.ok(Number.isInteger(repeats) && repeats > 0);
  const session = await page.context().newCDPSession(page);
  const results = [];
  for (let round = -1; round < repeats; round++) {
    // Reverse alternate rounds to distribute order effects. Discard the warm-up round.
    for (const condition of round % 2 === 0 ? cases : [...cases].reverse()) {
      const row = await run({ page, session, url, dispatchPan }, condition);
      if (round >= 0) results.push({ round, ...row });
      console.log(round, condition.league, condition.angle, condition.motion, condition.control, row.commit.p95, row.dropped);
    }
  }
  const bundleSha256 = createHash('sha256').update(await readFile(join(bundle, 'harness.js'))).digest('hex');
  await writeFile(join(output, 'movement.json'), JSON.stringify({ bundleSha256, browser: browser.version(), cpu: cpus()[0].model,
    viewport: page.viewportSize(), results }, null, 2));
}
