# Mini-spec — F-89 / SF-89-19 — Relevé de forme : capter TOUTES les zones de layout (trouver la liste des chats v2)

## Identifiant

`F-89 / SF-89-19`

## Feature parente

`F-89` — Le volet Teams — le terminal Teams (Terminée). Cette SF **corrige et approfondit** l'outil de
diagnostic livré par SF-89-16/17/18, dans la lignée de SF-89-10→18.

## Statut

`in-progress`

## Date de création

2026-09-20

## Branche Git

`feat/SF-89-19-releve-toutes-zones-layout`

---

## Objectif

> En une phrase : **relever la forme de TOUTES les zones de layout** de Teams v2
> (`[data-tid^="app-layout-area--"]` — `main`, `sidebar`, `rail`, `header`…), pas seulement `--main`,
> pour **voir la liste des conversations** (qui vit dans une autre zone que `--main`) et en émettre les
> ancres sous `code=chat_list`, avec la **même triple garde** de vie privée (forme only) que
> SF-89-16/17/18.

---

## Contexte (pourquoi SF-89-18 ne suffit pas — relevé réel prod CAGIP 2026-09-20)

- **Lire un fil est résolu** (ancres v2 en `main` : `[data-tid=chat-pane-item]`,
  `[data-tid=message-author-name]`, `time`, classe `fui-ChatMyMessage`). SF-89-19 **ne touche pas** ce
  chemin.
- **La liste des conversations n'est toujours pas captée.** Le relevé actuel ne prend que
  `[data-tid=app-layout-area--main]` (le volet du chat ouvert) + la **barre d'icônes d'app** (captée à
  tort comme `chat_rail` — c'est `role=navigation` avec des boutons-icônes SVG).
- **L'iframe `hwc-iframe` est une IMPASSE** : dernier relevé = `frame_blocked` (contentDocument nul →
  cross-origin/sandboxée). SF-89-19 **n'insiste pas** sur l'iframe.
- **Hypothèse forte** : la **liste des chats** est dans le **document principal**, mais dans une **AUTRE
  zone de layout** (panneau de gauche), ex. `app-layout-area--sidebar` / `--rail` / un pane gauche —
  **jamais capté** car on ne relève que `--main`.

**Point vie privée inchangé et non négociable** : on relève la **forme** du DOM de Microsoft (la même
pour tous les clients). Un nom, un message, une adresse, un id de fil n'en sortent jamais — **y compris
dans une zone `--sidebar` (liste de chats)**. Même triple garde que SF-89-16/17/18.

---

## Comportement attendu

### Déclenchement (identique à SF-89-16/17/18 — aucun nouveau geste)

Inchangé : opt-in gardé par le niveau **DEBUG** (F-132). L'admin clique **« Activer DEBUG »**
(SF-132-05), puis déclenche une **lecture des conversations** (vérification Radar **ou**
`teams_find_conversations`). `TeamsTools.findConversations` — **et seulement à DEBUG** — lance **une fois**
(anti-rafale) le relevé, qui navigue vers la vue Conversations, relève la forme (désormais **toutes les
zones de layout** incluses), puis **remet la vue**. Sortie dans le **Journal du runner** (`runner_diag`
→ `runner_diag_events` → panneau + `GET /runner-hosts/{hostId}/diag`).

> **Hors DEBUG, le runner est strictement inchangé** : aucun script, aucune navigation, aucun événement.

### Cas nominal du relevé (ce que SF-89-19 ajoute à SF-89-18)

