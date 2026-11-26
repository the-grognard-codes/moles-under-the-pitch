import { validateRecordedView } from './match-record-protocol.ts';
import type { MatchKeyframe, MatchRecord, MatchStatus, RecordedView } from './match-record-protocol.ts';

export type ReducedMatch = {
  matchId: string;
  view: RecordedView | null;
  status: MatchStatus | null;
};

export function startMatchReduction(matchId: string): ReducedMatch {
  return { matchId, view: null, status: null };
}

export function applyMatchRecord(match: ReducedMatch, record: MatchRecord): ReducedMatch {
  const before = match.view;
  const patch = record.patch;
  if (match.matchId !== record.matchId || (before?.revision ?? null) !== patch.baseRevision)
    throw Error('Format-4 record does not follow the current match revision');

  const sections = { ...before, ...patch.sections };
  for (const key of patch.removeSections) {
    if (!Object.hasOwn(sections, key)) throw Error('Format-4 patch removes an absent section');
    delete sections[key];
  }

  const players = new Map(before?.players.map(player => [player.id, player]));
  for (const id of patch.removePlayers) {
    if (!players.delete(id)) throw Error('Format-4 patch removes an absent player');
  }
  for (const player of patch.players) players.set(player.id, player);
  const order = patch.playerOrder ?? before?.players.map(player => player.id) ?? [];
  if (order.length !== players.size || new Set(order).size !== order.length)
    throw Error('Format-4 patch has an incomplete player order');
  const roster = order.map(id => {
    const player = players.get(id);
    if (!player) throw Error('Format-4 patch orders an absent player');
    return player;
  });
  const view = validateRecordedView({ ...sections, matchId: match.matchId, revision: record.revision, players: roster });
  return { ...match, view };
}

export function applyMatchStatus(match: ReducedMatch, status: MatchStatus): ReducedMatch {
  if (match.matchId !== status.matchId) throw Error('Format-4 status belongs to another match');
  if (match.status) {
    if (match.status.generation >= status.generation) return match;
    if (match.status.revision > status.revision) throw Error('Format-4 status revision went backwards');
  }
  return { ...match, status };
}

export function applyMatchKeyframe(match: ReducedMatch, frame: MatchKeyframe):
{ match: ReducedMatch; driftPaths: string[] } {
  if (match.matchId !== frame.view.matchId) throw Error('Format-4 keyframe belongs to another match');
  if (match.view && match.view.revision > frame.view.revision) return { match, driftPaths: [] };
  const driftPaths: string[] = [];
  if (match.view?.revision === frame.view.revision) differences(match.view, frame.view, '', driftPaths);
  return { match: { ...match, view: frame.view }, driftPaths };
}

function differences(before: unknown, after: unknown, path: string, found: string[]) {
  if (before === after || found.length >= 16) return;
  if (Array.isArray(before) && Array.isArray(after)) {
    if (before.length !== after.length) found.push(path + '.length');
    for (let index = 0; index < Math.min(before.length, after.length) && found.length < 16; index++)
      differences(before[index], after[index], path + '[' + index + ']', found);
  } else if (before && after && typeof before === 'object' && typeof after === 'object'
    && !Array.isArray(before) && !Array.isArray(after)) {
    const left = before as Record<string, unknown>;
    const right = after as Record<string, unknown>;
    for (const key of [...new Set([...Object.keys(left), ...Object.keys(right)])].sort()) {
      differences(left[key], right[key], path ? path + '.' + key : key, found);
      if (found.length >= 16) break;
    }
  } else found.push(path);
}
