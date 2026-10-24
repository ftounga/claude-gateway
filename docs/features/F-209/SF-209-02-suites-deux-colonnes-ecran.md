# Mini-spec — [F-209 / SF-209-02] Les suites du chantier en deux colonnes — écran

## Identifiant
`F-209 / SF-209-02` — Feature parente `F-209` (Les suites du chantier en deux colonnes) — Statut `in-review` — Créée 2026-10-24
Branche : `feat/SF-209-02-suites-deux-colonnes` — cadrage : décisions 3, 5, 6. Dépend de SF-209-01 (mergée : `column`, `blockedBy`, `prerequisites`, `Progress.title`).

## Objectif
Le panneau « Et maintenant ? » montre, quand un chantier est ouvert, deux colonnes — « Chantier N — titre » avec sa mini-barre et « Hors chantier » — avec un badge par option, ce qui bloque, et des prérequis cochables qu'un bouton « Lancer » envoie en une seule demande visible.

## Comportement nominal
- **Composant réutilisable** `app-terminal-next-steps` (entrées inchangées : `steps` = les suites, `chantier` = la progression ; sortie nouvelle `launch`) : F-210 l'affiche une fois par carte de réponse sans autre adaptation.
- **Deux colonnes** dès qu'une suite porte `column` : gauche « Chantier 4 — titre » + **mini-barre** « 5/14 faites » ; droite « Hors chantier » (absente si vide : la gauche prend toute la largeur). Touches 1 à 7 numérotées à travers les deux colonnes (gauche d'abord). Sans `column` (Libre) : une colonne, 4 au plus, inchangé.
- **Badge** par option rattachée : « Ch.4 · étape 4/14 » (le total vient de la progression du chantier) ; « Ch.3 · étape 2/2 » pour un autre chantier (avec « Basculer sur le chantier 3 », inchangé). La ligne de rattachement ne répète que le titre de l'étape.
- **Bloquée** : « bloquée : après les étapes 4, 5 · attente feu-vert ».
- **Prérequis** (`prerequisites`) sous l'option : cases à cocher **cochées par défaut, sauf EXTERNE** (jamais pré-cochée, marquée « externe ») ; un niveau ⇒ « Avant, en parallèle » ; plusieurs ⇒ « Avant, dans l'ordre » avec les niveaux numérotés 1 → 2.
- **« Lancer »** : compose UNE demande — « Chantier N — d'abord les prérequis, dans cet ordre — 1) en parallèle : étape 2 « … », étape 3 « … » ; 2) étape 4 « … ». Puis : <demande de l'option> » — et l'envoie par le chemin normal (visible dans le fil ; précision pendant un tour). Une étape EXTERNE cochée exige une **confirmation explicite** ([Confirmer] / [Annuler]).
- Le clic simple sur une option garde son comportement (cocher, remplir la saisie, rien n'est envoyé).
- **Téléphone** (< 820 px) : colonnes empilées, Chantier en premier.
- Charte : jetons existants seulement (mêmes teintes que le panneau existant), polices de charte.

## Cas d'erreur
| Situation | Comportement attendu |
|---|---|
| Aucune suite rangée en colonne (Libre, gateway antérieure) | une colonne, 4 au plus |
| Colonne Hors vide | la colonne Chantier prend toute la largeur |
| Aucun prérequis coché | « Lancer » envoie la demande de l'option seule |
| EXTERNE cochée puis [Annuler] | rien n'est envoyé |
| Lecture seule / tour sans précision | même garde que les autres envois (`onBoardRequest`) |

## Critères d'acceptation
- [ ] CA1 Deux colonnes côte à côte au bureau, avec en-tête « Chantier N — titre », mini-barre et « Hors chantier ».
- [ ] CA2 Badge « Ch.N · étape M/T » et texte de blocage.
- [ ] CA3 Prérequis cochés par défaut sauf EXTERNE ; niveaux affichés ; « Lancer » compose et envoie une demande.
- [ ] CA4 EXTERNE cochée : confirmation explicite obligatoire.
- [ ] CA5 Libre inchangé ; empilement sous 820 px.
- [ ] CA6 Suite frontend et build verts ; charte respectée.

## Plan de test
- `terminal-suites-colonnes.spec.ts` (nouveau) : fonctions pures (badge, blocage, groupes, composition), colonnes côte à côte (mesure réelle), touches, prérequis par défaut, confirmation EXTERNE, Libre, colonne Hors vide.
- `terminal-et-maintenant.spec.ts` : « Lancer » part par `draftChange` + `send`.
- Specs existantes du panneau inchangées et vertes.
- Isolation utilisateur : sans objet (aucune lecture, aucune route).

## Préoccupations transversales
Aucune.

## Impacts
`terminal-next-steps.component.ts`, `core/models/atelier.models.ts`, `atelier-terminal.component.html` (sortie `launch`), specs.

## Hors périmètre
Exécution réellement parallèle imposée (le harnais F-195 décide). Affichage par carte de réponse (F-210).
