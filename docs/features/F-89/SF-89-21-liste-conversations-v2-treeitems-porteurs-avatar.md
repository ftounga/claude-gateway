# Mini-spec — [F-89 / SF-89-21] Liste des conversations v2 : ne retenir que les treeitems porteurs d'un avatar/présence

---

## Identifiant

`F-89 / SF-89-21`

## Feature parente

`F-89` — Terminal Teams (adaptateur Teams pour le runner)

## Statut

`ready`

## Date de création

2026-09-20

## Branche Git

`feat/SF-89-21-liste-conversations-v2-avatar`

---

## Objectif

> En une phrase : que fait cette subfeature ?

Sur la vue liste des conversations v2, ne retenir un `[role=treeitem]` comme conversation que s'il porte (dans son sous-arbre) un `[data-tid=PersonaAvatar]` ou un `[data-tid=presence-badge]`, afin d'exclure les items de navigation (« Mentions », « Activité », filtres…) qui n'en portent pas.

---

## Comportement attendu

### Cas nominal

Le recalage SF-89-20 lit le rail `[data-tid=simple-collab-dnd-rail][role=tree]` et ses `[role=treeitem]`. Problème confirmé en prod CAGIP 2026-09-20 : **tous** les treeitems sont remontés, y compris ceux de navigation, si bien que `teams_find_conversations` renvoyait la structure de l'écran plutôt que les chats.

Relevé SF-89-19 : les **vraies conversations** portent `[data-tid=PersonaAvatar]` et `[data-tid=presence-badge]` ; les items de nav n'en portent pas. C'est le filtre discriminant.

