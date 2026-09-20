# Mini-spec — F-89 / SF-89-16 — Relevé de forme du DOM des conversations Teams v2 (pour recalibrer l'adaptateur)

## Identifiant

`F-89 / SF-89-16`

## Feature parente

`F-89` — Le volet Teams — le terminal Teams (Terminée ; cette SF est un correctif/outil de diagnostic
d'une feature livrée, dans la lignée de SF-89-10/11/12/13/14/15).

## Statut

`in-progress`

## Date de création

2026-09-20

## Branche Git

`feat/SF-89-16-releve-forme-dom-conversations`

---

## Objectif

> En une phrase : ajouter un **relevé de forme du DOM** — la SQUELETTE de la vue Conversations (liste
> des fils) puis d'un fil ouvert (vue messages) du nouveau Teams (v2) —, **jamais un contenu**, remonté
> dans le **Journal du runner (F-132)**, pour que l'on puisse **recaler les sélecteurs `TeamsScreen` v2**
> qui lisent aujourd'hui **0 conversation**.

---

## Contexte (cause racine confirmée en prod CAGIP le 2026-09-20)

`TeamsScreenFallback.list(TeamsScreen.CONVERSATIONS, TeamsRoutes.CONVERSATIONS)` **navigue bien** vers la
vue Conversations (route ouverte puis lue — vérifié), **mais lit 0 conversation** sur le Teams v2 : les
**sélecteurs DOM** de `TeamsScreen` (conteneur `[data-tid="chat-list"]`, élément `[data-tid="chat-list-item"]`,
titre, fil `[data-tid="message-pane-list-viewport"]`…) ont été **calibrés sur une version antérieure** et
ne matchent pas la v2. Les réunions/calendrier remontent (source réseau, recalée par SF-89-15) ;
conversations et messages **viennent de l'écran** (cache Chrome, pas du réseau — voir SF-89-15 §
« Conversations / messages — hors recalage »), donc **c'est la table de sélecteurs `TeamsScreen` qu'il
faut recaler**, pas un adaptateur réseau.

SF-89-15 l'avait explicitement renvoyé à un futur relevé : *« Un futur relevé « forme » ouvrant un fil
non caché le complètera. »* SF-89-12/14 relevaient la forme des **corps JSON réseau** (`PayloadShape`) ;
**ici la forme est celle du DOM** — un besoin distinct et nouveau.

Le point de **vie privée est non négociable** : le relevé sert à dresser la table des **formes du DOM de
Microsoft**, les mêmes pour tous les clients. Un nom, un message, une adresse, un identifiant de fil n'ont
rien à faire dans le rapport et n'y entrent pas. On ne remonte que la **forme** : noms de balises,
attributs **structurants** (`data-tid`, `role`, `class`, **noms** des attributs `aria-*`/autres),
profondeur, et **compteurs** d'éléments répétés. Tout nœud texte est **élidé** (remplacé par sa longueur).

---

## Comportement attendu

### Déclenchement (décision : le plus simple, sans DevTools)

Le relevé est **opt-in, gardé par le niveau de diagnostic F-132** — c'est le « flag debug » proposé par
le cadrage, et il **réutilise deux gestes admin déjà livrés**, sans nouveau bouton, sans endpoint,
sans plomberie app→runner :

1. Dans la **Vigie → poste → panneau « Journal du runner »** (SF-132-03), l'admin clique
   **« Activer DEBUG »** (bouton existant `enableDebug`, F-132/SF-132-05) : le poste passe en niveau
   `DEBUG` pour la durée choisie.
2. L'admin déclenche une **lecture des conversations** — soit via la **vérification Radar** existante,
   soit en demandant à l'assistant, dans un terminal Teams, de **lister ses conversations Teams**
   (outil `teams_find_conversations`).
3. Ce faisant, `TeamsTools.findConversations` — **et seulement quand le niveau est `DEBUG`** — lance
   **une fois** (throttle) le relevé de forme du DOM, qui **navigue déjà** vers la vue Conversations
   (chemin confirmé), relève la forme, **ouvre un fil** et relève sa forme, puis **remet la vue**.
4. La forme part dans le **Journal du runner** (trame `runner_diag` existante) → table
   `runner_diag_events` → lisible dans le panneau **et en base** (`GET /runner-hosts/{hostId}/diag`).

> **Hors niveau `DEBUG`, le comportement du runner est strictement inchangé** : aucun script de relevé,
> aucune navigation supplémentaire, aucun événement — exactement comme le mode `--forme` de SF-89-12 est
> inerte sans son drapeau.

### Cas nominal du relevé

