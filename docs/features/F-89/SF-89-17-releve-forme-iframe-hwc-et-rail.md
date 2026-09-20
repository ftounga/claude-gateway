# Mini-spec — F-89 / SF-89-17 — Relevé de forme v2 approfondi : iframe `hwc` + rail de gauche

## Identifiant

`F-89 / SF-89-17`

## Feature parente

`F-89` — Le volet Teams — le terminal Teams (Terminée). Cette SF **approfondit** l'outil de diagnostic
livré par SF-89-16, dans la lignée de SF-89-10/11/12/13/14/15/16.

## Statut

`in-progress`

## Date de création

2026-09-20

## Branche Git

`feat/SF-89-17-releve-forme-iframe-hwc-et-rail`

---

## Objectif

> En une phrase : **approfondir** le relevé de forme du DOM (SF-89-16) pour qu'il **entre dans l'iframe
> `hwc-iframe`** (où vit réellement le chat v2), **capte aussi le rail de gauche** (liste des chats) et
> **descende plus profond** (cap 12 → 22, sous-arbre `message-pane-list-runway`) — toujours **la forme
> seule, jamais le contenu** (même triple garde d'expurgation), pour donner les ancres exactes qui
> recaleront `TeamsScreen` v2 (qui lit aujourd'hui 0 conversation).

---

## Contexte (ce que le 1er relevé SF-89-16 a montré — prod CAGIP 2026-09-20)

Le relevé de forme (SF-89-16, `runner_diag_events`, `cat=shape`) a remonté une structure qui explique le
0 conversation :

1. **Classes CSS hashées/instables** (`fui-*`, `___1wzftc6`, `f1vx9l62`) → inutilisables ; il faut
   s'appuyer sur les **`data-tid`** (`message-pane-list-runway`, `message-pane-body`,
   `message-pane-list-viewport`…).
2. **Une iframe `data-tid=hwc-iframe`** apparaît (0 enfant capté) : le contenu du chat vit **très
   probablement DANS cette iframe**, que le lecteur (document principal) ne voit pas → 0 message lu.
   **Cause n°1 suspectée.**
3. Le relevé a capté le **volet messages** (`app-layout-area--main`) **mais pas la liste des chats** (rail
   de gauche, autre zone de layout), et il s'est **arrêté à la profondeur 12** (tronqué) → nœuds
   auteur/texte non atteints.

**Le point de vie privée reste non négociable** : on relève la forme du DOM de Microsoft — la même pour
tous les clients. Un nom, un message, une adresse, un id de fil n'y entrent pas. Seule la **forme** sort
(balises, `data-tid`/`role`/`class` **assainis**, **noms** d'attributs, profondeur, compteurs) ; tout
nœud texte est **élidé** en longueur — **y compris à l'intérieur de l'iframe**.

---

## Comportement attendu

### Déclenchement (identique à SF-89-16 — aucun nouveau geste)

Inchangé : opt-in gardé par le niveau **DEBUG** (F-132). L'admin clique **« Activer DEBUG »**
(SF-132-05), puis déclenche une **lecture des conversations** (vérification Radar **ou**
`teams_find_conversations`). `TeamsTools.findConversations` — **et seulement à DEBUG** — lance **une fois**
(anti-rafale) le relevé, qui **navigue déjà** vers la vue Conversations, relève la forme (désormais
approfondie), puis **remet la vue**. La sortie part dans le **Journal du runner** (`runner_diag` →
`runner_diag_events` → panneau + `GET /runner-hosts/{hostId}/diag`).

> **Hors DEBUG, le runner est strictement inchangé** : aucun script, aucune navigation, aucun événement,
> aucune auto-attache supplémentaire déclenchée par le relevé.

### Cas nominal du relevé (ce que SF-89-17 ajoute à SF-89-16)

1. **Entrer dans l'iframe `hwc-iframe` (cause n°1).** Le relevé réutilise **l'attache CDP déjà en place**
   (F-100 : `Target.setAutoAttach` — commande **déjà en liste blanche** — + événement
   `Target.attachedToTarget` qui donne un `sessionId` par cadre intégré). Pour chaque cadre **iframe**
   attaché **sur un domaine Microsoft** (garde `MicrosoftDomains.isAllowed` sur `targetInfo.url`), le
   relevé **exécute le script de forme DANS le cadre** via `connection.send(sessionId, Runtime.evaluate, …)`
   (`Runtime.evaluate` **déjà en liste blanche**). La forme du cadre sort marquée `frame` (label du cadre,
   p. ex. `hwc-iframe` par corrélation d'ordre avec les nœuds `iframe` du document principal, sinon
   `iframe#N`). **Aucune nouvelle commande CDP** n'est ajoutée.
2. **Cadre inaccessible → le dire.** Si l'évaluation dans le cadre échoue (cadre détaché, cross-origin non
   attachable, refus) ou ne rend rien, un **événement dédié `code=frame_blocked`** est émis (avec le
   `frame` et le **motif d'hôte** `SurveyPaths.hostMotif`, jamais le tenant) — info précieuse, jamais un
   crash.
3. **Capter le rail de gauche (liste des chats).** En plus de `app-layout-area--main`, le relevé capture
   les **autres zones de layout** ciblées par `data-tid`/`role` (jamais par classe) :
   `app-layout-area--rail`, `chat-list`, `[role=navigation]`, `[role=tree]`… Émis sous `code=chat_rail`
   (avec `fields.area=rail`), **dans le document principal ET dans chaque cadre** (le rail v2 vit
   probablement dans l'iframe).
4. **Descendre plus profond, borné.** Cap de profondeur **12 → 22**. En plus de la racine large, le relevé
   **cible le sous-arbre `message-pane-list-runway`** (et `message-pane-body`/`viewport`) — les enfants
   répétés = messages — pour capter la forme d'**un message** (conteneur, sous-nœuds auteur/horodatage/texte
   — **structure seulement**, texte élidé). Émis sous `code=thread` avec `fields.area=message_runway`, **dans
   le document principal ET dans chaque cadre**.
5. **Volume borné.** Profondeur ≤ `MAX_DEPTH` (22), enfants dépliés/nœud ≤ `MAX_CHILDREN` (40), nœuds/vue
   ≤ `MAX_NODES` (160, inchangé — le **repli des frères** garde le compte bas même en profondeur), cadres
   relevés ≤ `MAX_FRAMES` (3). Au-delà, `truncated=true` est **dit**. Chaque nœud = **un** événement F-132 ;
   un en-tête par vue.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|----------------------|
| Niveau ≠ `DEBUG` | Aucun relevé, aucune navigation, aucune auto-attache, aucun événement. |
| Aucun cadre iframe attaché (setAutoAttach ne remonte rien) | Aucun événement de cadre ; les vues du document principal sont relevées quand même ; jamais d'exception. |
| Cadre iframe **hors domaine Microsoft** | Ignoré (non relevé) — la garde de domaine F-108 s'applique aussi aux cadres. |
| Cadre iframe attaché mais **inaccessible** (détaché, cross-origin non attachable, refus) | Événement `code=frame_blocked` (`frame` + motif d'hôte) ; les autres vues/cadres continuent. |
| Racine large / rail / runway introuvable | En-tête `found=false`, aucun nœud, aucune exception. |
| Nœud portant nom/message/adresse/id (texte, `aria-label`, `title`, `href`, `id`, `data-item-id`…) **dans le document principal OU dans l'iframe** | Seuls balise + `data-tid`/`role`/`class` assainis + **noms** d'attributs + longueur de texte sortent ; aucune valeur, aucun texte. |
| `data-tid`/jeton de classe id-like (`19:…@thread.v2`, GUID, hex long…) | Assaini en `{id}` (`SurveyPaths.looksLikeId`) — inchangé de SF-89-16. |
| Débit d'événements > anneau | Best-effort F-132 : plus ancien écrasé, `dropped` compté (contrat SF-132-01 inchangé). |

---

## Critères d'acceptation

- [ ] **CA1 (inertie)** — Niveau `INFO` : `findConversations` n'émet **aucun** événement `cat=shape`, ne
  lance **aucune** navigation ni auto-attache ; chemin normal byte-identique. (Test.)
- [ ] **CA2 (iframe relevée)** — À `DEBUG`, un DOM modèle avec un cadre iframe **accessible** → sa forme
  est relevée avec `fields.frame` non vide (label du cadre, p. ex. `hwc-iframe`). (Test.)
- [ ] **CA3 (frame_blocked)** — Un cadre iframe attaché mais **inaccessible** → un événement
  `code=frame_blocked` est émis, sans crash, et les autres vues continuent. (Test.)
- [ ] **CA4 (rail)** — Un relevé produit des événements `code=chat_rail` (`fields.area=rail`), distincts du
  volet `main`. (Test.)
- [ ] **CA5 (profondeur accrue)** — Des nœuds de profondeur > 12 sont capturés (`MAX_DEPTH=22`), et le
  volume reste borné (`truncated=true` au-delà des caps). (Test.)
- [ ] **CA6 (message ciblé)** — Un relevé produit des événements `code=thread` avec
  `fields.area=message_runway` pour la forme du sous-arbre `message-pane-list-runway`. (Test.)
- [ ] **CA7 (VIE PRIVÉE — non négociable, iframe incluse)** — Un DOM modèle portant message
  (« bonjour Paul »), nom (« Jean Dupont »), adresse (`jean.dupont@client.fr`), id de fil
  (`19:secret@thread.v2`) — en nœuds texte, `aria-label`, `title`, `href`, `id` — **dans le document
  principal ET DANS l'iframe** → aucune de ces chaînes n'apparaît (ni `msg`, ni `fields`). (Test explicite.)
- [ ] **CA8 (bornes)** — `MAX_DEPTH=22`, `MAX_CHILDREN=40`, `MAX_NODES=160`, `MAX_FRAMES=3` respectés ;
  dépassement → `truncated=true`. (Test.)
- [ ] **CA9 (ne casse rien)** — Le relevé n'échoue jamais vers l'appelant (tout `catch`), remet la vue,
  et la suite runner reste verte (capture, collecte Radar, boucle Vigie, `TeamsAdapterV1`, heartbeat,
  F-132, SF-89-16, F-100 auto-attache réseau). (Test + suite complète.)

---

## Périmètre

### Hors scope (explicite)

- **Recaler `TeamsScreen`** (les vraies valeurs de sélecteurs v2) : c'est la **suite**, après le relevé
  réel approfondi lancé par le PO — comme SF-89-13/15 ont suivi SF-89-12/14.
- **Cliquer / naviguer À L'INTÉRIEUR d'un cadre** (ouvrir un fil dans l'iframe via des événements Input de
  session) : hors scope. Le relevé de cadre lit la forme **de ce qui est déjà affiché** (l'admin a un chat
  ouvert au moment du relevé). On ne pilote pas le cadre.
- Toute **écriture de valeur**, tout envoi de contenu au modèle/à la gateway.
- Tout **nouveau transport / table / endpoint / composant Angular / migration** : on réutilise le pipeline
  F-132 et l'attache F-100 **à l'identique**.
- Toute modification **backend / frontend / base de données**.
- Le relevé des corps **réseau** (SF-89-12/14, `PayloadShape`) : non touché.
- **Aucune nouvelle commande CDP** : on n'utilise que `Runtime.evaluate` et `Target.setAutoAttach`, déjà en
  liste blanche (`CdpCommands`).

---

## Valeurs initiales

Aucune entité. Aucun compteur persistant : la squelette vit le temps d'un relevé et repart avec lui.

## Contraintes de validation

| Champ | Obligatoire | Longueur / borne max | Format / Valeurs autorisées | Normalisation |
|-------|-------------|----------------------|-----------------------------|---------------|
| profondeur (`MAX_DEPTH`) | — | **22** (12 → 22) | au-delà : non déplié, `truncated=true` | — |
| enfants dépliés / nœud (`MAX_CHILDREN`) | — | 40 | au-delà : `truncated=true` | après repli des frères |
| nœuds / vue (`MAX_NODES`) | — | 160 | au-delà : `truncated=true` | ordre DFS pré-ordre |
| cadres relevés (`MAX_FRAMES`) | — | 3 | au-delà : cadres suivants ignorés | ordre d'attache |
| `frame` (label de cadre) | — | 80 | assaini (`scrubToken`) ; `main` pour le doc principal | `iframe#N` par défaut |
| `area` | — | 24 | enum : `rail`, `message_runway` (absent = zone principale) | — |
| motif d'hôte (`frame_blocked`) | — | — | `SurveyPaths.hostMotif` (tenant jamais écrit) | `*.sharepoint.com`… |
| longueur de texte | — | entier | **jamais** le texte, seulement `length` | `asInt(0)` côté Java |
| `tag` / `data-tid` / `role` / noms d'attributs / jetons de classe | — | inchangés SF-89-16 | inchangés SF-89-16 | inchangés SF-89-16 |
| `msg` / `fields` d'événement | — | 300 / scalaires (F-132) | ligne indentée sans valeur | `RunnerDiagRedaction` |

Notes :
- **Aucune valeur de feuille ni de valeur d'attribut sensible n'est jamais écrite** — testé dans le
  document principal **et dans l'iframe** (CA7). La triple garde de SF-89-16 (élision script →
  `TeamsDomShape.refilter` → `RunnerDiagRedaction`) s'applique **identiquement** à la forme relevée dans un
  cadre : le même script, le même `refilter`, le même Journal.

---

## Technique

### Endpoint(s)

Aucun (runner). Sortie via la trame `runner_diag` existante → `GET /api/runner-hosts/{hostId}/diag`.
**Aucun nouvel endpoint.**

### Tables impactées

Aucune. **Aucune migration.** (Réutilise `runner_diag_events`, SF-132-02.)

### Nouveaux `code` / `fields` à lire en base (`cat=shape`)

- `code=chat_rail` — forme du rail de gauche (liste des chats). `fields.area=rail`.
- `code=frame_blocked` — un cadre iframe attaché mais inaccessible. `fields.frame`, `fields.host` (motif).
- `fields.frame` — sur **tous** les événements de forme : `main` (document principal) ou le label du cadre
  (`hwc-iframe` / `iframe#N`).
- `fields.area` — sur les événements ciblés : `rail`, `message_runway` (absent = racine large de la vue).
- `code=conversations_list` / `code=thread` — **inchangés** (SF-89-16), désormais aussi émis avec
  `frame != main` quand relevés dans un cadre.

### Composants

- `runner/teams/TeamsDomShape` (**étendu**) — `MAX_DEPTH` 12 → 22 ; nouvelles racines `RAIL_SELECTORS`,
  `RUNWAY_SELECTORS` (ciblées `data-tid`/`role`) ; `iframeTids(Survey)` (labels de cadre par corrélation
  d'ordre, tids déjà assainis). Le **script**, `refilter`, `line`, `fields`, `scrubToken` : **inchangés**
  (la garde de vie privée ne bouge pas).
- `runner/teams/TeamsDomShapeSurvey` (**étendu**) — après la liste et le fil du document principal, relève
  aussi : le **rail** (doc + cadres), les **cadres iframe** (forme + rail + runway), et le **runway** ciblé ;
  émet `chat_rail` / `frame_blocked` et les champs `frame`/`area`. Ne lève jamais ; remet la vue.
- `runner/teams/PageActions` (**étendu, additif**) — `attachedFrames()` : passe l'onglet en auto-attache
  (réglage F-100, `SET_AUTO_ATTACH` déjà en liste blanche) et collecte les sessions des cadres **iframe** sur
  domaines Microsoft ; `readScriptInFrame(sessionId, expr)` : `Runtime.evaluate` **adressé à la session** du
  cadre (`connection.send(sessionId, …)`, contrat F-100 / SF-100-00). Lecture pure, aucune trace de gros
  volume. `Frame` record (`sessionId`, `url`, `type`).
- `runner/teams/TeamsTools#findConversations` — **inchangé** : la couture `TeamsDomShapeSurvey.run(link, …)`
  de SF-89-16 lance déjà le relevé ; c'est son **corps** qui s'approfondit.
- `runner/teams/CdpCommands` — **inchangé** : aucune nouvelle commande (auto-attache + evaluate déjà là).
- `runner/diag/RunnerDiag` — **inchangé**.

### Préoccupations transversales

| Préoccupation | Impacté ? | Composants vérifiés |
|--------------|-----------|---------------------|
| **Auth / Principal** | **Non** — runner local, aucune session gateway, aucun endpoint, aucun JWT. | — |
| **Contexte tenant / `user_id`** | **Non** — le runner observe le navigateur du poste ; seule la **forme** du DOM quitte la machine (jamais un contenu, jamais un tenant) ; l'isolation `user_id`+`host_id` du Journal est portée par SF-132-02 (inchangée). Aucun nouveau point d'accès aux données. | — |
| **Plans / limites** | **Non**. | — |
| **Navigation / routing (runner)** | **Oui (runner, pas frontend)** — le relevé navigue dans la page Teams (même discipline que SF-89-16 : report d'hôte `onTabHost`, gardes F-108, remise de la vue) **et** passe l'onglet en auto-attache des cadres. Composants runner vérifiés : `PageActions` (gardes de domaine, y compris sur `targetInfo.url` des cadres), `TeamsRoutes` (routes), `TeamsScreenFallback` (patron), **et l'attache F-100** (`NetworkObserver`/`NetworkSurvey` : l'auto-attache y est déjà activée pour la capture réseau ; re-l'activer est idempotent — vérifié : `Target.setAutoAttach` ré-émis ne casse pas l'observation réseau existante). | `PageActions`, `TeamsRoutes`, `TeamsScreenFallback`, `NetworkObserver`, `NetworkSurvey` |

---

## Plan de test

### Tests unitaires (module `runner`, JUnit 5)

- [ ] `TeamsDomShapeTest` — **profondeur accrue (CA5/CA8)** : `MAX_DEPTH=22` ; un nœud à profondeur 21 est
  déplié, à 23 borné ; `iframeTids` extrait les tids des nœuds `iframe` dans l'ordre.
- [ ] `TeamsDomShapeTest` — **racines rail/runway** : `RAIL_SELECTORS`/`RUNWAY_SELECTORS` ciblent des
  `data-tid`/`role` (jamais des classes) ; le script généré pour ces racines garde les mêmes interdits
  (`textContent`/`innerText`/cookie/stockage absents).
- [ ] `TeamsDomShapeTest` — **VIE PRIVÉE (CA7)** : inchangé de SF-89-16, toujours vert (aucune valeur ne
  survit au `refilter`).
- [ ] `TeamsDomShapeSurveyTest` — **inertie (CA1)** : niveau `INFO` → aucun événement, **aucune commande
  CDP** (y compris `SET_AUTO_ATTACH`).
- [ ] `TeamsDomShapeSurveyTest` — **iframe relevée (CA2)** : navigateur de papier avec un cadre iframe
  accessible (session) rendant un DOM modèle → événements portant `fields.frame` non vide ; la forme du
  cadre est relevée.
- [ ] `TeamsDomShapeSurveyTest` — **frame_blocked (CA3)** : un cadre attaché dont l'évaluation échoue →
  événement `code=frame_blocked` émis, pas d'exception, les autres vues continuent.
- [ ] `TeamsDomShapeSurveyTest` — **rail + runway (CA4/CA6)** : événements `code=chat_rail`
  (`area=rail`) et `code=thread` (`area=message_runway`) présents.
- [ ] `TeamsDomShapeSurveyTest` — **VIE PRIVÉE bout-en-bout, iframe incluse (CA7)** : DOM modèle sensible
  **dans le document ET dans le cadre** → aucun événement drainé ne contient nom/message/adresse/id.
- [ ] `TeamsDomShapeSurveyTest` — **anti-rafale / remise de vue / ne lève jamais (CA9)** : inchangés,
  toujours verts.
- [ ] Suite runner complète verte (`cd runner && ./mvnw -q test`), hors l'échec **pré-existant sur `main`**
  `TeamsScreenFallbackTest.the_network_wins_when_it_answers` (documenté en historique SF-89-16).

### Tests d'intégration

- N/A (module runner : pas de contexte Spring). La chaîne `runner_diag` → base → endpoint est **déjà**
  couverte par SF-132-02 (contrat de trame inchangé — mêmes tables/events, nouveaux `code`/`fields`
  scalaires seulement).

### Isolation utilisateur / workspace

- [ ] **Non applicable côté runner** — mono-poste, aucune donnée multi-tenant touchée. La confidentialité
  est couverte par CA7 (aucune valeur, aucun nom, aucun id, aucun texte, **iframe incluse**). L'isolation
  `user_id`+`host_id` du Journal est garantie par SF-132-02 (inchangée).

### Non-régression

- [ ] `findConversations`, capture réunion, collecte Radar, boucle Vigie, `TeamsAdapterV1`, heartbeat,
  F-132, **SF-89-16**, **F-100 (auto-attache réseau)** inchangés à niveau `INFO` (suite runner complète).

---

## Dépendances

### Subfeatures bloquantes (Done)

- `F-89 / SF-89-16` (relevé de forme du DOM `TeamsDomShape`/`TeamsDomShapeSurvey`) — **Done**.
- `F-100 / SF-100-00` (auto-attache des cadres : `Target.setAutoAttach`, `attachedToTarget`,
  `send(sessionId, …)`) — **Done**.
- `F-132 / SF-132-01/02/03/05` (émission `RunnerDiag`, `runner_diag_events`, panneau, bouton DEBUG) — **Done**.
- `F-108` (`PageActions`, gardes de geste), `F-88` (routes) — **Done**.

### Débloque

- Le **recalage de `TeamsScreen` v2** avec les ancres réelles de l'iframe (rail + message) — SF ultérieure,
  après le relevé réel approfondi lancé par le PO sur CAGIP.

### Questions ouvertes impactées

Aucune de `docs/OPEN_QUESTIONS.md`.

---

## Notes et décisions

- **D1 — Entrer dans l'iframe = réutiliser l'attache F-100, pas une nouvelle plomberie.** Le mécanisme
  existe déjà : `Target.setAutoAttach` (liste blanche) donne un `sessionId` par cadre via
  `Target.attachedToTarget`, et `connection.send(sessionId, Runtime.evaluate, …)` exécute un script DANS le
  cadre. On n'ajoute **aucune** commande CDP, **aucun** transport : on **étend** `PageActions` (le seul lieu
  d'où part un geste) avec deux lectures pures.
- **D2 — Garde de domaine aussi sur les cadres.** On ne relève un cadre que si `targetInfo.url` est un
  domaine Microsoft autorisé (`MicrosoftDomains.isAllowed`) — même discipline F-108 que pour la navigation.
  Un cadre hors liste est ignoré (ni relevé, ni `frame_blocked`).
- **D3 — `frame_blocked` = information, pas erreur.** Un cadre attaché mais inévaluable (détaché,
  cross-origin non attachable) est **dit** — c'est précisément le signal qui confirme (ou infirme) que le
  contenu vit dans une iframe hors de portée du document principal.
- **D4 — Rail et message par `data-tid`/`role`, jamais par classe.** SF-89-16 a montré des classes hashées
  instables : les nouvelles racines (`RAIL_SELECTORS`, `RUNWAY_SELECTORS`) ne ciblent que des `data-tid` et
  des `role` structurants.
- **D5 — Profondeur 22, volume borné par le repli des frères.** Le cap monte de 12 à 22 pour atteindre les
  nœuds message/auteur ; `MAX_NODES` reste à 160 car le **repli des frères** (déjà là) garde le compte bas
  même profond. `MAX_FRAMES=3` borne le nombre de cadres relevés.
- **D6 — Label de cadre best-effort.** `frame` vaut `main` (document principal) ou, pour un cadre, le tid
  d'un nœud `iframe` du document principal corrélé par ordre (p. ex. `hwc-iframe`), sinon `iframe#N`. Le tid
  est déjà assaini ; le label est un **confort de lecture**, la valeur utile est la forme du cadre. À
  confirmer sur poste réel.
- **D7 — Triple garde de vie privée inchangée, iframe incluse.** Même script (déjà élidé), même
  `TeamsDomShape.refilter`, même `RunnerDiagRedaction` — que la forme vienne du document ou d'un cadre. Le
  test central (CA7) porte sur le `refilter` Java, seul point exécutable sans navigateur.
- **D8 — À VALIDER SUR POSTE RÉEL** : le DOM v2 (et son iframe) n'existent pas en CI. On teste ici la
  **logique** (bascule de cadre, `frame_blocked`, cap de profondeur, rail/runway, expurgation iframe
  incluse) sur navigateur de papier ; **le PO relancera le relevé réel** (DEBUG + Vérifier) sur CAGIP.
- **D9 — Code runner modifié → mise à jour du runner requise** (rebuild + republication du jar par le PO ;
  **non déployé** par cette SF).
