# Cadrage F-197 — Bouton d'amendement de plan : `{{ }}` affichés bruts

**Demande PO (2026-10-15)**, livraison autonome (vague). Constat vérifié sur `origin/main` 6e16f2c8 et en prod. Justesse avant coût ; charte `DESIGN_SYSTEM.md` (aucune couleur nouvelle) ; préfixe système stable (rien de volatil).

## Besoin
En mode guidé, le bouton d'amendement de plan affiche l'expression Angular brute `{{ p.amendment ? … }}`.

## Cause (prouvée)
`frontend/src/app/atelier/terminal/terminal-journey-strip.component.ts:173` — template inline en backticks : `'Valider l\'amendement et reprendre'` ; dans un template littéral JS `\'` devient `'`, Angular reçoit un nombre impair d'apostrophes, ne trouve pas la fin de l'interpolation et rend le texte brut. Même défaut `terminal-journey-chip.component.ts:54` (`changement d\'infra`). Les tests (`terminal-journey.spec.ts:144`, `terminal-journey-resume.spec.ts:104`) ne vérifient qu'un `toContain` et passent sur le texte brut.

## Décisions
- Sortir les libellés en `computed`/constantes TS (pas d'apostrophe échappée dans un template inline).
- **Garde** : un test (ou un script de vérification au build) qui échoue si un template rendu contient `{{` — balayage de tous les composants à template inline contenant `\'` à l'intérieur d'une interpolation, et assertion `not.toContain('{{')` sur les rendus du parcours.

## Découpage
| SF | Objet | Risque |
|---|---|---|
| SF-197-01 | Correctif des deux templates + tests `not.toContain('{{')` + balayage des autres templates inline pour le même motif (corriger tout cas trouvé) | 🟢 |
