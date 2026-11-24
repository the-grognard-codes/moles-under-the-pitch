import { setCameraAngle, setCameraMode } from './camera-controls.mjs';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { mkdir } from 'node:fs/promises';
import { assertPitchWithoutStadium } from './pitch-appearance-helper.mjs';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';
import { createServer } from './browser-test-server.mjs';
import { squarePosition, travelToFocus } from './projected-pitch-helper.mjs';
import { resolvePlayerArt } from '../src/player-art.ts';
import { PitchProjection } from '../src/pitch-projection.ts';
import { bowl } from '../src/stadium-bowl-study.ts';
import { teamColorRamps } from '../src/team-color-art.ts';

// Rendering/input regression evidence only; full live acceptance uses real v2.
const initial = JSON.parse(readFileSync(new URL('./fixtures/m5a-blitz-projections.json', import.meta.url), 'utf8'))[0].actor;
const players = [
  { ...initial.players[0], id: 'human', x: 12, y: 7, art: { rosterId: 'human', positionId: 'blitzer' } },
  { ...initial.players[1], id: 'orc', x: 13, y: 7, art: { rosterId: 'orc', positionId: 'troll' } },
  { ...initial.players[0], id: 'prone', x: 12, y: 6, state: 'is prone', art: { rosterId: 'human', positionId: 'ogre' } },
  { ...initial.players[1], id: 'stunned', x: 13, y: 6, state: 'has been stunned', art: { rosterId: 'orc', positionId: 'goblin-lineman' } },
];
const root = fileURLToPath(new URL('../', import.meta.url));
const server = await createServer({ root, configFile: false, server: { host: '127.0.0.1', port: 0 } });
await server.listen();
const browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH
  || (process.platform === 'win32' ? 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe' : undefined) });
const errors = [];
const stadiumRequests = new WeakMap();
const gatehousePath = '/pitch/stadiums/gatehouse/';
const evidence = process.env.PITCH_SCENE_EVIDENCE_DIR;
if (evidence) await mkdir(evidence, { recursive: true });
async function open(role, failArt = false, diceMoment = null, pitchOptions = {}) {
  const page = await browser.newPage({ viewport: { width: 1280, height: 660 } });
  const requests = [];
  stadiumRequests.set(page, requests);
  page.on('request', request => { if (request.url().includes('/pitch/stadiums/')) requests.push(request.url()); });
  page.on('pageerror', error => errors.push(error.message));
  if (pitchOptions.failTurf) await page.route('**/pitch/stadium-v1.png', route => route.fulfill({ status: 404, body: '' }));
  if (failArt) await page.route('**/poses/**/master/*.png', route => route.fulfill({ status: 404, body: '' }));
  if (pitchOptions.failGatehouse) await page.route('**/pitch/stadiums/gatehouse/**', route => route.fulfill({ status: 404, body: '' }));
  await page.addInitScript(({ state, dice, actions, pinnedAction, zoom }) => { window.initial = state; window.initialDice = dice;
    window.initialActions = actions; window.initialPinnedAction = pinnedAction; window.initialZoom = zoom; window.intents = []; },
    { state: { ...initial, players: pitchOptions.players ?? players, callerRole: role, activePlayerId: 'human', ball: pitchOptions.ball ?? { x: 12, y: 8 },
      ballState: pitchOptions.ballState ?? { version: 1, carrierPlayerId: null, inPlay: true, moving: true },
      homeTeamArt: pitchOptions.homeTeamArt ?? { ...initial.homeTeamArt, league: null },
      awayTeamArt: pitchOptions.awayTeamArt ?? initial.awayTeamArt,
      homeTeamName: pitchOptions.homeTeamName ?? initial.homeTeamName, awayTeamName: pitchOptions.awayTeamName ?? initial.awayTeamName,
      phase: typeof diceMoment === 'string' ? diceMoment : pitchOptions.phase ?? initial.phase }, dice: typeof diceMoment === 'string' ? null : diceMoment,
      actions: pitchOptions.actions, pinnedAction: pitchOptions.pinnedAction, zoom: pitchOptions.zoom });
  await page.route('**/scene-test', route => route.fulfill({ contentType: 'text/html', body: `
    <style>html,body{margin:0;height:100%;background:#101c2b}.play-runtime{height:100%}#app .live-pitch{height:100%;display:flex;flex-direction:column;margin:0;padding:0;box-sizing:border-box}#app .live-pitch-viewport{flex:1;max-height:none;min-height:0;aspect-ratio:auto}</style>
    <div id="app" class="play-runtime"></div><script type="module" src="/test/projected-pitch-harness.tsx"></script>` }));
  await page.goto(`http://127.0.0.1:${server.httpServer.address().port}/scene-test`);
  await page.locator('[data-player-id="human"]').waitFor();
  await page.waitForFunction(() => [...document.querySelectorAll('.live-marker img')].every(image => image.complete));
  return page;
}
async function assertProjectedBallAndPitch(page, role, mode, angle, ball = { x: 12, y: 8 }) {
  const scene = page.locator('.live-pitch-scene');
  if (await scene.locator('.live-ball-football').count()) {
    const tip = await scene.locator('.live-ball-football').evaluate(image => {
      const style = getComputedStyle(image), width = parseFloat(style.width), height = parseFloat(style.height);
      const offset = new DOMMatrix(style.transform).transformPoint(new DOMPoint(0, height * .45));
      return { x: parseFloat(style.left) + width / 2 + offset.x,
        y: parseFloat(style.top) + height / 2 + offset.y, rendering: style.imageRendering };
    });
    assert.ok(Math.abs(tip.x) < .02 && Math.abs(tip.y) < .02, `${role} ${mode} football tip shares the arrows' ground anchor`);
    assert.equal(tip.rendering, 'pixelated');
  }
  const rendered = await scene.evaluate(element => {
    const ball = element.querySelector('.live-ball-marker');
    const highlight = element.querySelector('.live-ball-highlight');
    const ground = new DOMMatrix(getComputedStyle(highlight).transform);
    const corners = [[0,0], [32,0], [32,32], [0,32]].map(([x,y]) => {
      const p = ground.transformPoint(new DOMPoint(x,y));
      return { x: p.x / p.w, y: p.y / p.w };
    });
    const rect = highlight.getBoundingClientRect(), sceneRect = element.getBoundingClientRect();
    return { width: Number.parseFloat(element.style.width), height: Number.parseFloat(element.style.height),
      focus: Number(element.dataset.focus), transverseFocus: Number(element.dataset.transverseFocus),
      zoom: Number(element.dataset.zoom), center: { x: parseFloat(ball.style.left), y: parseFloat(ball.style.top) }, corners,
      bounds: { x: rect.x - sceneRect.x, y: rect.y - sceneRect.y, width: rect.width, height: rect.height },
      pointerEvents: getComputedStyle(ball).pointerEvents, rotations: Array.from(element.querySelectorAll('.live-ball-highlight animateTransform')).map(animation => ({
        from: animation.getAttribute('from'), to: animation.getAttribute('to'), duration: animation.getAttribute('dur'), mode: animation.getAttribute('calcMode') })),
      arrowFill: getComputedStyle(element.querySelector('.live-ball-arrow')).fill };
  });
  const camera = new PitchProjection({ width: rendered.width, height: rendered.height, focus: rendered.focus,
    transverseFocus: rendered.transverseFocus, zoom: rendered.zoom, end: role, mode,
    perspectiveElevation: angle });
  const expected = camera.project({ x: ball.x + .5, y: ball.y + .5 });
  assert.ok(expected, 'ball marker has a projected center');
  assert.ok(Math.abs(rendered.center.x - expected.x) < .02 && Math.abs(rendered.center.y - expected.y) < .02,
    'ball marker uses canonical square center');
  const square = camera.square(ball);
  for (let index = 0; index < 4; index++) assert.ok(Math.hypot(rendered.corners[index].x - square[index].x,
    rendered.corners[index].y - square[index].y) < .02, 'marker plane matches the square perspective at every corner');
  const width = Math.max(...square.map(p => p.x)) - Math.min(...square.map(p => p.x));
  const height = Math.max(...square.map(p => p.y)) - Math.min(...square.map(p => p.y));
  assert.ok(Math.abs(rendered.bounds.x - Math.min(...square.map(p => p.x))) < .1
    && Math.abs(rendered.bounds.y - Math.min(...square.map(p => p.y))) < .1
    && Math.abs(rendered.bounds.width - width) < .1 && Math.abs(rendered.bounds.height - height) < .1,
    'browser paints the marker inside the projected square');
  assert.equal(rendered.pointerEvents, 'none');
  assert.deepEqual(rendered.rotations, [
    { from: '0 16 16', to: '360 16 16', duration: '24s', mode: 'linear' },
    { from: '0 16 16', to: '-360 16 16', duration: '18s', mode: 'linear' },
  ]);
  assert.notEqual(rendered.arrowFill, 'none');
  await assertPitchWithoutStadium(page);
  assert.equal(await scene.locator('.live-ball-arrow').count(), 4);
}

async function assertGatehouseAlignment(page, end) {
  const scene = page.locator('.live-pitch-scene');
  const scenery = scene.locator(`.pitch-gatehouse[data-end="${end}"] canvas`);
  await scenery.waitFor({ state: 'attached' });
  assert.equal(await scene.locator('.pitch-stadium-turf').count(), 0);
  const rendered = await scenery.evaluate((canvas, opening) => {
    const stadium = canvas.closest('.pitch-gatehouse');
    const scene = canvas.closest('.live-pitch-scene');
    const matrix = new DOMMatrix(getComputedStyle(stadium).transform);
    const { pitchX, pitchY } = opening;
    const corners = [[pitchX, pitchY], [pitchX + 480, pitchY], [pitchX, pitchY + 832],
      [pitchX + 480, pitchY + 832]].map(([u, v]) => {
      const point = matrix.transformPoint(new DOMPoint(u, v));
      return { x: point.x / point.w, y: point.y / point.w };
    });
    return {
      camera: { width: parseFloat(scene.style.width), height: parseFloat(scene.style.height),
        focus: +scene.dataset.focus, transverseFocus: +scene.dataset.transverseFocus,
        zoom: +scene.dataset.zoom, end: scene.dataset.end, mode: scene.dataset.projection },
      corners, size: [canvas.width, canvas.height], pointerEvents: getComputedStyle(canvas).pointerEvents,
      sampling: getComputedStyle(canvas).imageRendering,
    };
  }, bowl);
  assert.deepEqual(rendered.size, [bowl.width, bowl.height]);
  assert.equal(rendered.pointerEvents, 'none');
  assert.equal(rendered.sampling, 'pixelated');
  const camera = new PitchProjection(rendered.camera);
  const world = end === 'home' ? [[26, 0], [26, 15], [0, 0], [0, 15]] : [[0, 15], [0, 0], [26, 15], [26, 0]];
  for (const [index, [x, y]] of world.entries()) {
    const expected = camera.project({ x, y });
    const actual = rendered.corners[index];
    assert.ok(Math.hypot(actual.x - expected.x, actual.y - expected.y) < .5,
      `${end} ${camera.zoom}× native pitch corner ${x},${y} projects to its canonical square`);
  }
}

async function assertGatehouseArtwork(page, end, teams) {
  const ramp = id => teamColorRamps.find(ramp => ramp.id === id).colors;
  const teamRamps = Object.fromEntries(Object.entries(teams).map(([role, team]) => [role, ramp(team.colors.primary)]));
  const worldOwners = end === 'home' ? { left: 'home', right: 'away', top: 'away', bottom: 'home' }
    : { left: 'away', right: 'home', top: 'home', bottom: 'away' };
  const result = await page.locator('.pitch-gatehouse canvas').evaluate(async (canvas, { teams, teamRamps, worldOwners, bowl }) => {
    const context = canvas.getContext('2d');
    function countMatching({ x, y, width, height }, expected) {
      const { data } = context.getImageData(x, y, width, height);
      let count = 0;
      for (let i = 0; i < data.length; i += 4) {
        if (expected.has((data[i] << 16) + (data[i + 1] << 8) + data[i + 2])) count++;
      }
      return count;
    }
    const { pitchX, pitchY } = bowl;
    const regions = { left: { x: 14, y: pitchY + 48, width: 80, height: 735 },
      right: { x: 694, y: pitchY + 48, width: 80, height: 735 } };
    const fans = Object.fromEntries(Object.entries(regions).map(([side, region]) => {
      const owner = worldOwners[side];
      const other = owner === 'home' ? 'away' : 'home';
      const count = role => countMatching(region, new Set(teamRamps[role].map(color => parseInt(color.slice(1), 16))));
      return [side, [count(owner), count(other)]];
    }));
    const paint = document.createElement('canvas');
    paint.width = canvas.width;
    paint.height = canvas.height;
    const expected = paint.getContext('2d');
    const { drawGatehouseEndZones } = await import('/src/stadium-end-zones.ts');
    const zoneTeam = side => {
      const { name, colors } = teams[worldOwners[side]];
      return { name, primary: colors.primary };
    };
    drawGatehouseEndZones(expected, { x: pitchX, y: pitchY, width: 480, height: 832 }, 32,
      { north: zoneTeam('top'), south: zoneTeam('bottom') });
    const endZones = [pitchY, pitchY + 800].map(top => {
      const target = expected.getImageData(pitchX, top, 480, 32).data;
      const live = context.getImageData(pitchX, top, 480, 32).data;
      let count = 0, same = 0;
      for (let i = 0; i < target.length; i += 4) {
        const rgb = (target[i] << 16) + (target[i + 1] << 8) + target[i + 2];
        if (rgb !== 0xfdf6e1 && rgb !== 0x07080b) continue;
        count++;
        if (target.subarray(i, i + 4).every((channel, offset) => live[i + offset] === channel)) same++;
      }
      return [count, same];
    });
    return { fans, endZones };
  }, { teams, teamRamps, worldOwners, bowl });
  if (teams.home.colors.primary !== teams.away.colors.primary) {
    for (const [side, [own, other]] of Object.entries(result.fans))
      assert.ok(own > 100 && own > other * 2, `owner kit dominates the ${side} crowd from the ${end} end (${own} vs ${other})`);
  }
  for (const [total, matching] of result.endZones) {
    assert.ok(total > 300 && matching / total > .9, `${end} end-zone paint keeps the defending team's name upright`);
  }
}
try {
  {
    const page = await open('home');
    await setCameraMode(page, 'top-down');
    await squarePosition(page, 13, 7);
    // Leave room for a natural drag start, then cover the remaining coarse
    // mouse samples as a floating panel can. Travel must capture before it.
    await page.locator('.live-pitch-viewport').evaluate(viewport => {
      const bounds = viewport.getBoundingClientRect();
      const panel = document.createElement('div');
      panel.id = 'capture-test-panel';
      panel.textContent = 'Floating panel';
      Object.assign(panel.style, { position: 'fixed', left: `${bounds.left}px`, top: `${bounds.top}px`,
        width: `${bounds.width}px`, height: `${bounds.height / 2 - 12}px`, zIndex: '9999' });
      document.body.append(panel);
    });
    const target = await squarePosition(page, 0, 7);
    await page.locator('#capture-test-panel').evaluate(panel => panel.remove());
    await page.locator('.live-pitch-scene').click({ position: target });
    assert.deepEqual(await page.evaluate(() => window.intents), [{ x: 0, y: 7 }],
      'Automated travel captures the pointer before crossing a floating panel and preserves canonical targeting');
    await page.close();
    console.log('PASS: automated pitch travel captures before crossing a floating panel.');
  }
  for (const role of ['home', 'away']) for (const carried of [false, true]) {
    const page = await open(role, false, null, { ball: { x: 12, y: 7 },
      ballState: { version: 1, carrierPlayerId: carried ? 'human' : null, inPlay: true, moving: !carried } });
    assert.equal(await page.locator('.live-ball-football').count(), carried ? 0 : 1,
      'An occupied loose ball remains distinct from native possession');
    for (const angle of [30, 40, 50, 90]) {
      if (angle === 90) await setCameraMode(page, 'top-down');
      else await setCameraAngle(page, String(angle));
      await assertProjectedBallAndPitch(page, role, angle === 90 ? 'top-down' : 'perspective', angle === 90 ? 40 : angle, { x: 12, y: 7 });
      if (evidence && [40,90].includes(angle)) await page.screenshot({ path: `${evidence}/ball-${role}-${carried ? 'carried' : 'occupied-loose'}-${angle}.png` });
    }
    await page.close();
  }
  for (const role of ['home', 'away']) {
    const page = await open(role);
    console.log('Camera interaction checks:', role);
    const scene = page.locator('.live-pitch-scene'), frame = page.locator('.live-pitch-viewport');
    assert.equal(await scene.getAttribute('data-end'), role);
    assert.equal(await scene.locator('[data-cell-x]').count(), 390);
    assert.equal(await scene.locator('.live-ball-marker[data-ball-x="12"][data-ball-y="8"]').count(), 1);
    assert.equal(await scene.locator('.live-ball-arrow').count(), 4);
    await assertPitchWithoutStadium(page);
    assert.equal(await scene.locator('[data-player-id="prone"]').getAttribute('data-pose'), 'prone');
    assert.equal(await scene.locator('[data-player-id="stunned"]').getAttribute('data-pose'), 'stunned');
    assert.equal(await scene.locator('[data-player-id="human"]').getAttribute('data-pose'), role === 'home' ? 'back' : 'front');
    await scene.locator('[data-player-id="orc"]').hover();
    assert.deepEqual(await page.evaluate(() => window.intents), []);
    // A nearer sprite canvas overlaps this farther player, but cannot steal its cell.
    await scene.locator('[data-player-id="orc"]').click();
    assert.deepEqual(await page.evaluate(() => window.intents), [{ player: 'orc' }]);
    await page.evaluate(() => { window.intents = []; });
    await scene.locator('[data-player-id="human"]').click();
    assert.deepEqual(await page.evaluate(() => window.intents), [{ player: 'human' }]);
    assert.equal(await scene.locator('.live-selection-square[data-selection]').getAttribute('data-selection'), 'human');
    assert.equal(await scene.locator('.live-marker.selected').evaluate(element => getComputedStyle(element).outlineStyle), 'none');
    const before = await scene.locator('.pitch-stadium-plate').first().getAttribute('style');
    await frame.dispatchEvent('wheel', { deltaY: 120 });
    await page.waitForFunction(() => Number(document.querySelector('.live-pitch-scene').dataset.zoom) < 1);
    assert.equal(await scene.getAttribute('data-focus'), '13', 'wheel zoom leaves travel unchanged');
    const wheelZoom = await scene.getAttribute('data-zoom');
    await frame.dispatchEvent('wheel', { deltaY: 120, ctrlKey: true });
    assert.equal(await scene.getAttribute('data-zoom'), wheelZoom, 'control-wheel leaves browser zoom gesture to the browser');
    assert.notEqual(await scene.locator('.pitch-stadium-plate').first().getAttribute('style'), before);
    const focus = await scene.getAttribute('data-focus');
    for (const angle of [30, 50, 40]) {
      await setCameraAngle(page, String(angle));
      await page.waitForFunction(angle => document.querySelector('.live-pitch-scene').dataset.elevation === angle, String(angle), { timeout: 1500 });
      assert.equal(await scene.getAttribute('data-elevation'), String(angle));
      assert.equal(await scene.locator('[data-shadow-player]').count(), players.length);
      assert.equal(await scene.locator('[data-player-id="human"]').getAttribute('data-anchor-mode'), 'feet');
      assert.equal(await scene.locator('[data-player-id="prone"]').getAttribute('data-anchor-mode'), 'ground');
      for (const player of players) {
        const body = resolvePlayerArt(player, { end: role }).body;
        const anchor = body.pose === 'prone' || body.pose === 'stunned' ? body.groundAnchor : body.footAnchor;
        const offset = await scene.locator(`[data-player-id="${player.id}"]`).evaluate((marker, anchor) => {
          const image = marker.querySelector('img'), surface = marker.closest('.live-pitch-scene').getBoundingClientRect();
          const rect = image.getBoundingClientRect(), scale = rect.width / image.naturalWidth;
          return { x: rect.left - surface.left + anchor.x * scale - Number(marker.dataset.centerX),
            y: rect.top - surface.top + anchor.y * scale - Number(marker.dataset.centerY) };
        }, anchor);
        assert.ok(Math.abs(offset.x) < .1 && Math.abs(offset.y) < .1, `${role} ${angle}° ${player.id} anchor follows square`);
        const shadowOffset = await scene.locator(`[data-shadow-player="${player.id}"]`).evaluate((shadow, center) => {
          const coordinates = shadow.getAttribute('points').trim().split(' ').map(pair => pair.split(',').map(Number));
          const xs = coordinates.map(pair => pair[0]), ys = coordinates.map(pair => pair[1]);
          return { x: (Math.min(...xs) + Math.max(...xs)) / 2 - center.x,
            y: (Math.min(...ys) + Math.max(...ys)) / 2 - center.y };
        }, await scene.locator(`[data-player-id="${player.id}"]`).evaluate(marker => ({ x: Number(marker.dataset.centerX), y: Number(marker.dataset.centerY) })));
        assert.ok(Math.abs(shadowOffset.x) < 2 && Math.abs(shadowOffset.y) < 2, `${role} ${angle}° ${player.id} shadow follows square`);
      }
      assert.equal(await scene.getAttribute('data-focus'), focus);
    assert.equal(await scene.locator('.live-selection-square[data-selection]').getAttribute('data-selection'), 'human');
      assert.equal(await scene.locator('.live-route-line').count(), 1);
      assert.deepEqual(await page.evaluate(() => window.intents), [{ player: 'human' }]);
      const target = await squarePosition(page, 12, 10);
      await scene.click({ position: target });
      assert.deepEqual(await page.evaluate(() => window.intents.at(-1)), { x: 12, y: 10 }, 'Each elevation resolves the same canonical ground target');
      await page.evaluate(() => { window.intents.pop(); });
      if (evidence && angle !== 40) await page.screenshot({ path: `${evidence}/perspective-${angle}-${role}.png` });
    }
    await setCameraAngle(page, '30');
    await page.getByRole('button', { name: '2×', exact: true }).click();
    await frame.dispatchEvent('wheel', { deltaY: -1000 });
    await page.waitForFunction(() => Number(document.querySelector('.live-pitch-scene').dataset.zoom) === 3);
    for (const [label, destinationFocus] of [['near', role === 'home' ? 26 : 0], ['far', role === 'home' ? 0 : 26]]) {
      await travelToFocus(page, destinationFocus);
        const crossing = await scene.locator('.pitch-stadium-plate').evaluateAll(plates => plates.map(plate => {
          const values = plate.style.transform.slice(9, -1).split(',').map(Number);
          const depths = [0, 941].map(y => values[3] * 0 + values[7] * y + values[15]);
          return { offset: plate.dataset.worldOffset, depths, rect: plate.getBoundingClientRect().toJSON() };
        }).filter(plate => plate.depths.some(depth => depth <= 0)));
        assert.deepEqual(crossing, [], `30 degree ${role} ${label} plate must not cross the browser perspective plane`);
        const widestSurface = await scene.locator('.pitch-stadium-world').evaluate(world => Math.max(
          ...[...world.querySelectorAll('.pitch-stadium-plate, .pitch-stadium-strip')].map(surface => surface.getBoundingClientRect().width)));
        assert.ok(widestSurface < 16384, `30 degree ${role} ${label} scenery surface exceeds compositor texture limit: ${widestSurface}`);
      if (evidence) await page.screenshot({ path: `${evidence}/perspective-30-${role}-${label}.png` });
    }
    await page.getByRole('button', { name: 'Midfield', exact: true }).click();
    await page.getByRole('button', { name: 'Fit', exact: true }).click();
    await frame.dispatchEvent('wheel', { deltaY: 120 });
    await setCameraAngle(page, '40');
    await setCameraMode(page, 'top-down');
    assert.equal(await scene.getAttribute('data-focus'), focus);
    assert.equal(await scene.getAttribute('data-projection'), 'top-down');
    assert.equal(await scene.locator('[data-player-id="human"]').getAttribute('data-anchor-mode'), 'body-center');
    assert.equal(await scene.locator('[data-player-id="prone"]').getAttribute('data-anchor-mode'), 'ground');
    const centered = await scene.locator('.live-marker').evaluateAll(markers => markers.every(marker => {
      const x = parseFloat(marker.style.left) + parseFloat(marker.style.width) / 2;
      const y = parseFloat(marker.style.top) + parseFloat(marker.style.height) / 2;
      return Math.abs(x - Number(marker.dataset.centerX)) < .001 && Math.abs(y - Number(marker.dataset.centerY)) < .001;
    }));
    assert.equal(centered, true, 'tactical visible bounds and ground poses center within their canonical cells');
    for (const player of players) {
      const art = resolvePlayerArt(player, { end: role, topDown: true });
      const anchor = ['prone', 'stunned'].includes(art.body.pose) ? art.body.groundAnchor
        : art.body.bodyAnchor;
      const offset = await scene.locator(`[data-player-id="${player.id}"]`).evaluate((marker, anchor) => {
        const image = marker.querySelector('img');
        const scale = parseFloat(image.style.width) / image.naturalWidth;
        const scene = marker.closest('.live-pitch-scene').getBoundingClientRect();
        const imageRect = image.getBoundingClientRect();
        const left = imageRect.left - scene.left;
        const top = imageRect.top - scene.top;
        return { dx: left + anchor.x * scale - Number(marker.dataset.centerX),
          dy: top + anchor.y * scale - Number(marker.dataset.centerY) };
      }, anchor);
      assert.ok(Math.abs(offset.dx) < .04 && Math.abs(offset.dy) < .04, `visible ${player.id} artwork is centered`);
      const contact = ['prone', 'stunned'].includes(art.body.pose) ? art.body.groundAnchor : art.body.footAnchor;
      const shadowOffset = await scene.locator(`[data-shadow-player="${player.id}"]`).evaluate((shadow, { id, contact }) => {
        const marker = shadow.closest('.live-pitch-scene').querySelector(`[data-player-id="${id}"]`);
        const image = marker.querySelector('img'), surface = marker.closest('.live-pitch-scene').getBoundingClientRect();
        const rect = image.getBoundingClientRect(), scale = rect.width / image.naturalWidth;
        const coordinates = shadow.getAttribute('points').split(' ').map(pair => pair.split(',').map(Number));
        const xs = coordinates.map(pair => pair[0]), ys = coordinates.map(pair => pair[1]);
        return { dx: (Math.min(...xs) + Math.max(...xs)) / 2 - (rect.left - surface.left + contact.x * scale),
          dy: (Math.min(...ys) + Math.max(...ys)) / 2 - (rect.top - surface.top + contact.y * scale) };
      }, { id: player.id, contact });
      assert.ok(Math.abs(shadowOffset.dx) < .05 && Math.abs(shadowOffset.dy) < .05, `${role} ${player.id} shadow is beneath rendered ground contact`);

    }
    const destination = await squarePosition(page, 10, 9);
    await scene.click({ position: destination });
    assert.deepEqual(await page.evaluate(() => window.intents), [{ player: 'human' }, { x: 10, y: 9 }]);
    const box = await frame.boundingBox();
    const panFocus = await scene.getAttribute('data-focus');
    const turfPlate = await scene.locator('.pitch-stadium-plate').first().getAttribute('style');
    await page.mouse.move(box.x + box.width * .35, box.y + box.height * .7);
    await page.mouse.down({ button: 'right' }); await page.mouse.move(box.x + box.width * .35, box.y + box.height * .7 - 90, { steps: 8 }); await page.mouse.up({ button: 'right' });
    await page.waitForFunction(before => document.querySelector('.live-pitch-scene').dataset.focus !== before, panFocus);
    assert.notEqual(await scene.locator('.pitch-stadium-plate').first().getAttribute('style'), turfPlate, 'turf tiles travel with the camera');
    assert.equal(await page.evaluate(() => window.intents.length), 2, 'drag to pan does not select or commit');
    const afterPan = await scene.evaluate(element => ({ width: Number.parseFloat(element.style.width), height: Number.parseFloat(element.style.height),
      focus: Number(element.dataset.focus), transverseFocus: Number(element.dataset.transverseFocus),
      zoom: Number(element.dataset.zoom), end: element.dataset.end, mode: element.dataset.projection,
      perspectiveElevation: 40 }));
    const panCamera = new PitchProjection(afterPan);
    const empty = panCamera.visibleCells().map(cell => ({ cell, projected: panCamera.project({ x: cell.x + .5, y: cell.y + .5 }) }))
      .find(({ cell, projected }) => !players.some(player => player.x === cell.x && player.y === cell.y)
        && projected.x > afterPan.width * .3 && projected.x < afterPan.width * .7
        && projected.y > afterPan.height * .35 && projected.y < afterPan.height * .65);
    assert.ok(empty, 'an unobstructed cell is visible after panning');
    const pannedBounds = await scene.boundingBox();
    await scene.click({ position: { x: empty.projected.x * pannedBounds.width / afterPan.width,
      y: empty.projected.y * pannedBounds.height / afterPan.height } });
    assert.deepEqual(await page.evaluate(() => window.intents.at(-1)), empty.cell, 'first left click after right pan is delivered');
    assert.equal(await page.evaluate(() => window.intents.length), 3, 'first left click after right pan produces one intent');
    assert.equal(await frame.evaluate(element => element.dispatchEvent(new MouseEvent('contextmenu', { bubbles: true, cancelable: true, button: 2 }))), false);
    const cancelledFocus = await scene.getAttribute('data-focus');
    await page.mouse.down({ button: 'right' });
    await frame.dispatchEvent('pointercancel', { pointerId: 1, button: 2 });
    await page.mouse.move(box.x + box.width * .35, box.y + box.height * .7 - 160);
    await page.mouse.up({ button: 'right' });
    assert.equal(await scene.getAttribute('data-focus'), cancelledFocus, 'pointer cancellation ends camera travel');
    await frame.focus(); await frame.press('ArrowUp'); await frame.press('ArrowRight');
    assert.equal(await scene.locator('.live-keyboard-square').count(), 1);
    assert.equal(await page.evaluate(() => window.intents.length), 3, 'keyboard navigation never mutates');
    await page.getByRole('button', { name: 'Midfield', exact: true }).click();
    for (const [width, height] of [[1920,1080],[1920,900],[1920,820],[1280,660],[375,660]]) {
      await page.setViewportSize({ width, height });
      await page.waitForFunction(() => Math.abs(parseFloat(document.querySelector('.live-pitch-scene').style.width) - document.querySelector('.live-pitch-viewport').clientWidth) < 3);
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true);
    }
    await page.setViewportSize({ width: 1280, height: 660 });
    await page.waitForFunction(() => Math.abs(parseFloat(document.querySelector('.live-pitch-scene').style.width) - document.querySelector('.live-pitch-viewport').clientWidth) < 3);
    if (evidence) await page.screenshot({ path: `${evidence}/tactical-${role}.png` });
    await setCameraMode(page, 'perspective');
    if (evidence) await page.screenshot({ path: `${evidence}/perspective-${role}.png` });
    await page.close();
  }
  for (const role of ['home', 'away']) {
    const setup = await open(role, false, 'SETUP');
    console.log('Camera setup/scenery checks:', role);
    const marker = setup.locator('[data-player-id="human"]');
    const opponent = setup.locator('[data-player-id="orc"]');
    await setup.evaluate(() => window.updatePitchView({ ...window.initial, revision: window.initial.revision + 1,
      players: window.initial.players.map(player => player.id === 'human' ? { ...player, x: 11 }
        : player.id === 'orc' ? { ...player, x: 14 } : player) }));
    await setup.waitForFunction(() => document.querySelector('[data-player-id="human"]').dataset.x === '11');
    for (const angle of [30, 40, 50]) {
      await setCameraAngle(setup, String(angle));
      assert.equal(await marker.getAttribute('data-pose'), role === 'home' ? 'back' : 'front', 'setup facing ignores movement direction in perspective');
      assert.equal(await opponent.getAttribute('data-pose'), role === 'home' ? 'front' : 'back', 'opposing team faces the other end zone');
      assert.equal(await setup.locator('[data-player-id="prone"]').getAttribute('data-pose'), 'prone');
      assert.equal(await setup.locator('[data-player-id="stunned"]').getAttribute('data-pose'), 'stunned');
      await assertProjectedBallAndPitch(setup, role, 'perspective', angle);
      if (evidence) await setup.screenshot({ path: `${evidence}/setup-perspective-${angle}-${role}.png` });
    }
    if (role === 'home') {
      await assertPitchWithoutStadium(setup);
      assert.equal(await setup.locator('.pitch-stadium-world image[href$="stadium-v1.png"]:not([data-grass-sample])').count(), 0,
        'no unmasked spectator-bearing side-tile images remain');
      assert.ok(await setup.locator('.pitch-stadium-turf').evaluateAll(images => images.length > 0 && images.every(image =>
        getComputedStyle(image).clipPath.startsWith('polygon('))), 'every source painting is clipped to its turf region');

    }
    await setCameraMode(setup, 'top-down');
    await setup.waitForFunction(expected => document.querySelector('[data-player-id="human"]').dataset.pose === expected,
      role === 'home' ? 'back' : 'front');
    assert.equal(await marker.getAttribute('data-pose'), role === 'home' ? 'back' : 'front', 'top-down standing art ignores movement facing');
    assert.equal(await opponent.getAttribute('data-pose'), role === 'home' ? 'front' : 'back', 'top-down opposing art ignores movement facing');
    await assertProjectedBallAndPitch(setup, role, 'top-down', 50);
    await setup.emulateMedia({ reducedMotion: 'reduce' });
    assert.deepEqual(await setup.locator('.live-ball-outer, .live-ball-inner').evaluateAll(elements =>
      elements.map(element => getComputedStyle(element).animationName)), ['none', 'none']);
    if (evidence) await setup.screenshot({ path: `${evidence}/setup-top-down-${role}.png` });
    await setup.close();
  }
  const humanArt = { rosterId:'human',league:'Old World Classic' }, orcArt = { rosterId:'orc',league:'Badlands Brawl' };
  const teams = {
    home: { name: 'Ironbank Rovers', colors: { version: 1, primary: 'navy', secondary: 'gold' } },
    away: { name: 'Cinderclaw Crew', colors: { version: 1, primary: 'red', secondary: 'ivory' } },
  };
  const gatehouseOptions = {
    homeTeamArt: { ...humanArt, colors: teams.home.colors }, homeTeamName: teams.home.name,
    awayTeamArt: { ...orcArt, colors: teams.away.colors }, awayTeamName: teams.away.name,
  };
  for (const end of ['home', 'away']) {
    const page = await open(end, false, null, { ...gatehouseOptions, zoom: .5 });
    const scene = page.locator('.live-pitch-scene');
    await assertPitchWithoutStadium(page);
    assert.deepEqual(stadiumRequests.get(page), [], 'angled Old World Classic requests only turf');
    await setCameraMode(page, 'top-down');
    await assertGatehouseAlignment(page, end);
    await assertGatehouseArtwork(page, end, teams);
    await page.evaluate(() => { window.retainedGatehouse = document.querySelector('.pitch-gatehouse canvas'); });
    const sameCanvas = () => page.evaluate(() => window.retainedGatehouse === document.querySelector('.pitch-gatehouse canvas'));
    const initialRequests = [...stadiumRequests.get(page)];
    assert.ok(initialRequests.some(url => url.includes(gatehousePath)), 'runtime loads only the Gatehouse delivery directory');
    for (let revision = 1; revision <= 10; revision++) {
      const x = revision % 2 ? 11 : 12;
      await page.evaluate(({ revision, x }) => window.updatePitchView({ ...window.initial,
        revision: window.initial.revision + revision,
        players: window.initial.players.map(player => player.id === 'human' ? { ...player, x } : player) }), { revision, x });
      await scene.locator(`[data-player-id="human"][data-x="${x}"]`).waitFor();
      assert.equal(await sameCanvas(), true, 'moving players keeps the cached native composition');
    }
    await page.locator('.live-pitch-viewport').dispatchEvent('wheel', { deltaY: -2400 });
    await page.waitForFunction(() => document.querySelector('.live-pitch-scene').dataset.zoom === '3');
    await assertGatehouseAlignment(page, end);
    await page.getByRole('button', { name: 'Fit', exact: true }).click();
    await assertGatehouseAlignment(page, end);
    await scene.locator('[data-player-id="human"]').click();
    const x = end === 'home' ? 0 : 25, y = 0;
    const edge = await squarePosition(page, x, y);
    await scene.hover({ position: edge });
    await scene.locator(`[data-hover-square="${x},${y}"]`).waitFor({ state: 'attached' });
    await scene.click({ position: edge });
    assert.deepEqual(await page.evaluate(() => window.intents), [{ player: 'human' }, { x, y }]);
    await setCameraAngle(page, '40');
    await assertPitchWithoutStadium(page);
    await setCameraMode(page, 'top-down');
    await assertGatehouseAlignment(page, end);
    assert.equal(await sameCanvas(), true, 'returning from the angled camera reattaches the cached canvas');
    assert.deepEqual(stadiumRequests.get(page), initialRequests, 'zoom, travel and angle changes do not reload Gatehouse');
    await page.getByRole('button', { name: 'Midfield', exact: true }).click();
    await scene.locator('[data-selection="human"]').waitFor({ state: 'attached' });
    await scene.locator('.live-ball-marker[data-ball-x="12"][data-ball-y="8"]').waitFor({ state: 'attached' });
    if (evidence) {
      await page.screenshot({ path: `${evidence}/gatehouse-${end}.png` });
    }
    console.log(`Gatehouse ${end} native composition: ${Math.round(Number(await scene.locator('.pitch-gatehouse').getAttribute('data-compose-ms')))} ms.`);
    await page.close();
  }
  const matchingKits = await open('away', false, null, { ...gatehouseOptions, awayTeamArt: { ...orcArt, colors: teams.home.colors } });
  await setCameraMode(matchingKits, 'top-down');
  await assertGatehouseAlignment(matchingKits, 'away');
  await assertGatehouseArtwork(matchingKits, 'away', { ...teams, away: { ...teams.away, colors: teams.home.colors } });
  await matchingKits.close();
  for (const [reason, options] of [
    ['missing venue', { homeTeamArt: { ...humanArt, league: null } }],
    ['unknown venue', { homeTeamArt: { ...humanArt, league: 'Unknown venue' } }],
    ['missing Gatehouse assets', { ...gatehouseOptions, failGatehouse: true }],
  ]) {
    const page = await open('home', false, null, options);
    const diagnostic = options.failGatehouse ? page.waitForEvent('console', {
      predicate: message => message.type() === 'warning' && message.text().includes('Gatehouse unavailable'),
    }) : undefined;
    await setCameraMode(page, 'top-down');
    if (options.failGatehouse) {
      await diagnostic;
      assert.ok(stadiumRequests.get(page).some(url => url.includes(gatehousePath)));
    } else {
      assert.deepEqual(stadiumRequests.get(page), [], reason + ' makes no stadium requests');
    }
    await assertPitchWithoutStadium(page);
    const edge = await squarePosition(page, 0, 0);
    await page.locator('.live-pitch-scene').click({ position: edge });
    assert.deepEqual(await page.evaluate(() => window.intents), [{ x: 0, y: 0 }]);
    await page.close();
  }
  console.log('PASS: native Gatehouse registration, edge targeting, kits, end zones, cache, and turf fallbacks from both coach ends.');
  for (const [host,homeTeamArt,awayTeamArt] of [['human',humanArt,orcArt],['orc',orcArt,humanArt]]) for (const role of ['home','away']) {
    const page=await open(role,false,null,{homeTeamArt,awayTeamArt});
    await page.emulateMedia({reducedMotion:'reduce'});
    await assertPitchWithoutStadium(page);
    await page.evaluate(() => { window.retainedTurf = document.querySelector('.pitch-stadium-turf'); });
    await page.evaluate(() => window.updatePitchView({ ...window.initial, revision: window.initial.revision + 1,
      players: window.initial.players.map(player => player.id === 'human' ? { ...player, x: 11 } : player) }));
    await page.waitForFunction(() => document.querySelector('[data-player-id="human"]').dataset.x === '11');
    assert.equal(await page.evaluate(() => document.querySelector('.pitch-stadium-turf') === window.retainedTurf), true,
      'accepted player movement retains the existing turf nodes');
    for (const mode of [30,40,50,90]) {
      if(mode===90)await setCameraMode(page, 'top-down');
      else await setCameraAngle(page, String(mode));
      for (const [position,focus] of [['near',role==='home'?1:25],['mid',13],['far',role==='home'?25:1]]) {
        await travelToFocus(page,focus);
        assert.equal(await page.locator('[data-cell-x]').count(),390);
        if (mode === 90 && host === 'human') await assertGatehouseAlignment(page, role);
        else await assertPitchWithoutStadium(page);
        if(evidence)await page.screenshot({path:evidence+'/stadium-'+host+'-'+role+'-'+mode+'-'+position+'.png'});
      }
    }
    await page.evaluate(()=>window.updatePitchView({...window.initial,revision:window.initial.revision+1,actor:'away',half:2,homeTurn:8,awayTurn:8,players:[]}));
    await page.waitForFunction(()=>document.querySelectorAll('[data-player-id]').length===0);
    if (host === 'human') await assertGatehouseAlignment(page, role);
    else {
      await assertPitchWithoutStadium(page);
      assert.deepEqual(stadiumRequests.get(page), [], 'unsupported venue makes no stadium requests');
    }
    await page.close();
  }
  console.log('PASS: 48 venue, coach-end, angle and camera-position cases select the correct scenery.');
  const readonly = await open('spectator');
  await readonly.locator('[data-player-id="human"]').click();
  await readonly.locator('.live-pitch-viewport').dispatchEvent('wheel', { deltaY: 90 });
  await readonly.getByRole('button', { name: /^Flip perspective:/ }).click();
  assert.deepEqual(await readonly.evaluate(() => window.intents), []);
  await assertPitchWithoutStadium(readonly);
  await readonly.emulateMedia({ reducedMotion: 'reduce' });
  await assertPitchWithoutStadium(readonly);
  await readonly.close();
  const kickAction = { id: 'kick:10:9', label: 'Kick to 10,9', kind: 'kickoff', actor: 'home', target: { x: 10, y: 9 } };
  const kickoff = await open('home', false, null, { phase: 'READY_FOR_KICKOFF', actions: [kickAction], pinnedAction: kickAction });
  const kickBall = kickoff.locator('.live-ball-marker[data-ball-x="10"][data-ball-y="9"]');
  await kickBall.waitFor({ state: 'attached' });
  await kickBall.locator('.live-ball-football').waitFor();
  assert.equal(await kickBall.locator('.live-ball-football').count(), 1);
  assert.equal(await kickBall.locator('.live-ball-highlight').count(), 1);
  assert.equal(await kickoff.locator('.live-target-square').count(), 0, 'Kick placement must not highlight every legal square');
  const kickPoint = await squarePosition(kickoff, 10, 9);
  await kickoff.locator('.live-pitch-scene').click({ position: kickPoint });
  assert.deepEqual(await kickoff.evaluate(() => window.intents.at(-1)), { x: 10, y: 9 });
  await kickoff.close();
  for (const role of ['home', 'away']) for (const faces of [['SKULL', 'PUSHBACK', 'POW'], ['2', '4', '5', '6', '3']]) {
    const dice = await open(role, false, { label: 'Revealed rolls', faces, subjectId: 'human', selected: null });
    console.log('Camera dice checks:', role, faces.join(','));
    const area = dice.locator('.live-pitch-viewport'), overlay = dice.locator('.live-dice-overlay');
    for (const angle of [30, 40, 50, 90]) {
      if (angle === 90) await setCameraMode(dice, 'top-down');
      else await setCameraAngle(dice, String(angle));
      const viewportRect = await area.boundingBox(), diceRect = await overlay.boundingBox();
      assert.ok(diceRect.x >= viewportRect.x && diceRect.x + diceRect.width <= viewportRect.x + viewportRect.width,
        `${role} ${angle}° ${faces.length} dice fit horizontally`);
      assert.ok(diceRect.y >= viewportRect.y && diceRect.y + diceRect.height <= viewportRect.y + viewportRect.height,
        `${role} ${angle}° ${faces.length} dice fit vertically`);
      const screenWidth = await dice.evaluate(() => innerWidth);
      assert.ok(Math.abs(diceRect.x + diceRect.width / 2 - screenWidth * .65) < 2,
        `${role} ${angle}° ${faces.length} dice remain at screen center-right`);
    }
    await dice.close();
  }
  if (evidence) {
    const comparison = await open('home');
    await setCameraAngle(comparison, '30');
    await travelToFocus(comparison, 11.56);
    await comparison.screenshot({ path: `${evidence}/perspective-30-home-original-camera-tiled.png` });
    await comparison.getByRole('button', { name: '2×', exact: true }).click();
    await travelToFocus(comparison, 26);
    await comparison.screenshot({ path: `${evidence}/perspective-30-home-near-2x-tiled.png` });
    assert.ok(await comparison.locator('.pitch-stadium-world .pitch-stadium-plate').count() > 0, 'projected turf plates remain present at 30 degrees and 2x');
    await comparison.close();
  }
  const missing = await open('home', true);
  await missing.locator('.live-token').first().waitFor();
  const tokenOffsets = await missing.locator('.live-token').evaluateAll(tokens => tokens.map(token => {
    const rect = token.getBoundingClientRect(), marker = token.parentElement;
    const scene = marker.closest('.live-pitch-scene').getBoundingClientRect();
    return { id: marker.dataset.playerId, dx: rect.x + rect.width / 2 - scene.x - Number(marker.dataset.centerX),
      dy: rect.y + rect.height / 2 - scene.y - Number(marker.dataset.centerY) };
  }));
  assert.ok(tokenOffsets.every(offset => Math.abs(offset.dx) < .02 && Math.abs(offset.dy) < .02), `unavailable art stays centered at the canonical ground anchor: ${JSON.stringify(tokenOffsets)}`);
  await missing.locator('[data-player-id="human"]').click();
  assert.deepEqual(await missing.evaluate(() => window.intents), [{ player: 'human' }]);
  await setCameraMode(missing, 'top-down');
  const fallbackShadows = await missing.locator('[data-shadow-player]').evaluateAll(shadows => shadows.map(shadow => {
    const marker = shadow.closest('.live-pitch-scene').querySelector(`[data-player-id="${shadow.dataset.shadowPlayer}"]`);
    const coordinates = shadow.getAttribute('points').split(' ').map(pair => pair.split(',').map(Number));
    return { mode: marker.dataset.anchorMode, x: (Math.min(...coordinates.map(p => p[0])) + Math.max(...coordinates.map(p => p[0]))) / 2 - Number(marker.dataset.centerX),
      y: (Math.min(...coordinates.map(p => p[1])) + Math.max(...coordinates.map(p => p[1]))) / 2 - Number(marker.dataset.centerY) };
  }));
  assert.ok(fallbackShadows.every(s => s.mode === 'token' && Math.abs(s.x) < .05 && Math.abs(s.y) < .05), 'Missing-art tokens retain centered shadows in top-down view');
  await missing.close();
  assert.deepEqual(errors, []);
  const missingTurf = await open('home',false,null,{failTurf:true});
  await missingTurf.getByText('Pitch texture unavailable; plain field shown.', { exact: true }).waitFor();
  assert.equal(await missingTurf.locator('[data-cell-x]').count(),390,'missing turf preserves playable squares');
  assert.equal(await missingTurf.locator('.pitch-stadium-world').count(),0,'missing turf uses the plain pitch fallback');
  await missingTurf.locator('[data-player-id="human"]').click();
  assert.deepEqual(await missingTurf.evaluate(() => window.intents), [{ player: 'human' }]);
  await missingTurf.close();
  assert.deepEqual(stadiumRequests.get(missingTurf), [], 'a turf-only pitch makes no stadium requests');
  assert.deepEqual(errors, []);
  console.log('PASS: both coach ends preserve canonical intents, bounded 30° scenery, player anchors/shadows, right-pan and wheel zoom, centered dice, kickoff targeting, and unavailable-art fallback.');
} finally { if (errors.length) console.error(errors); await browser.close(); await server.close(); }
