/**
 * **« Pas pour moi »** (F-265 / SF-265-01) — pur : deux jugements sur une phrase COURTE entendue, pour que Jarvis ne
 * réponde plus à une autre personne de la pièce.
 *
 * Séance du PO du 2026-11-15 (09:34:44 et 09:34:55) : « Maman ? Maman ? » puis « Ah bah, c'est pas dit non », dites par
 * une autre personne (0,8 et 1,1 s de voix, crête 0,22 et 0,17 contre ≥ 0,7 pour le PO), ont été traitées comme des
 * tours ; le cerveau a répondu deux fois « Pardon, Monsieur, je n'ai pas compris ». Indice inutilisé : la transcription
 * en direct avait entendu « Lui-même ? » pour la première (rien de commun avec la finale) et rien pour la seconde.
 *
 * 1. {@link liveFinalDiscord} — la transcription en direct et la retranscription finale d'une phrase courte n'ont
 *    **rien** en commun : une voix lointaine, que les deux moteurs devinent chacun à leur façon. La phrase est refusée.
 * 2. {@link mayHushPardon} / {@link isClarification} — hors d'une question posée par Jarvis, une phrase courte que le
 *    cerveau n'a pas comprise n'appelle pas « Pardon, je n'ai pas compris » : Jarvis se tait.
 *
 * **Contrainte dure** : une vraie réponse brève du PO (« Oui. », « Non. », « La deuxième », « Stop ») n'est JAMAIS
 * refusée ni tue ({@link isBriefReply}) — le mot bref « Oui » a déjà été un défaut (SF-251-03, banc F-256).
 */

/** Une phrase « courte » : au plus autant de mots (normalisés, hésitations retirées). */
export const SHORT_MAX_WORDS = 7;
/** La discordance n'est jugée que si la transcription en direct est elle aussi courte. */
export const DISCORD_LIVE_MAX_WORDS = 8;
/** En dessous de cette ressemblance de lettres (Dice sur les paires de lettres), deux textes n'ont rien en commun. */
export const DISCORD_MAX_SIMILARITY = 0.25;

/** Minuscules, sans accents, ponctuation en espaces. */
function plain(text: string | null | undefined): string {
  return (text ?? '').toLowerCase().normalize('NFD').replace(/\p{M}+/gu, '').replace(/[^a-z0-9]+/g, ' ').trim();
}

const HESITATIONS = new Set(['euh', 'heu', 'hum', 'hmm', 'hm', 'mmh', 'mm', 'mmm', 'ah', 'oh', 'eh', 'bah', 'ben',
  'hein', 'pff', 'tss', 'ouh', 'aah', 'ooh']);

/** Les mots d'une phrase, hésitations retirées. */
export function contentWords(text: string | null | undefined): string[] {
  return plain(text).split(' ').filter((w) => w && !HESITATIONS.has(w));
}

/**
 * Le vocabulaire d'une **réponse brève** : acquiescer, refuser, arrêter, choisir dans une liste, relancer. Une phrase
 * dont TOUS les mots en sont est une réponse brève, protégée des deux règles.
 */
const BRIEF = new Set([
  // oui / non
  'oui', 'ouais', 'ouai', 'ouaip', 'ouep', 'yes', 'non', 'nan', 'no', 'nope', 'si', 'pas', 'du', 'tout', 'jamais',
  'ok', 'okay', 'oki', 'd', 'accord', 'dac', 'exact', 'exactement', 'parfait', 'super', 'bien', 'tres', 'top', 'cool',
  'entendu', 'note', 'vu', 'certes', 'absolument', 'evidemment', 'carrement', 'voila', 'merci', 'fait',
  // arrêter, attendre, relancer
  'stop', 'stoppe', 'arrete', 'arretez', 'arreter', 'attends', 'attendez', 'pause', 'chut', 'tais', 'toi', 'annule',
  'annuler', 'annulez', 'laisse', 'laissez', 'tomber', 'continue', 'continuez', 'encore', 'reprends', 'reprenez',
  'repete', 'repetez', 'pardon', 'quoi', 'comment', 'hein', 'vas', 'va', 'y', 'allez', 'allons', 'go', 'fais', 'le',
  'faites', 'valide', 'valider', 'confirme', 'confirmer', 'envoie', 'envoyer', 'suivant', 'suivante', 'precedent',
  'precedente', 'apres', 'avant', 'plus', 'moins', 'assez', 'ca', 'c', 'est', 'cela', 'celui', 'celle', 'ceux', 'celles',
  'ci', 'la', 'les', 'l', 'un', 'une', 'des', 'de', 'au', 'aux', 'et', 'ou', 'autre', 'meme', 'aussi',
  // choisir dans une liste
  'premier', 'premiere', 'deuxieme', 'second', 'seconde', 'troisieme', 'quatrieme', 'cinquieme', 'sixieme',
  'dernier', 'derniere', 'numero', 'deux', 'trois', 'quatre', 'cinq', 'six', 'sept', 'huit', 'neuf', 'dix',
  '1', '2', '3', '4', '5', '6', '7', '8', '9', '10', '1er', '1ere', '2e', '2eme', '3e', '3eme', '4e', '4eme', '5e', '5eme',
  // l'adresse
  'jarvis', 'monsieur', 's', 'il', 'te', 'vous', 'plait', 'stp', 'svp', 'moi', 'nous',
]);

/** Une réponse brève du PO (« Oui. », « Non merci », « La deuxième », « Stop », « Vas-y ») : jamais refusée ni tue. */
export function isBriefReply(text: string | null | undefined): boolean {
  const words = contentWords(text);
  return words.length > 0 && words.length <= 5 && words.every((w) => BRIEF.has(w));
}

