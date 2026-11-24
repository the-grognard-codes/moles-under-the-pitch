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
  assert.deepEqual(next.ball?.from, next.player?.from);
  assert.deepEqual(next.ball?.to, { x: 9, y: 7 });
  next.clock.start();
  now += 800;
  nextFrame!(now);
  assert.equal(next.clock.snapshot(), 1);
});
