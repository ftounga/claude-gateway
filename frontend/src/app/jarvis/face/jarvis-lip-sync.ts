import {
  Alignment, MouthSample, MouthShape, VISEMES, VisemeEvent, mouthSample, sampleAmplitude, sampleVisemes,
  visemesFromAlignment,
} from './jarvis-visemes';

/** Ce que les lèvres lisent de la voix (le service de voix de l'écran, ou un double au banc). */
export interface LipSource {
  /** Position de lecture de la phrase en cours (s, `audio.currentTime`), −1 en silence. */
  playhead(): number;
  /** Alignement de la phrase en cours (rempli au fil de l'arrivée), ou `null`. */
  alignment(): Alignment | null;
  /** Niveau sonore (0-1). */
  level(): number;
}

/**
 * F-250 / SF-250-06 — **les lèvres dans l'écran** : la frise de visèmes de la phrase en cours, échantillonnée sur
 * l'horloge audio, sinon le niveau sonore (repli amplitude : voix OpenAI, voix locale, clip sans alignement, flux
 * interrompu ou mal formé). Aucune allocation par image ; la frise n'est recalculée que quand l'alignement grandit.
 */
export class LipSync {
  private events: readonly VisemeEvent[] = [];
  private source: Alignment | null = null;
  private seen = -1;
  private readonly sample: MouthSample = mouthSample();
  /** Ce que la dernière image a utilisé : `visemes`, `amplitude` ou `silence` (banc, télémétrie). */
  mode: 'visemes' | 'amplitude' | 'silence' = 'silence';

  constructor(private readonly voice: LipSource) {}

  /** La bouche à l'instant, écrite dans `out`. */
  read(out: MouthShape): void {
    const at = this.voice.playhead();
    let shape: MouthSample;
    if (at < 0) {
      this.mode = 'silence';
      shape = this.rest();
    } else {
      const alignment = this.voice.alignment();
      if (alignment && alignment.chars.length > 0) {
        if (alignment !== this.source || alignment.chars.length !== this.seen) {
          this.source = alignment;
          this.seen = alignment.chars.length;
          this.events = safeEvents(alignment);
        }
      }
      if (alignment && this.events.length > 0 && alignment === this.source) {
        this.mode = 'visemes';
        shape = sampleVisemes(this.events, at, this.sample);
      } else {
        this.mode = 'amplitude';
        shape = sampleAmplitude(this.voice.level(), this.sample);
      }
    }
    out.open = shape.open;
    out.wide = shape.wide;
    out.round = shape.round;
    out.press = shape.press;
  }

  /** Le visème qui domine (télémétrie), `SIL` hors visèmes. */
  get viseme(): string {
    return this.mode === 'visemes' ? this.sample.id : 'SIL';
  }

  private rest(): MouthSample {
    const s = this.sample;
    s.open = VISEMES.SIL.open;
    s.wide = 0;
    s.round = 0;
    s.press = VISEMES.SIL.press;
    s.id = 'SIL';
    return s;
  }
}

/** Une frise sûre : un alignement mal formé (longueurs, temps non finis) donne une frise vide (repli amplitude). */
function safeEvents(alignment: Alignment): readonly VisemeEvent[] {
  const { chars, starts, ends } = alignment;
  if (chars.length !== starts.length || starts.length !== ends.length) {
    return [];
  }
  for (let k = 0; k < starts.length; k++) {
    if (!Number.isFinite(starts[k]) || !Number.isFinite(ends[k])) {
      return [];
    }
  }
  try {
    return visemesFromAlignment(alignment);
  } catch {
    return [];
  }
}
