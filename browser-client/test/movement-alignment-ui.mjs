import assert from 'node:assert/strict';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';
import { createServer } from './browser-test-server.mjs';

const original = JSON.parse(await readFile(new URL('./fixtures/m5a-blitz-projections.json', import.meta.url), 'utf8'))[0].actor;
const players = [
  { ...original.players[0], x: 12, art: { rosterId: 'human', positionId: 'blitzer' } },
  { ...original.players[0], id: 'teammate', x: 13, y: 8, art: { rosterId: 'human', positionId: 'lineman' } },
  { ...original.players[1], x: 13, y: 6, art: { rosterId: 'orc', positionId: 'troll' } },
  { ...original.players[1], id: 'prone', x: 14, y: 8, state: 'is prone', art: { rosterId: 'orc', positionId: 'goblin-lineman' } },
];
const mover = players[0].id;
const fullPitch = [...players, ...Array.from({ length: 18 }, (_, i) => ({
  ...players[i % 2], id: 'bench-' + i, role: i < 9 ? 'home' : 'away',
  x: i < 9 ? 9 + i % 2 : 16 + i % 2, y: 3 + i % 9,
}))];
const record = (state, commands = []) => ({
  index: state.revision, revision: state.revision, kind: 'action', actor: 'home', at: state.revision, decision: null, state,
  native: commands.length ? [{ modelChangeList: { modelChangeArray: commands } }] : [],
});
const movePlayer = x => ({ modelChangeId: 'fieldModelSetPlayerCoordinate', modelChangeKey: mover, modelChangeValue: { x, y: 7 } });
const moveBall = x => ({ modelChangeId: 'fieldModelSetBallCoordinate', modelChangeValue: [x, 7] });
const state = (revision, end, x = 12, looseX = null, lineup = players, gatehouse = false) => ({
  ...original, revision, callerRole: end, actions: [], prompt: null, activePlayerId: mover,
  homeTeamArt: { rosterId: 'human', league: gatehouse ? 'Old World Classic' : null },
  awayTeamArt: { rosterId: 'orc', league: 'Badlands Brawl' },
  players: lineup.map(player => player.id === mover ? { ...player, x } : player),
  ball: { x: looseX ?? x, y: 7 },
  ballState: { version: 1, inPlay: true, moving: looseX !== null, carrierPlayerId: looseX === null ? mover : null },
});

const server = await createServer({ root: fileURLToPath(new URL('../', import.meta.url)), configFile: false,
  server: { host: '127.0.0.1', port: 0 } });
