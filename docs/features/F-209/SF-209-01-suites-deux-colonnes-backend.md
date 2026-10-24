# Mini-spec — [F-209 / SF-209-01] Les suites du chantier en deux colonnes — backend

## Identifiant
`F-209 / SF-209-01` — Feature parente `F-209` (Les suites du chantier en deux colonnes) — Statut `in-review` — Créée 2026-10-24
Branche : `feat/SF-209-01-suites-deux-colonnes` — cadrage : décisions 1, 2, 4, 7 (3, 5, 6 côté écran : SF-209-02). Dépend de F-208 / SF-208-01 (`depends_on`, mergée).

## Objectif
Quand un chantier est ouvert, la gateway range les suites proposées par l'agent en deux colonnes à plafonds séparés — « Chantier » ordonnée et jamais vide tant qu'une étape reste à faire, « Hors chantier » pour les messages, mails, approfondissements et un autre chantier — et calcule pour chaque option ce qui bloque son étape et ses prérequis.

## Comportement nominal
- **Consigne** `NEXT_STEPS_DOCTRINE` (littéral fixe, préfixe stable) : en guidé, deux colonnes, « Chantier » 2 à 4 options avec `etape`, « Hors chantier » 0 à 3 (message ou mail hors plan, approfondissement, au plus une option d'un autre chantier), 7 au plus ; une étape bloquée n'est pas « à faire » ; colonne Chantier jamais vide tant qu'une étape reste. Hors guidé : 2 à 4, inchangé.
- **Parseur** `NextStepsBlock.MAX_STEPS` 4 → **7** (lecture) ; plafonds appliqués au rattachement.
- **`NextStep`** gagne `column` (`CHANTIER` | `HORS`, absent hors chantier ouvert), `blockedBy` (« étape 3 », « attente cert-gino ») et `prerequisites[{step, title, risk, level}]`. Champs absents de la sérialisation quand vides.
- **`ChantierAnchor.apply(steps, journey, waits)`** :
  - colonne **Chantier** = suites rattachées à une étape du plan du focus, **au plus 4**, ordre imposé et stable : étapes à faire débloquées (par numéro), puis étapes bloquées (l'option porte `blockedBy`), puis vérification de ce qui vient d'être fait (étape FAIT), puis le reste ;
  - colonne **Hors** = approfondissements et suites d'un autre chantier ouvert (**au plus 1**), **au plus 3**, ordre du modèle ;
  - **repli « jamais vide »** : en Exécution sur le plan validé, si aucune suite n'est rattachée au focus et qu'une étape reste à faire, option dérivée « Continuer : étape M » (première étape à faire débloquée, sinon première à faire ; demande « Chantier N — fais l'étape M : titre ») ;
  - **prérequis** de l'étape visée = ses `depends_on` non faits, **transitivement**, jamais devinés (sans `depends_on` : aucun) ; niveau = 1 + niveau max de ses propres prérequis ; deux EXTERNE ne partagent jamais un niveau (la seconde passe au niveau suivant) ; 8 au plus ;
  - sans chantier ouvert portant un plan : une seule colonne, **4 au plus**, sans `column` (inchangé).
- **`Progress`** (avancement exposé avec les suites) gagne `title` (en-tête « Chantier 4 — titre »).
- **État des attentes** : la boucle lit, best-effort, l'état des attentes des étapes (`SubjectJourneyService.waitsOn`) une fois en fin de tour ; une attente `A_FAIRE`/`DEMANDE` bloque l'étape. Même rattachement pour les suites par question (F-210).

## Cas d'erreur
| Situation | Comportement attendu |
|---|---|
| 8 options ou plus | 7 lues ; plafonds par colonne ensuite |
| Plus d'une option d'un autre chantier | la première gardée, les autres écartées |
| Attentes illisibles | aucune attente ne bloque ; les dépendances du plan, oui |
| Plan en attente de validation / Investigation | pas de repli « Continuer » |
| Mode Libre / Clos / guidé sans plan | 4 au plus, une colonne, aucun champ nouveau |
| Bloc illisible | aucune suite (inchangé) |

## Critères d'acceptation
- [ ] CA1 Chantier ouvert : colonne Chantier ≤ 4, Hors ≤ 3 dont ≤ 1 autre chantier ; `column` posé.
- [ ] CA2 Ordre de la colonne Chantier : débloquées par numéro, bloquées (avec `blockedBy`), vérification.
- [ ] CA3 Repli : une option « Continuer : étape M » quand rien n'est rattaché en Exécution.
- [ ] CA4 Prérequis transitifs, niveaux, deux EXTERNE jamais au même niveau, aucun sans `depends_on`.
- [ ] CA5 Libre inchangé (4 au plus, aucun champ nouveau sérialisé).
- [ ] CA6 Consigne stable à l'octet entre deux constructions ; suite complète verte.

## Plan de test
- `ChantierAnchorTest` : colonnes et plafonds, ordre, bloquées par attente et par dépendances, repli, prérequis (niveaux, EXTERNE rétrogradée, aucun sans `depends_on`), Libre à 4, sérialisation.
- `NextStepsBlockTest` : 7 lues, 4 sans chantier.
- `AtelierChatServiceJourneyTest` : colonnes posées de bout en bout.
- `AtelierChatServiceSystemPromptTest` : consigne (deux colonnes) présente et stable.
- Isolation utilisateur : lecture des attentes par la boucle, sur le terminal possédé du tour (`user_id` du tour) — aucun nouvel accès.

## Préoccupations transversales
Aucune (pas d'auth, de tenant, de plan tarifaire ni de route).

## Impacts
`nextsteps/NextStep.java`, `nextsteps/ChantierAnchor.java`, `nextsteps/NextStepsBlock.java`, `AtelierChatService` (consigne, `waitsOf`, appels de rattachement), tests listés. Aucune table, aucun endpoint (les champs voyagent dans l'événement `done` et le relevé `terminal_json` existants).

## Hors périmètre
Écran (SF-209-02). Parallélisme imposé au modèle (le harnais F-195 décide).
