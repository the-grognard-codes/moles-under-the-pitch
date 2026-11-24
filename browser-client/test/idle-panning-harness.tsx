import { createRoot } from 'react-dom/client';
import { useEffect, useState, type ComponentType, type ReactNode } from 'react';
import { LivePitch } from '../src/LivePitch.tsx';
import { decodeSetupStateValue } from '../src/setup-protocol.ts';
import { GameView } from '../src/GameView.tsx';
import { PitchProjection } from '../src/pitch-projection.ts';
import { PlayerHoverCard } from '../src/PlayerHoverCard.tsx';
import initial from './fixtures/m5a-blitz-projections.json';
import '../../site/src/assets/site.css';
import '../src/play-brand.css';

// Optional integration with the diagnostics feature when present in the checkout.
// The panning regression also runs independently on a checkout without it.
type DiagnosticsModule = { DiagnosticsBoundary: ComponentType<{ environment: string; children: ReactNode }>;
  useDiagnostics: () => { service: { setMode: (mode: 'off' | 'counters' | 'graph') => void;
    preferences: { freezeOnThreshold: boolean; manual: boolean } } } | null };
const diagnostics = Object.values(import.meta.glob('../src/PerformanceDiagnostics.tsx', { eager: true }))[0] as DiagnosticsModule | undefined;
const requestedMode = new URLSearchParams(location.search).get('mode');
const mode = requestedMode === 'counters' || requestedMode === 'graph' ? requestedMode : 'off';
// Isolated real renderer: no socket, server decisions, gameplay mutations or polling.
const seed = initial[0].actor;
const view = { ...seed, players: Array.from({ length: 22 }, (_, index) => ({ ...seed.players[index % 2],
  id: index < 2 ? seed.players[index].id : `pan-player-${index}`, name: `Player ${index + 1}`, number: index % 11 + 1,
  x: index < 11 ? 11 - Math.floor(index / 5) : 14 + Math.floor((index - 11) / 5), y: 3 + (index % 5) * 2,
  role: index < 11 ? 'home' as const : 'away' as const })), actions: [] };
// Drive the same pitch inputs that live playback supplies, without a server or HUD updates.
function MovementBoard() {
  const params = new URLSearchParams(location.search);
  const motion = params.get('movement');
  const [step, setStep] = useState(0);
  const [running, setRunning] = useState(false);
  const tickMs = 200;
  useEffect(() => {
    const start = () => {
      if (motion !== 'stationary') { setStep(1); setRunning(true); }
    };
    window.addEventListener('start-motion', start);
    return () => window.removeEventListener('start-motion', start);
  }, [motion]);
  useEffect(() => {
    if (!running || motion === 'stationary') return;
    const timer = window.setInterval(() => setStep(previous => Math.min(previous + 1, 8)), tickMs);
    return () => window.clearInterval(timer);
  }, [running, motion]);
  const laneY = 3 + step;
  const state = decodeSetupStateValue({
    ...view, homeTeamArt: { rosterId: 'human', league: params.get('league') }, awayTeamArt: { rosterId: 'orc', league: 'Badlands Brawl' },
    players: view.players.map((player, i) => ({ ...player, x: i === 0 ? 13 : player.x, y: i === 0 ? (motion === 'player' ? laneY : 3) : player.y,
      art: i < 11 ? { rosterId: 'human', positionId: 'lineman' } : { rosterId: 'orc', positionId: 'orc-lineman' } })),
    ball: { x: 12, y: motion === 'ball' ? laneY : 3 },
    ballState: { version: 1, carrierPlayerId: null, inPlay: true, moving: motion === 'ball' && running },
  });
  return <main className="play-runtime live-match-page"><div className="coach-match hosted-match"><div className="match-board">
    <LivePitch view={state} actions={[]} selectedId="" onSquare={() => {}} onSelectPlayer={() => {}}
      playback={running && motion !== 'stationary'} movementDuration={running && motion !== 'stationary' ? tickMs : 0}/>
  </div></div></main>;
}
function Board() {
  const context = diagnostics?.useDiagnostics();
  useEffect(() => {
    if (!context) return;
    context.service.preferences.freezeOnThreshold = false; context.service.preferences.manual = false;
    context.service.setMode(mode);
    return () => context.service.setMode('off');
  }, [context?.service]);
  Object.assign(window, { idlePanDiagnosticsAvailable: !!context, idlePanDiagnosticsMode: context ? mode : 'unavailable' });
  if (new URLSearchParams(location.search).has('movement')) return <MovementBoard/>;
  const cardRole = new URLSearchParams(location.search).get('card');
  if (cardRole === 'home' || cardRole === 'away') return <main className="play-runtime">
    <PlayerHoverCard player={view.players.find(player => player.role === cardRole)!} teamName={cardRole}/>
  </main>;
  return <main className="play-runtime live-match-page"><GameView hosted view={view} connected pending={null} mutate={() => {}}/></main>;
}
const Boundary = diagnostics?.DiagnosticsBoundary;
createRoot(document.getElementById('app')!).render(Boundary ? <Boundary environment="dev"><Board/></Boundary> : <Board/>);

