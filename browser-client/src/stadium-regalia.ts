import atlasUrl from '../../assets/game/references/stadiums/scale-studies/regalia/atlas.png';
import materialsUrl from '../../assets/game/references/stadiums/scale-studies/regalia/materials.png';
import atlas from '../../assets/game/references/stadiums/scale-studies/regalia/atlas.json';
import layout from '../../assets/game/references/stadiums/scale-studies/regalia/layout.json';
import { MOCKUP_COLOR_PRESETS, mockupColorPair, type MockupColorPreset } from './stadium-mockup.ts';
import { recolorTeamImage } from './team-color-art.ts';
import type { TeamColors } from './team-colors.ts';

const presetLabels: Record<MockupColorPreset, string> = {
  defaults: 'Navy/gold and red/gold',
  identical: 'Identical navy',
  'white / black': 'White and black',
  'yellow / purple': 'Yellow and purple',
  'similar blues': 'Similar blues',
};
export const regaliaPresets = MOCKUP_COLOR_PRESETS.map(id => ({ id, label: presetLabels[id] }));

const sprites: Record<string, { x: number; y: number; width: number; height: number }> = atlas.sprites;

export function regaliaBounds(bayId: number) {
  return layout.placements.filter(piece => piece.bayIds.includes(bayId)).map(({ x, y, sprite }) =>
    ({ x, y, width: sprites[sprite].width, height: sprites[sprite].height }));
}

function surface(width: number, height: number) {
  const canvas = document.createElement('canvas');
  canvas.width = width;
  canvas.height = height;
  return canvas.getContext('2d')!;
}

async function atlasPixels(url: string) {
  const image = new Image();
  image.src = url;
  await image.decode();
  const { width, height } = atlas;
  if (image.width !== width || image.height !== height) throw Error('Invalid Gatehouse regalia atlas dimensions');
  const context = surface(width, height);
  context.drawImage(image, 0, 0);
  return context.getImageData(0, 0, width, height).data;
}

export async function loadRegalia() {
  const [source, materials] = await Promise.all([atlasPixels(atlasUrl), atlasPixels(materialsUrl)]);
  const { width, height } = atlas;
  const rgba = new Uint8Array(source);
  const indices = new Uint8Array(width * height);
  for (let pixel = 0; pixel < indices.length; pixel++) {
    const offset = pixel * 4;
    const index = materials[offset];
    if (index > 32 || (index && !rgba[offset + 3])) throw Error('Invalid Gatehouse regalia material index');
    indices[pixel] = index;
  }
  const atlases = new Map<string, HTMLCanvasElement>();
  const overlay = surface(layout.canvas.width, layout.canvas.height);
  overlay.imageSmoothingEnabled = false;
  let activePreset: MockupColorPreset | undefined;

  function recolorAtlas(colors: TeamColors) {
    const key = colors.primary + '/' + colors.secondary;
    const existing = atlases.get(key);
    if (existing) return existing;
    const context = surface(width, height);
    const art = new ImageData(recolorTeamImage({ rgba, indices }, colors), width, height);
    context.putImageData(art, 0, 0);
    atlases.set(key, context.canvas);
    return context.canvas;
  }

  return (preset: MockupColorPreset) => {
    if (activePreset === preset) return overlay.canvas;
    const colors = mockupColorPair(preset);
    const sources = { home: recolorAtlas(colors.home), away: recolorAtlas(colors.away) };
    overlay.clearRect(0, 0, overlay.canvas.width, overlay.canvas.height);
    for (const { owner, sprite, x, y } of layout.placements) {
      if (owner !== 'home' && owner !== 'away') throw Error('Invalid Gatehouse regalia ownership');
      const cell = sprites[sprite];
      overlay.drawImage(sources[owner], cell.x, cell.y, cell.width, cell.height, x, y, cell.width, cell.height);
    }
    activePreset = preset;
    return overlay.canvas;
  };
}
