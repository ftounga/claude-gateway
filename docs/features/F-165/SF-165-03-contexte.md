# Mini-spec — F-165 / SF-165-03 — `/contexte` : l'état mémoire du fil

## Identifiant

`F-165 / SF-165-03`

## Feature parente

`F-165` — Commandes slash dans le terminal (vues et actions, à la sauce claude-gateway)

## Statut

`ready`

## Date de création

2026-09-30

## Branche Git

`feat/SF-165-03-contexte`

---

## Objectif

> En une phrase : ajouter la commande slash **`/contexte`** — une **vue** qui affiche, **sans aucun tour
> modèle**, l'**état mémoire du fil** (taille du contexte vivant en tokens ≈ pages, part vivante vs
> rangée, présence d'un résumé ancré, progression vers le seuil de compaction, et l'état du rappel),
> servie par un **petit endpoint d'agrégation en lecture** isolé `user_id` + propriété du workspace.

---

## Comportement attendu

### Cas nominal

1. Dans le composer, taper `/contexte` (ou le choisir dans l'autocomplétion, socle SF-165-01) et valider :
   la commande est **interceptée AVANT tout `send`** (`parsePanelCommand`), **aucun tour modèle** n'est
   émis (`dispatchPanelCommand` n'appelle jamais `send.emit()`).
2. Le dispatch **branche l'appel REST de lecture** `GET /api/workspaces/{id}/chat/context-summary` (une
   **VUE** appelle un endpoint REST, jamais la boucle modèle), et rend un **panneau**
   `panelKind: 'context'` dans le fil (cadre réutilisable SF-165-01), d'abord en état **chargement**.
3. À la réponse, le panneau affiche l'**état mémoire du fil** en langage « classeur » :
   - **taille du contexte vivant** (tokens ≈ « pages ») ;
   - **progression vers le seuil de compaction** : jauge `contexte vivant / seuil` (`fillPercent` %) avec
     le seuil rendu en tokens ≈ pages ;
   - **part vivante vs rangée** : tours **vivants** (rejouables) vs tours **rangés** (repliés) ;
   - **résumé ancré** : présent ou non (la mémoire longue conservée par la compaction douce, F-162) ;
   - **compaction** : active ou coupée, et le nombre de messages récents gardés entiers
     (`keepRecentTurns`) ;
   - **rappel** : « mot-clé (toujours) » + « sémantique » actif ou non.
4. Le panneau se **ferme** par son bouton (mécanisme SF-165-01), reste **local et éphémère** (jamais
   dans `displayedMessages`, jamais dans l'historique envoyé au modèle).

### Règle de composition (aucun recalcul modèle, aucune table)

- **Ownership + tours** : `AtelierThreadService.resumeState(userId, id)` (`requireOwned` → 404 sinon)
  fournit `turns` (vivants), `foldedTurns` (rangés) et `threadStartedAt` (frontière du fil courant).
- **Contexte vivant** = `input_tokens` du **dernier tour** du fil courant (même proxy que `/cout`,
  SF-165-02), lu sur `usage_turns` isolé `user_id` + `workspace_id`. « Pages » ≈ `tokens / 500`.
- **Seuil de compaction** = `AtelierCompactionProperties.triggerTokens()` (défaut 120 000), avec
  `enabled()` et `keepRecentTurns()`. `fillPercent` = `min(100, contextTokens / triggerTokens × 100)`.
- **Résumé ancré** = présence de `Workspace.chatThreadSummary` (non nul et non vide), lue via
  `WorkspaceRepository.findByIdAndUserId` (isolé `user_id`).
- **Rappel sémantique** = `AtelierSemanticRecall.isEnabled()` (capacité serveur, F-162).

### Cas d'erreur / bord

| Situation | Comportement attendu |
|-----------|----------------------|
| Workspace d'un **autre** utilisateur | `requireOwned` → **404** indiscernable ; le panneau affiche un **état d'échec** neutre, jamais l'état d'autrui |
| Aucun `projectId` connu côté écran | Panneau en **état d'échec** neutre (pas d'appel), aucun tour |
| Gateway muette / erreur réseau | Panneau en **état d'échec** neutre (« contexte indisponible ») ; aucun tour, aucune exception non gérée |
| Fil **neuf** (aucun tour facturé) | Contexte à 0, `fillPercent` 0, pas de résumé ancré — lisible, pas une erreur |
| Seuil mal configuré (0/négatif) | `AtelierCompactionProperties` retombe sur le défaut 120 000 (jamais division par zéro) |
| Terminal en **lecture seule** (mosaïque, F-83) | Pas de composer → commande impossible (inchangé SF-165-01) |

---

## Critères d'acceptation

- [ ] `/contexte` figure au registre `SLASH_PANEL_COMMANDS` (famille **Vue**, `panelKind: 'context'`) et
      apparaît dans l'autocomplétion et dans `/aide`.
- [ ] Valider `/contexte` **n'émet jamais `send`** (garantie « aucun tour ») et **ajoute un panneau**
      `panelKind: 'context'` dans le fil — prouvé par un test.
- [ ] Le dispatch appelle `GET /api/workspaces/{id}/chat/context-summary` (endpoint REST de **lecture**),
      et **jamais** la boucle modèle — prouvé par un test (aucun `send`, un GET).
- [ ] Le panneau rend : contexte vivant (tokens + pages), progression vers le seuil (fillPercent + seuil),
      part vivante vs rangée, présence du résumé ancré, état compaction (active + keepRecentTurns), état du
      rappel sémantique.
- [ ] **Backend** : l'endpoint compose `resumeState` (ownership) + `usage_turns` (isolé `user_id` +
      `workspace_id`) + `AtelierCompactionProperties` + présence de `chatThreadSummary` + capacité recall ;
      **aucune table, aucune migration**.
