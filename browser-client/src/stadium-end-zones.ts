import { teamColorManifest } from './generated-team-colors.ts';

type PaintTarget = Pick<CanvasRenderingContext2D, 'fillStyle' | 'fillRect' | 'clearRect'>;
type PitchBounds = { x: number; y: number; width: number; height: number };
type Team = { name: string; primary: string };
type Glyph = readonly string[];

const IVORY = '#fdf6e1';
const CHARCOAL = '#07080b';
const TURF = '#737c43';
const GLYPH_HEIGHT = 7;

// These block letters render directly onto the native pitch pixels.
const glyphs: Record<string, Glyph> = {
  A: ['01110', '10001', '10001', '11111', '10001', '10001', '10001'],
  B: ['11110', '10001', '10001', '11110', '10001', '10001', '11110'],
  C: ['01111', '10000', '10000', '10000', '10000', '10000', '01111'],
  D: ['11110', '10001', '10001', '10001', '10001', '10001', '11110'],
  E: ['11111', '10000', '10000', '11110', '10000', '10000', '11111'],
  F: ['11111', '10000', '10000', '11110', '10000', '10000', '10000'],
  G: ['01111', '10000', '10000', '10111', '10001', '10001', '01111'],
  H: ['10001', '10001', '10001', '11111', '10001', '10001', '10001'],
  I: ['111', '010', '010', '010', '010', '010', '111'],
  J: ['00111', '00010', '00010', '00010', '00010', '10010', '01100'],
  K: ['10001', '10010', '10100', '11000', '10100', '10010', '10001'],
  L: ['10000', '10000', '10000', '10000', '10000', '10000', '11111'],
  M: ['10001', '11011', '10101', '10101', '10001', '10001', '10001'],
  N: ['10001', '11001', '11001', '10101', '10011', '10011', '10001'],
  O: ['01110', '10001', '10001', '10001', '10001', '10001', '01110'],
  P: ['11110', '10001', '10001', '11110', '10000', '10000', '10000'],
  Q: ['01110', '10001', '10001', '10001', '10101', '10010', '01101'],
  R: ['11110', '10001', '10001', '11110', '10100', '10010', '10001'],
  S: ['01111', '10000', '10000', '01110', '00001', '00001', '11110'],
  T: ['11111', '00100', '00100', '00100', '00100', '00100', '00100'],
  U: ['10001', '10001', '10001', '10001', '10001', '10001', '01110'],
  V: ['10001', '10001', '10001', '10001', '10001', '01010', '00100'],
  W: ['10001', '10001', '10001', '10101', '10101', '11011', '10001'],
  X: ['10001', '10001', '01010', '00100', '01010', '10001', '10001'],
  Y: ['10001', '10001', '01010', '00100', '00100', '00100', '00100'],
  Z: ['11111', '00001', '00010', '00100', '01000', '10000', '11111'],
  '0': ['01110', '10001', '10011', '10101', '11001', '10001', '01110'],
  '1': ['010', '110', '010', '010', '010', '010', '111'],
  '2': ['01110', '10001', '00001', '00010', '00100', '01000', '11111'],
  '3': ['11110', '00001', '00001', '01110', '00001', '00001', '11110'],
  '4': ['00010', '00110', '01010', '10010', '11111', '00010', '00010'],
  '5': ['11111', '10000', '10000', '11110', '00001', '00001', '11110'],
  '6': ['01110', '10000', '10000', '11110', '10001', '10001', '01110'],
  '7': ['11111', '00001', '00010', '00100', '01000', '01000', '01000'],
  '8': ['01110', '10001', '10001', '01110', '10001', '10001', '01110'],
  '9': ['01110', '10001', '10001', '01111', '00001', '00001', '01110'],
  '&': ['01100', '10010', '10100', '01000', '10101', '10010', '01101'],
  '-': ['000', '000', '000', '111', '000', '000', '000'],
  "'": ['1', '1', '0', '0', '0', '0', '0'],
  '.': ['0', '0', '0', '0', '0', '0', '1'],
  ' ': ['', '', '', '', '', '', ''],
};

const mark: Glyph = ['00100', '01110', '11011', '10001', '11011', '01110', '00100'];

function rgb(hex: string) {
  return [1, 3, 5].map(offset => Number.parseInt(hex.slice(offset, offset + 2), 16));
}

function turfPaint(color: string) {
  const turf = rgb(TURF);
  const channels = rgb(color).map((channel, i) => Math.round((channel + turf[i]) / 2));
  return '#' + channels.map(channel => channel.toString(16).padStart(2, '0')).join('');
}

function luminance(hex: string) {
  const [red, green, blue] = rgb(hex).map(channel => channel / 255)
    .map(channel => channel <= .04045 ? channel / 12.92 : ((channel + .055) / 1.055) ** 2.4);
  return .2126 * red + .7152 * green + .0722 * blue;
}

function contrast(first: number, second: number) {
  return (Math.max(first, second) + .05) / (Math.min(first, second) + .05);
}

function paintColors(primary: string) {
  const colors = teamColorManifest.ramps.find(ramp => ramp.id === primary)?.colors;
  const paint = colors ? turfPaint(colors[9]) : TURF;
  const base = luminance(paint);
  const ink = contrast(base, luminance(IVORY)) >= contrast(base, luminance(CHARCOAL)) ? IVORY : CHARCOAL;
  // Grain shifts away from the selected ink's luminance, preserving the base contrast.
  const grain = colors ? turfPaint(colors[ink === IVORY ? 8 : 10]) : ink === IVORY ? '#6f7841' : '#768046';
  return { paint, grain, ink };
}

