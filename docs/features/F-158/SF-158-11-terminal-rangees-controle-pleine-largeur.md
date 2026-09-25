# Mini-spec — [F-158 / SF-158-11] Rangées de contrôle du terminal en pleine largeur (mobile)

## Identifiant

`F-158 / SF-158-11`

## Feature parente

`F-158` — Écrans de contenu responsive (mobile)

## Statut

`in-progress`

## Date de création

2026-09-25

## Branche Git

`feat/SF-158-11-terminal-rangees-controle-pleine-largeur`

## Priorité

**P0** — 2ᵉ lot du correctif : après le containment (SF-158-10), rendre les rangées de contrôle
réellement **utilisables** sur téléphone (pas seulement clippées).

---

## Objectif

> En une phrase : que fait cette subfeature ?

Sous 819 px, rendre les **rangées de contrôle rigides** du terminal (sélecteur « target » où s'exécutent
les outils, sélecteur « mode » Réponse/Plan/Agir, état du poste + commande de relance, proposition de
runner, ligne de commande) **pleine largeur et enroulables** — pour que le fil et ses contrôles TIENNENT
dans l'écran à 360/400 px sans dépendre du seul clip de SF-158-10.

---

## Comportement attendu

### Cas nominal

- Desktop (≥ 820 px) : **inchangé** (toutes les règles sous `@media (max-width: 819px)`).
- Téléphone (< 819 px) :
  - **Sélecteur « target »** (`.terminal-target*`) : `mat-button-toggle-group` en **pleine largeur**
    (`width: 100%`), chaque option `flex: 1` (les 2 options à parts égales) ; le libellé passe sur sa
    propre ligne (le groupe pleine largeur force le retour à la ligne, `.terminal-target` étant déjà
    `flex-wrap: wrap`).
  - **Sélecteur « mode »** (`.terminal-mode*`) : idem — toggle-group pleine largeur, options `flex: 1`,
    libellé sur sa propre ligne.
  - **État du poste / commande de relance** (`.terminal-host-state*`) : la rangée passe à la ligne
    (`flex-wrap: wrap`), le corps `.terminal-host-state-body` porte `min-width: 0`, et la commande
    `code` enroule les chaînes longues (`overflow-wrap: anywhere; word-break: break-all`).
  - **Proposition de runner** (`.terminal-hint-runner*`) : la rangée passe à la ligne
    (`flex-wrap: wrap`), le texte `.terminal-hint-runner-text` porte `min-width: 0`.
  - **Ligne de commande** (`.terminal-command code`) : porte `min-width: 0` (elle est un item flex de
    `.terminal-command`) → elle enroule au lieu de forcer un débordement.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Commande de relance / chemin très long dans l'état du poste | Enroulé (`word-break: break-all`) ; la rangée tient dans 360 px |
| Sélecteur target/mode plus large que l'écran | Toggle-group pleine largeur, 2 options à parts égales ; tient dans l'écran |
| Ligne de commande shell insécable très longue | Ramenée dans le cadre (`min-width:0` + `word-break`) ; pas de scroll-x de page |
| Proposition de runner avec texte long + boutons | La rangée passe à la ligne ; texte et actions empilés |

---

## Critères d'acceptation

- [ ] Sous 819 px, `.terminal-target-toggle` et `.terminal-mode-toggle` sont en `width: 100%` et leurs
      `mat-button-toggle` en `flex: 1` (2 options à parts égales).
- [ ] Sous 819 px, `.terminal-host-state` passe à la ligne (`flex-wrap: wrap`), `.terminal-host-state-body`
      porte `min-width: 0`, et `.terminal-host-state code` porte `overflow-wrap: anywhere` +
      `word-break: break-all`.
- [ ] Sous 819 px, `.terminal-hint-runner` passe à la ligne (`flex-wrap: wrap`) et
      `.terminal-hint-runner-text` porte `min-width: 0`.