await server.listen();
const browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH
  || (process.platform === 'win32' ? 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe' : undefined) });
const url = 'http://127.0.0.1:' + server.httpServer.address().port + '/alignment-test';
const errors = [];

async function open({ angle = 50, end = 'home', mode = 'live', speed = 1, loose = false, reducedMotion = 'no-preference',
  gatehouse = false, lineup = players, provisional = false } = {}) {
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 }, reducedMotion });
  page.on('pageerror', error => errors.push(error.message));
  const view = state(0, end, 12, loose ? 12 : null, lineup, gatehouse);
  const input = { view, records: [record(view)], enabled: true, mode, speed, selectedId: mover,
    requestPending: provisional ? 'move' : null };
  await page.addInitScript(({ input, angle, end }) => {
    window.playbackInput = input;
    window.playbackFrames = [];
    const key = 'ffb.match.camera.' + encodeURIComponent(input.view.matchId) + '.' + end;
    sessionStorage.setItem(key, JSON.stringify({ angle, end, overheadEnd: end }));
  }, { input, angle, end });
  await page.route('**/alignment-test', route => route.fulfill({ contentType: 'text/html', body:
    '<div id="app"></div><script type="module" src="/test/playback-ui-harness.tsx"></script>' }));
  await page.goto(url);
  await page.waitForFunction(count => {
    const sprites = [...document.querySelectorAll('.live-marker img')];
    return sprites.length === count && sprites.every(image => image.complete && image.naturalWidth > 0);
  }, lineup.length);
  if (gatehouse && angle === 90) await page.locator('.pitch-gatehouse canvas').waitFor({ state: 'attached' });
  await page.waitForLoadState('networkidle');
  await page.evaluate(async ({ players, mover }) => {
    const { resolvePlayerArt, playerArtPlacement } = await import('/src/player-art.ts');
    const { PitchProjection } = await import('/src/pitch-projection.ts');
    const scene = document.querySelector('.live-pitch-scene');
    const topDown = scene.dataset.projection === 'top-down';
    const point = (x, y) => ({ x, y });
    const distance = (a, b) => Math.hypot(a.x - b.x, a.y - b.y);
    const vertices = polygon => polygon.getAttribute('points').trim().split(/\s+/).map(pair =>
      point(...pair.split(',').map(Number)));
    const boundsCenter = polygon => {
      const xs = polygon.map(vertex => vertex.x), ys = polygon.map(vertex => vertex.y);
      return point((Math.min(...xs) + Math.max(...xs)) / 2, (Math.min(...ys) + Math.max(...ys)) / 2);
    };
    const facings = ['north', 'north-east', 'east', 'south-east', 'south', 'south-west', 'west', 'north-west'];
    const markers = players.map(player => {
      const placements = new Map(facings.map(facing => {
        const body = resolvePlayerArt(player, { end: scene.dataset.end, topDown, facing }).body;
        return [body.pose + ':' + body.mirror, { ...playerArtPlacement(body, topDown), width: body.width }];
      }));
      return { id: player.id, placements, node: scene.querySelector('[data-player-id="' + player.id + '"]'),
        shadow: scene.querySelector('[data-shadow-player="' + player.id + '"]') };
    });
    const selection = scene.querySelector('[data-selection="' + mover + '"]');
    const camera = () => new PitchProjection({
      width: parseFloat(scene.style.width), height: parseFloat(scene.style.height),
      focus: Number(scene.dataset.focus), transverseFocus: Number(scene.dataset.transverseFocus),
      zoom: Number(scene.dataset.zoom), end: scene.dataset.end, mode: scene.dataset.projection,
      perspectiveElevation: topDown ? 50 : Number(scene.dataset.elevation),
    });
    window.measureAlignment = () => {
      const projection = camera();
      const surface = scene.getBoundingClientRect();
      const pieces = markers.map(({ id, node, shadow, placements }) => {
        const image = node.querySelector('img');
        const sprite = image.getBoundingClientRect();
        const button = node.getBoundingClientRect();
        const { anchor, shadowAnchor, width } = placements.get(node.dataset.pose + ':' + (image.style.transform === 'scaleX(-1)'));
        const scale = sprite.width / width;
        const center = point(sprite.left - surface.left + anchor.x * scale, sprite.top - surface.top + anchor.y * scale);
        // Invert the rendered sprite anchor so the expected ground shapes follow the paint.
        const displayed = projection.unproject(center);
        const expectedShadow = Array.from({ length: 16 }, (_, index) =>
          point(displayed.x + .28 * Math.cos(index * Math.PI / 8), displayed.y + .28 * Math.sin(index * Math.PI / 8)));
        const shadowOffset = point((shadowAnchor.x - anchor.x) * scale, (shadowAnchor.y - anchor.y) * scale);
        const shadowCenter = boundsCenter(projection.polygon(expectedShadow, true)
          .map(p => point(p.x + shadowOffset.x, p.y + shadowOffset.y)));
        const square = [Number(node.dataset.x), Number(node.dataset.y)];
        return { id, square, center, displayed, label: node.getAttribute('aria-label'),
          rest: distance(center, projection.project(point(square[0] + .5, square[1] + .5))),
          marker: distance(center, point(button.left - surface.left + button.width / 2, button.top - surface.top + button.height / 2)),
          shadow: distance(shadowCenter, boundsCenter(vertices(shadow))) };
      });
      const actor = pieces.find(piece => piece.id === mover);
      const selected = projection.polygon([[-.44, -.44], [.44, -.44], [.44, .44], [-.44, .44]]
        .map(([x, y]) => point(actor.displayed.x + x, actor.displayed.y + y)), true);
      const corners = vertices(selection);
      const selectionError = selected.length === corners.length
        ? Math.max(...corners.map((p, i) => distance(p, selected[i]))) : Infinity;
      const ball = scene.querySelector('.live-ball-marker');
      const ballRect = ball.getBoundingClientRect();
      const ballCenter = point(ballRect.left - surface.left, ballRect.top - surface.top);
      const ballWorld = projection.unproject(ballCenter);
      const ring = ball.querySelector('.live-ball-highlight');
      const ringStyle = getComputedStyle(ring);
      const matrix = new DOMMatrixReadOnly(ringStyle.transform);
      const ringWorld = (x, y) => {
        const projected = matrix.transformPoint({ x, y, z: 0, w: 1 });
        return point(ballCenter.x + parseFloat(ringStyle.left) + projected.x / projected.w,
          ballCenter.y + parseFloat(ringStyle.top) + projected.y / projected.w);
      };
      const ringError = Math.max(...[[16, 16], [2, 16], [30, 16], [16, 2], [16, 30]].map(([x, y]) =>
        distance(ringWorld(x, y), projection.project(point(ballWorld.x - .5 + x / 32, ballWorld.y - .5 + y / 32)))));
      return { pieces, selectionError, ballWorld, ringError,
        ballSquare: [Number(ball.dataset.ballX), Number(ball.dataset.ballY)],
        carrierError: ball.querySelector('.live-ball-football') ? null : distance(ballCenter, actor.center),
        active: document.querySelector('#playback-state').dataset.active === 'true',
        camera: [projection.focus, projection.transverseFocus, projection.zoom, projection.width, projection.height] };
    };
  }, { players: lineup, mover });
  return { page, input, end, lineup, gatehouse };
}

