# F-158 — Écrans de contenu responsive (mobile)

> Cadrage du 2026-09-25, à la demande du PO (« la navigation est mieux, mais ce n'est pas
> responsive : l'écran chat est une horreur sur téléphone, impossible à utiliser »).
>
> **Ce document cadre ; il ne livre aucun code.** Suite de F-151 (Atelier mobile) : F-151 a rendu
> responsive la **coquille** (nav globale → hamburger, shell Atelier, barre d'outils du terminal),
> mais **pas les écrans de contenu**. F-158 étend le même patron mobile à ces écrans. **Pur frontend.**

## 0. Objectif

Rendre l'application **utilisable sur un téléphone** (S23, ~400 px) écran par écran de **contenu**.
Sous ~819 px, les grilles multi-colonnes à largeur fixe passent en **une colonne** (les barres
latérales fixes deviennent pleine largeur empilée ou un repli), **jamais de scroll horizontal**, cibles
tactiles ≥ 44 px. **Le desktop (≥ 820 px) reste inchangé.**

## 1. Ce qui existe déjà (vérifié — patron à ÉTENDRE, pas à inventer)

- **Le point de rupture fait autorité : 819 px.** `_forge-layout-shell.scss:148` bascule
  `grid-template-columns` → `minmax(0, 1fr)` sous **819 px**, avec « liste plein écran ↔ détail plein
  écran, même URL » (`DESIGN_SYSTEM.md:686`). La Vigie applique la même forme par paliers 640/860/1020 px
  (`DESIGN_SYSTEM.md:777`). **C'est ce patron que F-158 réutilise partout.**
- **La coquille est déjà responsive (F-151, livrée)** : nav globale → hamburger (SF-151-01/04), shell
  Atelier `280px 1fr` → 1 colonne (SF-151-02), barre d'outils du terminal + boutons de décision au pouce
  (SF-151-03, feuille dédiée `atelier-terminal-mobile.component.scss`). **On ne retouche pas la coquille.**
- **Le budget de feuilles SCSS s'applique (F-117)** : la feuille principale est au budget de build
  (`angular.json`). Toute règle mobile qui ferait dépasser le budget d'un composant va dans une
  **feuille dédiée** ajoutée à `styleUrls` (précédent SF-151-03).

## 2. Ce qui manque (constat, cité `fichier:ligne`, tous à **0 `@media`**)

| Écran | Fichier:ligne | Grille aujourd'hui | Symptôme < 819 px |
|-------|---------------|--------------------|-------------------|
| **Chat** | `chat/chat.component.scss:9` | `grid-template-columns: 280px 1fr` | sidebar fixe 280 px → il reste ~120 px au fil ; « une horreur » (mot du PO). |
| **Postes (Forge)** | `postes/postes.component.scss:253` | `repeat(auto-fill, minmax(232px, 1fr))` | cartes à min 232 px → débordement/scroll-x sous 400 px. |
| **forge-rail** | `postes/forge-rail/forge-rail.component.scss:102` | `32px minmax(0,1fr) auto` | ligne à 3 colonnes + actions → serrage et débordement au doigt. |
| **Gouvernance** | `governance/governance.component.scss:171` | `repeat(auto-fill, minmax(min(320px,100%),1fr))` | 0 `@media` — à revoir empilement + cibles tactiles. |
| **Onboarding** | `onboarding/onboarding.component.scss:76` | `repeat(auto-fit, minmax(240px,1fr))` | min 240 px → resserrement sous 400 px. |
| **Rapports** | `reports/reports.component.scss:43` | `repeat(auto-fit, minmax(200px,1fr))` | grille de tuiles + tableaux → risque de scroll-x. |
| **Admin / usage** | `admin/usage/admin-usage.component.scss:36` | `repeat(auto-fit, minmax(180px,1fr))` | tuiles + tableau d'usage → débordement horizontal. |
| **space-pitch** | `shared/space-pitch/space-pitch.component.scss:45` | `repeat(auto-fit, minmax(200px,1fr))` | argumentaire empilé mal cadré sur téléphone. |
| **Terminal Atelier (contenu)** | `atelier/terminal/atelier-terminal.component.*` | — | F-151 a traité la **barre d'outils** ; le **contenu** (fil, blocs de code, cartes, canvas) reste à vérifier/passer responsive (débordement des blocs larges → scroll-x). |

## 3. Décisions de conception

- **D1 — Étendre le patron 819 px, ne rien inventer.** Réutiliser le point de rupture et la forme
  « colonnes fixes → 1 colonne empilée » du DESIGN_SYSTEM (`_forge-layout-shell.scss:148`). Aucune
  nouvelle route ; la même URL sert les deux tailles.
- **D2 — Charte stricte.** Jetons `--cg-*` uniquement, aucune couleur/police hors `DESIGN_SYSTEM.md` ;
  or de marque réservé aux gestes ; cibles tactiles ≥ 44 px.
- **D3 — Zéro régression desktop.** Tout est sous `@media (max-width: 819px)` (ou paliers dédiés) ; le
  rendu ≥ 820 px est **inchangé**. Aucune logique métier touchée.
- **D4 — Jamais de scroll horizontal.** Grilles → 1 colonne ; les **tableaux, blocs de code et
  diagrammes** larges vont dans un conteneur `overflow-x:auto` isolé, jamais la page.
- **D5 — Budget SCSS (F-117).** Si les règles mobile font dépasser le budget d'un composant → **feuille
  dédiée** ajoutée à `styleUrls` (précédent SF-151-03). Sinon, dans la feuille du composant.
- **D6 — Pur frontend, display-only.** Aucun endpoint, aucune migration, aucun DTO, aucun changement de
  protocole runner, **aucun composant cluster**. Gateway-First et Provider Independence intactes par
  construction.

## 4. Découpage en subfeatures (9)

| SF | Titre | Contenu | Impact |
|----|-------|---------|--------|
| **SF-158-01** | Chat responsive | `chat.component.scss:9` : sous 819 px, `280px 1fr` → 1 colonne ; sidebar (liste des conversations) → repli/plein écran ; le fil prend toute la largeur, cibles tactiles ≥ 44 px. **L'écran du besoin.** | `chat.component.{scss,html,ts}` + spec |
| **SF-158-02** | Postes (grille Forge) responsive | `postes.component.scss:253` : la grille de cartes s'empile proprement sous 819 px, aucune carte plus large que l'écran, aucun scroll-x. | `postes.component.{scss,html}` + spec |
| **SF-158-03** | forge-rail responsive | `forge-rail.component.scss:102` : la ligne `32px minmax(0,1fr) auto` reste lisible et tactile au doigt (actions atteignables sans débordement) sous 819 px. | `forge-rail.component.{scss,html}` + spec |
| **SF-158-04** | Gouvernance responsive | `governance.component.scss:171` : grille de paquets → 1 colonne sous 819 px, cibles tactiles ≥ 44 px, aucun scroll-x. | `governance.component.{scss,html}` + spec |
| **SF-158-05** | Onboarding responsive | `onboarding.component.scss:76` : étapes/cartes empilées sous 819 px, parcours confortable au doigt. | `onboarding.component.{scss,html}` + spec |
| **SF-158-06** | Rapports responsive | `reports.component.scss:43` : tuiles → 1 colonne ; **tableaux** dans un conteneur `overflow-x:auto` isolé (la page ne défile jamais horizontalement). | `reports.component.{scss,html}` + spec |
| **SF-158-07** | Admin / usage responsive | `admin-usage.component.scss:36` : tuiles → 1 colonne ; tableau d'usage en conteneur défilant isolé ; cibles ≥ 44 px. | `admin-usage.component.{scss,html}` + spec |
| **SF-158-08** | space-pitch responsive | `space-pitch.component.scss:45` : argumentaire empilé et cadré sous 819 px. | `space-pitch.component.{scss,html}` + spec |
| **SF-158-09** | Passe responsive du contenu du terminal Atelier | Le **contenu** du terminal (fil, **blocs de code**, cartes, canvas, tableaux markdown) ne déborde plus sous 819 px : blocs larges en conteneur `overflow-x:auto` isolé, jamais de scroll-x de page. Complète SF-151-03 (barre d'outils déjà faite). Feuille dédiée existante `atelier-terminal-mobile.component.scss` à étendre (budget F-117). | `atelier/terminal/*` (SCSS/HTML) + spec |

**Ordre** : **SF-158-01 (chat) en premier** (l'écran explicitement cité par le PO), puis 02 → 09 (chaque
SF est indépendante et démo-able isolément). L'ordre 02→09 n'est pas contraignant.

## 5. Préoccupations transversales

- **Navigation / routing** : SF-158-01 introduit un repli d'affichage de la sidebar chat **sans nouvelle
  route ni guard** (même URL aux deux tailles, patron `/forge`). Analyse d'impact à confirmer en
  mini-spec de SF-158-01 : lister les chemins de navigation liste↔fil et vérifier qu'ils restent
  joignables au format replié. Les autres SF sont display-only (aucune navigation touchée).
- **Auth / tenant / plans** : non touchés (pur affichage).

## 6. Garde-fous

Gateway-First, Provider Independence, isolation `user_id` (aucun accès données touché), DESIGN_SYSTEM
strict (jetons `--cg-*`, cibles ≥ 44 px), budget SCSS F-117 (feuille dédiée si dépassement), **aucun
composant cluster**, aucune migration, aucun endpoint, aucun changement runner, **jamais de scroll-x**.

## 7. Hors périmètre

- Toute modification du **desktop** (≥ 820 px inchangé).
- La coquille déjà rendue responsive par **F-151** (nav globale, shell Atelier, barre d'outils terminal).
- Toute logique métier, tout endpoint, toute migration.
- L'installabilité PWA (**F-152**) et les notifications (**F-153**).

## 8. Drapeaux

Aucun drapeau de déploiement (pur frontend, aucun secret, aucune migration) ; effet visible après
déploiement de l'image `frontend`.
