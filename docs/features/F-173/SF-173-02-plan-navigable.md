# Mini-spec — [F-173 / SF-173-02] Le plan qui se navigue

## Identifiant

`F-173 / SF-173-02`

## Feature parente

`F-173` — La carte vivante (cadrage `CADRAGE-F-173-la-carte-vivante.md`, D1→D7 validées le 2026-10-04)

## Statut

`done`

## Date de création

2026-10-04

## Branche Git

`feat/SF-173-02-plan-navigable`

---

## Objectif

Dans l'onglet **Carte** d'un poste de la Forge, dessiner la carte comme un **plan navigable** (cytoscape.js) : on voit les grandes ressources du client, on zoome et on se déplace, un clic descend d'un niveau, un fil d'Ariane remonte, et l'URL garde l'endroit.

---

## Comportement attendu

### Cas nominal

1. L'onglet Carte s'ouvre sur une **bascule segmentée** *Plan · Liste · Fichiers* (D5). **Plan** est la vue par défaut ; **Fichiers** est la vue actuelle (les 6 fichiers, le gain, la mesure F-140), inchangée ; **Liste** est l'équivalent hiérarchique accessible du plan (D6, critère transverse d'accessibilité). La vue choisie est dans l'URL : `?onglet=carte&vue-carte=fichiers|liste` (Plan n'encombre pas l'adresse).
2. Le plan est lu par `GET /api/governance/hosts/{hostRef}/map/graph` (contrat **importé de SF-173-01**), **jamais sur le poste** (D1) : il s'affiche **aussi poste hors ligne**, daté (« carte indexée le … »).
3. **Niveaux (D4)** :
   - **Niveau 1 — le client** : les ressources sans parent. Au-delà de **24** racines, elles sont **regroupées par type** en nœuds de groupe (« Comptes AWS · 42 ») qu'un clic ouvre.
   - **Niveau 2 — une plateforme** : un clic sur une ressource qui en contient d'autres l'ouvre : elle devient le cadre (nœud composé) et ses enfants s'affichent dedans ; au-delà de 24 enfants, même regroupement par type.
   - **Niveau 3 — la fiche** : un clic sur une ressource sans enfant la **sélectionne** (contour orange, geste) ; le panneau de fiche arrive en SF-173-03.
