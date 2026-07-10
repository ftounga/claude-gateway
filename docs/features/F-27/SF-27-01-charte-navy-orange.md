# Mini-spec — [F-27 / SF-27-01] Refonte charte graphique navy / orange / crème

---

## Identifiant

`F-27 / SF-27-01`

## Feature parente

`F-27` — Refonte charte graphique (nouvelle identité navy / orange / crème appliquée à toute l'app ; abandon de l'indigo `--cg-primary`)

## Statut

`ready`

## Date de création

2026-07-10

## Branche Git

`feat/SF-27-01-charte-navy-orange`

---

## Objectif

> En une phrase : remplacer l'identité visuelle applicative « Indigo Tech » (`--cg-primary` indigo `#4338CA` / accent cyan `#06B6D4`) par l'identité de marque **navy / orange / crème** (déjà utilisée sur la landing) sur **toute** l'application, en modifiant les jetons centraux `--cg-*` et le thème Material, sans toucher au comportement fonctionnel.

---

## Comportement attendu

### Cas nominal

Feature **100 % frontend, purement visuelle**. Aucun endpoint, aucune donnée, aucun flux métier modifié.

1. Les jetons de design centraux définis dans `frontend/src/styles.scss` (`:root`) sont redéfinis :
   - `--cg-primary` : indigo `#4338CA` → **navy `#0B1020`** (surfaces structurelles : header/toolbar, bulle message utilisateur, snackbar info).
   - `--cg-accent` : cyan `#06B6D4` → **orange `#E07B39`** (états actifs, liens, highlights, barres de progression, indicateur de nav actif).
   - `--cg-bg` : slate `#F8FAFC` → **crème `#F5EFE3`** (fond de page).
   - Ajout de jetons de marque explicites : `--cg-navy`, `--cg-navy-2`, `--cg-orange`, `--cg-orange-2`, `--cg-cream`.
   - Jetons inchangés : `--cg-surface` (blanc), `--cg-error`, `--cg-success`, `--cg-text-primary`, `--cg-text-secondary`, `--cg-divider`, typographies, espacements.
2. Le thème Angular Material (`mat.theme`) passe des palettes `violet` (primary) / `cyan` (tertiary) aux palettes **`orange`** (primary → boutons `color="primary"` = action de marque) / **`azure`** (tertiary).
3. Le badge `.badge--info` (seule teinte indigo résiduelle hors landing, `#EEF2FF`) passe à une teinte neutre froide compatible navy.
4. Tous les composants qui consomment `var(--cg-primary)` / `var(--cg-accent)` / `var(--cg-bg)` (14 + 6 usages recensés) héritent automatiquement de la nouvelle identité — aucune valeur indigo/cyan codée en dur ailleurs (vérifié par grep).
5. `docs/DESIGN_SYSTEM.md` (source de vérité) est mis à jour : palette, header, side-nav, snackbar info, addendum « Logo & marque », section interdits.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Palette Material `orange`/`azure` introuvable au build | `npm run build` échoue → corriger le nom de palette avant push (build vert obligatoire) |
| Contraste insuffisant texte/fond après changement | Conserver texte blanc sur navy, texte navy/ink sur crème/blanc ; vérifié visuellement |
| Régression d'un composant reposant sur une valeur indigo codée en dur | Aucune (grep : seul `styles.scss` portait `#4338CA`/`#06B6D4`/`#EEF2FF`) |

---

## Critères d'acceptation

- [ ] `--cg-primary` = `#0B1020` (navy), `--cg-accent` = `#E07B39` (orange), `--cg-bg` = `#F5EFE3` (crème) dans `styles.scss`.
- [ ] Plus aucune occurrence d'indigo `#4338CA` ni de cyan `#06B6D4` ni de `violet-palette`/`cyan-palette` dans le code frontend.
- [ ] Le thème Material utilise les palettes `orange` (primary) et `azure` (tertiary).
- [ ] Le header/toolbar (`.app-bar`) s'affiche en navy, les boutons `color="primary"` en orange, le fond de page en crème.
- [ ] Le badge `.badge--info` n'utilise plus de teinte indigo `#EEF2FF`.
- [ ] `docs/DESIGN_SYSTEM.md` reflète la nouvelle palette (source de vérité cohérente avec le code).
- [ ] `npm run build` vert et `npm test` vert (aucune régression de spec).
- [ ] Aucune police hors {Space Grotesk, Inter, JetBrains Mono} ; espacements ×4px inchangés.

