/**
 * Modèles de la copie de bloc façon ChatGPT (F-26).
 *
 * Un « bloc copiable » est un fenced code block généré par l'assistant (code, document, mail),
 * présenté inline dans le fil de chat avec un bouton « Copier ». Purement dérivé côté client des
 * messages déjà chargés — aucune persistance, aucun endpoint, aucun appel fournisseur.
 */

/** Nature d'un bloc copiable, déduite du token de langage du bloc Markdown. */
export type CopyBlockType = 'code' | 'doc' | 'mail';

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
