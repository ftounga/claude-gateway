# Mini-spec — F-100 / SF-100-10 : La collecte Radar navigue vers la section Chat et attend la liste avant de lire

---

## Identifiant

`F-100 / SF-100-10`

## Feature parente

`F-100` — Le Radar — la synchro du soir

## Statut

`ready`

## Date de création

2026-09-21

## Branche Git

`feat/SF-100-10-collecte-vue-chat`

---

## Objectif

> En une phrase : que fait cette subfeature ?

Faire que la **découverte des conversations** de la synchro du soir (`teams_radar_collect`) **navigue vers la vue Chat** du client v2 et **attende que la liste `mid-nav` soit chargée** avant de la lire, pour qu'elle collecte les conversations que la vérification (`teams_radar_verify`) voit déjà — au lieu de 0.

---

## Comportement attendu

### Contexte (problème confirmé en prod CAGIP 2026-09-20)

Après le recalage v2 (SF-89-20/21), la **vérification** voit 11 conversations à l'écran mais la **synchro** en collecte 0 (`coverage.conversations.read=0/readOnScreen=0`, `discovery.source="aucune"`, `listed:0`). Les deux passent par les **mêmes sélecteurs** (recalés, prouvés bons par la vérification). La différence est le **contexte de navigation** :

- « Vérifier » réussit parce que l'utilisateur est **déjà sur la vue Chat** : la liste `[data-tid=simple-collab-dnd-rail]` dans `app-layout-area--mid-nav` est chargée/affichée.
- La synchro navigue **seule** (Chrome managé) et **n'atterrit pas** sur la vue Chat. La route `TeamsRoutes.CONVERSATIONS` (`.../v2/#/conversations`) utilisée par le repli tombe sur un **volet message**, sans la liste `mid-nav` → le repli écran lit une vue sans liste → 0.

### Cas nominal

