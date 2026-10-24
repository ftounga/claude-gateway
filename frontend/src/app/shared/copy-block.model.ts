/**
 * Modèles de la copie de bloc façon ChatGPT (F-26).
 *
 * Un « bloc copiable » est un fenced code block généré par l'assistant (code, document, mail),
 * présenté inline dans le fil de chat avec un bouton « Copier ». Purement dérivé côté client des
 * messages déjà chargés — aucune persistance, aucun endpoint, aucun appel fournisseur.
 */

/** Nature d'un bloc copiable, déduite du token de langage du bloc Markdown. */
/** `message` (F-203) : message de messagerie ou de ticket à envoyer tel quel (Teams, Slack, Jira…). */
export type CopyBlockType = 'code' | 'doc' | 'mail' | 'message';

/** Bloc de contenu généré, extrait d'un message assistant, présenté avec un bouton « Copier ». */
export interface CopyBlock {
  /** Nature du bloc. */
  type: CopyBlockType;
  /** Token de langage brut du fence (`typescript`, `mail`, …) ou `null` si absent. */
  language: string | null;
  /** Libellé lisible affiché dans l'en-tête du bloc. */
  title: string;
  /** Contenu brut (non interprété) du bloc. */
  content: string;
  /** Clé de l'attente que ce message sert (F-211, balise ```mail attente=<clé>), si l'agent l'a donnée. */
  attente?: string;
  /** Identifiants des captures de preuve jointes (F-212, balise ```message preuves=<id1>,<id2>). */
  preuves?: string[];
}

/** Segment de prose (texte hors bloc) d'un message, rendu en Markdown assaini. */
export interface ProseSegment {
  kind: 'prose';
  /** Texte Markdown brut du segment. */
  text: string;
}

/** Segment « bloc copiable » d'un message, rendu avec en-tête + bouton « Copier ». */
export interface BlockSegment {
  kind: 'block';
  block: CopyBlock;
}

/**
 * Segment ordonné d'un message assistant : soit de la prose (Markdown), soit un bloc copiable.
 * L'ordre de la liste reflète l'ordre d'origine dans le message.
 */
export type MessageSegment = ProseSegment | BlockSegment;
