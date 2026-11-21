import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import architectureUrl from '../../assets/game/references/stadiums/scale-studies/empty-gatehouse.webp';
import seatingUrl from '../../assets/game/references/stadiums/scale-studies/empty-seating.png';
import seatingLayout from '../../assets/game/references/stadiums/scale-studies/empty-seating.json';
import benchLayout from '../../assets/game/references/stadiums/scale-studies/gatehouse-benches.json';
import artistReferenceUrl from '../../assets/game/references/stadiums/scale-studies/bay-six/cast-reference.webp';
import poseColumns from '../../assets/game/references/stadiums/scale-studies/bay-six/poses.json';
import seatingStudies from '../../assets/game/references/stadiums/scale-studies/bay-six/seats.json';
import castAnglesUrl from '../../assets/game/references/stadiums/scale-studies/end-stands/cast-angles.webp';
import endReferenceUrl from '../../assets/game/references/stadiums/scale-studies/end-stands/artist-reference.webp';
import endPoseGroups from '../../assets/game/references/stadiums/scale-studies/end-stands/pose-placements.json';
import fullEndPlacements from '../../assets/game/references/stadiums/scale-studies/end-stands/full-ends/placements.json';
import sidePlacements from '../../assets/game/references/stadiums/scale-studies/side-stands/placements.json';
import type { Point } from './pitch-projection.ts';
import { playerArtPlacement, resolvePlayerArt } from './player-art.ts';
import { bowl, drawBowlGround } from './stadium-bowl-study.ts';
import { MOCKUP_PLAYER_WIDTH_IN_SQUARES, STILL_LAYOUT, mockupCamera, mockupColorPair, mockupPlayers, type MockupColorPreset, type MockupPlayer } from './stadium-mockup.ts';
import { loadRegalia, regaliaBounds, regaliaPresets } from './stadium-regalia.ts';
import { teamColorArt } from './team-color-art.ts';
import type { TeamColors } from './team-colors.ts';
import './stadium-mockup.css';
import './stadium-scale-studies.css';

const bay = seatingLayout.bays.find(({ id }) => id === 6)!;
const square = STILL_LAYOUT.pixelsPerSquare;
const crop = { x: 0, y: bay.y - 17, width: bowl.pitchX + square + 6, height: bay.height + 38 };
const westFrames = { native: crop, detail: crop };
const studyImages = import.meta.glob<string>([
  '../../assets/game/references/stadiums/scale-studies/bay-six/*.png',
  '../../assets/game/references/stadiums/scale-studies/end-stands/*.png',
  '../../assets/game/references/stadiums/scale-studies/end-stands/full-ends/*.png',
  '../../assets/game/references/stadiums/scale-studies/side-stands/*.png',
], {
  eager: true, query: '?url', import: 'default',
});
const studyImageRoot = '../../assets/game/references/stadiums/scale-studies/bay-six/';
const endImageRoot = '../../assets/game/references/stadiums/scale-studies/end-stands/';
const sideImageRoot = '../../assets/game/references/stadiums/scale-studies/side-stands/';
const fullEndsLabel = 'North and south · Full stands';
const endViewLabel = 'North and south · 33%';
const endShowcaseLabel = 'North and south · Pose showcase';
const poseCellSize = 48;
const poseZoomSize = poseCellSize * 2;
const benchColors = [
  [202, 145, 254],
  [170, 235, 108],
  [255, 128, 104],
  [122, 159, 255],
  [232, 214, 196],
];
const benchAlpha = 135;
const maxPlanks = Math.max(...benchLayout.bays.map(({ planks }) => planks.length));
type SeatSlot = { slot: Point; plankId: number };
type Occupant = SeatSlot & Point & { knee?: Point; otherSole?: Point; feet?: readonly Point[] };
type Crop = Point & { width: number; height: number };
type CropFrames = { native: Crop; detail: Crop };
type EndPlacement = typeof endPoseGroups[number]['views'][number];
type BowlPlacement = (typeof fullEndPlacements | typeof sidePlacements)[number];
const fullPlan = seatingStudies[0].seats;
const bowlPlacements = [...fullEndPlacements, ...sidePlacements].sort((a, b) => a.bayId - b.bayId);
const standOccupancy = ['north', 'west', 'east', 'south'].map(stand => {
  const bays = bowlPlacements.filter(bay => bay.stand === stand);
  return {
    stand,
    label: stand === 'west' ? 'West (home)' : stand === 'east' ? 'East (away)' : standName(stand),
    occupied: bays.reduce((sum, bay) => sum + bay.seats.length, 0),
    capacity: bays.reduce((sum, bay) => sum + bay.capacity, 0),
  };
});