1. La collecte tente d'abord la découverte par le **réseau** (inchangé, prioritaire).
2. Si le réseau n'a rien servi (`book.conversations()` vide), **avant de lire l'écran** :
   - Si la liste `mid-nav` est **déjà présente** → on lit tout de suite (cas « déjà sur la vue Chat », comme la vérification).
   - Sinon, on **navigue vers `TeamsRoutes.CHAT`** (`.../v2/#/chat`, la route qui charge `app-layout-area--mid-nav` + `[data-tid=simple-collab-dnd-rail]`), via `PageActions.navigate` (gardes F-108 : domaine, jamais une page d'identification).
   - On **attend** que la liste soit présente : **poll borné** via le `Sleeper` existant (jamais l'horloge murale), plafond `MAX_CHAT_LIST_POLLS`, sonde = présence du conteneur de `TeamsScreen.CONVERSATIONS` (`positionScript >= 0`).
   - Si la route seule n'a pas chargé la liste → **geste de repli** : clic sur l'entrée « Chat » de la barre d'app (candidats de sélecteurs), puis nouvelle attente bornée.
3. La liste présente → lecture via `TeamsScreenFallback.list(TeamsScreen.CONVERSATIONS, …)` (sélecteurs v2 recalés SF-89-20/21 : treeitems porteurs d'avatar/présence) — **inchangés**. La collecte remonte alors les conversations **comme la vérification**.
4. La vue d'avant la découverte est **remise** (§4.7 F-108).
5. Un **diag F-132** clair est émis : `radar/chat_view` avec `reached` / `timeout`, `listed=N`, `polls=N`.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Liste jamais chargée dans le plafond | **Best-effort** : pas de crash ; `discovery.served=false`, `listed=0` ; diag `radar/chat_view` = `timeout, listed=0` ; issue de synchro `PARTIAL` |
| Onglet sur une page d'identification / hors domaine Microsoft | La garde F-108 refuse la navigation ; découverte non atteinte, dit dans le diag ; pas de crash |
| Réseau a servi la liste | Aucune navigation ni attente : comportement inchangé (chemin réseau prioritaire) |
| Liaison perdue pendant l'attente (`BrowserLinkException`) | Remontée par le contrat existant (`FAILED` `LINK_LOST`), aucune régression |

---

## Critères d'acceptation

- [ ] Quand le réseau ne sert rien, la découverte **navigue vers `TeamsRoutes.CHAT`** avant de lire (prouvé par les navigations émises).
- [ ] La lecture n'est ouverte **qu'une fois la liste présente** : liste absente → attend (poll via `Sleeper`) → présente → lit (stub).
- [ ] Liste jamais présente → **best-effort borné** (plafond de polls), `listed=0`, `discovery.served=false`, diag `timeout`, **aucun crash**, issue `PARTIAL`.
- [ ] Quand la liste est déjà présente, aucune navigation superflue (cas « déjà sur la vue Chat »).
- [ ] Un diag F-132 `radar/chat_view` est émis avec `reached`/`timeout` et `listed=N`.
- [ ] **Non-régression** : découverte réseau prioritaire ; collecte des réunions ; vérification ; lecture de fil (SF-89-20/21) ; les sélecteurs de lecture ne sont **pas** modifiés.
- [ ] Isolation par poste inchangée (la collecte reste bornée à la session du poste ; aucun accès de données cross-poste).

---

## Périmètre

### Hors scope (explicite)

- Modifier les **sélecteurs de lecture** de `TeamsScreen` (CONVERSATIONS/MESSAGES) — ils sont prouvés bons par la vérification.
- La lecture de fil, la capture réunion, la boucle Vigie, `TeamsAdapterV1`, le heartbeat, les relevés.
- Toute logique côté gateway (backend) : cette SF est **runner-only**.
- La validation sur DOM v2 réel (absent en CI) : testée en **logique** sur DOM/stub modèle ; le PO relance une synchro réelle.

---

## Contraintes de validation

| Champ | Valeur | Règle |
|-------|--------|-------|
| `MAX_CHAT_LIST_POLLS` | 20 | Plafond de sondages d'attente de la liste (borne dure) |
| Intervalle de poll | `BrowserLink.SCROLL_SETTLE_MS` (600 ms) | Réutilise le `Sleeper` existant, jamais l'horloge murale |
| Route de la vue Chat | `https://teams.microsoft.com/v2/#/chat` | Hypothèse (comme CALENDAR/CONVERSATIONS), à confirmer sur poste réel ; reportée sur l'hôte de l'onglet |

---

## Technique

### Endpoint(s)

Aucun. Runner uniquement (outil hors catalogue `teams_radar_collect`).

### Tables impactées

Aucune. Aucune migration.

### Composants / classes impactés (runner)

| Classe | Opération | Notes |
|--------|-----------|-------|
| `TeamsRoutes` | AJOUT | Constante `CHAT` (route de la vue Chat v2) |
| `TeamsScreenFallback` | AJOUT | `reachChatList(int)` + `ChatView` : navigue vers la vue Chat et attend la liste (poll borné via `Sleeper`) ; clic de repli sur l'entrée « Chat » de la barre d'app |
| `TeamsRadarCollector` | MODIF | Bloc découverte : appeler `reachChatList` avant `list(...)`, passer `TeamsRoutes.CHAT`, remettre la vue, émettre le diag F-132 `radar/chat_view` |

### Migration Liquibase

- [x] Non applicable

### Composants Angular

Aucun (feature runner sans écran ; les écrans de F-100 existent déjà).

---

## Préoccupations transversales

| Préoccupation | Impactée ? | Composants impactés / vérifiés |
|---------------|-----------|-------------------------------|
| Auth / Principal | Non | — |
| Contexte tenant | Non | La collecte reste bornée à la session du poste ; aucun changement de résolution de tenant |
| Plans / limites | Non | — |
| **Navigation / routing** | **Oui (runner, dans le Chrome managé)** | Nouvelle route `TeamsRoutes.CHAT` empruntée **uniquement** par le bloc découverte de `TeamsRadarCollector` quand le réseau ne sert rien. Chemins existants vérifiés : `TeamsRoutes.CALENDAR` (réunions) inchangé ; `TeamsRoutes.CONVERSATIONS` reste utilisée par la vérification (`RadarTools.verify`) et par les outils de lecture (`TeamsTools`, `find_conversations`) — **non modifiée** ; `TeamsScreenFallback.list` inchangée (réutilisée) ; la vue est remise après la découverte (§4.7). |

---

## Plan de test

### Tests unitaires / composants (runner, sur DOM/stub modèle)

- [ ] `TeamsRadarCollectorTest` — réseau vide : la découverte **navigue vers `TeamsRoutes.CHAT`**, attend (poll) que la liste apparaisse, **puis** lit et remonte les conversations (`discovery.source=ecran`, `listed>=1`).
- [ ] `TeamsRadarCollectorTest` — liste **jamais** présente dans le plafond : best-effort borné, `listed=0`, `discovery.served=false`, diag `radar/chat_view` = `timeout`, issue `PARTIAL`, aucun crash.
- [ ] `TeamsRadarCollectorTest` — liste **déjà** présente : lecture immédiate, **aucune** navigation vers la vue Chat superflue.
- [ ] `TeamsScreenFallbackTest` — `reachChatList` : liste absente → attend → présente → `listPresent=true` ; jamais présente → `listPresent=false`, `reached=true` (best-effort borné).
- [ ] Non-régression : découverte **réseau prioritaire** (liste servie → aucune navigation ni script d'écran) ; collecte des réunions inchangée ; vérification inchangée ; lecture de fil inchangée.

### Tests d'intégration

Sans objet (runner-only, pas d'endpoint HTTP). La logique est prouvée sur le Teams de papier ; le DOM v2 réel est **à valider sur poste** par le PO.

### Isolation

- [x] Applicable — la collecte reste bornée à la session du poste relié ; aucun accès de données d'un autre poste. Inchangé par cette SF.

---

## Dépendances

### Subfeatures bloquantes

- `SF-89-20` / `SF-89-21` — Done (sélecteurs de lecture v2 recalés, réutilisés tels quels).
- `SF-100-03` — Done (`TeamsRadarCollector`, chemin de découverte).
- `SF-89-06` — Done (`TeamsScreenFallback`, repli lecture-écran).

### Questions ouvertes impactées

- Aucune de `docs/OPEN_QUESTIONS.md`. La route `CHAT` est une **hypothèse nommée** (à confirmer sur poste réel), du même ordre que les routes `CALENDAR`/`CONVERSATIONS` déjà en place.

---

## Notes et décisions

- **Ne touche pas aux sélecteurs de lecture** : le sujet est le **contexte/la navigation** de la collecte, pas la lecture (prouvée par la vérification).
- La sonde de présence **réutilise** les sélecteurs de conteneur de `TeamsScreen.CONVERSATIONS` (`positionScript >= 0` = `[data-tid=simple-collab-dnd-rail]` / `chat-list` trouvé) — aucune nouvelle table de lecture.
- Le geste de repli (clic sur l'entrée « Chat » de la barre d'app) est un **candidat de sélecteurs de navigation** local à `TeamsScreenFallback`, hypothèse à confirmer sur poste ; il n'entre pas dans la table de lecture.
- **À valider sur poste réel** (DOM v2 absent en CI) : la logique (naviguer + attendre + best-effort) est testée sur DOM/stub ; le PO relance une synchro réelle.
