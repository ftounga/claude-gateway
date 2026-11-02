import {
  COCKPIT_FOLDS_KEY, COCKPIT_MIN_WIDTH, COCKPIT_PANEL, NO_INSETS, OPEN_FOLDS, cockpitInsets, cockpitMode, readFolds,
  viewShift, writeFolds,
} from './jarvis-cockpit-layout';
import { postChain } from '../jarvis-post-chain';
import { panelViewport } from '../jarvis-panels';

describe('F-236 / SF-236-15 — mise en page du cockpit PC (pur)', () => {
  afterEach(() => localStorage.removeItem(COCKPIT_FOLDS_KEY));

  it('cockpit sur PC large en 3D ; téléphone ; rien en 2D, en sobre ou sur PC étroit', () => {
    const base = { width: 1920, narrow: false, shown3d: true, sober: false };
    expect(cockpitMode(base)).toBe('cockpit');
    expect(cockpitMode({ ...base, width: COCKPIT_MIN_WIDTH })).toBe('cockpit');
    expect(cockpitMode({ ...base, width: COCKPIT_MIN_WIDTH - 1 })).toBe('none');
    expect(cockpitMode({ ...base, narrow: true, width: 390 })).toBe('phone');
    expect(cockpitMode({ ...base, shown3d: false })).toBe('none');
    expect(cockpitMode({ ...base, sober: true })).toBe('none');
  });

  it('panneaux repliés ⇒ univers pleine largeur (aucun décalage) ; un seul ouvert ⇒ centre de la zone libre', () => {
    expect(cockpitInsets('cockpit', { left: true, right: true })).toEqual(NO_INSETS);
    expect(viewShift(cockpitInsets('cockpit', { left: true, right: true }))).toBe(0);
    const open = cockpitInsets('cockpit', OPEN_FOLDS);
    expect(open).toEqual({ left: COCKPIT_PANEL.left + COCKPIT_PANEL.gutter,
      right: COCKPIT_PANEL.right + COCKPIT_PANEL.gutter });
    const leftOnly = cockpitInsets('cockpit', { left: false, right: true });
    expect(viewShift(leftOnly)).toBe(leftOnly.left / 2);
    expect(viewShift(cockpitInsets('cockpit', { left: true, right: false }))).toBeLessThan(0);
    expect(cockpitInsets('none', OPEN_FOLDS)).toEqual(NO_INSETS);
    expect(cockpitInsets('phone', OPEN_FOLDS)).toEqual(NO_INSETS);
  });

  it('le repli est retenu par appareil ; stockage illisible ⇒ panneaux ouverts', () => {
    expect(readFolds()).toEqual(OPEN_FOLDS);
    writeFolds({ left: true, right: false });
    expect(readFolds()).toEqual({ left: true, right: false });
    writeFolds({ left: true, right: true });
    expect(readFolds()).toEqual({ left: true, right: true });
    writeFolds(OPEN_FOLDS);
    expect(localStorage.getItem(COCKPIT_FOLDS_KEY)).toBeNull();
    spyOn(localStorage, 'getItem').and.throwError('bloqué');
    expect(readFolds()).toEqual(OPEN_FOLDS);
  });

  it('chaîne de rendu : profondeur de champ PC Haut ; SMAA et finition PC Haut/Moyen ; rien de cela au téléphone', () => {
    expect(postChain('high', false)).toEqual({ bloom: true, bokeh: true, smaa: true, finish: true });
    expect(postChain('medium', false)).toEqual({ bloom: true, bokeh: false, smaa: true, finish: true });
    expect(postChain('low', false)).toEqual({ bloom: false, bokeh: false, smaa: false, finish: false });
    for (const level of ['high', 'medium', 'low'] as const) {
      const phone = postChain(level, true);
      expect(phone.bokeh || phone.smaa || phone.finish).withContext(level).toBeFalse();
    }
  });

  it('aucun hologramme ne se pose sous un panneau latéral ouvert', () => {
    const core = { x: 960, y: 540, r: 40 };
    const without = panelViewport(1920, 1080, false, core);
    const withSides = panelViewport(1920, 1080, false, core, { left: 316, right: 336 });
    expect(withSides.reserved.length).toBe(without.reserved.length + 2);
    expect(withSides.reserved).toContain({ x: 0, y: 0, w: 316, h: 1080 });
    expect(withSides.reserved).toContain({ x: 1920 - 336, y: 0, w: 336, h: 1080 });
  });
});