function standName(stand: string) {
  return stand[0].toUpperCase() + stand.slice(1);
}

function seatKey({ slot, plankId }: SeatSlot) {
  return `${plankId}:${slot.x},${slot.y}`;
}

const westPlayer = {
  x: bowl.pitchX + square / 2,
  y: bowl.pitchY + (Math.floor((bay.y + bay.height / 2 - bowl.pitchY) / square) + .5) * square,
};

function bowlCrop(left: number, top: number, right: number, bottom: number): Crop {
  const x = Math.max(0, left);
  const y = Math.max(0, top);
  return { x, y, width: Math.min(bowl.width, right) - x, height: Math.min(bowl.height, bottom) - y };
}

function endFrames(bounds: Crop, north: boolean) {
  const pitchBottom = bowl.pitchY + STILL_LAYOUT.pitch.height;
  const playerAt = { x: bowl.pitchX + square / 2, y: north ? bowl.pitchY + square / 2 : pitchBottom - square / 2 };
  const left = Math.min(bounds.x - 12, bowl.pitchX - 12);
  const right = Math.max(bounds.x + bounds.width, bowl.pitchX + square) + 12;
  const top = north ? bounds.y - 12 : pitchBottom - square - 8;
  const bottom = north ? bowl.pitchY + square + 8 : bounds.y + bounds.height + 12;
  return {
    playerAt,
    native: bowlCrop(left, top, right, bottom),
    detail: bowlCrop(bounds.x - 8, bounds.y - 8, bounds.x + bounds.width + 8, bounds.y + bounds.height + 8),
  };
}

function isSideStand(stand: string) {
  return stand === 'west' || stand === 'east';
}

function bowlBayFrames(bounds: typeof bay) {
  const row = seatingLayout.bays.filter(({ stand }) => stand === bounds.stand);
  const index = row.findIndex(({ id }) => id === bounds.id);
  const before = row[index - 1];
  const after = row[index + 1];
  const rightEdge = bounds.x + bounds.width;
  const bottomEdge = bounds.y + bounds.height;
  // Include the adjacent stair or gate approach up to the center of the gap.
  const frame = isSideStand(bounds.stand) ? bowlCrop(
    bounds.x - 20,
    before ? Math.floor((before.y + before.height + bounds.y) / 2) : bounds.y - 24,
    rightEdge + 20,
    after ? Math.ceil((bottomEdge + after.y) / 2) : bottomEdge + 24,
  ) : bowlCrop(
    before ? Math.floor((before.x + before.width + bounds.x) / 2) : bounds.x - 32,
    bounds.y - 24,
    after ? Math.ceil((rightEdge + after.x) / 2) : rightEdge + 32,
    bottomEdge + 24,
  );
  return { native: frame, detail: frame };
}

function bayLabel({ stand, bayId }: BowlPlacement) {
  return standName(stand) + ' bay ' + bayId;
}

async function image(url: string, dimensions: { width: number; height: number }) {
  const result = new Image();
  result.src = url;
  await result.decode();
  if (result.width !== dimensions.width || result.height !== dimensions.height) {
    throw Error('Invalid Gatehouse layer dimensions');
  }
  return result;
}

function drawingSurface() {
  const canvas = document.createElement('canvas');
  canvas.width = bowl.width;
  canvas.height = bowl.height;
  return canvas;
}