/** Les paires de lettres de chaque mot (« maman » → ma, am, ma, an). */
function letterPairs(words: string[]): string[] {
  return words.flatMap((w) => (w.length < 2 ? [w] : Array.from({ length: w.length - 1 }, (_, i) => w.slice(i, i + 2))));
}

/** Ressemblance de lettres entre deux phrases (0 = rien en commun, 1 = identiques), coefficient de Dice. */
export function letterSimilarity(a: string[], b: string[]): number {
  const pa = letterPairs(a);
  const pb = letterPairs(b);
  if (pa.length === 0 || pb.length === 0) {
    return 0;
  }
  const pool = new Map<string, number>();
  pb.forEach((p) => pool.set(p, (pool.get(p) ?? 0) + 1));
  let shared = 0;
  for (const p of pa) {
    const n = pool.get(p) ?? 0;
    if (n > 0) {
      shared++;
      pool.set(p, n - 1);
    }
  }
  return (2 * shared) / (pa.length + pb.length);
}

/**
 * Vrai si la transcription en direct (`live`) et la finale (`final`) d'une phrase **courte** n'ont rien en commun :
 * aucun mot partagé et presque aucune paire de lettres. Une transcription en direct vide ne juge rien (le flux manque
 * parfois un mot bref que la finale rattrape) ; une réponse brève n'est jamais jugée discordante.
 */
export function liveFinalDiscord(live: string | null | undefined, final: string | null | undefined): boolean {
  const l = contentWords(live);
  const f = contentWords(final);
  if (l.length === 0 || f.length === 0 || f.length > SHORT_MAX_WORDS || l.length > DISCORD_LIVE_MAX_WORDS) {
    return false;
  }
  if (isBriefReply(live) || isBriefReply(final)) {
    return false;
  }
  if (l.some((w) => f.includes(w))) {
    return false;
  }
  return letterSimilarity(l, f) < DISCORD_MAX_SIMILARITY;
}

/**
 * Une phrase de Jarvis qui dit **ne pas avoir compris** et demande de répéter (« Pardon, Monsieur, je n'ai pas bien
 * compris : pouvez-vous répéter ? », « Pardon, Monsieur ? »). Une vraie question de précision (« la deuxième quoi ? »,
 * « à quel sujet répondez-vous ? ») n'en est pas une.
 */
export function isClarification(sentence: string | null | undefined): boolean {
  const p = plain(sentence);
  return /\bpas (bien |tout a fait |tres bien )?(compris|saisi|entendu|suivi|capte)\b/.test(p)
    || /\b(pouvez vous|peux tu|pourriez vous|voudriez vous) (me )?(le |la )?(repeter|redire|reformuler)\b/.test(p)
    || /^pardon( monsieur)?$/.test(p);
}

/** Jarvis attend une réponse : sa dernière phrase est une question, et pas une demande de répéter. */
export function awaitsAnswer(lastSaid: string | null | undefined): boolean {
  const said = (lastSaid ?? '').trim();
  if (!said) {
    return false;
  }
  const sentences = said.split(/(?<=[.!?…])\s+/).filter(Boolean);
  const last = sentences.at(-1) ?? said;
  return /\?\s*[»"”)]*\s*$/.test(last) && !isClarification(last);
}

/** Ce qu'on sait de la phrase entendue, pour décider si Jarvis peut se taire sur un « je n'ai pas compris ». */
export interface HushFacts {
  /** La dernière chose dite par Jarvis (affichée), `null` si rien. */
  lastSaid: string | null;
  /** L'écoute a commencé par la phrase qui contenait « Jarvis » : la parole lui est adressée. */
  preRoll?: boolean;
  /** Les noms des sujets et des clients (« data-platform », « CAGIP ») : une phrase qui en nomme un lui est adressée. */
  known?: readonly string[];
}

/** Le vocabulaire du travail : une phrase qui en contient un mot s'adresse à Jarvis (« Sur le chantier 2 »). */
const WORK = new Set(['chantier', 'chantiers', 'sujet', 'sujets', 'client', 'clients', 'poste', 'postes', 'etape',
  'etapes', 'ticket', 'tickets', 'mail', 'mails', 'message', 'messages', 'galerie', 'video', 'videos', 'schema', 'schemas',
  'ecran', 'montre', 'affiche', 'ouvre', 'lance', 'relance', 'reunion', 'agenda', 'rendez', 'courrier', 'preuve',
  'preuves', 'photo', 'photos', 'architecture', 'terminal', 'opus', 'plan', 'tache', 'taches', 'budget', 'fil']);

/** Les mots (≥ 3 lettres) des noms connus. */
function knownWords(names: readonly string[] | undefined): Set<string> {
  return new Set((names ?? []).flatMap((n) => contentWords(n)).filter((w) => w.length >= 3));
}

/**
 * Vrai si, pour cette phrase entendue, Jarvis peut se taire au lieu de dire « Pardon, je n'ai pas compris » : phrase
 * courte, qui ne nomme ni Jarvis, ni un sujet, ni un client, sans mot du travail (chantier, galerie…), qui n'est pas
 * une réponse brève, hors d'une question qu'il vient de poser et hors réveil. Le silence n'est appliqué que si le cerveau répond effectivement par une demande de répéter
 * ({@link isClarification}) : une phrase comprise reçoit sa réponse comme avant.
 */
export function mayHushPardon(heard: string | null | undefined, facts: HushFacts): boolean {
  const words = contentWords(heard);
  if (words.length === 0 || words.length > SHORT_MAX_WORDS || words.includes('jarvis')) {
    return false;
  }
  if (facts.preRoll || isBriefReply(heard)) {
    return false;
  }
  const known = knownWords(facts.known);
  if (words.some((w) => WORK.has(w) || known.has(w))) {
    return false;
  }
  return !awaitsAnswer(facts.lastSaid);
}
