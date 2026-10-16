# Mini-spec — F-206 / SF-206-03 Cap : suites `{chantier, etape}` validées chantier par chantier

## Identifiant
`F-206 / SF-206-03` — Feature parente : F-206. Statut : `ready` — 2026-10-16 — Branche `feat/SF-206-03-cap-chantier-etape`. Dépend de SF-206-01/02 (mergées).

## Objectif
Une suite « Et maintenant ? » qui relève d'un autre chantier ouvert est rattachée à CE chantier (« Chantier N · Étape M ») au lieu d'être marquée « Hors plan » ; « Hors plan » est réservé à ce qui ne relève d'aucun chantier ouvert (décision PO).

## Comportement nominal
1. **Doctrine** (`NEXT_STEPS_DOCTRINE`, littéral stable) : en mode guidé, chaque option porte `"etape"` et, si l'étape relève d'un autre chantier que le focus, `"chantier": N`.
2. **Lecture** (`NextStepsBlock`) : `chantier` lu comme `etape` (entier, ou texte contenant un entier) ; jamais cru tel quel.
3. **Rattachement** (`ChantierAnchor.apply`), en chantier guidé ouvert (au moins un chantier ouvert portant un plan) :
   - chantier visé = `chantier` proposé, sinon le focus ;
   - chantier ouvert **et** étape valide dans **son** plan réel ⇒ `scope=CHANTIER`, `chantier=N`, `stepIndex`, `stepTitle` (titre lu dans le plan de N) ;
   - `chantier` proposé ≠ focus, ouvert, sans étape valide ⇒ `scope=CHANTIER`, `chantier=N`, sans étape (la suite relève de ce chantier) ;
   - sinon (chantier inconnu ou clos, étape hors plan du focus, rien) ⇒ approfondissement, **un seul**, en dernier.
   - Ordre : suites du focus, puis des autres chantiers (ordre du modèle), puis l'approfondissement.
4. **Avancement par chantier** : `nextStepsChantier` (focus) garde `{number, done, total}` et gagne `others: [{number, done, total}]` (autres chantiers ouverts portant un plan), absent s'il n'y en a pas — forme F-196 inchangée pour un chantier unique.

## Cas d'erreur
| Situation | Résultat |
|---|---|
| `chantier` d'un chantier clos ou inconnu | approfondissement (« Hors plan ») |
| `etape` hors du plan du chantier visé, `chantier` = focus | approfondissement |
| `chantier` illisible (« deux ») | ignoré ⇒ focus |
| Libre / aucun plan dans aucun chantier ouvert | suites sans rattachement (inchangé) |

## Contraintes de validation
`chantier` entier ≥ 1, `etape` entier 1…taille du plan du chantier visé ; titre d'étape ≤ 60 caractères (inchangé).

## Critères d'acceptation
- [ ] Focus 2, suite `{chantier:1, etape:1}` ⇒ `CHANTIER`, `chantier=1`, `stepIndex=1`, titre du plan du chantier 1.
- [ ] Suite `{chantier:1}` sans étape ⇒ `CHANTIER`, `chantier=1`, `stepIndex` absent.
- [ ] Suite `{chantier:7}` (inconnu) ⇒ `APPROFONDISSEMENT`.
- [ ] Un seul chantier : sortie identique à F-196 hormis `chantier` sur les suites rattachées (tests F-196 verts).
- [ ] `nextStepsChantier.others` porte l'avancement des autres chantiers ouverts.

## Plan de test
- Unitaires `ChantierAnchorTest` (ajouts) : les 4 cas ci-dessus, ordre, focus sans plan mais autre chantier avec plan.
- Unitaires `NextStepsBlockTest` : lecture de `chantier`.
- `AtelierChatServiceJourneyTest` / `AtelierChatServiceSystemPromptTest` : non-régression (doctrine stable, rattachement de bout en bout).
- Isolation : aucun accès donnée nouveau — les autres chantiers viennent du parcours du terminal du tour (`forTurn`, `user_id` + `workspace_id`).

## Impacts
- Composants : `NextStep` (champ `chantier`), `NextStepsBlock`, `ChantierAnchor` (`Progress.others`), `AtelierChatService.NEXT_STEPS_DOCTRINE`.
- Tables / endpoints : aucun (champs additifs dans le relevé `terminal_json` et l'événement `done`).
- Préoccupations transversales : aucune (☐ Auth ☐ Tenant ☐ Plans ☐ Navigation).

## Hors périmètre
Bouton « Basculer » et libellés à l'écran (SF-206-04).
