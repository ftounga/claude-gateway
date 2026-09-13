# Mini-spec — F-98 / SF-98-03 — Les projets en grille

## Identifiant

`F-98 / SF-98-03`

## Feature parente

`F-98` — La Forge, refondue (cadrage : `CADRAGE-F-98-la-forge-refondue.md` §3 D2, D4 et « Les règles
de détail » ; maquette validée `maquette-forge-refondue.html`)

## Statut

`done`

## Date de création

2026-09-13

## Branche Git

`feat/SF-98-03-projets-en-grille`

---

## Objectif

Ranger les projets du poste ouvert en **grille de tuiles** (`auto-fill`, min 232 px) dont chacune ne
montre qu'**un seul contenu central** par ordre de priorité, avec un **tri** et une **tuile fantôme**
pour les dossiers non ouverts, pour que vingt projets tiennent sur un écran de portable.

---

## Comportement attendu

### Cas nominal

1. **Barre d'outils de l'onglet Projets** : sélecteur segmenté **Actifs d'abord** (défaut) · **A → Z**
   · **Récents**, puis « + Ajouter un projet » (machine seulement).
   - *Actifs d'abord* : autorisation en attente › actif ou terminal vivant › le reste ; à égalité,
     activité la plus récente, puis nom.
   - *A → Z* : nom, ordre alphabétique français, sans casse.
   - *Récents* : activité la plus récente d'abord, projets sans activité à la fin, puis nom.
2. **Grille** : `repeat(auto-fill, minmax(232px, 1fr))`, écart 12 px.
3. **Tuile de projet** (`app-forge-project-tile`) :
   - en-tête : **nom** (tronqué), à droite la pastille de vie (§11) si un terminal vit ; dessous le
     **chemin** en JetBrains Mono (omis pour « Hébergé ») ;
   - **un seul contenu central**, par priorité : **autorisation en attente** (l'aperçu §12, écrit
     « Attend votre autorisation » et la commande) › **aperçu des dernières lignes** (`app-terminal-
     preview`) › **« Au repos · dernier tour il y a … »** (ou « Au repos · aucun tour » ) ;
   - pied : « Actif » (§5) si le projet travaille, le dernier outil et sa date, puis **« Ouvrir → »**
     qui ouvre le terminal du projet ;
   - tuile en attente : **filet ambre** §12 (`#F9A825`) et anneau `#FFF8E1` — jamais un fond.
4. **Tuile fantôme** en fin de grille (machine en ligne, dossiers non ouverts > 0) : « *n* dossier(s)
   non ouvert(s) » (« *n*+ » si la machine a tronqué sa liste), « dans *racine* », bouton
   **Parcourir** qui ouvre l'explorateur « Ajouter un projet » (on y clique un dossier, le projet
   existe, sans nom à saisir — F-72). Bordure en pointillés `--cg-divider`.
5. **Filtre de la colonne** : quand le filtre ne retient le poste ouvert **que par ses projets**, la
   grille ne montre que les projets qui correspondent et le dit (« *k* projet(s) correspondent au
   filtre »).
6. **Poste sans projet** : le message existant, puis la tuile fantôme s'il y a des dossiers.

### Cas d'erreur

| Situation | Comportement attendu | Code |
|-----------|---------------------|------|
| Lecture des dossiers en échec ou poste hors ligne | pas de tuile fantôme (silencieux, F-72) | — |
| `lastActivityAt` illisible | « Au repos · aucun tour », jamais « NaN » | — |
| Aperçu `IDLE` sans lignes | considéré absent : « Au repos … » | — |
| Poste « Hébergé » | ni chemin, ni tuile fantôme, ni « Ajouter un projet » | — |
| Projet sans nom lisible | le nom rendu tel que la gateway le donne, tronqué visuellement | — |

---

## Critères d'acceptation

- [ ] Les projets du poste ouvert sont rendus en tuiles dans une grille `auto-fill` (min 232 px).
- [ ] Une tuile en attente montre « Attend votre autorisation » et la commande, porte le filet ambre,
      et passe en tête en *Actifs d'abord*.
- [ ] Une tuile avec aperçu montre les dernières lignes ; sans aperçu, « Au repos · dernier tour … ».
- [ ] Les trois tris ordonnent comme décrit ; *Actifs d'abord* est le défaut.
- [ ] La tuile fantôme dit le nombre de dossiers non ouverts et « Parcourir » ouvre « Ajouter un
      projet » ; elle n'existe ni hors ligne, ni pour « Hébergé ».
