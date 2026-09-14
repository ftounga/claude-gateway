# Mini-spec — F-112 / SF-112-03 Jetons personnels, accès par poste, journal, écran « IA connectées »

## Identifiant

`F-112 / SF-112-03`

## Feature parente

`F-112` — Le serveur MCP

## Statut

`ready`

## Date de création

2026-09-14

## Branche Git

`feat/SF-112-03-jetons-personnels`

---

## Objectif

Permettre à l'utilisateur de créer des **jetons personnels** MCP (hachés, à expiration obligatoire,
avec périmètres et postes), de piloter l'**accès poste par poste** (avec l'avertissement fournisseur),
de voir le **journal MCP**, le tout depuis un écran « IA connectées », avec des **limites par jeton**.

---

## Comportement attendu

### Cas nominal

1. Dans « IA connectées », l'utilisateur crée un jeton : nom, périmètres, **postes accessibles**,
   **expiration obligatoire** (≤ 90 jours). Le jeton en clair est **affiché une seule fois**, puis
   stocké **haché** (SHA-256). L'écran rappelle : *« les données de ces postes seront transmises au
   fournisseur de l'IA connectée »*.
2. Un client sans navigateur présente `Authorization: Bearer cgmcp_…` sur `/api/mcp` : le jeton est
   reconnu (préfixe), retrouvé par hachage, vérifié (non révoqué, non expiré), l'identité et les
   périmètres/postes sont posés dans le contexte d'appel ; `last_used_at` est mis à jour.
3. Chaque appel d'outil écrit une ligne de **journal** : client, jeton, outil, poste, **résumé des
   paramètres sans contenu**, résultat, durée. L'utilisateur lit son journal.
4. L'utilisateur **révoque** un jeton : il cesse immédiatement d'ouvrir `/api/mcp`.
5. Un **poste nouvellement créé n'est pas accessible** par un jeton existant tant qu'il n'y est pas
   ajouté (le jeton ne liste que les postes explicitement choisis).

### Cas d'erreur

| Situation | Comportement | Code |
|-----------|-------------|------|
| Création sans expiration, ou > 90 jours, ou ≤ 0 | Refus, message explicite | 400 |
| Périmètre inconnu, ou `admin` demandé par un non-ADMIN | Refus | 400 |
| Poste demandé n'appartenant pas à l'utilisateur | Refus | 400 |
| Jeton révoqué / expiré présenté à `/api/mcp` | Refus | 401 |
| Plus de 60 appels/minute pour un jeton | Refus nommé | 429 |
| Révocation d'un jeton d'un autre utilisateur | Introuvable | 404 |
| Lecture du journal / des jetons d'un autre utilisateur | Impossible (filtré `user_id`) | — |

---

## Critères d'acceptation