function buildBenchOverlay(seating: ImageData) {
  const overlay = drawingSurface();
  const context = overlay.getContext('2d')!;
  const pixels = context.createImageData(bowl.width, bowl.height);
  for (const { id, planks } of benchLayout.bays) {
    const bounds = seatingLayout.bays.find(bay => bay.id === id)!;
    for (const plank of planks) {
      const color = [...benchColors[(plank.id % 10) - 1], benchAlpha];
      for (const [left, top, width, height] of plank.rects) {
        for (let y = Math.max(0, top); y < Math.min(bounds.height, top + height); y++) {
          for (let x = Math.max(0, left); x < Math.min(bounds.width, left + width); x++) {
            const pixel = ((bounds.y + y) * bowl.width + bounds.x + x) * 4;
            if (seating.data[pixel] === id) pixels.data.set(color, pixel);
          }
        }
      }
    }
  }
  context.putImageData(pixels, 0, 0);
  return overlay;
}

async function loadScalePlayer(player: MockupPlayer, colors?: TeamColors) {
  const body = resolvePlayerArt(player, { end: 'home', topDown: true, colors })!.body;
  const art = await image(body.url, body);
  const anchor = playerArtPlacement(body, true).anchor;
  const scale = square * MOCKUP_PLAYER_WIDTH_IN_SQUARES / 64;
  return {
    art,
    offset: { x: anchor.x * scale, y: anchor.y * scale },
    width: Math.round(body.width * scale),
    height: Math.round(body.height * scale),
  };
}

async function loadKickoff() {
  const release = teamColorArt.retain();
  try {
    await Promise.all([teamColorArt.load('human'), teamColorArt.load('orc')]);
    const players = mockupPlayers('orc');
    const kits = mockupColorPair('defaults');
    const [home, away] = await Promise.all([
      loadScalePlayer(players.find(({ role }) => role === 'home')!, kits.home),
      loadScalePlayer(players.find(({ role }) => role === 'away')!, kits.away),
    ]);
    const camera = mockupCamera('home');
    return players.map(player => {
      const position = camera.project({ x: player.x + .5, y: player.y + .5 })!;
      return {
        sprite: player.role === 'home' ? home : away,
        at: {
          x: bowl.pitchX + STILL_LAYOUT.pitch.width / 2 + position.x - camera.center.x,
          y: bowl.pitchY + STILL_LAYOUT.pitch.height / 2 + position.y - camera.center.y,
        },
      };
    });
  } finally {
    release();
  }
}

function drawScalePlayer(context: CanvasRenderingContext2D, player: Awaited<ReturnType<typeof loadScalePlayer>>, at: Point) {
  context.drawImage(player.art, Math.round(at.x - player.offset.x), Math.round(at.y - player.offset.y), player.width, player.height);
}

async function loadEndSection(study: EndPlacement) {
  const bounds = seatingLayout.bays.find(({ id }) => id === study.bayId)!;
  const [crowd, rail] = await Promise.all([
    image(studyImages[endImageRoot + study.crowd], bounds),
    image(studyImages[endImageRoot + study.rail], bounds),
  ]);
  return { ...study, bounds, frames: endFrames(bounds, study.id === 'north'), crowd, rail };
}

async function loadBowlSection(study: BowlPlacement) {
  const bounds = seatingLayout.bays.find(({ id }) => id === study.bayId)!;
  const root = isSideStand(study.stand) ? sideImageRoot : endImageRoot + 'full-ends/';
  const crowd = await image(studyImages[root + study.crowd], bounds);
  const frames = bowlBayFrames(bounds);
  const regalia = regaliaBounds(study.bayId);
  const expanded = regalia.length ? bowlCrop(
    Math.min(frames.native.x, ...regalia.map(piece => piece.x)),
    Math.min(frames.native.y, ...regalia.map(piece => piece.y)),
    Math.max(frames.native.x + frames.native.width, ...regalia.map(piece => piece.x + piece.width)),
    Math.max(frames.native.y + frames.native.height, ...regalia.map(piece => piece.y + piece.height)),
  ) : frames.native;
  return { ...study, bounds, frames, decoratedFrames: { native: expanded, detail: expanded }, crowd };
}

