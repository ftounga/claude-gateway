# Mini-spec — [F-03 / SF-03-02] Gestion de la clé BYOK

## Identifiant

`F-03 / SF-03-02`

## Feature parente

`F-03` — BYOK (Bring Your Own Key)

## Statut

`ready`

## Date de création

2026-07-01

## Branche Git

`feat/SF-03-02-gestion-cle-byok`

---

## Objectif

> Permettre à l'utilisateur d'ajouter, consulter (statut masqué) et supprimer sa clé API Anthropic
> personnelle, chiffrée au repos (SF-03-01), après validation par un appel test réel au fournisseur.

---

## Comportement attendu

### Cas nominal

- `POST /api/user/api-key` `{ "apiKey": "sk-ant-..." }` :
  1. Normalise (trim) et valide le format (préfixe `sk-`, longueur ≥ 8).
  2. **Valide la clé par un appel test réel** via `AIProvider.complete(...)` en passant la clé de
     l'utilisateur (message minimal, modèle par défaut). Si le fournisseur refuse la clé → 400.
  3. Chiffre la clé via `ByokKeyCipher` (SF-03-01) et **upsert** la ligne `user_api_keys` de
     l'utilisateur (une seule par utilisateur) : blob chiffré, `key_last4`, `validated_at = now`,
     `active = true`.
  4. Réponse **masquée** : `{ present:true, maskedKey:"sk-…AB12", last4:"AB12", provider:"ANTHROPIC",
     mode:"BYOK", validatedAt, createdAt }`. **Jamais la clé en clair.**
- `GET /api/user/api-key` : statut de la clé courante (présente/absente, last4, mode, validatedAt).
  Absente → `{ present:false, mode:"HOSTED", ... }`.
- `DELETE /api/user/api-key` : supprime la clé de l'utilisateur (idempotent) → 204.
- Isolation : toute opération filtre sur le `user_id` du `SecurityContext` (jamais un paramètre client).

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| `apiKey` absent/vide | `validation_error` | 400 |
| Format invalide (pas de préfixe `sk-`, trop courte) | `invalid_api_key` | 400 |
| Clé refusée par le fournisseur lors de l'appel test | `invalid_api_key` | 400 |
| Chiffrement BYOK non configuré (dormant) | `byok_unavailable` (SF-03-01) | 503 |
| Fournisseur IA momentanément indisponible/en échec réseau | `provider_error` | 502 |
| Requête non authentifiée | — | 401 |

---

## Critères d'acceptation

- [ ] Table `user_api_keys` créée par migration `030-user-api-keys.xml` (changesets pg + h2), `user_id`
      unique, colonnes du blob chiffré + `key_last4` + `active` + `created_at`/`validated_at`, index `user_id`.
- [ ] `POST` valide la clé par un **appel test réel** (`AIProvider`) avant de chiffrer et stocker.
- [ ] La clé est stockée **chiffrée** (SF-03-01) ; **jamais** en clair en base, log ou réponse.
- [ ] La réponse ne contient que la version masquée (`sk-…last4`) + `last4`.
- [ ] `GET` renvoie le statut (present/absent, last4, mode, validatedAt) sans exposer la clé.
- [ ] `DELETE` supprime la clé de l'utilisateur (idempotent) et n'affecte aucun autre utilisateur.
- [ ] Une clé refusée par le fournisseur renvoie 400 sans rien persister.
- [ ] Isolation `user_id` vérifiée : un utilisateur ne voit/altère jamais la clé d'un autre.
- [ ] La suppression RGPD du compte (F-11) supprime aussi la clé BYOK.

---

## Périmètre

### Hors scope (explicite)

