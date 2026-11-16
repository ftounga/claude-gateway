import { ArchGestures, DRAG_PX, ORBIT_PER_PX, PINCH_STEP } from './jarvis-piece-gestures';
import { archVoiceRequest, defaultArchNav, reduceArch } from './jarvis-piece-arch';

describe('Architecture — gestes (retour PO 2026-11-16, SF-238-11)', () => {
  it('glisser (souris ou doigt) fait pivoter ; un petit tremblement ne fait rien', () => {
    const g = new ArchGestures();
    g.down(1, 100, 100, 0);
    expect(g.move(1, 102, 101)).toEqual({ kind: 'none' });
    const turn = g.move(1, 150, 120);
    expect(turn).toEqual({ kind: 'orbit', dAz: -48 * ORBIT_PER_PX, dEl: 19 * ORBIT_PER_PX });
    expect(g.up(1, 150, 120, 200)).toEqual({ kind: 'none' }); // un glissement n'est pas un toucher
    expect(DRAG_PX).toBeGreaterThan(0);
  });

  it('pincer à deux doigts zoome (écarter) et dézoome (rapprocher), sans faire pivoter', () => {
    const g = new ArchGestures();
    g.down(1, 100, 200, 0);
    g.down(2, 200, 200, 10);
    expect(g.pinching).toBeTrue();
    expect(g.move(2, 220, 200)).toEqual({ kind: 'none' });
    expect(g.move(2, 100 + 100 * PINCH_STEP + 1, 200)).toEqual({ kind: 'zoom', step: '+' });
    expect(g.move(1, 130, 200)).toEqual({ kind: 'none' });
    expect(g.move(1, 200, 200)).toEqual({ kind: 'zoom', step: '-' });
    expect(g.up(2, 0, 0, 300)).toEqual({ kind: 'none' });
    // Le doigt restant ne tourne pas le schéma et sa levée n'est pas un toucher.
    expect(g.move(1, 260, 260)).toEqual({ kind: 'none' });
    expect(g.up(1, 260, 260, 320)).toEqual({ kind: 'none' });
  });

  it('molette : un cran par événement', () => {
    expect(ArchGestures.wheel(-120)).toEqual({ kind: 'zoom', step: '+' });
    expect(ArchGestures.wheel(120)).toEqual({ kind: 'zoom', step: '-' });
    expect(ArchGestures.wheel(0)).toEqual({ kind: 'none' });
  });

  it('toucher bref = viser ; toucher trop long = rien (le toucher long replie, à part)', () => {
    const g = new ArchGestures();
    g.down(1, 10, 10, 0);
    expect(g.up(1, 11, 10, 100)).toEqual({ kind: 'tap', x: 11, y: 10 });
    g.down(1, 10, 10, 0);
    expect(g.up(1, 10, 10, 900)).toEqual({ kind: 'none' });
  });

  it('recentrer : la vue d’ensemble, sans focus ni orbite (bouton et « recentre »)', () => {
    const turned = { ...defaultArchNav(), view: 'face' as const, orbit: { az: 1, el: 0.2 },
      focus: { id: 'rdsa', label: 'RDS primaire' } };
    expect(reduceArch(turned, { kind: 'recenter' })).toEqual({ ...defaultArchNav() });
    expect(archVoiceRequest('Recentre le schéma')).toEqual({ kind: 'recenter' });
  });
});
