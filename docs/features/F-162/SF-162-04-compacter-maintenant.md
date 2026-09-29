# Mini-spec — F-162 / SF-162-04 — « Compacter maintenant » (compaction douce à la demande)

> Base : `project-governance/templates/subfeature-template.md`.
> Cadrage de référence (fait foi) : `docs/features/F-162/CADRAGE-F-162-rappel-a-la-demande.md` (§4, SF-162-04).
> SF précédentes livrées : SF-162-01 (`recall`), SF-162-02 (résumé ancré), SF-162-03 (visibilité compaction/recall).

---

## Identifiant

`F-162 / SF-162-04`

## Feature parente

`F-162` — Le rappel à la demande de l'historique (se souvenir sans tout rejouer)

## Statut

`ready`

## Date de création

2026-09-29

## Branche Git

`feat/SF-162-04-compacter-maintenant`

---

## Objectif

> En une phrase : que fait cette subfeature ?

Donner à l'utilisateur un geste **« Compacter maintenant »** — une **compaction douce à la demande**
(elle **résume les vieux tours et conserve le résumé**), **distincte du « Nouveau départ »** (reset dur,
sans résumé) — via un endpoint isolé `POST /workspaces/{id}/chat/compact` qui appelle la compaction
existante `compactNow(...)`, et faire évoluer le bandeau de suggestion de checkpoint (SF-117-03) pour
proposer **les deux** issues (Compacter / Nouveau départ / Plus tard) avec la différence dite en une ligne.

---

## Comportement attendu

### Cas nominal

**Backend**

1. `POST /workspaces/{id}/chat/compact` (nouvel endpoint de `AtelierChatController`) exige l'accès au
   terminal (`atelierAccess.requireTerminalAccess(id)`) comme les autres endpoints du contrôleur, puis
   délègue à `AtelierChatService.compactManually(userId, id)`.
