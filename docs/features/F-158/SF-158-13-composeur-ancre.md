# Mini-spec — F-158 / SF-158-13 Composeur ancré en bas + décisions empilées + gouttières

## Identifiant

`F-158 / SF-158-13`

## Feature parente

`F-158` — Écrans de contenu responsive (mobile)

## Statut

`ready`

## Date de création

2026-09-26

## Branche Git

`feat/SF-158-13-composeur-ancre`

---

## Objectif

Sur téléphone (< 820 px), ancrer la zone de saisie du terminal en bas de l'écran (champ pleine
largeur au-dessus d'une rangée d'icônes joindre/dicter/envoyer), empiler les boutons de décision
d'autorisation en pleine largeur, et rendre au fil ses gouttières de 16 px — le tout sans toucher le
desktop (≥ 820 px).

---

## Comportement attendu

### Cas nominal

- **Composeur (< 820 px)** : `.terminal-input` devient `position: sticky; bottom: 0` sur fond navy
  avec un `border-top`, retrait bas `env(safe-area-inset-bottom)`. Le champ (`.terminal-field`) passe
  **pleine largeur** en première ligne ; la rangée d'icônes (joindre, dicter) + le bouton **Envoyer**
  (poussé à droite) passe **sous** le champ. Les comportements existants — dictée (F-145), dépôt de
  fichiers, envoi, précision pendant un tour, @-mentions, slash-commands — sont **inchangés** (aucune
  modification du DOM/binding, style seul).
- **Décisions d'autorisation (< 820 px)** : `.terminal-ask-actions` passe en **colonne**, chaque
  bouton (Autoriser / Tout autoriser pour ce message / Refuser / Confirmer le refus) **pleine
  largeur** et **≥ 46 px**.
- **Gouttières du fil (< 820 px)** : `.terminal-scrollback` repasse à **16 px** (`--cg-space-3`),
  annulant le rognage à 8 px introduit par SF-158-09.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Appareil sans encoche (`safe-area-inset-bottom` = 0) | Retrait bas nul, aucun espace superflu |
| Aucune décision en attente | `.terminal-ask-actions` absent, rien à empiler |
| Envoi/précision pendant un tour | Le libellé « Préciser » et l'envoi fonctionnent comme avant |

---

## Critères d'acceptation

- [ ] < 820 px : `.terminal-input` est `position: sticky; bottom: 0`, fond navy, `border-top`, avec
      `env(safe-area-inset-bottom)`.
- [ ] < 820 px : le champ est pleine largeur au-dessus, la rangée joindre/dicter/Envoyer est en
      dessous, Envoyer poussé à droite.
- [ ] < 820 px : les boutons de décision sont empilés pleine largeur, ≥ 46 px (`flex-direction: column`).
- [ ] < 820 px : les gouttières du fil valent 16 px (`--cg-space-3`).
- [ ] À 360/400 px, composeur et décisions ne débordent pas (`scrollWidth <= clientWidth`).
- [ ] Desktop (≥ 820 px) inchangé : toute règle nouvelle est sous `@media (max-width: 819px)`.
- [ ] `npm run build` vert ; feuille principale du terminal intacte (11.97 kB) ; styles dans la
      feuille mobile dédiée (< 12 kB) ; jetons `--cg-*` uniquement, aucune couleur nouvelle.

---

## Périmètre

### Hors scope (explicite)

- L'en-tête compact + menu ⋯ (SF-158-12, livré).
- La peau du fil / des messages et le moment Teams en colonne (SF-158-14).
- Toute modification desktop, tout backend/endpoint/migration/composant cluster.
- Toute modification de la LOGIQUE de saisie/dictée/dépôt (style seul).

---

## Technique

### Composants Angular

- `atelier-terminal-mobile.component.scss` (feuille mobile dédiée, sous `@media (max-width: 819px)`) —
  composeur ancré, décisions empilées, gouttières 16 px. **Aucun changement de template ni de TS.**

### Tables impactées / Migration

Aucune. Pur frontend display-only.

---

## Préoccupations transversales

Aucune (auth / tenant / plans / routing non touchés). CSS seul dans la feuille mobile ; aucun
composant tiers impacté (le desktop est protégé par la garde `@media`).

---

## Plan de test

### Tests composant (Karma ChromeHeadless, `terminal-largeur-reelle.spec.ts`)

- [ ] À 360/400 px, le composeur ne fait pas déborder la page (`scrollWidth <= clientWidth`).
- [ ] À 360/400 px, avec une décision en attente, les boutons empilés ne débordent pas et
      `.terminal-ask-actions` est en colonne (`getComputedStyle` `flex-direction: column`).
- [ ] `npm run build` vert.

### Isolation workspace

- [ ] Non applicable — composant de présentation, aucun accès données.

---

## Notes et décisions

- **Garantie desktop** : SF entièrement CSS sous `@media (max-width: 819px)` — aucune règle desktop
  touchée, aucun template/TS modifié.
- **Fidélité maquette** (`.composer`, `.ask .acts .btn`) : champ boxé (fond `--cg-navy-2` en creux
  sur le fond `--cg-primary` du terminal), Envoyer en pastille `--cg-accent` (jeton existant). Le
  prompt `$` (décoratif, `aria-hidden`) est masqué sur mobile comme dans la maquette.
