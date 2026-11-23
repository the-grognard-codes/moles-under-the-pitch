import type { PitchProjectionMode } from './pitch-projection.ts';
import type { MatchTeamArt } from './setup-protocol.ts';
import { defaultTeamColors } from './team-colors.ts';

export function usesGatehouse(mode: PitchProjectionMode, league?: string | null) {
  return mode === 'top-down' && league === 'Old World Classic';
}

export function gatehouseTeam(art?: MatchTeamArt, name?: string | null) {
  const colors = art?.colors ?? defaultTeamColors(art?.rosterId === 'orc' ? 'orc' : 'human');
  return { name, colors };
}
