# Mini-spec — [F-89 / SF-89-20] Recaler l'adaptateur Teams v2 : lecture de la liste des conversations ET d'un fil (avec les vrais noms)

## Identifiant

`F-89 / SF-89-20`

## Feature parente

`F-89` — Le terminal Teams (peau, droit, adaptateur écran/réseau, relevés de forme)

## Statut

`in-progress`

## Date de création

2026-09-20

## Branche Git

`feat/SF-89-20-recaler-adaptateur-teams-v2`

---

## Objectif

> En une phrase : recaler les sélecteurs de **lecture d'écran** (`TeamsScreen`/`TeamsScreenReader`, vues `CONVERSATIONS` et `MESSAGES`) sur les **ancres réelles v2** relevées en prod (SF-89-16→19), en gardant les anciens sélecteurs en repli, pour que la liste des conversations remonte avec **leurs noms** et un fil remonte avec **l'auteur (« qui parle »)**, l'horodatage, le texte et le drapeau moi/autre.

---

## Contexte

Le repli lecture-écran (SF-89-06) lit **0 conversation** sur Teams v2 : ses sélecteurs `TeamsScreen`
ciblent une structure antérieure (`chat-list` / `message-pane-list-viewport`). Les relevés de forme
SF-89-16→19 (prod CAGIP, 2026-09-20) ont donné les ancres **réelles** v2. Ce lot **recale les
sélecteurs de lecture uniquement** ; il ne touche PAS au mécanisme de navigation/collecte
(`TeamsScreenReader.collect`, `TeamsScreenFallback.list/thread`, gestes F-108) qui fonctionne.

### Ancres v2 confirmées (à utiliser — data-tid/role stables, JAMAIS les classes hashées `fui-*`/`___*`)

**Liste des conversations (rail gauche)**
- Zone : `[data-tid="app-layout-area--mid-nav"]`.
- Conteneur liste : `[data-tid="simple-collab-dnd-rail"]` avec `role="tree"` (classe `fui-Tree`, ignorée).
- Chaque conversation = `[role="treeitem"]` (classe `fui-TreeItem`, ignorée). Peut être imbriqué (`role="group"`).
- Nom de la conversation = **nom accessible de l'item** : `aria-label` de l'item, sinon texte du layout (`fui-TreeItemLayout`), sinon texte de l'item.
- Avatar `[data-tid="PersonaAvatar"]`, présence `[data-tid="presence-badge"]` (bruit, non lu).

**Fil ouvert (messages d'une conversation)**
- Liste des messages : `[data-tid="message-pane-list-runway"]` (classe `fui-Chat`).
- Chaque message = `[data-tid="chat-pane-item"]` (classe `fui-unstable-ChatItem`).
- Auteur (le vrai nom, LA clé « qui parle ») = `[data-tid="message-author-name"]`.
- Horodatage = balise `time` (`datetime` machine + `aria-label` humain) → on lit `datetime` (ISO).
- **Moi vs autres** : mes messages portent une classe contenant `ChatMyMessage`
  (`fui-ChatMyMessage`, `fui-ChatMyMessage__author`, `__timestamp`) ; les autres n'ont pas `My`.
  → « moi » si un élément de l'item porte une classe `*ChatMyMessage*`.
- Texte = contenu de `chat-pane-item` hors auteur/horodatage
  (sélecteurs data-tid existants `chat-pane-message`/`content-*`/`message-body-content` conservés).

---

## Comportement attendu

### Cas nominal

**Liste** : la vue `CONVERSATIONS` essaie d'abord le conteneur v2 (`simple-collab-dnd-rail` + `role=tree`)
et les items `role=treeitem` ; pour chaque item elle extrait le nom accessible (aria-label → texte layout →
texte item) et l'identifiant (`data-item-id`/`id`), et alimente `TeamsConversation` (contrat F-101/F-87
inchangé). Si le conteneur v2 est absent, elle retombe sur l'ancien (`chat-list` / `chat-list-item` /
`chat-list-item-title`).

