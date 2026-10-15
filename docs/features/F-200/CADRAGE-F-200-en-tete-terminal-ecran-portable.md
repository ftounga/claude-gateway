# Cadrage F-200 — En-tête du terminal lisible sur un écran de portable

**Demande PO (2026-10-15)**, livraison autonome (vague). Constat vérifié sur `origin/main` 6e16f2c8 et en prod. Justesse avant coût ; charte `DESIGN_SYSTEM.md` (aucune couleur nouvelle) ; préfixe système stable (rien de volatil).

## Besoin
Sur l'écran du PC seul (sans grand écran externe), l'en-tête du terminal est laid : boutons superposés (nom du client, Nouveau départ, etc.).

## Cause (code)
`atelier-terminal.component.html:31-316` : un seul `header.terminal-bar` portant fil d'Ariane + client, puce parcours, budget, coût, moteur, live, Teams, puis jusqu'à 9 boutons texte (Interrompre, instructions, N à faire, Validation, Fichiers, Publier, Réinitialiser, Compacter, Nouveau départ, Quitter). `.terminal-bar` et `.terminal-bar-actions` en flex **sans wrap ni overflow** (`.scss:59-67`, `289-298`) ; seul le fil d'Ariane rétrécit ; le menu ⋯ et le wrap n'existent que sous 819 px (`isNarrow`, `.ts:1977-1992`). Entre 820 et ~1600 px tout se chevauche.

## Décisions
- Mise en page en **paliers** : au-dessus d'un seuil large, tout visible ; en dessous, les actions secondaires (Publier, Réinitialiser, Compacter, Fichiers, instructions…) passent dans un menu ⋯ ; les actions primaires (Interrompre, Validation, à faire) restent visibles ; le fil d'Ariane s'ellipse proprement ; jamais de chevauchement ni de débordement horizontal.
- Mesure par **largeur réelle disponible** du conteneur (ResizeObserver ou container queries), pas seulement la fenêtre (le terminal peut être dans une grille/mosaïque).
- Vérifier à 1280, 1366, 1440, 1536, 1920 px (captures headless jointes à la PR) ; budget de style : feuille annexe si la principale est au plafond.

## Découpage
| SF | Objet | Risque |
|---|---|---|
| SF-200-01 | En-tête à paliers (menu ⋯ des actions secondaires, priorités, ellipses), tests de rendu aux 5 largeurs + captures | 🟠 |
