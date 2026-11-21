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
}

const PITCH = { x: 156, y: 169, width: 480, height: 832 };
const SQUARE = 32;
const CLUBS = {
  north: { name: 'Cinderclaw Crew', primary: 'red' },
  south: { name: 'Ironbank Rovers', primary: 'navy' },
};
const INKS = ['#fff9e8', '#07080b'];

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
  return '#' + channels.map((value, i) => Math.round((73 * value + 27 * [115, 124, 67][i]) / 100)
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
    { x, y, width, height: SQUARE, color: '#c03433' },
    { x, y: y + height - SQUARE, width, height: SQUARE, color: '#335c9a' },
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
    for (const paint of new Set(ctx.fills.map(rect => rect.color).filter(color => color !== ink))) {
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

function northWord(name: string, width = PITCH.width) {
  const ctx = new PaintLog();
  drawGatehouseEndZones(ctx, { ...PITCH, width }, SQUARE, { ...CLUBS, north: { ...CLUBS.north, name } });
  return nameMarks(ctx, true);
}

test('normalizes accents and punctuation and uses an appropriate mark when names cannot fit', () => {
  assert.deepEqual(northWord('Étoile — L’hiver 12'), northWord("ETOILE - L'HIVER 12"));
  assert.deepEqual(northWord('漢字 🛡️'), northWord(''));
  assert.notDeepEqual(northWord(''), northWord('Y'));
  assert.deepEqual(northWord('Left Rovers Eleven '.repeat(100), 82), northWord('LRE', 82));
  assert.deepEqual(northWord('Long club name', 2), [{ x: PITCH.x + 1, y: PITCH.y + 16, width: 1, height: 1, color: '#fff9e8' }]);
});
