# Mini-spec — [F-158 / SF-158-09] Passe responsive du contenu du terminal Atelier

## Identifiant

`F-158 / SF-158-09`

## Feature parente

`F-158` — Écrans de contenu responsive (mobile)

## Statut

`in-progress`

## Date de création

2026-09-25

## Branche Git

`feat/SF-158-09-terminal-contenu-responsive`

---

## Objectif

> En une phrase : que fait cette subfeature ?

Sous ~819 px, le **contenu** du terminal Atelier (fil, ligne vivante, bandeau « plafond atteint »,
zone de saisie) ne provoque plus de **défilement horizontal de page** et reste utilisable au pouce ;
elle complète SF-151-03 (barre d'outils déjà faite) et laisse le desktop (≥ 820 px) strictement
inchangé.

---

## Comportement attendu

### Cas nominal

- Desktop (≥ 820 px) : rendu du terminal **inchangé** (aucune règle hors `@media (max-width: 819px)`).
- Téléphone (< 819 px) :
  - Le fil (`.terminal-scrollback`) **clôt l'axe horizontal** (`overflow-x: hidden`) : une ligne
    trop large est ramenée dans le cadre, jamais renvoyée à la **page**. Les blocs larges qui doivent
    défiler (diff `.terminal-diff-body`, bloc de code Markdown `pre`, tableau Markdown `table`)
    portent **déjà** leur propre `overflow-x: auto` et défilent **chez eux** (non-régression : rien
    n'est retiré). Gouttières du fil resserrées (`--cg-space-2`) pour rendre au bloc de code la
    largeur perdue sur un écran de ~400 px.
  - La **ligne vivante** (`.terminal-live` : spinner · action · étapes · tokens · chrono) passe à la
    ligne (`flex-wrap: wrap`) au lieu de pousser les chiffres hors champ.
  - Le **bandeau « plafond atteint »** (`.terminal-live-limit`) passe à la ligne ; le bouton
    « Racheter des tokens » (`.terminal-live-limit-retry`) tombe sous le texte et fait ≥ 44 px.
  - Le **champ de saisie** (`.terminal-field`) peut rétrécir sous la largeur de ses boutons
    (`min-width: 0`) : son gabarit intrinsèque ne force plus de débordement. Les boutons joindre /
    dictée / envoyer restent au pouce (≥ 44 px, déjà posé par SF-151-03).

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Bloc de code / diff très large | Défile **dans son cadre** (`overflow-x:auto` existant) ; la page ne défile jamais |
| Sortie de commande avec très longue ligne | Ramenée dans le fil (`word-break`/`overflow-x:hidden`) ; pas de scroll-x de page |
| Plafond de dépense atteint sur écran étroit | Le bandeau passe à la ligne ; le bouton de rachat reste tactile, pas d'écrasement du texte |
| Tour en cours (ligne vivante) avec gros compteurs | Les compteurs passent à la ligne, aucun débordement |

---

## Critères d'acceptation

- [ ] Sous 819 px, `.terminal-scrollback` porte `overflow-x: hidden` : la **page** ne défile jamais
      horizontalement à ~400 px.
- [ ] Les blocs larges (diff, `pre` Markdown, `table` Markdown) conservent leur `overflow-x:auto`
      isolé (non-régression : rien retiré).
- [ ] Sous 819 px, `.terminal-live` et `.terminal-live-limit` passent à la ligne (`flex-wrap`).
- [ ] Sous 819 px, `.terminal-live-limit-retry` fait ≥ 44 px ; les boutons de saisie restent ≥ 44 px.
- [ ] `.terminal-field` peut rétrécir (`min-width: 0`) sous 819 px.
- [ ] À ≥ 820 px, le rendu est identique à l'existant (aucune régression desktop).
- [ ] Aucune couleur/police hors `DESIGN_SYSTEM.md` ; jetons `--cg-*` uniquement (aucune couleur ajoutée).
- [ ] F-151 (barre d'outils, boutons de décision au pouce) intacte.
- [ ] Le build frontend passe (`npm run build`) — vert.

---

## Périmètre

### Hors scope (explicite)

- Le desktop (≥ 820 px) : inchangé.
- La coquille (nav globale, shell Atelier `280px 1fr` → 1 colonne) : déjà faite par F-151 (SF-151-02),
  non touchée. `atelier.component.scss` porte déjà son `@media (max-width: 819px)`.
- La barre d'outils du terminal et les boutons de décision : déjà faits par SF-151-03.
- Les autres écrans de F-158 (chat, postes, forge-rail, gouvernance, onboarding, rapports, admin/usage,
  space-pitch) — chacun une SF distincte.
- Toute logique métier, tout endpoint, toute migration, tout DTO. Pur frontend, display-only.
- Toute modification de `docs/PRODUCT_SPEC.md` (étape 6 groupée séparément).

---

## Technique

### Endpoint(s)

Aucun. Pur frontend.

### Tables impactées

Aucune.

### Migration Liquibase

- [x] Non applicable

### Composants Angular (si applicable)

- `AtelierTerminalComponent` — feuille dédiée **existante** `atelier-terminal-mobile.component.scss`
  (14ᵉ feuille, créée par SF-151-03 pour la raison du budget de build 12 ko de la feuille principale,
  cf. angular.json `anyComponentStyle`). On y **étend** le bloc `@media (max-width: 819px)` déjà
  présent (D5 : feuille dédiée si dépassement). **Aucune modification HTML** : les conteneurs de
  défilement des blocs larges existent déjà (diff SF-30-13, `pre`/`table` Markdown SF-30-14).
  **Aucune modification de la feuille principale** ni des 13 autres feuilles.

---

## Plan de test

### Tests unitaires (composant)

- [ ] `terminal-contenu-responsive.spec.ts` (nouveau) — garde-fou **indépendant du viewport** par la
      CSSOM : les styles du composant étant injectés par Angular dans le document, on inspecte les
      `document.styleSheets` et on vérifie qu'il existe une règle `@media (max-width: 819px)` qui :
  - clôt l'axe horizontal du fil (`terminal-scrollback` + `overflow-x`),
  - fait passer à la ligne la ligne vivante et le bandeau plafond (`flex-wrap`),
  - garde le rachat tactile (`terminal-live-limit-retry` + `min-height`).
- [ ] Non-régression : les blocs larges gardent leur `overflow-x` (diff, `pre`, `table`) — vérifié
      par le même balayage CSSOM (présence des règles `overflow-x:auto`).
- [ ] Les specs existantes du terminal restent vertes (aucun HTML touché).

### Tests d'intégration

- Non applicable (pur affichage CSS ; responsivité vérifiée par le build + la revue du `@media` +
  le garde-fou CSSOM).

### Isolation workspace

- [x] Non applicable — raison : aucun accès données, pur affichage CSS. Aucune requête, aucun filtre
      `user_id` touché.

---

## Dépendances

### Subfeatures bloquantes

- Aucune (SF indépendante et démo-able isolément, cf. CADRAGE F-158 §4). S'appuie sur SF-151-03
  (feuille mobile existante) déjà livrée.

### Questions ouvertes impactées

- Aucune.

---

## Préoccupations transversales

- **Navigation / routing** : non touchée. Aucun nouveau chemin, aucun guard, aucune redirection ;
  la même URL sert les deux tailles (display-only). Composants de navigation impactés : **aucun**
  (le fil terminal remplace toute la grille Atelier, la coquille n'est pas retouchée).
- **Auth / tenant / plans** : non touchés (pur affichage). Aucun accès données modifié.

---

## Notes et décisions

- **D1 (CADRAGE)** : réutilisation du point de rupture 819 px et de la forme responsive du
  DESIGN_SYSTEM (`_forge-layout-shell.scss:148`, `DESIGN_SYSTEM.md:686`). Aucune nouvelle route.
- **D4 (CADRAGE)** : jamais de scroll-x de page → le fil clôt l'axe horizontal (`overflow-x:hidden`),
  les **blocs larges** (diff, `pre`, `table`) gardent leur `overflow-x:auto` isolé (déjà en place).
- **Cibles ≥ 44 px** : `min-height: 44px` sur le rachat de tokens (les boutons de saisie et de
  décision le sont déjà par SF-151-03).
- **Budget SCSS (F-117 / D5)** : les règles vont dans la feuille dédiée mobile existante (1,5 ko →
  bien sous le seuil `anyComponentStyle` de 4 ko), pas dans la feuille principale (au plafond 12 ko).
  Aucune couleur nouvelle.
