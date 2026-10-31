# SF-226-02 — Onglets lisibles, split sur téléphone, split allégé

**Feature :** F-226 · **Statut :** done (2026-10-31) · **Estimation :** < 1 j · **Branche :** `feat/SF-226-02-onglets-et-split`

## Objectif
Le multi-chantier et le split se lisent sans effort : onglets entiers, état de chaque volet, pas de bande répétée.

## Comportement
1. **Onglets du tableau des chantiers** : ils passent à la ligne (plus de défilement horizontal ni de « Ch.1 R… ») ; un titre trop long s'ellipse au plus à 320 px et l'info-bulle (`title`) donne « Chantier N — titre » entier.
2. **Onglets du split sur téléphone** : « Chantier N · titre » (ou « Fil du sujet ») et une pastille d'état écrite — « tourne » (tour vivant ou course autonome) ou « à vous » (plan à valider, diagnostic, clôture proposée). L'en-tête du volet de droite prend le même titre. Les titres viennent du parcours relu par le terminal (`chantiersChange`), aucun appel de plus.
3. **Split allégé** : dans le volet de droite, la bande du parcours ne répète pas les onglets des chantiers (`compact`) — le volet de gauche les porte, l'en-tête du volet dit lequel il montre.

## Erreurs
Sans objet (affichage). Titre inconnu : « Chantier N ».

## Critères d'acceptation
- [x] `.board__tabs` en `flex-wrap: wrap`, sans `overflow-x: auto` ; info-bulle du titre entier.
- [x] Volet de droite : pas de `.journey-chantiers`.
- [x] Onglets du split : titre et état (« tourne », « à vous », rien).

## Plan de test
`terminal-chantier-parallele-f226.spec` (+3 tests : styles calculés des onglets, bande compacte, libellés et états — fonctions pures `lane-tabs.ts`) ; suite front complète (3631/3631), build.

## Impact
`terminal-chantiers-board` (styles, titre), `terminal-journey-strip` (`compact`), `atelier-terminal` (`chantiersChange`, `compact` en volet), `atelier.component` (onglets, en-tête du volet), `lane-tabs.ts` (nouveau), `atelier.component.scss`. Préoccupations transversales : aucune.

## Hors périmètre
Une bande commune « au-dessus » des deux volets (refonte du gabarit du split) : la réduction dans le volet de droite suffit au cadrage (« commune ou réduite »).
