import {
  END_SILENCE_FRAMES, MIN_WORD_FRAMES, PHRASE_GAP_FRAMES, WAKE_RATE, WordSegmenter, downsample, dtw, enrollThreshold, frameEnergies,
  matchesProfile, mfcc,
} from './jarvis-wake';

/** Un signal synthétique : une voyelle (deux formants) puis une autre, à 16 kHz. */
function word(f1: number, f2: number, seconds = 0.5): Float32Array {
  const n = Math.round(WAKE_RATE * seconds);
  const out = new Float32Array(n);
  for (let i = 0; i < n; i++) {
    const t = i / WAKE_RATE;
    const f = i < n / 2 ? f1 : f2;
    out[i] = 0.3 * Math.sin(2 * Math.PI * f * t) + 0.2 * Math.sin(2 * Math.PI * (f * 2.7) * t);
  }
  return out;
}

/** **« Jarvis… » sur l'appareil** (F-231 / SF-231-05) : empreinte, alignement, découpe des mots. */
describe('Jarvis — le mot d’éveil sur l’appareil (F-231 / SF-231-05)', () => {
  it('ramène un signal à 16 kHz', () => {
    expect(downsample(new Float32Array(48_000), 48_000).length).toBe(16_000);
    const same = new Float32Array(10);
    expect(downsample(same, 16_000)).toBe(same);
  });

  it('l’empreinte a 12 coefficients par trame de 10 ms', () => {
    const features = mfcc(word(300, 900));
    expect(features.length).toBeGreaterThan(40);
    expect(features[0].length).toBe(12);
  });

  it('un mot est plus proche de lui-même que d’un autre', () => {
    const jarvis = mfcc(word(300, 900));
    const again = mfcc(word(310, 880));
    const other = mfcc(word(1200, 200));
    expect(dtw(jarvis, jarvis)).toBeCloseTo(0, 6);
    expect(dtw(jarvis, again)).toBeLessThan(dtw(jarvis, other));
  });

  it('accepte ce qui ressemble aux gabarits appris, refuse le reste', () => {
    const templates = [mfcc(word(300, 900)), mfcc(word(305, 905)), mfcc(word(295, 895))];
    const profile = { templates, threshold: Math.max(enrollThreshold(templates), 1e-3) };
    expect(matchesProfile(mfcc(word(302, 902)), profile)).toBeTrue();
    expect(matchesProfile(mfcc(word(1200, 200)), profile)).toBeFalse();
    expect(matchesProfile([], profile)).toBeFalse();
  });

  it('découpe un mot isolé : de l’énergie, puis du calme', () => {
    const segmenter = new WordSegmenter();
    let found: [number, number] | null = null;
    const energies = [
      ...new Array(30).fill(0.001), ...new Array(40).fill(0.2), ...new Array(END_SILENCE_FRAMES).fill(0.001),
    ];
    for (const e of energies) {
      found = segmenter.push(e) ?? found;
    }
    expect(found).toEqual([30, 70]);
  });

  it('ignore un claquement trop bref et une phrase trop longue', () => {
    const segmenter = new WordSegmenter();
    const results: ([number, number] | null)[] = [];
    for (const e of [...new Array(MIN_WORD_FRAMES - 5).fill(0.2), ...new Array(END_SILENCE_FRAMES).fill(0.001),
      ...new Array(200).fill(0.2), ...new Array(END_SILENCE_FRAMES).fill(0.001)]) {
      results.push(segmenter.push(e));
    }
    expect(results.filter((r) => r !== null)).toEqual([]);
  });

  it('F-239 — sait si « Jarvis » suit une parole proche (« Bonjour Jarvis ») ou un long calme', () => {
    const quiet = (n: number) => new Array(n).fill(0.001);
    const speech = (n: number) => new Array(n).fill(0.2);
    const run = (energies: number[]) => {
      const segmenter = new WordSegmenter();
      const words: { word: [number, number]; before: number; phrase: number }[] = [];
      for (const e of energies) {
        const found = segmenter.push(e);
        if (found) {
          words.push({ word: found, before: segmenter.speechBeforeWord, phrase: segmenter.phraseStartFrame });
        }
      }
      return { words, segmenter };
    };
    // « Bonjour » (40), 30 trames de calme, « Jarvis » (40) : deux mots, le second précédé de parole.
    const greeting = run([...quiet(30), ...speech(40), ...quiet(30), ...speech(40), ...quiet(END_SILENCE_FRAMES)]);
    expect(greeting.words.length).toBe(2);
    const jarvis = greeting.words[1];
    expect(jarvis.word[0] - jarvis.before).toBeLessThanOrEqual(PHRASE_GAP_FRAMES);
    expect(jarvis.phrase).toBe(30);
    // « Jarvis » après 2 s de calme : rien avant.
    const alone = run([...quiet(30), ...speech(40), ...quiet(200), ...speech(40), ...quiet(END_SILENCE_FRAMES)]);
    const second = alone.words[1];
    expect(second.word[0] - second.before).toBeGreaterThan(PHRASE_GAP_FRAMES);
    expect(second.phrase).toBe(second.word[0]);
    // La parole qui reprend après le mot se voit.
    const wordEnd = second.word[1];
    expect(alone.segmenter.lastSpeechFrame > wordEnd).toBeFalse();
    alone.segmenter.push(0.2);
    expect(alone.segmenter.lastSpeechFrame > wordEnd).toBeTrue();
  });

  it('mesure l’énergie par trame de 10 ms', () => {
    expect(frameEnergies(new Float32Array(1_600)).length).toBe(10);
  });
});
