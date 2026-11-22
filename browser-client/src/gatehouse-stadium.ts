import type { PitchEnd, PlaneMatrix } from './pitch-projection.ts';
import { drawBowlGround, drawBowlMarkings } from './stadium-bowl-study.ts';
import { drawGatehouseEndZone } from './stadium-end-zones.ts';
import { recolorTeamImage } from './team-color-art.ts';
import type { TeamColors } from './team-colors.ts';

export type GatehouseTeams = Record<PitchEnd, { name?: string | null; colors: TeamColors }>;

type Size = { width: number; height: number };
type Rect = Size & { x: number; y: number };
type MaterialImage = { rgba: Uint8Array; indices: Uint8Array };
type Layer = { kind: 'ground'; rotate180?: boolean } | ({ kind: 'image'; art: string; rotate180?: boolean } & Rect);
type Alignment = {
  pitchOrigin: [number, number]; worldOrigin: [number, number];
  worldRight: [number, number]; worldDown: [number, number]; pixelsPerSquare: number;
};
type View = {
  alignment: Alignment; layers: string; crowd: string; regalia: string; kitByBay: Record<number, PitchEnd>;
  endZones: (Rect & { team: PitchEnd; seed: number })[];
};
type Manifest = {
  version: number; canvas: Size; views: Record<PitchEnd, View>; layers: Record<string, Layer[]>;
  crowds: Record<string, { art: string; materials: string; bays: (Rect & { id: number; stand: string })[] }>;
  regaliaAtlases: Record<string, Size & { art: string; materials: string; sprites: Record<string, Rect> }>;
  regaliaSets: Record<string, { atlas: string; pieces: { x: number; y: number; sprite: string; team: PitchEnd }[] }>;
};
type Options = {
  crowd?: 'all' | 'ends' | 'none'; crowdColors?: Record<PitchEnd, TeamColors>;
  regalia?: boolean; endZones?: boolean; markings?: boolean;
};
type Composition = { canvas: HTMLCanvasElement; alignment: PlaneMatrix; composeMs: number };

const root = () => import.meta.env.BASE_URL + 'assets/game/pitch/stadiums/gatehouse/';

let manifestLoad: Promise<Manifest> | undefined;

function loadManifest() {
  manifestLoad ??= fetch(root() + 'views.json').then(async response => {
    if (!response.ok) throw Error('Gatehouse view manifest unavailable');
    const manifest = await response.json() as Manifest;
    if (manifest.version !== 1) throw Error('Unsupported Gatehouse view manifest');
    return manifest;
  }).catch(error => {
    manifestLoad = undefined;
    throw error;
  });
  return manifestLoad;
}

const images = new Map<string, Promise<HTMLImageElement>>();

function loadImage(file: string) {
  let pending = images.get(file);
  if (!pending) {
    const image = new Image();
    image.src = root() + file;
    pending = image.decode().then(() => image).catch(error => {
      images.delete(file);
      throw error;
    });
    images.set(file, pending);
  }
  return pending;
}

function surface({ width, height }: Size, readback = false) {
  const canvas = document.createElement('canvas');
  canvas.width = width;
  canvas.height = height;
  const context = canvas.getContext('2d', { willReadFrequently: readback });
  if (!context) throw Error('Gatehouse canvas unavailable');
  context.imageSmoothingEnabled = false;
  return context;
}

function readAtlas(image: HTMLImageElement, size: Size) {
  if (image.naturalWidth !== size.width || image.naturalHeight !== size.height) {
    throw Error('Invalid Gatehouse atlas dimensions');
  }
  const context = surface(size, true);
  context.drawImage(image, 0, 0);
  return context;
}

function readMaterials(art: CanvasRenderingContext2D, mask: CanvasRenderingContext2D, rect: Rect): MaterialImage {
  const { x, y, width, height } = rect;
  const rgba = new Uint8Array(art.getImageData(x, y, width, height).data);
  const maskRgba = mask.getImageData(x, y, width, height).data;
  const indices = new Uint8Array(width * height);
  for (let i = 0; i < indices.length; i++) {
    const pos = i * 4;
    const index = maskRgba[pos];
    if (index > 32 || index !== maskRgba[pos + 1] || index !== maskRgba[pos + 2]
      || maskRgba[pos + 3] !== 255 || (index && rgba[pos + 3] !== 255)) {
      throw Error('Invalid Gatehouse material index');
    }
    indices[i] = index;
  }
  return { rgba, indices };
}

function recolor(source: MaterialImage, size: Size, colors: TeamColors) {
  const context = surface(size);
  context.putImageData(new ImageData(recolorTeamImage(source, colors), size.width, size.height), 0, 0);
  return context.canvas;
}