async function loadLayers() {
  const [architecture, seating, player, atlas, studies, showcases, fullBowl, kickoff, regalia] = await Promise.all([
    image(architectureUrl, bowl),
    image(seatingUrl, bowl),
    loadScalePlayer(mockupPlayers('human')[0]),
    image(studyImages[studyImageRoot + 'supporter-kits.png'],
      { width: poseColumns.length * poseCellSize, height: poseCellSize * 2 }),
    Promise.all(seatingStudies.map(async study => {
      const [crowd, rail] = await Promise.all([
        image(studyImages[studyImageRoot + study.id + '.png'], bay),
        image(studyImages[studyImageRoot + study.id + '-rail.png'], bay),
      ]);
      return { ...study, crowd, rail };
    })),
    Promise.all(endPoseGroups.map(async group => {
      const views = await Promise.all(group.views.map(loadEndSection));
      return { id: group.id, views };
    })),
    Promise.all(bowlPlacements.map(loadBowlSection)),
    loadKickoff(),
    loadRegalia(),
  ]);
  const stadium = drawingSurface();
  const baseContext = stadium.getContext('2d')!;
  drawBowlGround(baseContext, true);
  baseContext.drawImage(architecture, 0, 0);

  const overlay = drawingSurface();
  const context = overlay.getContext('2d')!;
  context.drawImage(seating, 0, 0);
  const pixels = context.getImageData(0, 0, bowl.width, bowl.height);
  const benches = buildBenchOverlay(pixels);
  for (let i = 0; i < pixels.data.length; i += 4) {
    const bay = pixels.data[i];
    const away = bay >= 8 && bay <= 11;
    pixels.data.set(away ? [235, 132, 203, 145] : [64, 214, 195, 145], i);
    if (!bay) pixels.data[i+3] = 0;
  }
  context.putImageData(pixels, 0, 0);
  const fullEnds = fullBowl.filter(({ stand }) => !isSideStand(stand));
  return { stadium, overlay, benches, player, atlas, studies, showcases, fullBowl, fullEnds, kickoff, regalia };
}

function drawSeatMarkers(context: CanvasRenderingContext2D, bounds: Point, slots: readonly SeatSlot[], seats: readonly Occupant[]) {
  const occupied = new Set(seats.map(seatKey));
  context.strokeStyle = '#d4cdc1';
  for (const seat of slots) {
    const { x, y } = seat.slot;
    if (!occupied.has(seatKey(seat))) context.strokeRect(bounds.x + x - .5, bounds.y + y - .5, 2, 2);
  }
  const toBowl = ({ x, y }: Point) => ({ x: bounds.x + x, y: bounds.y + y });
  const knees = seats.flatMap(({ knee }) => knee ? [toBowl(knee)] : []);
  const contacts = seats.flatMap(seat => seat.feet ?? (seat.otherSole ? [seat, seat.otherSole] : [seat])).map(toBowl);
  // Draw every outline before marker colors, so a nearby outline cannot hide a knee.
  context.fillStyle = '#101820';
  for (const { x, y } of knees) {
    context.fillRect(x - 1, y - 1, 3, 3);
  }
  for (const { x, y } of contacts) {
    context.fillRect(x - 2, y - 1, 5, 3);
    context.fillRect(x - 1, y - 2, 3, 5);
  }
  context.fillStyle = '#ffe17b';
  for (const { x, y } of knees) context.fillRect(x, y, 1, 1);
  context.fillStyle = '#80f5ff';
  for (const { x, y } of contacts) {
    context.fillRect(x - 1, y, 3, 1);
    context.fillRect(x, y - 1, 1, 3);
  }
}

function drawCrop(target: HTMLCanvasElement | null, source: HTMLCanvasElement, frame: Crop) {
  const context = target?.getContext('2d');
  if (!target || !context) return;
  context.imageSmoothingEnabled = false;
  context.drawImage(source, frame.x, frame.y, frame.width, frame.height, 0, 0, target.width, target.height);
}

