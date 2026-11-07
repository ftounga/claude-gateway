import { LipSource, LipSync } from './jarvis-lip-sync';
import { Alignment, LIP_LEAD_S } from './jarvis-visemes';

class FakeVoice implements LipSource {
  at = -1;
  al: Alignment | null = null;
  lvl = 0;
  playhead(): number { return this.at; }
  alignment(): Alignment | null { return this.al; }
  level(): number { return this.lvl; }
}

function aligned(text: string, step = 0.08): Alignment {
  const chars = Array.from(text);
  return { chars, starts: chars.map((_, i) => i * step), ends: chars.map((_, i) => (i + 1) * step) };
}

describe('F-250 / SF-250-06 — les lèvres dans l’écran', () => {
  const out = { open: 0, wide: 0, round: 0, press: 0 };

  it('silence : bouche au repos', () => {
    const lips = new LipSync(new FakeVoice());
    lips.read(out);
    expect(lips.mode).toBe('silence');
    expect(out.open).toBeLessThan(0.05);
  });

  it('voix alignée : la frise est lue sur l’horloge audio (fermeture sur le « m »)', () => {
    const voice = new FakeVoice();
    voice.al = aligned('ma');
    voice.at = 0.04 - LIP_LEAD_S;
    const lips = new LipSync(voice);
    lips.read(out);
    expect(lips.mode).toBe('visemes');
    expect(out.press).toBeGreaterThan(0.6);
    expect(lips.viseme).toBe('PBM');
  });

  it('l’alignement grandit morceau par morceau : la frise suit', () => {
    const voice = new FakeVoice();
    const al: Alignment = { chars: ['m'], starts: [0], ends: [0.08] };
    voice.al = al;
    voice.at = 0.12;
    const lips = new LipSync(voice);
    lips.read(out);
    al.chars.push('a');
    al.starts.push(0.08);
    al.ends.push(0.24);
    lips.read(out);
    expect(out.open).toBeGreaterThan(0.4);
  });

  it('repli amplitude : sans alignement, ou alignement mal formé, la bouche suit le niveau sans erreur', () => {
    const voice = new FakeVoice();
    voice.at = 0.5;
    voice.lvl = 0.5;
    const lips = new LipSync(voice);
    lips.read(out);
    expect(lips.mode).toBe('amplitude');
    expect(out.open).toBeCloseTo(0.55, 2);
    voice.al = { chars: ['a', 'b'], starts: [0], ends: [0.1, Number.NaN] };
    expect(() => lips.read(out)).not.toThrow();
    expect(lips.mode).toBe('amplitude');
  });
});
