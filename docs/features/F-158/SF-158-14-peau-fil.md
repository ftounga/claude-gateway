# Mini-spec — F-158 / SF-158-14 Peau du fil (messages) mobile + moment Teams en colonne

## Identifiant

`F-158 / SF-158-14`

## Feature parente

`F-158` — Écrans de contenu responsive (mobile)

## Statut

`ready`

## Date de création

2026-09-26

## Branche Git

`feat/SF-158-14-peau-fil`

---

## Objectif

Sur téléphone (< 820 px), appliquer au fil du terminal la peau « pro » de la maquette (carte de la
demande, bloc de sortie/code qui défile dans sa boîte, coût aligné à droite de la ligne vivante) et
faire passer le moment Teams en **une colonne** (image au-dessus du texte) — en style seul, sans
toucher le DOM/binding ni le desktop (≥ 820 px).

---

## Comportement attendu

### Cas nominal

- **Carte de la demande (`.req`)** : `.terminal-prompt-line` devient une carte boxée (fond
  `--cg-navy-2`, coins arrondis, gouttière interne) — le caret garde son accent.
- **Bloc de sortie / code (`.out`)** : `.terminal-output` devient une boîte en creux (fond
  `--cg-navy`, filet, coins arrondis) qui **défile dans sa boîte** (`overflow-x: auto`) sans jamais
  faire défiler la page.
- **Ligne vivante (`.live`)** : le chrono/coût (`.terminal-live-elapsed`, accent) est poussé à
  droite (`margin-left: auto`), comme le coût de la maquette ; la ligne enroule déjà (SF-158-09).
- **Sous-agents groupés (`.subs`) / étapes (`.step`) / essentiel (`.ess`)** : déjà porteurs de la
  peau attendue par leurs feuilles dédiées (F-150 `.terminal-subagents` boxé à filet ; F-126
  `.terminal-essential` filet accent + voile doré ; `.terminal-command` compact). Aucune règle
  mobile nouvelle nécessaire — les gouttières 16 px rendues par SF-158-13 leur redonnent leur
  respiration.
- **Moment Teams (`.teams-moment`)** : sous 819 px il passe en **colonne** — l'image
  (`.teams-moment__shot`) ne reste pas dans une colonne fixe de 200 px mais occupe la pleine largeur,
  la phrase passe dessous. Feuille Teams dédiée (budget).

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Sortie très large (ligne mono insécable) | Défile DANS la boîte `.terminal-output` (overflow-x auto), la page ne défile pas |
| Moment Teams sans image | La phrase et l'heure suffisent, pas de colonne image vide |
| Terminal Teams comme terminal de poste | La carte/boîte du fil s'appliquent au terminal de poste ; la peau Teams garde ses propres jetons |

---

## Critères d'acceptation

- [ ] < 820 px : `.terminal-prompt-line` est une carte boxée (fond `--cg-navy-2`, radius, padding).
- [ ] < 820 px : `.terminal-output` est une boîte en creux (fond `--cg-navy`, filet, radius) avec
      `overflow-x: auto` ; une sortie très large ne fait pas déborder la page.
- [ ] < 820 px : le chrono/coût de la ligne vivante est aligné à droite.
- [ ] < 820 px : `.teams-moment` est en colonne (image pleine largeur au-dessus du texte, plus de
      colonne fixe 200 px).
- [ ] Desktop (≥ 820 px) inchangé : toute règle nouvelle est sous `@media (max-width: 819px)`.
- [ ] `npm run build` vert ; feuille principale du terminal intacte (11.97 kB) ; styles du fil dans
      la feuille mobile dédiée, moment Teams dans la feuille Teams dédiée (chacune < 12 kB) ; jetons
      `--cg-*` uniquement, aucune couleur nouvelle.

---

## Périmètre

### Hors scope (explicite)

- L'en-tête compact (SF-158-12) et le composeur/décisions (SF-158-13), livrés.
- Toute modification de la LOGIQUE du fil (rendu Markdown, groupement des sous-agents, essentiel) —
  style seul, DOM inchangé.
- Toute modification desktop, tout backend/endpoint/migration/composant cluster.

---

## Technique

### Composants Angular

- `atelier-terminal-mobile.component.scss` — carte de la demande, boîte de sortie, ligne vivante,
  sous `@media (max-width: 819px)`.
- `atelier-terminal-teams.component.scss` — `.teams-moment` en colonne sous `@media (max-width: 819px)`.
- **Aucun changement de template ni de TS.**

### Tables impactées / Migration

Aucune. Pur frontend display-only.

---

## Préoccupations transversales

Aucune (auth / tenant / plans / routing non touchés). CSS seul, deux feuilles dédiées ; desktop
protégé par la garde `@media`.

---

## Plan de test

### Tests composant (Karma ChromeHeadless, `terminal-largeur-reelle.spec.ts`)

- [ ] À 360/400 px, avec un tour rendu (demande + sortie), le fil ne fait pas déborder la page
      (`scrollWidth <= clientWidth`).
- [ ] `.terminal-output` porte `overflow-x: auto` sous le patron mobile (la boîte défile chez elle).
- [ ] `npm run build` vert.

### Isolation workspace

- [ ] Non applicable — composant de présentation, aucun accès données.

---

## Notes et décisions

- **Garantie desktop** : SF entièrement CSS sous `@media (max-width: 819px)` (feuilles mobile + Teams
  dédiées) — aucune règle desktop touchée, aucun template/TS modifié.
- **Fidélité maquette** : la ligne vivante de la maquette montre un « coût » en euros à droite ; le
  vrai terminal n'a pas de coût euro DANS la ligne vivante (le coût par tour vit dans `.terminal-cost`,
  le budget/coût projet dans la barre). On aligne donc à droite le chrono (`.terminal-live-elapsed`,
  déjà en accent), qui joue ce rôle — aucun binding/logique ajouté. Les blocs `.subs`/`.step`/`.ess`
  gardent la peau de leurs feuilles dédiées (déjà conforme), pour ne pas dupliquer de style ni gonfler
  le budget.
