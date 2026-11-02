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

/** En dessous, le micro n'a pas entendu de voix pendant l'écoute (ms au-dessus du seuil de parole). */
export const MIN_VOICED_MS = 300;
/** Une parole « sans voix » au micro n'est refusée que si elle est courte (au plus autant de mots). */
export const NO_VOICE_MAX_WORDS = 6;
/** Débit maximal plausible : mots par seconde de voix, plus une marge fixe. */
export const MAX_WORDS_PER_VOICED_SECOND = 6;
export const IMPLAUSIBLE_MARGIN_WORDS = 4;
/** Une écoute ne dure jamais plus : au-delà elle se clôt, et la porte décide. */
export const MAX_LISTEN_MS = 30_000;

/** Ce que l'écran sait de l'écoute. */
export interface ListenFacts {
  /**
   * Le temps pendant lequel le micro a entendu une voix (niveau ≥ seuil de parole), en ms ; `null` si le niveau n'a
   * pas pu être mesuré (les règles de voix ne s'appliquent alors pas).
   */
  voicedMs: number | null;
  /** Vrai si l'écoute a commencé par l'audio de la phrase qui contenait « Jarvis » (le micro n'en a rien mesuré). */
  preRoll?: boolean;
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
  if (facts.preRoll || facts.voicedMs === null) {
    return null;
  }
  const voiced = Math.max(0, facts.voicedMs);
  if (voiced < MIN_VOICED_MS && words.length <= NO_VOICE_MAX_WORDS) {
    return 'no_voice';
  }
  if (words.length > MAX_WORDS_PER_VOICED_SECOND * (voiced / 1000) + IMPLAUSIBLE_MARGIN_WORDS) {
    return 'implausible';
  }
  return null;
}