- [ ] Création d'un jeton : haché en base, **jamais** relu en clair après la création, expiration
      obligatoire ≤ 90 jours, périmètres et postes validés (postes de l'utilisateur, `admin` réservé ADMIN).
- [ ] Le clair n'est renvoyé qu'**une fois** (à la création).
- [ ] Un jeton personnel authentifie `/api/mcp` (préfixe reconnu) ; révoqué ou expiré → 401.
- [ ] Limite **60 appels/min par jeton** → 429 au-delà.
- [ ] Le **journal MCP** enregistre chaque appel d'outil **sans contenu** ; l'utilisateur le lit ;
      isolation `user_id`.
- [ ] Accès **poste par poste** stocké par jeton ; un poste non listé n'est pas accessible.
- [ ] Écran « IA connectées » : créer / lister / révoquer un jeton, avertissement fournisseur, journal.
- [ ] Isolation `user_id` (et `host_id`) sur tous les accès (jetons, hôtes, journal).
- [ ] Migration `105` (premier numéro libre > 104), compatible H2 et PostgreSQL.

---

## Périmètre

### Hors scope (explicite)

- La gestion des **connexions OAuth actives** dans l'écran (liste/révocation) : les autorisations
  OAuth ne sont pas encore persistées (SF-112-02 les tient en mémoire) → **suivi** ; l'écran couvre
  les jetons personnels et le journal.
- La limite « 10 tours simultanés » : liée aux tours (outils terminaux), **SF-112-05**.
- Les outils de domaine, la sélection de postes **dans l'écran de consentement OAuth** : SF-112-04→08.

## Valeurs initiales

| Champ | Valeur | Règle |
|-------|--------|-------|
| token_hash | SHA-256 hex du clair | jamais le clair |
| expires_at | obligatoire | ≤ 90 jours après la création |
| revoked_at | null | posé à la révocation |
| created_at | now | base |
| user_id | utilisateur connecté | contexte de sécurité |

## Contraintes de validation

| Champ | Obligatoire | Longueur/Format |
|-------|-------------|-----------------|
| name | Oui | 1..120, trim non vide |
| scopes | Oui | sous-ensemble de `McpScopes.all()` ; `admin` ⇒ rôle ADMIN |
| hostIds | Non | postes de l'utilisateur (`findByIdAndUserId`) |
| expiresInDays | Oui | entier 1..90 |

---

## Technique

### Endpoints (chaîne principale, JWT plateforme, rôle USER)

| Méthode | URL | Rôle |
|---------|-----|------|
| GET | `/api/mcp-connections/tokens` | USER (ses jetons) |
| POST | `/api/mcp-connections/tokens` | USER (clair renvoyé une fois) |
| DELETE | `/api/mcp-connections/tokens/{id}` | USER (révocation) |
| GET | `/api/mcp-connections/journal` | USER (son journal) |
| GET | `/api/mcp-connections/hosts` | USER (ses postes, pour le sélecteur) |

### Tables (migration `105`)

| Table | Colonnes clés |
|-------|---------------|
| `mcp_personal_tokens` | id, user_id, name, token_hash (unique), token_prefix, scopes, created_at, expires_at, last_used_at, revoked_at |
| `mcp_token_hosts` | token_id (FK cascade), host_id (FK runner_hosts cascade), PK(token_id, host_id) |
| `mcp_journal` | id, user_id, client, token_id, auth_kind, tool, host_id, params_summary (sans contenu), result, duration_ms, created_at |

### Composants

- `McpPersonalToken`, `McpPersonalTokenHost`, `McpJournalEntry` (entités) + repositories.
- `McpPersonalTokenService` (création/hachage/validation/révocation), `McpJournalService`.
- `McpConnectionsController` (les 5 endpoints) + DTOs.
- `McpPersonalTokenAuthenticationFilter` (sur la chaîne `/mcp`, avant le filtre OAuth ; ne traite que
  le préfixe `cgmcp_`), `McpRateLimiter` (60/min par jeton).
- `McpJournalingTool` (décorateur autour des `SyncToolSpecification` pour journaliser sans contenu).
- Extension de `McpCallContext` (authKind, tokenId, scopes, hostIds) + `McpTransportContextFactory`.
- Frontend : `mcp-connections` (service, modèles, composant écran + route), lien depuis « Paramètres ».

### Préoccupations transversales — composants impactés

- **Auth / Principal (majeure)** : nouveau type de porteur (jeton personnel) sur la chaîne `/mcp`.
  Composants : `McpResourceServerConfig` (ajout du filtre personnel **avant** OAuth), `McpTransportContextFactory`,
  `McpCallContext`. **Non-régression** : les routes existantes et la chaîne principale inchangées ; un
  jeton personnel n'ouvre que `/api/mcp` (préfixe ignoré ailleurs, et la chaîne principale ne le lit pas).
- **Contexte tenant** : `user_id` (et `host_id`) résolus depuis le jeton, jamais d'un paramètre.
- **Plans / limites** : nouvelle limite par jeton (60/min) ; le périmètre `admin` réservé au rôle ADMIN.
- **Navigation** : nouvel écran « IA connectées » + lien depuis « Paramètres ».

---

## Plan de test

### Tests unitaires

- [ ] `McpPersonalTokenService` : hachage, expiration obligatoire ≤ 90 j, `admin` réservé ADMIN,
      poste d'un autre utilisateur refusé, révocation.
- [ ] `McpRateLimiter` : 60 passages puis refus.

### Tests d'intégration

- [ ] `POST /tokens` renvoie le clair une fois ; `GET /tokens` ne le renvoie jamais.
- [ ] Jeton personnel authentifie `/api/mcp` (conformité) ; révoqué → 401 ; expiré → 401.
- [ ] `GET /journal` liste les appels (sans contenu) ; une entrée est écrite après un appel d'outil.
- [ ] Isolation : un utilisateur ne voit ni ne révoque les jetons/journal d'un autre (404 / liste vide).

### Isolation utilisateur

- [ ] Applicable — tous les accès filtrés `user_id` ; postes validés `findByIdAndUserId`.

---

## Dépendances

- `SF-112-01` (serveur, chaîne `/mcp`) et `SF-112-02` (chaîne ressource) — Done.

### Migration

- `105-mcp-personal-tokens-journal.xml` — premier numéro libre strictement > 104. changeSet id unique,
  types compatibles H2 + PostgreSQL (`uuid`, `timestamp with time zone`, `varchar`), FK cascade.

---

## Notes et décisions

- Jeton en clair : préfixe `cgmcp_` + 32 octets base64url. Haché en **SHA-256** (secret à haute
  entropie : pas de bcrypt nécessaire). Le préfixe affiché (`cgmcp_ab12…`) aide à reconnaître un jeton.
- Le journal ne stocke **jamais** le contenu (paramètres résumés par leurs clés) — cadrage §6.6.
- Limite 60/min **en mémoire** par pod (foundation) ; une limite partagée multi-pod est un suivi.