Flux recalé (vue `CONVERSATIONS` uniquement) :
1. Le script d'écran sélectionne les éléments via la liste des sélecteurs d'item (premier non vide l'emporte), comme aujourd'hui.
2. **Nouveau** : quand le sélecteur gagnant est `[role=treeitem]`, chaque élément n'est conservé que s'il (ou un descendant) correspond à `[data-tid=PersonaAvatar]` **OU** `[data-tid=presence-badge]`. Sinon il est ignoré (item de nav/filtre). Le filtrage a lieu **avant** le plafond `MAX_ITEMS` pour ne pas gaspiller la fenêtre sur des items de nav.
3. Le **nom** de la conversation est lu comme en SF-89-20 : nom accessible (`aria-label` → texte d'un sous-élément → texte de l'item).
4. Les autres champs (id, time) sont inchangés.

Le filtre est **porté par une table** (`View.requireAny`, clé = sélecteur d'item, valeur = marqueurs) : il ne s'applique qu'au sélecteur `[role=treeitem]`. Le sélecteur de repli `[data-tid=chat-list-item]` n'y figure pas → **repli ancien inchangé**.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Aucun treeitem ne porte d'avatar/présence (rail = pure nav) | 0 conversation remontée (au lieu d'items de nav faussement pris pour des chats) |
| Rail v2 absent, structure ancienne présente (`chat-list`/`chat-list-item`) | Repli ancien : les items de repli ne sont **pas** filtrés → comportement SF-89-06 inchangé |
| Conteneur de la vue introuvable | Manque « structure attendue introuvable » (inchangé, SF-89-06/20) |
| Un treeitem porte un avatar mais aucun nom lisible | Item conservé (c'est une conversation) mais sans `topic` — inchangé côté extraction |

---

## Critères d'acceptation

- [ ] Arbre mixte v2 (treeitems de nav SANS avatar + treeitems de chat AVEC `PersonaAvatar`/`presence-badge`) → **seuls les chats** remontent, avec leur nom ; les items de nav sont exclus.
- [ ] Un treeitem est retenu s'il porte `PersonaAvatar` **OU** `presence-badge` (l'un suffit).
- [ ] Le nom de conversation reste lu via le nom accessible (aria-label → texte), comme SF-89-20.
- [ ] Repli ancien (`conversations.html`, `chat-list-item`) : les 2 conversations remontent toujours (filtre non appliqué au sélecteur de repli).
- [ ] Lecture de fil SF-89-20 (`runway`/`chat-pane-item`/`message-author-name`/`time`/`ChatMyMessage`) **inchangée** : aucun filtre avatar/présence sur la vue MESSAGES.
- [ ] Aucun champ de saisie n'est lu (garde F-108 conservée : secret de recherche jamais présent dans la sortie).
- [ ] `TeamsScreen.VERSION` est bumpée pour tracer ce raffinement.

---

## Périmètre

### Hors scope (explicite)

- Toute modification de la lecture de fil (SF-89-20), de la capture réunion, de la collecte Radar, de la boucle Vigie, de `TeamsAdapterV1`, du heartbeat, de F-132.
- Toute modification des vues MESSAGES / ACTIVITY / TRANSCRIPT (pas de `requireAny`).
- Détection d'un « type » de conversation (1:1 vs groupe), tri, ou toute autre sémantique de liste.
- Confirmation sur poste réel du DOM v2 (les DOM modèles éprouvent la logique de filtre, pas que Teams sert exactement ce DOM).

---

## Contraintes de validation

| Champ | Obligatoire | Format / Valeurs autorisées | Normalisation |
|-------|-------------|----------------------------|---------------|
| marqueur retenu | Oui | `[data-tid=PersonaAvatar]` OU `[data-tid=presence-badge]` (sélecteurs data-tid, priorité data-tid/role) | — |
| nom (topic) | Non | nom accessible : aria-label → texte sous-élément → texte item (SF-89-20, inchangé) | trim, bornage `MAX_VALUE_CHARS` |

Notes :
- La détection de présence du marqueur réutilise exactement le patron du champ `presence` (item ou descendant), déjà éprouvé et gardé.
- Priorité aux sélecteurs `data-tid`/`role`, jamais aux classes Fluent (`fui-*`/`___*`).

---

## Technique

### Endpoint(s)

Aucun. Runner (client local) uniquement — outil `teams_find_conversations`.

### Tables impactées

Aucune. Pas de base de données.

### Migration Liquibase

- [x] Non applicable

### Composants impactés (runner)

- `runner/.../teams/TeamsScreen.java` — `record View` : ajout d'un champ `requireAny` (Map sélecteur→marqueurs) ; vue `CONVERSATIONS` en porte un ; `spec()` le sérialise ; `readScript` filtre les items du sélecteur gagnant.
- `runner/.../teams/PaperScreen.java` (double de test) — réplique le filtre pour rester fidèle au script.
- Bump `TeamsScreen.VERSION`.

### Préoccupation transversale — Navigation / routing

Non applicable au sens produit (pas de route Angular). Le mot « navigation » ici = items de nav du DOM Teams, précisément ce que la SF exclut. Composants runner impactés listés ci-dessus. Aucun autre appelant de la table de sélecteurs (seule la vue `CONVERSATIONS` reçoit un `requireAny`) : MESSAGES/ACTIVITY/TRANSCRIPT gardent une map vide → comportement identique.

---

## Plan de test

### Tests unitaires (runner, JUnit + PaperScreen)

- [ ] `TeamsScreenV2Test` — arbre mixte (nav sans avatar + chats avec `PersonaAvatar`/`presence-badge`) → seuls les chats remontent, avec leur nom ; nav exclue.
- [ ] `TeamsScreenV2Test` — un chat porteur du seul `presence-badge` (sans `PersonaAvatar`) est retenu ; un chat porteur du seul `PersonaAvatar` est retenu.
- [ ] `TeamsScreenV2Test` — test existant (2 conversations avatar+présence) reste vert.
- [ ] `TeamsScreenV2Test` — fil v2 inchangé (aucune régression MESSAGES).
- [ ] `TeamsScreenFallbackTest` — repli `conversations.html` : 2 conversations toujours remontées (filtre non appliqué au repli).
- [ ] Secret de recherche jamais présent dans la sortie (garde conservée).

### Tests d'intégration

- [ ] `teams_find_conversations` sur DOM mixte v2 → source `ecran`, seulement les chats.

### Isolation workspace

- [x] Non applicable — runner local, pas de multi-tenant côté serveur ici.

---

## Dépendances

### Subfeatures bloquantes

- `SF-89-20` — statut : done (recalage v2 lecture liste et fil).

### Questions ouvertes impactées

- Aucune (OQ non concernées).

---

## Notes et décisions

- **Filtre table-driven, gated par sélecteur** : `requireAny` est une `Map<sélecteur d'item, List<marqueurs>>`. Il ne s'applique qu'aux items sélectionnés via un sélecteur présent dans la map (ici `[role=treeitem]`). Ce choix garantit que le repli ancien (`chat-list-item`, absent de la map) n'est jamais filtré — condition explicite du cadrage.
- **Filtrage avant `MAX_ITEMS`** : on filtre les items de nav avant de tronquer, sinon un rail chargé de nav pourrait consommer la fenêtre de 200 avant les chats.
- **Runner** : changement de code runner → le runner déployé doit être reconstruit/redéployé par le PO (non déployé ici). `TeamsScreen.VERSION` sert de trace.