---

## Périmètre

### Hors scope (explicite)

- Aucun changement backend, endpoint, table, migration.
- Aucun changement de layout, de structure de composant, de typographie ou d'espacement.
- Le logo et le nom de marque (« Claude Proxy », `claude-proxy-logo.png`) restent inchangés (déjà navy/orange).
- La landing (`landing.component.scss`) déjà à l'identité de marque : pas de refonte requise (ses variables SCSS locales restent valables ; aucune régression).
- Pas de thème sombre, pas de sélecteur de thème (hors périmètre F-27).

---

## Technique

### Endpoint(s)

Aucun.

### Tables impactées

Aucune. **Aucune migration Liquibase.**

### Composants Angular impactés (analyse transversale — préoccupation « navigation/UI globale »)

Changement centralisé sur les **jetons** ; composants héritant sans modification de code (via `var(--cg-*)`) :
`shell` (header navy), `chat` (bulle user navy, chip, indicateur accent orange), `ask`, `templates`, `documents`, `billing`, `reports` (barres orange), `settings`, `onboarding` (dégradé navy→orange), `admin`, `auth/*` (logo navy, liens orange via `--cg-primary`/`accent` du `styles.scss` global), dialogues partagés.
Fichiers **modifiés** : `frontend/src/styles.scss` (jetons + thème Material + badge info), `docs/DESIGN_SYSTEM.md`.

### Migration Liquibase

- [x] Non applicable

---

## Plan de test

### Tests unitaires / specs

- [ ] `npm test` (suite existante ~191 specs) reste **vert** : la refonte ne touchant pas la logique, aucune spec ne doit casser (garde-fou de non-régression).
- [ ] `npm run build` (compilation SCSS + Angular) **vert** : valide que les palettes Material `orange`/`azure` existent et que le SCSS compile.

### Vérifications visuelles / statiques

- [ ] `grep` post-modif : 0 occurrence de `#4338CA`, `#06B6D4`, `violet-palette`, `cyan-palette`, `#EEF2FF` dans `frontend/src`.
- [ ] Header navy + boutons orange + fond crème cohérents avec `DESIGN_SYSTEM.md`.

### Isolation utilisateur

- [x] Non applicable — feature purement présentationnelle, aucun accès données.

---

## Dépendances

### Subfeatures bloquantes

Aucune (toutes les features UII sont livrées ; F-27 est une passe transversale).

### Questions ouvertes impactées

Aucune (OPEN_QUESTIONS non impacté).

---

## Notes et décisions (arbitrages réversibles)

- **Découpage en une seule SF** : refonte de jetons = slice vertical cohérent, sans surface d'API (précédents F-15/F-19/F-22/F-26 mono-SF). Réversible.
- **Mapping des rôles de couleur** : `--cg-primary` = navy (structure : header, bulle user, snackbar info) ; `--cg-accent` = orange (action/état actif). La palette Material **primary = orange** pour que les boutons `color="primary"` portent la couleur d'action de marque (comme les CTA de la landing), tandis que le navy reste la couleur structurelle pilotée par CSS custom. Sémantique documentée dans `DESIGN_SYSTEM.md`. Réversible (inverser primary/accent ou choisir azure pour les boutons est un changement d'une ligne).
- **Fond crème** (`--cg-bg`) : cartes/surfaces restent blanches (`--cg-surface`) et se détachent par l'ombre du design system. Réversible.
- **Aucun renommage de variable** : on conserve les noms `--cg-primary`/`--cg-accent` (14+6 usages) pour minimiser le churn ; seule leur valeur change. Réversible.
