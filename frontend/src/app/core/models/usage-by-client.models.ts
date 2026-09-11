/**
 * Modèles de la consommation **par client** (F-61 / SF-61-02, `GET /api/usage/by-client`).
 *
 * <p>Contrat figé côté backend. Des **volumes** et des **coûts**, jamais des contenus : ni message,
 * ni commande, ni chemin de fichier n'y figure — et le coût est une **estimation** au tarif de la
 * plateforme, pas un montant facturé.</p>
 */

/** Consommation d'un projet, sous son client. */
export interface ProjectUsageView {
  /** Identifiant du projet, ou `null` pour un tour hors projet (conversation, question). */
  workspaceId: string | null;
  /** Nom du projet, ou `null` s'il a été supprimé depuis — la dépense, elle, reste comptée. */
  name: string | null;
  inputTokens: number;
  outputTokens: number;
  totalTokens: number;
  /** Coût estimé (devise de la réponse). */
  estimatedCost: number;
  /** Part du total de la fenêtre, entre 0 et 1. */
  share: number;
}

/** Consommation d'un client — c'est-à-dire d'un **poste** — et de ses projets. */
export interface ClientUsageView {
  /** Identifiant du poste, ou `null` pour le seau « hors client ». */
  hostId: string | null;
  /** Nom du poste, ou `null` s'il a été supprimé depuis. */
  hostName: string | null;
  inputTokens: number;
  outputTokens: number;
  totalTokens: number;
  estimatedCost: number;
  /** Part du total de la fenêtre, entre 0 et 1. */
  share: number;
  projects: ProjectUsageView[];
}

/** Réponse complète : totaux de la fenêtre et clients, du plus consommateur au moins. */
export interface UsageByClientView {
  currency: string;
  /** Premier mois observé (ISO `YYYY-MM-DD`, premier du mois). */
  from: string;
  /** Dernier mois observé, inclus (ISO `YYYY-MM-DD`, premier du mois). */
  to: string;
  inputTokens: number;
  outputTokens: number;
  totalTokens: number;
  estimatedCost: number;
  clients: ClientUsageView[];
}
