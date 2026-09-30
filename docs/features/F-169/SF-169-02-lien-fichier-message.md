# Mini-spec — F-169 / SF-169-02 — Lien fichier ↔ message (backend + contrat d'envoi)

## Identifiant

`F-169 / SF-169-02`

## Feature parente

`F-169` — Pièces jointes attachées au message

## Statut

`ready`

## Date de création

2026-09-30

## Branche Git

`feat/SF-169-02-lier-depot-message`

---

## Objectif

> En une phrase : quand le PO envoie un message avec des pièces jointes, associer **exactement**
> ces dépôts à **ce message** (`message_id` + `consumed_at`) au lieu de les consommer par fenêtre
> temporelle, et **persister** le lien pour que le frontend retrouve les fichiers d'un message au
> rechargement.

---

## Comportement attendu

### Cas nominal

1. Le frontend (déjà porteur des puces, SF-169-01) envoie `POST /workspaces/{id}/chat`
   (ou `/chat/stream`) avec, en plus de `message`/`mode`/`force`, une liste optionnelle
   `attachedDepositIds` (identifiants de dépôt renvoyés par `POST /workspaces/{id}/deposit`).
2. Au début du tour, quand `attachedDepositIds` est **non vide** : le service résout **exactement**
   ces dépôts, filtrés par `(user_id, workspace_id)` **et** non encore consommés, les marque
   consommés (`consumed_at`), et bâtit la **note de consigne** (chemins seulement, jamais le
   binaire) **à partir d'eux** — pas de fenêtre temporelle (aucune double-consommation).
3. Après persistance du message utilisateur, le service pose `message_id` sur exactement ces mêmes
   dépôts : le lien fichier ↔ message est durable.
4. Au rechargement du fil (`GET /workspaces/{id}/chat`), chaque message expose sa liste `files`
   (chemin + taille) reconstruite depuis `atelier_deposited_files.message_id`.
5. **Rétrocompat** : requête **sans** `attachedDepositIds` → comportement **inchangé** (consommation
   par fenêtre temporelle, `consumeForTurn`) ; aucun `message_id` posé. Les clients d'avant SF-169-02
   ne régressent pas.
6. Le dépôt reste **« chemin uniquement »** (Gateway-First / Provider-First) : l'agent lit par
   `read_file`, le binaire ne repasse jamais par le message.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| `attachedDepositIds` contient l'id d'un dépôt d'**un autre utilisateur / workspace** | Ignoré silencieusement (filtre `user_id`+`workspace_id`) — **jamais** attaché ni consommé | 200 (tour normal) |
| `attachedDepositIds` contient un id **inconnu** ou **déjà consommé** | Ignoré (le filtre `consumed_at IS NULL` + `id IN` ne le remonte pas) | 200 |
| `attachedDepositIds` vide ou absent | Fenêtre temporelle historique (`consumeForTurn`) | 200 |
| Workspace non possédé | 404 (isolation, `requireOwned` en tête de tour, inchangé) | 404 |
| Échec best-effort de la consigne de dépôt | Le tour continue (comme F-115) ; jamais de 500 pour un défaut de consigne | 200 |

---

## Critères d'acceptation

- [ ] La colonne `atelier_deposited_files.message_id` (uuid, **nullable**) existe (migration Liquibase
      **136**, changesets `postgresql` + `h2`, index sur `message_id`), réversible (`dropColumn`).
- [ ] `AtelierChatRequest` porte `attachedDepositIds` (`List<UUID>`, optionnel) ; les constructeurs
      historiques et le contrat sans réfs restent valides (rétrocompat).
- [ ] Avec `attachedDepositIds` non vide : **exactement** ces dépôts (filtrés `user_id`+`workspace_id`
      + non consommés) sont marqués consommés, portent `message_id` = id du message utilisateur, et
      alimentent la note de consigne (chemins). La fenêtre temporelle n'est **pas** invoquée.
- [ ] Un id de dépôt appartenant à un autre `(user_id, workspace_id)` n'est **jamais** attaché ni
      consommé (test d'isolation explicite).
- [ ] Sans `attachedDepositIds` : `consumeForTurn` (fenêtre temporelle) est utilisé, aucun
      `message_id` posé — comportement d'avant SF-169-02 (rétrocompat vérifiée).
- [ ] `GET /workspaces/{id}/chat` expose `files` (chemin + taille) par message, reconstruits depuis
      `message_id` ; un message sans pièce jointe rend `files` vide.
- [ ] `POST /workspaces/{id}/deposit` expose l'`id` de chaque dépôt (pour que SF-169-03 le renvoie).
- [ ] Aucun traitement lourd synchrone ; l'agent lit toujours par `read_file` (Gateway-First).

---

## Périmètre

### Hors scope (explicite)

- Rendu des fichiers dans la bulle et capture des ids côté frontend → **SF-169-03**.
- Repasser le binaire par le message (jamais : modèle « chemin »).
- Endpoint de suppression d'un dépôt côté backend.
- Garbage-collection des dépôts d'une puce retirée avant envoi (restent non consommés ; une prochaine
  demande sans réfs les consommera par fenêtre — auto-cicatrisation, hors scope ici).

---

## Valeurs initiales

| Champ | Valeur initiale | Règle |
|-------|----------------|-------|
| `atelier_deposited_files.message_id` | `null` | posé à l'association (envoi avec réfs) ; reste `null` pour les dépôts consommés par fenêtre |

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format / Valeurs | Unicité | Normalisation |
|-------|-------------|-------------|------------------|---------|---------------|
| `attachedDepositIds` | Non | — | liste d'UUID ; null/vide = pas de réfs | Non | dédupliqué à la résolution (idempotent par `id IN`) |