1. **Découvrir toutes les zones de layout.** Après le relevé du document principal (liste + rail +
   runway, SF-89-16/17, inchangé), le relevé exécute **un** script supplémentaire **dans l'onglet**
   (`actions.readScript`, `Runtime.evaluate` déjà en liste blanche — **aucune nouvelle commande CDP**)
   qui énumère **tous** les éléments `document.querySelectorAll('[data-tid^="app-layout-area--"]')`
   (bornés à `MAX_AREAS`). Pour **chaque** zone : sa forme (racine = l'élément de zone lui-même) est
   émise sous **`code=layout_area`** avec `fields.area = <data-tid de la zone>` (ex.
   `app-layout-area--sidebar`), `fields.frame = main`.
2. **Cibler la liste des chats dans chaque zone.** Dans chaque zone découverte, on cherche un conteneur
   de liste par `data-tid`/`role` **stables** (`LIST_SELECTORS` : `role=tree`/`role=list`/`role=grid`,
   ou `data-tid` de type `chat-list`/`chatListItem`/`list-*` — **jamais** par classe hashée). S'il est
   trouvé, sa forme est émise sous **`code=chat_list`** avec `fields.area = <data-tid de la zone
   d'origine>`, `fields.frame = main`.
3. **Garder le reste pour comparaison.** Les relevés existants (main/thread/message_runway/chat_rail de
   SF-89-16/17 + les cadres iframe same-origin de SF-89-18) sont **conservés** tels quels. Le chemin
   iframe reste (cross-origin réels) mais **n'est plus le sujet**.
4. **Volume borné.** `MAX_AREAS` (8) zones au plus ; par zone, mêmes bornes que le document
   (`MAX_DEPTH=22`, `MAX_CHILDREN=40`, `MAX_NODES=160`). Au-delà, `truncated=true` est **dit**. Chaque
   nœud = **un** événement F-132 ; un en-tête par vue.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|----------------------|
| Niveau ≠ `DEBUG` | Aucun relevé, aucune navigation, aucun script de zones, aucun événement. |
| Aucun `[data-tid^="app-layout-area--"]` dans le document | Aucun événement `layout_area`/`chat_list` du document ; le reste (main/iframe) est relevé quand même ; jamais d'exception. |
| Zone sans liste (aucun `LIST_SELECTORS` trouvé) | La forme de la zone est émise (`layout_area`) ; **aucun** `chat_list` pour cette zone ; aucune exception. |
| Script de zones refusé par la garde F-108 (page sortie du domaine) | `BrowserLinkException`/`RuntimeException` avalée → aucune zone relevée, le reste du relevé reste inchangé. |
| Nœud portant nom/message/adresse/id (texte, `aria-label`, `title`, `href`, `id`…) **dans une zone `--sidebar`** | Seuls balise + `data-tid`/`role`/`class` assainis + **noms** d'attributs + longueur de texte sortent ; aucune valeur, aucun texte. |
| `data-tid`/jeton de classe id-like (`19:…@thread.v2`, GUID, hex long…) | Assaini en `{id}` (`SurveyPaths.looksLikeId`) — inchangé. |
| Débit d'événements > anneau | Best-effort F-132 : plus ancien écrasé, `dropped` compté (contrat SF-132-01 inchangé). |

---

## Critères d'acceptation

- [ ] **CA1 (inertie)** — Niveau `INFO` : `findConversations` n'émet **aucun** événement `cat=shape`, ne
  lance **aucun** script de zones, aucune navigation. (Test.)
- [ ] **CA2 (découverte multi-zones)** — À `DEBUG`, un DOM modèle avec plusieurs
  `[data-tid^="app-layout-area--"]` (dont `--main` **et** `--sidebar`) → **chaque** zone est relevée sous
  `code=layout_area` avec `fields.area = app-layout-area--<nom>`, `fields.frame = main`. (Test.)
- [ ] **CA3 (chat_list dans la sidebar)** — La zone `--sidebar` contient une liste (`role=tree` /
  `data-tid=chat-list` avec items) → cette liste est émise sous `code=chat_list`,
  `fields.area = app-layout-area--sidebar`, `fields.frame = main`. (Test.)
- [ ] **CA4 (sélecteurs stables)** — `LIST_SELECTORS` ne ciblent que des `data-tid`/`role` (jamais une
  classe), et couvrent `role=tree`/`role=list`/`role=grid` + `data-tid` de liste de chats. (Test.)
- [ ] **CA5 (VIE PRIVÉE — non négociable, sidebar incluse)** — Un DOM modèle portant message
  (« bonjour Paul »), noms (« Jean Dupont », « Marie Martin »), adresse (`jean.dupont@client.fr`), id de
  fil (`19:secret@thread.v2`) — en nœuds texte, `aria-label`, `title`, `href`, `id` — **dans la zone
  `--sidebar` (liste de chats)** → aucune de ces chaînes n'apparaît (ni `msg`, ni `fields`). (Test
  explicite.)
