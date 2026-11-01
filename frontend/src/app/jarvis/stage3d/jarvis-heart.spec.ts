import {
  HEART_LOOKS, HEART_MOODS, HEART_TRANSITIONS, SLEEP_AFTER_MS, SleepWatch, heartMood, waveAmplitude,
} from './jarvis-heart';
import { HOLO_COLORS, MOTION_DURATIONS } from './jarvis-motion';

describe('F-236 / SF-236-02 — le cœur et ses états', () => {
  it('chaque humeur a un rendu distinct', () => {
    const signatures = HEART_MOODS.map((m) => JSON.stringify(HEART_LOOKS[m]));
    expect(new Set(signatures).size).toBe(HEART_MOODS.length);
  });

  it('priorité : coupure > « oui » attendu > veille > état de l’écran', () => {
    expect(heartMood({ state: 'parole', frozen: true, waiting: true, asleep: true })).toBe('coupure');
    expect(heartMood({ state: 'reflexion', frozen: false, waiting: true, asleep: true })).toBe('oui');
    expect(heartMood({ state: 'repos', frozen: false, waiting: false, asleep: true })).toBe('veille');
    expect(heartMood({ state: 'ecoute', frozen: false, waiting: false, asleep: true })).toBe('ecoute');
    expect(heartMood({ state: 'parole', frozen: false, waiting: false, asleep: false })).toBe('parole');
  });

  it('grammaire §23 (goût PO V1) : normal orange, « oui » attendu en cyan (l’attente)', () => {
    expect(HOLO_COLORS[HEART_LOOKS.repos.color]).toBe('#E07B39');
    expect(HOLO_COLORS[HEART_LOOKS.oui.color]).toBe('#5FD4FF');
  });

  it('écoute : ondes entrantes, anneaux face à la caméra ; parole : ondes sortantes et rayons ; coupure : gel', () => {
    expect(HEART_LOOKS.ecoute.waveDir).toBe(-1);
    expect(HEART_LOOKS.ecoute.facing).toBe(1);
    expect(HEART_LOOKS.parole.waveDir).toBe(1);
    expect(HEART_LOOKS.parole.rays).toBe(1);
    expect(HEART_LOOKS.coupure.frozen).toBeTrue();
    expect(HEART_LOOKS.coupure.glow).toBeCloseTo(0.5, 5);
    expect(HEART_LOOKS.veille.glow).toBeCloseTo(0.3, 5);
  });

  it('ondes ∝ niveau (micro ou voix), nulles hors écoute / parole, bornées 0-1', () => {
    expect(waveAmplitude('ecoute', 0.2)).toBeCloseTo(0.2, 6);
    expect(waveAmplitude('ecoute', 0.8)).toBeCloseTo(0.8, 6);
    expect(waveAmplitude('parole', 0.5)).toBeCloseTo(0.5, 6);
    expect(waveAmplitude('parole', 4)).toBe(1);
    expect(waveAmplitude('ecoute', Number.NaN)).toBe(0);
    expect(waveAmplitude('repos', 0.9)).toBe(0);
    expect(waveAmplitude('reflexion', 0.9)).toBe(0);
  });

  it('transitions du catalogue §7.4 : T-C1 0,35 s, T-C2 0,6 s, T-C3 0,25 s, T-C4 1,2 s, T-C5 0,5 s, T-V1 2,5 s', () => {
    expect(HEART_TRANSITIONS.ecoute).toEqual({ ms: 350, curve: 'sortie' });
    expect(HEART_TRANSITIONS.reflexion).toEqual({ ms: 600, curve: 'glisse' });
    expect(HEART_TRANSITIONS.parole).toEqual({ ms: 250, curve: 'sortie' });
    expect(HEART_TRANSITIONS.repos).toEqual({ ms: 1200, curve: 'souffle' });
    expect(HEART_TRANSITIONS.oui).toEqual({ ms: 500, curve: 'glisse' });
    expect(HEART_TRANSITIONS.veille).toEqual({ ms: 2500, curve: 'souffle' });
    expect(MOTION_DURATIONS.wake).toBe(600);
  });

  describe('veille (N3) et réveil (N2)', () => {
    it('60 s sans conversation, au repos ⇒ veille ; une conversation la lève (réveil compté)', () => {
      const w = new SleepWatch();
      expect(w.update(false, 'repos', 0)).toBeFalse();
      expect(w.update(false, 'repos', SLEEP_AFTER_MS - 1)).toBeFalse();
      expect(w.update(false, 'repos', SLEEP_AFTER_MS)).toBeTrue();
      expect(w.wakes).toBe(0);
      expect(w.update(true, 'repos', SLEEP_AFTER_MS + 10)).toBeFalse();
      expect(w.wakes).toBe(1);
    });

    it('un tour en cours repousse la veille', () => {
      const w = new SleepWatch();
      w.update(false, 'repos', 0);
      w.update(false, 'reflexion', 30_000);
      expect(w.update(false, 'repos', 70_000)).toBeFalse();
      expect(w.update(false, 'repos', 130_000)).toBeTrue();
    });

    it('le mot « Jarvis » réveille et joue l’onde de choc, même éveillé', () => {
      const w = new SleepWatch();
      w.update(false, 'repos', 0);
      w.update(false, 'repos', SLEEP_AFTER_MS);
      w.wake(SLEEP_AFTER_MS + 5);
      expect(w.asleep).toBeFalse();
      expect(w.wakes).toBe(1);
      w.wake(SLEEP_AFTER_MS + 9);
      expect(w.wakes).toBe(2);
    });
  });
});
