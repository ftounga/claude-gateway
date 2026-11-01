import { IDLE_SIGNALS, directStage, samePlan } from './jarvis-stage-director';

describe('F-236 / SF-236-02 — directeur de scène (cœur)', () => {
  it('au repos : cœur au repos, aucun réveil', () => {
    expect(directStage(IDLE_SIGNALS)).toEqual(jasmine.objectContaining({ mood: 'repos', wakes: 0 }));
  });

  it('l’état de l’écran et la coupure pilotent l’humeur ; le compteur de réveils passe tel quel', () => {
    expect(directStage({ ...IDLE_SIGNALS, state: 'ecoute' }).mood).toBe('ecoute');
    expect(directStage({ ...IDLE_SIGNALS, state: 'parole', frozen: true }).mood).toBe('coupure');
    expect(directStage({ ...IDLE_SIGNALS, waiting: true }).mood).toBe('oui');
    expect(directStage({ ...IDLE_SIGNALS, asleep: true, wakes: 3 })).toEqual(
      jasmine.objectContaining({ mood: 'veille', wakes: 3 }));
  });

  it('deux plans égaux sont reconnus comme tels', () => {
    expect(samePlan(directStage(IDLE_SIGNALS), directStage(IDLE_SIGNALS))).toBeTrue();
    expect(samePlan(directStage(IDLE_SIGNALS), directStage({ ...IDLE_SIGNALS, wakes: 1 }))).toBeFalse();
  });
});