- [ ] **CA6 (bornes)** — `MAX_AREAS=8`, `MAX_DEPTH=22`, `MAX_CHILDREN=40`, `MAX_NODES=160` respectés ;
  dépassement → `truncated=true`. (Test.)
- [ ] **CA7 (script sans interdits)** — Le script de zones ne lit jamais `textContent`/`innerText` comme
  valeur, ni `document.cookie`, ni `localStorage`/`sessionStorage`, et descend par
  `querySelectorAll('[data-tid^="app-layout-area--"]')`. (Test.)
- [ ] **CA8 (ne casse rien)** — Le relevé ne lève jamais vers l'appelant (tout `catch`), remet la vue, et
  la suite runner reste verte : capture réunion, collecte Radar, boucle Vigie, `TeamsAdapterV1`,
  heartbeat, F-132, F-100, F-132, **SF-89-16/17/18** (dont le chemin CDP frame et le chemin
  `contentDocument` conservés). (Test + suite complète.)

---

## Périmètre

### Hors scope (explicite)

- **Recaler `TeamsScreen`** (les vraies valeurs de sélecteurs v2 de la liste) : c'est la **suite**, après
  le relevé réel multi-zones lancé par le PO — comme SF-89-13/15 ont suivi SF-89-12/14.
- **Piloter / cliquer dans une zone** : hors scope. On lit la forme de ce qui est déjà affiché (l'admin a
  un chat ouvert au moment du relevé).
- **Retirer les chemins iframe de SF-89-17/18** : conservés pour non-régression et OOPIF/same-origin
  réels.
- Toute **écriture de valeur**, tout envoi de contenu au modèle / à la gateway.
- Tout **nouveau transport / table / endpoint / composant Angular / migration** : on réutilise le
  pipeline F-132 et `Runtime.evaluate` **à l'identique**.
- Toute modification **backend / frontend / base de données**.
- Le relevé des corps **réseau** (SF-89-12/14, `PayloadShape`) : non touché.
- **Aucune nouvelle commande CDP** : on n'utilise que `Runtime.evaluate`, déjà en liste blanche.

---

## Valeurs initiales

Aucune entité. Aucun compteur persistant : la squelette vit le temps d'un relevé et repart avec lui.

## Contraintes de validation

| Champ | Obligatoire | Longueur / borne max | Format / Valeurs autorisées | Normalisation |
|-------|-------------|----------------------|-----------------------------|---------------|
| zones de layout relevées (`MAX_AREAS`) | — | 8 | au-delà : zones suivantes ignorées | ordre DOM |
| profondeur (`MAX_DEPTH`) | — | 22 | au-delà : non déplié, `truncated=true` | — |
| enfants dépliés / nœud (`MAX_CHILDREN`) | — | 40 | au-delà : `truncated=true` | après repli des frères |
| nœuds / vue (`MAX_NODES`) | — | 160 | au-delà : `truncated=true` | ordre DFS pré-ordre |
| `area` (label de zone) | — | 80 | assaini (`scrubToken`) ; le `data-tid` de la zone (`app-layout-area--…`) | `area#N` par défaut |
| `frame` | — | 80 | `main` pour le document principal (les zones vivent dans le doc principal) | — |
| longueur de texte | — | entier | **jamais** le texte, seulement `length` | `asInt(0)` côté Java |
| `tag`/`data-tid`/`role`/noms d'attributs/jetons de classe | — | inchangés SF-89-16 | inchangés SF-89-16 | inchangés SF-89-16 |
| `msg` / `fields` d'événement | — | 300 / scalaires (F-132) | ligne indentée sans valeur | `RunnerDiagRedaction` |

Notes :
- **Aucune valeur de feuille ni de valeur d'attribut sensible n'est jamais écrite** — testé dans une zone
  `--sidebar` (liste de chats, CA5). La triple garde de SF-89-16 (élision script →
  `TeamsDomShape.refilter` → `RunnerDiagRedaction`) s'applique **identiquement** à toute zone de layout :
  le même `refilter`, le même Journal.
