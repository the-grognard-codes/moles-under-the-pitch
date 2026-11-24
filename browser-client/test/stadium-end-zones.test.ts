import assert from 'node:assert/strict';
import test from 'node:test';
import { teamColorManifest } from '../src/generated-team-colors.ts';
import { drawGatehouseEndZones } from '../src/stadium-end-zones.ts';

type PixelRect = { x: number; y: number; width: number; height: number; color: string };

class PaintLog {
  fillStyle = '#123456';
  fills: PixelRect[] = [];

  fillRect(x: number, y: number, width: number, height: number) {
    this.fills.push({ x, y, width, height, color: this.fillStyle });
  }
  clearRect(x: number, y: number, width: number, height: number) {
    this.fills.push({ x, y, width, height, color: 'transparent' });
  }
}

const PITCH = { x: 156, y: 169, width: 480, height: 832 };
const SQUARE = 32;
const CLUBS = {
  north: { name: 'Cinderclaw Crew', primary: 'red' },
  south: { name: 'Ironbank Rovers', primary: 'navy' },
};
const INKS = ['#fdf6e1', '#07080b'];

function luminance(hex: string) {
  const channels = [1, 3, 5].map(index => Number.parseInt(hex.slice(index, index + 2), 16) / 255);
  return channels.reduce((total, value, i) => total + [0.2126, 0.7152, 0.0722][i] *
    (value > .04045 ? ((value + .055) / 1.055) ** 2.4 : value / 12.92), 0);
}

function contrast(one: string, other: string) {
  const values = [luminance(one), luminance(other)].sort((a, b) => a - b);
  return (values[1] + .05) / (values[0] + .05);
}

function blendedPaint(color: string) {
  const channels = [1, 3, 5].map(index => Number.parseInt(color.slice(index, index + 2), 16));
  return '#' + channels.map((value, i) => Math.round((value + [115, 124, 67][i]) / 2)
    .toString(16).padStart(2, '0')).join('');
}

function nameMarks(ctx: PaintLog, north: boolean) {
  return ctx.fills.filter(fill => INKS.includes(fill.color) && (fill.y < PITCH.y + SQUARE) === north);
}

function hasInk(marks: PixelRect[], x: number, y: number) {
  return marks.some(mark => x >= mark.x && x < mark.x + mark.width && y >= mark.y && y < mark.y + mark.height);
}

test('paints exactly the two outermost native rows without mutating the canvas fill style', () => {
  const ctx = new PaintLog();
  drawGatehouseEndZones(ctx, PITCH, SQUARE, CLUBS);
  assert.equal(ctx.fillStyle, '#123456');
  const { x, y, width, height } = PITCH;
  assert.deepEqual(ctx.fills.filter(mark => mark.width === width), [
    { x, y, width, height: SQUARE, color: '#a84b38' },
    { x, y: y + height - SQUARE, width, height: SQUARE, color: '#47667f' },
  ]);
  for (const rect of ctx.fills) {
    assert.ok([rect.x, rect.y, rect.width, rect.height].every(Number.isInteger));
    assert.ok(rect.x >= x && rect.x + rect.width <= x + width);
    const inNorth = rect.y >= y && rect.y + rect.height <= y + SQUARE;
    const inSouth = rect.y >= y + height - SQUARE && rect.y + rect.height <= y + height;
    assert.ok(inNorth || inSouth, JSON.stringify(rect));
  }
});

test('renders both full club names centered, upright, and strong enough to occupy 21 native rows', () => {
  const ctx = new PaintLog();
  drawGatehouseEndZones(ctx, PITCH, SQUARE, CLUBS);
  for (const north of [true, false]) {
    const marks = nameMarks(ctx, north);
    const left = Math.min(...marks.map(mark => mark.x));
    const right = Math.max(...marks.map(mark => mark.x + mark.width));
    const top = Math.min(...marks.map(mark => mark.y));
    const bottom = Math.max(...marks.map(mark => mark.y + mark.height));
    const rowTop = north ? PITCH.y : PITCH.y + PITCH.height - SQUARE;
    assert.ok(Math.abs(left + right - (PITCH.x * 2 + PITCH.width)) <= 1);
    assert.ok(Math.abs(top + bottom - (rowTop * 2 + SQUARE)) <= 1);
    assert.equal(bottom - top, 21);
    assert.ok(right - left > 210);

    const expectedFirst = north ? ['01111', '10000', '10000', '10000', '10000', '10000', '01111']
      : ['111', '010', '010', '010', '010', '010', '111'];
    for (const [y, glyphRow] of expectedFirst.entries()) for (const [x, on] of [...glyphRow].entries()) {
      assert.equal(hasInk(marks, left + x * 3 + 1, top + y * 3 + 1), on === '1');
    }
  }
});