const publish = (page, input) => page.evaluate(value => window.publishPlayback(value), input);

async function play({ page, input, end, lineup, gatehouse }, { loose = false, steps = 4 } = {}) {
  const target = loose ? state(1, end, 12, 12 + steps, lineup, gatehouse) : state(1, end, 12 + steps, null, lineup, gatehouse);
  const commands = Array.from({ length: steps }, (_, index) => (loose ? moveBall : movePlayer)(13 + index));
  const next = { ...input, view: target, records: [...input.records, record(target, commands)] };
  await publish(page, next);
  return next;
}

async function frames(page, durationMs) {
  await page.evaluate(durationMs => {
    window.alignmentFrames = new Promise((done, reject) => {
      const frames = [];
      let start;
      function tick(now) {
        start ??= now;
        try {
          frames.push({ ms: now - start, phase: window.alignmentPhase, ...window.measureAlignment() });
        } catch (error) { reject(error); return; }
        if (now - start < durationMs) requestAnimationFrame(tick);
        else done(frames);
      }
      requestAnimationFrame(tick);
    });
  }, durationMs);
}

const collect = page => page.evaluate(() => window.alignmentFrames);
const geometry = page => page.evaluate(() => window.measureAlignment());
const active = page => page.locator('#playback-state[data-active="true"]').waitFor({ state: 'attached' });
const nextFrame = page => page.evaluate(() => new Promise(done => requestAnimationFrame(() => done())));
const waitForSprite = page => page.waitForFunction(() =>
  [...document.querySelectorAll('.live-marker img')].every(image => image.complete && image.naturalWidth > 0));

const phase = (page, value) => page.evaluate(value => { window.alignmentPhase = value; }, value);

async function pan(page, { moves = 16, zoom = false, capture = false, describe = true } = {}) {
  const bounds = await page.locator('.live-pitch-viewport').boundingBox();
  const x = bounds.x + bounds.width * .5, y = bounds.y + bounds.height * .46;
  await page.mouse.move(x, y);
  await page.mouse.down({ button: 'right' });
  if (describe) await phase(page, 'pan');
  for (let i = 1; i <= moves; i++) {
    // Reverse while the same gesture is held.
    const movement = Math.sin(i / moves * Math.PI * 1.5);
    await page.mouse.move(x + 38 * movement, y + 72 * movement);
    if (zoom && i === Math.floor(moves / 2)) await page.mouse.wheel(0, -90);
    if (capture && i === Math.floor(moves / 3)) await page.screenshot({ path: capture });
    await nextFrame(page);
  }
  await page.mouse.up({ button: 'right' });
  if (describe) await phase(page, 'release');
}

function errorMax(frames, read) {
  return Math.max(...frames.map(read));
}

