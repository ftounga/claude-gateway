import { isNoise } from './jarvis-conversation';

/**
 * **La porte de parole réelle** (F-239 / SF-239-01, D3b) — pure : avant tout traitement d'une parole écoutée, elle
 * dit si quelqu'un a vraiment parlé. Sinon, **aucun tour** : ni cerveau rapide, ni Opus.
 *
 * Constat prod du 2026-11-02 (08:15:18, 110 s d'écoute ; 08:15:55, 21 s) : l'écoute en flux a validé un texte né du
 * bruit, le cerveau rapide l'a traité et a délégué. Les fournisseurs de transcription « entendent » parfois des
 * phrases dans le silence ou le bruit (hallucinations bien connues : « Sous-titres réalisés par… », « Merci. »).
 */

/** Pourquoi une parole est refusée — rapporté dans la mesure du tour, **jamais le texte**. */
export type SpeechRejection = 'empty' | 'noise' | 'hallucination' | 'no_voice' | 'implausible';

/*
 * F-241 / SF-241-06 — **le micro « n'entend pas »** (mesure prod du 2026-11-02 au soir : 6 paroles réelles sur 10
 * refusées, dont 4 « implausible » de 62 à 119 caractères dites en 5 à 12 s). Cause : le temps de voix ne comptait que
 * les trames au-dessus d'un seuil fixe ; sur un micro à gain bas ou à contrôle automatique de gain, la voix réelle
 * était sous-comptée, et une vraie phrase de 15 à 20 mots paraissait « trop rapide ». Désormais :
 * - « implausible » se juge sur la **durée de l'écoute** (débit physiquement impossible), plus sur le temps de voix ;
 * - « no_voice » ne refuse qu'une phrase **très courte** dans une écoute où le micro n'a **presque rien** entendu ;
 * - le temps de voix se compte au-dessus d'un seuil **adaptatif** au bruit de fond ({@link NoiseFloor}).
 * L'objectif anti-hallucination de SF-239-01 tient : les hallucinations connues sont refusées par leur texte, et un
 * long texte né de quelques secondes de silence reste un débit impossible.
 */

/** En dessous (ms de voix au micro), le micro n'a pratiquement rien entendu. */
export const MIN_VOICED_MS = 100;
/** Une parole « sans voix » au micro n'est refusée que si elle est très courte (au plus autant de mots). */
export const NO_VOICE_MAX_WORDS = 4;
/**
 * F-251 / SF-251-03 — à partir d'autant de mots, un texte de la retranscription complète n'est plus refusé « sans voix »
 * (essai du 07/11 : une vraie phrase jetée à 96 ms de voix mesurée).
 */
export const TRANSCRIBED_MIN_WORDS = 2;
/** Débit physiquement impossible : plus de mots par seconde d'écoute que cela, plus une marge fixe. */
export const MAX_WORDS_PER_SECOND = 6;
export const IMPLAUSIBLE_MARGIN_WORDS = 4;
/** Une écoute ne dure jamais plus : au-delà elle se clôt, et la porte décide. */
export const MAX_LISTEN_MS = 30_000;

/** Ce que l'écran sait de l'écoute. */
export interface ListenFacts {
  /**
   * Le temps pendant lequel le micro a entendu une voix (niveau ≥ seuil adaptatif), en ms ; `null` si le niveau n'a
   * pas pu être mesuré (la règle « sans voix » ne s'applique alors pas).
   */
  voicedMs: number | null;
  /**
   * SF-241-06 — la durée de l'écoute (de son ouverture à la fin de la parole, `speech_ms`), en ms : la base du débit
   * « implausible ». Absente : le temps de voix sert de base (ancien comportement).
   */
  listenedMs?: number | null;
  /** Vrai si l'écoute a commencé par l'audio de la phrase qui contenait « Jarvis » (le micro n'en a rien mesuré). */
  preRoll?: boolean;
  /** F-251 / SF-251-03 — le texte vient de la retranscription complète de l'énoncé (pas du seul flux). */
  transcribed?: boolean;
}

/** Le seuil de voix ne descend jamais sous ce niveau (un souffle n'est pas une voix), ni au-dessus du seuil fixe. */
export const MIN_VOICE_LEVEL = 0.03;
/** La voix dépasse le bruit de fond d'au moins autant de fois, plus une marge. */
export const VOICE_OVER_FLOOR = 2.5;
export const VOICE_FLOOR_MARGIN = 0.015;

