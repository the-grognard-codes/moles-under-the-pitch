import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import { GatehouseCompositionCache, gatehouseAlignment, type GatehouseTeams } from '../src/gatehouse-stadium.ts';
import type { PitchEnd, PlaneMatrix } from '../src/pitch-projection.ts';

const manifest = JSON.parse(readFileSync(new URL(
  '../../assets/game/pitch/stadiums/gatehouse/views.json', import.meta.url), 'utf8'));
const ends: PitchEnd[] = ['home', 'away'];

function worldPoint(matrix: PlaneMatrix, u: number, v: number) {
  return matrix.slice(0, 2).map(([uScale, vScale, offset]) => uScale * u + vScale * v + offset);
}

test('Gatehouse views map all 390 native square centers to the existing world grid', () => {
  for (const end of ends) {
    const { alignment } = manifest.views[end];
    const [left, top] = alignment.pitchOrigin;
    const square = alignment.pixelsPerSquare;
    const transform = gatehouseAlignment(alignment);
    for (let row = 0; row < 26; row++) {
      for (let column = 0; column < 15; column++) {
        const u = left + (column + .5) * square;
        const v = top + (row + .5) * square;
        assert.deepEqual(worldPoint(transform, u, v),
          end === 'home' ? [25.5 - row, column + .5] : [row + .5, 14.5 - column]);
      }
    }
  }
});

test('each Gatehouse view assigns end-zone paint and visiting supporters to the world-owning team', () => {
  for (const end of ends) {
    const view = manifest.views[end];
    const transform = gatehouseAlignment(view.alignment);
    for (const row of view.endZones) {
      const [x] = worldPoint(transform, row.x, row.y + row.height / 2);
      assert.equal(x, row.team === 'home' ? .5 : 25.5);
    }
    const crowd = manifest.crowds[view.crowd];
    assert.equal(crowd.bays.length, 15);
    const visitors = crowd.bays.filter(({ id }: { id: number }) => view.kitByBay[id] === 'away');
    assert.deepEqual(visitors.map(({ id }: { id: number }) => id), end === 'home' ? [8, 9, 10, 11] : [4, 5, 6, 7]);
    const { atlas, pieces } = manifest.regaliaSets[view.regalia];
    const awayPennants = pieces.filter(({ team }: { team: PitchEnd }) => team === 'away');
    assert.equal(awayPennants.length, 4);
    for (const piece of pieces) {
      assert.ok(manifest.regaliaAtlases[atlas].sprites[piece.sprite]);
      if (piece.team === 'away') {
        assert.equal(piece.sprite, end === 'home' ? 'orc-pennant-left' : 'orc-pennant-right');
      }
    }
  }
});

const teams: GatehouseTeams = {
  home: { name: 'Ironbank Rovers', colors: { version: 1, primary: 'navy', secondary: 'gold' } },
  away: { name: 'Cinderclaw Crew', colors: { version: 1, primary: 'red', secondary: 'gold' } },
};

test('the Gatehouse cache shares in-flight and settled work and resets both ends on an identity change', async () => {
  let calls = 0;
  const cache = new GatehouseCompositionCache(async () => ({
    canvas: {} as HTMLCanvasElement,
    alignment: [[1, 0, 0], [0, 1, 0], [0, 0, 1]],
    composeMs: ++calls,
  }));
  const home = cache.load('home', teams);
  assert.equal(cache.load('home', teams), home);
  assert.equal((await home).composeMs, 1);
  assert.equal((await cache.load('away', teams)).composeMs, 2);
  assert.equal(cache.load('home', structuredClone(teams)), home);

  const changedTeams = { ...teams, away: { ...teams.away, name: 'New Away Team' } };
  assert.equal((await cache.load('away', changedTeams)).composeMs, 3);
  assert.equal((await cache.load('home', changedTeams)).composeMs, 4);
});

test('a failed Gatehouse composition can be retried', async () => {
  let calls = 0;
  const cache = new GatehouseCompositionCache(async () => {
    if (++calls === 1) throw Error('art unavailable');
    return { canvas: {} as HTMLCanvasElement, alignment: [[1, 0, 0], [0, 1, 0], [0, 0, 1]], composeMs: 2 };
  });
  await assert.rejects(cache.load('home', teams), /art unavailable/);
  assert.equal((await cache.load('home', teams)).composeMs, 2);
});