**Fil** : la vue `MESSAGES` essaie d'abord le conteneur v2 (`message-pane-list-runway`), items
`chat-pane-item` ; pour chaque message elle extrait auteur (`message-author-name`), horodatage
(`time[datetime]`), texte et le **drapeau moi/autre** (présence d'une classe `*ChatMyMessage*`), et
alimente `TeamsMessage` via `TeamsParticipant.self` (contrat inchangé). Repli ancien conteneur
(`message-pane-list-viewport` / `chat-pane-list`) conservé.

`TeamsScreen.VERSION` est bumpée pour tracer le recalage v2.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|----------------------|
| Conteneur v2 ET ancien absents | `found=false` → manque « Teams a changé d'écran » (`SCREEN_CHANGED`), 0 élément, jamais un résultat inventé (comportement SF-89-06 inchangé) |
| Item sans nom accessible (liste) | conversation écartée (title vide → non rendue), comportement `conversations()` inchangé |
| Message sans auteur ou sans heure | message non rendu, compté en manque `MISSING_FIELD` (inchangé) |
| Classe `ChatMyMessage` absente | drapeau moi = faux (message d'un autre) |
| Champ de saisie / brouillon dans l'item | jamais lu (garde `BANNED` + refiltrage Java inchangés) |

---

## Critères d'acceptation

- [ ] DOM modèle **liste v2** (`simple-collab-dnd-rail` `role=tree` + `role=treeitem` nommés, dont un groupe imbriqué) → conversations découvertes **avec leurs noms**.
- [ ] DOM modèle **fil v2** (`message-pane-list-runway` + `chat-pane-item` + `message-author-name` + `time[datetime]` + `ChatMyMessage`) → messages extraits avec **auteur (vrai nom)**, horodatage, texte et **drapeau moi/autre** correct (moi=true sur message `ChatMyMessage`, false sinon).
- [ ] Priorité **data-tid/role** ; les classes hashées `fui-*`/`___*` ne servent JAMAIS de sélecteur, sauf le marqueur sémantique `ChatMyMessage` (accepté, fragilité documentée).
- [ ] Repli ancien sélecteur **conservé** : les DOM modèles anciens (`chat-list` / `message-pane-list-viewport`) continuent d'être lus (non-régression SF-89-06).
- [ ] `TeamsScreen.VERSION` bumpée (trace du recalage v2).
- [ ] Aucun script d'écran ne touche cookies/stockage/caches/valeur de champ (garde SF-89-06 inchangée).
- [ ] Isolation : la lecture écran reste locale au navigateur relié de l'utilisateur (aucune donnée d'un autre `user_id` ; garde de domaine/identification F-108 inchangée).

---

## Périmètre

### Hors scope (explicite)

- Le mécanisme de navigation/collecte/défilement/recollage (`collect`, `list`, `thread`, gestes F-108) — inchangé.
- La capture réunion, la collecte Radar, la boucle Vigie, `TeamsAdapterV1` (réseau), le heartbeat, F-132.
- Les relevés de forme SF-89-16→19 (sources du recalage) — non modifiés.
- Les vues `ACTIVITY` et `TRANSCRIPT` — hors de ce lot (le lot vise liste + fil).
- Toute nouvelle capacité produit ou nouveau format de contrat (F-101/F-87 réutilisé tel quel).

---

## Technique

### Endpoint(s)

Aucun endpoint backend. Code **runner** (client déposé sur le poste) uniquement.
Outils runner concernés (déjà existants, non modifiés dans leur signature) : `find_conversations`,
`read_conversation` (repli écran).

### Tables impactées

Aucune. Pas de migration Liquibase (`Non applicable`).

### Fichiers impactés (runner)

| Fichier | Opération | Notes |
|---------|-----------|-------|
| `runner/.../teams/TeamsScreen.java` | MODIF | vues `CONVERSATIONS`/`MESSAGES` recalées v2 + repli ; nouveau champ `presence` (drapeau) et `nameMode` (nom accessible) ; script générique ; `spec()` ; `VERSION` |
| `runner/.../teams/TeamsScreenFallback.java` | MODIF | `thread()` : drapeau `self` (moi/autre) porté dans `TeamsParticipant` |
| `runner/.../teams/PaperScreen.java` (test) | MODIF | miroir jsoup des nouveaux modes `presence`/`nameMode` |
| `runner/.../resources/teams/ecran/conversations-v2.html` | AJOUT | DOM modèle liste v2 |
| `runner/.../resources/teams/ecran/fil-ecran-v2-1.html` | AJOUT | DOM modèle fil v2 |
| `runner/.../teams/TeamsScreenV2Test.java` | AJOUT | tests v2 (liste + fil + drapeau) |

### Composants Angular

Aucun (code runner ; aucune UI produit modifiée).

---

## Préoccupations transversales

| Préoccupation | Concernée ? | Composants impactés / vérification |
|--------------|-------------|-------------------------------------|
| Auth / Principal | Non | Aucun changement d'auth ; la garde F-108 (domaine + identification du navigateur relié) est inchangée. |
| Contexte tenant | Non | Aucun nouveau moyen de résoudre le tenant. La lecture écran reste locale au navigateur de l'utilisateur relié ; aucun accès cross-`user_id`. |
| Plans / limites | Non | Aucun gate touché. |
| Navigation / routing | Non | Le mécanisme de navigation (`list`/`thread`/gestes) est explicitement hors scope et inchangé ; seuls les **sélecteurs de lecture** bougent. |

---

## Plan de test

### Tests unitaires (runner, DOM modèles jsoup)

- [ ] `TeamsScreenV2Test` — liste v2 : `simple-collab-dnd-rail` `role=tree` + `treeitem` nommés (dont groupe imbriqué) → N conversations avec noms (aria-label et texte layout).
- [ ] `TeamsScreenV2Test` — fil v2 : `message-pane-list-runway` + `chat-pane-item` → messages avec auteur, horodatage (ISO), texte.
- [ ] `TeamsScreenV2Test` — drapeau moi/autre : message `fui-ChatMyMessage` → `self=true` ; message sans `My` → `self=false`.
- [ ] `TeamsScreenV2Test` — nom accessible : item avec `aria-label` → nom lu ; item sans aria-label mais avec texte layout → nom lu.
- [ ] `TeamsScreenV2Test` — garde : un brouillon/champ de saisie dans un item v2 n'est jamais lu.

### Tests d'intégration / non-régression

- [ ] `TeamsScreenFallbackTest` (existant, DOM anciens) reste **vert** → repli ancien sélecteur conservé.
- [ ] `screen_scripts_never_touch...` reste **vert** (aucun accès cookies/stockage/valeur).
- [ ] Suite runner complète verte (relevés SF-89-16→19, capture, Radar, Vigie, F-132, `TeamsAdapterV1`).

### Isolation workspace / tenant

- [ ] Applicable au sens tenant : la lecture reste locale au navigateur relié de l'utilisateur ; aucun accès aux données d'un autre `user_id`. Vérifié : aucun changement de la garde de domaine/identification F-108.

---

## Dépendances

### Subfeatures bloquantes

- `SF-89-06` — done (mécanisme de lecture écran).
- `SF-89-16` → `SF-89-19` — done (relevés de forme, sources des ancres v2).

### Questions ouvertes impactées

- Aucune de `docs/OPEN_QUESTIONS.md`.

---

## Notes et décisions

- **Priorité data-tid/role.** Les classes `fui-*` / `___*` sont ignorées comme sélecteurs.
  **Exception documentée** : le marqueur `ChatMyMessage` est **sémantique** (il distingue « moi »),
  pas purement décoratif ; il est accepté comme unique moyen connu de lire le drapeau moi/autre, mais
  reste **fragile** (renommage Fluent UI possible) — d'où le repli implicite : drapeau absent = « autre ».
- **Drapeau moi/autre** : porté par `TeamsParticipant.self` (déjà surfacé en JSON via `TeamsViews.message`
  → champ `self` et `authorId` `ecran:<nom>`). Nouveau champ écran `self` (mode `presence`) → `self=true`.
- **Nom de conversation** : nouveau mode `nameMode` (nom accessible) — `aria-label` de l'item, puis texte
  d'un sous-élément layout, puis texte de l'item. Couvre v2 (aria-label/treeitem) et l'ancien
  (`chat-list-item-title`) dans un seul champ, sans changer les autres champs.
- **Repli** : les sélecteurs v2 sont placés en tête des listes de conteneurs/items ; l'ancien reste en
  queue (premier trouvé l'emporte, comportement `first()` existant).
- **Mise à jour runner requise** : le recalage est du code runner ; le PO doit **mettre à jour le runner**
  sur le poste pour en bénéficier (le bump `VERSION` trace le recalage dans chaque résultat/manque).
- **À VALIDER SUR POSTE RÉEL** : le DOM v2 est absent en CI. Les tests éprouvent la logique de
  sélection/extraction sur des DOM modèles v2 reproduisant les ancres ; le PO validera en réel.
