import assert from 'node:assert/strict';
import test from 'node:test';
import { gatehouseTeam, usesGatehouse } from '../src/gatehouse-match.ts';
import { defaultTeamColors } from '../src/team-colors.ts';

test('only the exact supported home league uses Gatehouse overhead', () => {
  assert.equal(usesGatehouse('top-down', 'Old World Classic'), true);
  assert.equal(usesGatehouse('perspective', 'Old World Classic'), false);
  for (const league of [null, undefined, '', 'Unknown', 'Badlands Brawl'])
    assert.equal(usesGatehouse('top-down', league), false);
});

test('frozen match kits take priority and legacy teams use roster colours without inventing names', () => {
  const colors = defaultTeamColors('orc');
  assert.deepEqual(gatehouseTeam({ rosterId: 'human', league: null, colors }, 'Rovers'), { name: 'Rovers', colors });
  assert.deepEqual(gatehouseTeam({ rosterId: 'orc', league: null }), { name: undefined, colors });
  assert.deepEqual(gatehouseTeam(), { name: undefined, colors: defaultTeamColors('human') });
});