function drawLayer(context: CanvasRenderingContext2D, layer: Layer, image: HTMLImageElement | undefined, markings: boolean) {
  context.save();
  try {
    const bounds = layer.kind === 'ground' ? { x: 0, y: 0, width: context.canvas.width, height: context.canvas.height } : layer;
    if (layer.rotate180) {
      context.translate(bounds.x * 2 + bounds.width, bounds.y * 2 + bounds.height);
      context.scale(-1, -1);
    }
    if (layer.kind === 'ground') drawBowlGround(context, true, markings);
    else context.drawImage(image!, bounds.x, bounds.y, bounds.width, bounds.height);
  } finally {
    context.restore();
  }
}

export function gatehouseAlignment(alignment: Alignment): PlaneMatrix {
  const [left, top] = alignment.pitchOrigin;
  const axes = alignment.worldOrigin.map((origin, axis) => {
    const u = alignment.worldRight[axis] / alignment.pixelsPerSquare;
    const v = alignment.worldDown[axis] / alignment.pixelsPerSquare;
    return [u, v, origin - u * left - v * top];
  });
  return [axes[0], axes[1], [0, 0, 1]];
}

export async function loadGatehouseStadium(end: PitchEnd, teams: GatehouseTeams, options: Options = {}): Promise<Composition> {
  const manifest = await loadManifest();
  const view = manifest.views[end];
  const layers = manifest.layers[view.layers];
  const crowd = options.crowd === 'none' ? undefined : manifest.crowds[view.crowd];
  const regalia = options.regalia === false ? undefined : manifest.regaliaSets[view.regalia];
  const atlas = regalia && manifest.regaliaAtlases[regalia.atlas];
  const [background, crowdImages, regaliaImages] = await Promise.all([
    Promise.all(layers.map(layer => layer.kind === 'image' ? loadImage(layer.art) : undefined)),
    crowd && Promise.all([loadImage(crowd.art), loadImage(crowd.materials)]),
    atlas && Promise.all([loadImage(atlas.art), loadImage(atlas.materials)]),
  ]);
  const start = performance.now();
  const context = surface(manifest.canvas);
  const markings = options.markings !== false;
  layers.forEach((layer, i) => drawLayer(context, layer, background[i], markings));

  if (options.endZones !== false) {
    for (const zone of view.endZones) {
      const paint = surface(zone);
      paint.translate(-zone.x, -zone.y);
      const team = teams[zone.team];
      drawGatehouseEndZone(paint, zone, { name: team.name?.trim() || null, primary: team.colors.primary }, zone.seed);
      if (markings) drawBowlMarkings(paint);
      context.drawImage(paint.canvas, zone.x, zone.y);
    }
  }
  if (crowd && crowdImages) {
    const art = readAtlas(crowdImages[0], manifest.canvas);
    const mask = readAtlas(crowdImages[1], manifest.canvas);
    for (const bay of crowd.bays) {
      if (options.crowd === 'ends' && (bay.stand === 'east' || bay.stand === 'west')) continue;
      const team = view.kitByBay[bay.id];
      const colors = options.crowdColors?.[team] ?? teams[team].colors;
      context.drawImage(recolor(readMaterials(art, mask, bay), bay, colors), bay.x, bay.y);
    }
  }
  if (regalia && atlas && regaliaImages) {
    const art = readAtlas(regaliaImages[0], atlas);
    const mask = readAtlas(regaliaImages[1], atlas);
    const material = readMaterials(art, mask, { ...atlas, x: 0, y: 0 });
    const kits = { home: recolor(material, atlas, teams.home.colors), away: recolor(material, atlas, teams.away.colors) };
    for (const { x, y, sprite, team } of regalia.pieces) {
      const cell = atlas.sprites[sprite];
      context.drawImage(kits[team], cell.x, cell.y, cell.width, cell.height, x, y, cell.width, cell.height);
    }
  }
  return { canvas: context.canvas, alignment: gatehouseAlignment(view.alignment), composeMs: performance.now() - start };
}

export function gatehouseTeamKey({ home, away }: GatehouseTeams) {
  return JSON.stringify([home, away].map(({ name, colors }) => [name ?? '', colors.version, colors.primary, colors.secondary]));
}

/** Owned by the match view, with at most one composition promise per end. */
export class GatehouseCompositionCache {
  private key?: string;
  private entries = new Map<PitchEnd, Promise<Composition>>();
  private readonly compose: typeof loadGatehouseStadium;

  constructor(compose = loadGatehouseStadium) {
    this.compose = compose;
  }

  load(end: PitchEnd, teams: GatehouseTeams) {
    const key = gatehouseTeamKey(teams);
    if (key !== this.key) {
      this.key = key;
      this.entries.clear();
    }
    let pending = this.entries.get(end);
    if (!pending) {
      pending = this.compose(end, teams).catch(error => {
        if (this.entries.get(end) === pending) this.entries.delete(end);
        throw error;
      });
      this.entries.set(end, pending);
    }
    return pending;
  }
}
