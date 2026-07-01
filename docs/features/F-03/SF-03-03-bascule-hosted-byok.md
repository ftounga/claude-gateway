# Mini-spec — [F-03 / SF-03-03] Bascule Hosted/BYOK dans le chat

## Identifiant

`F-03 / SF-03-03`

## Feature parente

`F-03` — BYOK (Bring Your Own Key)

## Statut

`ready`

## Date de création

2026-07-01

## Branche Git

`feat/SF-03-03-bascule-hosted-byok`

---

## Objectif

> Utiliser la clé BYOK de l'utilisateur (déchiffrée à la volée) pour ses appels de chat quand le mode
> BYOK est actif, sinon la clé plateforme (Hosted), et exposer un endpoint de bascule Hosted/BYOK.

---

## Comportement attendu

### Cas nominal

- **Sélection du fournisseur dans le chat** : au moment de l'appel, `ChatService` résout la clé active
  de l'utilisateur via `ByokKeyService.resolveActiveApiKey(userId)` :
  - une clé existe et `active = true` → la clé est **déchiffrée à la volée** (SF-03-01) et passée à
    `AIProvider` en paramètre neutre (`ChatCompletionRequest.apiKey`) → **appel avec la clé de l'utilisateur** ;
  - sinon (`Optional.empty()`) → `apiKey = null` → **clé plateforme** (mode Hosted, comportement inchangé).
  - La clé en clair n'est jamais persistée ni journalisée.
- **Bascule** `PUT /api/user/api-key/mode` `{ "mode": "HOSTED" | "BYOK" }` :
  - `BYOK` : requiert une clé enregistrée → passe `active = true` (mode BYOK) ;
  - `HOSTED` : passe `active = false` si une clé existe (la clé est conservée mais non utilisée),
    sinon no-op (déjà Hosted).
  - Réponse : statut masqué (SF-03-02) avec `mode` à jour.
- Isolation : la clé résolue/basculée est toujours celle du `user_id` du `SecurityContext`.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| `mode` absent / hors {HOSTED,BYOK} | `validation_error` | 400 |
| `PUT mode=BYOK` sans clé enregistrée | `byok_mode_conflict` | 409 |
| Déchiffrement impossible (BYOK dormant) au moment du chat | `byok_unavailable` (SF-03-01) | 503 |
| Requête non authentifiée | — | 401 |

---

## Critères d'acceptation

- [ ] Quand une clé BYOK active existe, l'appel de chat porte la **clé déchiffrée de l'utilisateur**
      (vérifié : `ChatCompletionRequest.apiKey` = clé d'origine).
- [ ] Sans clé (ou clé inactive), l'appel de chat utilise la **clé plateforme** (`apiKey = null`) —
      non-régression du mode Hosted.
- [ ] `PUT /api/user/api-key/mode` bascule `active` et renvoie le `mode` à jour.
- [ ] `mode=BYOK` sans clé → 409 ; `mode` invalide → 400.
- [ ] La clé en clair n'est jamais journalisée ni persistée (déchiffrement à la volée uniquement).
- [ ] Isolation `user_id` : la clé d'un utilisateur n'est jamais résolue/basculée pour un autre.

---

## Périmètre

### Hors scope (explicite)

- Écran de réglages Angular (toggle + affichage) → **SF-03-04**.
- **Quotas/limites** : le contrôle de quota (F-10) reste **inchangé** pour les utilisateurs BYOK
  (toujours vérifié et enregistré). Modifier ce comportement toucherait la facturation/entitlements
  (sujet non tranché) : décision explicite requise → **hors scope**, non modifié silencieusement.

---

## Contraintes de validation

| Champ | Obligatoire | Format / Valeurs autorisées | Normalisation |
|-------|-------------|----------------------------|---------------|
| mode | Oui | `HOSTED` \| `BYOK` (exact) | — |

---

## Technique

### Endpoint(s)

| Méthode | URL | Auth | Rôle minimum |
|---------|-----|------|-------------|
| PUT | `/api/user/api-key/mode` | Oui | USER |

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| `user_api_keys` | SELECT / UPDATE (`active`) | colonne créée en SF-03-02 ; isolation `user_id` |

### Migration Liquibase

- [ ] Non applicable (colonne `active` déjà créée par `030-user-api-keys.xml` en SF-03-02).

### Préoccupations transversales

- **Contexte tenant / Provider Independence** : le choix Hosted/BYOK est résolu **par utilisateur** au
  moment de l'appel. Composants impactés listés/vérifiés :
  - `ByokKeyService` (nouveau : `resolveActiveApiKey`, `setMode`) ;
  - `ChatService.reply` (résout la clé active et la passe à `AIProvider` ; sans clé → plateforme) ;
  - `AIProvider`/`AnthropicProvider` (déjà prêt depuis SF-03-02 : override → plateforme) ;
  - `ChatController` (inchangé) ; `ChatServiceTest`, `ChatApiIntegrationTest` (non-régression Hosted + cas BYOK).
- **Plans / limites** : quota **non modifié** (voir hors scope). Aucun gate ajouté/retiré.

---

## Plan de test

### Tests unitaires

- [ ] `ByokKeyService.resolveActiveApiKey` — clé active → déchiffre et renvoie ; clé inactive → vide ;
      absente → vide (isolation `user_id`).
- [ ] `ByokKeyService.setMode` — BYOK avec clé → `active=true` ; HOSTED → `active=false` ; BYOK sans clé → `ByokModeException`.
- [ ] `ChatService` — clé BYOK active → `ChatCompletionRequest.apiKey` = clé déchiffrée ; sans clé → `apiKey` null.

### Tests d'intégration

- [ ] `PUT /api/user/api-key/mode` HOSTED/BYOK → 200 avec `mode` à jour.
- [ ] `PUT mode=BYOK` sans clé → 409 ; `mode` invalide → 400 ; non authentifié → 401.
- [ ] `POST /chat` avec clé BYOK enregistrée → l'appel fournisseur porte la clé de l'utilisateur.
- [ ] `POST /chat` sans clé → mode Hosted (non-régression).

### Isolation utilisateur

- [ ] Applicable — la clé d'Alice n'est jamais utilisée pour un appel de Bob ; la bascule de Bob
      n'affecte pas l'état d'Alice.

---

## Dépendances

### Subfeatures bloquantes

- `SF-03-01` (crypto) — **Done** (#46) ; `SF-03-02` (clé + `active` + override provider) — **Done** (#48).

### Questions ouvertes impactées

- [x] `OQ-06` — consommée (déchiffrement via `ByokKeyCipher`).

---

## Notes et décisions

- **Mode implicite + toggle** : une clé validée est active par défaut (mode BYOK dès l'ajout,
  SF-03-02). Le toggle permet de conserver la clé tout en revenant à Hosted (`active=false`).
- **Quota inchangé** : arbitrage réversible ; l'éventuelle exemption de quota des utilisateurs BYOK
  relève de la facturation/entitlements (sujet à trancher explicitement).