- **Réutilisation `area` (redéfinition élargie).** SF-89-17/18 mettaient dans `fields.area` un nom de
  sous-zone (`rail`/`message_runway`/`chat_list`). SF-89-19 y met **aussi** le `data-tid` de la zone de
  layout (`app-layout-area--sidebar`…). Décision documentée (D2) : `area` reste un discriminant de
  lecture en base, désormais assez large pour porter le `data-tid` de la zone. Les codes existants
  (`chat_list`, `chat_rail`…) restent inchangés.

---

## Technique

### Endpoint(s)

Aucun (runner). Sortie via la trame `runner_diag` existante → `GET /api/runner-hosts/{hostId}/diag`.
**Aucun nouvel endpoint.**

### Tables impactées

Aucune. **Aucune migration.** (Réutilise `runner_diag_events`, SF-132-02.)

### Nouveaux `code` / `fields` à lire en base (`cat=shape`)

- `code=layout_area` — **nouveau** — la **forme d'une zone de layout** du document principal.
  `fields.area = <data-tid de la zone>` (ex. `app-layout-area--sidebar`, `app-layout-area--main`,
  `app-layout-area--rail`…), `fields.frame = main`.
- `code=chat_list` — **existant (SF-89-18)** — désormais aussi émis **depuis le document principal** avec
  `fields.area = <data-tid de la zone d'origine>` (la liste des chats trouvée dans cette zone), en plus
  de l'émission depuis un cadre same-origin (SF-89-18, `area=chat_list`).
- `code=conversations_list` / `chat_rail` / `thread` / `frame_blocked` — **inchangés** (SF-89-16/17/18).

### Composants

- `runner/teams/TeamsDomShape` (**étendu**) — `LIST_SELECTORS` **enrichis** (`role=grid`,
  `data-tid^="list-"`) ; nouveau `AREA_DISCOVERY` (le sélecteur d'attribut-préfixe
  `[data-tid^="app-layout-area--"]`) ; nouveau script `areasScript(mapper)` (« cg-domareas ») qui énumère
  toutes les zones et, pour chacune, relève sa forme **et** sa liste de chats (mêmes interdits) ; record
  `AreaShape(area, shape, list)` + `refilterAreas(JsonNode)` (borné `MAX_AREAS`, label assaini). Le
  script existant, `surveyScript`, `framesScript`, `refilter`, `refilterFrames`, `line`, `fields`,
  `scrubToken`, `Node`, `Survey`, `FrameShape` : **inchangés**.
- `runner/teams/TeamsDomShapeSurvey` (**étendu**) — après le document principal (SF-89-16/17), appelle
  `surveyLayoutAreas()` : lit `areasScript` via `actions.readScript`, re-filtre, et émet par zone
  `layout_area` (+ `chat_list` si une liste y est trouvée), étiquetés `frame=main`/`area=<data-tid>`. Ne
  lève jamais ; remet la vue. Nouveau `code=layout_area`. Le chemin iframe (SF-89-18) reste appelé après.
- `runner/teams/PageActions` — **inchangé** : `readScript(expression)` (déjà là, `Runtime.evaluate`)
  suffit ; **aucune nouvelle méthode, aucune nouvelle commande CDP**.
- `runner/teams/TeamsTools#findConversations` — **inchangé** : la couture `TeamsDomShapeSurvey.run(...)`
  lance déjà le relevé ; c'est son corps qui s'approfondit.
- `runner/teams/CdpCommands` — **inchangé** : `Runtime.evaluate` déjà en liste blanche.
- `runner/diag/RunnerDiag` / `RunnerDiagRedaction` — **inchangés**.

### Préoccupations transversales

