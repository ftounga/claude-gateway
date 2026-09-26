# Mini-spec — [F-158 / SF-158-20] La « Note poste » repliée en mini-bouton icône sur téléphone

## Identifiant

`F-158 / SF-158-20`

## Feature parente

`F-158` — Écrans de contenu responsive (mobile)

## Statut

`ready`

## Date de création

2026-09-26

## Branche Git

`feat/SF-158-20-note-poste-mini-bouton-mobile`

---

## Objectif

Sur téléphone (≤ 819 px), réduire l'état replié du rappel « Note poste »
(`app-workstation-notice`) d'une **pastille large icône + libellé** (~200 px) à un **mini-bouton
icône seul** (le bouclier, ~48 px, coin bas-gauche) pour qu'il ne recouvre quasiment plus le début
du rail « Vos questions », tout en restant tappable pour déployer la notice.

---

## Comportement attendu

### Cas nominal

- **Téléphone (≤ 819 px), notice due, état replié** : la surface fixe en bas-gauche est un
  **bouton icône seul** (bouclier `policy`), carré ~48 px (cible tactile ≥ 44 px), sans le libellé
  texte « Note poste ». Empreinte comparable au bouton d'aide « ? » (`help-chat-widget`) en
  bas-droite — côtés opposés, aucun chevauchement mutuel.
- **Tap sur le mini-bouton** → déploie la notice complète (comportement SF-158-17 inchangé :
  `expand()`), avec bouton réduire pour revenir à l'icône.
- La remontée au-dessus du composeur ancré (SF-158-19,
  `bottom: calc(112px + env(safe-area-inset-bottom))`) est **conservée**.
- **Desktop (≥ 820 px)** : rigoureusement inchangé — la chip et le bouton réduire restent en
  `display:none` hors du point de rupture ; le bandeau reste rendu quel que soit l'état.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Notice non due (rappel récent / « jamais ») | Rien n'est rendu (`visible()` faux) — inchangé. |
| Viewport desktop (≥ 820 px) | Aucune règle mobile appliquée : rendu desktop identique. |
| Lecteur d'écran sur le mini-bouton | Le nom accessible reste porté par l'`aria-label` du bouton (« Déployer le rappel : journalisation du poste ») — le libellé masqué reste dans le DOM. |

---

## Critères d'acceptation

- [ ] Sous 819 px, l'état replié est un bouton **icône seul** : le libellé `.notice-chip-label` est
      masqué (`display:none`).
- [ ] Le mini-bouton est carré ~48 px (largeur et hauteur), cible tactile ≥ 44 px.
- [ ] Le tap sur le mini-bouton déploie toujours la notice complète (SF-158-17 intact).
- [ ] La remontée SF-158-19 (`bottom: calc(112px + env(safe-area-inset-bottom))`) est conservée.
- [ ] L'`aria-label` du bouton est conservé (nom accessible « Note poste » / journalisation).
- [ ] Desktop (≥ 820 px) strictement inchangé : chip en `display:none` hors `@include bp.phone`,
      aucune règle hors point de rupture.
- [ ] Jetons `--cg-*` uniquement, aucune couleur nouvelle, feuille < 12 ko.

---

## Périmètre

### Hors scope (explicite)

- Le desktop (≥ 820 px).
- Le guide d'accueil « Vos premiers pas » (F-53) — autre composant, non touché.
- Le bouton d'aide « ? » (F-54 / SF-158-19) — déjà en FAB icône, non touché.
- Toute logique TS / service / persistance (`WorkstationNoticeService`) — aucun changement.
- Le contenu, le ton et les actions de la notice déployée (« Compris » / « Me le rappeler »).

---

## Technique

### Composants Angular

- `WorkstationNoticeComponent` (`frontend/src/app/atelier/notice/workstation-notice.component.*`) :
  - **`.scss`** : sous `@include bp.phone`, réduire `.notice--collapsed .notice-chip` à un carré
    ~48 px centré (icône seule) et masquer `.notice--collapsed .notice-chip-label`.
  - **`.html`** : inchangé (le libellé reste dans le DOM, masqué en CSS — préserve le nom lisible et
    le test SF-158-17 `textContent`).
  - **`.ts`** : inchangé.

### Tables impactées

Aucune. Aucun endpoint, aucune migration.

### Migration Liquibase

- [x] Non applicable

---

## Préoccupations transversales

- **Auth / Principal** : non concerné.
- **Contexte tenant** : non concerné.
- **Plans / limites** : non concerné.
- **Navigation / routing** : non concerné (surface `position:fixed`, aucune route).

Aucune préoccupation transversale cochée → pas de liste d'impact requise.

---

## Plan de test

### Tests unitaires (Karma, `workstation-notice.component.spec.ts`)

- [ ] Non-régression SF-158-17 : la chip existe repliée, le tap déploie, le bouton réduire referme,
      « Compris » reste joignable (tests existants inchangés — le libellé masqué reste dans
      `textContent`).
- [ ] Non-régression SF-158-19 : `bottom: calc(112px + env(safe-area-inset-bottom))` toujours
      présent dans la CSSOM mobile.
- [ ] Nouveau (garde-fou CSSOM, patron SF-158-19) : sous 819 px, `.notice-chip-label` est en
      `display:none` et `.notice--collapsed .notice-chip` fait 48 px de large et de haut.

### Tests d'intégration

- [ ] Non applicable (pur frontend, display-only, aucun endpoint).

### Isolation workspace

- [x] Non applicable — raison : aucun accès aux données, style seul.

---

## Notes et décisions

- La surface visible (fond `--cg-surface`, bordure `--cg-divider`, ombre, rayon 12 px) est déjà
  portée par `.notice--collapsed` (l'`<aside>`) ; le bouton `.notice-chip` reste transparent et
  centre simplement le bouclier — l'`<aside>` s'ajuste (`width:auto`) au carré ~48 px. Aucune
  nouvelle valeur de couleur ni d'ombre introduite.
- Le libellé « Note poste » est masqué en CSS (et non retiré du DOM) : il reste le nom lisible en
  secours et n'apparaît jamais sur desktop (la chip entière y est en `display:none`).
