# Mini-spec — F-197 / SF-197-01 Les `{{ }}` bruts du bouton d'amendement : correctif + garde (frontend)

## Identifiant
`F-197 / SF-197-01` — Feature parente : F-197 « Amendement de plan : `{{ }}` affichés bruts » (demande PO 2026-10-15, cadrage `CADRAGE-F-197-amendement-de-plan-accolades.md`).
Statut : `ready` — 2026-10-16 — Branche `feat/SF-197-01-accolades-templates-inline`. Aucune dépendance.

## Objectif
Le bouton d'amendement de plan et l'aide « Guidé » affichent leur libellé évalué, jamais l'expression Angular brute, et un build échoue si le motif réapparaît dans un template inline.

## Comportement attendu

### Cas nominal
1. **Bande du parcours (plan en attente de validation)** : le bouton affiche « Valider l'amendement et reprendre » (plan amendé) ou « Valider le plan et lancer » ; l'aide affiche « Le plan validé a été modifié : rien ne change chez vous avant votre validation. » ou « Rien ne sera modifié avant votre validation. ». Libellés portés par des `computed` (`validateLabel`, `validationHint`) dans `TerminalJourneyStripComponent`.
2. **Pastille du parcours (menu)** : l'entrée « Guidé » affiche « Chantier, incident, changement d'infra : on comprend, on planifie, puis on agit. » ou, s'il existe des chantiers clos et qu'on est en Libre, « Ouvre un nouveau chantier : … ». Libellé porté par `guideHint` (`computed`) dans `TerminalJourneyChipComponent`.
3. **Garde** : `scripts/verify-inline-templates.mjs` parcourt tous les composants à template inline (backticks) et échoue si une expression — interpolation `{{ }}`, liaison `[x]="…"`/`(x)="…"`, bloc `@if/@for/@switch (…)` — contient un `\'` que JavaScript cuit en `'` nu. Le `\\'` (échappement Angular correct) et le texte statique ne sont pas signalés. Branchée en `prebuild` (donc `npm run build` local et image Docker), avec son propre test (`verify-inline-templates.test.mjs`).

### Cas d'erreur
| Situation | Comportement |
|---|---|
| Un nouveau template inline réintroduit `'l\'x'` dans une expression | `npm run build` échoue avec fichier:ligne et la consigne « sortez le libellé en TS » |
| Template externe (`templateUrl`) | hors garde : `\'` y est lu tel quel par Angular, pas de défaut |

## Critères d'acceptation
- [ ] Plan amendé : `.journey-plan__validate` vaut exactement « Valider l'amendement et reprendre » ; aucun `{{` dans le rendu de la bande.
- [ ] Menu de la pastille : l'entrée Guidé contient « changement d'infra », sans `{{`.
- [ ] `npm run verify:templates` : OK sur `main` corrigé ; échoue sur le motif d'origine (test du script).
- [ ] Balayage : 56 templates inline vérifiés, seuls les 2 cas du cadrage trouvés (le `\\'` de `presentations-panel` est correct).

## Plan de test
- `terminal-journey.spec.ts` : égalité exacte des libellés amendés + `not.toContain('{{')` ; nouveau test du menu de la pastille (computed + rendu du menu ouvert).
- `terminal-journey-resume.spec.ts` : `not.toContain('{{')` sur le bouton amendé.
- `scripts/verify-inline-templates.test.mjs` : interpolation, multiligne, liaison, bloc de contrôle signalés ; `\\'`, texte statique, computed non signalés.
- Isolation utilisateur : sans objet (aucun accès données, aucune requête nouvelle).

## Impacts
- Tables : aucune. Endpoints : aucun. Migration : aucune.
- Composants : `terminal-journey-strip.component.ts`, `terminal-journey-chip.component.ts`, `frontend/package.json` (`prebuild`, `verify:templates`), `frontend/scripts/verify-inline-templates*.mjs`.
- Préoccupations transversales : Auth ☐ ; Tenant ☐ ; Plans/limites ☐ ; Navigation ☐.
- Charte : aucune couleur ni police nouvelle.

## Hors périmètre
Convertir les templates inline en fichiers externes ; vérifier les templates externes ; toute modification du flux d'amendement (F-176).
