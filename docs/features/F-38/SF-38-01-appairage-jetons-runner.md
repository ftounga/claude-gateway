# Mini-spec — F-38 / SF-38-01 — Identité du runner : appairage et jetons

## Identifiant
`F-38 / SF-38-01`

## Feature parente
`F-38` — Exécution sur machine connectée (runner local)

## Statut
`ready`

## Date de création
2026-08-29

## Branche Git
`feat/SF-38-01-appairage-jetons-runner`

---

## Objectif

> Permettre à un runner posé sur la machine du client d'obtenir, par un **appairage à usage unique**
> initié par l'utilisateur, un **jeton runner** lié à son `user_id` et à un workspace, révocable —
> sans encore ni canal WebSocket ni exécution.

---

## Comportement attendu

### Cas nominal

1. L'utilisateur (JWT, accès Atelier = Gold ou ADMIN), depuis un de **ses** workspaces, demande un
   **code d'appairage** : `POST /workspaces/{id}/runner/pairing-code`. Réponse : un code court
   (8 caractères A–Z2–9, sans caractères ambigus), sa date d'expiration (**TTL 5 min**), à **usage unique**.
2. Le runner échange ce code contre un jeton : `POST /runner/pair` avec `{ "code": "...", "label": "..." }`.
   Réponse : `{ "token": "<opaque ~43 car>", "workspaceId": "...", "expiresAt": "..." }`.
   **Le jeton n'est renvoyé qu'à cet instant**, jamais réexposé ensuite.
3. L'utilisateur peut **lister** ses jetons runner d'un workspace (`GET /workspaces/{id}/runner/tokens`,
   métadonnées seulement : id, label, createdAt, expiresAt, lastSeenAt, revoked) et en **révoquer** un
   (`DELETE /workspaces/{id}/runner/tokens/{tokenId}`).

Le jeton **authentifiera** le runner en SF-38-02 (canal WS). SF-38-01 fournit l'émission, le stockage
et la révocation ; la **vérification** du jeton (composant `RunnerTokenAuthenticator`) est livrée ici
et testée unitairement, mais n'est branchée sur aucun endpoint tant que SF-38-02 n'existe pas.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Génération de code sur un workspace d'un autre utilisateur | Accès refusé | 404 (on ne révèle pas l'existence) |
| Génération de code sans accès Atelier (ni Gold ni ADMIN) | Refus | 403 `atelier_forbidden` |
| `POST /runner/pair` avec code inconnu / expiré / déjà consommé | Refus générique (pas de distinction) | 401 `pairing_invalid` |
| `POST /runner/pair` sans code | Validation | 400 |
| `label` > 100 caractères | Validation | 400 |
| Révocation d'un jeton d'un workspace d'un autre utilisateur | Accès refusé | 404 |
| Révocation d'un jeton déjà révoqué | Idempotent (204) | 204 |

---

## Critères d'acceptation

- [ ] Un utilisateur avec accès Atelier obtient un code d'appairage pour **son** workspace (8 car., TTL 5 min).
- [ ] `POST /runner/pair` avec un code valide renvoie un jeton opaque **une seule fois** et consomme le code.
- [ ] Un même code ne peut pas être échangé deux fois (usage unique vérifié).
- [ ] Un code expiré est refusé (401 générique), sans distinction d'avec un code inconnu.
- [ ] Le jeton runner est stocké **haché** (SHA-256) : la valeur en clair n'existe qu'en réponse HTTP.
- [ ] Le jeton porte `user_id` **et** `workspace_id` ; les deux sont exigés à la vérification.
- [ ] L'utilisateur ne voit et ne révoque que les jetons de **ses** workspaces (isolation `user_id`).
- [ ] `RunnerTokenAuthenticator.authenticate(token)` renvoie l'identité runner pour un jeton valide,
      vide pour un jeton inconnu/expiré/révoqué — couvert par tests unitaires.
- [ ] La chaîne de sécurité dédiée `/runner/**` (`@Order(1)`) n'affecte pas les endpoints existants :
      test de non-régression sur `/me`, `/workspaces`, `/auth/**`.

---

## Périmètre

### Hors scope (explicite)
- Canal WebSocket et registre de connexions (**SF-38-02**).
- Toute exécution ou opération fichier (**SF-38-04+**).
- Écran d'appairage frontend (**SF-38-06**).
- Rotation automatique des jetons ; un jeton par workspace maximum (plusieurs jetons autorisés).

---

## Valeurs initiales

| Champ | Valeur initiale | Règle |
|-------|----------------|-------|
| `runner_pairing_codes.consumed_at` | `null` | passe à `now()` à l'échange |
| `runner_pairing_codes.expires_at` | `now()+5min` | TTL configurable `app.runner.pairing-code-ttl` |
| `runner_tokens.revoked_at` | `null` | passe à `now()` à la révocation |
| `runner_tokens.expires_at` | `now()+30j` | TTL configurable `app.runner.token-ttl` |
| `runner_tokens.last_seen_at` | `null` | mis à jour par SF-38-02 (heartbeat) |

