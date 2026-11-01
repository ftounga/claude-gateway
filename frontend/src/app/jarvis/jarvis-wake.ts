/**
 * **« Jarvis… » — le mot d'éveil, sur l'appareil** (F-231 / SF-231-05, D5).
 *
 * <p><b>Aucun son ne sort avant le mot</b> : tout se passe dans le navigateur. Le micro est analysé localement ;
 * rien n'est envoyé, rien n'est enregistré. Coût : 0 €.</p>
 *
 * <p><b>Le moteur</b> (`WakeWordEngine`, remplaçable) : une reconnaissance par <b>gabarits appris</b> — le PO dit
 * « Jarvis » trois fois, l'écran en garde l'empreinte (coefficients cepstraux, MFCC) ; ensuite, chaque mot isolé
 * entendu est comparé aux gabarits par alignement temporel (DTW). Technique classique, sans modèle à télécharger
 * ni licence ; adaptée à une voix, un appareil. Un moteur neuronal pourra la remplacer derrière la même interface.</p>
 */

/** Fréquence de travail des empreintes. */
export const WAKE_RATE = 16_000;
const FRAME = 400; // 25 ms
const HOP = 160; // 10 ms
const FFT_SIZE = 512;
const MEL_BANDS = 26;
const CEPSTRA = 12;

/** Un mot isolé dure entre ces bornes (en trames de 10 ms). */
export const MIN_WORD_FRAMES = 20;
export const MAX_WORD_FRAMES = 150;
/** Autant de trames calmes closent un mot. */
export const END_SILENCE_FRAMES = 25;

/** Les gabarits appris et le seuil d'acceptation, gardés sur l'appareil seulement. */
export interface WakeProfile {
  templates: number[][][];
  threshold: number;
}

export const WAKE_PROFILE_KEY = 'cg.jarvis.wake';
/** Nombre de répétitions demandées à l'apprentissage. */
export const ENROLL_COUNT = 3;

/** Le moteur du mot d'éveil : n'importe quelle implémentation sur l'appareil. */
export interface WakeWordEngine {
  start(onWake: () => void): Promise<void>;
  stop(): void;
  readonly running: boolean;
}

// ---------------------------------------------------------------- empreinte (MFCC)

/** Ramène un signal de `rate` Hz à 16 kHz (moyenne par paquets — suffisant pour la voix). */
export function downsample(input: Float32Array, rate: number): Float32Array {
  const factor = rate / WAKE_RATE;
  if (factor <= 1) {
    return input;
  }
  const length = Math.floor(input.length / factor);
  const out = new Float32Array(length);
  for (let i = 0; i < length; i++) {
    const start = Math.floor(i * factor);
    const end = Math.min(input.length, Math.floor((i + 1) * factor));
    let sum = 0;
    for (let j = start; j < end; j++) {
      sum += input[j];
    }
    out[i] = sum / Math.max(1, end - start);
  }
  return out;
}

/** L'énergie (RMS) de chaque trame de 10 ms. */
export function frameEnergies(samples: Float32Array): number[] {
  const energies: number[] = [];
  for (let start = 0; start + HOP <= samples.length; start += HOP) {
    let sum = 0;
    for (let i = start; i < start + HOP; i++) {
      sum += samples[i] * samples[i];
    }
    energies.push(Math.sqrt(sum / HOP));
  }
  return energies;
}

let melCache: number[][] | null = null;

function melFilters(): number[][] {
  if (melCache) {
    return melCache;
  }
  const toMel = (f: number) => 2595 * Math.log10(1 + f / 700);
  const fromMel = (m: number) => 700 * (10 ** (m / 2595) - 1);
  const maxMel = toMel(WAKE_RATE / 2);
  const points = Array.from({ length: MEL_BANDS + 2 }, (_, i) => fromMel((maxMel * i) / (MEL_BANDS + 1)));
  const bins = points.map((f) => Math.floor(((FFT_SIZE + 1) * f) / WAKE_RATE));
  const filters: number[][] = [];
  for (let m = 1; m <= MEL_BANDS; m++) {
    const filter = new Array(FFT_SIZE / 2 + 1).fill(0);
    for (let k = bins[m - 1]; k < bins[m]; k++) {
      filter[k] = (k - bins[m - 1]) / Math.max(1, bins[m] - bins[m - 1]);
    }
    for (let k = bins[m]; k < bins[m + 1]; k++) {
      filter[k] = (bins[m + 1] - k) / Math.max(1, bins[m + 1] - bins[m]);
    }
    filters.push(filter);
  }
  melCache = filters;
  return filters;
}

