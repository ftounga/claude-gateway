# Mini-spec — [F-41 / SF-41-02] Plan BYOK sans clé : un refus qui dit quoi faire

## Identifiant

`F-41 / SF-41-02`

## Feature parente

`F-41` — Plan BYOK (plateforme seule)

## Statut

`ready`

## Date de création

2026-09-07

## Branche Git

`feat/SF-41-02-refus-sans-cle`

---

## Objectif

Quand l'offre est **BYOK** et qu'**aucune clé n'est enregistrée**, refuser l'appel **avant** tout
contact avec le fournisseur, par un message qui dit **quoi faire et où le faire** — jamais un 500,
jamais un appel envoyé avec la clé de la plateforme.

---

## Comportement attendu

### Le défaut d'aujourd'hui, en clair

`ByokKeyService.resolveActiveApiKey` rend `Optional.empty()` quand il n'y a pas de clé active, et
tous les appelants font `.orElse(null)` : l'appel part alors **avec la clé plateforme** (mode
Hosted). C'est le comportement correct pour un abonné Hosted — et exactement le mauvais pour un
abonné BYOK, qui ne paie aucun jeton à la plateforme. Depuis SF-41-01, son quota ne le bloque plus
non plus : sans ce garde-fou, il consommerait silencieusement les jetons de la gateway.

### Cas nominal

1. L'utilisateur a une offre **BYOK en cours** (`ACTIVE`/`PAST_DUE`) et **aucune clé active**.
2. Le pré-vol — `QuotaService.assertWithinQuota`, déjà le point commun des trois chemins servis —
   lève `ByokKeyRequiredException`.
3. **Aucun** appel fournisseur n'est émis, **aucun** message n'est persisté, **aucune** consommation
   n'est enregistrée.
