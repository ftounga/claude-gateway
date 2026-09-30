# Mini-spec — F-165 / SF-165-06 — actions `/compacter` + `/nouveau` + `/rappel` + `/aide`

## Identifiant

`F-165 / SF-165-06`

## Feature parente

`F-165` — Commandes slash dans le terminal (vues et actions, à la sauce claude-gateway)

## Statut

`ready`

## Date de création

2026-09-30

## Branche Git

`feat/SF-165-06-actions`

---

## Objectif

> En une phrase : ajouter les commandes slash **actions** `/compacter` (compaction douce), `/nouveau`
> (nouveau départ) et `/rappel <terme>` (recall — recherche dans l'historique, **sans tour modèle**), et
> compléter `/aide` avec la **liste complète groupée** des commandes ; les actions **réutilisent les
> canaux déjà existants** (sorties `compactNow`/`restart` du terminal, déjà câblées au parent) et le
> recall passe par un **petit endpoint de lecture** réutilisant la recherche F-162.

---

## Comportement attendu

### `/compacter` (action) et `/nouveau` (action)

1. Taper `/compacter` (ou `/nouveau`) et valider : interception AVANT tout `send` (`parsePanelCommand`) ;
   **aucun `send.emit()`** n'est émis (jamais un tour de conversation).
2. Le dispatch **émet la sortie existante** du terminal : `compactNow` pour `/compacter`, `restart` pour
   `/nouveau`. Ces sorties sont **déjà câblées** au parent (`atelier.component`) sur ses handlers
   `compactNow()` / `restartThread()` (F-162 / SF-162-04, F-39 / SF-39-04) — **aucun mécanisme
   réimplémenté**. Le parent produit ses retours habituels (barre de compaction, marqueur « Conversation
   compactée · N tours résumés », repli de l'historique, snackbars).
3. Un **panneau d'action** confirme localement le geste (« Compaction lancée… », « Nouveau départ lancé… »).

> **Note tour modèle** : `/compacter` déclenche la **compaction douce serveur** existante (`POST /compact`),
> qui produit une **synthèse serveur** — ce que cet endpoint fait **déjà** ; ce n'est **pas** un tour de
> conversation. `/nouveau` (`POST /restart`) ne relance **aucun** tour. Conforme au cadrage §7.

### `/rappel <terme>` (recherche, sans tour)

1. Taper `/rappel adressage réseau` et valider : interception AVANT tout `send`, **aucun tour**.
2. Le dispatch appelle **`GET /api/workspaces/{id}/chat/recall?q=<terme>`** (endpoint de **lecture**,
   jamais la boucle modèle) et rend un panneau `panelKind: 'recall'`, d'abord en **chargement**.
3. À la réponse, le panneau liste les **extraits** trouvés dans l'historique du fil (rôle, extrait borné,
   date), en indiquant si la recherche était **sémantique** ou **par mot-clé**.
4. Sans terme → invite « donnez un terme » (aucun appel). Aucun extrait → « aucun extrait trouvé ».

### `/aide` (méta, complétée)

- `/aide` liste **toutes** les commandes, **groupées par famille** (Vues / Actions / Aide), à partir du
  registre (déjà la source de vérité). Reste une **lecture locale**, **sans aucun tour**.

### Règle de câblage (recall = lecture, pas un tour)

- Le `recall` F-162 est un **outil d'agent** (pas d'endpoint REST). `/rappel` **réutilise ses briques de
  recherche** — recherche **sémantique** (`AtelierSemanticRecall.search`, si active) puis repli
  **mot-clé** (`AtelierMessageRepository.searchByContent`), **isolées `user_id` + `workspace_id`** — via un
  **petit endpoint de lecture** (le « petit endpoint d'agrégation » prévu au cadrage §1.2/§7). **Aucun tour
  modèle, aucune table.**

### Cas d'erreur / bord

| Situation | Comportement attendu |
|-----------|----------------------|
| `/rappel` sans terme | Panneau **invite** « donnez un terme » ; aucun appel, aucun tour |
| `/rappel` sans `projectId` | Panneau en **échec** neutre ; aucun appel |
| `/rappel` projet d'autrui | `requireOwned` → **404** ; panneau **échec** neutre, jamais l'historique d'autrui |
| `/rappel` aucun extrait | Panneau « aucun extrait trouvé pour … » (pas une erreur) |
| `/rappel` gateway muette | Panneau en **échec** neutre ; aucun tour |
| `/compacter` / `/nouveau` | Émettent la sortie existante ; le parent gère le résultat (inchangé) |
| Terminal en **lecture seule** (F-83) | Pas de composer → commandes impossibles (inchangé SF-165-01) |

---

## Critères d'acceptation

- [ ] `/compacter`, `/nouveau`, `/rappel` figurent au registre (famille **Action** ; `/rappel`
      `takesArgument`), et **toutes** les commandes apparaissent dans `/aide`, **groupées par famille**.
- [ ] Valider `/compacter` **émet la sortie `compactNow`** (pas `send`) ; `/nouveau` **émet `restart`**
      (pas `send`) — prouvé par des tests.
- [ ] Valider `/rappel <terme>` **n'émet jamais `send`** et appelle **`GET .../chat/recall?q=`** — prouvé
      par un test ; sans terme → invite, sans appel.
- [ ] **Backend** : l'endpoint `recall` **réutilise** la recherche existante (sémantique puis mot-clé),
      isolé **`user_id` + `requireOwned`**, borné (≤ 5 extraits) ; **aucune table, aucune migration**.
- [ ] **Isolation** : un utilisateur ne rappelle jamais l'historique d'un autre → **404** — prouvé par un
      test d'isolation.
- [ ] `/aide` liste la **liste complète et à jour** (10 commandes), groupée.
- [ ] Les panneaux se **ferment** (SF-165-01) et restent **hors** `displayedMessages`.
- [ ] **Design** : jetons `--cg-*` ; `tabular-nums` ; ≥ 44 px ; aucun débordement à 390 px (SF-158).
- [ ] **Non-régression** : `/aide`, `/cout`, `/contexte`, `/quota`, `/budget`, `/poste`, `/sujet`, `/revue`,
      message ordinaire, `@`, dictée, steer, porte, `demander` restent intacts ; boutons existants
      « Compacter »/« Nouveau départ » inchangés (mêmes sorties).
- [ ] Builds **verts** : `mvnw test` ciblé back + `npm run build` + Karma ciblé front.

---

## Périmètre

### Hors scope (explicite)

- Toute **nouvelle logique** de compaction / restart / recherche (réutilisation stricte).
- Une **confirmation destructive** pour `/nouveau` : le geste est **réversible** (rien n'est supprimé,
  l'historique se replie) — comme le bouton existant, il ne demande pas de confirmation.
- Toute **nouvelle table** ou migration.
- Toute logique de **moteur IA** (le recall est une **recherche**, pas un tour).

---

## Technique

### Endpoint(s)

| Méthode | Route | Rôle | Isolation |
|---------|-------|------|-----------|
| `GET` | `/workspaces/{id}/chat/recall?q=<terme>` | Recherche **en lecture** dans l'historique du fil (sémantique puis mot-clé), extraits bornés | `user_id` (JWT) **+** `requireOwned(userId, id)` → 404 sinon |

Réponse (`ThreadRecallResponse`) : `query`, `semantic` (bool), `extracts[]{role, excerpt, createdAt}`.
`/compacter` et `/nouveau` **n'ajoutent aucun endpoint** : ils émettent les sorties existantes du terminal
(`compactNow` → `POST /compact` ; `restart` → `POST /restart`), déjà câblées au parent.

### Tables impactées

Aucune. **Lecture seule** de `atelier_messages` (isolée `user_id` + `workspace_id`) + embeddings F-162
existants. **Aucune migration Liquibase.**

### Composants / fichiers

**Backend**
| Fichier | Rôle |
|---------|------|
| `atelier/dto/ThreadRecallResponse.java` (nouveau) | DTO de lecture (extraits bornés) |
| `atelier/AtelierRecallService.java` (nouveau) | Recherche : ownership (`requireOwned`), sémantique puis mot-clé, extraits bornés — réutilise `AtelierSemanticRecall` + `AtelierMessageRepository` |
| `atelier/AtelierChatController.java` (modifié) | `GET /recall` (controller fin) + injection |

**Frontend**
| Fichier | Rôle |
|---------|------|
| `atelier/terminal/slash-panel-commands.ts` (modifié) | Entrées `/compacter` + `/nouveau` + `/rappel` ; types recall + `actionMessage` ; `buildPanel` |
| `core/services/atelier-recall.service.ts` (nouveau) | `recall(workspaceId, query)` → `GET .../chat/recall?q=` |
| `atelier/terminal/atelier-slash-recall.component.ts` (+ html/scss, nouveau) | Corps `/rappel` (extraits) |
| `atelier/terminal/atelier-slash-action.component.ts` (+ html/scss, nouveau) | Corps commun `/compacter` + `/nouveau` (accusé local) |
| `atelier/terminal/atelier-slash-help.component.ts` (+ html, modifié) | `/aide` **groupé par famille** |
| `atelier/terminal/atelier-terminal.component.ts` (modifié) | Dispatch : `/compacter`→`compactNow.emit()`, `/nouveau`→`restart.emit()`, `/rappel`→GET ; imports |
| `atelier/terminal/atelier-terminal.component.html` (modifié) | `@case ('compact'/'restart'/'recall')` |

### Migration Liquibase

- [x] Non applicable

---

## Plan de test

### Backend

- [ ] `AtelierRecallServiceTest` (unitaire, Mockito) : mot-clé (repli) → extraits bornés ; sémantique actif
      → extraits réordonnés ; terme vide → vide ; aucun résultat → vide ; **isolation** (`requireOwned`
      lève → propagé, aucune recherche).
- [ ] `AtelierRecallApiIntegrationTest` (MockMvc) : propriétaire → **200** + extraits ; autrui → **404**.

### Frontend (Karma ciblé)

- [ ] `slash-panel-commands.spec.ts` (ajouts) : `/compacter` + `/nouveau` + `/rappel` au registre
      (Action ; `/rappel` `takesArgument`), `buildPanel` (action message ; recall loading).
- [ ] `atelier-recall.service.spec.ts` (nouveau) : `recall('w1','x')` → `GET .../recall?q=x`.
- [ ] `atelier-slash-recall.component.spec.ts` (nouveau) : loading / échec / vide / prêt (extraits).
- [ ] `atelier-slash-action.component.spec.ts` (nouveau) : rend le message d'accusé.
- [ ] `atelier-slash-help.component.spec.ts` (ajouts) : rendu **groupé par famille**, 10 commandes.
- [ ] `atelier-terminal.component.spec.ts` (ajouts) : `/compacter` **émet `compactNow`**, pas `send` ;
      `/nouveau` **émet `restart`**, pas `send` ; `/rappel x` **pas `send`**, un `GET .../recall?q=x`,
      panneau `ready` ; `/rappel` sans terme → invite sans appel.

### Isolation utilisateur

- [x] Applicable — back : `AtelierRecallService` (`requireOwned` + `searchByContent`/`findBy…IdIn` filtrés
  `user_id`+`workspace_id`) ; API 404 pour autrui. `/compacter`/`/nouveau` réutilisent des endpoints déjà
  isolés.

---

## Préoccupations transversales

| Préoccupation | Impact | Composants vérifiés / listés |
|--------------|--------|------------------------------|
| Auth / Principal | Aucun changement. `recall` lit l'identité du `CurrentUser` (JWT) | `AtelierChatController` (mêmes `currentUser.requireId()`) |
| Contexte tenant | **Nouvel accès données** (recall) isolé `user_id` + `requireOwned` + `workspace_id` | `AtelierRecallService`, `AtelierMessageRepository.searchByContent`/`findByWorkspaceIdAndUserIdAndIdIn` |
| Plans / limites | Aucun nouveau gate ; `/rappel` **gratuit** (recherche, aucun tour) ; `/compacter`/`/nouveau` réutilisent les gestes existants | `submit()` (interception avant gate) |
| Navigation / routing | Aucune route Angular ajoutée/modifiée (panneaux locaux) | — |

---

## Dépendances

### Subfeatures bloquantes

- **SF-165-01 → 05** — **livrées**.

### Questions ouvertes impactées

- Aucune de `docs/OPEN_QUESTIONS.md`.

---

## Notes et décisions

- **`/compacter` et `/nouveau` réutilisent les sorties EXISTANTES du terminal** (`compactNow`, `restart`),
  déjà câblées au parent (`atelier.component` → `compactNow()` / `restartThread()`). Dispatcher ces
  commandes revient exactement à cliquer les boutons existants : **aucun mécanisme réimplémenté**, retours
  UI (barre, marqueur, repli, snackbar) inchangés. Le panneau d'action n'est qu'un accusé local.
- **`/rappel` = une recherche, pas un tour** (cadrage §7). Le `recall` F-162 n'ayant pas d'endpoint REST
  (c'est un outil d'agent), `/rappel` réutilise ses **briques de recherche** (sémantique puis mot-clé,
  isolées) derrière un **petit endpoint de lecture** — la seule addition backend, prévue au cadrage.
  Aucun tour modèle, aucune table.
- **`/nouveau` sans confirmation destructive** : réversible (rien supprimé, historique replié) — cohérent
  avec le bouton existant et le design system (pas de `window.confirm`).
- **`/aide` groupé** : la liste vient du registre (source de vérité) ; le regroupement par famille est un
  rendu, pas une nouvelle donnée.
- **Gateway-First / Provider-First** : `/rappel` relaie la recherche existante ; `/compacter` et `/nouveau`
  rappellent des actions existantes — aucun moteur IA, aucun code dépendant directement d'Anthropic.
- **Aucune incohérence `ARCHITECTURE_CANONIQUE.md`** : aucune table, un seul endpoint de lecture.
