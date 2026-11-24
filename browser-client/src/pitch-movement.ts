import { useSyncExternalStore } from 'react';
import type { Point } from './pitch-projection.ts';
import type { SetupState } from './setup-protocol.ts';

type SquarePath = { from: Point; to: Point };
export type MovingSquare = SquarePath & { clock: MovementClock };
export type PitchMovement = { clock: MovementClock; playerId?: string; player: MovingSquare | null; ball: MovingSquare | null };

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

function pathBetween(from: Point | null, to: Point | null, previous?: MovingSquare | null): SquarePath | null {
  if (!from || !to || from.x === to.x && from.y === to.y) return null;
  return { from: previous ? sampleSquare(previous, previous.clock.snapshot()) : from, to };
}

export function pitchMovement(before: SetupState, after: SetupState, duration: number,
  playerId: string | undefined, previous: PitchMovement | null): PitchMovement | null {
  if (duration <= 0) return null;
  const playerPath = playerId ? pathBetween(squareOf(before, playerId), squareOf(after, playerId),
    previous?.playerId === playerId ? previous.player : null) : null;
  const carrier = after.ballState?.carrierPlayerId;
  const ballPath = !carrier && before.ballState?.inPlay !== false && after.ballState?.inPlay !== false
    ? pathBetween(before.ball, after.ball, previous?.ball) : null;
  if (!playerPath && !ballPath) return null;
  const clock = new MovementClock(duration);
  const player = playerPath ? { ...playerPath, clock } : null;
  // A possession change to a stationary player places the ring on that player immediately.
  const ball = after.ball && carrier && carrier === playerId ? player : ballPath ? { ...ballPath, clock } : null;
  return { clock, playerId, player, ball };
}

const subscribeIdle = () => () => {};
const idleSnapshot = () => 1;

/** Static pieces subscribe to an idle source and never re-render on an animation frame. */
export function useMovingSquare(square: Point, motion?: MovingSquare | null): Point {
  const progress = useSyncExternalStore(motion?.clock.subscribe ?? subscribeIdle, motion?.clock.snapshot ?? idleSnapshot);
  return motion ? sampleSquare(motion, progress) : square;
}
