import contract from '../../ffb-server/src/main/resources/match/format-4.json' with { type: 'json' };
import { parseUniqueJson } from './saved-team-protocol.ts';

type Fields = Record<string, unknown>;
type Side = 'home' | 'away';
type Square = { x: number; y: number };
export type RecordedPlayer = Fields & { id: string; role: Side; offPitch: string; x: number | null; y: number | null };
export type RecordedView = Fields & { matchId: string; revision: number; players: RecordedPlayer[] };
export type PublicPatch = {
  baseRevision: number | null;
  sections: Fields;
  removeSections: string[];
  players: RecordedPlayer[];
  removePlayers: string[];
  playerOrder?: string[];
};
export type PresentationMarker = { commandNr: number | null; ordinal: number };
export type MatchRecord = {
  formatVersion: 4;
  matchId: string;
  revision: number;
  kind: 'START' | 'ACTION' | 'SELECTION' | 'TOUCHDOWN' | 'HALFTIME' | 'FULL_TIME';
  actor: { side: Side; teamId: string } | null;
  at: number;
  decision: (Fields & { operation: string }) | null;
  outcomes: (Fields & { commandNr: number })[];
  presentation: PresentationMarker[];
  patch: PublicPatch;
};
export type MatchKeyframe = { formatVersion: 4; view: RecordedView };
export type MatchStatus = {
  formatVersion: 4;
  matchId: string;
  revision: number;
  generation: number;
  sampledAt: number;
  saveResume: { status: 'ACTIVE' | 'SAVE_PENDING' | 'RESUME_PENDING' | 'SUSPENDED' | 'ABANDONED'; proposer: Side | null; expiresAt: number | null } | null;
  clock: { activeRole: Side | null; turnElapsedMs: number; homeReserveMs: number; awayReserveMs: number; running: boolean };
  reaction: { actor: Side; title: string; detail?: string; expiresAt: number; allowanceMs: number; running: boolean } | null;
};

type Schema = string | (string | number | SchemaObject | readonly unknown[])[] | SchemaObject;
type SchemaObject = { required: Record<string, Schema>; optional: Record<string, Schema> };
const schemas: Record<string, SchemaObject> = contract.schemas;
const reportFields: Record<string, string[]> = contract.reportFields;
const utf8 = new TextEncoder();
const maxBytes = 8 * 1024 * 1024;
const maxDepth = 24;
const matchId = /^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/;
const teamId = /^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}:(home|away)$/;
const object = (value: unknown): value is Fields => !!value && typeof value === 'object' && !Array.isArray(value);
const integer = (value: unknown, min: number, max: number) =>
  typeof value === 'number' && Number.isSafeInteger(value) && value >= min && value <= max;
const text = (value: unknown, max: number) => typeof value === 'string' && value.length > 0 && value.length <= max;
const invalid = (path: string) => Error('Invalid format 4 at ' + path);
const checked = (value: unknown, schema: Schema, path: string) => {
  if (!accepts(value, schema, path)) throw invalid(path);
};

function parse(json: string): unknown {
  if (json.length > maxBytes || utf8.encode(json).byteLength > maxBytes) throw invalid('encoding bounds');
  return parseUniqueJson(json, { integersOnly: true, maxDepth });
}

function accepts(value: unknown, schema: Schema, path: string): boolean {
  if (typeof schema === 'string') return primitive(value, schema, path);
  if (!Array.isArray(schema)) {
    if (!object(value)) return false;
    for (const key of Object.keys(schema.required)) if (!Object.hasOwn(value, key)) throw invalid(path + '.' + key);
    for (const [key, item] of Object.entries(value)) {
      const expected = Object.hasOwn(schema.required, key) ? schema.required[key]
        : Object.hasOwn(schema.optional, key) ? schema.optional[key] : undefined;
      if (expected === undefined) throw invalid(path + '.' + key);
      checked(item, expected, path + '.' + key);
    }
    return true;
  }
  const [kind, nested, max] = schema;
  if (kind === 'nullable') return value === null || accepts(value, nested as Schema, path);
  if (kind === 'list') {
    if (!Array.isArray(value) || value.length > Number(max)) return false;
    for (const item of value) checked(item, nested as Schema, path + '[]');
    return true;
  }
  if (kind === 'any') {
    for (const candidate of schema.slice(1)) {
      try { if (accepts(value, candidate as Schema, path)) return true; } catch { /* Try the next shape. */ }
    }
    return false;
  }
  return schema.includes(value as string | number);
}

