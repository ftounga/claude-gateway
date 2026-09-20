# Mini-spec — F-89 / SF-89-18 — Relevé de forme : lire l'iframe same-origin (`hwc`) par son contexte d'exécution

## Identifiant

`F-89 / SF-89-18`

## Feature parente

`F-89` — Le volet Teams — le terminal Teams (Terminée). Cette SF **corrige et approfondit** l'outil de
diagnostic livré par SF-89-16/17, dans la lignée de SF-89-10→17.

## Statut

`in-progress`

## Date de création

2026-09-20

## Branche Git

`feat/SF-89-18-iframe-same-origin-contexte`

---

## Objectif

> En une phrase : **faire entrer** le relevé de forme dans les iframes **same-origin** (dont
> `hwc-iframe`, où vit la liste des conversations v2) en descendant `iframe.contentDocument` **dans le
> script injecté lui-même** — puisque l'auto-attache CDP de SF-89-17 ne les attrape pas (pas d'OOPIF
> séparé pour un cadre same-origin) — et remonter leur forme, dont la **liste des chats** (`code=chat_list`),
> avec la **même triple garde** de vie privée, iframe incluse.

---

## Contexte (pourquoi SF-89-17 ne suffit pas — prod CAGIP 2026-09-20)

Le relevé SF-89-17 tente d'entrer dans les iframes via l'**auto-attache de cibles CDP**
(`Target.setAutoAttach` / `Target.attachedToTarget` → un `sessionId` par cadre, puis
`Runtime.evaluate` adressé à la session). **Mais** `hwc-iframe` est **same-origin**
(`teams.microsoft.com` dans `teams.microsoft.com`) : ce n'est **pas** une cible / un OOPIF séparé, donc
l'auto-attache ne l'annonce **jamais**. Résultat du relevé réel de ce jour : **0 événement iframe**,
**0 `frame_blocked`** — le chemin CDP frame de SF-89-17 est inerte pour le cas réel.

**Conclusion (arbitrage technique) :** un iframe **same-origin** se lit par son **contexte d'exécution**,
pas par une cible. Comme il est same-origin, le plus simple et le plus fiable est de le lire **depuis le
document principal** : le script de relevé (`Runtime.evaluate` dans l'onglet, déjà en liste blanche)
**descend dans `iframe.contentDocument`** — accessible en same-origin — et relève sa forme.

**Point vie privée inchangé et non négociable** : on relève la **forme** du DOM de Microsoft (la même
pour tous les clients). Un nom, un message, une adresse, un id de fil n'en sortent jamais — **y compris à
l'intérieur du `contentDocument`** de l'iframe. Même triple garde que SF-89-16/17.

---

## Comportement attendu

### Déclenchement (identique à SF-89-16/17 — aucun nouveau geste)

Inchangé : opt-in gardé par le niveau **DEBUG** (F-132). L'admin clique **« Activer DEBUG »**
(SF-132-05), puis déclenche une **lecture des conversations** (vérification Radar **ou**
`teams_find_conversations`). `TeamsTools.findConversations` — **et seulement à DEBUG** — lance **une
fois** (anti-rafale) le relevé, qui navigue vers la vue Conversations, relève la forme (désormais
iframes same-origin incluses), puis **remet la vue**. Sortie dans le **Journal du runner**
(`runner_diag` → `runner_diag_events` → panneau + `GET /runner-hosts/{hostId}/diag`).

> **Hors DEBUG, le runner est strictement inchangé** : aucun script, aucune navigation, aucun événement,
> aucune auto-attache supplémentaire déclenchée par le relevé.

### Cas nominal du relevé (ce que SF-89-18 ajoute à SF-89-17)

1. **Entrer dans les iframes same-origin (voie retenue : `contentDocument`).** Après le relevé du
   document principal (liste + rail + runway, SF-89-17, inchangé), le relevé exécute **un** script
   supplémentaire **dans l'onglet** (`actions.readScript`, `Runtime.evaluate` déjà en liste blanche —
   **aucune nouvelle commande CDP**). Ce script parcourt les éléments `<iframe>` du document (bornés à
   `MAX_FRAMES`) et, pour chacun, tente d'accéder à `iframe.contentDocument`. **Même triple garde** : le
   script n'émet que la forme (aucun `textContent`/`innerText`/cookie/stockage), `TeamsDomShape.refilter`
   re-filtre, `RunnerDiagRedaction` re-expurge — appliqués **identiquement** au DOM du `contentDocument`.