Comportements à la création : `user_id` = utilisateur courant (`CurrentUser`), `workspace_id` = workspace
du chemin **après** vérification d'appartenance. `created_at` par la base.

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format / Valeurs | Unicité | Normalisation |
|-------|-------------|-------------|------------------|---------|---------------|
| `code` (pair) | Oui | 8 | `[A-Z2-9]{8}` | — | trim, upper |
| `label` | Non | 100 | texte libre | Non | trim |
| `token` (interne) | — | 64 (hash hex) | SHA-256 hex | Oui | — |

---

## Technique

### Endpoints

| Méthode | URL | Auth | Chaîne | Rôle min |
|---------|-----|------|--------|----------|
| POST | `/workspaces/{id}/runner/pairing-code` | JWT | principale | accès Atelier |
| GET | `/workspaces/{id}/runner/tokens` | JWT | principale | accès Atelier |
| DELETE | `/workspaces/{id}/runner/tokens/{tokenId}` | JWT | principale | accès Atelier |
| POST | `/runner/pair` | **code d'appairage** (pas de JWT) | **dédiée `/runner/**` @Order(1)** | — |

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| `runner_pairing_codes` | CREATE | id, user_id, workspace_id, code_hash, expires_at, consumed_at, created_at |
| `runner_tokens` | CREATE | id, user_id, workspace_id, token_hash (unique), label, expires_at, revoked_at, last_seen_at, created_at |

### Migration Liquibase
- [x] Oui — `047-runner-pairing-and-tokens.xml` (PostgreSQL + H2, réversible `dropTable`)

### Composants Angular
- Aucun (SF-38-06).

---

## Plan de test

### Tests unitaires
- [ ] `RunnerPairingService` — génération : code 8 car., TTL, lié user+workspace.
- [ ] `RunnerPairingService` — échange nominal : jeton émis, code consommé.
- [ ] `RunnerPairingService` — code déjà consommé / expiré / inconnu → `PairingInvalidException`.
- [ ] `RunnerTokenService` — le jeton est stocké haché, jamais en clair.
- [ ] `RunnerTokenService` — révocation idempotente.
- [ ] `RunnerTokenAuthenticator` — jeton valide → identité ; expiré/révoqué/inconnu → vide.

### Tests d'intégration
- [ ] `POST /workspaces/{id}/runner/pairing-code` → 200 pour le propriétaire ; 404 pour un autre user ; 403 sans accès Atelier.
- [ ] `POST /runner/pair` → 200 avec code valide ; 401 avec code invalide/expiré ; 400 sans code.
- [ ] Rejeu du même code → 401.
- [ ] `GET/DELETE /workspaces/{id}/runner/tokens` → isolation `user_id` (404 cross-user).

### Isolation
- [x] Applicable — un utilisateur du workspace A ne peut ni générer de code, ni lister/révoquer les jetons du workspace B.

---

## Dépendances

### Subfeatures bloquantes
- Aucune (première subfeature de F-38).

### Questions ouvertes impactées
- Aucune de `docs/OPEN_QUESTIONS.md`. Le choix « registre PgNotify » (D8) ne concerne que SF-38-02.

---

## Préoccupation transversale — Auth / Principal (OBLIGATOIRE)

SF-38-01 introduit un **second type de porteur d'identité** : le **runner**, authentifié par jeton
opaque et non par JWT utilisateur. Analyse d'impact :

**Composants qui résolvent le Principal / le tenant aujourd'hui :**
- `JwtAuthenticationFilter` — pose l'`AuthenticatedUser` (JWT). **Inchangé.**
- `CurrentUser` — lit l'`AuthenticatedUser` du `SecurityContext`. **Inchangé** ; ne doit jamais
  renvoyer une identité runner (le runner n'est pas un `AuthenticatedUser`).
- `SecurityConfig` (chaîne principale, `anyRequest().authenticated()`). **Inchangée.**
- `AtelierAccessService` — gate Gold/ADMIN. **Inchangé** ; appliqué aux endpoints de génération/gestion.

**Décision d'isolation (D9) :** le jeton runner est traité par une **chaîne de sécurité dédiée**
`@Order(1)` `securityMatcher("/runner/**")`, STATELESS, CSRF off. Elle ne partage aucun filtre avec
la chaîne principale. Conséquence : un jeton runner **ne peut jamais** authentifier un endpoint
utilisateur, et un JWT utilisateur n'est pas attendu sur `/runner/**`.

**Tests de non-régression exigés :** `/me`, `/workspaces` (liste), `/auth/login` continuent de se
comporter comme avant l'ajout de la chaîne dédiée (200/401 identiques). Ajoutés au plan d'intégration.

---

## Notes et décisions

- **Stockage haché du jeton** (écart assumé vs `password_reset_tokens` qui stocke en clair) : le
  jeton runner ouvre un canal d'exécution ; on stocke `SHA-256(token)`, jamais le clair. La valeur
  en clair n'existe que dans la réponse HTTP de `POST /runner/pair`.
- **Code d'appairage haché** de même (`SHA-256`), TTL 5 min, usage unique.
- Le format du jeton réutilise `SecureTokenGenerator` (32 octets Base64URL).
