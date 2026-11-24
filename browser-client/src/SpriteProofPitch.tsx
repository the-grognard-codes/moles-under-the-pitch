import { useEffect, useMemo, useRef, useState } from 'react';
import { PitchProjection, type PitchEnd, type PitchProjectionMode } from './pitch-projection.ts';
import { playerArtPlacement, resolvePlayerArt, type PlayerFacing } from './player-art.ts';
import { animationFrameAt, findProofClip, type ProofAnimation, type ProofClip, type ProofDesign } from './sprite-proof-animation.ts';
import { proofImageCanvas, SpriteProofTextures } from './sprite-proof-format.ts';
import type { SetupPlayer } from './setup-protocol.ts';

export type ProofPitchSettings = { design: ProofDesign; clip: ProofClip; direction: string; end: PitchEnd; mode: PitchProjectionMode;
  primary: string; secondary: string; mirror: boolean; elapsed: number; reduced: boolean; crowd: boolean; staticScene: boolean; sameKits: boolean };

const designs = ['lineman', 'halfling', 'ogre'] as const;
const percentile = (values: number[], fraction: number) =>
  [...values].sort((a, b) => a - b)[Math.floor((values.length - 1) * fraction)] ?? 0;

export function SpriteProofPitch({ pack, settings }: { pack: ProofAnimation; settings: ProofPitchSettings }) {
  const host = useRef<HTMLDivElement>(null), [error, setError] = useState('');
  const { end, mode } = settings;
  const camera = useMemo(() => new PitchProjection({ width: 1000, height: 600, end, mode, zoom: 0.8 }), [end, mode]);
  const current = useRef({ settings, camera });
  current.current = { settings, camera };

  useEffect(() => {
    const scene = host.current!;
    const sprites = Array.from({ length: 22 }, () => {
      const sprite = new Image();
      sprite.alt = '';
      sprite.draggable = false;
      sprite.hidden = true;
      scene.appendChild(sprite);
      return sprite;
    });
    let request = 0, scenario = '', lastTime = 0, textureBytes = 0;
    const intervals: number[] = [], costs: number[] = [];
    const newCache = () => new SpriteProofTextures(pack.palettePack, (image, pixels) => {
      textureBytes += image.width * image.height * 4;
      return proofImageCanvas(image, pixels).toDataURL();
    }, 96);
    let textures = newCache();

    const render = (now: number) => {
      const start = performance.now(), { settings: s, camera } = current.current;
      const key = [s.primary, s.secondary, s.sameKits, s.staticScene, s.crowd, s.mode, s.end].join(':');
      if (key !== scenario) {
        textures = newCache();
        scenario = key;
        intervals.length = costs.length = 0;
        lastTime = 0;
        textureBytes = 0;
      }
      let fallbacks = 0;
      sprites.forEach((sprite, index) => {
        sprite.hidden = !s.crowd && index !== 0;
        if (sprite.hidden) {
          sprite.removeAttribute('src');
          return;
        }
        const design = s.crowd ? designs[index % designs.length] : s.design;
        const player: SetupPlayer = { id: String(index), name: design, slot: index + 1, role: index < 11 ? 'home' : 'away',
          x: s.crowd ? 9 + Math.floor(index / 6) * 2 : 13, y: s.crowd ? 2.5 + index % 6 * 2 : 7.5,
          state: 'standing', art: { rosterId: 'human', positionId: design } };
        const facing: PlayerFacing = s.direction === 'front45' ? 'south-east' : s.direction === 'back45' ? 'north-east' : 'east';
        const { body } = resolvePlayerArt(player, { end: s.end, facing })!;
        const clip = s.staticScene ? null : findProofClip(pack, design, s.clip, body.pose);
        const frame = clip?.frames[animationFrameAt(clip, s.elapsed, s.reduced)];
        const image = frame && pack.palettePack.images.find(image => image.id === frame.imageId);
        const swapKits = player.role === 'away' && !s.sameKits;
        const url = image ? textures.resolve(image.sourceUrl, swapKits ? s.secondary : s.primary, swapKits ? s.primary : s.secondary)! : body.url;
        const record = image && frame ? { ...frame, file: image.sourceUrl, width: image.width, height: image.height } : body;
        const { anchor, mode } = playerArtPlacement(record, s.mode === 'top-down');
        const point = camera.project({ x: player.x!, y: player.y! })!;
        const mirrored = body.mirror !== s.mirror;
        // The resolver already mirrors accepted anchors. Proof frames carry the source anchors.
        const anchorX = (image ? mirrored : s.mirror) ? record.width - anchor.x : anchor.x;
        const scale = point.pixelsPerSquare * 1.08 / 64;
        sprite.style.left = `${point.x - anchorX * scale}px`;
        sprite.style.top = `${point.y - anchor.y * scale}px`;
        sprite.style.width = `${record.width * scale}px`;
        sprite.style.height = `${record.height * scale}px`;
        sprite.style.transform = mirrored ? 'scaleX(-1)' : 'none';
        sprite.dataset.pose = record.pose;
        sprite.dataset.anchorMode = mode;
        sprite.dataset.mirrored = String(mirrored);
        if (image) sprite.dataset.frame = image.id;
        else { delete sprite.dataset.frame; fallbacks++; }
        if (sprite.getAttribute('src') !== url) sprite.src = url;
      });
      if (lastTime) intervals.push(now - lastTime);
      lastTime = now;
      costs.push(performance.now() - start);
      if (intervals.length > 600) intervals.shift();
      if (costs.length > 600) costs.shift();
      scene.dataset.metrics = JSON.stringify({ count: s.crowd ? 22 : 1, samples: intervals.length,
        intervalMedianMs: percentile(intervals, 0.5), intervalP95Ms: percentile(intervals, 0.95),
        cpuMedianMs: percentile(costs, 0.5), cpuP95Ms: percentile(costs, 0.95), textures: textures.size,
        textureBytes, fallbacks, staticScene: s.staticScene });
      request = requestAnimationFrame(tick);
    };
    const tick = (now: number) => {
      try { render(now); }
      catch (error) { setError(String(error)); }
    };
    request = requestAnimationFrame(tick);
    return () => { cancelAnimationFrame(request); scene.replaceChildren(); delete scene.dataset.metrics; };
  }, [pack]);

  return <><div className="proof-pitch-scroll"><div className="proof-pitch-stage">
    <img alt="Existing pitch background" src="/assets/game/pitch/live-pitch.svg" width={960} height={564}
      style={{ position: 'absolute', transformOrigin: '0 0', transform: camera.planeImageTransform([[1 / 36, 0, -1 / 3], [0, 1 / 36, -1 / 3], [0, 0, 1]]) }}/>
    <div ref={host} className="proof-pitch-sprites" role="img" aria-label="Projected sprite proof"
      onErrorCapture={() => setError('Player art could not be loaded')}/>
  </div></div>{error && <p role="alert">Pitch proof unavailable: {error}</p>}</>;
}