2. **Same-origin (`contentDocument` non nul) → relever sa forme.** Dans le `contentDocument`, le relevé
   cible : sa **racine large** (`ROOT_SELECTORS` → `code=conversations_list`), son **rail**
   (`RAIL_SELECTORS` → `code=chat_rail`, `area=rail`), son **runway** de messages
   (`RUNWAY_SELECTORS` → `code=thread`, `area=message_runway`) et, **nouveau**, la **liste des chats**
   (`LIST_SELECTORS` → `code=chat_list`, `area=chat_list`) — ciblée par `role=tree`/`role=list`/`data-tid`
   de liste de chats (`chat-list`, `chatListItem`…), **distincte** du `chat_rail` (barre d'icônes d'app,
   capté à tort avant). Tous ces événements portent `fields.frame` = label du cadre (`hwc-iframe` par le
   `data-tid` de l'`<iframe>`, sinon `iframe#N`).
3. **Cross-origin (`contentDocument` nul / accès refusé) → le dire.** Si `iframe.contentDocument` est
   `null` (ou l'accès lève, cross-origin), un événement **`code=frame_blocked`** est émis (avec le
   `frame` label ; **aucune URL du cadre n'est lue** — on ne franchit pas la barrière d'origine), jamais
   un crash. `frame_blocked` est **désormais réservé** aux iframes réellement cross-origin.
4. **Volume borné.** `MAX_FRAMES` (3) iframes au plus ; par cadre, mêmes bornes que le document
   (`MAX_DEPTH=22`, `MAX_CHILDREN=40`, `MAX_NODES=160`). Au-delà, `truncated=true` est **dit**. Chaque
   nœud = **un** événement F-132 ; un en-tête par vue.
5. **Le chemin CDP frame de SF-89-17 est conservé** (non-régression) : il peut encore attraper un vrai
   OOPIF cross-origin attaché. Les deux mécanismes coexistent ; en prod same-origin seul le nouveau
   remonte quelque chose.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|----------------------|
| Niveau ≠ `DEBUG` | Aucun relevé, aucune navigation, aucun script iframe, aucun événement. |
| Aucun `<iframe>` dans le document | Aucun événement de cadre same-origin ; le document principal est relevé quand même ; jamais d'exception. |
| Iframe **cross-origin** (`contentDocument` nul / accès refusé) | Événement `code=frame_blocked` (`frame`) ; les autres cadres/vues continuent ; pas de crash, pas d'URL lue. |
| Iframe same-origin **sans liste** (racine/rail/runway/list introuvables) | En-tête `found=false`, aucun nœud, aucune exception. |
| Script iframe refusé par la garde F-108 (page sortie du domaine) | `BrowserLinkException` avalée → relevé de cadre vide, le document principal reste relevé. |
| Nœud portant nom/message/adresse/id (texte, `aria-label`, `title`, `href`, `id`…) **dans le `contentDocument`** | Seuls balise + `data-tid`/`role`/`class` assainis + **noms** d'attributs + longueur de texte sortent ; aucune valeur, aucun texte. |
| `data-tid`/jeton de classe id-like (`19:…@thread.v2`, GUID, hex long…) | Assaini en `{id}` (`SurveyPaths.looksLikeId`) — inchangé. |
| Débit d'événements > anneau | Best-effort F-132 : plus ancien écrasé, `dropped` compté (contrat SF-132-01 inchangé). |

---

## Critères d'acceptation

- [ ] **CA1 (inertie)** — Niveau `INFO` : `findConversations` n'émet **aucun** événement `cat=shape`, ne
  lance **aucun** script iframe, aucune navigation. (Test.)
- [ ] **CA2 (iframe same-origin relevée par contentDocument)** — À `DEBUG`, un DOM modèle avec un iframe
  same-origin **accessible** (contentDocument non nul) portant une liste → sa forme est relevée avec
  `fields.frame` = `hwc-iframe`. (Test.)
- [ ] **CA3 (chat_list)** — La liste des chats **dans l'iframe** (role=tree/list + items) est relevée sous
  `code=chat_list` (`area=chat_list`), **distincte** de `chat_rail`. (Test.)
- [ ] **CA4 (cross-origin → frame_blocked)** — Un iframe **cross-origin** (`contentDocument` nul) →
  événement `code=frame_blocked`, sans crash, les autres vues continuent ; **aucune URL du cadre** n'est
  lue. (Test.)
- [ ] **CA5 (VIE PRIVÉE — non négociable, iframe same-origin incluse)** — Un DOM modèle portant message
  (« bonjour Paul »), nom (« Jean Dupont », « Marie Martin »), adresse (`jean.dupont@client.fr`), id de
  fil (`19:secret@thread.v2`) — en nœuds texte, `aria-label`, `title`, `href`, `id` — **dans le
  `contentDocument` de l'iframe same-origin** → aucune de ces chaînes n'apparaît (ni `msg`, ni
  `fields`). (Test explicite.)
- [ ] **CA6 (bornes)** — `MAX_FRAMES=3`, `MAX_DEPTH=22`, `MAX_CHILDREN=40`, `MAX_NODES=160` respectés ;
  dépassement → `truncated=true`. (Test.)
- [ ] **CA7 (script sans interdits, iframe incluse)** — Le script de descente iframe ne lit jamais
  `textContent`/`innerText` comme valeur, ni `document.cookie`, ni `localStorage`/`sessionStorage`. (Test.)
- [ ] **CA8 (ne casse rien)** — Le relevé ne lève jamais vers l'appelant (tout `catch`), remet la vue, et
  la suite runner reste verte : capture réunion, collecte Radar, boucle Vigie, `TeamsAdapterV1`,
  heartbeat, F-132, F-100, **SF-89-16/17** (dont le chemin CDP frame conservé). (Test + suite complète.)

---

## Périmètre

### Hors scope (explicite)

- **Recaler `TeamsScreen`** (les vraies valeurs de sélecteurs v2) : c'est la **suite**, après le relevé
  réel same-origin lancé par le PO — comme SF-89-13/15 ont suivi SF-89-12/14.
- **Piloter / cliquer À L'INTÉRIEUR d'un contentDocument** : hors scope. On lit la forme de ce qui est
  déjà affiché (l'admin a un chat ouvert au moment du relevé).
- **Retirer le chemin CDP frame de SF-89-17** : conservé pour non-régression et OOPIF cross-origin réels.
- Toute **écriture de valeur**, tout envoi de contenu au modèle / à la gateway.
- Tout **nouveau transport / table / endpoint / composant Angular / migration** : on réutilise le pipeline
  F-132 et `Runtime.evaluate` **à l'identique**.
- Toute modification **backend / frontend / base de données**.
- Le relevé des corps **réseau** (SF-89-12/14, `PayloadShape`) : non touché.
- **Aucune nouvelle commande CDP** : on n'utilise que `Runtime.evaluate`, déjà en liste blanche.

---

## Valeurs initiales

Aucune entité. Aucun compteur persistant : la squelette vit le temps d'un relevé et repart avec lui.

## Contraintes de validation

| Champ | Obligatoire | Longueur / borne max | Format / Valeurs autorisées | Normalisation |
|-------|-------------|----------------------|-----------------------------|---------------|
| iframes descendus (`MAX_FRAMES`) | — | 3 | au-delà : cadres suivants ignorés | ordre DOM |
| profondeur (`MAX_DEPTH`) | — | 22 | au-delà : non déplié, `truncated=true` | — |
| enfants dépliés / nœud (`MAX_CHILDREN`) | — | 40 | au-delà : `truncated=true` | après repli des frères |
| nœuds / vue (`MAX_NODES`) | — | 160 | au-delà : `truncated=true` | ordre DFS pré-ordre |
| `frame` (label de cadre) | — | 80 | assaini (`scrubToken`) ; `main` pour le doc principal | `iframe#N` par défaut |
| `area` | — | 24 | enum : `rail`, `message_runway`, `chat_list` (absent = racine large) | — |
| `frame_blocked` | — | — | `frame` seul ; **aucune URL de cadre cross-origin lue** | — |
| longueur de texte | — | entier | **jamais** le texte, seulement `length` | `asInt(0)` côté Java |
| `tag`/`data-tid`/`role`/noms d'attributs/jetons de classe | — | inchangés SF-89-16 | inchangés SF-89-16 | inchangés SF-89-16 |
| `msg` / `fields` d'événement | — | 300 / scalaires (F-132) | ligne indentée sans valeur | `RunnerDiagRedaction` |

Notes :
- **Aucune valeur de feuille ni de valeur d'attribut sensible n'est jamais écrite** — testé dans le
  `contentDocument` de l'iframe same-origin (CA5). La triple garde de SF-89-16 (élision script →
  `TeamsDomShape.refilter` → `RunnerDiagRedaction`) s'applique **identiquement** à la forme relevée dans
  un `contentDocument` : le même `refilter`, le même Journal.

---

## Technique

### Endpoint(s)

Aucun (runner). Sortie via la trame `runner_diag` existante → `GET /api/runner-hosts/{hostId}/diag`.
**Aucun nouvel endpoint.**

### Tables impactées

Aucune. **Aucune migration.** (Réutilise `runner_diag_events`, SF-132-02.)

### Nouveaux `code` / `fields` à lire en base (`cat=shape`)

- `code=chat_list` — la **liste des chats** relevée dans un cadre same-origin. `fields.area=chat_list`.
- `fields.frame` — sur les événements de forme relevés dans un cadre same-origin : le label du cadre
  (`hwc-iframe` / `iframe#N`) ; `main` pour le document principal (inchangé SF-89-17).
- `code=conversations_list` / `chat_rail` / `thread` / `frame_blocked` — **inchangés** (SF-89-16/17),
  désormais aussi émis avec `frame != main` quand relevés dans un cadre **same-origin** (via
  `contentDocument`). `frame_blocked` réservé aux iframes **cross-origin**.

### Composants

- `runner/teams/TeamsDomShape` (**étendu**) — nouvelle racine `LIST_SELECTORS` (liste des chats, ciblée
  `data-tid`/`role`) ; nouveau script `framesScript(mapper)` (« cg-domframes ») qui descend dans
  `iframe.contentDocument` des iframes same-origin et relève leur forme (mêmes interdits) ; record
  `FrameShape` (label, blocked, surveys par zone) + `refilterFrames(JsonNode)` (borné, label assaini).
  Le script existant, `refilter`, `line`, `fields`, `scrubToken`, `Node`, `Survey` : **inchangés**.
- `runner/teams/TeamsDomShapeSurvey` (**étendu**) — après le document principal et le chemin CDP frame
  (conservé), appelle `surveySameOriginFrames()` : lit `framesScript` via `actions.readScript`, re-filtre,
  et émet par cadre `conversations_list` / `chat_list` / `chat_rail` / `thread` (ou `frame_blocked` si
  cross-origin), étiquetés `frame`/`area`. Ne lève jamais ; remet la vue. Nouveau `code=chat_list`,
  `area=chat_list`.
- `runner/teams/PageActions` — **inchangé** : `readScript(expression)` (déjà là, `Runtime.evaluate`)
  suffit ; **aucune nouvelle méthode, aucune nouvelle commande CDP**.
- `runner/teams/TeamsTools#findConversations` — **inchangé** : la couture `TeamsDomShapeSurvey.run(...)`
  lance déjà le relevé ; c'est son corps qui s'approfondit.
- `runner/teams/CdpCommands` — **inchangé** : `Runtime.evaluate` déjà en liste blanche.
- `runner/diag/RunnerDiag` — **inchangé**.

### Préoccupations transversales

| Préoccupation | Impacté ? | Composants vérifiés |
|--------------|-----------|---------------------|
| **Auth / Principal** | **Non** — runner local, aucune session gateway, aucun endpoint, aucun JWT. | — |
| **Contexte tenant / `user_id`** | **Non** — le runner observe le navigateur du poste ; seule la **forme** du DOM quitte la machine (jamais un contenu, jamais un tenant) ; l'isolation `user_id`+`host_id` du Journal est portée par SF-132-02 (inchangée). Aucun nouveau point d'accès aux données. | — |
| **Plans / limites** | **Non**. | — |
| **Navigation / routing (runner)** | **Oui (runner, pas frontend)** — le relevé navigue dans la page Teams (même discipline que SF-89-16/17 : report d'hôte `onTabHost`, gardes F-108 via `readScript` → `assertCurrentPageAllowed`, remise de la vue). **Le nouveau script iframe ne navigue pas** (il lit `contentDocument` depuis l'onglet). Composants runner vérifiés : `PageActions` (garde de page courante sur `readScript`), `TeamsRoutes` (routes inchangées), `TeamsScreenFallback` (patron), l'attache F-100 (`NetworkObserver`/`NetworkSurvey` : intacte, le chemin CDP frame de SF-89-17 est conservé). | `PageActions`, `TeamsRoutes`, `TeamsScreenFallback`, `NetworkObserver`, `NetworkSurvey` |

---

## Plan de test

### Tests unitaires (module `runner`, JUnit 5)

- [ ] `TeamsDomShapeTest` — **`LIST_SELECTORS`** ciblent des `data-tid`/`role` (jamais des classes) ; le
  script `framesScript` généré garde les mêmes interdits (`textContent`/`innerText`/cookie/stockage
  absents) et descend bien via `contentDocument` (CA7).
- [ ] `TeamsDomShapeTest` — **`refilterFrames`** : un payload frames modèle (une iframe same-origin avec
  liste + une iframe cross-origin `blocked`) → labels assainis, borne `MAX_FRAMES`, expurgation appliquée
  (CA6).
- [ ] `TeamsDomShapeTest` — **VIE PRIVÉE (`refilter`)** : inchangé de SF-89-16, toujours vert.
- [ ] `TeamsDomShapeSurveyTest` — **inertie (CA1)** : niveau `INFO` → aucun événement, aucun script.
- [ ] `TeamsDomShapeSurveyTest` — **iframe same-origin relevée (CA2/CA3)** : navigateur de papier rendant
  un `framesDom` same-origin avec une liste → événements `fields.frame=hwc-iframe`, dont un `code=chat_list`
  (`area=chat_list`).
- [ ] `TeamsDomShapeSurveyTest` — **cross-origin → frame_blocked (CA4)** : une iframe `blocked`
  (contentDocument nul) → `code=frame_blocked`, pas d'exception, pas d'URL de cadre ; les autres continuent.
- [ ] `TeamsDomShapeSurveyTest` — **VIE PRIVÉE bout-en-bout, iframe same-origin incluse (CA5)** : valeurs
  sensibles semées **dans le contentDocument** → aucun événement drainé ne contient nom/message/adresse/id.
- [ ] `TeamsDomShapeSurveyTest` — **non-régression SF-89-17** : le chemin CDP frame (hwc-iframe,
  frame_blocked via session) reste vert ; anti-rafale / remise de vue / ne lève jamais inchangés.
- [ ] Suite runner complète verte (`cd runner && ./mvnw -q test`), hors l'échec **pré-existant sur `main`**
  `TeamsScreenFallbackTest.the_network_wins_when_it_answers` (documenté en historique SF-89-16/17).

### Tests d'intégration

- N/A (module runner : pas de contexte Spring). La chaîne `runner_diag` → base → endpoint est **déjà**
  couverte par SF-132-02 (contrat de trame inchangé — mêmes tables/events, nouveaux `code`/`fields`
  scalaires seulement).

### Isolation utilisateur / workspace

- [ ] **Non applicable côté runner** — mono-poste, aucune donnée multi-tenant touchée. La confidentialité
  est couverte par CA5 (aucune valeur, aucun nom, aucun id, aucun texte, **contentDocument inclus**).
  L'isolation `user_id`+`host_id` du Journal est garantie par SF-132-02 (inchangée).

### Non-régression

- [ ] `findConversations`, capture réunion, collecte Radar, boucle Vigie, `TeamsAdapterV1`, heartbeat,
  F-132, **SF-89-16/17** (dont le chemin CDP frame), **F-100** inchangés à niveau `INFO` (suite runner).

---

## Dépendances

### Subfeatures bloquantes (Done)

- `F-89 / SF-89-16` (relevé de forme `TeamsDomShape`/`TeamsDomShapeSurvey`) — **Done**.
- `F-89 / SF-89-17` (rail + runway + profondeur + chemin CDP frame) — **Done**.
- `F-132 / SF-132-01/02/03/05` (émission `RunnerDiag`, `runner_diag_events`, panneau, bouton DEBUG) — **Done**.
- `F-108` (`PageActions`, gardes de geste, `readScript`), `F-88` (routes) — **Done**.

### Débloque

- Le **recalage de `TeamsScreen` v2** avec les ancres réelles de l'iframe same-origin (liste + message) —
  SF ultérieure, après le relevé réel same-origin lancé par le PO sur CAGIP.

### Questions ouvertes impactées

Aucune de `docs/OPEN_QUESTIONS.md`.

---

## Notes et décisions

- **D1 — Voie retenue : `contentDocument`, pas `contextId`.** Pour un iframe **same-origin**, le document
  principal peut lire `iframe.contentDocument` directement. C'est **plus simple et plus fiable** que la
  voie `Runtime.executionContextCreated` + `Runtime.evaluate(contextId=…)` : tout tient dans **un seul
  script injecté** (déjà autorisé, `Runtime.evaluate` dans l'onglet), sans écoute d'événement CDP, sans
  corrélation `auxData.frameId`, sans `Runtime.enable`. La voie `contextId` reste possible pour un cadre
  cross-origin — mais un cadre cross-origin n'est de toute façon pas lisible (barrière d'origine) et est
  simplement **dit** par `frame_blocked`. Le garde-fou est intrinsèque : on ne descend que si
  `contentDocument` est non nul.
- **D2 — `chat_list` vs `chat_rail`.** SF-89-17 captait sous `chat_rail` la barre d'icônes d'application
  (rail de gauche au sens layout), pas la **liste des conversations**. SF-89-18 introduit `code=chat_list`
  ciblant spécifiquement une liste (`role=tree`/`role=list`/`data-tid` de liste de chats) **à l'intérieur**
  du cadre — c'est elle qui portait le *0 conversation*.
- **D3 — `frame_blocked` recentré sur le cross-origin.** Désormais un cadre est `frame_blocked` **si et
  seulement si** son `contentDocument` est nul (cross-origin / non lisible). C'est le signal qui confirme
  qu'un cadre vit hors de portée — et non plus l'échec d'une attache CDP qui, pour un same-origin, n'arrive
  jamais.
- **D4 — Aucune URL de cadre cross-origin lue.** Pour un `frame_blocked`, on ne lit pas le `src` de
  l'`<iframe>` (éviter toute fuite) : seul le label (index / `data-tid` assaini) est émis.
- **D5 — Chemin CDP frame de SF-89-17 conservé.** On n'enlève rien : le chemin `attachedFrames()` +
  `readScriptInFrame(sessionId)` reste (OOPIF cross-origin réels, non-régression). Les deux mécanismes
  coexistent sans se gêner ; en prod same-origin, seul le nouveau remonte.
- **D6 — Triple garde de vie privée inchangée, contentDocument inclus.** Même schéma élidé dans le script,
  même `TeamsDomShape.refilter`, même `RunnerDiagRedaction` — que la forme vienne du document ou d'un
  `contentDocument`. Le test central (CA5) porte sur le `refilter` Java + l'assemblage, seuls points
  exécutables sans navigateur.
- **D7 — À VALIDER SUR POSTE RÉEL** : le DOM v2 (et son iframe same-origin) n'existent pas en CI. On teste
  ici la **logique** (descente `contentDocument`, `chat_list`, cross-origin → `frame_blocked`, bornes,
  expurgation contentDocument incluse) sur navigateur de papier ; **le PO relancera le relevé réel**
  (DEBUG + Vérifier, avec un chat ouvert) sur CAGIP.
- **D8 — Code runner modifié → mise à jour du runner requise** (rebuild + republication du jar par le PO ;
  **non déployé** par cette SF).
