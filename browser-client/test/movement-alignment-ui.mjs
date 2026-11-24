import assert from 'node:assert/strict';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';
import { createServer } from './browser-test-server.mjs';
import { travelToFocus } from './projected-pitch-helper.mjs';
import { PitchProjection } from '../src/pitch-projection.ts';
import { BALL_STEP_MS, PLAYER_STEP_MS } from '../src/presentation-timing.ts';

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
const movePlayer = (x, y = 7, playerId = mover) => ({ modelChangeId: 'fieldModelSetPlayerCoordinate', modelChangeKey: playerId, modelChangeValue: { x, y } });
const moveBall = (x, y = 7) => ({ modelChangeId: 'fieldModelSetBallCoordinate', modelChangeValue: [x, y] });
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
  gatehouse = false, lineup = players, provisional = false, ball = null, carrier = null, interactive = false } = {}) {
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 }, reducedMotion });
  page.on('pageerror', error => errors.push(error.message));
  const view = state(0, end, 12, loose ? 12 : null, lineup, gatehouse);
  if (ball) {
    view.ball = { x: ball[0], y: ball[1] };
    view.ballState = { version: 1, inPlay: true, moving: carrier === null, carrierPlayerId: carrier };
  }
  const input = { view, records: [record(view)], enabled: true, mode, speed, interactive, selectedId: mover,
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
      const football = ball.querySelector('.live-ball-football');
      return { pieces, selectionError, ballWorld, ringError, footballVisible: !!football && football.getBoundingClientRect().width > 0,
        ballSquare: [Number(ball.dataset.ballX), Number(ball.dataset.ballY)],
        carrierError: football ? null : distance(ballCenter, actor.center),
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

async function pan(page, { moves = 16, zoom = false, capture = false, describe = true, advanceFrame = () => nextFrame(page) } = {}) {
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
    await advanceFrame();
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

function assertAligned(frames, label, { moving = mover, ballY = 7.5 } = {}) {
  const observed = summarize(frames);
  for (const frame of frames) {
    for (const piece of frame.pieces) {
      if (piece.id !== moving) assert.ok(piece.rest < 1, label + ' stationary player followed the camera');
    }
    if (ballY !== null) assert.ok(Math.abs(frame.ballWorld.y - ballY) < .01, label + ' ball followed its pitch-space route');
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
    // Keep regression sampling deterministic under load. Evidence uses the real compositor clock.
    if (!evidence) await steppedClock(page);
    const advanceFrame = evidence ? () => nextFrame(page) : () => page.clock.runFor(16);
    await phase(page, 'still');
    await frames(page, 1100);
    await play(run, { loose });
    await active(page);
    for (let i = 0; i < 5; i++) await advanceFrame();
    await pan(page, { moves: 10, zoom, capture: evidence, advanceFrame });
    if (resize) {
      for (let i = 0; i < 3; i++) await advanceFrame();
      await phase(page, 'resize');
      await page.setViewportSize({ width: 1320, height: 840 });
    }
    if (!evidence) for (let i = 0; i < 70; i++) await advanceFrame();
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

async function steppedClock(page) {
  await page.clock.install();
  await page.clock.pauseAt(await page.evaluate(() => Date.now() + 100));
}

function assertSquare(frame, id, x, y) {
  const piece = frame.pieces.find(piece => piece.id === id);
  assert.deepEqual(piece.square, [x, y], 'Marker data preserves the authoritative square');
  assert.ok(piece.label.endsWith('square ' + x + ', ' + y), 'The accessible name gives the authoritative square');
  return piece;
}

function assertOnSegment(point, from, to) {
  assert.ok(Math.abs((point.x - from.x) / (to.x - from.x) - (point.y - from.y) / (to.y - from.y)) < .01,
    'The painted position follows the diagonal in pitch space');
  assert.ok(point.x > Math.min(from.x, to.x) + .05 && point.x < Math.max(from.x, to.x) - .05,
    'The painted position is between the authoritative squares');
}

async function possessionChanges() {
  for (const [label, ball, carrier] of [['flight caught', [15, 9], null], ['steal', [13, 6], players[2].id]]) {
    const { page, input, end } = await open({ ball, carrier });
    try {
      await steppedClock(page);
      assert.equal((await geometry(page)).footballVisible, carrier === null);
      const target = state(1, end);
      await publish(page, { ...input, view: target, records: [...input.records,
        record(target, [moveBall(12, 7), { modelChangeId: 'fieldModelSetBallMoving', modelChangeValue: false }])] });
      await active(page);
      const samples = [];
      for (let elapsed = 48; elapsed < BALL_STEP_MS - 32; elapsed += 48) {
        await page.clock.runFor(48);
        const sample = await geometry(page);
        assert.deepEqual(sample.ballSquare, [12, 7], label + ' commits its destination at the start of the beat');
        assertOnSegment(sample.ballWorld, { x: ball[0] + .5, y: ball[1] + .5 }, { x: 12.5, y: 7.5 });
        assert.equal(sample.footballVisible, true, label + ' shows the football in flight');
        samples.push(sample);
      }
      assertAligned(samples, label, { ballY: null });
      await page.clock.runFor(BALL_STEP_MS);
      const final = await verifyFinal(page, 12);
      assert.equal(final.footballVisible, false, label + ' removes the football on possession');
      assertAligned([final], label + ' after possession');
    } finally { await page.close(); }
  }
  const { page, input, end } = await open({ ball: [13, 7] });
  try {
    await steppedClock(page);
    const target = state(1, end, 13);
    assert.equal((await geometry(page)).footballVisible, true, 'The loose football is initially on the destination square');
    await publish(page, { ...input, view: target, records: [...input.records,
      record(target, [movePlayer(13), { modelChangeId: 'fieldModelSetBallMoving', modelChangeValue: false }])] });
    await active(page);
    const samples = [];
    for (let elapsed = 48; elapsed <= 144; elapsed += 48) {
      await page.clock.runFor(48);
      const sample = await geometry(page);
      assertSquare(sample, mover, 13, 7);
      assert.ok(actorPosition(sample) > 12.55 && actorPosition(sample) < 13.45, 'The player is approaching the ball');
      assert.ok(Math.abs(sample.ballWorld.x - 13.5) < .002, 'The ball remains on its original loose square');
      assert.equal(sample.footballVisible, true, 'The approaching player has not reached the visible football');
      samples.push(sample);
    }
    assertAligned(samples, 'stationary pickup before arrival');
    await page.clock.runFor(PLAYER_STEP_MS);
    const final = await verifyFinal(page, 13);
    assert.equal(final.footballVisible, false, 'The football disappears at pickup');
    assertAligned([final], 'stationary pickup after arrival');
  } finally { await page.close(); }

}

async function diagonalPushAndHits() {
  const { page, input, end } = await open({ interactive: true });
  try {
    await steppedClock(page);
    const pushed = players[2];
    const target = state(1, end);
    target.players = target.players.map(player => player.id === pushed.id ? { ...player, x: 14, y: 5 } : player);
    await publish(page, { ...input, view: target, records: [...input.records, record(target, [movePlayer(14, 5, pushed.id)])] });
    await active(page);
    const marker = page.locator('[data-player-id="' + pushed.id + '"]');
    const samples = [];
    for (let elapsed = 48; elapsed <= 144; elapsed += 48) {
      await page.clock.runFor(48);
      const sample = await geometry(page);
      const piece = assertSquare(sample, pushed.id, 14, 5);
      assertOnSegment(piece.displayed, { x: 13.5, y: 6.5 }, { x: 14.5, y: 5.5 });
      if (elapsed === 48) {
        assert.deepEqual([Math.floor(piece.displayed.x), Math.floor(piece.displayed.y)], [13, 6],
          'The displayed hitbox is still over the original square');
        const hit = await marker.evaluate(node => {
          const bounds = node.getBoundingClientRect();
          const x = bounds.x + bounds.width / 2, y = bounds.y + bounds.height / 2;
          return { x, y, id: document.elementFromPoint(x, y)?.closest('.live-marker')?.dataset.playerId };
        });
        assert.equal(hit.id, pushed.id, 'Hit testing locates the button at its displayed position');
        await page.mouse.click(hit.x, hit.y);
        assert.deepEqual(await page.evaluate(() => window.playbackIntents), [], 'Clicks during playback send no square or player intent');
      }
      samples.push(sample);
    }
    assertAligned(samples, 'diagonal push', { moving: pushed.id });
    await page.clock.runFor(PLAYER_STEP_MS);
    await verifyFinal(page, 12);
    await marker.click();
    assert.deepEqual(await page.evaluate(() => window.playbackIntents), [{ playerId: pushed.id }], 'The settled button selects its authoritative player');
  } finally { await page.close(); }
}

async function reducedMotionToggle() {
  const run = await open();
  const { page } = run;
  try {
    await steppedClock(page);
    await play(run, { steps: 1 });
    await active(page);
    await page.clock.runFor(64);
    const moving = await geometry(page);
    assert.ok(actorPosition(moving) > 12.55 && actorPosition(moving) < 13.45, 'A step is in progress before switching preferences');
    await page.evaluate(() => {
      const preference = matchMedia('(prefers-reduced-motion: reduce)');
      window.reducedMotionChanged = new Promise(done => preference.addEventListener('change', () => done(), { once: true }));
    });
    await page.emulateMedia({ reducedMotion: 'reduce' });
    await page.evaluate(() => window.reducedMotionChanged);
    await page.clock.runFor(16);
    const settled = await geometry(page);
    assert.ok(settled.active, 'The current beat remains active during the preference change');
    assertSquare(settled, mover, 13, 7);
    assert.ok(Math.abs(actorPosition(settled) - 13.5) < .002, 'Reduced motion completes the current step immediately');
    assertAligned([settled], 'reduced motion toggle');
    await page.clock.runFor(PLAYER_STEP_MS);
    await verifyFinal(page, 13);
  } finally { await page.close(); }
}

async function revealCamera() {
  for (const [end, button] of [['home', 'Reveal selected'], ['away', 'Reveal ball / active'], ['home', null]]) {
    const { page, input } = await open({ end, interactive: !button });
    try {
      await page.getByRole('button', { name: '2×', exact: true }).click();
      const scene = page.locator('.live-pitch-scene');
      const options = await scene.evaluate(node => ({
        width: node.clientWidth, height: node.clientHeight, zoom: Number(node.dataset.zoom), end: node.dataset.end,
        perspectiveElevation: Number(node.dataset.elevation),
      }));
      const camera = new PitchProjection(options);
      const target = { x: 13.5, y: 7.5 };
      const focus = Array.from({ length: 261 }, (_, index) => index / 10).find(focus => {
        const projection = camera.with({ focus });
        return projection.isVisible(target, 25) && !projection.isVisible(target, 40);
      });
      assert.ok(focus !== undefined, 'The destination can be placed in the Reveal edge margin');
      await travelToFocus(page, focus);
      await steppedClock(page);
      const destination = state(1, end, 13);
      if (!button) destination.actions = [{ id: 'reroll', kind: 'reroll', actor: 'home',
        label: 'Use team re-roll for Pick Up', sourcePlayerId: mover, target: null }];
      await publish(page, { ...input, view: destination, records: [...input.records, record(destination, [movePlayer(13)])] });
      await active(page);
      await page.clock.runFor(64);
      const before = Number(await scene.getAttribute('data-focus'));
      assert.ok(Math.abs(before - target.x) > .5, 'Reveal starts with the authoritative destination near the edge');
      if (button) await page.getByRole('button', { name: button, exact: true }).click();
      else {
        assert.equal(await page.locator('#required-choice').count(), 0, 'The decision is gated during playback');
        await page.clock.runFor(PLAYER_STEP_MS);
        await page.locator('#required-choice').waitFor();
      }
      const samples = [];
      for (let i = 0; i < 3; i++) {
        await page.clock.runFor(16);
        samples.push(await geometry(page));
      }
      assert.ok(samples.every(frame => Math.abs(frame.camera[0] - target.x) < .001), 'Reveal moved the camera to the canonical square');
      if (button) {
        assert.ok(samples.every(frame => frame.active), button + ' changed the camera during the same movement beat');
        assertMoving(samples, actorPosition);
      }
      assertAligned(samples, button ?? 'decision-triggered Reveal');
    } finally { await page.close(); }
  }
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

function geometryCsv(frames) {
  const header = 'time_ms,phase,playback_active,stationary_shadow_max_px,moving_shadow_px,selection_px,ring_px,carrier_px';
  const rows = frames.map(frame => {
    const { stationaryShadow, movingShadow, selection, ring, carrier } = summarize([frame]);
    return [frame.ms, frame.phase, Number(frame.active), stationaryShadow, movingShadow, selection, ring, carrier]
      .map(value => typeof value === 'number' ? Number(value.toFixed(4)) : value).join(',');
  });
  return [header, ...rows].join('\n') + '\n';
}

const evidence = process.env.MOVEMENT_ALIGNMENT_EVIDENCE;
const benchmarkOnly = process.argv.includes('--benchmark-only');
const captureOnly = process.argv.includes('--capture-only');
try {
  if (captureOnly && !evidence) throw Error('--capture-only requires MOVEMENT_ALIGNMENT_EVIDENCE');
  if (evidence) await mkdir(evidence, { recursive: true });
  const timing = (benchmarkOnly || evidence) ? await benchmark() : null;
  if (timing) {
    console.log('50 degree pan during move:', JSON.stringify({ ...timing, intervals: undefined }));
    if (evidence) await writeFile(join(evidence, 'frame-intervals.json'), JSON.stringify({ ...timing, browser: browser.version() }, null, 2));
  }
  if (!benchmarkOnly) {
    const capture = await motionCapture({ zoom: true }, evidence && join(evidence, 'pan.png'));
    console.log('50 degree still and pan:', JSON.stringify({ still: capture.stillSummary, pan: capture.panSummary }));
    if (evidence) {
      await writeFile(join(evidence, 'alignment.json'), JSON.stringify({ ...capture, browser: browser.version() }));
      await writeFile(join(evidence, 'geometry.csv'), geometryCsv(capture.trace));
    }
    if (!captureOnly) {
      assertAligned(capture.trace, '50 degree home');
      assertAligned([capture.final], '50 degree home settled');
      assertMoving(capture.trace.filter(frame => frame.active && frame.phase === 'still'), actorPosition);
      await reducedMotionToggle();
      await possessionChanges();
      await diagonalPushAndHits();
      await revealCamera();
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
      console.log('PASS: intermediate pitch alignment at all angles and both ends, carried and loose ball, pan reversal and release, zoom, reveal, resize, possession changes, diagonal pushes, hit testing, replay, reduced motion, idle pan, and provisional playback.');
    }
  }
  assert.deepEqual(errors, []);
} finally {
  await browser.close();
  await server.close();
}