function summarize(frames) {
  return {
    frames: frames.length,
    active: frames.filter(frame => frame.active).length,
    stationaryShadow: errorMax(frames, frame => Math.max(...frame.pieces.filter(piece => piece.id !== mover).map(piece => piece.shadow))),
    movingShadow: errorMax(frames, frame => frame.pieces.find(piece => piece.id === mover).shadow),
    selection: errorMax(frames, frame => frame.selectionError),
    ring: errorMax(frames, frame => frame.ringError),
    carrier: errorMax(frames, frame => frame.carrierError ?? 0),
    marker: errorMax(frames, frame => Math.max(...frame.pieces.map(piece => piece.marker))),
  };
}

async function verifyFinal(page, actorX, ballX = actorX) {
  await page.waitForFunction(({ actorX, ballX, mover }) =>
    document.querySelector('#playback-state').dataset.active === 'false'
    && document.querySelector('[data-player-id="' + mover + '"]').dataset.x === String(actorX)
    && document.querySelector('.live-ball-marker').dataset.ballX === String(ballX), { actorX, ballX, mover });
  await waitForSprite(page);
  const frame = await geometry(page);
  const actor = frame.pieces.find(piece => piece.id === mover);
  assert.deepEqual(actor.square, [actorX, 7]);
  assert.ok(actor.label.endsWith('square ' + actorX + ', 7'));
  assert.deepEqual(frame.ballSquare, [ballX, 7]);
  assert.ok(Math.hypot(actor.displayed.x - actorX - .5, actor.displayed.y - 7.5) < .01, 'Player settled at the authoritative square');
  assert.ok(Math.hypot(frame.ballWorld.x - ballX - .5, frame.ballWorld.y - 7.5) < .01, 'Ball settled at the authoritative square');
  return frame;
}

function assertAligned(frames, label) {
  const observed = summarize(frames);
  for (const frame of frames) {
    for (const piece of frame.pieces) {
      if (piece.id !== mover) assert.ok(piece.rest < 1, label + ' stationary player followed the camera');
      assert.ok(piece.label.endsWith('square ' + piece.square.join(', ')), label + ' retains the authoritative accessible square');
    }
    assert.ok(Math.abs(frame.ballWorld.y - 7.5) < .01, label + ' ball followed its pitch-space route');
  }
  for (const [name, error] of Object.entries(observed)) {
    if (name !== 'frames' && name !== 'active') {
      assert.ok(error <= 1, label + ' ' + name + ' departed by ' + error.toFixed(2) + ' CSS pixels');
    }
  }
}

const actorPosition = frame => frame.pieces.find(piece => piece.id === mover).displayed.x;

function assertMoving(frames, read, { instant = false } = {}) {
  assert.ok(frames.length >= 2, 'Sampled multiple browser frames');
  const threshold = instant ? .002 : .08;
  const between = frames.filter(frame => {
    const square = read(frame) - .5;
    return Math.abs(square - Math.round(square)) > threshold;
  });
  if (instant) assert.equal(between.length, 0, 'Reduced motion steps land on whole squares immediately');
  else assert.ok(between.length >= 2, 'Sampled intermediate positions on multiple browser frames');
}

async function motionCapture(options = {}, evidence) {
  const run = await open(options);
  const { page } = run;
  try {
    const rest = await geometry(page);
    const { loose = false, zoom = false, resize = false } = options;
    await phase(page, 'still');
    await frames(page, 1100);
    await play(run, { loose });
    await active(page);
    for (let i = 0; i < 5; i++) await nextFrame(page);
    await pan(page, { moves: 10, zoom, capture: evidence });
    if (resize) {
      for (let i = 0; i < 3; i++) await nextFrame(page);
      await phase(page, 'reveal');
      await page.getByRole('button', { name: 'Reveal ball / active' }).evaluate(button => button.click());
      await phase(page, 'resize');
      await page.setViewportSize({ width: 1320, height: 840 });
    }
    const trace = await collect(page);
    const final = await verifyFinal(page, loose ? 12 : 16, 16);
    // All pre-pan samples have the same camera as the rest frame.
    const still = trace.filter(frame => frame.active && frame.phase === 'still');
    const travelling = trace.filter(frame => frame.active && frame.phase === 'pan');
    const release = trace.filter(frame => frame.phase === 'release');
    assert.ok(still.length >= 2 && travelling.length >= 2 && release.length >= 2,
      'Sampled still, dragging and just-released geometry at ' + (options.angle ?? 50) + '° ' + (options.end ?? 'home')
      + ' (still ' + still.length + ', dragging ' + travelling.length + ', released ' + release.length + ')');
    assert.ok(travelling.some(frame => frame.camera[0] !== rest.camera[0]), 'The camera focus changed during playback');
    if (zoom) assert.ok(travelling.some(frame => frame.camera[2] !== rest.camera[2]), 'Wheel zoom changed the camera during playback');
    if (resize) assert.ok(trace.some(frame => frame.active && frame.camera[3] !== rest.camera[3]), 'Resize changed the viewport during playback');
    return { trace, final, stillSummary: summarize(still), panSummary: summarize(travelling), releaseSummary: summarize(release) };
  } finally { await page.close(); }
}

