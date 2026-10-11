import { TestBed } from '@angular/core/testing';

import { FINISH_SOUND_KEY, FinishSoundService } from './finish-sound.service';

describe('FinishSoundService (F-191 / SF-191-03)', () => {
  beforeEach(() => localStorage.removeItem(FINISH_SOUND_KEY));
  afterEach(() => localStorage.removeItem(FINISH_SOUND_KEY));

  function service(): FinishSoundService {
    TestBed.configureTestingModule({});
    return TestBed.inject(FinishSoundService);
  }

  it('est coupé par défaut', () => {
    expect(service().enabled()).toBeFalse();
  });

  it('mémorise l\'activation sur cet appareil, et la retire', () => {
    const s = service();
    s.setEnabled(true);
    expect(s.enabled()).toBeTrue();
    expect(localStorage.getItem(FINISH_SOUND_KEY)).toBe('1');

    s.setEnabled(false);
    expect(localStorage.getItem(FINISH_SOUND_KEY)).toBeNull();
  });

  it('relit le réglage mémorisé', () => {
    localStorage.setItem(FINISH_SOUND_KEY, '1');
    expect(service().enabled()).toBeTrue();
  });

  it('ne joue rien quand il est coupé', () => {
    const s = service();
    const play = spyOn(s, 'play');
    s.playIfEnabled();
    expect(play).not.toHaveBeenCalled();

    s.setEnabled(true);
    s.playIfEnabled();
    expect(play).toHaveBeenCalledTimes(1);
  });

  it('stockage indisponible : coupé, sans erreur', () => {
    spyOn(localStorage, 'getItem').and.throwError('bloqué');
    spyOn(localStorage, 'setItem').and.throwError('bloqué');
    const s = service();
    expect(s.enabled()).toBeFalse();
    expect(() => s.setEnabled(true)).not.toThrow();
    expect(s.enabled()).toBeTrue();
  });

  it('audio indisponible : silence, sans erreur', () => {
    const g = globalThis as unknown as { AudioContext?: unknown; webkitAudioContext?: unknown };
    const saved = g.AudioContext;
    const savedWebkit = g.webkitAudioContext;
    g.AudioContext = undefined;
    g.webkitAudioContext = undefined;
    try {
      expect(() => service().play()).not.toThrow();
    } finally {
      g.AudioContext = saved;
      g.webkitAudioContext = savedWebkit;
    }
  });
});