1. **Vue liste.** Le relevé navigue vers `TeamsRoutes.CONVERSATIONS` (report d'hôte via `onTabHost`,
   même discipline que `TeamsScreenFallback.list`), attend le chargement, puis extrait la **squelette du
   DOM** d'une **racine large et robuste** (candidats `[data-tid="app-layout-area--main"]`, `[role="main"]`,
   `main`, `#app`, repli `document.body`) — **sans dépendre des sélecteurs cassés**, puisque le but est de
   révéler la forme *réelle*. Événement F-132 `cat=shape`, `code=conversations_list`.
2. **Vue fil.** Le relevé clique le **premier fil plausible** (jeu de sélecteurs large et indépendant de
   la table cassée : `[role="treeitem"]`, `[role="listitem"] a`, `a[href*="conversations"]`,
   `[data-tid$="list-item"]`…), attend, puis extrait la squelette de la même racine large (elle montre
   alors les messages). Événement F-132 `cat=shape`, `code=thread`. Si aucun fil n'a pu être ouvert, un
   champ `opened=false` le **dit** et la squelette de la liste est relevée quand même.
3. **Squelette (par nœud).** Pour chaque nœud retenu : `tagName`, `data-tid` (assaini), `role`,
   **noms** des attributs `aria-*` et des autres attributs (jamais leurs valeurs), jetons de `class`
   (assainis/tronqués), **profondeur**, **longueur totale des nœuds texte directs** (un entier, jamais le
   texte), **nombre d'enfants**, et le **compteur `n`** d'éléments frères identiques repliés
   (« 12 frères `data-tid=chat-list-item` » → un nœud représentatif + `n=12`, on ne descend que dans le
   premier — même patron que « premier élément de tableau » de `PayloadShape`).
4. **Volume borné.** Profondeur ≤ `MAX_DEPTH` (12), enfants dépliés par nœud ≤ `MAX_CHILDREN` (40),
   nœuds par vue ≤ `MAX_NODES` (160) ; au-delà, `truncated=true` est **dit**. Chaque nœud = **un**
   événement F-132 (`msg` = une ligne indentée lisible ≤ 300 car, `fields` = scalaires structurants),
   plus un événement d'en-tête par vue (compteurs). ≈ 2×(1+160) ≤ 322 événements par relevé, sous
   l'anneau runner (500) et l'anneau poste (2000).
