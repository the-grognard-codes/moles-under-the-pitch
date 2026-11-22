import assert from 'node:assert/strict';
import { mkdir, readFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';
import { createServer } from './browser-test-server.mjs';

const root = fileURLToPath(new URL('../', import.meta.url));
const captures = process.env.STADIUM_MOCKUP_OUTPUT || resolve(root, '../.tools/stadium-mockup');
const artwork = resolve(root, '../assets/game/references/stadiums/scale-studies');
const studies = JSON.parse(await readFile(resolve(artwork, 'bay-six/seats.json'), 'utf8'));
const ends = JSON.parse(await readFile(resolve(artwork, 'end-stands/placements.json'), 'utf8'));
const endPoseGroups = JSON.parse(await readFile(resolve(artwork, 'end-stands/pose-placements.json'), 'utf8'));
const fullEndPlacements = JSON.parse(await readFile(resolve(artwork, 'end-stands/full-ends/placements.json'), 'utf8'));
const sidePlacements = JSON.parse(await readFile(resolve(artwork, 'side-stands/placements.json'), 'utf8'));
const regaliaAtlas = JSON.parse(await readFile(resolve(artwork, 'regalia/atlas.json'), 'utf8'));
const regaliaLayout = JSON.parse(await readFile(resolve(artwork, 'regalia/layout.json'), 'utf8'));
const lockerStairwell = JSON.parse(await readFile(resolve(artwork, 'locker-stairwell.json'), 'utf8'));
const [atlasPng, authoringPng] = await Promise.all(['supporter-kits.png', 'supporters.png'].map(file =>
  readFile(resolve(artwork, 'bay-six', file), 'base64')));
const { ramps } = JSON.parse(await readFile(resolve(artwork, '../../../team-colors/v1/delivery/manifest.json'), 'utf8'));
const ramp = name => ramps.find(({ id }) => id === name).colors.map(color => parseInt(color.slice(1), 16));
const neutralPalette = [...ramp('slate'), ...ramp('brown')];
const kitPairs = [...ramp('teal'), ...ramp('violet')].map((color, index) => [color, neutralPalette[index]]);
const landmarks = JSON.parse(await readFile(resolve(artwork, 'bay-six/pose-landmarks.json'), 'utf8'));
const posesManifest = JSON.parse(await readFile(resolve(artwork, 'bay-six/poses.json'), 'utf8'));
const poseById = new Map(posesManifest.map(pose => [pose.id, pose]));
const poseColumns = posesManifest.map(({ id }) => id);
const identity = pose => pose.slice(pose.indexOf('-') + 1);
const rosterLabel = 'Inspect all ' + posesManifest.length + ' poses';
const caption = ({ pose, neutral }) => {
  const { label, wardrobe } = poseById.get(pose);
  const stance = pose.startsWith('standing-') ? 'Standing' : 'Seated';
  const clothing = wardrobe === 'neutral' ? 'Fixed clothing'
    : neutral === undefined ? 'Team colors' : neutral ? 'Neutral kit' : 'Home kit';
  return label + ' ' + stance + ' · ' + clothing;
};
const layout = JSON.parse(await readFile(resolve(artwork, 'empty-seating.json'), 'utf8'));
const benchLayout = JSON.parse(await readFile(resolve(artwork, 'gatehouse-benches.json'), 'utf8'));
const mask = await readFile(resolve(artwork, 'empty-seating.png'), 'base64');
const bay = layout.bays.find(({ id }) => id === 6);
const bayPlanks = benchLayout.bays.find(({ id }) => id === bay.id).planks;
const seatKey = ({ plankId, slot }) => `${plankId}:${slot.x},${slot.y}`;

async function benchReference(canvas, colors) {
  return canvas.evaluate(async (full, { benchLayout, layout, mask, colors }) => {
    const maskImage = new Image();
    maskImage.src = 'data:image/png;base64,' + mask;
    await maskImage.decode();
    const overlay = document.createElement('canvas');
    overlay.width = full.width;
    overlay.height = full.height;
    const context = overlay.getContext('2d');
    context.drawImage(maskImage, 0, 0);
    const ids = context.getImageData(0, 0, full.width, full.height).data;
    const pixels = context.createImageData(full.width, full.height);
    const counts = {};
    for (const { id, planks } of benchLayout.bays) {
      const bay = layout.bays.find(bay => bay.id === id);
      for (const { id } of planks) counts[id] = 0;
      for (let y = 0; y < bay.height; y++) {
        for (let x = 0; x < bay.width; x++) {
          const pixel = ((bay.y + y) * full.width + bay.x + x) * 4;
          if (ids[pixel] !== id) continue;
          const plank = planks.find(({ rects }) => rects.some(([left, top, width, height]) =>
            x >= left && x < left + width && y >= top && y < top + height));
          if (!plank) continue;
          pixels.data.set([...colors[plank.id % 10 - 1], 135], pixel);
          counts[plank.id]++;
        }
      }
    }
    context.putImageData(pixels, 0, 0);
    return { overlay: overlay.toDataURL(), counts };
  }, { benchLayout, layout, mask, colors });
}

function drawBenchReference(canvas, background, overlay) {
  return canvas.evaluate(async (full, { background, overlay }) => {
    async function decode(src) {
      const image = new Image();
      image.src = src;
      await image.decode();
      return image;
    }
    const expected = document.createElement('canvas');
    expected.width = full.width;
    expected.height = full.height;
    const context = expected.getContext('2d');
    context.drawImage(await decode(background), 0, 0);
    context.drawImage(await decode(overlay), 0, 0);
    return expected.toDataURL();
  }, { background, overlay });
}

async function verifyFullRoster(page) {
  const summary = page.getByText(rosterLabel, { exact: true });
  const roster = page.getByRole('list', { name: 'Full supporter roster' });
  const reference = page.getByRole('link', { name: 'Artist reference' });
  assert.equal(await roster.isVisible(), false);
  assert.equal(await reference.isVisible(), false);
  await summary.press('Enter');
  await roster.waitFor();
  assert.equal(await reference.count(), 1);
  const servedReference = await page.request.get(await reference.evaluate(link => link.href));
  assert.equal(servedReference.status(), 200);
  assert.match(servedReference.headers()['content-type'], /^image\/webp/);
  assert.deepEqual(await servedReference.body(), await readFile(resolve(artwork, 'bay-six/cast-reference.webp')));
  assert.deepEqual(await roster.locator('figcaption').allTextContents(),
    poseColumns.map(pose => caption({ pose })));
  await roster.evaluate(async (list, { atlasPng, authoringPng, posesManifest, kitPairs }) => {
    const size = 48;
    async function decodedContext(bytes, width, height) {
      const image = new Image();
      image.src = 'data:image/png;base64,' + bytes;
      await image.decode();
      if (image.width !== width || image.height !== height) throw Error('Unexpected roster atlas dimensions');
      const canvas = document.createElement('canvas');
      canvas.width = width;
      canvas.height = height;
      const context = canvas.getContext('2d');
      context.drawImage(image, 0, 0);
      return context;
    }
    const width = size * posesManifest.length;
    const [source, kits] = await Promise.all([
      decodedContext(authoringPng, width, size), decodedContext(atlasPng, width, size * 2),
    ]);
    const canvases = list.querySelectorAll('canvas');
    if (canvases.length !== posesManifest.length) throw Error('Roster is missing isolated poses');
    const kitPalette = new Map(kitPairs);
    const rgb = (pixels, i) => pixels[i] * 65536 + pixels[i + 1] * 256 + pixels[i + 2];
    const uniquePoses = new Set();
    for (const [column, { id: pose, wardrobe }] of posesManifest.entries()) {
      const x = column * size;
      const authoring = source.getImageData(x, 0, size, size).data;
      const home = kits.getImageData(x, 0, size, size).data;
      const neutral = kits.getImageData(x, size, size, size).data;
      if (home.some((value, i) => value !== authoring[i])) throw Error('Home kit changes the authored ' + pose);
      let recolored = false;
      for (let i = 0; i < home.length; i += 4) {
        if (home[i + 3] !== neutral[i + 3]) throw Error('Neutral kit changes the ' + pose + ' silhouette');
        if (rgb(home, i) !== rgb(neutral, i)) {
          if (kitPalette.get(rgb(home, i)) !== rgb(neutral, i)) throw Error('Neutral kit changes a fixed pixel of ' + pose);
          recolored = true;
        }
      }
      if (recolored !== (wardrobe === 'team')) throw Error('Kit rows do not match the wardrobe designation for ' + pose);
      if (!home.some((value, i) => i % 4 === 3 && value > 0)) throw Error('Empty roster pose ' + pose);
      uniquePoses.add(home.join(','));
      const enlarged = canvases[column];
      if (enlarged.width !== size * 2 || enlarged.height !== size * 2) throw Error('Expected a 2× roster pose');
      const visible = enlarged.getContext('2d').getImageData(0, 0, enlarged.width, enlarged.height).data;
      for (let y = 0; y < enlarged.height; y++) {
        for (let x = 0; x < enlarged.width; x++) {
          const i = (Math.floor(y / 2) * size + Math.floor(x / 2)) * 4;
          const target = (y * enlarged.width + x) * 4;
          if (home.subarray(i, i + 4).some((value, channel) => value !== visible[target + channel])) {
            throw Error('Wrong isolated sprite or zoom for ' + pose);
          }
        }
      }
    }
    if (uniquePoses.size !== posesManifest.length) throw Error('A roster pose duplicates another source sprite');
  }, { atlasPng, authoringPng, posesManifest, kitPairs });
  await roster.screenshot({ path: resolve(captures, 'west-bay-six-roster.png') });
  await summary.press('Enter');
  await roster.waitFor({ state: 'hidden' });
}

function readEndSections(views) {
  assert.deepEqual(views.map(({ id, bayId, facing }) => [id, bayId, facing]), [
    ['north', 1, 'front'], ['south', 12, 'back'],
  ]);
  return Promise.all(views.map(async end => {
    assert.equal(end.capacity, 9);
    assert.equal(new Set(end.slots.map(seatKey)).size, 9);
    assert.equal(new Set(end.seats.map(seatKey)).size, 3);
    const available = new Set(end.slots.map(seatKey));
    assert.ok(end.seats.every(seat => available.has(seatKey(seat))));
    const bounds = layout.bays.find(({ id }) => id === end.bayId);
    const [crowd, railArt] = await Promise.all([end.crowd ?? end.id + '.png', end.rail ?? end.id + '-rail.png']
      .map(name => readFile(resolve(artwork, 'end-stands', name), 'base64')));
    return { ...end, bounds, crowd, railArt };
  }));
}

async function verifyEndDraw(stadium, sections, original, withRail) {
  return stadium.evaluate(async (full, { sections, original, withRail }) => {
    async function decode(src) {
      const image = new Image();
      image.src = src;
      await image.decode();
      return image;
    }
    function surface(width, height) {
      const canvas = document.createElement('canvas');
      canvas.width = width;
      canvas.height = height;
      return canvas.getContext('2d');
    }
    const ground = await decode(original);
    const frame = full.getContext('2d');
    const groundSurface = surface(full.width, full.height);
    groundSurface.drawImage(ground, 0, 0);
    return Promise.all(sections.map(async ({ id, bounds, crowd, railArt }) => {
      const { x, y, width, height } = bounds;
      const expected = surface(width, height);
      const [people, guard] = await Promise.all([crowd, railArt].map(data => decode('data:image/png;base64,' + data)));
      expected.drawImage(ground, -x, -y);
      expected.drawImage(people, 0, 0);
      if (withRail) expected.drawImage(guard, 0, 0);
      const actual = frame.getImageData(x, y, width, height).data;
      if (!expected.getImageData(0, 0, width, height).data.every((value, i) => value === actual[i])) {
        throw Error(id + ' uses incorrect stand or rail art');
      }

      const native = document.querySelector('#end-' + id + '-native-canvas');
      const zoom = document.querySelector('#end-' + id + '-zoom-canvas');
      const pitchY = id === 'north' ? 169 : 969;
      const frameY = id === 'north' ? Math.max(0, y - 12) : pitchY - 8;
      const frameBottom = id === 'north' ? pitchY + 32 + 8 : y + height + 12;
      if (native.height !== frameBottom - frameY) throw Error(id + ' native crop exceeds stadium bounds');
      const nativePixels = frame.getImageData(x - 12, frameY, native.width, native.height).data;
      const nativeContext = native.getContext('2d');
      if (!nativeContext.getImageData(0, 0, native.width, native.height).data.every((value, i) => value === nativePixels[i])) {
        throw Error(id + ' native crop is not an exact stadium crop');
      }
      const turf = groundSurface.getImageData(156, pitchY, 32, 32).data;
      const withBlitzer = nativeContext.getImageData(156 - (x - 12), pitchY - frameY, 32, 32).data;
      if (turf.filter((value, i) => value !== withBlitzer[i]).length < 100) throw Error(id + ' native crop is missing its blitzer');

      const detailX = Math.max(0, x - 8);
      const detailY = Math.max(0, y - 8);
      const detailWidth = Math.min(full.width, x + width + 8) - detailX;
      const detailHeight = Math.min(full.height, y + height + 8) - detailY;
      if (zoom.width !== detailWidth * 4 || zoom.height !== detailHeight * 4) throw Error(id + ' has the wrong pixel zoom');
      const detail = frame.getImageData(detailX, detailY, detailWidth, detailHeight).data;
      const pixels = zoom.getContext('2d').getImageData(0, 0, zoom.width, zoom.height).data;
      for (let yy = 0; yy < zoom.height; yy++) {
        for (let xx = 0; xx < zoom.width; xx++) {
          const source = (Math.floor(yy / 4) * detailWidth + Math.floor(xx / 4)) * 4;
          const target = (yy * zoom.width + xx) * 4;
          if (detail.subarray(source, source + 4).some((value, i) => value !== pixels[target + i])) {
            throw Error(id + ' detail crop does not replicate native pixels');
          }
        }
      }
      return expected.canvas.toDataURL();
    }));
  }, { sections, original, withRail });
}

function verifyEndMarkers(stadium, sections) {
  return stadium.evaluate((full, sections) => {
    const frame = full.getContext('2d');
    return sections.every(({ bounds, slots, seats }) => {
      const color = ({ x, y }) => frame.getImageData(bounds.x + x, bounds.y + y, 1, 1).data.join(',');
      const key = ({ slot, plankId }) => plankId + ':' + slot.x + ',' + slot.y;
      const occupied = new Set(seats.map(key));
      return seats.every(seat => (seat.feet ?? [seat]).every(contact => color(contact) === '128,245,255,255') &&
        (!seat.knee || color(seat.knee) === '255,225,123,255')) &&
        slots.every(slot => occupied.has(key(slot)) ||
          color({ x: slot.slot.x - 1, y: slot.slot.y - 1 }) === '212,205,193,255');
    });
  }, sections);
}

async function verifyEndStands(page, original) {
  const picker = page.getByLabel('Gallery view');
  const rail = page.getByRole('checkbox', { name: 'Front rail' });
  const markers = page.getByRole('checkbox', { name: 'Seat markers' });
  const stadium = page.locator('canvas[data-ready="true"]');
  const review = page.getByRole('region', { name: 'North and south stands' });
  const sections = await readEndSections(ends);
  await picker.selectOption('ends');
  await page.getByLabel('Gatehouse stadium with north and south supporters').waitFor();
  await review.getByText('3 of 9 occupied per bay', { exact: true }).waitFor();
  const reference = review.getByRole('link', { name: 'View six full poses', exact: true });
  assert.ok(await reference.isVisible());
  const servedReference = await page.request.get(await reference.evaluate(link => link.href));
  assert.equal(servedReference.status(), 200);
  assert.match(servedReference.headers()['content-type'], /^image\/webp/);
  assert.deepEqual(await servedReference.body(), await readFile(resolve(artwork, 'end-stands/artist-reference.webp')));
  for (const name of ['North stand · Front view', 'South stand · Back view']) {
    await review.getByRole('heading', { name, exact: true }).waitFor();
  }
  assert.equal(await rail.isChecked(), true);
  assert.equal(await markers.isChecked(), false);
  const withRail = await verifyEndDraw(stadium, sections, original, true);
  await review.screenshot({ path: resolve(captures, 'end-stands-review.png') });
  await stadium.screenshot({ path: resolve(captures, 'end-stands-full.png') });
  await rail.uncheck();
  const withoutRail = await verifyEndDraw(stadium, sections, original, false);
  for (const [i, { id }] of sections.entries()) assert.notEqual(withRail[i], withoutRail[i], id + ' changes when rail is toggled');
  await rail.check();
  await markers.check();
  assert.equal(await verifyEndMarkers(stadium, sections), true, 'Both ends show three contacts and six vacant seat markers');
  await review.screenshot({ path: resolve(captures, 'end-stands-guides.png') });
  await markers.uncheck();
  assert.deepEqual(await verifyEndDraw(stadium, sections, original, true), withRail);
}

async function verifyEndPoseShowcase(page, original) {
  const picker = page.getByLabel('Gallery view');
  const rail = page.getByRole('checkbox', { name: 'Front rail' });
  const markers = page.getByRole('checkbox', { name: 'Seat markers' });
  const stadium = page.locator('canvas[data-ready="true"]');
  const review = page.getByRole('region', { name: 'North and south pose showcase' });
  const chooser = review.getByLabel('Pose group');
  await picker.selectOption('end-poses');
  await chooser.waitFor();
  assert.equal(await chooser.inputValue(), 'approved');
  assert.deepEqual(await chooser.locator('option').allTextContents(), endPoseGroups.map(({ label }) => label));
  assert.deepEqual(endPoseGroups.map(({ id }) => id), ['approved', 'seated-a', 'seated-b', 'standing']);
  const reference = review.getByRole('link', { name: 'View all 12 pose pairs', exact: true });
  const servedReference = await page.request.get(await reference.evaluate(link => link.href));
  assert.equal(servedReference.status(), 200);
  assert.match(servedReference.headers()['content-type'], /^image\/webp/);
  assert.deepEqual(await servedReference.body(), await readFile(resolve(artwork, 'end-stands/cast-angles.webp')));

  const pairs = new Set();
  const rendered = new Set();
  for (const group of endPoseGroups) {
    const sections = await readEndSections(group.views);
    const pairKeys = ({ seats }) => seats.map(({ supporter, stance }) =>
      supporter.replace(/^standing-/, '') + ':' + stance).sort();
    const northKeys = pairKeys(sections[0]);
    assert.deepEqual(northKeys, pairKeys(sections[1]), group.id + ' shows corresponding angle pairs');
    for (const key of northKeys) {
      assert.ok(!pairs.has(key), 'Duplicate end pose ' + key);
      pairs.add(key);
    }
    await chooser.selectOption(group.id);
    const withRail = await verifyEndDraw(stadium, sections, original, true);
    rendered.add(withRail.join());
    await review.screenshot({ path: resolve(captures, 'end-pose-' + group.id + '.png') });
    if (group.id === 'standing') {
      for (const { seats } of sections) {
        assert.equal(seats.filter(({ stance, feet }) => stance === 'standing' && feet?.length === 2).length, 2);
      }
      await rail.uncheck();
      const withoutRail = await verifyEndDraw(stadium, sections, original, false);
      assert.notDeepEqual(withRail, withoutRail);
      await markers.check();
      assert.equal(await verifyEndMarkers(stadium, sections), true, 'Standing markers show both boots');
      await review.screenshot({ path: resolve(captures, 'end-pose-standing-guides.png') });
      await markers.uncheck();
      await rail.check();
    }
  }
  assert.equal(pairs.size, 12);
  assert.equal(new Set([...pairs].map(pair => pair.split(':')[0])).size, 10);
  assert.equal(rendered.size, 4);
  await picker.selectOption('ends');
  await page.getByRole('region', { name: 'North and south stands' }).waitFor();
  await verifyEndDraw(stadium, await readEndSections(ends), original, true);
}

const fullKickoffLabel = 'Gatehouse kickoff with 22 players and crowds in all four stands';
const sideStands = ['west', 'east'];
const colorPairs = {
  defaults: { home: ['navy', 'gold'], away: ['red', 'gold'] },
  identical: { home: ['navy', 'navy'], away: ['navy', 'navy'] },
  'white / black': { home: ['white', 'black'], away: ['black', 'white'] },
  'yellow / purple': { home: ['lemon', 'violet'], away: ['violet', 'lemon'] },
  'similar blues': { home: ['navy', 'sky'], away: ['sky', 'navy'] },
};
const decorationPaletteLabel = 'End zone and regalia colors';
const readyGallery = page => page.locator('.gatehouse-overview canvas[data-ready="true"]');
const composedPixels = page => readyGallery(page).evaluate(canvas => canvas.toDataURL());
const reservationLegend = [
  'Solid red marks sideline staff.',
  'Dashed blue marks reserve players.',
  'Dotted magenta marks cheerleaders.',
];

function stairwellCrops(stadium, proof) {
  return stadium.evaluate(async (canvas, { layout, proof }) => {
    async function decode(src) {
      const image = new Image();
      image.src = src;
      await image.decode();
      return image;
    }
    const [sprite, walls] = proof ? await Promise.all(proof.map(decode)) : [];
    if (sprite && (sprite.width !== layout.width || sprite.height !== layout.height)) throw Error('Invalid native stair sprite size');
    return layout.placements.map(({ corner, x, y, width, height, rotate180 }) => {
      const crop = document.createElement('canvas');
      crop.width = width;
      crop.height = height;
      const ctx = crop.getContext('2d');
      ctx.imageSmoothingEnabled = false;
      function read(source, sx, sy, sw, sh) {
        ctx.clearRect(0, 0, width, height);
        ctx.drawImage(source, sx, sy, sw, sh, 0, 0, width, height);
        return ctx.getImageData(0, 0, width, height).data;
      }
      const visible = read(canvas, x, y, width, height);
      if (sprite && walls) {
        const stairs = read(sprite, 0, 0, layout.width, layout.height);
        const architecture = read(walls, x, y, width, height);
        let exposed = 0, covered = 0;
        for (let i = 0; i < visible.length; i += 4) {
          const stairIndex = rotate180 ? visible.length - 4 - i : i;
          const wallAlpha = architecture[i + 3];
          if (stairs[stairIndex + 3] !== 255) continue;
          // Canvas quantizes the premultiplied channels at the translucent wall edge.
          const rounding = wallAlpha > 0 && wallAlpha < 255 ? 1 : 0;
          for (let channel = 0; channel < 3; channel++) {
            const expected = Math.round((architecture[i + channel] * wallAlpha +
              stairs[stairIndex + channel] * (255 - wallAlpha)) / 255);
            if (Math.abs(expected - visible[i + channel]) > rounding) {
              throw Error(corner + ' has an incorrect stair or wall pixel at local index ' + i / 4);
            }
          }
          if (wallAlpha === 255) covered++;
          if (wallAlpha === 0) exposed++;
        }
        if (!exposed || !covered) throw Error(corner + ' needs exposed steps and steps hidden by the architecture');
      }
      return Array.from(visible);
    });
  }, { layout: lockerStairwell, proof });
}

async function verifyReservationPixels(stadium, approved) {
  const areas = await stadium.evaluate(async (canvas, approved) => {
    const { apronReservations } = await import('/src/stadium-apron-reservations.ts');
    const { width, height } = canvas;
    const original = document.createElement('canvas');
    original.width = width;
    original.height = height;
    const ctx = original.getContext('2d');
    async function read(src) {
      const image = new Image();
      image.src = src;
      await image.decode();
      ctx.drawImage(image, 0, 0);
      return ctx.getImageData(0, 0, width, height).data;
    }
    const current = canvas.toDataURL();
    const before = await read(approved);
    const after = await read(current);
    const coverage = new Uint8Array(width * height);
    function inside(outline, x, y) {
      let contained = false;
      for (let i = 0, j = outline.length - 1; i < outline.length; j = i++) {
        const [ax, ay] = outline[i], [bx, by] = outline[j];
        if ((ay > y) !== (by > y) && x < ax + (y - ay) * (bx - ax) / (by - ay)) contained = !contained;
      }
      return contained;
    }
    for (const [i, area] of apronReservations.entries()) {
      for (let dy = 0; dy < area.height; dy++) {
        for (let dx = 0; dx < area.width; dx++) {
          if (inside(area.outline, dx + .5, dy + .5)) coverage[(area.y + dy) * width + area.x + dx] = i + 1;
        }
      }
    }
    const changed = apronReservations.map(() => 0);
    const strokes = apronReservations.map(() => 0);
    const strokeColor = { staff: 0xff6459, reserves: 0x62b4ff, cheerleaders: 0xf275ee };
    for (let i = 0; i < coverage.length; i++) {
      const index = i * 4;
      if (before[index] === after[index] && before[index + 1] === after[index + 1] &&
          before[index + 2] === after[index + 2] && before[index + 3] === after[index + 3]) continue;
      const x = i % width, y = Math.floor(i / width);
      if (!coverage[i]) throw Error('Unexpected apron guide pixel at ' + x + ', ' + y);
      if (x >= 156 && x < 636 && y >= 169 && y < 1001) throw Error('Apron guide paints the pitch at ' + x + ', ' + y);
      const area = coverage[i] - 1;
      changed[area]++;
      const rgb = (after[index] << 16) | (after[index + 1] << 8) | after[index + 2];
      if (rgb === strokeColor[apronReservations[area].kind]) strokes[area]++;
    }
    return apronReservations.map(({ kind, side, width, height }, index) =>
      ({ kind, side, width, height, changed: changed[index], strokes: strokes[index] }));
  }, approved);
  assert.ok(areas.every(area => area.strokes > 0 && area.changed <= area.width * area.height));
  for (const kind of ['staff', 'reserves', 'cheerleaders']) {
    assert.deepEqual([...new Set(areas.filter(area => area.kind === kind).map(area => area.side))].sort(),
      kind === 'cheerleaders' ? ['east', 'north', 'south', 'west'] : ['east', 'west']);
  }
}

async function verifyRegalia(page, stadium, approved, defaultDecorated) {
  const toggle = page.getByRole('checkbox', { name: 'Regalia' });
  const palette = page.getByLabel(decorationPaletteLabel);
  const [art, mask] = await Promise.all(['atlas.png', 'materials.png'].map(file =>
    readFile(resolve(artwork, 'regalia', file), 'base64')));
  const ramps = Object.fromEntries(Object.entries(colorPairs).map(([id, pair]) => [
    id, Object.fromEntries(Object.entries(pair).map(([role, colors]) => [role, colors.flatMap(ramp)])),
  ]));
  const expected = await stadium.evaluate(async (canvas, { art, mask, atlas, layout, approved, ramps }) => {
    function surface(width, height) {
      const backed = document.createElement('canvas');
      backed.width = width;
      backed.height = height;
      return backed.getContext('2d');
    }
    async function raster(src, width, height) {
      const image = new Image();
      image.src = src;
      await image.decode();
      if (image.width !== width || image.height !== height) throw Error('Incorrect regalia raster dimensions');
      const ctx = surface(width, height);
      ctx.drawImage(image, 0, 0);
      return ctx.getImageData(0, 0, width, height);
    }
    const png = 'data:image/png;base64,';
    const [source, materials, base] = await Promise.all([
      raster(png + art, atlas.width, atlas.height),
      raster(png + mask, atlas.width, atlas.height),
      raster(approved, canvas.width, canvas.height),
    ]);
    const results = {};
    for (const [id, pair] of Object.entries(ramps)) {
      const rendered = surface(canvas.width, canvas.height);
      const pixels = rendered.createImageData(canvas.width, canvas.height);
      pixels.data.set(base.data);
      const proof = { home: { fixed: 0, primary: new Set(), secondary: new Set() },
        away: { fixed: 0, primary: new Set(), secondary: new Set() } };
      for (const { id, owner, sprite, x, y } of layout.placements) {
        const cell = atlas.sprites[sprite];
        let visible = 0;
        for (let row = 0; row < cell.height; row++) {
          for (let col = 0; col < cell.width; col++) {
            const src = ((cell.y + row) * atlas.width + cell.x + col) * 4;
            const dst = ((y + row) * canvas.width + x + col) * 4;
            const index = materials.data[src];
            const alpha = source.data[src + 3];
            if (index > 32 || (!alpha && index) || (alpha && alpha !== 255)) throw Error('Invalid material or outline');
            if (!alpha) continue;
            visible++;
            if (index) {
              const rgb = pair[owner][index - 1];
              pixels.data.set([rgb >> 16, (rgb >> 8) & 255, rgb & 255, alpha], dst);
              proof[owner][index <= 16 ? 'primary' : 'secondary'].add(index);
            } else {
              pixels.data.set(source.data.subarray(src, src + 4), dst);
              proof[owner].fixed++;
            }
          }
        }
        if (!visible) throw Error('Empty regalia placement ' + id);
      }
      for (const { fixed, primary, secondary } of Object.values(proof)) {
        if (fixed < 1 || primary.size < 4 || secondary.size < 4) throw Error('Expected fixed and shaded team details for both stands');
      }
      rendered.putImageData(pixels, 0, 0);
      results[id] = rendered.canvas.toDataURL();
    }
    return results;
  }, { art, mask, atlas: regaliaAtlas, layout: regaliaLayout, approved, ramps });
  assert.deepEqual(await palette.locator('option').evaluateAll(options => options.map(option => option.value)), Object.keys(colorPairs));
  assert.equal(defaultDecorated, expected.defaults, 'Regalia is painted at its registered pixels by default');
  assert.equal(new Set([approved, ...Object.values(expected)]).size, Object.keys(colorPairs).length + 1);
  assert.equal(await palette.isDisabled(), true);
  await toggle.check();
  for (const [preset, pixels] of Object.entries(expected)) {
    await palette.selectOption(preset);
    assert.equal(await composedPixels(page), pixels,
      preset + ' preserves fixed colors and replaces each exact material shade for its owner');
  }
  await toggle.uncheck();
  assert.equal(await composedPixels(page), approved, 'Turning off regalia restores the exact approved bowl');
  await toggle.check();
  assert.equal(await palette.inputValue(), 'similar blues');
  assert.equal(await composedPixels(page), expected['similar blues']);
  await palette.selectOption('defaults');
  return expected;
}

async function verifyEndZones(page, stadium, approved, decorated, defaultKickoff) {
  const zones = page.getByRole('checkbox', { name: 'End zones' });
  const regalia = page.getByRole('checkbox', { name: 'Regalia' });
  const palette = page.getByLabel(decorationPaletteLabel);
  const expected = await stadium.evaluate(async (canvas, { approved, decorated, colorPairs }) => {
    const { drawGatehouseEndZones } = await import('/src/stadium-end-zones.ts');
    const pitch = { x: 156, y: 169, width: 480, height: 832 };
    const square = 32;
    const rows = [pitch.y, pitch.y + pitch.height - square];
    function surface() {
      const layer = document.createElement('canvas');
      layer.width = canvas.width;
      layer.height = canvas.height;
      return layer.getContext('2d');
    }
    async function read(src) {
      const image = new Image();
      image.src = src;
      await image.decode();
      return image;
    }
    const base = await read(approved);
    const results = {};
    for (const [preset, pair] of Object.entries(colorPairs)) {
      const paint = surface();
      drawGatehouseEndZones(paint, pitch, square, {
        north: { name: 'Cinderclaw Crew', primary: pair.away[0] },
        south: { name: 'Ironbank Rovers', primary: pair.home[0] },
      });
      function compose(original) {
        const native = surface();
        native.drawImage(original, 0, 0);
        for (const top of rows) {
          const chalk = native.getImageData(pitch.x, top, pitch.width, square);
          native.drawImage(paint.canvas, pitch.x, top, pitch.width, square, pitch.x, top, pitch.width, square);
          const strip = native.getImageData(pitch.x, top, pitch.width, square);
          let changed = 0;
          for (let y = 0; y < square; y++) {
            const across = top === pitch.y ? y === 0 : y === 0 || y === square - 1;
            for (let x = 0; x < pitch.width; x++) {
              const at = (y * pitch.width + x) * 4;
              if (across || x % square === 0 || x === pitch.width - 1) {
                strip.data.set(chalk.data.subarray(at, at + 4), at);
              } else if (strip.data.subarray(at, at + 3).some((value, index) => value !== chalk.data[at + index])) {
                changed++;
              }
            }
          }
          if (changed < pitch.width * square / 2) throw Error('Insufficient paint in end zone at y=' + top);
          native.putImageData(strip, pitch.x, top);
        }
        return native.canvas.toDataURL();
      }
      results[preset] = { paintOnly: compose(base) };
      if (preset === 'defaults' || preset === 'similar blues') {
        results[preset].combined = compose(await read(decorated[preset]));
      }
    }
    return results;
  }, { approved, decorated, colorPairs });
  assert.equal(defaultKickoff, expected.defaults.combined, 'The default kickoff shows the native paint under the chalk and the default regalia');
  assert.equal(new Set(Object.values(expected).map(({ paintOnly }) => paintOnly)).size, Object.keys(colorPairs).length);
  await zones.check();
  await regalia.uncheck();
  assert.equal(await palette.isEnabled(), true);
  for (const [preset, { paintOnly }] of Object.entries(expected)) {
    await palette.selectOption(preset);
    assert.equal(await composedPixels(page), paintOnly,
      preset + ' paints the away north and home south strips with native chalk above them');
  }
  await zones.uncheck();
  assert.equal(await palette.isDisabled(), true);
  assert.equal(await stadium.getAttribute('aria-label'), fullKickoffLabel);
  assert.equal(await composedPixels(page), approved, 'Both review layers off restores the original kickoff');
  await regalia.check();
  assert.equal(await composedPixels(page), decorated['similar blues']);
  await zones.check();
  assert.equal(await composedPixels(page), expected['similar blues'].combined);
  await palette.selectOption('defaults');
  assert.equal(await composedPixels(page), defaultKickoff);
  return defaultKickoff;
}

async function verifyKickoff(page, original, full) {
  const picker = page.getByLabel('Gallery view');
  const stadium = page.getByLabel(full ? fullKickoffLabel : 'Gatehouse kickoff with 22 players and north and south crowds');
  const review = page.getByRole('region', { name: full ? 'Full stadium at kickoff' : 'North and south stands at kickoff' });
  const detail = page.getByRole('region', { name: 'Bay details' });
  const bayPicker = page.getByLabel(full ? 'Seating bay' : 'End-stand bay');
  const markers = page.getByRole('checkbox', { name: 'Seat markers' });
  const placement = full ? [...fullEndPlacements, ...sidePlacements].sort((a, b) => a.bayId - b.bayId) : fullEndPlacements;
  const sections = await Promise.all(placement.map(async entry => ({
    ...entry, bounds: layout.bays.find(({ id }) => id === entry.bayId),
    png: await readFile(resolve(artwork, sideStands.includes(entry.stand) ? 'side-stands' : 'end-stands/full-ends', entry.crowd), 'base64'),
  })));
  assert.deepEqual(sections.map(({ bayId }) => bayId), full ? layout.bays.map(({ id }) => id) : [1, 2, 3, 12, 13, 14, 15]);
  assert.ok(sections.every(({ stand, bounds }) => stand === bounds.stand));
  await picker.selectOption(full ? 'full-bowl' : 'full-ends');
  await readyGallery(page).waitFor();
  const regalia = page.getByRole('checkbox', { name: 'Regalia' });
  const zones = page.getByRole('checkbox', { name: 'End zones' });
  const reservations = page.getByRole('checkbox', { name: 'Apron reservations' });
  const legend = page.getByRole('list', { name: 'Apron reservation key' });
  let defaultKickoff, defaultDecorated;
  if (full) {
    assert.equal(await regalia.isChecked(), true);
    assert.equal(await zones.isChecked(), true);
    assert.equal(await reservations.isChecked(), false);
    assert.equal(await legend.count(), 0);
    assert.equal(await stadium.getAttribute('aria-label'), fullKickoffLabel +
      '. Cinderclaw Crew in the north end zone and Ironbank Rovers in the south end zone.');
    assert.equal(await page.getByLabel(decorationPaletteLabel).inputValue(), 'defaults');
    defaultKickoff = await composedPixels(page);
    await zones.uncheck();
    defaultDecorated = await composedPixels(page);
    await regalia.uncheck();
  } else {
    assert.equal(await regalia.count(), 0);
    assert.equal(await zones.count(), 0);
    assert.equal(await reservations.count(), 0);
  }
  assert.equal(await markers.isChecked(), false);
  assert.equal(await page.getByRole('checkbox', { name: 'Front rail' }).count(), 0);
  assert.deepEqual(await stadium.evaluate(c => [c.width, c.height]), [790, 1188]);
  assert.ok(await detail.evaluate(section =>
    Boolean(document.querySelector('.gatehouse-overview').compareDocumentPosition(section) & Node.DOCUMENT_POSITION_FOLLOWING)));
  const labels = { north: 'North', south: 'South', west: 'West (home)', east: 'East (away)' };
  for (const stand of full ? Object.keys(labels) : ['north', 'south']) {
    const { capacity, occupied } = sections.filter(bay => bay.stand === stand).reduce((total, bay) =>
      ({ capacity: total.capacity + bay.capacity, occupied: total.occupied + bay.seats.length }), { capacity: 0, occupied: 0 });
    assert.ok(Math.abs(occupied - capacity * 2 / 3) <= 1);
    await review.getByText(labels[stand] + ' ' + occupied + ' of ' + capacity + ' occupied', { exact: true }).waitFor();
  }
  if (full) {
    const westTrial = studies.find(({ id }) => id === 'b');
    const westSix = sections.find(({ bayId }) => bayId === 6);
    const places = seats => seats.map(seat => [seatKey(seat), seat.pose, seat.x, seat.y]).sort();
    assert.deepEqual(places(westSix.seats), places(westTrial.seats), 'West 6 preserves the approved trial poses and locations');
    for (const { stand, seats } of sidePlacements) {
      assert.ok(seats.some(({ neutral }) => neutral) && seats.some(({ neutral }) => !neutral));
      for (const seat of seats) {
        assert.equal(seat.neutral, poseById.get(seat.pose).wardrobe === 'neutral');
        const source = landmarks[seat.pose];
        const delta = (marker, origin) => [
          (marker[0] - origin[0]) * (stand === 'west' ? 1 : -1), marker[1] - origin[1],
        ];
        if (source.knee) {
          assert.deepEqual([seat.knee.x - seat.x, seat.knee.y - seat.y], delta(source.knee, source.contact),
            stand + ' ' + seat.pose + ' must face the pitch');
        } else {
          const [first, second] = seat.feet;
          assert.deepEqual([second.x - first.x, second.y - first.y], delta(source.otherSole, source.contact),
            stand + ' ' + seat.pose + ' must face the pitch');
        }
      }
    }
  }
  // Compare the entire native stadium against the architecture, the source
  // insert for every bay, and the two kickoff kits at regulation square scale.
  const unadorned = await readyGallery(page).evaluate(async (canvas, { sections, original, sideColors }) => {
    async function image(src) {
      const art = new Image();
      art.src = src;
      await art.decode();
      return art;
    }
    const expected = document.createElement('canvas');
    expected.width = canvas.width;
    expected.height = canvas.height;
    const context = expected.getContext('2d');
    context.imageSmoothingEnabled = false;
    context.drawImage(await image(original), 0, 0);
    for (const { stand, bounds, png } of sections) {
      const art = await image('data:image/png;base64,' + png);
      if (sideColors[stand]) {
        const artCanvas = document.createElement('canvas');
        artCanvas.width = art.width;
        artCanvas.height = art.height;
        const artContext = artCanvas.getContext('2d');
        artContext.drawImage(art, 0, 0);
        const native = artContext.getImageData(0, 0, art.width, art.height).data;
        const palette = new Set();
        for (let i = 0; i < native.length; i += 4) {
          if (native[i + 3] === 255) palette.add((native[i] << 16) + (native[i + 1] << 8) + native[i + 2]);
        }
        for (const family of sideColors[stand]) {
          if (!family.some(rgb => palette.has(rgb))) throw Error('Missing side team colors in bay ' + bounds.id);
        }
      }
      const before = context.getImageData(bounds.x, bounds.y, bounds.width, bounds.height).data;
      context.drawImage(art, bounds.x, bounds.y);
      if (!context.getImageData(bounds.x, bounds.y, bounds.width, bounds.height).data.some((value, i) => value !== before[i])) {
        throw Error('Missing visible supporters in bay ' + bounds.id);
      }
    }
    const { resolvePlayerArt } = await import('/src/player-art.ts');
    const { teamColorArt } = await import('/src/team-color-art.ts');
    const release = teamColorArt.retain();
    const formation = [[12, 6], [12, 7], [12, 8], [10, 2], [10, 4], [10, 10], [10, 12], [7, 5], [7, 9], [4, 6], [4, 8]];
    const scale = 32 * 1.08 / 64;
    try {
      await Promise.all([teamColorArt.load('human'), teamColorArt.load('orc')]);
      for (const { role, rosterId, positionId, primary, pose } of [
        { role: 'home', rosterId: 'human', positionId: 'blitzer', primary: 'navy', pose: 'back' },
        { role: 'away', rosterId: 'orc', positionId: 'orc-blitzer', primary: 'red', pose: 'front' },
      ]) {
        const { body } = resolvePlayerArt({ role, x: 12, y: 6, state: 'standing', art: { rosterId, positionId } },
          { end: 'home', topDown: true, colors: { version: 1, primary, secondary: 'gold' } });
        if (body.pose !== pose || !body.url.startsWith('data:image/png')) throw Error('Missing kickoff kit or facing for ' + role);
        const sprite = await image(body.url);
        for (const [row, column] of formation) {
          const x = 156 + (column + .5) * 32;
          const y = 169 + ((role === 'home' ? 25 - row : row) + .5) * 32;
          context.drawImage(sprite, Math.round(x - body.bodyAnchor.x * scale), Math.round(y - body.bodyAnchor.y * scale),
            Math.round(body.width * scale), Math.round(body.height * scale));
        }
      }
    } finally {
      release();
    }
    const rendered = canvas.toDataURL();
    if (rendered !== expected.toDataURL()) throw Error('The native overview differs from the source bays and 22 players');
    return rendered;
  }, { sections, original, sideColors: { west: [ramp('navy'), ramp('gold')], east: [ramp('red'), ramp('gold')] } });
  let overview = unadorned;
  if (full) {
    const decorated = await verifyRegalia(page, stadium, unadorned, defaultDecorated);
    overview = await verifyEndZones(page, stadium, unadorned, decorated, defaultKickoff);
  }

  assert.deepEqual(await bayPicker.locator('option').allTextContents(), sections.map(({ stand, bayId }) =>
    stand[0].toUpperCase() + stand.slice(1) + ' bay ' + bayId));
  await page.getByRole('link', { name: 'Inspect a bay' }).click();
  for (const section of sections) {
    const { bayId, seats, capacity, bounds } = section;
    assert.equal(new Set(section.slots.map(seatKey)).size, capacity);
    assert.ok(seats.every(seat => section.slots.some(slot => seatKey(slot) === seatKey(seat))));
    await bayPicker.selectOption(String(bayId));
    await detail.getByText(seats.length + ' of ' + capacity + ' occupied', { exact: true }).waitFor();
    const row = sections.filter(bay => bay.stand === section.stand).map(({ bounds }) => bounds);
    const before = row[row.indexOf(bounds) - 1], after = row[row.indexOf(bounds) + 1];
    // Detail includes the adjoining staircase, gap, or edge of the central gate.
    const side = sideStands.includes(section.stand);
    let left = side ? bounds.x - 20 : (before ? Math.floor((before.x + before.width + bounds.x) / 2) : bounds.x - 32);
    let right = side ? bounds.x + bounds.width + 20
      : (after ? Math.ceil((bounds.x + bounds.width + after.x) / 2) : bounds.x + bounds.width + 32);
    let top = side && before ? Math.floor((before.y + before.height + bounds.y) / 2) : bounds.y - 24;
    let bottom = side && after ? Math.ceil((bounds.y + bounds.height + after.y) / 2) : bounds.y + bounds.height + 24;
    if (full) {
      for (const piece of regaliaLayout.placements.filter(piece => piece.bayIds.includes(bayId))) {
        const cell = regaliaAtlas.sprites[piece.sprite];
        left = Math.min(left, piece.x);
        right = Math.max(right, piece.x + cell.width);
        top = Math.min(top, piece.y);
        bottom = Math.max(bottom, piece.y + cell.height);
      }
    }
    await detail.evaluate((section, { left, right, top, bottom, bayId, cropId }) => {
      const full = document.querySelector('canvas[data-ready="true"]');
      const x = Math.max(0, left), y = Math.max(0, top);
      const native = section.querySelector('#' + cropId + '-native-canvas');
      const zoom = section.querySelector('#' + cropId + '-zoom-canvas');
      const width = Math.min(full.width, right) - x;
      const height = Math.min(full.height, bottom) - y;
      if (native.width !== width || native.height !== height || zoom.width !== width * 4 || zoom.height !== height * 4)
        throw Error('Incorrect crop dimensions for bay ' + bayId);
      const actual = native.getContext('2d').getImageData(0, 0, width, height).data;
      const expected = full.getContext('2d').getImageData(x, y, width, height).data;
      if (actual.some((value, i) => value !== expected[i])) throw Error('Incorrect native crop in bay ' + bayId);
      const enlarged = zoom.getContext('2d').getImageData(0, 0, zoom.width, zoom.height).data;
      for (let y = 0; y < zoom.height; y++) {
        for (let x = 0; x < zoom.width; x++) {
          const source = (Math.floor(y / 4) * width + Math.floor(x / 4)) * 4;
          const target = (y * zoom.width + x) * 4;
          for (let channel = 0; channel < 4; channel++) {
            if (expected[source + channel] !== enlarged[target + channel]) throw Error('Blurred pixels in bay ' + bayId);
          }
        }
      }
    }, { left, right, top, bottom, bayId, cropId: full ? 'bowl-bay' : 'full-end-bay' });
  }
  await markers.check();
  assert.equal(await verifyEndMarkers(stadium, sections), true, 'All selected occupied and vacant places are marked');
  await markers.uncheck();
  assert.equal(await stadium.evaluate(c => c.toDataURL()), overview);
  if (full) {
    await reservations.check();
    assert.deepEqual(await legend.getByRole('listitem').allTextContents(), reservationLegend);
    await verifyReservationPixels(stadium, overview);
    await reservations.uncheck();
    assert.equal(await legend.count(), 0);
    assert.equal(await stadium.evaluate(canvas => canvas.toDataURL()), overview);
  }
  await page.setViewportSize({ width: 360, height: 800 });
  await bayPicker.selectOption('2');
  assert.equal(await detail.locator('.bay-six-zoom').evaluate(element =>
    element.scrollWidth > element.clientWidth && document.documentElement.scrollWidth === window.innerWidth), true);
  if (full) {
    for (const id of ['7', '11']) {
      await bayPicker.selectOption(id);
      const zoom = detail.getByRole('region', { name: 'Pixel zoom · 4×' });
      await zoom.focus();
      await zoom.press('End');
      assert.equal(await zoom.evaluate(element =>
        element.scrollHeight > element.clientHeight && element.scrollWidth > element.clientWidth), true);
      await page.waitForFunction(() => document.activeElement.scrollTop > 0);
      assert.equal(await page.getByRole('link', { name: 'Link to this bay' }).getAttribute('href'), '?view=full-bowl&bay=' + id + '#bowl-detail');
    }
  }
  await page.setViewportSize({ width: 1200, height: 960 });
  return overview;
}

await mkdir(captures, { recursive: true });
const server = await createServer({ root, server: { host: '127.0.0.1', port: 0 } });
await server.listen();
let browser;
try {
  browser = await chromium.launch({ executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH });
  const page = await browser.newPage({ viewport: { width: 1200, height: 960 } });
  const url = 'http://127.0.0.1:' + server.httpServer.address().port + '/stadium-mockup';
  await page.goto(url);
  const canvas = page.locator('canvas[data-ready="true"]');
  await canvas.waitFor();
  const select = page.getByLabel('Gallery view');
  assert.deepEqual(studies.map(({ id }) => id), ['a', 'b', 'c']);
  assert.equal(await select.inputValue(), 'full-bowl');
  await page.getByLabel(fullKickoffLabel).waitFor();
  await select.selectOption('b');
  assert.equal(await select.inputValue(), 'b');
  await page.getByText('6 of 9 occupied', { exact: true }).waitFor();
  assert.equal(await select.locator('option:checked').textContent(), 'B · 66% full');
  assert.equal(new Set(poseColumns).size, 12);
  assert.equal(new Set(poseColumns.map(identity)).size, 10);
  const pairs = Map.groupBy(posesManifest, ({ id }) => identity(id));
  assert.deepEqual([...pairs].filter(([, poses]) => poses.length > 1).map(([id]) => id).sort(), ['horn', 'tambourine']);
  for (const id of ['horn', 'tambourine']) {
    assert.deepEqual(pairs.get(id).map(({ id }) => id).sort(), ['seated-' + id, 'standing-' + id]);
  }
  assert.deepEqual(posesManifest.filter(({ wardrobe }) => wardrobe === 'neutral')
    .map(({ id }) => identity(id)).sort(), ['chestnuts', 'mug', 'pie']);
  assert.equal(posesManifest.filter(({ wardrobe }) => wardrobe === 'team').length, 9);
  assert.deepEqual(await select.locator('option').allTextContents(),
    ['Full stadium · Kickoff', 'North and south · Full stands', 'A · 100% full', 'B · 66% full', 'C · 33% full', 'North and south · 33%',
      'North and south · Pose showcase', 'Empty stadium', 'Show seating masks']);
  const rail = page.getByRole('checkbox', { name: 'Front rail' });
  const markers = page.getByRole('checkbox', { name: 'Seat markers' });
  const benches = page.getByRole('checkbox', { name: 'Bench tops' });
  const benchKey = page.getByRole('list', { name: 'Bench top key' });
  assert.equal(await rail.isChecked(), true);
  assert.equal(await markers.isChecked(), false);
  assert.equal(await benches.isChecked(), false);
  assert.equal(await benchKey.count(), 0);
  await verifyFullRoster(page);

  await select.selectOption('empty');
  await page.getByLabel('Empty Gatehouse stadium').waitFor();
  await canvas.screenshot({ path: resolve(captures, 'layout-base.png') });
  const original = await canvas.evaluate(c => c.toDataURL());
  assert.deepEqual(Object.fromEntries(lockerStairwell.placements.map(({ corner, rotate180 }) => [corner, rotate180])),
    { northwest: false, southeast: true });
  const stairwellProof = await Promise.all([['locker-stairwell.png', 'image/png'], ['empty-gatehouse.webp', 'image/webp']]
    .map(async ([file, mime]) => 'data:' + mime + ';base64,' + await readFile(resolve(artwork, file), 'base64')));
  const emptyStairs = await stairwellCrops(canvas, stairwellProof);
  await benches.check();
  await benchKey.waitFor();
  const colors = await benchKey.locator('li span').evaluateAll(spans => spans.map(span =>
    getComputedStyle(span).backgroundColor.match(/\d+/g).map(Number)));
  const maxPlanks = Math.max(...benchLayout.bays.map(({ planks }) => planks.length));
  assert.equal(colors.length, maxPlanks);
  assert.equal(new Set(colors.map(color => color.join(','))).size, maxPlanks);
  assert.deepEqual(await benchKey.locator('li').allTextContents(), colors.map((_, index) => 'Plank ' + (index + 1)));
  assert.deepEqual(benchLayout.bays.map(({ id }) => id).sort((a, b) => a - b), layout.bays.map(({ id }) => id));
  const { overlay: benchOverlay, counts: plankCounts } = await benchReference(canvas, colors);
  for (const { id, planks } of benchLayout.bays) {
    assert.ok(planks.length > 0, 'Bench geometry exists for bay ' + id);
    assert.deepEqual(planks.map(plank => plank.id).sort((a, b) => a - b), planks.map((_, index) => id * 10 + index + 1));
    for (const plank of planks) assert.ok(plankCounts[plank.id] > 0, 'Visible pixels for plank ' + plank.id);
  }
  const benched = await drawBenchReference(canvas, original, benchOverlay);
  assert.equal(await canvas.evaluate(c => c.toDataURL()), benched, 'Bench tops draw exactly in the approved 15 bays');
  await canvas.screenshot({ path: resolve(captures, 'bench-tops.png') });
  await page.getByRole('main').screenshot({ path: resolve(captures, 'bench-tops-gallery.png') });
  await benches.uncheck();
  assert.equal(await canvas.evaluate(c => c.toDataURL()), original);

  const bayDraws = [];
  const fullPlan = new Map(studies[0].seats.map(seat => [seatKey(seat), seat]));
  const fullRows = Map.groupBy(fullPlan.values(), seat => seat.plankId);
  for (const row of fullRows.values()) row.sort((a, b) => a.slot.y - b.slot.y);
  assert.equal(fullPlan.size, 9);
  assert.equal(fullPlan.size, studies[0].capacity);
  let previousLocations = new Set(fullPlan.keys());
  for (const [index, { id, label, seats, spacing, capacity }] of studies.entries()) {
    const seatsPerPlank = [3, 2, 1][index];
    const percentage = [100, 66, 33][index];
    assert.equal(capacity, fullPlan.size);
    assert.equal(seats.length, seatsPerPlank * 3);
    assert.equal(Math.floor(seats.length * 100 / capacity), percentage);
    assert.ok(label.includes(percentage + '%'));
    const locations = new Set(seats.map(seatKey));
    assert.equal(locations.size, seats.length);
    assert.ok([...locations].every(location => previousLocations.has(location)), id + ' is a subset of the prior study');
    previousLocations = locations;
    const vacant = [...fullPlan.values()].filter(seat => !locations.has(seatKey(seat)));
    const standing = seats.filter(seat => seat.stance === 'standing');
    const neutralSeats = seats.filter(({ neutral }) => neutral);
    assert.equal(standing.length, id === 'a' ? 0 : 1);
    assert.equal(neutralSeats.length, seatsPerPlank);
    assert.equal(new Set(neutralSeats.map(({ pose }) => identity(pose))).size, neutralSeats.length);
    const seatsByPlank = new Map();
    for (const seat of seats) {
      const planned = fullPlan.get(seatKey(seat));
      assert.ok(planned, id + ' uses a fixed slot from A');
      assert.equal(seat.neutral, poseById.get(seat.pose).wardrobe === 'neutral', id + ' uses the catalog wardrobe');
      assert.equal(seat.neutral, planned.neutral, id + ' preserves its kit');
      if (seat.stance === 'standing') {
        assert.equal(planned.pose, seat.pose.replace(/^standing-/, 'seated-'), id + ' preserves supporter identity across stances');
        const row = fullRows.get(seat.plankId);
        const north = row[row.indexOf(planned) - 1];
        assert.ok(north, id + ' has a physical seat north of ' + seatKey(seat));
        assert.ok(!locations.has(seatKey(north)), id + ' leaves the immediately north seat ' + seatKey(north) + ' vacant');
      } else {
        assert.deepEqual(seat, planned, id + ' preserves the seated pose at ' + seatKey(seat));
      }
      assert.equal(typeof seat.neutral, 'boolean');
      const row = seatsByPlank.get(seat.plankId) ?? [];
      row.push(seat);
      seatsByPlank.set(seat.plankId, row);
    }
    assert.equal(seatsByPlank.size, 3, id + ' uses three planks');
    assert.ok([...seatsByPlank.values()].every(row => row.length === seatsPerPlank),
      id + ' fills the same number of seats on each plank');
    const nativeGaps = [...seatsByPlank.entries()].flatMap(([plankId, row]) => {
      row.sort((a, b) => a.y - b.y);
      return row.slice(1).map((seat, index) => {
        const previous = row[index];
        assert.notEqual(seat.pose, previous.pose, id + ' repeats an adjacent pose on plank ' + plankId);
        return seat.y - previous.y;
      });
    });
    const expectedSpacing = nativeGaps.length ? { min: Math.min(...nativeGaps), max: Math.max(...nativeGaps) } : null;
    assert.deepEqual(spacing ?? null, expectedSpacing);
    await select.selectOption(id);
    await page.getByLabel('Gatehouse stadium with ' + label + ' in west bay 6').waitFor();
    const [crowdPng, railPng] = await Promise.all(['.png', '-rail.png'].map(suffix =>
      readFile(resolve(artwork, 'bay-six', id + suffix)).then(bytes => bytes.toString('base64'))));
    const [expectedBay, expectedBenchedBay] = await canvas.evaluate(async (c, { original, benched, bay, crowdPng, railPng }) => {
      async function decode(src) {
        const image = new Image();
        image.src = src;
        await image.decode();
        return image;
      }
      const region = document.createElement('canvas');
      region.width = bay.width;
      region.height = bay.height;
      const ctx = region.getContext('2d');
      const [empty, benches, crowd, rail] = await Promise.all([
        decode(original), decode(benched), decode('data:image/png;base64,' + crowdPng), decode('data:image/png;base64,' + railPng),
      ]);
      return [empty, benches].map(base => {
        ctx.drawImage(base, bay.x, bay.y, bay.width, bay.height, 0, 0, bay.width, bay.height);
        ctx.drawImage(crowd, 0, 0);
        const withoutRail = region.toDataURL();
        ctx.drawImage(rail, 0, 0);
        return { withoutRail, withRail: region.toDataURL() };
      });
    }, { original, benched, bay, crowdPng, railPng });
    const bayDraw = () => canvas.evaluate((c, { x, y, width, height }) => {
      const region = document.createElement('canvas');
      region.width = width;
      region.height = height;
      region.getContext('2d').drawImage(c, x, y, width, height, 0, 0, width, height);
      return region.toDataURL();
    }, bay);

    assert.equal(await bayDraw(), expectedBay.withRail, id + ' uses its exact crowd and rail artwork');
    assert.notEqual(expectedBay.withRail, expectedBay.withoutRail, id + ' rail changes visible pixels');
    bayDraws.push(expectedBay.withRail);

    const review = page.getByRole('region', { name: 'West bay 6' });
    const native = review.locator('canvas[aria-labelledby="bay-six-native"]');
    const zoom = review.locator('canvas[aria-labelledby="bay-six-zoom"]');
    const [nativeBox, zoomBox] = await Promise.all([native.boundingBox(), zoom.boundingBox()]);
    assert.equal(zoomBox.width, nativeBox.width * 4);
    assert.equal(zoomBox.height, nativeBox.height * 4);
    const disclosure = review.getByText('Inspect full poses', { exact: true });
    assert.equal(await disclosure.evaluate(summary => summary.parentElement.open), false, id + ' starts with its pose inspection closed');
    await review.screenshot({ path: resolve(captures, 'west-bay-six-' + id + '.png') });

    await disclosure.press('Enter');
    const poses = review.getByRole('list', { name: 'Supporters in bay order' });
    await poses.waitFor();
    assert.deepEqual(await poses.locator('figcaption').allTextContents(), seats.map(caption));
    assert.ok(await review.getByText(`${seats.length} of ${capacity} occupied`, { exact: true }).isVisible());
    if (spacing) {
      const spacingLabel = spacing.min === spacing.max ? String(spacing.min) : `${spacing.min}–${spacing.max}`;
      assert.ok(await review.getByText(spacingLabel + ' px spacing along planks', { exact: false }).isVisible());
    } else {
      assert.equal(await review.getByText('spacing along planks', { exact: false }).count(), 0);
    }
    for (const { pose, stance, origin, knee, otherSole, plankId, x, y } of seats) {
      const local = landmarks[pose];
      if (stance === 'standing') {
        assert.equal(local.behindSeat, undefined, pose + ' has no seated occlusion');
      } else {
        assert.ok(local.behindSeat?.length > 0, pose + ' defines its occluded calf region');
      }
      assert.deepEqual([x - origin.x, y - origin.y], local.contact, id + ' registers ' + pose + ' at its contact');
      assert.deepEqual(knee ? [knee.x - origin.x, knee.y - origin.y] : undefined, local.knee,
        id + ' preserves the authored knee position for ' + pose);
      assert.deepEqual(otherSole ? [otherSole.x - origin.x, otherSole.y - origin.y] : undefined, local.otherSole,
        id + ' preserves the authored second sole position for ' + pose);
      const plank = bayPlanks.find(({ id }) => id === plankId);
      assert.ok(plank, id + ' resolves plank ' + plankId + ' for ' + pose);
      const supports = ({ x, y }) => plank.rects.some(([left, top, width, height]) =>
        x >= left && x < left + width && y >= top && y < top + height);
      assert.ok(supports({ x, y }) && (!otherSole || supports(otherSole)),
        id + ' registers each contact with plank ' + plankId);
    }
    const revealsTimber = await poses.evaluate(async (list, {
      atlasPng, crowdPng, mask, withoutRail, seats, landmarks, bay, bayPlanks, poseCount,
    }) => {
      const size = 48;
      const factor = 2;
      async function decode(src) {
        const img = new Image();
        img.src = src;
        await img.decode();
        return img;
      }
      function surface(width, height) {
        const canvas = document.createElement('canvas');
        canvas.width = width;
        canvas.height = height;
        return canvas.getContext('2d');
      }
      const [atlas, crowd, seatingMask, cleanBay] = await Promise.all([
        ...[atlasPng, crowdPng, mask].map(bytes => decode('data:image/png;base64,' + bytes)),
        decode(withoutRail),
      ]);
      if (atlas.width !== size * poseCount || atlas.height !== size * 2) throw Error('Expected home and neutral pose rows');
      const source = surface(atlas.width, atlas.height);
      source.drawImage(atlas, 0, 0);
      const assembled = surface(bay.width, bay.height);
      const unoccluded = surface(bay.width, bay.height);
      const maskRegion = surface(bay.width, bay.height);
      maskRegion.drawImage(seatingMask, bay.x, bay.y, bay.width, bay.height, 0, 0, bay.width, bay.height);
      const seatingIds = maskRegion.getImageData(0, 0, bay.width, bay.height).data;
      const tile = surface(size, size);
      const contains = (rects, x, y) => rects.some(([left, top, width, height]) =>
        x >= left && x < left + width && y >= top && y < top + height);
      const canvases = list.querySelectorAll('canvas');
      if (canvases.length !== seats.length) throw Error('Missing isolated pose');
      for (const [index, { pose, plankId, origin, column, neutral }] of seats.entries()) {
        const x = column * size;
        const row = neutral ? size : 0;
        const cell = source.getImageData(x, row, size, size);
        const native = cell.data;
        const zoom = canvases[index];
        if (zoom.width !== size * factor || zoom.height !== size * factor) throw Error('Expected a 2× isolated pose');
        const pixels = zoom.getContext('2d').getImageData(0, 0, zoom.width, zoom.height).data;
        for (let y = 0; y < zoom.height; y++) {
          for (let x = 0; x < zoom.width; x++) {
            const sample = (Math.floor(y / factor) * size + Math.floor(x / factor)) * 4;
            const target = (y * zoom.width + x) * 4;
            if (native.subarray(sample, sample + 4).some((value, channel) => value !== pixels[target + channel])) {
              throw Error('Isolated pose ' + index + ' differs from its full ' + (neutral ? 'neutral' : 'home') + ' kit');
            }
          }
        }
        if (!native.some((channel, index) => index % 4 === 3 && channel > 0)) throw Error('Empty isolated pose');
        unoccluded.drawImage(atlas, x, row, size, size, origin.x, origin.y, size, size);

        const eligible = landmarks[pose].behindSeat ?? [];
        const support = bayPlanks.find(({ id }) => id === plankId).rects;
        for (let y = 0; y < size; y++) {
          for (let x = 0; x < size; x++) {
            const pixel = (y * size + x) * 4;
            if (!native[pixel + 3] || !contains(eligible, x, y)) continue;
            const bx = origin.x + x, by = origin.y + y;
            if (bx < 0 || by < 0 || bx >= bay.width || by >= bay.height) continue;
            if (seatingIds[(by * bay.width + bx) * 4] === bay.id && contains(support, bx, by)) {
              native.fill(0, pixel, pixel + 4);
            }
          }
        }
        tile.putImageData(cell, 0, 0);
        assembled.drawImage(tile.canvas, origin.x, origin.y);
      }
      const expected = surface(bay.width, bay.height);
      expected.drawImage(crowd, 0, 0);
      if (assembled.canvas.toDataURL() !== expected.canvas.toDataURL()) {
        throw Error('Occluded pose recomposition differs from the assembled bay crowd');
      }
      const barePixels = unoccluded.getImageData(0, 0, bay.width, bay.height).data;
      const coveredPixels = assembled.getImageData(0, 0, bay.width, bay.height).data;
      const clean = surface(bay.width, bay.height);
      clean.drawImage(cleanBay, 0, 0);
      const cleanPixels = clean.getImageData(0, 0, bay.width, bay.height).data;
      for (let offset = 0; offset < barePixels.length; offset += 4) {
        if (barePixels[offset + 3] && !coveredPixels[offset + 3] &&
          barePixels.subarray(offset, offset + 3).some((value, channel) => value !== cleanPixels[offset + channel])) return true;
      }
      return false;
    }, { atlasPng, crowdPng, mask, withoutRail: expectedBay.withoutRail,
      seats: seats.map(seat => ({ ...seat, column: poseColumns.indexOf(seat.pose) })), landmarks, bay, bayPlanks,
      poseCount: poseColumns.length });
    await review.getByRole('heading', { name: 'West bay 6' }).click();
    await review.screenshot({ path: resolve(captures, 'west-bay-six-' + id + '-poses.png') });

    await rail.uncheck();
    assert.equal(await markers.isChecked(), false);
    assert.equal(await benches.isChecked(), false);
    // Transparent pixels in this reference show the original stadium below the crowd.
    assert.equal(await bayDraw(), expectedBay.withoutRail, id + ' renders the clean composite pixel for pixel');
    assert.ok(revealsTimber, id + ' visibly exposes timber where opaque calves were occluded');
    await review.screenshot({ path: resolve(captures, 'west-bay-six-' + id + '-occlusion.png') });
    await benches.check();
    assert.equal(await bayDraw(), expectedBenchedBay.withoutRail, id + ' bench colors sit under the crowd');
    await rail.check();
    assert.equal(await bayDraw(), expectedBenchedBay.withRail, id + ' bench colors sit under the foreground rail');
    await markers.check();
    assert.ok(await review.getByText('mark hip or boot contact.', { exact: false }).isVisible());
    assert.equal(await review.getByText('mark vacant seats.', { exact: false }).isVisible(), vacant.length > 0);
    assert.equal(await canvas.evaluate((c, { bay, seats, vacant }) => {
      const context = c.getContext('2d');
      const color = ({ x, y }) => context.getImageData(bay.x + x, bay.y + y, 1, 1).data.join(',');
      const vacantMark = ({ x, y }) => color({ x: x - 1, y: y - 1 }) === '212,205,193,255';
      return seats.every(seat => color(seat) === '128,245,255,255' &&
        (!seat.knee || color(seat.knee) === '255,225,123,255') &&
        (!seat.otherSole || color(seat.otherSole) === '128,245,255,255') && !vacantMark(seat.slot)) &&
        vacant.every(({ slot }) => vacantMark(slot));
    }, { bay, seats, vacant }), true, id + ' marks occupied contacts and vacant locations above the bench and rail layers');
    await review.screenshot({ path: resolve(captures, 'west-bay-six-' + id + '-benches.png') });
    assert.equal(await native.evaluate(async (c, original) => {
      const full = document.querySelector('canvas[data-ready="true"]');
      const zoom = document.querySelector('canvas[aria-labelledby="bay-six-zoom"]');
      const pixels = c.getContext('2d').getImageData(0, 0, c.width, c.height).data;
      const enlarged = zoom.getContext('2d').getImageData(0, 0, zoom.width, zoom.height).data;
      const frame = { x: 0, y: 572 };
      const fullPixels = full.getContext('2d').getImageData(frame.x, frame.y, c.width, c.height).data;
      if (pixels.some((value, index) => value !== fullPixels[index])) return false;
      for (let y = 0; y < zoom.height; y++) {
        for (let x = 0; x < zoom.width; x++) {
          const source = (Math.floor(y / 4) * c.width + Math.floor(x / 4)) * 4;
          const target = (y * zoom.width + x) * 4;
          if (pixels.subarray(source, source + 4).some((value, i) => value !== enlarged[target + i])) return false;
        }
      }

      // The native frame extends onto the pitch and contains the scale player's real draw.
      const empty = new Image();
      empty.src = original;
      await empty.decode();
      const base = document.createElement('canvas');
      base.width = c.width;
      base.height = c.height;
      const context = base.getContext('2d');
      context.drawImage(empty, -frame.x, -frame.y);
      const firstSquare = { x: 156, width: 32 };
      const originalPitch = context.getImageData(firstSquare.x, 0, firstSquare.width, c.height).data;
      const mockupPitch = c.getContext('2d').getImageData(firstSquare.x, 0, firstSquare.width, c.height).data;
      return mockupPitch.some((value, index) => value !== originalPitch[index]);
    }, original), true, id + ' draws the real crop and enlarges each native pixel to a 4×4 block');
    await markers.uncheck();
    assert.equal(await bayDraw(), expectedBenchedBay.withRail);
    await benches.uncheck();
    assert.equal(await bayDraw(), expectedBay.withRail);
  }
  assert.equal(new Set(bayDraws).size, 3);

  await select.selectOption('masks');
  await page.getByText('Teal marks home seating.',{exact:false}).waitFor();
  assert.equal(await page.getByText('Inspect full poses', { exact: true }).count(), 0);
  const counts = await canvas.evaluate(async(c,{original,mask})=>{
    async function pixels(src) {
      const im=new Image(); im.src=src; await im.decode();
      const tmp=document.createElement('canvas');tmp.width=c.width;tmp.height=c.height;
      const ctx=tmp.getContext('2d');ctx.drawImage(im,0,0);return ctx.getImageData(0,0,c.width,c.height).data;
    }
    const before=await pixels(original), ids=await pixels('data:image/png;base64,'+mask);
    const after=c.getContext('2d').getImageData(0,0,c.width,c.height).data;
    const counts=Array(16).fill(0);
    for(let i=0;i<ids.length;i+=4){
      const id=ids[i], changed=before[i]!==after[i] || before[i+1]!==after[i+1] || before[i+2]!==after[i+2];
      if(changed!==Boolean(id) || id>15) throw Error('Unexpected mask pixel '+i/4);
      counts[id]++;
    }
    return counts;
  },{original,mask});
  assert.ok(counts.every(n=>n>100));
  await canvas.screenshot({path:resolve(captures,'empty-seating.png')});
  const masksOnly = await canvas.evaluate(c => c.toDataURL());
  await benches.check();
  const combined = await drawBenchReference(canvas, masksOnly, benchOverlay);
  assert.equal(await canvas.evaluate(c => c.toDataURL()), combined, 'Bench and seating masks compose independently');
  await canvas.screenshot({ path: resolve(captures, 'seating-and-bench-tops.png') });
  await select.selectOption('empty');
  await page.waitForFunction(src => document.querySelector('canvas').toDataURL() === src, benched);
  assert.equal(await benches.isChecked(), true);
  await benches.uncheck();
  await page.waitForFunction(src=>document.querySelector('canvas').toDataURL()===src,original);
  const fullBowl = await verifyKickoff(page, original, true);
  assert.deepEqual(await stairwellCrops(canvas), emptyStairs, 'The empty and full stadium share both native stairwell crops');
  const overhead = page.getByLabel('Overhead end');
  assert.equal(await overhead.inputValue(), 'home');
  await page.locator('.gatehouse-overview').screenshot({ path: resolve(captures, 'kickoff-home.png') });
  await overhead.selectOption('away');
  await page.locator('.gatehouse-overview[data-overhead-end="away"] canvas[data-ready="true"]').waitFor();
  assert.equal(await canvas.getAttribute('aria-label'), fullKickoffLabel +
    '. Ironbank Rovers in the north end zone and Cinderclaw Crew in the south end zone.');
  await page.getByText('West (away) 57 of 86 occupied', { exact: true }).waitFor();
  await page.getByText('East (home) 57 of 86 occupied', { exact: true }).waitFor();
  assert.deepEqual(await stairwellCrops(canvas), emptyStairs, 'The interim away view retains the approved upright stairwells');
  assert.match(await page.getByRole('link', { name: 'Link to this bay' }).getAttribute('href'), /&end=away#bowl-detail$/);
  await page.locator('.gatehouse-overview').screenshot({ path: resolve(captures, 'kickoff-away.png') });
  await overhead.selectOption('home');
  await page.locator('.gatehouse-overview[data-overhead-end="home"] canvas[data-ready="true"]').waitFor();
  await verifyKickoff(page, original, false);
  await verifyEndStands(page, original);
  await verifyEndPoseShowcase(page, original);
  await page.goto(url + '?view=end-poses&group=standing');
  await canvas.waitFor();
  assert.equal(await select.inputValue(), 'end-poses');
  assert.equal(await page.getByLabel('Pose group').inputValue(), 'standing');
  await page.setViewportSize({ width: 360, height: 800 });
  assert.equal(await page.locator('.bay-six-zoom').evaluateAll(elements =>
    elements.length === 2 && elements.every(element => element.scrollWidth > element.clientWidth) &&
    document.documentElement.scrollWidth === window.innerWidth), true);
  await page.setViewportSize({ width: 1200, height: 960 });
  await page.goto(url + '?view=ends');
  await canvas.waitFor();
  assert.equal(await select.inputValue(), 'ends');
  await page.getByRole('region', { name: 'North and south stands' }).waitFor();
  await page.setViewportSize({ width: 360, height: 800 });
  assert.equal(await page.locator('.bay-six-zoom').evaluateAll(elements =>
    elements.length === 2 && elements.every(element => element.scrollWidth > element.clientWidth) &&
    document.documentElement.scrollWidth === window.innerWidth), true);
  await page.setViewportSize({ width: 1200, height: 960 });
  await page.goto(url + '?view=full-ends&bay=13');
  await canvas.waitFor();
  assert.equal(await select.inputValue(), 'full-ends');
  assert.equal(await page.getByLabel('End-stand bay').inputValue(), '13');
  await page.goto(url + '?view=full-bowl&bay=11#bowl-detail');
  await canvas.waitFor();
  assert.equal(await select.inputValue(), 'full-bowl');
  assert.equal(await page.getByLabel('Seating bay').inputValue(), '11');
  await page.waitForFunction(() => window.scrollY > 0);
  const reservations = page.getByRole('checkbox', { name: 'Apron reservations' });
  const legend = page.getByRole('list', { name: 'Apron reservation key' });
  await page.goto(url + '?view=full-bowl&reservations=1&bay=6#bowl-detail');
  await canvas.waitFor();
  assert.equal(await reservations.isChecked(), true);
  assert.equal(await page.getByLabel('Seating bay').inputValue(), '6');
  assert.match(await page.getByRole('link', { name: 'Link to this bay' }).getAttribute('href'), /&reservations=1#bowl-detail$/);
  await verifyReservationPixels(canvas, fullBowl);
  assert.deepEqual(await stairwellCrops(canvas), emptyStairs, 'The kickoff guide preserves both stairwell crops');
  const westCrops = page.locator('#bowl-bay-native-canvas, #bowl-bay-zoom-canvas');
  const guidedCrops = await westCrops.evaluateAll(crops => crops.map(crop => crop.toDataURL()));
  assert.equal(guidedCrops.length, 2);
  await page.setViewportSize({ width: 360, height: 800 });
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth === window.innerWidth), true);
  await page.setViewportSize({ width: 1200, height: 960 });
  await reservations.uncheck();
  assert.equal(await canvas.evaluate(c => c.toDataURL()), fullBowl);
  assert.ok((await westCrops.evaluateAll(crops => crops.map(crop => crop.toDataURL())))
    .every((pixels, i) => pixels !== guidedCrops[i]), 'Both bay crops show the reservations when enabled');
  await page.goto(url + '?view=empty&reservations=1');
  await canvas.waitFor();
  assert.equal(await select.inputValue(), 'empty');
  assert.equal(await reservations.isChecked(), true);
  assert.deepEqual(await legend.getByRole('listitem').allTextContents(), reservationLegend);
  await verifyReservationPixels(canvas, original);
  assert.deepEqual(await stairwellCrops(canvas), emptyStairs, 'The empty guide preserves both stairwell crops');
  await reservations.uncheck();
  assert.equal(await canvas.evaluate(c => c.toDataURL()), original);
  await page.goto(url+'?view=scale');
  await canvas.waitFor();
  assert.equal(await select.inputValue(), 'b');
  await page.setViewportSize({ width: 360, height: 800 });
  await benches.check();
  await benchKey.waitFor();
  await page.getByText('Inspect full poses', { exact: true }).click();
  await page.getByText(rosterLabel, { exact: true }).click();
  assert.equal(await page.locator('.bay-six-zoom').evaluate(element =>
    element.scrollWidth > element.clientWidth && document.documentElement.scrollWidth === window.innerWidth), true);
  console.log('All 15 bays, both corner locker stairs, end-zone and regalia palettes, apron reservations, side kits and facings, 22-player kickoff, approved west and end views, all 12 pose pairs, native and enlarged crops, rails and seat markers passed.');
} finally {
  await browser?.close();await server.close();
}
