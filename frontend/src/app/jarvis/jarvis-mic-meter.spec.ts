import { END_OF_SPEECH_MS, EndOfSpeech, MAX_SILENCE_MS, MIN_SILENCE_MS, SILENCE_MS_KEY, silenceMs } from './jarvis-mic-meter';

/** La fin de phrase au silence (F-231 / SF-231-02), réglée à 0,7 s par F-233 / SF-233-02 (D6). */
describe('Fin de phrase au silence (F-233 / SF-233-02)', () => {
  afterEach(() => {
    try {
      localStorage.removeItem(SILENCE_MS_KEY);
    } catch {
      // ignoré
    }
  });

  it('le silence par défaut est de 1,2 s (F-239 / SF-239-10)', () => {
    expect(END_OF_SPEECH_MS).toBe(1_200);
    expect(silenceMs()).toBe(1_200);
    const end = new EndOfSpeech(0);
    expect(end.update(0.5, 10)).toBeFalse();
    expect(end.update(0, 100)).toBeFalse();
    expect(end.update(0, 1_299)).toBeFalse();
    expect(end.update(0, 1_300)).toBeTrue();
  });

  it('le réglage de l’appareil est borné, et une valeur illisible retombe sur le défaut', () => {
    localStorage.setItem(SILENCE_MS_KEY, '50');
    expect(silenceMs()).toBe(MIN_SILENCE_MS);
    localStorage.setItem(SILENCE_MS_KEY, '99999');
    expect(silenceMs()).toBe(MAX_SILENCE_MS);
    localStorage.setItem(SILENCE_MS_KEY, '1000');
    expect(silenceMs()).toBe(1000);
    localStorage.setItem(SILENCE_MS_KEY, 'beaucoup');
    expect(silenceMs()).toBe(END_OF_SPEECH_MS);
    const end = new EndOfSpeech(0, 1000);
    end.update(0.5, 0);
    end.update(0, 10);
    expect(end.update(0, 900)).toBeFalse();
    expect(end.update(0, 1010)).toBeTrue();
  });
});