5. **Traçabilité / consentement.** Le passage en `DEBUG` est un geste admin explicite et **time-boxé**
   (SF-132-05) ; l'en-tête de chaque vue dit ce qui a été relevé (nombre de nœuds, profondeur, tronqué).

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|----------------------|
| Niveau ≠ `DEBUG` | Aucun relevé, aucune navigation, aucun événement (runner inchangé). |
| Racine introuvable (page inattendue) | Repli `document.body` ; si toujours rien, en-tête `found=false`, aucun nœud, aucune exception. |
| Aucun fil ouvrable (sélecteurs larges vides) | En-tête `thread` `opened=false` ; la liste est relevée quand même ; jamais d'exception. |
| Navigation refusée par la garde F-108 (hors domaine / page d'identification) | `BrowserLinkException` **avalée** ; en-tête `found=false`, la lecture des conversations n'est pas cassée. |
| Nœud portant un nom / message / adresse / id de fil (texte, `aria-label`, `title`, `href`, `id`, `data-item-id`…) | **Seuls** balise + `data-tid`/`role`/`class` (assainis) + **noms** d'attributs + longueur de texte sortent ; aucune valeur, aucun texte. |
| `data-tid` / jeton de classe ressemblant à un identifiant (`19:…@thread.v2`, GUID, hex long…) | Assaini en `{id}` (`SurveyPaths.looksLikeId`) — jamais la valeur brute. |
| Débit d'événements > anneau | Best-effort F-132 : plus ancien écrasé, `dropped` compté (contrat SF-132-01 inchangé). |

---

## Critères d'acceptation

- [ ] **CA1 (inertie)** — Niveau `INFO` : `findConversations` n'émet **aucun** événement `cat=shape` et
  ne lance **aucune** navigation/‌script de relevé ; le chemin normal est **byte-identique**. (Test.)
- [ ] **CA2 (liste)** — Niveau `DEBUG` : un relevé produit des événements `cat=shape, code=conversations_list`
  portant, par nœud, `tag` + `data-tid` + `role` + profondeur + compteur `n` + nombre d'enfants.
- [ ] **CA3 (fil)** — Un relevé produit des événements `cat=shape, code=thread` pour la forme d'un fil
  ouvert (ou `opened=false` dit si aucun fil ouvrable).
- [ ] **CA4 (VIE PRIVÉE — non négociable)** — Un DOM modèle portant un message (« bonjour Paul »), un nom
  (« Jean Dupont »), une adresse (`jean.dupont@client.fr`) et un id de fil (`19:secret@thread.v2`) —
  **en nœuds texte, en `aria-label`, `title`, `href`, `id`** — produit une squelette où **aucune** de ces
  chaînes n'apparaît (ni `msg`, ni `fields`) : seulement balises, `data-tid`/`role`/`class` assainis,
  **noms** d'attributs, entiers de longueur/compteur. (Test explicite.)
- [ ] **CA5 (élision texte)** — Tout nœud texte est remplacé par sa **longueur** (entier) ; aucune valeur
  de feuille texte n'est jamais écrite.
- [ ] **CA6 (assainissement)** — Un `data-tid` ou un jeton de classe ressemblant à un id est ramené à
  `{id}` ; `role`/noms d'attributs sont bornés en longueur.
- [ ] **CA7 (bornes)** — Profondeur (12), enfants dépliés (40), nœuds par vue (160) sont respectés ;
  un dépassement met `truncated=true`.
- [ ] **CA8 (repli frères)** — N frères de même signature (`tag|data-tid|role`) produisent **un** nœud
  avec `n=N`, et l'on ne descend que dans le premier.
- [ ] **CA9 (ne casse rien)** — Le relevé n'échoue jamais vers l'appelant (tout `catch`), remet la vue,
  et la suite runner reste verte (capture, collecte, Vigie, F-132, `TeamsAdapterV1`, heartbeat).

---

## Périmètre

### Hors scope (explicite)

- **Recaler `TeamsScreen`** (les vraies valeurs de sélecteurs v2) : c'est la **suite**, après un relevé
  réel lancé par le PO — comme SF-89-13/15 ont suivi SF-89-12/14.
- Toute **écriture de valeur** (texte de message, nom, valeur d'attribut sensible), tout envoi de contenu
  au modèle ou à la gateway.
- Tout **nouveau transport / table / endpoint / composant Angular** : on **réutilise** intégralement le
  pipeline F-132 (émission `RunnerDiag` → trame `runner_diag` → `runner_diag_events` → panneau + base).
- Toute modification **backend / frontend / base de données**.
- Le relevé des corps **réseau** (SF-89-12/14, `PayloadShape`) : non touché.

---

## Valeurs initiales

Aucune entité. Aucun compteur persistant : la squelette vit le temps d'un relevé et repart avec lui.

## Contraintes de validation

| Champ | Obligatoire | Longueur / borne max | Format / Valeurs autorisées | Normalisation |
|-------|-------------|----------------------|-----------------------------|---------------|
| profondeur (`MAX_DEPTH`) | — | 12 | au-delà : non déplié, `truncated=true` | — |
| enfants dépliés / nœud (`MAX_CHILDREN`) | — | 40 | au-delà : `truncated=true` | après repli des frères |
| nœuds / vue (`MAX_NODES`) | — | 160 | au-delà : `truncated=true` | ordre DFS pré-ordre |
| longueur de texte | — | entier | **jamais** le texte, seulement `length` | `asInt(0)` côté Java |
| `tag` | Oui | 24 | minuscules `[a-z0-9-]` | tronqué |
| `data-tid` | — | 80 | assaini | `{id}` si `looksLikeId`, sinon tronqué |
| `role` | — | 40 | jeton court | tronqué |
| noms d'attributs (`aria`/`attrs`) | — | 16 noms, 40 car/nom | `^[a-zA-Z][a-zA-Z0-9:_-]*$` | rejet si valeur/`=`/espace |
| jetons de `class` | — | 6 jetons, 40 car/jeton | assainis | `{id}` si `looksLikeId` |
| `msg` d'événement | — | 300 (F-132) | ligne indentée sans valeur | tronqué (redaction F-132) |
| `fields` d'événement | — | scalaires, ≤ 24, 120 car/champ (F-132) | nombres/booléens/noms | `RunnerDiagRedaction` |

Notes :
- **Aucune valeur de feuille ni de valeur d'attribut sensible n'est jamais écrite** — point de vie
  privée testé (CA4/CA5). Le type/nom de balise et d'attribut, et les valeurs `data-tid`/`role`/`class`
  **assainies**, ne sont pas des données client : c'est ce qui permet de recaler `TeamsScreen`.
- Double garde : la squelette est produite par le script (déjà élidée), **puis re-filtrée en Java**
  (`TeamsDomShape.refilter`), **puis** re-expurgée par `RunnerDiagRedaction` à l'entrée du Journal.

---

## Technique

### Endpoint(s)

Aucun (runner). Sortie via la **trame `runner_diag` existante** (F-132/SF-132-01) → endpoint de lecture
existant `GET /api/runner-hosts/{hostId}/diag` (SF-132-02). **Aucun nouvel endpoint.**

### Tables impactées

Aucune. **Aucune migration.** (Réutilise `runner_diag_events`, créée par SF-132-02.)

### Composants

- `runner/teams/TeamsDomShape` (**nouveau**) — fonctions pures : générateur du **script JS** de relevé de
  forme (racine → squelette DFS élidée, repli des frères, bornes) ; `refilter(JsonNode)` (re-filtre Java,
  cœur du test de vie privée, assainit `data-tid`/classe via `SurveyPaths.looksLikeId`) ; `line(Node)`
  (ligne `msg`) ; `fields(Node, seq)` (carte scalaire).
- `runner/teams/TeamsDomShapeSurvey` (**nouveau**) — pilote gardé `DEBUG` : navigue (Conversations),
  relève la liste, ouvre un fil, relève le fil, **remet la vue**, émet les événements F-132 ; ne lève
  jamais. Bâti sur `PageActions` (navigation + `readScript` existants) et `TeamsRoutes`.
- `runner/teams/TeamsTools#findConversations` — **une** couture : quand `RunnerDiag.level()==DEBUG`,
  lance `TeamsDomShapeSurvey` **une fois** (throttle) après la lecture d'écran existante. `try/catch`
  englobant : le relevé ne casse jamais l'outil.
- `runner/diag/RunnerDiag` — **inchangé** (émission via `info(...)` existant).
- `runner/teams/CdpCommands` — **inchangé** (le relevé n'utilise que `Runtime.evaluate` /
  `Page.navigate`, déjà en liste blanche).

### Préoccupations transversales

| Préoccupation | Impacté ? | Composants |
|--------------|-----------|-----------|
| **Auth / Principal** | **Non** — runner local, aucune session gateway, aucun endpoint, aucun JWT. | — |
| **Contexte tenant / `user_id`** | **Non** — le runner observe le navigateur du poste ; rien ne quitte la machine que la **forme** du DOM ; l'isolation `user_id`+`host_id` du Journal est **déjà** portée côté gateway par SF-132-02 (identité de session). Aucun nouveau point d'accès aux données. | — |
| **Plans / limites** | **Non**. | — |
| **Navigation / routing** | **Non (frontend)** — aucune route Angular. Côté runner, la navigation dans la page Teams réutilise **la même** discipline que `TeamsScreenFallback.list` (report d'hôte `onTabHost`, gardes F-108, remise de la vue) ; composants runner vérifiés : `TeamsScreenFallback` (patron), `PageActions` (gardes), `TeamsRoutes` (routes). | `TeamsScreenFallback`, `PageActions`, `TeamsRoutes` |

---

## Plan de test

### Tests unitaires (module `runner`, JUnit 5)

- [ ] `TeamsDomShapeTest` — **cas nominal** : un DOM modèle (racine → conteneur → 12 frères
  `chat-list-item` → sous-champs) produit une squelette avec `tag`/`data-tid`/`role`/profondeur, un repli
  des frères (`n=12`, descente dans le premier seulement).
- [ ] `TeamsDomShapeTest` — **VIE PRIVÉE (CA4/CA5)** : un DOM modèle portant « bonjour Paul », « Jean
  Dupont », `jean.dupont@client.fr`, `19:secret@thread.v2` en **nœud texte, `aria-label`, `title`, `href`,
  `id`** → la sortie (`line` + `fields` de chaque nœud) ne contient **aucune** de ces chaînes ; le texte
  devient un entier ; les clés inconnues sont écartées.
- [ ] `TeamsDomShapeTest` — **assainissement/bornes (CA6/CA7)** : `data-tid`/classe id-like → `{id}` ;
  profondeur 13 non dépliée ; > 160 nœuds → `truncated=true` ; nom d'attribut avec `=`/espace rejeté.
- [ ] `TeamsDomShapeTest` — **garde script** : le script généré ne renvoie jamais `textContent`/`innerText`
  comme valeur (seulement `.length`), et ne lit ni cookie, ni stockage (mêmes mots interdits que `TeamsScreen`).
- [ ] `TeamsDomShapeSurveyTest` — **inertie (CA1)** : niveau `INFO` → `run` n'émet rien et n'envoie aucune
  commande CDP (navigate/evaluate).
- [ ] `TeamsDomShapeSurveyTest` — **relevé (CA2/CA3/CA9)** : niveau `DEBUG`, navigateur de papier
  (`CdpConnection` de test) rendant un DOM modèle → événements `conversations_list` **et** `thread`
  émis (`RunnerDiag.drain`), en-têtes portant compteurs/`opened`, vue remise, aucune exception propagée.
- [ ] `TeamsDomShapeSurveyTest` — **vie privée bout-en-bout** : DOM modèle sensible → aucun événement
  drainé ne contient de valeur/nom/message.
- [ ] Suite runner complète verte (`cd runner && ./mvnw -q test`), dont `PageActionsTest`,
  `TeamsScreenFallbackTest`, `TeamsScreenRadarTest`, `RunnerDiag*Test`.

### Tests d'intégration

- N/A (module runner : pas de contexte Spring ; couvert par tests unitaires + non-régression). La chaîne
  `runner_diag` → base → endpoint est **déjà** couverte par SF-132-02 (contrat de trame inchangé).

### Isolation utilisateur / workspace

- [ ] **Non applicable côté runner** — mono-poste, aucune donnée multi-tenant touchée. La confidentialité
  est couverte par CA4/CA5 (aucune valeur, aucun nom, aucun id, aucun texte). L'isolation `user_id`+`host_id`
  du Journal est garantie par SF-132-02 (inchangée).

### Non-régression

- [ ] `findConversations`, capture réunion, collecte Radar, boucle Vigie, `TeamsAdapterV1`, heartbeat,
  F-132 inchangés à niveau `INFO` (suite runner complète verte).

---

## Dépendances

### Subfeatures bloquantes (Done)

- `F-132 / SF-132-01` (émission `RunnerDiag` / trame `runner_diag`) — **Done**.
- `F-132 / SF-132-02` (`runner_diag_events`, `GET …/diag`) — **Done**.
- `F-132 / SF-132-03` + `SF-132-05` (panneau + bouton « Activer DEBUG ») — **Done** (le déclenchement admin).
- `F-89 / SF-89-06` (`TeamsScreen`/`TeamsScreenReader`/`TeamsScreenFallback`), `F-108` (`PageActions`,
  gardes de geste), `F-88` (routes) — **Done**.

### Débloque

- Le **recalage de `TeamsScreen` v2** (conteneurs/éléments de la liste et du fil) — SF ultérieure, après
  un relevé réel lancé par le PO sur CAGIP.

### Questions ouvertes impactées

Aucune de `docs/OPEN_QUESTIONS.md`.

---

## Notes et décisions

- **D1 — Relevé de forme du DOM, distinct du relevé réseau.** Conversations/messages viennent de l'écran
  (cache Chrome), pas du réseau : `PayloadShape` (SF-89-12/14) ne s'applique pas ; il faut la forme du
  **DOM**. Nouvelle classe `TeamsDomShape`, même *esprit* (forme only, bornée, testée sur la vie privée).
- **D2 — Déclenchement = flag debug F-132, sans nouveau bouton.** Le cadrage offrait « bouton admin OU
  flag debug — le plus simple ». Le flag debug (niveau `DEBUG`, déjà réglable par un bouton admin livré)
  est **le plus simple** : **zéro** changement backend/frontend/base, sortie déjà lisible en base par le
  panneau F-132 et par l'assistant. Un bouton dédié « Relevé de forme » aurait touché 3 couches
  (frontend + endpoint + outil runner) pour le même résultat.
- **D3 — Racine large, indépendante des sélecteurs cassés.** Le relevé lit la forme d'une racine générale
  (`main`/`#app`), pas des sélecteurs `TeamsScreen` (justement suspects) : c'est la seule façon de révéler
  la forme *réelle* de la v2.
- **D4 — Sortie par événement/nœud** (`msg` lisible + `fields` scalaires), reconstructible en base par
  `seq`+`depth` : respecte le contrat scalaire de F-132 sans nouveau stockage.
- **D5 — Double, puis triple garde de vie privée** : élision dans le script, re-filtre `TeamsDomShape`,
  puis `RunnerDiagRedaction`. Le test central (CA4) porte sur le re-filtre Java, seul point exécutable
  sans navigateur (le DOM v2 n'existe pas en CI ; le PO lancera le relevé réel).
- **D6 — À VALIDER SUR POSTE RÉEL** : la logique d'expurgation et l'assemblage sont testés sur DOM modèle ;
  la forme v2 réelle sera relevée par le PO (le DOM v2 n'existe pas en CI).