async function idlePan() {
  const { page } = await open();
  try {
    const rest = await geometry(page);
    await frames(page, 380);
    await pan(page, { moves: 12 });
    const trace = await collect(page);
    assert.ok(trace.every(frame => !frame.active));
    assert.ok(trace.some(frame => frame.camera[0] !== rest.camera[0]), 'Idle drag actually moves the camera');
    assertAligned(trace, 'idle pan');
  } finally { await page.close(); }
}

async function reducedMotion() {
  const run = await open({ reducedMotion: 'reduce' });
  const { page } = run;
  try {
    await frames(page, 380);
    await play(run);
    await pan(page, { moves: 12 });
    const trace = await collect(page);
    const final = await verifyFinal(page, 16);
    assert.ok(trace.some(frame => frame.active), 'Sampled reduced-motion playback');
    assertAligned([...trace, final], 'reduced motion');
    assertMoving(trace, actorPosition, { instant: true });
  } finally { await page.close(); }
}

async function replaySnapshots() {
  const run = await open({ mode: 'replay', speed: 2 });
  const { page, end } = run;
  try {
    const playing = await play(run);
    await active(page);
    const seek = state(2, end, 17);
    const seeking = { ...playing, view: seek, snapshotKey: 1,
      records: [...playing.records, record(seek, [movePlayer(17)])] };
    await phase(page, 'seek');
    await frames(page, 180);
    await publish(page, seeking);
    const sought = await verifyFinal(page, 17);
    assertAligned([sought], 'replay seek');
    const seekFrames = (await collect(page)).filter(frame => frame.pieces[0].square[0] === 17);
    assert.ok(seekFrames.length >= 2, 'Sampled frames immediately after seek');
    assertMoving(seekFrames, actorPosition, { instant: true });

    const next = state(3, end, 15);
    const moving = { ...seeking, view: next, records: [...seeking.records, record(next, [movePlayer(16), movePlayer(15)])] };
    await publish(page, moving);
    await active(page);
    await phase(page, 'skip');
    await frames(page, 180);
    await publish(page, { ...moving, enabled: false });
    const skipped = await verifyFinal(page, 15);
    assertAligned([skipped], 'replay skip animations');
    const skipFrames = (await collect(page)).filter(frame => frame.pieces[0].square[0] === 15);
    assert.ok(skipFrames.length >= 2, 'Sampled frames immediately after skip');
    assertMoving(skipFrames, actorPosition, { instant: true });
  } finally { await page.close(); }
}

async function provisionalMotion() {
  const run = await open({ provisional: true });
  const { page, input } = run;
  try {
    await frames(page, 520);
    await page.evaluate(playerId => window.beginProvisional({ routeVersion: 3, revision: 0, actor: 'home', playerId,
      from: { x: 12, y: 7 }, remaining: 6,
      steps: [13, 14, 15].map(x => ({ x, y: 7, dodge: 0, rush: 0, reactions: [], checks: [] })) }), mover);
    await active(page);
    await pan(page, { moves: 9 });
    await publish(page, { ...input, requestPending: null });
    await verifyFinal(page, 12);
    const trace = await collect(page);
    assert.ok(trace.some(frame => frame.active && frame.phase === 'pan'), 'Panned before the provisional move was cancelled');
    assertAligned(trace, 'provisional movement and cancellation');
    assertMoving(trace.filter(frame => frame.active), actorPosition);
    assertMoving(trace.filter(frame => !frame.active), actorPosition, { instant: true });
  } finally { await page.close(); }
}