/** Spectre de puissance d'une trame (FFT radix-2 en place). */
function powerSpectrum(frame: Float32Array): number[] {
  const re = new Float64Array(FFT_SIZE);
  const im = new Float64Array(FFT_SIZE);
  for (let i = 0; i < frame.length && i < FFT_SIZE; i++) {
    re[i] = frame[i];
  }
  for (let i = 1, j = 0; i < FFT_SIZE; i++) {
    let bit = FFT_SIZE >> 1;
    for (; j & bit; bit >>= 1) {
      j ^= bit;
    }
    j ^= bit;
    if (i < j) {
      [re[i], re[j]] = [re[j], re[i]];
    }
  }
  for (let len = 2; len <= FFT_SIZE; len <<= 1) {
    const angle = (-2 * Math.PI) / len;
    for (let i = 0; i < FFT_SIZE; i += len) {
      for (let k = 0; k < len / 2; k++) {
        const wr = Math.cos(angle * k);
        const wi = Math.sin(angle * k);
        const ar = re[i + k + len / 2];
        const ai = im[i + k + len / 2];
        const tr = ar * wr - ai * wi;
        const ti = ar * wi + ai * wr;
        re[i + k + len / 2] = re[i + k] - tr;
        im[i + k + len / 2] = im[i + k] - ti;
        re[i + k] += tr;
        im[i + k] += ti;
      }
    }
  }
  const power: number[] = [];
  for (let k = 0; k <= FFT_SIZE / 2; k++) {
    power.push((re[k] * re[k] + im[k] * im[k]) / FFT_SIZE);
  }
  return power;
}

/**
 * L'empreinte d'un mot : 12 coefficients cepstraux par trame de 25 ms (pas de 10 ms), centrés (moyenne retirée)
 * pour ne pas dépendre du micro ni du volume.
 */
export function mfcc(samples: Float32Array): number[][] {
  const filters = melFilters();
  const window = Array.from({ length: FRAME }, (_, i) => 0.54 - 0.46 * Math.cos((2 * Math.PI * i) / (FRAME - 1)));
  const frames: number[][] = [];
  for (let start = 0; start + FRAME <= samples.length; start += HOP) {
    const frame = new Float32Array(FRAME);
    for (let i = 0; i < FRAME; i++) {
      const previous = start + i > 0 ? samples[start + i - 1] : 0;
      frame[i] = (samples[start + i] - 0.97 * previous) * window[i];
    }
    const power = powerSpectrum(frame);
    const logMel = filters.map((f) => Math.log(1e-10 + f.reduce((acc, w, k) => acc + w * power[k], 0)));
    const coeffs: number[] = [];
    for (let c = 1; c <= CEPSTRA; c++) {
      let sum = 0;
      for (let m = 0; m < MEL_BANDS; m++) {
        sum += logMel[m] * Math.cos((Math.PI * c * (m + 0.5)) / MEL_BANDS);
      }
      coeffs.push(sum);
    }
    frames.push(coeffs);
  }
  if (frames.length === 0) {
    return frames;
  }
  const mean = new Array(CEPSTRA).fill(0);
  for (const f of frames) {
    f.forEach((v, i) => (mean[i] += v / frames.length));
  }
  return frames.map((f) => f.map((v, i) => v - mean[i]));
}

