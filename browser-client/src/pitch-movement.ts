import { useSyncExternalStore } from 'react';
import type { Point } from './pitch-projection.ts';
import type { SetupState } from './setup-protocol.ts';
import { pitchBallState } from './kickoff-ball-presentation.ts';

type SquarePath = { from: Point; to: Point };
export type PiecePath = SquarePath & { clock: MovementClock };
export type BallMovement = { path: PiecePath; kind: 'carried' | 'loose' };
export type PitchMovement = { duration: number; clock: MovementClock; playerId?: string; player: PiecePath | null; ball: BallMovement | null };

/** One clock publishes a shared sample for every layer participating in a beat. */
class MovementClock {
  private readonly duration: number;
  private frame: number | null = null;
  private progress = 0;
  private preference?: MediaQueryList;
  private listeners = new Set<() => void>();

  constructor(duration: number) {
    this.duration = duration;
  }

  snapshot = () => this.progress;

  subscribe = (listener: () => void) => {
    this.listeners.add(listener);
    return () => { this.listeners.delete(listener); };
  };

  start() {
    this.preference = window.matchMedia?.('(prefers-reduced-motion: reduce)');
    if (this.preference?.matches) {
      this.finish();
      return;
    }
    this.preference?.addEventListener('change', this.onPreferenceChange);
    const start = performance.now();
    const sample = (now: number) => {
      this.frame = null;
      const progress = Math.max(0, (now - start) / this.duration);
      if (progress >= 1) {
        this.finish();
        return;
      }
      this.publish(progress);
      this.frame = requestAnimationFrame(sample);
    };
    this.frame = requestAnimationFrame(sample);
  }

  private onPreferenceChange = (event: MediaQueryListEvent) => {
    if (event.matches) this.finish();
  };

  private publish(progress: number) {
    if (progress === this.progress) return;
    this.progress = progress;
    this.listeners.forEach(listener => listener());
  }

  finish() {
    this.stop();
    this.publish(1);
  }

  stop() {
    if (this.frame !== null) cancelAnimationFrame(this.frame);
    this.frame = null;
    this.preference?.removeEventListener('change', this.onPreferenceChange);
    this.preference = undefined;
  }
}

function sampleSquare(path: SquarePath, progress: number): Point {
  if (progress === 0) return path.from;
  if (progress === 1) return path.to;
  return { x: path.from.x + (path.to.x - path.from.x) * progress, y: path.from.y + (path.to.y - path.from.y) * progress };
}

function squareOf(view: SetupState, playerId: string): Point | null {
  const player = view.players.find(player => player.id === playerId);
  return player?.x != null && player.y != null ? { x: player.x, y: player.y } : null;
}

function pathBetween(from: Point | null, to: Point | null, previous?: PiecePath | null): SquarePath | null {
  if (!from || !to || from.x === to.x && from.y === to.y) return null;
  return { from: previous ? sampleSquare(previous, previous.clock.snapshot()) : from, to };
}

export function pitchMovement(before: SetupState, after: SetupState, duration: number,
  playerId: string | undefined, previous: PitchMovement | null): PitchMovement | null {
  if (duration <= 0) return null;
  const playerPath = playerId ? pathBetween(squareOf(before, playerId), squareOf(after, playerId),
    previous?.playerId === playerId ? previous.player : null) : null;
  const beforeBall = pitchBallState(before), afterBall = pitchBallState(after);
  const carried = !!playerId && beforeBall?.carrierPlayerId === playerId && afterBall?.carrierPlayerId === playerId;
  let ballPath: SquarePath | null = null;
  if (!carried && before.ball && after.ball && beforeBall?.inPlay !== false && afterBall?.inPlay !== false) {
    ballPath = pathBetween(before.ball, after.ball, previous?.ball?.path);
    // A mover reaches an already-loose ball before the pickup is visible.
    if (!ballPath && playerPath && beforeBall?.carrierPlayerId === null && afterBall?.carrierPlayerId === playerId)
      ballPath = { from: before.ball, to: after.ball };
  }
  if (!playerPath && !ballPath) return null;
  const clock = new MovementClock(duration);
  const player = playerPath ? { ...playerPath, clock } : null;
  const ball: BallMovement | null = carried && player && before.ball && after.ball
    ? { path: player, kind: 'carried' } : ballPath ? { path: { ...ballPath, clock }, kind: 'loose' } : null;
  return { duration, clock, playerId, player, ball };
}

const subscribeIdle = () => () => {};
const idleSnapshot = () => 1;

/** Static pieces subscribe to an idle source and never re-render on an animation frame. */
export function useDisplayedPosition(square: Point, path?: PiecePath | null): { position: Point; inMotion: boolean } {
  const progress = useSyncExternalStore(path?.clock.subscribe ?? subscribeIdle, path?.clock.snapshot ?? idleSnapshot);
  return { position: path ? sampleSquare(path, progress) : square, inMotion: progress < 1 };
}
