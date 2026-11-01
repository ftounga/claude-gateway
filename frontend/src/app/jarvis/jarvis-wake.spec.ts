import {
  END_SILENCE_FRAMES, MIN_WORD_FRAMES, WAKE_RATE, WordSegmenter, downsample, dtw, enrollThreshold, frameEnergies,
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

  it('mesure l’énergie par trame de 10 ms', () => {
    expect(frameEnergies(new Float32Array(1_600)).length).toBe(10);
  });
});