4. **Fil d'Ariane** : *nom du poste › ancêtres › niveau courant* ; chaque maillon ramène à son niveau. **Retour arrière** du navigateur = niveau précédent : l'état est dans l'URL (`?onglet=carte&noeud=<id>`), chaque descente est une entrée d'historique.
5. **Zoom / déplacement** natifs (molette, glisser, pincer) ; bouton *Recadrer* ; liens dessinés entre les ressources du niveau, leur nature écrite sur le lien.
6. **Lecture** : la forme dit le type (plateformes en rectangle arrondi, accès/proxy/hôtes en losange, équipes en ellipse, identifiants en étiquette) **et** le nom est toujours écrit ; la couleur ne porte jamais seule l'information (D3, `DESIGN_SYSTEM` §9). Palette : nœuds blancs à filet navy, nœud qui contient d'autres ressources en navy plein à texte blanc, pièges en filet rouge `--cg-error` avec compteur, faits périmés en gris `--cg-text-secondary` pâli, sélection en orange `--cg-accent` (geste). Polices Inter / JetBrains Mono.
7. **Téléphone (D6)** : sous 768 px, *Plan* affiche la **Liste** (mêmes niveaux, même URL, même fil d'Ariane) — aucun canevas.
8. cytoscape.js est **chargé paresseusement** (import dynamique) à la première ouverture du plan : il ne pèse rien sur les autres écrans.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Plan illisible (réseau / 5xx) | Message « Le plan n'a pas pu être lu. » + bouton *Réessayer* ; la vue Fichiers reste disponible |
| Carte pas encore indexée (`indexed=false`) | « La carte n'est pas encore indexée » (+ « n sections en cours de lecture » si `pendingSections>0`) et lien vers la vue Fichiers |
| `?noeud=` inconnu (ressource disparue, lien ancien) | Retour au niveau 1, sans erreur |
| Chargement de cytoscape impossible | Repli automatique sur la Liste, avec une phrase |
| Plan tronqué (`truncated`) | « 2 000 ressources affichées sur N » |
| Poste « Hébergé » | Pas d'onglet Carte (inchangé, `tabsFor`) |

---

## Critères d'acceptation

- [x] Plan par défaut ; bascule Plan · Liste · Fichiers ; Fichiers = l'écran d'avant, inchangé.
- [x] Niveau 1 = racines (regroupées par type au-delà de 24) ; un clic sur un parent ou un groupe descend ; fil d'Ariane remonte.
- [x] `?noeud=` et `?vue-carte=` dans l'URL, retour arrière = niveau précédent ; les autres onglets et `/forge/:hostRef` inchangés.
- [x] Plan affiché poste hors ligne (lu en base).
- [x] < 768 px : liste hiérarchique, aucun canevas.
- [x] cytoscape.js importé dynamiquement (chunk séparé, budget initial inchangé).
- [x] Aucune couleur ni police hors `DESIGN_SYSTEM.md`.

---

## Contraintes de validation

| Élément | Règle |
|---------|-------|
| Nœuds affichés par niveau avant regroupement | ≤ 24 ; au-delà, groupes par type |
| Groupe | id `groupe:<type>` ou `groupe:<type>@<parentId>`, libellé « <Type au pluriel> · n » |
| Mise en page | `cose` jusqu'à 150 nœuds affichés, `grid` au-delà (fluidité ≤ 2 000 nœuds) |
| Point de rupture mobile | 768 px |

---

## Périmètre

### Hors scope (explicite)

- Le panneau de fiche (SF-173-03), la grille (04), le chemin (05), l'échéancier et la liste « à cartographier » (06), « Demander à la Forge » (07).
- Toute modification de la lecture de la vue Fichiers (relecture du poste conservée telle quelle, D5).
- Toute écriture.

---

## Technique

### Contrat API

Importé de SF-173-01 (`GET /api/governance/hosts/{hostRef}/map/graph`), sans modification.

### Composants frontend

- `core/models/governance.models.ts` : `MapGraph`, `MapNode`, `MapEdge`, `MapDeadline`, `MapToMap`.
- `core/services/governance.service.ts` : `hostMapGraph(hostRef)`.
- `postes/forge-map/forge-map-levels.ts` : fonctions pures (niveau courant, regroupement, fil d'Ariane, type → forme / libellé).
- `postes/forge-map/forge-map-canvas.component.ts` : enveloppe cytoscape (jeton `CYTOSCAPE_LOADER` pour les tests).
- `postes/forge-map/forge-map.component.ts|html|scss` : bascule, fil d'Ariane, plan / liste, états.
- `postes/postes.component.html|ts` : l'onglet Carte héberge `app-forge-map` ; la vue Fichiers reprend le contenu existant.
- `package.json` : `cytoscape@3.34.3` (MIT, types fournis).

### Préoccupations transversales

| Préoccupation | Cochée | Composants |
|---|---|---|
| Navigation / routing | Oui | `PostesComponent.selectTab` (onglet `?onglet=`, inchangé ; `queryParamsHandling: 'merge'` garde `noeud`/`vue-carte` hors onglet Carte sans effet), `PostesComponent.openHost`/rail (`queryParamsHandling: 'preserve'` — un `noeud` d'un autre poste retombe au niveau 1, cas « noeud inconnu »), `forge-tabs.effectiveTab` (inchangé), `app.routes.ts` `/forge/:hostRef` (inchangé). Nouveaux paramètres lus uniquement par `ForgeMapComponent`. |
| Auth, tenant, plans | Non | — (isolation portée par la gateway, SF-173-01) |

---

## Plan de test

- [x] `forge-map-levels.spec.ts` : racines, regroupement > 24, descente dans un parent, groupe, fil d'Ariane, nœud inconnu → niveau 1, formes par type.
- [x] `forge-map.component.spec.ts` (service et chargeur cytoscape simulés) : Plan par défaut, bascule et URL, clic → `noeud` dans l'URL, fil d'Ariane, non indexé, erreur + réessayer, mobile → liste, échec du chargeur → liste.
- [x] `governance.service.spec.ts` : URL du plan.
- [x] `postes.component.spec.ts` : l'onglet Carte héberge le plan ; la vue Fichiers montre toujours les fichiers.
- [x] `npm run build && npm test` verts.

## Dépendances

SF-173-01 (contrat figé ; mergée avant ce front).

## Notes et décisions

- **Arbitrage (réversible)** : une 3ᵉ vue **Liste** en plus de Plan / Fichiers : c'est l'équivalent accessible exigé par le cadrage (§6) et le repli mobile D6 — un même composant sert les deux.
- **Arbitrage (réversible)** : regroupement par type au-delà de 24 nœuds par niveau — lisible au premier coup d'œil, et c'est ce qui tient 2 000 nœuds sans dessiner 2 000 nœuds.
- **Arbitrage (réversible)** : la sélection d'une ressource est en orange — c'est un geste (D3 réserve l'accent aux gestes).
