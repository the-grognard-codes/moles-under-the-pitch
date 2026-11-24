import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import { pitchMovement } from '../src/pitch-movement.ts';
import { decodeSetupStateValue } from '../src/setup-protocol.ts';

const fixture = JSON.parse(readFileSync(new URL('./fixtures/m5a-blitz-projections.json', import.meta.url), 'utf8'));
const view = decodeSetupStateValue(fixture[0].actor);
const carrier = view.players[0].id;
const atSquare = (x: number) => ({
  ...view,
  players: view.players.map(player => player.id === carrier ? { ...player, x, y: 7 } : player),
  ball: { x, y: 7 },
  ballState: { version: 1 as const, inPlay: true, moving: false, carrierPlayerId: carrier },
});

test('an interrupted step starts at the last displayed position and a late frame lands exactly on target', t => {
  let now = 1000;
  let nextFrame: FrameRequestCallback | undefined;
  t.mock.method(performance, 'now', () => now);
  Object.assign(globalThis, {
    window: {},
    requestAnimationFrame(callback: FrameRequestCallback) { nextFrame = callback; return 1; },
    cancelAnimationFrame() { nextFrame = undefined; },
  });
  t.after(() => {
    for (const key of ['window', 'requestAnimationFrame', 'cancelAnimationFrame']) Reflect.deleteProperty(globalThis, key);
  });
  const first = pitchMovement(atSquare(7), atSquare(8), 200, carrier, null)!;
  first.clock.start();
  now += 75;
  nextFrame!(now);
  const next = pitchMovement(atSquare(8), atSquare(9), 200, carrier, first)!;
  first.clock.stop();
  assert.deepEqual(next.player?.from, { x: 7.375, y: 7 });
  assert.equal(next.ball?.kind, 'carried');
  assert.equal(next.ball?.path, next.player);
  assert.deepEqual(next.ball?.path.to, { x: 9, y: 7 });
  next.clock.start();
  now += 800;
  nextFrame!(now);
  assert.equal(next.clock.snapshot(), 1);
});

test('an independent ball path retains travel when a native beat gives it a carrier', () => {
  const { ballState, ...base } = atSquare(7);
  const loose = { ...base, ball: { x: 4, y: 5 }, ballState: { ...ballState, moving: true, carrierPlayerId: null } };
  const caught = pitchMovement(loose, atSquare(7), 250, undefined, null);
  assert.equal(caught?.ball?.kind, 'loose');
  assert.deepEqual(caught?.ball?.path.from, { x: 4, y: 5 });
  assert.deepEqual(caught?.ball?.path.to, { x: 7, y: 7 });

  const pickup = pitchMovement({ ...loose, ball: { x: 8, y: 7 } }, atSquare(8), 200, carrier, null);
  assert.ok(pickup?.player);
  assert.equal(pickup.ball?.kind, 'loose', 'The ball remains loose while the player approaches');
  assert.deepEqual(pickup.ball.path.from, { x: 8, y: 7 });
  assert.deepEqual(pickup.ball.path.to, pickup.ball.path.from, 'The ball stays on the pickup square');
  assert.equal(pickup.ball.path.clock, pickup.player.clock);
});

test('kickoff travels when the pitch displays a ball outside native play', () => {
  const kickoff = (x: number) => {
    const state = atSquare(x);
    return { ...state, actions: [], driveTracker: { version: 1 as const, receivingRole: 'home' as const, phase: 'KICKOFF' as const,
      secretWeapons: false, knockouts: false, event: null, eventComplete: false },
      ballState: { ...state.ballState, inPlay: false, carrierPlayerId: null } };
  };
  const before = kickoff(5);
  const after = kickoff(8);
  const kick = pitchMovement(before, after, 250, undefined, null);
  assert.equal(kick?.ball?.kind, 'loose');
  assert.deepEqual(kick?.ball?.path.from, { x: 5, y: 7 });
  assert.deepEqual(kick?.ball?.path.to, { x: 8, y: 7 });
  assert.equal(pitchMovement({ ...before, driveTracker: undefined }, { ...after, driveTracker: undefined }, 250, undefined, null), null);
});