function fieldRandom(seed: number) {
  return (limit: number) => {
    seed = (Math.imul(seed, 1664525) + 1013904223) >>> 0;
    return Math.floor(seed / 4294967296 * limit);
  };
}

// The gaps in each scuff leave individual blades of painted grass between the bare ones.
const scuffs = [
  ['    #  ', '  ##  #', '#### # ', ' #     '],
  ['      ## ', '  ###   #', '## ##    '],
  [' #  ', ' ###', '##  '],
  ['##    ', ' ### #', '   ## '],
];

function weatherGround(ctx: PaintTarget, row: PitchBounds, grain: string, seed: number) {
  const next = fieldRandom(seed);
  ctx.fillStyle = grain;
  for (let i = 0; i < Math.floor(row.width * row.height / 60); i++) {
    const width = 1 + next(Math.min(3, row.width));
    ctx.fillRect(row.x + next(row.width - width + 1), row.y + next(row.height), width, 1);
  }

  for (let i = 0; i < Math.floor(row.width * row.height / 1200); i++) {
    const scuff = scuffs[next(scuffs.length)];
    const width = scuff[0].length;
    if (row.width < width || row.height < scuff.length) continue;
    const left = row.x + next(row.width - width + 1);
    const top = row.y + next(row.height - scuff.length + 1);
    scuff.forEach((line, y) => {
      for (let x = 0; x < width; x++) {
        if (line[x] === '#') ctx.clearRect(left + x, top + y, 1, 1);
      }
    });
  }
}

function glyphWidth(glyph: Glyph) {
  return glyph[0].length || 2;
}

function wordWidth(word: readonly Glyph[]) {
  return word.reduce((width, glyph) => width + glyphWidth(glyph) + 1, 0) - 1;
}

function teamWord(name: string, width: number) {
  const text = name.normalize('NFKD').toUpperCase().replace(/\p{M}/gu, '')
    .replace(/[‘’]/g, "'").replace(/[‐‑‒–—]/g, '-').replace(/[^A-Z0-9 &'\-.]+/g, ' ')
    .trim().replace(/\s+/g, ' ');
  if (!/[A-Z0-9]/.test(text)) return [mark];
  const full = Array.from(text, letter => glyphs[letter]);
  if (wordWidth(full) <= width) return full;
  const initials = text.match(/[A-Z0-9][A-Z0-9'\-.]*/g)!.slice(0, 3).map(word => glyphs[word[0]]);
  return wordWidth(initials) <= width ? initials : [mark];
}

function drawWord(ctx: PaintTarget, row: PitchBounds, name: string, ink: string, seed: number) {
  const width = row.width - 2 * Math.min(16, Math.floor(row.width / 8));
  const height = row.height - 2 * Math.min(4, Math.floor(row.height / 8));
  const word = teamWord(name, width);
  const units = wordWidth(word);
  const scale = Math.floor(Math.min(width / units, height / GLYPH_HEIGHT));
  ctx.fillStyle = ink;
  if (!scale) {
    ctx.fillRect(row.x + Math.floor(row.width / 2), row.y + Math.floor(row.height / 2), 1, 1);
    return;
  }

  let left = row.x + Math.floor((row.width - units * scale) / 2);
  const top = row.y + Math.floor((row.height - GLYPH_HEIGHT * scale) / 2);
  const next = fieldRandom(seed);
  for (const glyph of word) {
    // Nick an outside edge while leaving the core of even a one-block stroke intact.
    const nickY = scale >= 3 && next(3) !== 0 ? next(GLYPH_HEIGHT) : -1;
    const nickLeft = next(2) === 0;
    const edge = glyph[nickY] ?? '';
    const nickX = nickLeft ? edge.indexOf('1') : edge.lastIndexOf('1');
    glyph.forEach((pixels, y) => {
      for (let x = 0; x < pixels.length; x++) {
        if (pixels[x] !== '1') continue;
        const px = left + x * scale, py = top + y * scale;
        if (x === nickX && y === nickY) {
          ctx.fillRect(px, py, scale, scale - 1);
          ctx.fillRect(px + (nickLeft ? 2 : 0), py + scale - 1, scale - 2, 1);
        } else {
          ctx.fillRect(px, py, scale, scale);
        }
      }
    });
    left += (glyphWidth(glyph) + 1) * scale;
  }
}

/** Paint on a separate layer so worn spots reveal the original grass beneath the markings. */
export function drawGatehouseEndZones(ctx: PaintTarget, pitch: PitchBounds, square: number,
  teams: { north: Team; south: Team }) {
  if (![pitch.x, pitch.y, pitch.width, pitch.height, square].every(Number.isInteger)
    || square <= 0 || pitch.width <= 0 || pitch.height < square * 2) return;

  const before = ctx.fillStyle;
  for (const [team, y, seed] of [[teams.north, pitch.y, 572], [teams.south, pitch.y + pitch.height - square, 573]] as const) {
    const row = { x: pitch.x, y, width: pitch.width, height: square };
    const { paint, grain, ink } = paintColors(team.primary);
    ctx.fillStyle = paint;
    ctx.fillRect(row.x, row.y, row.width, row.height);
    weatherGround(ctx, row, grain, seed);
    drawWord(ctx, row, team.name, ink, seed);
  }
  ctx.fillStyle = before;
}
