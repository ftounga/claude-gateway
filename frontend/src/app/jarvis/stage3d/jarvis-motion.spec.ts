import { HOLO_ALPHA, HOLO_COLORS, MOTION_CURVES, MOTION_DURATIONS, MotionCurve, cssCurve, ease, holoCssVars, rgb } from './jarvis-motion';

/** Contraste WCAG entre deux couleurs `#RRGGBB`. */
function contrast(a: string, b: string): number {
  const lum = (hex: string): number => {
    const [r, g, bl] = rgb(hex).map((v) => (v <= 0.03928 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4));
    return 0.2126 * r + 0.7152 * g + 0.0722 * bl;
  };
  const [x, y] = [lum(a), lum(b)];
  return (Math.max(x, y) + 0.05) / (Math.min(x, y) + 0.05);
}

describe('F-236 / SF-236-01 — jarvis-motion (DESIGN_SYSTEM §23)', () => {
  it('goût du PO V1 : la teinte dominante est l’orange de la marque, l’attente est en cyan', () => {
    expect(HOLO_COLORS.normal).toBe('#E07B39');
    expect(HOLO_COLORS.text).toBe('#F0954F');
    expect(HOLO_COLORS.wait).toBe('#5FD4FF');
    expect(HOLO_COLORS.fog).toBe('#0B1020');
  });

  it('chaque couleur d’état a une teinte distincte (une signification chacune)', () => {
    const states = [HOLO_COLORS.normal, HOLO_COLORS.deep, HOLO_COLORS.green, HOLO_COLORS.wait, HOLO_COLORS.alert,
      HOLO_COLORS.off];
    expect(new Set(states).size).toBe(states.length);
  });

  it('tout texte d’état sur navy tient ≥ 5,8:1 (§7.3 règle 9)', () => {
    for (const color of [HOLO_COLORS.text, HOLO_COLORS.core, HOLO_COLORS.deep, HOLO_COLORS.green, HOLO_COLORS.wait,
      HOLO_COLORS.alert]) {
      expect(contrast(color, HOLO_COLORS.fog)).withContext(color).toBeGreaterThanOrEqual(5.8);
    }
  });

  it('cinq paliers de transparence, croissants', () => {
    expect(Object.values(HOLO_ALPHA)).toEqual([0.04, 0.18, 0.45, 0.75, 1]);
  });

  it('les courbes valent 0 au départ, 1 à l’arrivée, et sont monotones (sauf « rebond », qui dépasse de peu)', () => {
    for (const name of Object.keys(MOTION_CURVES) as MotionCurve[]) {
      expect(ease(name, 0)).toBe(0);
      expect(ease(name, 1)).toBe(1);
      let previous = 0;
      let max = 0;
      for (let i = 1; i <= 20; i++) {
        const v = ease(name, i / 20);
        max = Math.max(max, v);
        if (name !== 'rebond') {
          expect(v).withContext(`${name} @ ${i / 20}`).toBeGreaterThanOrEqual(previous - 1e-9);
        }
        previous = v;
      }
      expect(max).toBeLessThan(1.1);
    }
    expect(ease('glisse', 0.5)).toBeCloseTo(0.5, 3);
  });

  it('expose la palette et le mouvement en propriétés CSS (une seule source)', () => {
    const vars = holoCssVars();
    expect(vars['--cg-holo']).toBe(HOLO_COLORS.normal);
    expect(vars['--cg-holo-wait']).toBe(HOLO_COLORS.wait);
    expect(vars['--jv-ease-glisse']).toBe(cssCurve('glisse'));
    expect(vars['--jv-ease-glisse']).toBe('cubic-bezier(0.65, 0, 0.35, 1)');
    expect(vars['--jv-dur-fade']).toBe(`${MOTION_DURATIONS.fade}ms`);
    expect(MOTION_DURATIONS.fade).toBe(400);
  });
});
