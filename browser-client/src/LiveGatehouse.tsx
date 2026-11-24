import { useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { GatehouseCompositionCache } from './gatehouse-stadium.ts';
import type { PitchProjection } from './pitch-projection.ts';
import type { SetupState } from './setup-protocol.ts';
import { gatehouseTeam, usesGatehouse } from './gatehouse-match.ts';

type Composition = Awaited<ReturnType<GatehouseCompositionCache['load']>>;

export function useLiveGatehouse(view: SetupState, camera: PitchProjection) {
  const home = view.homeTeamArt, away = view.awayTeamArt;
  const { homeTeamName, awayTeamName, matchId } = view;
  const teams = useMemo(() => ({
    home: gatehouseTeam(home, homeTeamName), away: gatehouseTeam(away, awayTeamName),
  }), [homeTeamName, awayTeamName, home?.rosterId, away?.rosterId, home?.colors?.primary,
    home?.colors?.secondary, away?.colors?.primary, away?.colors?.secondary]);
  const cache = useMemo(() => new GatehouseCompositionCache(), [matchId, teams]);
  const { mode, end } = camera;
  const league = home?.league;
  const eligible = usesGatehouse(mode, league);
  const [ready, setReady] = useState<{ cache: GatehouseCompositionCache; end: typeof end; art: Composition } | null>(null);
  useEffect(() => {
    if (!eligible) {
      if (mode === 'top-down') console.debug('Using turf for home league', league ?? '(unspecified)');
      return;
    }
    let active = true;
    cache.load(end, teams).then(art => {
      if (active) setReady({ cache, end, art });
    }).catch(error => {
      if (active) console.warn('Gatehouse unavailable; using turf', error);
    });
    return () => { active = false; };
  }, [cache, teams, end, eligible, mode, league]);
  return eligible && ready?.cache === cache && ready.end === end ? ready.art : null;
}

export function LiveGatehouse({ art, camera }: { art: Composition; camera: PitchProjection }) {
  const host = useRef<HTMLDivElement>(null);
  useLayoutEffect(() => {
    const container = host.current!;
    container.replaceChildren(art.canvas);
    return () => { container.replaceChildren(); };
  }, [art]);
  return <div ref={host} aria-hidden="true" className="stadium-surface pitch-gatehouse" data-end={camera.end}
    data-compose-ms={art.composeMs} style={{ transform: camera.planeImageTransform(art.alignment), imageRendering: 'pixelated' }}/>;
}