function drawReviewCrops(review: HTMLElement | null, source: HTMLCanvasElement, id: string, frames: CropFrames) {
  if (!review) return;
  drawCrop(review.querySelector<HTMLCanvasElement>(`#${id}-native-canvas`), source, frames.native);
  drawCrop(review.querySelector<HTMLCanvasElement>(`#${id}-zoom-canvas`), source, frames.detail);
}

function ReviewCrops({ id, frames }: { id: string; frames: CropFrames }) {
  return <div className="bay-six-crops">
    <figure>
      <figcaption id={id + '-native'}>Native size · 1×</figcaption>
      <canvas id={id + '-native-canvas'} width={frames.native.width} height={frames.native.height} aria-labelledby={id + '-native'}/>
    </figure>
    <figure>
      <figcaption id={id + '-zoom'}>Pixel zoom · 4×</figcaption>
      <div className="bay-six-zoom" tabIndex={0} role="region" aria-labelledby={id + '-zoom'}>
        <canvas id={id + '-zoom-canvas'} width={frames.detail.width * 4} height={frames.detail.height * 4}
          aria-labelledby={id + '-zoom'}/>
      </div>
    </figure>
  </div>;
}

function SupporterPose({ atlas, pose, neutral }: { atlas: HTMLImageElement; pose: string; neutral?: boolean }) {
  const canvas = useRef<HTMLCanvasElement>(null);
  const column = poseColumns.findIndex(({ id }) => id === pose);
  const { label, wardrobe } = poseColumns[column];
  const clothing = wardrobe === 'neutral' ? 'Fixed clothing'
    : neutral === undefined ? 'Team colors' : neutral ? 'Neutral kit' : 'Home kit';
  const row = neutral ? poseCellSize : 0;
  useLayoutEffect(() => {
    const context = canvas.current?.getContext('2d');
    if (!context) return;
    context.imageSmoothingEnabled = false;
    context.drawImage(atlas, column * poseCellSize, row, poseCellSize, poseCellSize,
      0, 0, poseZoomSize, poseZoomSize);
  }, [atlas, column, row]);

  return <li>
    <figure>
      <canvas ref={canvas} width={poseZoomSize} height={poseZoomSize} aria-hidden="true"/>
      <figcaption>{label}{' '}
        <span>{pose.startsWith('standing-') ? 'Standing' : 'Seated'} · {clothing}</span>
      </figcaption>
    </figure>
  </li>;
}