test('chooses the highest-contrast ink and clears 4.5:1 for every primary, even with identical kits', () => {
  for (const ramp of teamColorManifest.ramps) {
    const ctx = new PaintLog();
    drawGatehouseEndZones(ctx, PITCH, SQUARE, {
      north: { ...CLUBS.north, primary: ramp.id }, south: { ...CLUBS.south, primary: ramp.id },
    });
    const [ink, ...rest] = new Set(ctx.fills.map(rect => rect.color).filter(color => INKS.includes(color)));
    assert.ok(ink, ramp.id);
    assert.equal(rest.length, 0, ramp.id);
    const base = ctx.fills[0].color;
    assert.equal(base, blendedPaint(ramp.colors[9]), ramp.id);
    assert.equal(contrast(ink, base), Math.max(...INKS.map(color => contrast(color, base))), ramp.id);
    assert.ok(ctx.fills.some(rect => rect.color === blendedPaint(ramp.colors[ink === INKS[0] ? 8 : 10])), ramp.id);
    for (const paint of new Set(ctx.fills.map(rect => rect.color).filter(color => color !== ink && color !== 'transparent'))) {
      assert.ok(contrast(ink, paint) >= 4.5, `${ramp.id} ${ink} ${paint}`);
    }
  }
});

test('uses identical native pixels when the renderer paints into packed cached rows', () => {
  const full = new PaintLog(), packed = new PaintLog();
  drawGatehouseEndZones(full, PITCH, SQUARE, CLUBS);
  drawGatehouseEndZones(packed, { x: 0, y: 0, width: PITCH.width, height: SQUARE * 2 }, SQUARE, CLUBS);
  const relative = full.fills.map(rect => ({ ...rect, x: rect.x - PITCH.x,
    y: rect.y < PITCH.y + SQUARE ? rect.y - PITCH.y : rect.y - (PITCH.y + PITCH.height - SQUARE * 2) }));
  assert.deepEqual(packed.fills, relative);
});

test('exposes small patches of real turf and keeps their locations stable across kits', () => {
  const normal = new PaintLog(), recolored = new PaintLog();
  drawGatehouseEndZones(normal, PITCH, SQUARE, CLUBS);
  drawGatehouseEndZones(recolored, PITCH, SQUARE, {
    north: { ...CLUBS.north, primary: 'white' }, south: { ...CLUBS.south, primary: 'white' },
  });
  const holes = (ctx: PaintLog, top: number) => ctx.fills.filter(rect =>
    rect.color === 'transparent' && rect.y >= top && rect.y < top + SQUARE);
  const rowPatterns: string[][] = [];
  for (const top of [PITCH.y, PITCH.y + PITCH.height - SQUARE]) {
    const clear = holes(normal, top);
    assert.deepEqual(clear, holes(recolored, top));
    const ink = nameMarks(normal, top === PITCH.y);
    const visible = clear.filter(pixel => !hasInk(ink, pixel.x, pixel.y));
    const sites = new Set(visible.map(pixel => [pixel.x - PITCH.x, pixel.y - top].join(',')));
    assert.ok(sites.size > 35 && sites.size < PITCH.width * SQUARE * .015);
    assert.ok(visible.some(pixel => sites.has([pixel.x + 1 - PITCH.x, pixel.y - top].join(','))));
    rowPatterns.push([...sites]);
  }
  assert.notDeepEqual(rowPatterns[0], rowPatterns[1]);
});

test('edge nicks leave the center of every printed block and small lettering intact', () => {
  const ctx = new PaintLog();
  drawGatehouseEndZones(ctx, PITCH, SQUARE, CLUBS);
  for (const north of [true, false]) {
    const ink = nameMarks(ctx, north);
    const left = Math.min(...ink.map(rect => rect.x)), top = Math.min(...ink.map(rect => rect.y));
    const cells = new Map<string, number>();
    for (const rect of ink) {
      const col = Math.floor((rect.x - left) / 3), row = Math.floor((rect.y - top) / 3);
      const key = col + ',' + row;
      cells.set(key, (cells.get(key) ?? 0) + rect.width * rect.height);
      assert.ok(hasInk(ink, left + col * 3 + 1, top + row * 3 + 1));
    }
    const chipped = [...cells.values()].filter(area => area < 9);
    assert.ok(chipped.length >= 2 && chipped.length < cells.size / 10);
    assert.ok(chipped.every(area => area >= 7));
  }
  const tiny = northWord('Ironbank Rovers', 180);
  assert.ok(tiny.every(rect => rect.width === rect.height && rect.width <= 2));
});

function northWord(name: string, width = PITCH.width) {
  const ctx = new PaintLog();
  drawGatehouseEndZones(ctx, { ...PITCH, width }, SQUARE, { ...CLUBS, north: { ...CLUBS.north, name } });
  return nameMarks(ctx, true);
}

test('a missing team name keeps the weathered field paint without ink', () => {
  const ctx = new PaintLog();
  drawGatehouseEndZones(ctx, PITCH, SQUARE, { ...CLUBS, north: { ...CLUBS.north, name: null } });
  assert.deepEqual(nameMarks(ctx, true), []);
  assert.ok(nameMarks(ctx, false).length > 0);
});

test('normalizes accents and punctuation and uses an appropriate mark when names cannot fit', () => {
  assert.deepEqual(northWord('Étoile — L’hiver 12'), northWord("ETOILE - L'HIVER 12"));
  assert.deepEqual(northWord('漢字 🛡️'), northWord(''));
  assert.notDeepEqual(northWord(''), northWord('Y'));
  assert.deepEqual(northWord('Left Rovers Eleven '.repeat(100), 82), northWord('LRE', 82));
  assert.deepEqual(northWord('Long club name', 2), [{ x: PITCH.x + 1, y: PITCH.y + 16, width: 1, height: 1, color: INKS[0] }]);
});