- [ ] **Isolation** : un utilisateur ne voit **jamais** l'état d'un workspace/utilisateur qui n'est pas le
      sien → **404** — prouvé par un test d'isolation.
- [ ] Le panneau se **ferme** (bouton fermer, SF-165-01) et reste **hors** `displayedMessages`.
- [ ] **Design** : jetons `--cg-*` uniquement ; `tabular-nums` sur les chiffres ; cibles ≥ 44 px ; aucun
      débordement à 390 px (SF-158) ; aucune couleur/police hors `DESIGN_SYSTEM.md`.
- [ ] **Non-régression** : `/aide`, `/cout`, `/revue` (macro F-121), message ordinaire, autocomplétion `@`,
      dictée, steer, porte d'autorisation, outil `demander` restent intacts.
- [ ] Builds **verts** au premier plan : `mvnw test` ciblé back + `npm run build` + Karma ciblé front.

---

## Périmètre

### Hors scope (explicite)

- Les autres commandes `/quota`, `/budget`, `/poste`, `/sujet`, `/compacter`, `/nouveau`, `/rappel`
  (SF-165-04 → 06) et `/cout` (SF-165-02, livrée).
- Un **décompte exact** des tokens de contexte (la gateway n'en fait pas : la compaction estime par
  heuristique caractères/token pour éviter un appel réseau par tour, F-117). `/contexte` réutilise le
  **proxy** `input_tokens` du dernier tour, cohérent avec `/cout`.
