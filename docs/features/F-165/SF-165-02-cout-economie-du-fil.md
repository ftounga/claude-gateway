# Mini-spec — F-165 / SF-165-02 — `/cout` : l'économie du fil (vaisseau amiral)

## Identifiant

`F-165 / SF-165-02`

## Feature parente

`F-165` — Commandes slash dans le terminal (vues et actions, à la sauce claude-gateway)

## Statut

`ready`

## Date de création

2026-09-30

## Branche Git

`feat/SF-165-02-cout-du-fil`

---

## Objectif

> En une phrase : ajouter la commande slash **`/cout`** — une **vue** qui affiche, **sans aucun tour
> modèle**, l'**économie du fil courant** (coût cumulé et du dernier tour, décomposition
> écriture/lecture/sortie, % de cache chaud, taille du contexte vivant, part rangée vs live,
> mini-tendance), servie par un **petit endpoint d'agrégation en lecture** isolé `user_id` + propriété
> du workspace.

---

## Comportement attendu

### Cas nominal

1. Dans le composer, taper `/cout` (ou le choisir dans l'autocomplétion, socle SF-165-01) et valider :
   la commande est **interceptée AVANT tout `send`** (`parsePanelCommand`), **aucun tour modèle** n'est
   émis (`dispatchPanelCommand` n'appelle jamais `send.emit()`).
2. Le dispatch **branche l'appel REST de lecture** `GET /api/workspaces/{id}/chat/cost-summary` (une
   **VUE** appelle un endpoint REST, jamais la boucle modèle), et rend un **panneau** `panelKind: 'cost'`
   dans le fil (cadre réutilisable SF-165-01), d'abord en état **chargement**.
3. À la réponse, le panneau affiche l'**économie du fil courant** en langage « classeur » :
   - **coût cumulé** du fil (€) et **coût du dernier tour** (€) ;
   - **décomposition** en mini-barres : ✍️ **écriture cache** · 📖 **lecture cache** · 💬 **sortie**
     (montants € **et %**, somme = 100 %) ;
   - **% de cache chaud** (part de l'entrée servie depuis le cache) ;
   - **taille du contexte vivant** (tokens ≈ « pages ») ;
   - **part rangée (compactée) vs vivante** (tours repliés vs tours rejouables, depuis `/resume`) ;
   - **budget restant** de la semaine **si un plafond existe et est lisible** (réutilise la lecture
     admin `WeeklyBudgetService` déjà chargée ; sinon la ligne est simplement absente — voir §Notes) ;
   - **mini-tendance** coût/tour sur les derniers tours (barres compactes).
4. Le panneau se **ferme** par son bouton (mécanisme SF-165-01), reste **local et éphémère** (jamais
   dans `displayedMessages`, jamais dans l'historique envoyé au modèle).

### Décomposition du coût (règle de calcul)

- Le grain est la table `usage_turns` (colonnes `input_tokens`, `cache_read_tokens`,
  `cache_write_tokens`, `output_tokens`, `provider_cost_usd`, `model`, `occurred_at`, `workspace_id`,
  `user_id`). `input_tokens` porte le **volume d'entrée traité, cache compris** (les colonnes de cache
  le **ventilent**, cf. `UsageTurn` / `UsageTurnWriter`).
- **Fil courant** = tours dont `occurred_at ≥ threadStartedAt` (frontière du dernier « nouveau départ »,
  lue via `/resume`) ; sans frontière, tous les tours du workspace.
- **Coût cumulé / dernier tour** : somme (et dernière valeur) de `provider_cost_usd`, converties en € par
  la **conversion existante** (`TurnCostView.toEur`, taux `app.cost.provider.usd-to-eur`). Aucune
  nouvelle grille : réutilisation de `ProviderPricingProperties` (opus-5 : entrée 5, sortie 25,
  lecture-cache 0,50, écriture-cache 10 / Mtoken).
- **Décomposition (parts)** : coût par nature recalculé des tokens au tarif du modèle servi —
  `écriture = entrée-neuve×Pin + écriture-cache×Pwrite` (l'entrée neuve, marginale en usage agentique où
  la boucle écrit tout dans le cache, est **agrégée au poste écriture** pour une décomposition
  **exhaustive** dont la somme fait 100 %), `lecture = lecture-cache×Pread`, `sortie = sortie×Pout`.
- **% de cache chaud** = `cache_read / input_tokens` (part de l'entrée **traitée** servie depuis le
  cache ; `input_tokens` inclut déjà le cache, cf. §Notes la réconciliation avec la formule du cadrage).
- **Contexte vivant** = `input_tokens` du **dernier tour** (proxy du contexte rejoué au modèle).
  « Pages » ≈ `tokens / 500` (constante nommée, ~une page de texte).

### Cas d'erreur / bord

| Situation | Comportement attendu |
|-----------|----------------------|
| Workspace d'un **autre** utilisateur | `requireOwned` → **404** indiscernable ; le panneau affiche un **état d'échec** neutre, jamais le coût d'autrui |
| Aucun `projectId` connu côté écran | Panneau en **état d'échec** neutre (pas d'appel), aucun tour |
| Gateway muette / erreur réseau | Panneau en **état d'échec** neutre (« coût indisponible ») ; aucun tour, aucune exception non gérée |
| Fil **sans tour facturé** (neuf) | Coûts à 0, décomposition vide (barres à 0), contexte 0 — lisible, pas une erreur |
| Tours **antérieurs à F-133** (sans coût) | `provider_cost_usd` nul → comptés 0 € (pas d'estimation inventée), volumes ignorés du calcul de coût |
| Pas de budget lisible (non-admin, ou aucun plafond) | La ligne **budget** est **absente** (jamais un plafond inventé) |
| Terminal en **lecture seule** (mosaïque, F-83) | Pas de composer → commande impossible (inchangé SF-165-01) |

---

## Critères d'acceptation

- [ ] `/cout` figure au registre `SLASH_PANEL_COMMANDS` (famille **Vue**, `panelKind: 'cost'`) et apparaît
      dans l'autocomplétion et dans `/aide`.
- [ ] Valider `/cout` **n'émet jamais `send`** (garantie « aucun tour ») et **ajoute un panneau**
      `panelKind: 'cost'` dans le fil — prouvé par un test.
- [ ] Le dispatch appelle `GET /api/workspaces/{id}/chat/cost-summary` (endpoint REST de **lecture**), et
      **jamais** la boucle modèle — prouvé par un test (aucun `send`, un appel HTTP GET).
- [ ] Le panneau rend : coût cumulé, coût du dernier tour, décomposition écriture/lecture/sortie (€ + %),
      % cache chaud, contexte vivant (tokens + pages), part rangée vs live, mini-tendance ; budget si
      disponible.
- [ ] **Backend** : l'endpoint renvoie la décomposition à partir de `usage_turns`, **isolé `user_id`**
      **et** propriété du workspace (`requireOwned`), fenêtre = fil courant (`threadStartedAt`).
- [ ] **Isolation** : un utilisateur ne voit **jamais** le coût d'un workspace/utilisateur qui n'est pas
      le sien → **404** — prouvé par un test d'isolation.
- [ ] Le panneau se **ferme** (bouton fermer, SF-165-01) et reste **hors** `displayedMessages`.
- [ ] **Design** : jetons `--cg-*` uniquement ; `tabular-nums` sur les chiffres ; cibles ≥ 44 px ; aucun
      débordement horizontal à 390 px (SF-158) ; aucune couleur/police hors `DESIGN_SYSTEM.md`.
- [ ] **Non-régression** : `/aide`, `/revue` (macro F-121), message ordinaire, autocomplétion `@`,
      dictée, steer, porte d'autorisation, outil `demander` restent intacts.
- [ ] Builds **verts** au premier plan : `mvnw test` ciblé back + `npm run build` + Karma ciblé front.

---

## Périmètre

### Hors scope (explicite)

- Les autres commandes `/contexte`, `/quota`, `/budget`, `/poste`, `/sujet`, `/compacter`, `/nouveau`,
  `/rappel` (SF-165-03 → 06).
- Une **vue budget non-admin par projet** : le budget (`/admin/cost`, F-70/F-133) est **scopé admin** ;
  la décision d'une lecture non-admin est **reportée à SF-165-04** (cadrage §7). SF-165-02 se contente
  d'afficher le budget **déjà lisible** (admin) via le service existant, sinon rien.
- Toute **nouvelle table** ou migration (aucune : lecture seule sur `usage_turns` + `/resume`).
- Toute logique de **moteur IA** (aucune ; `/cout` est une vue sur nos données).

---

## Technique

### Endpoint(s)

| Méthode | Route | Rôle | Isolation |
|---------|-------|------|-----------|
| `GET` | `/workspaces/{id}/chat/cost-summary` | Agrégation **en lecture** de l'économie du fil courant | `user_id` (contexte JWT) **+** `requireOwned(userId, id)` → 404 sinon |

Réponse (`ThreadCostSummaryResponse`) : `currency`, `cumulativeEur`, `lastTurnEur`, `turnCount`,
`breakdown{writeEur, writePercent, readEur, readPercent, outputEur, outputPercent}`, `hotCachePercent`,
`contextTokens`, `contextPages`, `liveTurns`, `foldedTurns`, `trendEur[]`. **Aucun contenu** (ni message,
ni chemin) : des volumes et des montants seulement.

### Tables impactées

Aucune création. **Lecture seule** de `usage_turns` (isolée `user_id` + `workspace_id`) et de l'état de
reprise (`/resume` : `turns`, `foldedTurns`, `threadStartedAt`). **Aucune migration Liquibase.**

### Composants / fichiers

**Backend**
| Fichier | Rôle |
|---------|------|
| `quota/UsageTurnRepository.java` (modifié) | Ajout `findByUserIdAndWorkspaceIdOrderByOccurredAtAsc` (isolé `user_id` + `workspace_id`) |
| `atelier/AtelierThreadCostService.java` (nouveau) | Agrégation : ownership via `resumeState` (`requireOwned`), décomposition depuis `usage_turns`, conversion via `TurnCostView`, tarifs via `ProviderPricingProperties` |
| `atelier/dto/ThreadCostSummaryResponse.java` (nouveau) | DTO de lecture (volumes + montants, aucun contenu) |
| `atelier/AtelierChatController.java` (modifié) | `GET /cost-summary` (controller fin, `currentUser.requireId()`) |

**Frontend**
| Fichier | Rôle |
|---------|------|
| `atelier/terminal/slash-panel-commands.ts` (modifié) | Entrée registre `/cout` ; type `ThreadCostSummary`/`CostPanelState` ; `SlashPanel.cost`/`costState` ; `buildPanel` (état chargement) |
| `core/services/atelier-cost.service.ts` (nouveau) | `costSummary(workspaceId)` → `GET /api/workspaces/{id}/chat/cost-summary` |
| `atelier/terminal/atelier-slash-cost.component.ts` (+ html/scss, nouveau) | Corps du panneau `/cout` (vitrine : mini-barres, `tabular-nums`, tendance, budget) |
| `atelier/terminal/atelier-terminal.component.ts` (modifié) | Dispatch `/cout` (appel REST + mise à jour du panneau par id) ; import du corps ; budget |
| `atelier/terminal/atelier-terminal.component.html` (modifié) | `@case ('cost')` |

### Migration Liquibase

- [x] Non applicable

---

## Plan de test

### Backend

- [ ] `AtelierThreadCostServiceTest` (unitaire, Mockito) :
  - décomposition écriture/lecture/sortie (parts somme = 100 %), % cache chaud, contexte vivant (dernier
    tour), pages, cumul et dernier tour ;
  - **fenêtre fil courant** : tours avant `threadStartedAt` exclus ;
  - fil vide → tout à 0 (pas d'erreur) ; tours sans coût → 0 € ;
  - **isolation** : `resumeState` lève `WorkspaceNotFoundException` (non-propriétaire) → le service
    propage (aucun coût calculé, aucune lecture de coût d'autrui) ;
  - le dépôt est interrogé avec **exactement** `userId` + `workspaceId` (isolation par construction).
- [ ] `AtelierThreadCostApiIntegrationTest` (MockMvc, `@SpringBootTest`) :
  - propriétaire : `GET .../cost-summary` → **200** + JSON attendu ;
  - **isolation** : un autre utilisateur sur le même workspace → **404** (indiscernable).

### Frontend (Karma ciblé)

- [ ] `slash-panel-commands.spec.ts` (ajouts) : `/cout` au registre (famille Vue, `panelKind 'cost'`),
      `panelCommandSuggestions('/co')` contient `cout`, `buildPanel(cout,…)` → `costState: 'loading'`.
- [ ] `atelier-cost.service.spec.ts` (nouveau) : `costSummary('w1')` appelle
      `GET /api/workspaces/w1/chat/cost-summary` et mappe la réponse (`HttpTestingController`).
- [ ] `atelier-slash-cost.component.spec.ts` (nouveau) : rend les montants/parts en `ready` ;
      chargement et échec rendus sans planter ; budget rendu quand fourni.
- [ ] `atelier-terminal.component.spec.ts` (ajouts) : dispatcher `/cout` **n'émet pas `send`**, ajoute un
      panneau `panelKind 'cost'`, déclenche **un** `GET .../cost-summary` ; à la réponse le panneau passe
      en `ready`.

### Isolation utilisateur

- [x] Applicable — couverte back (service + API : 404 pour autrui) et par construction (filtre
  `user_id` + `workspace_id` sur `usage_turns`, `requireOwned` sur le workspace).

---

## Préoccupations transversales

| Préoccupation | Impact | Composants vérifiés / listés |
|--------------|--------|------------------------------|
| Auth / Principal | Aucun changement. L'endpoint lit l'identité du `CurrentUser` (JWT), comme les autres routes `/workspaces/{id}/chat` | `AtelierChatController` (mêmes `currentUser.requireId()` que `resume`/`turn`) |
| Contexte tenant | **Nouvel accès données** — isolé `user_id` + `requireOwned` | `AtelierThreadCostService` (ownership via `resumeState`), `UsageTurnRepository.findByUserIdAndWorkspaceId…` (filtre `user_id`+`workspace_id`) |
| Plans / limites | Aucun nouveau gate ; aucune modification des gates existants ; `/cout` est **gratuit** (aucun tour, ne touche ni quota ni plafond) | `submit()` (interception avant le gate, inchangé SF-165-01) |
| Navigation / routing | Aucune route Angular ajoutée/modifiée (panneau local dans le fil) | — |

---

## Dépendances

### Subfeatures bloquantes

- **SF-165-01** (socle : registre, dispatch, cadre de panneau) — **livrée** sur `main`.

### Questions ouvertes impactées

- Aucune de `docs/OPEN_QUESTIONS.md`. Le point « budget non-admin par projet » est **cadré** (cadrage §7)
  et **reporté à SF-165-04** — non bloquant ici.

---

## Notes et décisions

- **Décision (produit) — `/cout` montre à l'utilisateur le coût de SON PROPRE fil.** L'isolation est
  `user_id` **+** propriété du workspace (`requireOwned`) : un utilisateur ne voit jamais que **ses**
  données et **son** workspace (cadrage §0, §7). C'est **délibérément distinct** de la garde admin de
  F-133 (`TurnCostView.labelFor` masque le coût aux **non-admins** dans la vue de **refacturation
  inter-clients**) : ici l'utilisateur consulte **sa propre** consommation — la raison d'être de F-165
  (« vérifier son coût ne doit rien coûter »). Aucun coût d'un **autre** utilisateur n'est jamais exposé.
- **Réconciliation « % cache chaud »** : le cadrage écrit `cache_read / (input + cache_read)`. Or, dans
  `usage_turns`, `input_tokens` **inclut déjà** le cache (ventilé par les colonnes de cache). La mesure
  fidèle du **concept** (part de l'entrée traitée servie à chaud depuis le cache) est donc
  `cache_read / input_tokens` — c'est cette forme, correcte, qui est implémentée.
- **Budget** : la lecture du budget hebdomadaire est **scopée admin** (`/admin/cost`, F-70/F-133). La
  décision d'une **vue budget non-admin par projet** est **reportée à SF-165-04** (cadrage §7). SF-165-02
  affiche donc le budget **uniquement s'il est déjà lisible** (réutilisation de `WeeklyBudgetService`,
  déjà chargé par le terminal), sinon la ligne est **absente** — jamais un plafond inventé.
- **Garantie « aucun tour »** conservée du socle : `dispatchPanelCommand` n'appelle jamais `send.emit()` ;
  `/cout` = **un GET de lecture** + rendu local. Le panneau vit dans le signal `slashPanels`, **hors**
  `displayedMessages` — il ne rejoint jamais l'historique envoyé au modèle ni le préfixe système stable.
- **Gateway-First / Provider-First** : `/cout` est une **vue sur NOS données** (`usage_turns`, `/resume`)
  et **réutilise** la conversion et les tarifs existants — aucune capacité fournie par Claude n'est
  réimplémentée, aucun code métier ne dépend directement d'Anthropic.
- **Aucune incohérence `ARCHITECTURE_CANONIQUE.md`** : aucune table créée, lecture seule (cadrage §7).
