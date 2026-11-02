import { frozenClock, readBanc, seededRandom } from './jarvis-banc';

describe('F-236 / SF-236-01 — banc de captures (T12)', () => {
  const q = (s: string): URLSearchParams => new URLSearchParams(s);

  it('inopérant en production', () => {
    expect(readBanc(q('?banc=coeur&t=1200'), false)).toBeNull();
  });

  it('absent sans ?banc', () => {
    expect(readBanc(q('?sujet=abc'), true)).toBeNull();
    expect(readBanc(null, true)).toBeNull();
  });

  it('lit la scène et l’instant ; scène inconnue ⇒ coeur ; instant invalide ⇒ 0', () => {
    expect(readBanc(q('?banc=coeur&t=1200'), true)).toEqual({ scene: 'coeur', t: 1200, level: null });
    expect(readBanc(q('?banc=<script>&t=-5'), true)).toEqual({ scene: 'coeur', t: 0, level: null });
    expect(readBanc(q('?banc=coeur&t=abc&niveau=zzz'), true)).toEqual({ scene: 'coeur', t: 0, level: null });
  });

  it('SF-236-27 — les scènes de l’itération 4 sont lues (elles ne retombent pas sur le cœur)', () => {
    for (const scene of ['cockpit-replay', 'presentation', 'veilleur', 'ambiance-radar', 'cockpit-activite']) {
      expect(readBanc(q(`?banc=${scene}&t=1200`), true)?.scene).withContext(scene).toBe(scene);
    }
  });

  it('niveau de qualité imposé : haut, moyen, bas', () => {
    expect(readBanc(q('?banc=coeur&niveau=haut'), true)?.level).toBe('high');
    expect(readBanc(q('?banc=coeur&niveau=moyen'), true)?.level).toBe('medium');
    expect(readBanc(q('?banc=coeur&niveau=bas'), true)?.level).toBe('low');
    expect(readBanc(q('?banc=coeur&niveau=constructor'), true)?.level).toBeNull();
  });

  it('horloge figée et hasard graine : mêmes paramètres ⇒ même suite', () => {
    expect(frozenClock(1500)()).toBe(1500);
    const a = seededRandom(0x236);
    const b = seededRandom(0x236);
    const first = [a(), a(), a()];
    expect([b(), b(), b()]).toEqual(first);
    expect(first.every((v) => v >= 0 && v < 1)).toBeTrue();
    expect(seededRandom(1)()).not.toBe(first[0]);
  });
});