2. `compactManually` applique l'**isolation en premier** : `workspaceService.requireOwned(userId, id)`
   (404 sur un projet d'autrui — jamais un identifiant venu du client n'ouvre le workspace d'un autre).
3. Elle résout la clé fournisseur (BYOK ou plateforme, même règle que la boucle :
   `byokKeyService.resolveActiveApiKey(userId)`) et appelle **`compactionService.compactNow(userId,
   workspace, apiKey, listener)`** — la **compaction douce** : elle résume les tours anciens, **garde
   le résumé** dans `chatThreadSummary` et avance la frontière `chatThreadStartedAt` (contrairement au
   « Nouveau départ » / `restart`, qui **efface** le résumé et replie tout l'historique).
4. Un **listener capteur** relève le nombre de tours résumés émis par `onCompactionDone(N)`
   (mécanisme SF-162-03). L'endpoint renvoie `AtelierCompactResponse(compacted, summarizedTurns)`.
5. **Best-effort** : un appel de synthèse en échec n'écrit rien et laisse le fil intact — `compacted=false`,
   `summarizedTurns=0` (jamais une 500 : la compaction est une optimisation, pas une étape obligatoire).

**Frontend**

6. Une action **« Compacter maintenant »** dans l'en-tête du terminal (desktop **et** menu ⋯ mobile),
   **à côté de « Nouveau départ »**, hors lecture seule. Icône `compress` (distincte de `history_toggle_off`
   du Nouveau départ). Infobulle explicitant la différence : *allège en gardant la mémoire* vs *repart à blanc*.
7. Au clic : une **barre de progression indéterminée** (réutilise `terminal-compaction-bar` de SF-162-03)
   s'affiche pendant l'appel, puis, au succès avec N>0, un **marqueur** « Conversation compactée · N tours
   résumés » (réutilise `terminal-flux-marker` + `compactionMarkerLabel` de SF-162-03) reste affiché.
8. Le **bandeau de suggestion** (SF-117-03) propose désormais **DEUX** issues : **« Compacter »**
   (doux, recommandé, `mat-flat-button`) **et** **« Nouveau départ »** (dur, `mat-stroked-button`),
   plus **« Plus tard »**. Le texte du bandeau explique la différence en une ligne.

### Cas d'erreur / limites

| Situation | Comportement attendu |
|-----------|---------------------|
| Rien à compacter (`splitIndex == 0`, fil déjà court) | `compactNow` ⇒ `doCompact` renvoie `NONE` **sans émettre** : réponse neutre `compacted=false, summarizedTurns=0` — l'écran dit « Rien à compacter », **pas** d'erreur |
| Appel de synthèse en échec (best-effort) | Fil inchangé, réponse `compacted=false, summarizedTurns=0`, **jamais** de 500 ; la barre disparaît sans marqueur |
| Compaction désactivée (`compaction.enabled=false`) | `compactNow` ⇒ `NONE` : réponse neutre, aucun effet |
| Workspace d'autrui / inconnu | `requireOwned` ⇒ `WorkspaceNotFoundException` ⇒ **404** (isolation) — avant tout accès données |
| Sans droit d'accès au terminal | `requireTerminalAccess` ⇒ `AtelierAccessDeniedException` ⇒ 403 |
| Lecture seule (front) | Le bouton et le bandeau sont **absents** (c'est un geste) |
| `prefers-reduced-motion: reduce` | La barre est non animée (héritée de SF-162-03) |
| Erreur réseau (front) | `MatSnackBar` « Impossible de compacter maintenant. » ; la barre disparaît |

---

## Critères d'acceptation

- [ ] `POST /workspaces/{id}/chat/compact` existe, protégé par `requireTerminalAccess(id)`, et renvoie
      `AtelierCompactResponse(boolean compacted, int summarizedTurns)`.
- [ ] `AtelierChatService.compactManually` applique **`requireOwned` en premier** (isolation `user_id`) —
      un workspace d'autrui rend **404** avant tout autre traitement.
- [ ] La compaction manuelle appelle **`compactNow`** (compaction **douce** : conserve `chatThreadSummary`),
      **jamais** `restart` (qui efface le résumé) : « Compacter » ≠ « Nouveau départ ».
- [ ] Rien à compacter ⇒ réponse neutre (`compacted=false, summarizedTurns=0`), **aucune** erreur.
- [ ] Best-effort : un échec de l'appel de synthèse ne renvoie **jamais** une 500 (réponse neutre).
- [ ] `summarizedTurns` renvoyé = N tours résumés capté via `onCompactionDone(N)` (même notion de tour
      que SF-162-02/03).
- [ ] **Frontend** : bouton « Compacter maintenant » dans l'en-tête (desktop + menu ⋯ mobile), hors
      lecture seule, **visuellement distinct** de « Nouveau départ » (icône + infobulle qui disent la différence).
- [ ] **Frontend** : au clic, barre indéterminée pendant l'appel, marqueur « Conversation compactée · N
      tours résumés » au succès (N>0) ; « Rien à compacter » (snackbar) si N=0.
- [ ] **Bandeau SF-117-03** : propose « Compacter » (recommandé) + « Nouveau départ » + « Plus tard », et
      dit la différence en une ligne. La suggestion par **taille** (seuil `LONG_THREAD_TURNS`) est conservée.
- [ ] `atelier.service.ts` expose `compactThread(id): Observable<AtelierCompactResult>` (POST `/compact`).
- [ ] **Design system** : jetons `--cg-*` uniquement, **aucune couleur/police nouvelle** ; cibles tactiles
      ≥44 px ; réutilise les classes SF-162-03 (`terminal-compaction-bar`, `terminal-flux-marker`).
- [ ] **Responsive mobile** : rien ne déborde à 390 px ; rail (SF-158-22), auto-scroll (SF-158-23) et menu
      ⋯ (SF-158-12) intacts.
- [ ] **Non-régression** : restart/resume (F-39), compaction auto (SF-162-02), visibilité (SF-162-03),
      recall (SF-162-01), repli d'historique (SF-117-05/06/07) — tous les tests existants restent verts.

---

## Périmètre

### Hors scope (explicite)

- **Pas de détection fine de « changement de sujet »** : le cadrage la marque **optionnelle** et
  « ne pas sur-concevoir ». Après analyse, une détection par recouvrement de mots-clés introduirait un
  seuil arbitraire non mesuré et de la logique de raisonnement côté gateway (frontière floue avec
  « moteur IA maison »). **On garde le déclenchement par taille existant (SF-117-03)** et on le note ici
  en hors-scope, conformément à l'autorisation du cadrage. Réservé à une itération ultérieure si mesuré.
- **Pas de nouvel événement SSE ni de flux** : le geste manuel est un simple POST/réponse ; il **réutilise**
  le rendu SF-162-03 (barre + marqueur) côté écran, sans passer par un tour vivant.
- **Pas de persistance nouvelle** : aucune table, aucune colonne, aucune migration. `compactNow` écrit déjà
  `chatThreadSummary`/`chatThreadStartedAt` sur `workspace` (comportement SF-117-01, inchangé).
- **Pas de modification de la logique de compaction** (seuils, garde `splitIndex`, best-effort, gabarit).
- **Pas de filet utilisateur** (SF-162-05) ni de sémantique (SF-162-06).

---

## Valeurs initiales

Aucune entité, aucune table, aucune colonne, aucune migration.

| Réglage | Valeur | Règle |
|---------|--------|-------|
| N (tours résumés) | `count(USER)` de la tranche résumée | capté via `onCompactionDone(N)` (SF-162-03) |
| Compaction manuelle | **douce** (`compactNow`) | conserve le résumé — jamais `restart` |
| Suggestion checkpoint | seuil `LONG_THREAD_TURNS` (inchangé, SF-117-03) | déclenchement par taille conservé |

---

## Technique

### Endpoint(s)

- **Nouveau** : `POST /workspaces/{id}/chat/compact` → `AtelierCompactResponse(boolean compacted, int
  summarizedTurns)`. Isolation : `requireTerminalAccess` (contrôleur) + `requireOwned` (service).

### Tables impactées

Aucune. `atelier_messages` en lecture seule (via `compactNow`) ; `workspace` mis à jour par `compactNow`
(résumé + frontière) — comportement SF-117-01 déjà existant, non modifié ici.

### Migration Liquibase

- [x] Non applicable — aucune table ni colonne nouvelle.

### Composants impactés

**Backend**

- `AtelierChatController` — nouvel endpoint `compact(...)`.
- `AtelierChatService` — nouvelle méthode `compactManually(UUID, UUID)` (requireOwned + apiKey +
  `compactNow` + listener capteur) ; record `AtelierCompactResult` (retour interne).
- `dto/AtelierCompactResponse` — nouveau DTO de réponse.
- Réutilise : `AtelierCompactionService.compactNow(...)`, `AtelierProgressListener` (SF-162-03).

**Frontend**

- `core/services/atelier.service.ts` — `compactThread(id)`.
- `core/models/atelier.models.ts` — type `AtelierCompactResult`.
- `atelier/atelier.component.{ts,html}` — `compactNow()`, signaux `compactingNow` / `compactionMarker`,
  câblage `[compactingNow]`/`[compactionMarker]`/`(compactNow)`.
- `atelier/terminal/atelier-terminal.component.{ts,html}` — bouton « Compacter maintenant » (en-tête +
  menu ⋯), barre + marqueur manuels (réutilisent les classes SF-162-03), 2e bouton du bandeau SF-117-03.

### Points de câblage

- Endpoint : `AtelierChatController.compact` → `AtelierChatService.compactManually`.
- Front : bouton en-tête `(compactNow)` → `atelier.component.compactNow()` → `atelier.service.compactThread`.
- Bandeau : `suggestRestart()` (inchangé) + nouveau `(compactNow)` à côté de `(restart)`.

---

## Plan de test

### Tests unitaires — backend

- [ ] `compactManually` : compaction réelle ⇒ `compacted=true`, `summarizedTurns=N` (workspace résumé,
      `chatThreadSummary` **non nul**).
- [ ] Rien à compacter (fil court) ⇒ `compacted=false`, `summarizedTurns=0`, `chatThreadSummary` inchangé.
- [ ] Compaction manuelle **conserve** le résumé (distinction restart) : après `compactManually`,
      `chatThreadSummary` n'est **pas** vidé.
- [ ] Best-effort : provider en échec ⇒ `compacted=false, summarizedTurns=0`, pas d'exception propagée.

### Tests d'intégration / non-régression — backend

- [ ] `POST /workspaces/{id}/chat/compact` (MockMvc, utilisateur possédant) ⇒ 200 + corps JSON attendu.
- [ ] **Isolation** : `POST .../compact` sur le workspace d'un **autre** utilisateur ⇒ **404**
      (un utilisateur ne compacte pas le workspace d'un autre).
- [ ] Sans authentification ⇒ 401 ; sans droit terminal ⇒ 403.
- [ ] `AtelierCompactionServiceTest` / `AtelierChatServiceCompactionTest` existants restent verts.

### Tests unitaires — frontend

- [ ] `atelier.service` : `compactThread(id)` fait un POST sur `/api/workspaces/{id}/chat/compact` et
      mappe la réponse.
- [ ] `atelier-terminal.component` : le bouton « Compacter maintenant » est rendu (hors readOnly),
      distinct de « Nouveau départ », et émet `compactNow` au clic.
- [ ] `atelier-terminal.component` : la barre manuelle est rendue quand `compactingNow=true` ; le marqueur
      « Conversation compactée · N » est rendu quand `compactionMarker` est posé.
- [ ] Bandeau SF-117-03 : contient bien **deux** boutons d'action (Compacter + Nouveau départ) + Plus tard.
- [ ] Non-régression : `terminal-rail-en-tete.spec`, `atelier-terminal.component.spec`,
      `terminal-bandeaux-empiles.spec` restent verts.

### Isolation workspace / utilisateur

- [x] **Applicable** — nouvel endpoint accédant aux données. `requireTerminalAccess` (contrôleur) +
      `requireOwned` (service, en premier) filtrent `user_id`. **Test d'isolation dédié** : 404 sur le
      workspace d'autrui.

---

## Préoccupations transversales

| Préoccupation | Concernée ? | Composants impactés |
|--------------|-------------|---------------------|
| Auth / Principal | Non (réutilise `CurrentUser.requireId()` + `requireTerminalAccess`, aucun nouveau type d'auth) | — |
| Contexte tenant | **Oui** — nouvel accès données. Le tenant est résolu comme partout : `CurrentUser.requireId()` (contrôleur) puis `workspaceService.requireOwned(userId, id)` (service, en premier). **Composants qui résolvent le tenant sur ce chemin** : `AtelierChatController.compact` (identité), `AtelierChatService.compactManually` (`requireOwned`), `AtelierCompactionService.compactNow`/`replayable` (déjà filtrés `workspace_id`+`user_id`, inchangés). Aucun autre chemin d'accès n'est introduit. |
| Plans / limites | Non — la compaction manuelle ne consomme pas de quota séparé (comme la compaction auto : best-effort, hors chemin de quota) ; aucun nouveau gate. | — |
| Navigation / routing | Non — pas de nouvelle route front, pas de guard, pas de redirection ; un bouton et un appel POST. | — |

---

## Dépendances

### Subfeatures bloquantes

- **SF-162-03** (livrée) : `AtelierProgressListener.onCompactionDone(int)` (comptage capté), et le rendu
  terminal réutilisé (barre `terminal-compaction-bar`, marqueur `terminal-flux-marker`,
  `compactionMarkerLabel`).
- **SF-117-01** (livrée) : `AtelierCompactionService.compactNow(...)`.
- **SF-117-03** (livrée) : le bandeau de suggestion à faire évoluer.

### Questions ouvertes impactées

- Aucune. (La détection de « changement de sujet » est explicitement laissée hors-scope, cf. §Périmètre.)

---

## Notes et décisions

- **« Compacter » ≠ « Nouveau départ »** (non négociable, cadrage §4) : la compaction manuelle passe par
  `compactNow` (conserve `chatThreadSummary`) ; `restart`/`restartThread` restent le chemin **dur** (efface
  le résumé, replie l'historique). Deux chemins bien séparés, deux boutons, deux libellés.
- **Réutilisation SF-162-03** : « bénéficie automatiquement des événements de flux » est interprété comme
  la réutilisation du **rendu** (barre + marqueur) et du **comptage** (`onCompactionDone`). Comme le geste
  manuel n'a pas de tour vivant, l'écran pilote ce rendu depuis la réponse HTTP — pas de nouvel événement SSE.
- **Détection de changement de sujet** : laissée hors-scope pour ne pas sur-concevoir (cadrage l'autorise
  explicitement) — un seuil non mesuré + du raisonnement côté gateway seraient un mauvais compromis.
- **Gateway-First / Provider-First / préfixe stable** : inchangés — aucun moteur IA, la synthèse passe par
  `AiAgentProvider` (déjà en place) ; aucun contenu volatil n'entre dans le contexte modèle.
- **Piège des deux constructeurs** : aucun `@ConfigurationProperties` touché ; le record `AtelierCompactResult`
  est un simple DTO interne.
