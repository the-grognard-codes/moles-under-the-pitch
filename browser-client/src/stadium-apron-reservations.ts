import layout from '../../assets/game/references/stadiums/scale-studies/apron-reservations.json';

export const apronGuideColors = { staff: '#ff6459', reserves: '#62b4ff', cheerleaders: '#f275ee' } as const;
const kinds = ['staff', 'reserves', 'cheerleaders'] as const;
export const apronReservations = kinds.flatMap(kind =>
  layout[kind].map(area => {
    const { width, height } = area;
    const outline = ('outline' in area && area.outline) || [[0, 0], [width, 0], [width, height], [0, height]];
    return { ...area, kind, outline };
  }));

/** Stroke inward so the guide exposes the exact footprint available for each group. */
export function drawApronReservations(context: CanvasRenderingContext2D) {
  for (const { kind, x, y, outline } of apronReservations) {
    const shape = new Path2D();
    shape.moveTo(x + outline[0][0], y + outline[0][1]);
    for (const [dx, dy] of outline.slice(1)) shape.lineTo(x + dx, y + dy);
    shape.closePath();
    context.save();
    context.clip(shape);
    const color = apronGuideColors[kind];
    context.fillStyle = color + '28';
    context.fill(shape);
    context.strokeStyle = color;
    context.lineWidth = 4;
    context.setLineDash(kind === 'reserves' ? [6, 3] : kind === 'cheerleaders' ? [2, 2] : []);
    context.stroke(shape);
    context.restore();
  }
}