- [ ] « Ouvrir → » ouvre `/atelier/<id>`.
- [ ] Aucune couleur hors `DESIGN_SYSTEM.md` : bordure `--cg-divider`, rayon 8 px, ambre §12, gris §5.

---

## Périmètre

### Hors scope (explicite)

- Le **temps restant** d'une autorisation sur la tuile : la vue d'ensemble (`GET /api/runner-hosts/
  overview`) ne le porte pas et le cadrage fige les endpoints lus ; l'afficher exigerait de le
  deviner à l'écran (interdit par SF-47-02). La commande est affichée.
- La **dette de promotion** par projet : aucune donnée de la vue d'ensemble ne la porte.
- L'état de mission par tuile : c'est celui du poste, écrit dans l'en-tête (§10).
- Le glisser-déposer, les vues enregistrées (cadrage §8).
- Les textes et l'écran téléphone (SF-98-05).

---

## Valeurs initiales

| Champ | Valeur initiale | Règle |
|-------|----------------|-------|
| tri | `actifs` | non retenu : il ne survit pas à la page |

---

## Contraintes de validation

| Champ | Obligatoire | Format / Valeurs autorisées | Normalisation |
|-------|-------------|----------------------------|---------------|
| tri | Oui | `actifs`, `alpha`, `recents` | — |

---

## Technique

### Endpoint(s)

Aucun. Lectures inchangées (vue d'ensemble, dossiers de la racine une fois par page).

### Tables impactées

Aucune.

### Migration Liquibase

- [x] Non applicable

### Composants Angular

- `postes/forge-projects.ts` (nouveau) — tris, contenu central d'une tuile, filtre de la grille.
- `postes/forge-project-tile/` (nouveau) — la tuile, présentationnelle.
- `frontend/karma.conf.js` — fenêtre de test 1440 × 900 (`--window-size`), pour que le critère de la
  feature se **mesure** : sans elle, Chrome sans tête rend en 800 × 600 et la colonne s'empile.
- `PostesComponent` — barre d'outils, grille, tuile fantôme ; retrait de la liste des dossiers non
  ouverts et de l'ouverture d'un dossier d'un clic depuis l'écran (le même geste vit dans
  l'explorateur « Ajouter un projet »).

### Préoccupations transversales

- Navigation / routing : non (aucune route, aucun paramètre ; « Ouvrir → » garde `/atelier/<id>`).
- Auth / Principal, tenant, plans / limites : non.

---

## Plan de test

### Tests unitaires (frontend)

- [ ] `forge-projects.spec.ts` — trois tris (égalités comprises), contenu central (attente › aperçu ›
      repos), aperçu `IDLE` vide ignoré, filtre de la grille.
- [ ] `forge-project-tile.component.spec.ts` — nom, chemin, pastille de vie, attente (libellé, filet),
      aperçu, repos daté, « Ouvrir → » émis, « Hébergé » sans chemin.
- [ ] `PostesComponent` — critère de la feature mesuré (1440 × 900, 12 tuiles et la colonne sans
      défiler) ; grille rendue ; tri par défaut et changement de tri ; tuile fantôme (compte,
      troncature, Parcourir ⇒ dialogue, absente hors ligne et pour « Hébergé ») ; filtre de la grille ;
      tests existants (terminal, pastilles de vie, aperçus) adaptés aux tuiles.

### Isolation workspace

- [x] Non applicable — aucune donnée nouvelle.

---

## Dépendances

### Subfeatures bloquantes

- SF-98-02 — `done`.

### Questions ouvertes impactées

- Aucune.

---

## Notes et décisions

- **Le contenu central réemploie `app-terminal-preview`** : §12 en fait le composant unique de l'aperçu
  et de l'attente ; la tuile ne recompose ni la pastille ni les lignes.
- **Plus de filet d'identité par projet** : la grille n'affiche que les projets du poste ouvert, dont
  l'identité est déjà portée par l'en-tête et la ligne ouverte de la colonne (fidèle à la maquette).
- **Le critère de la feature est un test** : 4 postes, 15 projets sur l'un, fenêtre 1440 × 900 — la
  12e tuile et la dernière ligne de la colonne tiennent sous 900 px, barre d'application (64 px)
  comprise. Mesuré : tuile ~152 px, 12e tuile à ~798 px.
- **Tuile fantôme au lieu de la liste** : le cadrage l'impose ; « ouvrir d'un clic » reste le geste de
  l'explorateur, à un clic de plus, et la page ne grandit plus avec les trente dossiers d'un `~/dev`.