4. Le client reçoit un refus nommé et actionnable :
   - REST : `409` `{"error":"byok_key_required","message":"Votre offre BYOK utilise votre propre clé Anthropic, mais aucune clé n'est enregistrée. Ajoutez-la depuis Paramètres, section « Clé API »."}`
   - SSE (chat en streaming, Atelier) : événement `error` de code `byok_key_required` — le refus
     voyage **dans le flux**, jamais en 403/409 HTTP (l'endpoint produit `text/event-stream`).
5. Dès qu'une clé est enregistrée (F-03), l'appel repart normalement, servi par cette clé.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Offre BYOK en cours, aucune clé enregistrée | Refus nommé, actionnable, avant tout appel fournisseur | 409 `byok_key_required` |
| Offre BYOK en cours, clé enregistrée mais **désactivée** (mode Hosted, F-03/SF-03-03) | Même refus : une clé inactive ne sert pas l'appel | 409 `byok_key_required` |
| Offre BYOK **résiliée**, aucune clé | Le refus de quota prime (l'abonnement d'abord, la clé ensuite) | 402 `quota_exceeded` |
| Offre Hosted (Solo/Pro/Gold/Daily), aucune clé | **Inchangé** : la clé plateforme sert l'appel | 200 |
| Offre Hosted, clé BYOK active | **Inchangé** : la clé de l'utilisateur sert l'appel | 200 |
| Essai gratuit, aucune clé | **Inchangé** : quota d'essai, clé plateforme | 200 |
| Même situation sur un endpoint SSE | Refus **dans le flux** (`error: byok_key_required`), jamais un 409 | 200 + event `error` |

---

## Critères d'acceptation

- [ ] `ByokKeyRequiredException` existe dans le paquet `byok` et est traduite en `409 byok_key_required` par `GlobalExceptionHandler`, avec un message qui **nomme l'écran** où déposer la clé.
- [ ] `ByokKeyService.requireActiveApiKey(userId)` rend la clé déchiffrée, ou lève `ByokKeyRequiredException` si aucune clé **active** n'existe. La clé n'est ni journalisée, ni renvoyée en clair au client.
- [ ] `QuotaService.assertWithinQuota` exige la clé **avant** de rendre la main, pour une offre BYOK en cours — donc avant tout appel fournisseur et toute persistance.
- [ ] `POST /api/chat` → 409 `byok_key_required` pour un abonné BYOK sans clé ; **aucun message persisté**, **aucune consommation enregistrée**.
- [ ] `POST /api/chat/stream` → événement SSE `error` de code `byok_key_required` (jamais un 409, jamais `internal_error`).
- [ ] `POST /api/workspaces/{id}/chat` (Atelier, SSE) → événement `error` de code `byok_key_required`.
- [ ] `POST /api/workspaces/{id}/agent` (Atelier Managed Agents, SSE) → événement `error` de code `byok_key_required`.
- [ ] **Non-régression Hosted** : un abonné Solo/Pro/Gold/Daily sans clé, et un abonné en essai, continuent d'être servis par la clé plateforme — aucune exception levée.
- [ ] **Ordre des refus** : un abonné BYOK **résilié** reçoit `402 quota_exceeded` (l'abonnement d'abord), pas `409 byok_key_required`.
- [ ] Isolation : la clé exigée est toujours celle du `userId` du contexte de sécurité ; l'absence de clé chez un utilisateur ne refuse jamais un autre.

---

## Périmètre

### Hors scope (explicite)

- Tout écran ou message frontend (→ SF-41-03) : cette subfeature s'arrête au contrat d'API.
- La **validation** de la clé au moment de l'appel : elle est déjà faite à l'enregistrement (F-03) ;
  une clé devenue invalide chez Anthropic reste une erreur fournisseur (`provider_error`).
- Le comportement d'un abonné **Hosted** qui possède une clé : inchangé, la clé prime (F-03).
- Toute modification du quota, du catalogue ou du prix (livrés en SF-41-01).

---

## Valeurs initiales

Sans objet : cette subfeature ne crée aucune entité et ne modifie aucun état initial.

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format / Valeurs autorisées | Unicité | Normalisation |
|-------|-------------|-------------|----------------------------|---------|---------------|
| code d'erreur REST | Oui | — | `byok_key_required` (stable, contractuel) | — | — |
| code d'erreur SSE | Oui | — | `byok_key_required` (identique au REST : un seul vocabulaire) | — | — |
| message d'erreur | Oui | — | français, actionnable, nomme l'écran ; **jamais** la clé, même masquée | — | — |

Notes :
- Le code `409` (conflit d'état) est retenu par cohérence avec `byok_mode_conflict` déjà servi par
  F-03 pour la situation jumelle (« activer BYOK sans clé »). Un `402` dirait « payez », ce qui est
  faux : le client a payé. Un `403` dirait « vous n'avez pas le droit », faux également.

---

## Technique

### Endpoint(s)

| Méthode | URL | Auth | Rôle minimum |
|---------|-----|------|-------------|
| POST | `/api/chat` | Oui | USER — 409 `byok_key_required` |
| POST | `/api/chat/stream` | Oui | USER — event `error: byok_key_required` |
| POST | `/api/workspaces/{id}/chat` | Oui | USER — event `error: byok_key_required` |
| POST | `/api/workspaces/{id}/agent` | Oui | USER — event `error: byok_key_required` |

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| `user_api_keys` | SELECT | lecture par `user_id` (existante, F-03) |
| `subscriptions` | SELECT | lecture par `user_id` (existante) |

Aucune écriture ajoutée.

### Migration Liquibase

- [ ] Oui
- [x] **Non applicable** — aucun changement de schéma.

### Composants Angular (si applicable)

Aucun dans cette subfeature (→ SF-41-03).

---

## Plan de test

### Tests unitaires

- [ ] `ByokKeyServiceTest` — `requireActiveApiKey` rend la clé déchiffrée quand elle est active.
- [ ] `ByokKeyServiceTest` — `requireActiveApiKey` lève `ByokKeyRequiredException` quand aucune clé n'existe.
- [ ] `ByokKeyServiceTest` — `requireActiveApiKey` lève aussi quand la clé existe mais est **désactivée** (mode Hosted).
- [ ] `QuotaServiceTest` — offre BYOK sans clé ⇒ `assertWithinQuota` lève `ByokKeyRequiredException`.
- [ ] `QuotaServiceTest` — offre BYOK **avec** clé ⇒ ne lève pas, et le compteur de période n'est pas lu.
- [ ] `QuotaServiceTest` — offre Hosted sans clé ⇒ la clé n'est **jamais** exigée (non-régression).
- [ ] `QuotaServiceTest` — offre BYOK résiliée ⇒ `QuotaExceededException`, pas `ByokKeyRequiredException` (ordre des refus).

### Tests d'intégration

- [ ] `POST /api/chat` (BYOK sans clé) → 409 `byok_key_required` ; aucun message persisté, aucun compteur d'usage écrit.
- [ ] `POST /api/chat` (BYOK **avec** clé) → 200, et la requête fournisseur porte bien la clé de l'utilisateur.
- [ ] `POST /api/chat` (Solo actif, sans clé) → 200 (non-régression Hosted).
- [ ] `POST /api/chat/stream` (BYOK sans clé) → 200 + event SSE `error` `byok_key_required`, jamais `internal_error`.
- [ ] `POST /api/workspaces/{id}/chat` (BYOK sans clé) → event SSE `error` `byok_key_required`.
- [ ] `POST /api/workspaces/{id}/agent` (BYOK sans clé) → event SSE `error` `byok_key_required`.

### Isolation utilisateur

- [x] Applicable — test : deux abonnés BYOK, l'un avec clé, l'autre sans ; le premier est servi, le
  second refusé, et aucun ne voit la clé ni le refus de l'autre. La clé est toujours résolue depuis
  le `userId` du contexte de sécurité.

---

## Préoccupations transversales

| Préoccupation | Cochée | Composants impactés |
|--------------|--------|---------------------|
| **Auth / Principal** | Non | Aucun changement du Principal ni du mode d'authentification. |
| **Contexte tenant** | Non | Le `userId` reste celui du `SecurityContext` ; aucune nouvelle façon de le résoudre. |
| **Plans / limites** | **Oui** | Nouveau gate posé sur le pré-vol commun. Composants qui appellent ce pré-vol, **tous revus** : `ChatService.reply` (REST), `ChatService.prepareStream` (SSE), `AtelierChatService.chatStreaming` (SSE), `AtelierSessionService.runTaskStreaming` (SSE), et leurs quatre contrôleurs — `ChatController`, `AtelierChatController`, `AtelierAgentController`. Les trois contrôleurs SSE traduisent le refus **dans le flux** ; sans cela il tomberait dans leur `catch (RuntimeException)` et sortirait en `internal_error`. |
| **Navigation / routing** | Non | Aucune route ajoutée ou modifiée. |

---

## Dépendances

### Subfeatures bloquantes

- `SF-41-01` (plan BYOK au catalogue) — statut : **done** (sur `main`).
- `SF-03-02` / `SF-03-03` (clé chiffrée, mode fournisseur) — statut : **done**.

### Questions ouvertes impactées

Aucune.

---

## Notes et décisions

- **Pourquoi le pré-vol de quota, et pas le point de résolution de la clé.** Les trois chemins
  résolvent la clé à trois endroits différents (`ChatService` ×2, `AtelierChatService`), mais ils
  passent **tous** par `QuotaService.assertWithinQuota` avant. Poser le garde-fou là où ils passent
  déjà vaut mieux qu'en poser trois — un quatrième chemin ajouté demain hérite du refus au lieu de
  l'oublier. C'est aussi le seul endroit qui connaît déjà l'abonnement : la question « faut-il une
  clé ? » est une question de **plan**, pas de clé.
- **Pourquoi 409 et pas 402.** Le client BYOK a payé. Lui répondre « paiement requis » l'enverrait
  sur la page de facturation, où il ne trouverait rien à corriger. `409` — conflit d'état, code déjà
  utilisé par F-03 pour la situation jumelle — l'envoie au bon endroit.
- **L'ordre des refus est un choix.** L'abonnement est vérifié avant la clé : un abonné résilié qui
  n'a pas de clé doit d'abord entendre qu'il n'est plus abonné. Lui parler de sa clé le ferait
  travailler pour rien.