- [ ] Sous 819 px, `.terminal-command code` porte `min-width: 0`.
- [ ] **Test de largeur (extension de SF-158-10)** : un tour rendu avec target + mode + état du poste à
      **360 px** vérifie `host.scrollWidth <= host.clientWidth` ; une commande de relance volontairement
      très longue vérifie que la rangée d'état du poste enroule (`row.scrollWidth <= row.clientWidth`) —
      **rouge avant** le `word-break`/`min-width:0`, **vert après**.
- [ ] À ≥ 820 px, rendu identique à l'existant (aucune régression desktop).
- [ ] Jetons `--cg-*` uniquement, aucune couleur nouvelle ; nouveaux styles dans la feuille mobile dédiée
      (`atelier-terminal-mobile.component.scss`), feuille principale intacte (plafond 12 ko).
- [ ] `npm run build` vert.

---

## Périmètre

### Hors scope (explicite)

- Le desktop (≥ 820 px) : inchangé.
- SF-158-12 (en-tête compact ⋯), SF-158-13 (composeur ancré en bas + décisions empilées),
  SF-158-14 (moment Teams en colonne) : ergonomie fine, lot suivant.
- Toute logique métier, endpoint, migration, DTO, composant cluster. Pur frontend, display-only.
- Toute modification de `docs/PRODUCT_SPEC.md` (étape 6, séparée).

---

## Technique

### Endpoint(s)

Aucun. Pur frontend.

### Tables impactées

Aucune.

### Migration Liquibase

- [x] Non applicable

### Composants Angular

- `AtelierTerminalComponent` — feuille dédiée **existante** `atelier-terminal-mobile.component.scss` :
  on y **étend** le bloc `@media (max-width: 819px)` (règles des rangées de contrôle). Les
  `<mat-button-toggle>` sont écrits dans le template du terminal (ils portent l'attribut `_ngcontent`
  du composant) → ciblables sans `::ng-deep`. **Aucune modification HTML.** **Aucune modification de la
  feuille principale.**

---

## Plan de test

### Tests unitaires (composant)

- [ ] `terminal-largeur-reelle.spec.ts` (existant, SF-158-10) — **étendu** : monte un tour avec les
      rangées de contrôle (target + mode + état du poste, `executionTarget='RUNNER'`, `hostName` posé,
      `runnerStatus` non connecté/appairé) dans un hôte de 360 px, applique le patron mobile au layout
      réel, et asserte :
  - `host.scrollWidth <= host.clientWidth` (page ne défile pas, contrôles réels présents),
  - avec une commande de relance volontairement très longue, la rangée d'état du poste enroule
    (`row.scrollWidth <= row.clientWidth`) — sensible au `word-break: break-all` (rouge-avant/vert-après),
  - les toggle-groups target/mode remplissent la largeur de leur rangée (sensible à `width: 100%`).
- [ ] `terminal-contenu-responsive.spec.ts` (existant) reste vert ; complété par la vérification CSSOM
      des nouvelles règles (toggle pleine largeur, `word-break`, `min-width:0`).

### Tests d'intégration

- Non applicable (pur affichage CSS ; layout vérifié par le test DOM réel).

### Isolation workspace

- [x] Non applicable — raison : aucun accès données, pur affichage CSS.

---

## Dépendances

### Subfeatures bloquantes

- **SF-158-10** (containment + test de largeur) — livrée (PR #900). SF-158-11 étend son test.

### Questions ouvertes impactées

- Aucune.

---

## Préoccupations transversales

- **Navigation / routing** : non touchée. Aucun nouveau chemin, guard ni redirection. Composants de
  navigation impactés : **aucun**.
- **Auth / tenant / plans / limites** : non touchés (pur affichage). Aucun accès données modifié.

---

## Notes et décisions

- **D1** : les `<mat-button-toggle>` du template terminal portent l'attribut `_ngcontent` du composant →
  `flex: 1` s'applique sans `::ng-deep`. Le groupe (`inline-flex` par défaut de Material) + `width: 100%`
  → rangée pleine largeur, options à parts égales.
- **D2** : `.terminal-command` est `display: flex` → `code` est un item flex ; `min-width: 0` y est
  significatif (permet l'enroulement au lieu du débordement).
- **Budget SCSS (F-117)** : règles dans la feuille dédiée mobile, feuille principale intacte. Aucune
  couleur nouvelle.
