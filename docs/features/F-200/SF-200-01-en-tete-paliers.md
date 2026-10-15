# Mini-spec — [F-200 / SF-200-01] En-tête du terminal à paliers

## Identifiant
`F-200 / SF-200-01` — Feature parente `F-200` (En-tête du terminal lisible sur un écran de portable) — Statut `in-review` — Créée 2026-10-16
Branche : `feat/SF-200-01-en-tete-paliers` — aucune dépendance.

## Objectif
Sur un écran de portable (1280 à 1920 px), l'en-tête du terminal tient sur une ligne, sans chevauchement ni débordement : les actions secondaires passent dans un menu ⋯, les actions primaires restent en clair.

## Comportement nominal
Le palier est choisi par la **largeur réelle de l'en-tête** (`ResizeObserver` sur l'hôte du composant, pas la fenêtre) — `terminal-bar-tier.ts`, fonction pure :

| Palier | Largeur d'en-tête | Rendu |
|---|---|---|
| `full` | ≥ 2880 px (ou largeur inconnue) | bandeau complet, inchangé |
| `compact` | 1680 – 2879 px | Interrompre (pendant un run), « N à faire », Validation en clair ; instructions, Fichiers, Publier, Réinitialiser, Compacter, Nouveau départ, Quitter sous ⋯ ; dossier réduit à son icône ; pastille moteur ellipsée |
| `tight` | 820 – 1679 px | comme `compact`, et les pastilles/actions primaires ne gardent que leur icône (« 3 » pour « 3 à faire ») ; nom accessible et infobulle conservés |
| `narrow` | < 820 px (fenêtre ou en-tête) | en-tête téléphone de F-158 / SF-158-12, inchangé |

Garanties CSS (≥ 820 px de fenêtre, feuille `atelier-terminal-header.component.scss`) : seul le fil d'Ariane rétrécit (base nulle, prend la place libre, ellipse sur une ligne : seul le niveau courant cède) ; les autres éléments gardent leur taille ; filet de sécurité `flex-wrap` (passer à la ligne plutôt que superposer).

## Cas d'erreur
| Situation | Comportement attendu |
|---|---|
| `ResizeObserver` absent (environnement sans DOM) | palier `full` (rendu historique), aucune erreur |
| Largeur 0 (élément non mis en page, tests) | palier `full` |
| Contenu imprévu plus large que le seuil mesuré | la barre passe à la ligne, jamais de chevauchement ni de défilement horizontal |
| Redimensionnement pixel par pixel | aucun rendu tant que le palier ne change pas |

## Critères d'acceptation
- [ ] CA1 À 1280, 1366, 1440, 1536 et 1920 px, avec l'en-tête le plus chargé (client, dossier, budget, coût, moteur « ma machine — en ligne… », connecté, run en cours, 3 à faire, toutes les actions de la machine) : `scrollWidth ≤ clientWidth`, aucun élément ne chevauche un autre, une seule ligne.
- [ ] CA2 Idem aux seuils de chaque palier (1680, 2880 px).
- [ ] CA3 Interrompre, « à faire » et Validation restent en clair à tous les paliers ≥ 820 px ; le menu ⋯ n'existe qu'en dessous du palier complet.
- [ ] CA4 Au palier compact, le menu ⋯ porte instructions, Fichiers, Publier, Réinitialiser, Compacter, Nouveau départ, Quitter — sans doublon de « à faire »/Validation ; chaque entrée émet le même `@Output` que son bouton.
- [ ] CA5 Le palier suit la largeur du conteneur (`onBarResize`) ; l'hôte est observé et l'observateur lâché à la destruction.
- [ ] CA6 Non-régression : en-tête téléphone F-158 inchangé ; contraste AA des boutons du terminal (F-30 / SF-30-15) vert ; suite complète verte ; `ng build` vert (budget de style).
- [ ] CA7 Captures headless aux 5 largeurs jointes (`docs/features/F-200/captures/`).

## Plan de test
- `terminal-en-tete-paliers.spec.ts` (nouveau) : fonction pure (4 cas), mesure de layout réel ChromeHeadless aux 7 largeurs (CA1/CA2), palier et actions primaires (CA3), menu ⋯ ouvert (CA4), relevé du ResizeObserver + observe/disconnect (CA5).
- `terminal-largeur-reelle.spec.ts` : le test « bandeau complet en desktop » pose désormais un en-tête de grand écran (seuil `full`).
- Isolation utilisateur : sans objet (aucun accès données, aucun endpoint).
- Non-régression : `npm run build && npm test` complet.

## Impacts
- Tables / endpoints : aucun (frontend seul).
- Composants : `AtelierTerminalComponent` (en-tête : `barWidth`, `barTier`, `onBarResize`, gabarit `barPrimaryActions`), `ForgeBreadcrumbComponent` (modificateur `forge-crumbs--one-line`, `title` sur les niveaux texte), nouvelle feuille `atelier-terminal-header.component.scss`, `terminal-bar-tier.ts`.
- Préoccupations transversales : **Navigation / routing** non (les liens du fil sont inchangés) ; auth, tenant, plans : non.
- Design system : aucune couleur ni police nouvelle ; espacements de la charte (`--cg-space-*`).

## Arbitrages (gate 🟠 réversible — décidé par défaut)
- **Seuils mesurés sur le contenu maximal**, pas sur un contenu typique : un en-tête qui tient toujours sur une ligne plutôt qu'un bandeau complet qui casse quand un run démarre. Conséquence : à 1920 px, les actions secondaires sont sous ⋯. Alternative écartée : décider sur le contenu mesuré en direct (oscillations entre paliers). Réversible : deux constantes dans `terminal-bar-tier.ts`.
- **Quitter et Nouveau départ** sont secondaires (sous ⋯) : ils ne débloquent ni ne protègent un travail en cours. Le fil d'Ariane reste un chemin de sortie en un clic.
- **Palier serré icône seule** pour Validation/à faire/moteur : l'état reste dit par l'icône et la couleur de la charte, le libellé par l'infobulle et `aria-label`.

## Hors périmètre
- Backend, en-tête téléphone (< 820 px), tuiles de mosaïque (lecture seule, sans en-tête).
- Refonte du contenu des pastilles budget/coût/parcours.
