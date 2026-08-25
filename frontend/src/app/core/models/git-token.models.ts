/**
 * Modèles du jeton GitHub de l'utilisateur (F-31 / SF-31-01). Le jeton en clair n'est jamais
 * manipulé côté client au-delà de sa saisie : seule une version masquée (`…last4`) revient du
 * backend, accompagnée du compte GitHub auquel il donne accès.
 */

/** État renvoyé par `GET /api/user/git-token`. */
export interface GitTokenStatus {
  present: boolean;
  githubLogin: string | null;
  maskedToken: string | null;
  last4: string | null;
  createdAt: string | null;
  updatedAt: string | null;
}

/** Corps de `POST /api/user/git-token`. */
export interface SaveGitTokenRequest {
  token: string;
}