/** Distance d'alignement temporel (DTW) entre deux empreintes, rapportée à la longueur du chemin. */
export function dtw(a: number[][], b: number[][]): number {
  if (a.length === 0 || b.length === 0) {
    return Number.POSITIVE_INFINITY;
  }
  const n = a.length;
  const m = b.length;
  let previous = new Float64Array(m + 1).fill(Number.POSITIVE_INFINITY);
  let current = new Float64Array(m + 1);
  previous[0] = 0;
  for (let i = 1; i <= n; i++) {
    current.fill(Number.POSITIVE_INFINITY);
    for (let j = 1; j <= m; j++) {
      let d = 0;
      const x = a[i - 1];
      const y = b[j - 1];
      for (let k = 0; k < x.length; k++) {
        d += (x[k] - y[k]) ** 2;
      }
      current[j] = Math.sqrt(d) + Math.min(previous[j], current[j - 1], previous[j - 1]);
    }
    [previous, current] = [current, previous];
  }
  return previous[m] / (n + m);
}

/** Le seuil d'acceptation : l'écart le plus grand entre les gabarits appris, avec une marge. */
export function enrollThreshold(templates: number[][][]): number {
  let worst = 0;
  for (let i = 0; i < templates.length; i++) {
    for (let j = i + 1; j < templates.length; j++) {
      worst = Math.max(worst, dtw(templates[i], templates[j]));
    }
  }
  return worst * 1.35;
}

/** Le mot entendu ressemble-t-il à « Jarvis » ? */
export function matchesProfile(features: number[][], profile: WakeProfile): boolean {
  if (features.length < MIN_WORD_FRAMES || profile.templates.length === 0) {
    return false;
  }
  const best = Math.min(...profile.templates.map((t) => dtw(features, t)));
  return best <= profile.threshold;
}

// ---------------------------------------------------------------- découpe des mots

/**
 * **La découpe des mots isolés** — pur : on lui donne l'énergie de chaque trame de 10 ms, il dit où commence un mot
 * et quand il est fini. Le bruit de fond est suivi (plancher adaptatif) ; un mot = de l'énergie au-dessus de trois
 * fois le plancher, entre 0,2 et 1,5 s, suivi de 0,25 s de calme.
 */
export class WordSegmenter {
  private floor = 0.004;
  private start: number | null = null;
  private quiet = 0;
  private index = 0;

  /** Rend `[début, fin]` (en trames) quand un mot vient de finir, sinon `null`. */
  push(energy: number): [number, number] | null {
    const i = this.index++;
    const speech = energy > Math.max(0.012, this.floor * 3);
    if (!speech && this.start === null) {
      this.floor = this.floor * 0.95 + energy * 0.05;
    }
    if (speech) {
      this.start ??= i;
      this.quiet = 0;
      if (i - this.start > MAX_WORD_FRAMES) {
        // Trop long pour un mot : une phrase. On l'oublie et on attend le calme.
        this.start = Number.NEGATIVE_INFINITY;
      }
      return null;
    }
    if (this.start === null) {
      return null;
    }
    this.quiet++;
    if (this.quiet < END_SILENCE_FRAMES) {
      return null;
    }
    const begin = this.start;
    const end = i - this.quiet + 1;
    this.start = null;
    this.quiet = 0;
    if (!Number.isFinite(begin) || end - begin < MIN_WORD_FRAMES) {
      return null;
    }
    return [begin, end];
  }

  /** Numéro de la prochaine trame. */
  get position(): number {
    return this.index;
  }
}

// ---------------------------------------------------------------- profil

export function readWakeProfile(): WakeProfile | null {
  try {
    const raw = localStorage.getItem(WAKE_PROFILE_KEY);
    const parsed = raw ? JSON.parse(raw) as WakeProfile : null;
    return parsed && Array.isArray(parsed.templates) && parsed.templates.length > 0
      && typeof parsed.threshold === 'number' ? parsed : null;
  } catch {
    return null;
  }
}