function primitive(value: unknown, type: string, path: string): boolean {
  if (type.startsWith('@')) return accepts(value, schemas[type.slice(1)], path);
  switch (type) {
    case 'id': return text(value, 200);
    case 'text': return text(value, 2000);
    case 'match': return typeof value === 'string' && matchId.test(value);
    case 'matchTeam': return typeof value === 'string' && teamId.test(value);
    case 'nativeSquare': return Array.isArray(value) && value.length === 2
      && value.every(item => integer(item, -2147483648, 2147483647));
    case 'int': return integer(value, -2147483648, 2147483647);
    case 'nat': return integer(value, 0, 2147483647);
    case 'positive': return integer(value, 1, 2147483647);
    case 'column': return integer(value, 0, 25);
    case 'row': return integer(value, 0, 14);
    case 'kickColumn': return integer(value, -6, 31);
    case 'kickRow': return integer(value, -6, 20);
    case 'time': return integer(value, 0, Number.MAX_SAFE_INTEGER);
    case 'generation': return integer(value, 1, Number.MAX_SAFE_INTEGER);
    case 'boolean': return typeof value === 'boolean';
    case 'null': return value === null;
    case 'report': return report(value, path);
    default: throw Error('Unknown format-4 schema type ' + type);
  }
}

function report(value: unknown, path: string): boolean {
  if (!object(value) || typeof value.reportId !== 'string' || !Object.hasOwn(reportFields, value.reportId)) return false;
  const permitted = reportFields[value.reportId];
  const extensions = schemas.report.optional;
  const structured = schemas.reportData.optional;

  for (const [key, item] of Object.entries(value)) {
    if (key === 'reportId') continue;
    if (Object.hasOwn(extensions, key)) {
      checked(item, extensions[key], path + '.' + key);
      continue;
    }
    const normalizedSide = key === 'side' && permitted.includes('coach');
    if (!normalizedSide && (!permitted.includes(key) || contract.omittedReportFields.includes(key))) return false;
    if (!Object.hasOwn(structured, key)) return false;
    checked(item, structured[key], path + '.' + key);
  }
  return true;
}

function unique(values: readonly string[], path: string) {
  const identities = new Set(values);
  if (identities.size !== values.length) throw invalid(path);
  return identities;
}

function playerIds(players: RecordedPlayer[]) {
  for (const player of players) {
    const onPitch = player.offPitch === 'pitch';
    if (onPitch === (player.x === null) || onPitch === (player.y === null)
      || Object.hasOwn(player, 'positionRace') !== Object.hasOwn(player, 'positionRole')) throw invalid('player location or position');
  }
  return unique(players.map(player => player.id), 'player identity');
}

export function validateRecordedView(view: RecordedView): RecordedView {
  checked(view, schemas.view, 'view');
  const players = playerIds(view.players);
  if ((view.phase === 'FULL_TIME') !== (view.decisionKind === 'none')
    || (view.activePlayerId === null ? Object.hasOwn(view, 'activePlayerAction') : !players.has(String(view.activePlayerId))))
    throw invalid('view decision or active player');
  return view;
}

export const matchTeamId = (match: string, side: Side) => match + ':' + side;
export const presentationMarkerId = (record: MatchRecord, marker: PresentationMarker) =>
  [record.matchId, record.revision, marker.commandNr ?? '-', marker.ordinal].join(':');

