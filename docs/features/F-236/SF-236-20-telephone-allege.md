# Mini-spec — [F-236 / SF-236-20] Téléphone allégé

## Identifiant
SF-236-20

## Feature parente
F-236 — Jarvis en 3D (cadrage §12.2, §15 ; itération 3 « Le cockpit PC »)

## Statut
done

## Date de création
2026-11-02

## Branche Git
`feat/SF-236-20-telephone-allege`

---

## Objectif

Appliquer au téléphone la matrice §12.2 (ce qui reste, ce qui disparaît) et y donner accès aux vues du cockpit par
« ☰ Vues » en volets plein écran, une à la fois.

## Comportement attendu

### Cas nominal

1. **Matrice en configuration pure** (`hud/jarvis-device-profile.ts`) : un profil PC et un profil téléphone, un champ
   par ligne de la matrice, lus par le moteur, le directeur et le cockpit.
2. **Ce qui reste** au téléphone : cœur et états (particules réduites, densité du niveau), les deux cerveaux (allégés,
   SF-236-03), clients, scènes, « oui », « Montrer », « À envoyer », sous-titres, fil d'Ariane, compteur, Pause.
3. **Ce qui change** : sujets et chantiers du client courant ou cités (D7, inchangé) ; **liaisons : le chemin du
   focus seulement**, le reste **absent** (plus de liaisons latentes) ; **un seul panneau ancré** (deux pour S4) ;
   **entrée 1,2 s** (même chorégraphie accélérée ; HUD qui glisse à ≈ 0,9 s) ; **décor** : ni nébuleuse ni poussière,
   une seule profondeur d'étoiles, grille simple (≤ 3 cercles) ; ni profondeur de champ, ni SMAA, ni finition
   (SF-236-15), ni verre flouté (déjà réservé au pointeur fin) ; **essaim ≤ 2** (SF-236-12) ; **aucune dérive
   automatique** de l'univers.
4. **« ☰ Vues »** : panneaux latéraux, journée, météo, personnes, chronologies, inspecteur et tableaux flottants
   disparaissent de la scène ; un bouton « ☰ Vues » ouvre un **volet plein écran** (onglets Aujourd'hui, Météo,
   Personnes, Chronologies, Inspecteur ; une vue à la fois ; « Fermer »), qui réutilise les vues du cockpit (mêmes
   gabarits) sans « Détacher ».

### Cas d'erreur

| Cas | Comportement |
|---|---|
| Inspecteur sans nœud touché | « Touchez un nœud de l'univers pour voir sa fiche. » + liste de l'univers. |
| Rotation en paysage (> 767 px au relevé) | Le relevé « téléphone » est pris à l'ouverture (comportement existant). |
| 2D (repli) ou mode sobre | Ni bouton ni volet (écran F-234 inchangé). |

---

## Critères d'acceptation

- [x] CA1 — Chaque ligne de la matrice vérifiée par un test de configuration par profil, et sur ses consommateurs
  (disposition, liaisons, pool de panneaux, budget de niveau, chaîne de rendu, entrée, essaim).
- [x] CA2 — Volet « ☰ Vues » : une vue à la fois, onglets, fermeture ; aucun panneau ni tableau flottant au téléphone
  (test du composant).
- [x] CA3 — Captures téléphone (`cockpit-vues`, `univers-telephone`, `scene-tickets`, `scene-commande`, `entree`) non
  vides, sans erreur console.
- [ ] CA4 — ≥ 50 i/s p50 et ≤ 4 ms CPU p90 sur le téléphone du PO avec toutes les scènes : à relever en usage
  (événement `RENDER`) — le banc tourne en rendu logiciel.

## Contraintes de validation

| Élément | Règle |
|---|---|
| Entrée téléphone | 1 200 ms |
| Panneaux ancrés téléphone | 1 (S4 : 2), jamais plus que le pool (2) |
| Grille du sol téléphone | ≤ 3 cercles |

## Plan de test

- Unitaires : `hud/jarvis-device-profile.spec.ts` (matrice ligne par ligne + consommateurs) ; `jarvis-panels.spec.ts`
  et `jarvis-scenes-chantier.spec.ts` mis à jour (téléphone : 1 panneau, 2 pour S4).
- Composant : `hud/jarvis-cockpit.component.spec.ts` (volet « ☰ Vues »).
- Isolation utilisateur : sans objet. Visuel : captures téléphone.

## Périmètre

### Impacté
`hud/jarvis-device-profile.ts`, `hud/jarvis-cockpit.component.{ts,html,scss}` (gabarits partagés, volet),
`engine/jarvis-engine.ts` (budget par profil, entrée, dérive), `jarvis-panels.ts` (`boundPanels(…, sceneId)`),
`jarvis-link-graph.ts` (`narrow`), `jarvis-stage-director.ts`, `jarvis.component.scss` (HUD de l'entrée), banc.

### Hors scope (explicite)
- Replay et mode présentation au téléphone (itération 4). Compagnon Android natif (§19).
- Aucun endpoint, table, appel IA ni consigne.

## Préoccupations transversales
Aucune cochée (aucune route : le volet est local à `/jarvis`).

## Notes et décisions

| # | Arbitrage | Pourquoi | Alternative écartée | Réversible |
|---|---|---|---|---|
| A1 | **Un panneau ancré** au téléphone (2 pour S4), au lieu du pool de 2 de SF-236-08. | La matrice §12.2 le demande ; deux tests de SF-08/SF-10 mis à jour en conséquence. | Garder 2. | Oui. |
| A2 | Volet à 94 % d'opacité, sans flou. | Pas de verre flouté au téléphone (§12.2) ; l'univers reste deviné. | Volet opaque. | Oui. |
| A3 | Les vues du volet réutilisent les gabarits du cockpit. | Une seule source de vérité ; pas de dette de deux rendus. | Composants dédiés. | Oui. |
| A4 | Dérive coupée, directeur de caméra inchangé (changement de sujet, focus). | « La caméra ne bouge qu'au changement de sujet et sur un focus. » | Directeur dédié au téléphone. | Oui. |
