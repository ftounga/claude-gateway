# Mini-spec — F-196 / SF-196-02 Le panneau Cap montre le chantier (frontend)

## Identifiant
`F-196 / SF-196-02` — Feature parente : F-196 « Cap au service du chantier » (décision PO 3).
Statut : `ready` — 2026-10-12 — Branche `feat/SF-196-02-cap-panneau-chantier`. Contrat importé de SF-196-01 (mergée, PR #1175).

## Objectif
Le panneau « Et maintenant ? » dit, en chantier guidé ouvert, à quelle étape chaque suite fait avancer le chantier, marque l'approfondissement « Hors plan », affiche l'avancement et prévient (sans bloquer) quand on coche un approfondissement.

## Comportement attendu

### Cas nominal (chantier ouvert : `nextStepsChantier` présent)
1. **En-tête** : à côté de « Et maintenant ? », une mention « Chantier : X/Y étapes » (X = `done`, Y = `total`).
2. **Étiquette de rattachement** sous le titre de chaque suite :
   - `scope = CHANTIER` ⇒ « Étape N · <stepTitle> » (neutre : texte secondaire, filet discret) ;
   - `scope = APPROFONDISSEMENT` ⇒ « Hors plan · approfondissement » en **ambre §12** (`#F9A825`, filet + texte, jamais un fond plein).
3. **Avertissement** : si au moins une suite cochée est un approfondissement, une ligne s'affiche au pied du panneau : « Le chantier n'est pas terminé : il reste N étapes » (N = `total − done`, singulier « 1 étape »). Elle n'empêche rien : la coche et la saisie fonctionnent comme avant. Lue par les lecteurs d'écran (`role="status"`).
4. **Le chantier en direct prime** : si le parcours du terminal est connu et n'est plus un chantier guidé ouvert (repassé en Libre, clos), aucune mention de chantier n'est affichée (pas d'en-tête, pas d'étiquette, pas d'avertissement).
5. **Transport** : `nextStepsChantier` est recopié du `done` SSE et relu de `terminal.nextStepsChantier` à l'historique.

### Libre / sans avancement
Panneau strictement identique à F-192 (aucun badge, aucun en-tête de chantier, aucun avertissement).

### Cas d'erreur
| Situation | Comportement |
|---|---|
| Backend antérieur (champs absents) | panneau F-192 inchangé |
| `scope` présent mais `nextStepsChantier` absent | aucune étiquette (le chantier n'est pas ouvert) |
| `stepTitle` absent sur une suite CHANTIER | « Étape N » seul |
| `total − done ≤ 0` avec approfondissement coché | pas d'avertissement |

## Contraintes de validation
Aucune saisie nouvelle. Couleurs : palette existante du panneau + ambre §12 `#F9A825` (déjà utilisé par l'étiquette Risque) — **aucune couleur nouvelle**. Polices Inter / JetBrains Mono. Espacements multiples de 4 px.

## Critères d'acceptation
- [ ] Chantier ouvert : en-tête « Chantier : 3/10 étapes ».
- [ ] Suite CHANTIER : « Étape 2 · Appliquer la policy NAP V12 ».
- [ ] Suite APPROFONDISSEMENT : « Hors plan · approfondissement », style ambre.
- [ ] Cocher l'approfondissement affiche « Le chantier n'est pas terminé : il reste 7 étapes » ; la saisie est quand même remplie.
- [ ] Libre : aucun de ces éléments.
- [ ] Parcours en direct clos/Libre : aucun de ces éléments même si le message les porte.
- [ ] `done` SSE et rechargement transportent `nextStepsChantier`.

## Plan de test
- `terminal-next-steps.component.spec.ts` (ajouts) : en-tête, étiquette d'étape, étiquette hors plan, avertissement à la coche (et coche effective), singulier, absence en Libre / sans chantier.
- `atelier.service.spec.ts` : le relais `done` recopie `nextStepsChantier`.
- Isolation : aucune nouvelle requête (le panneau n'appelle rien) ; lecture du parcours existant.

## Impacts
- Tables : aucune. Endpoints : aucun (consomme des champs additifs).
- Composants : `TerminalNextStepsComponent`, `AtelierTerminalComponent` (passe l'avancement filtré par le parcours en direct), `atelier.component.ts` (done + rechargement), `atelier.service.ts` (relais `done`), modèles `atelier.models.ts` / `atelier.types.ts`.
- Préoccupations transversales : Auth ☐ ; Tenant ☐ ; Plans/limites ☐ ; Navigation ☐.

## Hors périmètre
Séparateur de résolution (SF-196-03) ; interdire les approfondissements ; mode Libre.
