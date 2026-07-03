/**
 * Modèles du Canvas / Artifacts (F-22).
 *
 * Un « artefact » est un bloc de contenu autonome généré par l'assistant (code, document, mail),
 * extrait d'un message pour être prévisualisé et copié dans le panneau latéral du chat.
 * Purement dérivé côté client des messages déjà chargés — aucune persistance, aucun endpoint.
 */

/** Nature d'un artefact, déduite du token de langage du bloc Markdown. */
export type ArtifactType = 'code' | 'doc' | 'mail';

/** Bloc de contenu généré, extrait d'un message assistant. */
export interface Artifact {
  /** Identifiant stable dans la conversation : `${messageId}#${index}`. */
  id: string;
  /** Identifiant du message d'origine. */
  messageId: string;
  /** Position (0-based) du bloc dans le message. */
  index: number;
  /** Nature de l'artefact. */
  type: ArtifactType;
  /** Token de langage brut du fence (`typescript`, `mail`, …) ou `null` si absent. */
  language: string | null;
  /** Libellé lisible affiché dans la liste. */
  title: string;
  /** Contenu brut (non interprété) du bloc. */
  content: string;
}