- Usage de la clé dans le chat + bascule Hosted/BYOK (endpoint `mode`) → **SF-03-03** (la colonne
  `active` est créée ici mais non pilotable tant que SF-03-03 n'expose pas le toggle ; à la création
  d'une clé, `active = true` → `mode = BYOK`).
- Écran de réglages Angular → **SF-03-04**.
- Rotation/expiration de clé, multi-provider (seul `ANTHROPIC` en V1).

---

## Valeurs initiales

| Champ | Valeur initiale | Règle |
|-------|----------------|-------|
| provider | `ANTHROPIC` | seule valeur V1 |
| active | `true` | à la création d'une clé (mode BYOK actif dès validation) |
| validated_at | `now()` | renseigné à chaque validation réussie |
| created_at | `now()` | base |

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format / Valeurs | Unicité | Normalisation |
|-------|-------------|-------------|------------------|---------|---------------|
| apiKey | Oui | 200 | non vide, préfixe `sk-`, longueur ≥ 8 | — | `trim()` |
| user_id | Oui | — | UUID du contexte | Oui (1 clé/utilisateur) | — |
| key_last4 | Oui | 4 | 4 derniers caractères de la clé | — | dérivé |

---

## Technique

### Endpoint(s)

| Méthode | URL | Auth | Rôle minimum |
|---------|-----|------|-------------|
| POST | `/api/user/api-key` | Oui | USER |
| GET | `/api/user/api-key` | Oui | USER |
| DELETE | `/api/user/api-key` | Oui | USER |

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| `user_api_keys` | CREATE / SELECT / UPSERT / DELETE | nouvelle table, isolation `user_id` |

### Migration Liquibase

- [x] Oui — `030-user-api-keys.xml` (numéro haut pour éviter toute collision avec la vague documentaire).

### Préoccupations transversales

- **Provider Independence (BLOCAGE si mal fait)** : l'appel test passe la clé **en paramètre neutre**
  sur `ChatCompletionRequest` (nouveau champ optionnel `apiKey`) ; `AnthropicProvider` utilise cette
  clé si présente, sinon la clé plateforme. Composants impactés listés/vérifiés :
  - `ChatCompletionRequest` (ajout champ optionnel + constructeurs de compat) ;
  - `AnthropicProvider.complete` (résolution clé override → plateforme, 503 si aucune) ;
  - `ChatService.reply` (inchangé : passe `apiKey = null` → clé plateforme, non-régression testée) ;
  - `AnthropicProviderTest`, `ChatServiceTest` (verts, non-régression).
- **Contexte tenant** : nouvel accès `user_api_keys`, toujours filtré `user_id` (service). Composant
  impacté : `ByokKeyService`.
- **RGPD (F-11)** : `AccountService.deleteAccount` supprime aussi `user_api_keys` (composant impacté :
  `AccountService`, test mis à jour).

---

## Plan de test

### Tests unitaires

- [ ] `ByokKeyService` — save : format invalide → `InvalidApiKeyException`.
- [ ] `ByokKeyService` — save : appel test échoue (`AIProviderException`) → `InvalidApiKeyException`, rien persisté.
- [ ] `ByokKeyService` — save : nominal → chiffre (cipher mocké), persiste, renvoie masqué (last4 correct).
- [ ] `ByokKeyService` — getStatus absente → present=false, mode=HOSTED.
- [ ] `ByokKeyService` — delete idempotent.
- [ ] `AnthropicProvider` — utilise la clé override quand fournie (appel réseau simulé/mécanisme vérifié).

### Tests d'intégration

- [ ] `POST /api/user/api-key` → 200 masqué (clé valide, AIProvider stubbé OK).
- [ ] `POST` → 400 clé vide / format invalide.
- [ ] `POST` → 400 si le fournisseur refuse la clé (AIProvider stub lève).
- [ ] `GET` present/absent.
- [ ] `DELETE` → 204 puis `GET` present=false.
- [ ] 401 si non authentifié sur les trois routes.

### Isolation utilisateur

- [ ] Applicable — la clé d'Alice n'est jamais lue/supprimée via le token de Bob ; `DELETE` d'Alice
      n'affecte pas la clé de Bob ; suppression de compte d'Alice n'affecte pas Bob.

---

## Dépendances

### Subfeatures bloquantes

- `SF-03-01` (socle crypto) — **Done** (mergé #46).

### Questions ouvertes impactées

- [x] `OQ-06` — tranchée (KMS), consommée via `ByokKeyCipher`.

---

## Notes et décisions

- **Upsert 1 clé/utilisateur** : contrainte d'unicité `user_id`. Un nouvel ajout remplace la clé
  existante et re-valide.
- **Appel test réel** : conforme à la spec F-03 (validation par appel). Coût minime (message court),
  facturé au compte Anthropic du propriétaire de la clé (cohérent avec BYOK).
- **Colonne `active`** : créée dès cette migration pour éviter une 2ᵉ migration en SF-03-03 (une
  migration appliquée ne doit pas être modifiée). Non pilotable tant que SF-03-03 n'expose pas le toggle.
- **`mode` dérivé** : `BYOK` si une clé active existe, sinon `HOSTED`.
