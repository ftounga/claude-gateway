# Mini-spec — [F-158 / SF-158-18] Bandeaux du terminal empilés sur mobile

---

## Identifiant

`F-158 / SF-158-18`

## Feature parente

`F-158` — Le terminal de l'Atelier utilisable sur téléphone (responsive P0)

## Statut

`in-progress`

## Date de création

2026-09-26

## Branche Git

`feat/SF-158-18-bandeaux-empiles-mobile`

---

## Objectif

> En une phrase : que fait cette subfeature ?

Sous 819 px, empiler le bandeau de compaction (« Cette conversation est longue — repartir propre ? »)
— texte pleine largeur, boutons dessous et tactiles — et compléter le même empilement sur les
bandeaux frères du terminal restés à l'état « texte écrasé + boutons en rangée » (proposition de
runner).

---

## Comportement attendu

### Cas nominal

> Description précise du flux principal (entrée → traitement → sortie).

- **≥ 820 px (desktop)** : INCHANGÉ. Icône + texte (`flex:1`) + actions (`flex-shrink:0`) restent sur
  une seule rangée, comme aujourd'hui.
- **≤ 819 px (téléphone, cas P0 à 390 px)** : le bandeau de compaction passe la rangée d'actions
  (`.terminal-compaction-hint-actions`) sous le texte, en pleine largeur (`flex: 1 1 100%` +
  `flex-wrap: wrap` sur le conteneur). Le bloc de texte occupe alors la première ligne (icône + texte)
  et se lit normalement (plus « un mot par ligne »). Les deux boutons (« Nouveau départ » / « Plus
  tard ») sont dimensionnés au pouce (`min-height: 44px`).
- **Balayage même patron** : la proposition de runner (`.terminal-hint-runner`), déjà `flex-wrap: wrap`
  (SF-158-11) mais dont la rangée d'actions (« Plus tard » + « Connecter un poste », ~240 px) écrase
  encore le texte à 390 px, reçoit le même complément : `.terminal-hint-runner-actions { flex: 1 1 100% }`
  + boutons `min-height: 44px`. On ne double pas ce qui existe (le `flex-wrap` du conteneur et le
  `min-width: 0` du texte restent), on complète ce qui manquait (le saut de ligne forcé des actions).

### Cas d'erreur

> Lister tous les cas d'erreur identifiés.

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Aucun (changement purement CSS, présentation) | — | — |

---

## Critères d'acceptation

> Chaque critère est vérifiable. Pas d'ambiguïté.

- [ ] Sous 819 px, `.terminal-compaction-hint` porte `flex-wrap: wrap` et
      `.terminal-compaction-hint-actions` porte `flex: 1 1 100%` → les boutons passent sous le texte,
      pleine largeur.
- [ ] Sous 819 px, les boutons du bandeau de compaction sont tactiles (`min-height: 44px`).
- [ ] Sous 819 px, `.terminal-hint-runner-actions` porte `flex: 1 1 100%` (complément du `flex-wrap`
      existant) et ses boutons sont tactiles (`min-height: 44px`).
- [ ] Le texte du bandeau de compaction n'est plus écrasé à 390 px (il dispose de ~toute la largeur,
      ≥ ~250 px, une fois les boutons descendus).
- [ ] Desktop (≥ 820 px) STRICTEMENT inchangé : toutes les règles sont sous `@media (max-width: 819px)`,
      dans la feuille mobile dédiée (`atelier-terminal-mobile.component.scss`).
- [ ] Charte respectée : jetons `--cg-*` uniquement, aucune couleur nouvelle, grille de 4 px, cibles
      ≥ 44 px, point de rupture 819 px, budget de style par feuille (12 ko compilé) tenu.
- [ ] Garde-fou test (CSSOM, indépendant du viewport, patron SF-158-09) : les intentions ci-dessus
      sont vérifiées dans la feuille mobile.

---

## Périmètre

### Hors scope (explicite)

- Le DOM / les templates (`atelier-terminal.component.html`) : aucun changement de structure — style seul.
- Le comportement des bandeaux `.terminal-live-limit` et `.terminal-host-state` : déjà traités en
  SF-158-11 (bouton unique, le texte y garde une largeur lisible). On n'y touche pas (anti-doublon).
- Le desktop (≥ 820 px).
- Toute logique métier (le geste « Nouveau départ » / « Connecter un poste » est inchangé).

---

## Technique

### Composants Angular (si applicable)

- `AtelierTerminalComponent` — feuille mobile dédiée `atelier-terminal-mobile.component.scss` (toutes
  les règles `@media (max-width: 819px)` du terminal y vivent déjà : live-limit, host-state,
  hint-runner, décisions d'autorisation…). On y ajoute le bloc « bandeau de compaction » et le
  complément « proposition de runner ». Le template et le SCSS de base restent inchangés.

### Migration Liquibase

- [x] Non applicable

---

## Plan de test

### Tests unitaires

- [ ] Nouveau spec CSSOM (`terminal-bandeaux-empiles.spec.ts`, patron SF-158-09
      `terminal-contenu-responsive.spec.ts`) : sous `max-width: 819px`,
  - `.terminal-compaction-hint` → `flex-wrap: wrap`
  - `.terminal-compaction-hint-actions` → `flex: 1 1 100%`
  - bouton du bandeau de compaction → `min-height: 44px`
  - `.terminal-hint-runner-actions` → `flex: 1 1 100%`

### Tests d'intégration

- [ ] `npm run build` (frontend) vert — le budget de style (12 ko compilé/feuille) tient.

### Isolation workspace

- [x] Non applicable — raison : changement de présentation CSS, aucun accès aux données.

---

## Préoccupations transversales

| Préoccupation | Impactée ? | Composants |
|---------------|-----------|------------|
| Auth / Principal | Non | — |
| Contexte tenant | Non | — |
| Plans / limites | Non | — |
| Navigation / routing | Non | — (les `routerLink` des boutons sont inchangés) |

Aucune préoccupation transversale cochée : changement purement présentationnel, mobile uniquement.

---

## Notes et décisions

- **Emplacement** : feuille mobile dédiée `atelier-terminal-mobile.component.scss` (et non la feuille
  `atelier-terminal-compaction.component.scss`), pour regrouper toutes les règles `@media` du terminal
  au même endroit, comme `.terminal-live-limit` / `.terminal-host-state` / `.terminal-hint-runner`
  (SF-158-10/11). Le garde-fou CSSOM scanne toutes les feuilles → l'emplacement n'affecte pas le test.
- **Mécanisme du fix** : `flex-wrap: wrap` sur le conteneur + `flex: 1 1 100%` sur la rangée d'actions.
  Le `flex-basis: 100%` de la rangée d'actions ne peut pas cohabiter sur la même ligne que l'icône et
  le texte → elle saute à sa propre ligne, pleine largeur ; le texte récupère alors toute la première
  ligne (le `flex-wrap` seul ne suffisait pas car le texte, en `min-width: 0`, se laissait écraser au
  lieu de forcer le retour à la ligne des boutons).
- **Garde-fou de test** : CSSOM (inspection de `document.styleSheets`), indépendant du viewport —
  patron déjà retenu en SF-158-09 parce que la fenêtre Karma n'est pas à 390 px et que `matchMedia`
  n'y matcherait pas de façon fiable. Le critère « largeur ≥ 250 px » est garanti structurellement par
  la descente des boutons (le texte occupe la première ligne complète).