export function decodeMatchRecord(json: string): MatchRecord {
  const source = parse(json);
  checked(source, schemas.record, 'record');
  const record = source as MatchRecord;
  const { actor, decision, patch, revision } = record;
  const start = revision === 0;
  if (start !== (record.kind === 'START') || start !== (decision === null) || start !== (patch.baseRevision === null)
    || (start ? actor !== null || !patch.playerOrder : !actor || patch.baseRevision !== revision - 1))
    throw invalid('record revision');
  if (actor && actor.teamId !== matchTeamId(record.matchId, actor.side)) throw invalid('record.actor.teamId');
  if (decision) {
    if (decision.optionId !== undefined && decision.operation !== 'choice'
      || decision.reason !== undefined && decision.operation !== 'sizeLimit'
      || (decision.playerId !== undefined || decision.to !== undefined) && decision.operation !== 'place'
      || decision.playerIds !== undefined && decision.operation !== 'action') throw invalid('record.decision');
    const movement = (decision.logPresentation as { movement: { commitRevision: number } | null } | undefined)?.movement;
    if (movement && movement.commitRevision >= revision) throw invalid('record.decision.logPresentation.movement');
  }
  const removedSections = unique(patch.removeSections, 'patch section identity');
  if (Object.keys(patch.sections).some(key => removedSections.has(key))) throw invalid('patch section overlap');
  const removedPlayers = unique(patch.removePlayers, 'patch removed player identity');
  if ([...playerIds(patch.players)].some(id => removedPlayers.has(id))) throw invalid('patch player overlap');
  if (patch.playerOrder) unique(patch.playerOrder, 'patch player order');
  let last = 0;
  const commands = new Set<number>();
  for (const outcome of record.outcomes) {
    if (outcome.commandNr <= last) throw invalid('record.outcomes order');
    last = outcome.commandNr;
    commands.add(last);
    checkOutcomeTeams(outcome, record.matchId);
  }
  record.presentation.forEach((marker, ordinal) => {
    if (marker.ordinal !== ordinal || marker.commandNr !== null && !commands.has(marker.commandNr)) throw invalid('record.presentation order');
  });
  return record;
}

function checkOutcomeTeams(outcome: MatchRecord['outcomes'][number], match: string) {
  const list = outcome.reportList as { reports: Fields[] } | null | undefined;
  if (!list) return;
  const home = matchTeamId(match, 'home');
  const away = matchTeamId(match, 'away');
  const outsideMatch = (id: unknown) => id != null && id !== home && id !== away;
  for (const report of list.reports) {
    if (contract.teamFields.some(field => outsideMatch(report[field])))
      throw invalid('record.outcomes team identity');
    for (const field of contract.teamListFields) {
      const teams = report[field] as string[] | null | undefined;
      if (teams?.some(outsideMatch)) throw invalid('record.outcomes team identity');
    }
  }
}

export function decodeMatchKeyframe(json: string): MatchKeyframe {
  const source = parse(json);
  checked(source, schemas.keyframe, 'keyframe');
  const frame = source as MatchKeyframe;
  validateRecordedView(frame.view);
  return frame;
}

export function decodeMatchStatus(json: string): MatchStatus {
  const source = parse(json);
  checked(source, schemas.status, 'status');
  const status = source as MatchStatus;
  const save = status.saveResume;
  if (save) {
    const pending = save.status.endsWith('_PENDING');
    if (pending === (save.proposer === null) || pending === (save.expiresAt === null)) throw invalid('status.saveResume');
  }
  if (status.clock.running && status.clock.activeRole === null) throw invalid('status.clock');
  return status;
}

export type RecordedMatchNames = {
  team: (matchTeamId: string) => string;
  player: (playerId: string) => string;
};

export function resolveRecordedViewNames(view: RecordedView, names: RecordedMatchNames) {
  return {
    ...view,
    homeTeamName: names.team(matchTeamId(view.matchId, 'home')),
    awayTeamName: names.team(matchTeamId(view.matchId, 'away')),
    ball: (view.ball as Square | null | undefined) ?? null,
    players: view.players.map(player => ({ ...player, name: names.player(player.id) })),
  };
}
