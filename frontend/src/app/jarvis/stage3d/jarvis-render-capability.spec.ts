import {
  ContextLossPolicy, JARVIS_VIEW_KEY, RenderContext, RenderDecision, RenderProbe, canOffer3d, decideRender,
  readManual2d, startLevel, writeManual2d,
} from './jarvis-render-capability';

const PC: RenderProbe = {
  webgl2: true, majorPerformanceCaveat: false, reducedMotion: false, saveData: false, pointerFine: true, width: 1920,
  deviceMemory: 8, hardwareConcurrency: 8,
};
const CTX: RenderContext = { sober: false, manual2d: false, contextLostTwice: false, banc: false };

describe('F-236 / SF-236-01 — capacité : 3D ou 2D (T10)', () => {
  it('table de vérité : chaque cause seule ⇒ 2D, avec sa raison', () => {
    const cases: [Partial<RenderProbe>, Partial<RenderContext>, string][] = [
      [{ webgl2: false }, {}, 'no_webgl'],
      [{ majorPerformanceCaveat: true }, {}, 'perf_caveat'],
      [{ reducedMotion: true }, {}, 'reduced_motion'],
      [{ saveData: true }, {}, 'save_data'],
      [{}, { sober: true }, 'sober'],
      [{}, { manual2d: true }, 'manual'],
      [{}, { contextLostTwice: true }, 'context_lost'],
    ];
    for (const [probe, ctx, reason] of cases) {
      expect(decideRender({ ...PC, ...probe }, { ...CTX, ...ctx }))
        .withContext(reason).toEqual({ mode: '2d', reason } as RenderDecision);
    }
  });

  it('aucune cause ⇒ 3D', () => {
    expect(decideRender(PC, CTX)).toEqual({ mode: '3d', level: 'high' });
  });

  it('le banc admet le rendu logiciel (captures headless), jamais l’absence de WebGL2', () => {
    expect(decideRender({ ...PC, majorPerformanceCaveat: true }, { ...CTX, banc: true }).mode).toBe('3d');
    expect(decideRender({ ...PC, webgl2: false }, { ...CTX, banc: true }).mode).toBe('2d');
  });

  it('niveau de départ : Haut (pointeur fin, ≥ 1024 px), Bas (≤ 3 Go ou ≤ 4 cœurs), Moyen sinon', () => {
    expect(startLevel(PC)).toBe('high');
    expect(startLevel({ ...PC, pointerFine: false, width: 412 })).toBe('medium');
    expect(startLevel({ ...PC, width: 900 })).toBe('medium');
    expect(startLevel({ ...PC, deviceMemory: 3 })).toBe('low');
    expect(startLevel({ ...PC, hardwareConcurrency: 4 })).toBe('low');
    expect(startLevel({ ...PC, deviceMemory: null, hardwareConcurrency: null })).toBe('high');
  });

  it('le bouton « Vue 3D / 2D » n’est proposé que si la 3D est possible', () => {
    expect(canOffer3d(PC, false)).toBeTrue();
    expect(canOffer3d({ ...PC, webgl2: false }, false)).toBeFalse();
    expect(canOffer3d({ ...PC, majorPerformanceCaveat: true }, false)).toBeFalse();
    expect(canOffer3d({ ...PC, majorPerformanceCaveat: true }, true)).toBeTrue();
    expect(canOffer3d({ ...PC, reducedMotion: true }, false)).toBeFalse();
    expect(canOffer3d({ ...PC, saveData: true }, false)).toBeFalse();
  });

  it('T11 : première perte ⇒ restauration tentée ; seconde ⇒ 2D pour la session', () => {
    const policy = new ContextLossPolicy();
    expect(policy.lostTwice).toBeFalse();
    expect(policy.lost()).toBe('retry');
    expect(policy.lost()).toBe('stay2d');
    expect(policy.lostTwice).toBeTrue();
  });

  it('la préférence « Vue 2D » est retenue par appareil, et survit à un stockage indisponible', () => {
    writeManual2d(true);
    expect(localStorage.getItem(JARVIS_VIEW_KEY)).toBe('2d');
    expect(readManual2d()).toBeTrue();
    writeManual2d(false);
    expect(readManual2d()).toBeFalse();
    spyOn(Storage.prototype, 'getItem').and.throwError('bloqué');
    spyOn(Storage.prototype, 'setItem').and.throwError('bloqué');
    expect(readManual2d()).toBeFalse();
    expect(() => writeManual2d(true)).not.toThrow();
  });
});