export function saveWakeProfile(profile: WakeProfile): void {
  try {
    localStorage.setItem(WAKE_PROFILE_KEY, JSON.stringify({
      templates: profile.templates.map((t) => t.map((f) => f.map((v) => Math.round(v * 1000) / 1000))),
      threshold: profile.threshold,
    }));
  } catch {
    // Stockage indisponible : le mot d'éveil vaudra pour cette ouverture.
  }
}

// ---------------------------------------------------------------- l'écoute

/**
 * **L'écoute du mot d'éveil** (moteur par gabarits). Ouvre le micro (annulation d'écho et réduction de bruit du
 * navigateur), découpe les mots isolés, compare chacun aux gabarits. En **apprentissage**, chaque mot isolé est rendu
 * tel quel (son empreinte) au lieu d'être comparé.
 */
export class TemplateWakeWordEngine implements WakeWordEngine {
  private context: AudioContext | null = null;
  private stream: MediaStream | null = null;
  private processor: ScriptProcessorNode | null = null;
  private onWord: ((features: number[][]) => void) | null = null;

  constructor(private readonly profile: () => WakeProfile | null) {}

  get running(): boolean {
    return this.context !== null;
  }

  /** Écoute, et appelle `onWake` à chaque « Jarvis » reconnu. */
  start(onWake: () => void): Promise<void> {
    return this.open((features) => {
      const profile = this.profile();
      if (profile && matchesProfile(features, profile)) {
        onWake();
      }
    });
  }

  /** Apprentissage : chaque mot isolé entendu est rendu par son empreinte. */
  enroll(onWord: (features: number[][]) => void): Promise<void> {
    return this.open(onWord);
  }

  stop(): void {
    this.processor?.disconnect();
    this.stream?.getTracks().forEach((t) => t.stop());
    void this.context?.close().catch(() => undefined);
    this.processor = null;
    this.stream = null;
    this.context = null;
    this.onWord = null;
  }

  private async open(onWord: (features: number[][]) => void): Promise<void> {
    this.stop();
    this.onWord = onWord;
    const stream = await navigator.mediaDevices.getUserMedia({
      audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true },
    });
    const Ctor = window.AudioContext ?? (window as unknown as { webkitAudioContext?: typeof AudioContext }).webkitAudioContext;
    const context = new Ctor();
    const source = context.createMediaStreamSource(stream);
    const processor = context.createScriptProcessor(4096, 1, 1);
    const segmenter = new WordSegmenter();
    // Une seconde et demie de signal à 16 kHz en mémoire, pas plus : de quoi reprendre le mot qui vient de finir.
    const keep = WAKE_RATE * 2;
    let buffer = new Float32Array(0);
    let bufferStartFrame = 0;
    let carry = new Float32Array(0);
    processor.onaudioprocess = (event) => {
      const input = downsample(event.inputBuffer.getChannelData(0), context.sampleRate);
      const joined = concat(carry, input);
      const usable = joined.length - (joined.length % HOP);
      carry = joined.slice(usable);
      const chunk = joined.slice(0, usable);
      buffer = concat(buffer, chunk);
      for (const energy of frameEnergies(chunk)) {
        const word = segmenter.push(energy);
        if (word) {
          const from = Math.max(0, (word[0] - 5 - bufferStartFrame) * HOP);
          const to = Math.max(from, (word[1] + 3 - bufferStartFrame) * HOP);
          const features = mfcc(buffer.slice(from, Math.min(buffer.length, to)));
          this.onWord?.(features);
        }
      }
      if (buffer.length > keep) {
        const drop = buffer.length - keep - ((buffer.length - keep) % HOP);
        buffer = buffer.slice(drop);
        bufferStartFrame += drop / HOP;
      }
    };
    source.connect(processor);
    // Le nœud doit être relié à la sortie pour tourner ; il n'écrit rien (silence).
    processor.connect(context.destination);
    this.context = context;
    this.stream = stream;
    this.processor = processor;
  }
}

function concat(a: Float32Array, b: Float32Array): Float32Array {
  const out = new Float32Array(a.length + b.length);
  out.set(a, 0);
  out.set(b, a.length);
  return out;
}