export function StadiumScaleStudies() {
  const endQuery = new URLSearchParams(window.location.search);
  const [layers, setLayers] = useState<Awaited<ReturnType<typeof loadLayers>>>();
  const [failed, setFailed] = useState(false);
  const [view, setView] = useState(() => {
    const requested = endQuery.get('view');
    if (requested === 'scale') return 'b';
    return requested === 'ends' || requested === 'end-poses' || requested === 'full-ends' ? requested : 'full-bowl';
  });
  const [endGroup, setEndGroup] = useState(() =>
    endPoseGroups.find(({ id }) => id === endQuery.get('group'))?.id ?? endPoseGroups[0].id);
  const [selectedBay, setSelectedBay] = useState(() =>
    bowlPlacements.find(({ bayId }) => String(bayId) === endQuery.get('bay'))?.bayId);
  const [benches, setBenches] = useState(false);
  const [rail, setRail] = useState(true);
  const [markers, setMarkers] = useState(false);
  const [regalia, setRegalia] = useState(true);
  const [regaliaPreset, setRegaliaPreset] = useState<MockupColorPreset>('defaults');
  const canvas = useRef<HTMLCanvasElement>(null);
  const review = useRef<HTMLElement>(null);
  const study = seatingStudies.find(({ id }) => id === view);
  const standing = study?.seats.filter(({ stance }) => stance === 'standing').length ?? 0;
  const spacing = study?.spacing;
  const contactSpacing = spacing && (spacing.min === spacing.max ? spacing.min : `${spacing.min}–${spacing.max}`);
  const masked = view === 'masks';
  const fullEndsView = view === 'full-ends';
  const fullBowlView = view === 'full-bowl';
  const kickoffView = fullBowlView || fullEndsView;
  const detailId = fullEndsView ? 'full-ends-detail' : 'bowl-detail';
  const cropId = fullEndsView ? 'full-end-bay' : 'bowl-bay';
  const showcaseView = view === 'end-poses';
  const endView = view === 'ends' || showcaseView;
  const displayedEnds = layers?.showcases.find(({ id }) => id === (showcaseView ? endGroup : 'approved'))?.views;
  const placements = fullEndsView ? fullEndPlacements : bowlPlacements;
  const bayId = placements.some(({ bayId }) => bayId === selectedBay) ? selectedBay : placements[0].bayId;
  const kickoffBays = fullEndsView ? layers?.fullEnds : layers?.fullBowl;
  const inspectedBay = kickoffBays?.find(bay => bay.bayId === bayId);
  const inspectedFrames = fullBowlView ? inspectedBay?.decoratedFrames : inspectedBay?.frames;
  const markerToggle = <label><input type="checkbox" checked={markers} onChange={event => setMarkers(event.target.checked)}
    aria-describedby={markers ? 'gatehouse-marker-key' : undefined}/>Seat markers</label>;
  const controls = <div className="mockup-controls">
    <label><input type="checkbox" checked={rail} onChange={event => setRail(event.target.checked)}/>Front rail</label>
    {markerToggle}
  </div>;
  const markerKey = markers && <p className="bay-six-marker-key" id="gatehouse-marker-key">
    <span>Cyan crosses</span> mark hip or boot contact. <span>Gold dots</span> mark seated knees.
    {(kickoffView || endView || (study && study.seats.length < study.capacity)) && <> <span>Grey squares</span> mark vacant seats.</>}
  </p>;

  useEffect(() => {
    let active = true;
    document.title = 'Gatehouse stadium';
    loadLayers().then(value => { if (active) setLayers(value); }, () => { if (active) setFailed(true); });
    return () => { active = false; };
  }, []);
  useEffect(() => {
    if (layers && kickoffView && window.location.hash === '#' + detailId) review.current?.scrollIntoView();
  }, [layers, kickoffView, detailId]);
  useLayoutEffect(() => {
    const full = canvas.current;
    const context = full?.getContext('2d');
    if (!full || !context || !layers) return;
    context.imageSmoothingEnabled = false;
    context.drawImage(layers.stadium, 0, 0);
    if (masked) context.drawImage(layers.overlay, 0, 0);
    if (benches) context.drawImage(layers.benches, 0, 0);
    if (kickoffView && kickoffBays) {
      for (const { bounds, crowd } of kickoffBays) context.drawImage(crowd, bounds.x, bounds.y);
      if (fullBowlView && regalia) context.drawImage(layers.regalia(regaliaPreset), 0, 0);
      for (const { sprite, at } of layers.kickoff) drawScalePlayer(context, sprite, at);
      if (markers) {
        for (const { bounds, slots, seats } of kickoffBays) drawSeatMarkers(context, bounds, slots, seats);
      }
      if (inspectedFrames) drawReviewCrops(review.current, full, cropId, inspectedFrames);
    }
    const variant = layers.studies.find(({ id }) => id === view);
    if (variant) {
      const { player } = layers;
      context.drawImage(variant.crowd, bay.x, bay.y);
      if (rail) context.drawImage(variant.rail, bay.x, bay.y);
      drawScalePlayer(context, player, westPlayer);
      if (markers) drawSeatMarkers(context, bay, fullPlan, variant.seats);
      drawReviewCrops(review.current, full, 'bay-six', westFrames);
    }
    if (endView && displayedEnds) {
      for (const end of displayedEnds) {
        const { bounds } = end;
        context.drawImage(end.crowd, bounds.x, bounds.y);
        if (rail) context.drawImage(end.rail, bounds.x, bounds.y);
        drawScalePlayer(context, layers.player, end.frames.playerAt);
        if (markers) drawSeatMarkers(context, bounds, end.slots, end.seats);
        drawReviewCrops(review.current, full, 'end-' + end.id, end.frames);
      }
    }
  }, [layers, view, masked, kickoffView, kickoffBays, cropId, endView, displayedEnds, inspectedFrames,
    benches, rail, markers, fullBowlView, regalia, regaliaPreset]);

  const overview = <div className={'scale-study-crop gatehouse-overview' + (kickoffView ? ' kickoff-overview' : '')}
    data-full-bowl={fullBowlView}>
    <canvas ref={canvas} width={bowl.width} height={bowl.height} data-ready={Boolean(layers)}
      aria-label={fullBowlView ? 'Gatehouse kickoff with 22 players and crowds in all four stands'
        : fullEndsView ? 'Gatehouse kickoff with 22 players and north and south crowds'
          : study ? 'Gatehouse stadium with ' + study.label + ' in west bay 6'
            : endView ? 'Gatehouse stadium with north and south supporters'
              : masked ? 'Gatehouse seating masks' : 'Empty Gatehouse stadium'}/>
  </div>;

  return <main className="stadium-mockup scale-studies gatehouse-bay-gallery">
    <header className="mockup-heading">
      <h1>Gatehouse stadium</h1>
      <div className="mockup-controls">
        <label>Gallery view
          <select value={view} onChange={event => setView(event.target.value)}>
            <option value="full-bowl">Full stadium · Kickoff</option>
            <option value="full-ends">{fullEndsLabel}</option>
            {seatingStudies.map(({ id, label }) => <option key={id} value={id}>{label}</option>)}
            <option value="ends">{endViewLabel}</option>
            <option value="end-poses">{endShowcaseLabel}</option>
            <option value="empty">Empty stadium</option>
            <option value="masks">Show seating masks</option>
          </select>
        </label>
        <label className="gatehouse-bench-toggle">
          <input type="checkbox" checked={benches} onChange={event => setBenches(event.target.checked)}
            aria-describedby={benches ? 'gatehouse-bench-key' : undefined}/>Bench tops
        </label>
      </div>
    </header>
    {masked && <p>Teal marks home seating. Pink marks the east away stand.</p>}
    {benches && <ul className="gatehouse-bench-key" id="gatehouse-bench-key" role="list" aria-label="Bench top key">
      {benchColors.slice(0, maxPlanks).map((color, index) => <li key={index}>
        <span aria-hidden="true" style={{ backgroundColor: `rgb(${color.join(' ')})` }}/>Plank {index + 1}
      </li>)}
    </ul>}
    {failed && <p role="alert">The stadium artwork could not be loaded.</p>}
    {!failed && !layers && <p role="status">Loading stadium artwork…</p>}
    {kickoffView && <section className="bay-six-review" aria-labelledby="kickoff-heading">
      <h2 id="kickoff-heading">{fullBowlView ? 'Full stadium at kickoff' : 'North and south stands at kickoff'}</h2>
      <div className="bay-six-toolbar kickoff-toolbar">
        {standOccupancy.filter(({ stand }) => fullBowlView || !isSideStand(stand)).map(({ stand, label, occupied, capacity }) =>
          <p key={stand}>{label} <strong>{occupied} of {capacity} occupied</strong></p>)}
        <div className="mockup-controls kickoff-review-controls">
          {markerToggle}
          {fullBowlView && <>
            <label><input type="checkbox" checked={regalia} onChange={event => setRegalia(event.target.checked)}/>Regalia</label>
            <label>Regalia colors
              <select value={regaliaPreset} disabled={!regalia} onChange={event => {
                const preset = regaliaPresets.find(preset => preset.id === event.target.value);
                if (preset) setRegaliaPreset(preset.id);
              }}>
                {regaliaPresets.map(({ id, label }) => <option key={id} value={id}>{label}</option>)}
              </select>
            </label>
          </>}
          <a className="end-stand-reference" href={'#' + detailId}>Inspect a bay</a>
        </div>
      </div>
      {markerKey}
      {overview}
      <section ref={review} className="gatehouse-bay-detail" id={detailId} aria-labelledby="bay-detail-heading"
        data-side={inspectedBay ? isSideStand(inspectedBay.stand) : false}>
        <h3 id="bay-detail-heading">Bay details</h3>
        <div className="bay-six-toolbar">
          <div className="mockup-controls">
            <label>{fullEndsView ? 'End-stand bay' : 'Seating bay'}
              <select value={bayId} onChange={event => setSelectedBay(Number(event.target.value))}>
                {placements.map(placement => <option key={placement.bayId} value={placement.bayId}>
                  {bayLabel(placement)}
                </option>)}
              </select>
            </label>
          </div>
          {inspectedBay && <p><strong>{inspectedBay.seats.length} of {inspectedBay.capacity} occupied</strong></p>}
          {fullBowlView && <a className="end-stand-reference" href={`?view=full-bowl&bay=${bayId}#${detailId}`}>Link to this bay</a>}
        </div>
        {inspectedFrames && <ReviewCrops key={bayId} id={cropId} frames={inspectedFrames}/>}
      </section>
    </section>}
    {study && <section ref={review} className="bay-six-review" aria-labelledby="bay-six-heading">
      <h2 id="bay-six-heading">West bay 6</h2>
      <div className="bay-six-toolbar">
        <p>{study.label} · <strong>{study.seats.length} of {study.capacity} occupied</strong>
          {spacing && ` · ${contactSpacing} px spacing along planks`}
          {standing > 0 && ` · ${study.seats.length - standing} seated, ${standing} standing`}</p>
        {controls}
      </div>
      {markerKey}
      {layers && <ReviewCrops id="bay-six" frames={westFrames}/>}
      {layers && <details className="bay-six-poses" key={study.id}>
        <summary>Inspect full poses</summary>
        <ol role="list" aria-label="Supporters in bay order">
          {study.seats.map(seat => <SupporterPose key={seatKey(seat)} atlas={layers.atlas} pose={seat.pose} neutral={seat.neutral}/>)}
        </ol>
      </details>}
      {layers && <details className="bay-six-poses bay-six-roster">
        <summary>Inspect all {poseColumns.length} poses</summary>
        <ol role="list" aria-label="Full supporter roster">
          {poseColumns.map(({ id }) => <SupporterPose key={id} atlas={layers.atlas} pose={id}/>)}
        </ol>
        <a href={artistReferenceUrl} target="_blank" rel="noreferrer">Artist reference</a>
      </details>}
    </section>}
    {endView && <section ref={review} className="bay-six-review" aria-labelledby="end-stands-heading">
      <h2 id="end-stands-heading">{showcaseView ? 'North and south pose showcase' : 'North and south stands'}</h2>
      <div className="bay-six-toolbar">
        {showcaseView ? <div className="mockup-controls">
          <label>Pose group
            <select value={endGroup} onChange={event => setEndGroup(event.target.value)}>
              {endPoseGroups.map(({ id, label }) => <option key={id} value={id}>{label}</option>)}
            </select>
          </label>
        </div> : displayedEnds && <p><strong>{displayedEnds[0].seats.length} of {displayedEnds[0].capacity} occupied per bay</strong></p>}
        {controls}
        <a className="end-stand-reference" href={showcaseView ? castAnglesUrl : endReferenceUrl} target="_blank" rel="noreferrer">
          {showcaseView ? 'View all 12 pose pairs' : 'View six full poses'}
        </a>
      </div>
      {markerKey}
      {displayedEnds?.map(end => <section key={end.id} className="end-stand-review" aria-labelledby={'end-' + end.id + '-heading'}>
        <h3 id={'end-' + end.id + '-heading'}>{end.label}</h3>
        <ReviewCrops id={'end-' + end.id} frames={end.frames}/>
      </section>)}
    </section>}
    {!kickoffView && overview}
  </main>;
}
