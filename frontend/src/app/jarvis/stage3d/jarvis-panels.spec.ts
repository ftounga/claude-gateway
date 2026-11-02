import {
  PANEL_MARGIN, PANEL_POOL, PanelRequest, Rect, boundPanels, circleHits, intersection, panelLook, panelViewport,
  placePanels,
} from './jarvis-panels';

describe('F-236 / SF-236-08 — panneaux holographiques : pool, placement, T-P1 / T-P2', () => {
  const pc = panelViewport(1920, 1080, false, { x: 960, y: 520, r: 120 });
  const phone = panelViewport(390, 844, true, { x: 195, y: 380, r: 70 });

  function rects(requests: PanelRequest[], vp = pc): Rect[] {
    const spots = placePanels(requests, vp);
    return spots.map((s, i) => ({ x: s.x, y: s.y, w: requests[i].w, h: requests[i].h }));
  }

  it('pool : 8 panneaux au plus sur PC ; téléphone 1 (2 pour S4, SF-236-20 §12.2) ; « devant tout » d’abord, jamais écarté', () => {
    const panels = Array.from({ length: 12 }, (_, i) => ({ id: `p${i}`, front: i === 11 }));
    expect(boundPanels(panels, false).length).toBe(PANEL_POOL.wide);
    expect(boundPanels(panels, true).map((p) => p.id)).toEqual(['p11']);
    expect(boundPanels(panels, true, 'S4').map((p) => p.id)).toEqual(['p11', 'p0']);
  });

  it('jamais sur le cœur, jamais sur les sous-titres ni la barre du haut, toujours dans l’écran', () => {
    const requests: PanelRequest[] = [
      { id: 'coeur', anchor: null, onCore: true, w: 260, h: 70 },
      { id: 'droite', anchor: { x: 1300, y: 400 }, onCore: false, w: 260, h: 90 },
      { id: 'gauche-bas', anchor: { x: 600, y: 700 }, onCore: false, w: 260, h: 90 },
      { id: 'bord', anchor: { x: 1900, y: 90 }, onCore: false, w: 260, h: 90 },
    ];
    for (const r of rects(requests)) {
      expect(circleHits(r, pc.core)).withContext(JSON.stringify(r)).toBeFalse();
      for (const band of pc.reserved) {
        expect(intersection(r, band)).withContext(JSON.stringify(r)).toBe(0);
      }
      expect(r.x).toBeGreaterThanOrEqual(PANEL_MARGIN);
      expect(r.x + r.w).toBeLessThanOrEqual(1920 - PANEL_MARGIN);
    }
  });

  it('deux panneaux ne se recouvrent pas quand la place existe', () => {
    const [a, b] = rects([
      { id: 'a', anchor: { x: 1300, y: 400 }, onCore: false, w: 260, h: 90 },
      { id: 'b', anchor: { x: 1310, y: 410 }, onCore: false, w: 260, h: 90 },
    ]);
    expect(intersection(a, b)).toBe(0);
  });

  it('« devant tout » ancré au cœur : au-dessus du cœur ; téléphone : dans l’écran, hors bande basse', () => {
    const [front] = placePanels([{ id: 's2', anchor: null, onCore: true, w: 240, h: 80, front: true }], pc);
    expect(front.quadrant).toBe('n');
    const [r] = rects([{ id: 's2', anchor: null, onCore: true, w: 240, h: 80, front: true }], phone);
    expect(r.x).toBeGreaterThanOrEqual(PANEL_MARGIN);
    expect(r.x + r.w).toBeLessThanOrEqual(390 - PANEL_MARGIN);
    expect(intersection(r, phone.reserved[1])).toBe(0);
  });

  it('positions entières (texte net)', () => {
    const [s] = placePanels([{ id: 'a', anchor: { x: 1300.4, y: 400.7 }, onCore: false, w: 261, h: 91 }], pc);
    expect(Number.isInteger(s.x) && Number.isInteger(s.y)).toBeTrue();
  });

  it('T-P1 : échelle 0,92 → 1, opacité 0 → 1, balayage 300 ms, filament 250 ms, 90 ms d’écart', () => {
    expect(panelLook('in', 0)).toEqual({ opacity: 0, scale: 0.92, sweep: null, filament: 0 });
    expect(panelLook('in', 150).sweep).toBeCloseTo(0.5, 2);
    expect(panelLook('in', 250).filament).toBe(1);
    const end = panelLook('in', 420);
    expect(end.opacity).toBe(1);
    expect(end.scale).toBeCloseTo(1, 5);
    expect(end.sweep).toBeNull();
    expect(panelLook('in', 90, 1).opacity).toBe(0);
    expect(panelLook('in', 100, 1).opacity).toBeGreaterThan(0);
  });

  it('T-P2 : échelle 1 → 0,96, opacité 1 → 0, filament rétracté, balayage inverse', () => {
    expect(panelLook('out', 0)).toEqual(jasmine.objectContaining({ opacity: 1, scale: 1, filament: 1, sweep: 1 }));
    const end = panelLook('out', 280);
    expect(end.opacity).toBe(0);
    expect(end.scale).toBeCloseTo(0.96, 5);
    expect(end.filament).toBe(0);
  });

  it('aucun saut : entre deux images de 16 ms, l’opacité et l’échelle varient peu (règle §7.3-1)', () => {
    for (let t = 0; t < 420; t += 16) {
      const a = panelLook('in', t);
      const b = panelLook('in', t + 16);
      expect(Math.abs(b.opacity - a.opacity)).toBeLessThan(0.25);
      expect(Math.abs(b.scale - a.scale)).toBeLessThan(0.05);
    }
  });
});
