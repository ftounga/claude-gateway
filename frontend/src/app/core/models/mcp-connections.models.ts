/**
 * Modèles de l'écran « IA connectées » (F-112 / SF-112-03). Le secret d'un jeton personnel n'est
 * renvoyé qu'une seule fois, à la création ({@link CreatedMcpToken.secret}) ; jamais ensuite.
 */

/** Un jeton personnel listé (jamais le secret, seulement son préfixe). */
export interface McpToken {
  id: string;
  name: string;
  prefix: string;
  scopes: string[];
  hostIds: string[];
  createdAt: string;
  expiresAt: string;
  lastUsedAt: string | null;
  revoked: boolean;
  expired: boolean;
}

/** Réponse de création : le secret en clair (affiché une fois) + le jeton. */
export interface CreatedMcpToken {
  secret: string;
  token: McpToken;
}

/** Corps de création d'un jeton personnel. */
export interface CreateMcpTokenRequest {
  name: string;
  scopes: string[];
  hostIds: string[];
  expiresInDays: number;
}

/** Une ligne du journal MCP (sans contenu). */
export interface McpJournalEntry {
  id: string;
  client: string;
  authKind: string;
  tool: string | null;
  hostId: string | null;
  paramsSummary: string | null;
  result: string;
  durationMs: number | null;
  createdAt: string;
}

/** Un poste proposé au sélecteur d'accès poste par poste. */
export interface McpHostOption {
  id: string;
  name: string;
}

/** Les périmètres MCP et leur libellé français (miroir de `McpScopes` côté backend). */
export const MCP_SCOPES: { value: string; label: string }[] = [
  { value: 'postes:lire', label: 'Voir vos postes, statut, projets et gouvernance' },
  { value: 'postes:agir', label: 'Vérifier un poste, mettre à jour le runner, activer un espace' },
  { value: 'terminaux:ecrire', label: 'Écrire dans un terminal, préciser, interrompre' },
  { value: 'radar:lire', label: 'Lire la Vigie et le Radar' },
  { value: 'radar:ecrire', label: 'Synchroniser, donner une nouvelle, clore un sujet' },
  { value: 'pages', label: 'Publier, lister et lire des pages' },
  { value: 'courriel', label: 'Vous envoyer un courriel' },
  { value: 'compte:lire', label: 'Voir votre quota, abonnement et consommation' },
  { value: 'admin', label: "Outils d'administration (rôle ADMIN)" },
];