/**
 * SF-241-06 — **le bruit de fond mesuré** : il descend aussitôt vers un niveau plus calme et ne remonte que très
 * lentement (la voix ne le tire pas vers le haut). Le seuil de voix en découle, borné entre {@link MIN_VOICE_LEVEL} et
 * le seuil fixe (`ceiling`) : un micro à gain bas compte enfin sa voix, un micro normal ne change rien.
 */
export class NoiseFloor {
  private floor: number | null = null;

  constructor(private readonly ceiling: number) {}

  /** Une trame : met à jour le bruit de fond et dit si c'est de la voix. */
  update(level: number): boolean {
    const l = Math.max(0, level);
    if (this.floor === null || l < this.floor) {
      this.floor = l;
    } else {
      this.floor += (l - this.floor) * 0.002;
    }
    return l >= this.voiceLevel();
  }

  voiceLevel(): number {
    const adaptive = (this.floor ?? 0) * VOICE_OVER_FLOOR + VOICE_FLOOR_MARGIN;
    return Math.min(this.ceiling, Math.max(MIN_VOICE_LEVEL, adaptive));
  }
}

/** Minuscules, sans accents, ponctuation en espaces. */
function plain(text: string): string {
  return text.toLowerCase().normalize('NFD').replace(/\p{M}+/gu, '').replace(/[^a-z0-9]+/g, ' ').trim();
}

/**
 * Les hallucinations typiques des transcripteurs sur du silence ou du bruit, **dites seules** (normalisées). Une
 * vraie politesse isolée (« Merci. ») est sacrifiée : elle n'appelle pas de réponse.
 */
const HALLUCINATIONS = new Set([
  'merci', 'merci beaucoup', 'merci a tous', 'merci d avoir regarde', 'merci de votre attention',
  'merci d avoir regarde cette video', 'merci de m avoir ecoute', 'abonnez vous', 'n oubliez pas de vous abonner',
  'sous titres', 'sous titrage', 'sous titres realises par la communaute d amara org',
  'sous titres realises para la communaute d amara org', 'sous titrage st 501', 'sous titrage societe radio canada',
  'musique', 'applaudissements', 'rires', 'silence', 'bruit', 'a bientot', 'au revoir', 'bon appetit',
  'thank you', 'thanks for watching', 'you',
]);

/** Retire les balises d'événements sonores (« (musique) », « [rires] », « *bruit* »). */
function withoutEvents(text: string): string {
  return text.replace(/\([^)]*\)|\[[^\]]*\]|\*[^*]*\*|♪+/g, ' ');
}

/** Rend `null` si la parole est réelle, sinon la raison du refus. */
export function speechGate(said: string | null | undefined, facts: ListenFacts): SpeechRejection | null {
  const raw = (said ?? '').trim();
  if (!raw) {
    return 'empty';
  }
  const spoken = withoutEvents(raw);
  const words = plain(spoken).split(' ').filter(Boolean);
  if (words.length === 0) {
    return 'hallucination';
  }
  if (isNoise(spoken)) {
    return 'noise';
  }
  const normalized = words.join(' ');
  if (HALLUCINATIONS.has(normalized) || normalized.startsWith('sous titres realises')
      || normalized.startsWith('sous titrage')) {
    return 'hallucination';
  }
  // Niveau non mesuré, ou phrase du réveil : les règles de voix ne s'appliquent pas (inchangé depuis SF-239-01).
  if (facts.preRoll || facts.voicedMs === null) {
    return null;
  }
  const voiced = Math.max(0, facts.voicedMs);
  const trusted = !!facts.transcribed && words.length >= TRANSCRIBED_MIN_WORDS;
  if (voiced < MIN_VOICED_MS && words.length <= NO_VOICE_MAX_WORDS && !trusted) {
    return 'no_voice';
  }
  const basis = Math.max(0, facts.listenedMs ?? voiced);
  if (words.length > MAX_WORDS_PER_SECOND * (basis / 1000) + IMPLAUSIBLE_MARGIN_WORDS) {
    return 'implausible';
  }
  return null;
}