| Préoccupation | Impacté ? | Composants vérifiés |
|--------------|-----------|---------------------|
| **Auth / Principal** | **Non** — runner local, aucune session gateway, aucun endpoint, aucun JWT. | — |
| **Contexte tenant / `user_id`** | **Non** — le runner observe le navigateur du poste ; seule la **forme** du DOM quitte la machine (jamais un contenu, jamais un tenant) ; l'isolation `user_id`+`host_id` du Journal est portée par SF-132-02 (inchangée). Aucun nouveau point d'accès aux données. | — |
| **Plans / limites** | **Non**. | — |
| **Navigation / routing (runner)** | **Oui (runner, pas frontend)** — le relevé navigue dans la page Teams (même discipline que SF-89-16/17/18 : report d'hôte `onTabHost`, gardes F-108 via `readScript` → `assertCurrentPageAllowed`, remise de la vue). **Le nouveau script de zones ne navigue pas** (il lit le document courant). Composants runner vérifiés : `PageActions` (garde de page courante sur `readScript`), `TeamsRoutes` (routes inchangées), `TeamsScreenFallback` (patron), l'attache F-100 (`NetworkObserver`/`NetworkSurvey` : intacte, chemins CDP/contentDocument de SF-89-17/18 conservés). | `PageActions`, `TeamsRoutes`, `TeamsScreenFallback`, `NetworkObserver`, `NetworkSurvey` |

---

## Plan de test

### Tests unitaires (module `runner`, JUnit 5)

- [ ] `TeamsDomShapeTest` — **`LIST_SELECTORS`** ciblent des `data-tid`/`role` (jamais des classes) et
  couvrent `role=grid` + `data-tid^="list-"` (CA4).
- [ ] `TeamsDomShapeTest` — **`areasScript`** généré garde les mêmes interdits
  (`textContent`/`innerText`/cookie/stockage absents), énumère
  `querySelectorAll('[data-tid^="app-layout-area--"]')` et ne lit jamais le `src` d'un cadre (CA7).
- [ ] `TeamsDomShapeTest` — **`refilterAreas`** : un payload zones modèle (plusieurs zones dont
  `--sidebar` avec une liste porteuse d'id de fil) → labels assainis, borne `MAX_AREAS`, expurgation
  appliquée à la forme **et** à la liste (CA6).
- [ ] `TeamsDomShapeTest` — **VIE PRIVÉE (`refilter`)** : inchangé de SF-89-16, toujours vert.
- [ ] `TeamsDomShapeSurveyTest` — **inertie (CA1)** : niveau `INFO` → aucun événement, aucun script.
- [ ] `TeamsDomShapeSurveyTest` — **découverte multi-zones (CA2)** : navigateur de papier rendant
  plusieurs `app-layout-area--*` (dont `--main` et `--sidebar`) → événements `code=layout_area` par zone,
  `fields.area = app-layout-area--<nom>`, `fields.frame = main`.
- [ ] `TeamsDomShapeSurveyTest` — **chat_list dans la sidebar (CA3)** : la `--sidebar` porte une liste
  `role=tree`/`data-tid=chat-list` → un `code=chat_list`, `fields.area = app-layout-area--sidebar`,
  `fields.frame = main`.
- [ ] `TeamsDomShapeSurveyTest` — **VIE PRIVÉE bout-en-bout, sidebar incluse (CA5)** : valeurs sensibles
  semées **dans la sidebar** → aucun événement drainé ne contient nom/message/adresse/id.
- [ ] `TeamsDomShapeSurveyTest` — **non-régression SF-89-16/17/18** : liste/rail/runway/hwc-iframe/
  frame_blocked restent verts ; anti-rafale / remise de vue / ne lève jamais inchangés.
- [ ] Suite runner complète verte (`cd runner && ./mvnw -q test`), hors l'échec **pré-existant sur `main`**
  `TeamsScreenFallbackTest.the_network_wins_when_it_answers` (documenté en historique SF-89-16/17/18).

### Tests d'intégration

- N/A (module runner : pas de contexte Spring). La chaîne `runner_diag` → base → endpoint est **déjà**
  couverte par SF-132-02 (contrat de trame inchangé — mêmes tables/events, nouveaux `code`/`fields`
  scalaires seulement).

### Isolation utilisateur / workspace

- [ ] **Non applicable côté runner** — mono-poste, aucune donnée multi-tenant touchée. La confidentialité
  est couverte par CA5 (aucune valeur, aucun nom, aucun id, aucun texte, **zone sidebar incluse**).
  L'isolation `user_id`+`host_id` du Journal est garantie par SF-132-02 (inchangée).

### Non-régression

- [ ] `findConversations`, capture réunion, collecte Radar, boucle Vigie, `TeamsAdapterV1`, heartbeat,
  F-132, **SF-89-16/17/18** (dont chemin CDP frame et chemin `contentDocument`), **F-100** inchangés à
  niveau `INFO` (suite runner).

---

## Dépendances

### Subfeatures bloquantes (Done)

- `F-89 / SF-89-16` (relevé de forme `TeamsDomShape`/`TeamsDomShapeSurvey`) — **Done**.
- `F-89 / SF-89-17` (rail + runway + profondeur + chemin CDP frame) — **Done**.
- `F-89 / SF-89-18` (iframe same-origin par `contentDocument` + `chat_list`) — **Done**.
- `F-132 / SF-132-01/02/03/05` (émission `RunnerDiag`, `runner_diag_events`, panneau, bouton DEBUG) — **Done**.
- `F-108` (`PageActions`, gardes de geste, `readScript`), `F-88` (routes) — **Done**.

### Débloque

- Le **recalage de `TeamsScreen` v2** avec les ancres réelles de la liste des conversations (zone de
  layout gauche) — SF ultérieure, après le relevé réel multi-zones lancé par le PO sur CAGIP.

### Questions ouvertes impactées

Aucune de `docs/OPEN_QUESTIONS.md`.

---

## Notes et décisions

- **D1 — Voie retenue : énumération d'attribut-préfixe dans un seul script.** On lit
  `document.querySelectorAll('[data-tid^="app-layout-area--"]')` — robuste et indépendant des sélecteurs
  cassés de `TeamsScreen`. Tout tient dans **un seul script injecté** (`Runtime.evaluate` déjà autorisé),
  sans écoute d'événement CDP, sans nouvelle commande.
- **D2 — `area` porte désormais le `data-tid` de la zone.** SF-89-17/18 mettaient dans `fields.area` un
  nom de sous-zone (`rail`/`message_runway`/`chat_list`). SF-89-19 l'élargit pour porter le `data-tid` de
  la **zone de layout** (`app-layout-area--sidebar`…). Choix : réutiliser le champ existant plutôt que
  d'en créer un nouveau — `area` reste le discriminant de lecture, désormais assez large. Le PO filtre en
  base par `code=layout_area`/`chat_list` + `area LIKE 'app-layout-area--%'`.
- **D3 — `chat_list` désormais aussi émis depuis `main`.** SF-89-18 l'émettait depuis un cadre
  same-origin (`frame != main`, `area=chat_list`). SF-89-19 l'émet **aussi** depuis le document principal
  (`frame=main`, `area=app-layout-area--<zone>`) quand la liste vit dans une zone de layout — c'est
  l'hypothèse forte du relevé réel. Le `code` est le même ; le `frame`/`area` disent d'où elle vient.
- **D4 — On garde tout l'existant.** Les relevés main/rail/runway/thread (SF-89-16/17) et les chemins
  iframe CDP + `contentDocument` (SF-89-17/18) sont **conservés** pour comparaison et non-régression.
  SF-89-19 **ajoute** une passe multi-zones ; il n'enlève rien.
- **D5 — Sélecteurs de liste stables uniquement.** `LIST_SELECTORS` ne cible que des `data-tid`/`role`
  (`role=tree`/`list`/`grid`, `data-tid` de liste de chats), **jamais** une classe : les classes v2 sont
  hashées et instables (leçon SF-89-16→18).
- **D6 — Triple garde de vie privée inchangée, zone sidebar incluse.** Même schéma élidé dans le script,
  même `TeamsDomShape.refilter`, même `RunnerDiagRedaction` — que la forme vienne de `--main` ou de
  `--sidebar`. Le test central (CA5) porte sur le `refilter` Java + l'assemblage, seuls points
  exécutables sans navigateur.
- **D7 — À VALIDER SUR POSTE RÉEL** : le DOM v2 (et ses zones de layout) n'existent pas en CI. On teste
  ici la **logique** (découverte multi-zones, sélecteurs de liste, `chat_list` dans une zone, bornes,
  expurgation zone incluse) sur navigateur de papier ; **le PO relancera le relevé réel** (DEBUG +
  Vérifier, avec un chat ouvert) sur CAGIP.
- **D8 — Code runner modifié → mise à jour du runner requise** (rebuild + republication du jar par le
  PO ; **non déployé** par cette SF).