---

## Technique

### Endpoint(s)

| Méthode | URL | Auth | Changement |
|---------|-----|------|-----------|
| POST | `/workspaces/{id}/chat` | Oui | corps porte `attachedDepositIds?` |
| POST | `/workspaces/{id}/chat/stream` | Oui | idem (première itération du tour uniquement) |
| GET | `/workspaces/{id}/chat` | Oui | réponse porte `files` par message |
| POST | `/workspaces/{id}/deposit` | Oui | réponse porte `id` par fichier déposé |

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| `atelier_deposited_files` | ALTER (add `message_id`) + UPDATE (association) + SELECT (rendu) | index `message_id` |

### Migration Liquibase

- [x] Oui — `136-atelier-deposited-files-message-id.xml` (postgresql + h2, index, rollback `dropColumn`)

### Composants Angular

- Aucun (backend seul). SF-169-03 consommera `files` et l'`id` du dépôt.

---

## Préoccupations transversales

- [x] **Contexte tenant / persistance du fil** — cochée.

**Composants impactés (liste obligatoire) :**
1. `DepositConsumptionService` — nouvelle voie `consumeForMessage(userId, workspaceId, depositIds)`
   (association par ids, isolation stricte) + `linkToMessage(...)` (pose `message_id`) ; `consumeForTurn`
   (fenêtre) **inchangé** pour la rétrocompat.
2. `AtelierChatService` — `runLoop`/`chat`/`chatStreaming` portent `attachedDepositIds` (paramètre, pas
   ThreadLocal : le flux SSE tourne sur un autre thread) ; nouvelle méthode `attachedFilesByMessage(...)`
   pour le rendu à l'historique. Isolation `requireOwned` en tête, inchangée.
3. `AtelierDepositedFileRepository` — `findByUserIdAndWorkspaceIdAndIdInAndConsumedAtIsNullOrderByCreatedAtAsc`
   (association) + `findByUserIdAndWorkspaceIdAndMessageIdInOrderByCreatedAtAsc` (rendu).
4. Sérialisation du transcript — `AtelierMessageResponse` gagne `files` (additif) ;
   `AtelierController#deposit`/`DepositResponse` exposent l'`id` du dépôt ; `AtelierChatController#history`
   assemble les `files`.
5. `WorkspaceDepositService` — `record(...)` retourne l'`id` persisté pour le porter dans la réponse.
6. Entité `AtelierDepositedFile` — champ `messageId` (nullable, settable).

---

## Plan de test

### Tests unitaires

- [ ] `DepositConsumptionService.consumeForMessage` — cas nominal : marque consommés + bâtit la note à
      partir des ids fournis (chemins, `read_file`, pas de binaire).
- [ ] `DepositConsumptionService.consumeForMessage` — **isolation** : un id d'un autre
      `(user_id, workspace_id)` n'est jamais remonté ni consommé (requête filtrée).
- [ ] `DepositConsumptionService.linkToMessage` — pose `message_id` sur exactement les dépôts fournis.
- [ ] `AtelierChatService` — avec `attachedDepositIds` : `consumeForMessage` utilisé,
      `consumeForTurn` **jamais** appelé ; la consigne envoyée porte les chemins ; `message_id` posé
      sur les dépôts.
- [ ] `AtelierChatService` — **rétrocompat** : sans réfs, `consumeForTurn` utilisé (comportement F-115
      inchangé), aucun `message_id`.

### Tests d'intégration

- [ ] Envoi avec `attachedDepositIds` → `message_id` posé + `GET /chat` rend `files` (chemin + taille)
      pour le message utilisateur (transcript rechargé).
- [ ] `POST /deposit` → la réponse porte l'`id` de chaque fichier.

### Isolation workspace / utilisateur

- [x] Applicable — un dépôt d'un autre utilisateur/workspace n'est jamais attaché (filtre `user_id`
      + `workspace_id` dans la requête d'association).

---

## Dépendances

### Subfeatures bloquantes

- `SF-169-01` — Done (puces dans le composer). SF-169-03 dépend de celle-ci.

### Questions ouvertes impactées

- Aucune (`docs/OPEN_QUESTIONS.md`).

---

## Notes et décisions

- **Référence = id de dépôt** (et non chemin) : plus robuste que le chemin (deux dépôts de même nom
  → deux lignes de même `path`, indissociables), et exact pour l'isolation (`id IN` + `user_id` +
  `workspace_id`). Impose d'exposer l'`id` dans la réponse de dépôt (backend, en scope). **Flag
  d'arbitrage par défaut.**
- **Association en deux temps** : `consumed_at` posé en tête de tour (note de consigne) puis
  `message_id` posé après la persistance du message (l'id du message n'existe qu'à ce moment). Les
  deux passes filtrent `user_id`+`workspace_id`.
- **Paramètre, pas ThreadLocal** : `attachedDepositIds` est porté par l'appel (comme `force`, F-161),
  parce que la boucle SSE tourne sur un thread distinct de la requête.
- **Contrat de flux (SSE) inchangé** : `StreamDone` ne change pas. Le frontend rend les pièces jointes
  du message utilisateur depuis ses puces locales à l'envoi (SF-169-03) ; le rechargement les
  reconstruit via `GET /chat`.