type Input = { at: number; received: number; focus: number; transverse: number; commit?: number; opportunity?: number };
const input: Input[] = [], frames: number[] = [], errors: number[] = [], cameraAge: number[] = [];
let active = false, drag: { x: number; y: number; camera: PitchProjection; pointerId: number } | null = null;
let latest: Input | null = null, request = 0, prior = 0, rectReads = 0, mutations = 0;
let represented: Input | null = null;
let minimumFocus = 0, maximumFocus = 0;
const nativeRect = Element.prototype.getBoundingClientRect;
Element.prototype.getBoundingClientRect = function () { if (active) rectReads++; return nativeRect.call(this); };
const scene = () => document.querySelector<HTMLElement>('.live-pitch-scene')!;
const aligned = (sample: Input) => Math.abs(Number(scene().dataset.focus) - sample.focus) < 1e-6
  && Math.abs(Number(scene().dataset.transverseFocus) - sample.transverse) < 1e-6;
document.addEventListener('pointerdown', event => {
  if (!active || event.button !== 2) return;
  const el = scene(), viewport = el.closest<HTMLElement>('.live-pitch-viewport')!;
  drag = { x: event.clientX, y: event.clientY, pointerId: event.pointerId, camera: new PitchProjection({ width: viewport.clientWidth,
    height: viewport.clientHeight, focus: Number(el.dataset.focus), transverseFocus: Number(el.dataset.transverseFocus),
    zoom: Number(el.dataset.zoom), end: el.dataset.end === 'away' ? 'away' : 'home',
    mode: el.dataset.projection === 'top-down' ? 'top-down' : 'perspective',
    perspectiveElevation: el.dataset.projection === 'top-down' ? 50 : Number(el.dataset.elevation) as 30 | 40 | 50 }) };
}, true);
document.addEventListener('pointermove', event => {
  if (!active || !drag || !(event.buttons & 2)) return;
  const camera = drag.camera.panPixels({ x: event.clientX - drag.x, y: event.clientY - drag.y });
  latest = { at: event.timeStamp, received: performance.now(), focus: camera.focus, transverse: camera.transverseFocus };
  input.push(latest);
}, true);
document.addEventListener('pointerup', () => { drag = null; }, true);
const observer = new MutationObserver(records => {
  if (!active) return;
  mutations += records.length;
  minimumFocus = Math.min(minimumFocus, Number(scene().dataset.focus));
  maximumFocus = Math.max(maximumFocus, Number(scene().dataset.focus));
  const matching = input.findLast(sample => aligned(sample));
  if (matching) { represented = matching; if (matching.commit === undefined) {
    matching.commit = performance.now();
    requestAnimationFrame(() => { if (aligned(matching) && matching.opportunity === undefined) matching.opportunity = performance.now(); });
  } }
});
observer.observe(document.getElementById('app')!, { subtree: true, attributes: true });
function frame(now: number) {
  if (!active) return;
  if (prior) frames.push(now - prior); prior = now;
  if (latest) {
    errors.push(Math.hypot(Number(scene().dataset.focus) - latest.focus, Number(scene().dataset.transverseFocus) - latest.transverse));
    if (aligned(latest) && latest.opportunity === undefined) latest.opportunity = performance.now();
    if (drag && represented) cameraAge.push(performance.now() - represented.at);
  }
  request = requestAnimationFrame(frame);
}
Object.assign(window, { idlePan: {
  pointerId() { return drag?.pointerId; },
  start() { input.length = frames.length = errors.length = cameraAge.length = 0; rectReads = mutations = prior = 0; latest = represented = null;
    minimumFocus = maximumFocus = Number(scene().dataset.focus); active = true; request = requestAnimationFrame(frame); },
  finish() { active = false; cancelAnimationFrame(request); return { input, frames, errors, rectReads, mutations,
    cameraAge, panRange: maximumFocus - minimumFocus, finalAligned: !latest || aligned(latest), focus: scene().dataset.focus, transverse: scene().dataset.transverseFocus }; }
} });