async function benchmark() {
  const intervals = [];
  for (let attempt = 0; attempt < 6; attempt++) {
    const run = await open({ lineup: fullPitch, gatehouse: true });
    const { page } = run;
    try {
      // Time only frames within the actual right drag. Geometry reads would add layout work.
      await page.evaluate(() => {
        const viewport = document.querySelector('.live-pitch-viewport');
        const output = document.querySelector('#playback-state');
        window.panIntervals = [];
        let animation = 0, previous;
        function tick(now) {
          if (previous !== undefined && output.dataset.active === 'true') window.panIntervals.push(now - previous);
          previous = now;
          animation = requestAnimationFrame(tick);
        }
        viewport.addEventListener('pointerdown', () => { animation = requestAnimationFrame(tick); }, { once: true });
        viewport.addEventListener('pointerup', () => cancelAnimationFrame(animation), { once: true });
      });
      await play(run, { steps: 6 });
      await active(page);
      await pan(page, { moves: 40, describe: false });
      const trace = await page.evaluate(() => window.panIntervals);
      assert.ok(trace.length >= 30, 'Captured rAF intervals during a real pan on active playback');
      if (attempt > 0) intervals.push(...trace); // Warm Vite and the browser first.
    } finally { await page.close(); }
  }
  const sorted = [...intervals].sort((a, b) => a - b);
  const percentile = fraction => sorted[Math.ceil(sorted.length * fraction) - 1];
  return { frames: sorted.length, p50Ms: percentile(.5), p95Ms: percentile(.95), maxMs: sorted.at(-1), intervals };
}

const evidence = process.env.MOVEMENT_ALIGNMENT_EVIDENCE;
const benchmarkOnly = process.argv.includes('--benchmark-only');
const captureBaseline = process.argv.includes('--baseline');
try {
  if (evidence) await mkdir(evidence, { recursive: true });
  const timing = (benchmarkOnly || evidence) ? await benchmark() : null;
  if (timing) {
    console.log('50 degree pan during move:', JSON.stringify({ ...timing, intervals: undefined }));
    if (evidence) await writeFile(join(evidence, 'frame-intervals.json'), JSON.stringify({ ...timing, browser: browser.version() }, null, 2));
  }
  if (!benchmarkOnly) {
    const capture = await motionCapture({ zoom: true }, evidence && join(evidence, 'pan.png'));
    console.log('50 degree still and pan:', JSON.stringify({ still: capture.stillSummary, pan: capture.panSummary }));
    if (evidence) await writeFile(join(evidence, 'alignment.json'), JSON.stringify({ ...capture, browser: browser.version() }));
    if (captureBaseline) {
      assert.ok(capture.stillSummary.movingShadow > 1 && capture.panSummary.stationaryShadow > 1,
        'The baseline reproduces both the moving shadow lead and stationary player lag');
    } else {
      assertAligned(capture.trace, '50 degree home');
      assertAligned([capture.final], '50 degree home settled');
      assertMoving(capture.trace.filter(frame => frame.active && frame.phase === 'still'), actorPosition);
      for (const end of ['home', 'away']) {
        for (const [angle, gatehouse] of [[30, false], [40, false], [50, false], [90, false], [90, true]]) {
          const label = angle + ' degree ' + end + (gatehouse ? ' Gatehouse' : '');
          if (end !== 'home' || angle !== 50) {
            const player = await motionCapture({ angle, end, gatehouse, resize: angle === 50 });
            assertAligned([...player.trace, player.final], label + ' carrier');
            assertMoving(player.trace.filter(frame => frame.active), actorPosition);
          }
          const loose = await motionCapture({ angle, end, gatehouse, loose: true });
          assertAligned([...loose.trace, loose.final], label + ' loose ball');
          assertMoving(loose.trace.filter(frame => frame.active && frame.phase === 'still'), frame => frame.ballWorld.x);
        }
      }
      for (const speed of [1, 2]) {
        const replay = await motionCapture({ mode: 'replay', speed });
        assertAligned([...replay.trace, replay.final], 'replay ' + speed + 'x');
        assertMoving(replay.trace.filter(frame => frame.active), actorPosition);
      }
      await reducedMotion();
      await idlePan();
      await replaySnapshots();
      await provisionalMotion();
      console.log('PASS: intermediate pitch alignment at all angles and both ends, carried and loose ball, pan reversal and release, zoom, resize, replay, reduced motion, idle pan, and provisional playback.');
    }
  }
  assert.deepEqual(errors, []);
} finally {
  await browser.close();
  await server.close();
}
