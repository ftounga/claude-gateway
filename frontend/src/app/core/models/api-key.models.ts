/**
 * Modèles de la gestion de clé BYOK (F-03). La clé en clair n'est jamais manipulée côté client :
 * seule la version masquée (`sk-…last4`) est reçue du backend.
 */

/** Mode fournisseur effectif de l'utilisateur. */
export type ProviderMode = 'HOSTED' | 'BYOK';

/** Statut de la clé BYOK renvoyé par `GET /api/user/api-key`. */
export interface ApiKeyStatus {
  present: boolean;
  maskedKey: string | null;
  last4: string | null;
  provider: string | null;
  mode: ProviderMode;
  validatedAt: string | null;
  createdAt: string | null;
}

/** Corps de `POST /api/user/api-key`. */
export interface SaveApiKeyRequest {
  apiKey: string;
}

/** Corps de `PUT /api/user/api-key/mode`. */
export interface SetModeRequest {
  mode: ProviderMode;
}