- Une **couverture de recall par fil** (ratio messages indexés / total) : **inexistante** aujourd'hui
  (l'embedding backfill est global, F-162). `/contexte` expose l'**état** du rappel sémantique
  (actif/inactif), pas une couverture chiffrée — voir §Notes.
- Toute **nouvelle table** ou migration (aucune : lecture seule).
- Toute logique de **moteur IA** (aucune ; `/contexte` est une vue sur nos données).

---

## Technique

### Endpoint(s)

| Méthode | Route | Rôle | Isolation |
|---------|-------|------|-----------|
| `GET` | `/workspaces/{id}/chat/context-summary` | Agrégation **en lecture** de l'état mémoire du fil courant | `user_id` (JWT) **+** `requireOwned(userId, id)` → 404 sinon |

Réponse (`ThreadContextSummaryResponse`) : `contextTokens`, `contextPages`, `liveTurns`, `foldedTurns`,
`hasAnchoredSummary`, `compactionEnabled`, `triggerTokens`, `triggerPages`, `fillPercent`,
`keepRecentTurns`, `recallSemantic`. **Aucun contenu** (ni message, ni résumé, ni chemin) : des volumes,
des drapeaux et un seuil seulement.

### Tables impactées

Aucune création. **Lecture seule** de `usage_turns` (isolée `user_id` + `workspace_id`), de l'état de
reprise (`/resume`) et de `workspaces.chat_thread_summary` (présence uniquement, isolé `user_id`).
**Aucune migration Liquibase.**

### Composants / fichiers

**Backend**
| Fichier | Rôle |
|---------|------|
| `atelier/dto/ThreadContextSummaryResponse.java` (nouveau) | DTO de lecture (volumes + drapeaux + seuil, aucun contenu) |
| `atelier/AtelierThreadContextService.java` (nouveau) | Agrégation : ownership via `resumeState`, contexte vivant depuis `usage_turns`, seuil via `AtelierCompactionProperties`, résumé ancré via `WorkspaceRepository`, recall via `AtelierSemanticRecall` |
| `atelier/AtelierChatController.java` (modifié) | `GET /context-summary` (controller fin, `currentUser.requireId()`) |

**Frontend**
| Fichier | Rôle |
|---------|------|
| `atelier/terminal/slash-panel-commands.ts` (modifié) | Entrée registre `/contexte` ; types `ThreadContextSummary`/`ContextPanelState` ; `SlashPanel.context`/`contextState` ; `buildPanel` (état chargement) |
| `core/services/atelier-context.service.ts` (nouveau) | `contextSummary(workspaceId)` → `GET /api/workspaces/{id}/chat/context-summary` |
| `atelier/terminal/atelier-slash-contexte.component.ts` (+ html/scss, nouveau) | Corps du panneau `/contexte` (jauge de seuil, vivant/rangé, résumé ancré, compaction, rappel) |
| `atelier/terminal/atelier-terminal.component.ts` (modifié) | Dispatch `/contexte` (appel REST + mise à jour du panneau par id) ; import du corps |
| `atelier/terminal/atelier-terminal.component.html` (modifié) | `@case ('context')` |

### Migration Liquibase

- [x] Non applicable

---

## Plan de test

### Backend

- [ ] `AtelierThreadContextServiceTest` (unitaire, Mockito) :
  - contexte vivant = `input_tokens` du dernier tour du fil courant ; pages ; fillPercent vs seuil ;
  - **fenêtre fil courant** : tours avant `threadStartedAt` exclus ;
  - fil vide → tout à 0, `fillPercent` 0 (pas d'erreur) ;
  - résumé ancré : `chatThreadSummary` non vide → `hasAnchoredSummary` vrai ; nul/vide → faux ;
  - seuil : défaut 120 000 quand la config est absente/incohérente ;
  - **isolation** : `resumeState` lève `WorkspaceNotFoundException` (non-propriétaire) → le service propage
    (aucun état calculé) ;
  - le dépôt `usage_turns` est interrogé avec **exactement** `userId` + `workspaceId`.
- [ ] `AtelierThreadContextApiIntegrationTest` (MockMvc, `@SpringBootTest`) :
  - propriétaire : `GET .../context-summary` → **200** + JSON attendu ;
  - **isolation** : un autre utilisateur sur le même workspace → **404** (indiscernable).

### Frontend (Karma ciblé)

- [ ] `slash-panel-commands.spec.ts` (ajouts) : `/contexte` au registre (famille Vue,
      `panelKind 'context'`), `panelCommandSuggestions('/con')` contient `contexte`,
      `buildPanel(contexte,…)` → `contextState: 'loading'`.
- [ ] `atelier-context.service.spec.ts` (nouveau) : `contextSummary('w1')` appelle
      `GET /api/workspaces/w1/chat/context-summary` et mappe la réponse (`HttpTestingController`).
- [ ] `atelier-slash-contexte.component.spec.ts` (nouveau) : rend la jauge/les compteurs en `ready` ;
      chargement et échec rendus sans planter ; résumé ancré présent/absent.
- [ ] `atelier-terminal.component.spec.ts` (ajouts) : dispatcher `/contexte` **n'émet pas `send`**, ajoute
      un panneau `panelKind 'context'`, déclenche **un** `GET .../context-summary` ; à la réponse le panneau
      passe en `ready` ; sans projet → `error`.

### Isolation utilisateur

- [x] Applicable — couverte back (service + API : 404 pour autrui) et par construction (filtre `user_id` +
  `workspace_id` sur `usage_turns` et `workspaces`, `requireOwned` via `resumeState`).

---

## Préoccupations transversales

| Préoccupation | Impact | Composants vérifiés / listés |
|--------------|--------|------------------------------|
| Auth / Principal | Aucun changement. L'endpoint lit l'identité du `CurrentUser` (JWT), comme `resume`/`cost-summary` | `AtelierChatController` (mêmes `currentUser.requireId()`) |
| Contexte tenant | **Nouvel accès données** — isolé `user_id` + `requireOwned` | `AtelierThreadContextService` (ownership via `resumeState`), `UsageTurnRepository` (filtre `user_id`+`workspace_id`), `WorkspaceRepository.findByIdAndUserId` |
| Plans / limites | Aucun nouveau gate ; `/contexte` est **gratuit** (aucun tour, ne touche ni quota ni plafond) | `submit()` (interception avant le gate, inchangé SF-165-01) |
| Navigation / routing | Aucune route Angular ajoutée/modifiée (panneau local dans le fil) | — |

---

## Dépendances

### Subfeatures bloquantes

- **SF-165-01** (socle : registre, dispatch, cadre de panneau) — **livrée** sur `main`.
- **SF-165-02** (`/cout`, patron du couple service/endpoint de lecture) — **livrée** sur `main`.

### Questions ouvertes impactées

- Aucune de `docs/OPEN_QUESTIONS.md`.

---

## Notes et décisions

- **Décision — contexte vivant = proxy `input_tokens` du dernier tour**, cohérent avec `/cout` (SF-165-02).
  La gateway ne tient pas de décompte exact du contexte : la compaction (F-117) l'**estime** par
  heuristique caractères/token pour éviter un aller-retour réseau par tour. Le proxy `input_tokens` du
  dernier tour est la meilleure mesure déjà journalisée ; il alimente la jauge de progression vers le
  seuil `triggerTokens`.
- **Décision — l'état du rappel, pas une couverture chiffrée.** Il n'existe **aucune** métrique de
  couverture de recall par fil (l'embedding backfill F-162 est global, pas par workspace). `/contexte`
  expose donc l'**état** : le rappel par **mot-clé** couvre toujours tout le fil (recherche `LIKE`), et le
  rappel **sémantique** est **actif ou non** (`AtelierSemanticRecall.isEnabled()`). Aucune valeur inventée.
- **Garantie « aucun tour »** conservée du socle : `dispatchPanelCommand` n'appelle jamais `send.emit()` ;
  `/contexte` = **un GET de lecture** + rendu local. Le panneau vit dans le signal `slashPanels`, **hors**
  `displayedMessages`.
- **Gateway-First / Provider-First** : `/contexte` est une **vue sur NOS données** (`usage_turns`,
  `/resume`, config de compaction, présence de résumé) — aucune capacité fournie par Claude n'est
  réimplémentée, aucun code métier ne dépend directement d'Anthropic.
- **Aucune incohérence `ARCHITECTURE_CANONIQUE.md`** : aucune table créée, lecture seule.
