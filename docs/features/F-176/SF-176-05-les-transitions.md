# Mini-spec — F-176 / SF-176-05 — Les transitions (diagnostic + confiance, validation, retour en Investigation)

## Identifiant
`F-176 / SF-176-05` — dépend de **SF-176-03** (#1076) et **SF-176-04** (#1078).
Branche : `feat/SF-176-05-transitions`. Statut : `in-progress`. Date : 2026-10-05.

## Objectif
Faire avancer le sujet guidé d'une phase à l'autre comme le cadrage §4 le décrit : sortir de
l'Investigation par un **diagnostic prouvé avec un niveau de confiance** que l'utilisateur confirme,
suivre l'exécution et la **vérification preuves à l'appui**, revenir en **Investigation** quand une
découverte contredit le diagnostic, et **clore** sur confirmation.

## Comportement attendu

### Cas nominal
1. **`submit_diagnosis(diagnosis, evidence, confidence)`** (Investigation) : diagnostic et preuves
   requis, confiance `FAIBLE` | `MOYENNE` | `ELEVEE` (illisible → MOYENNE). L'écran montre « Prêt à
   planifier — confiance X », le diagnostic, les preuves, **[Planifier] [Continuer l'investigation]**.
   - `POST …/journey/diagnosis/confirm` → phase **Plan** ; `…/diagnosis/dismiss` → reste en
     Investigation (le diagnostic reste lisible, l'agent peut en reposer un).
   - `set_subject_plan` en Investigation est désormais refusé : « pose d'abord ton diagnostic ».
2. **`update_plan_step(step, status, evidence)`** sur le plan **validé et courant**, en Exécution ou
   Vérification : `FAIT` après exécution ; `VERIFIE` / `ECHEC` **avec preuve obligatoire**.
   - plus aucune étape « à faire » → phase **Vérification** (une étape qui attend un input garde le
     sujet en Exécution) ;
   - toutes `VERIFIE` → **clôture proposée** : « Toutes les vérifications sont vertes » **[Clore le
     sujet] [Pas encore]** (`POST …/journey/close`, `…/close/dismiss`) ;
   - `ECHEC` → l'agent est invité à amender ou à revenir en Investigation.
   - L'avancement suit le plan validé (ce n'est pas un amendement : la porte reste ouverte).
3. **`reopen_investigation(reason)`** (toute phase guidée sauf Clos) → phase **Investigation**, plan et
   version validée **gardés**, propositions effacées, porte refermée ; journal `REOPENED` (raison).
4. **Clos** : la porte reste fermée ; le menu « Guidé » rouvre un sujet clos en Investigation.
5. Consigne du tour : diagnostic (≤ 500 car.) et confiance, diagnostic en attente, clôture en attente.
   Guide stable étendu : sortir par le diagnostic, `FAIT` après chaque étape, vérifier comme le plan
   le dit, jamais `VERIFIE` sans preuve, rouge → reopen ou amendement.
6. Journal : `DIAGNOSIS_PROPOSED/CONFIRMED/DISMISSED`, `STEP_UPDATED`, `REOPENED`, `CLOSE_PROPOSED`, `CLOSED`.

### Cas d'erreur
| Situation | Comportement |
|---|---|
| diagnostic ou preuves vides | résultat d'outil en erreur |
| diagnostic hors Investigation | erreur : « déjà posé ; s'il est contredit, reopen_investigation » |
| étape inconnue / plan non validé ou pas en exécution | résultat d'outil en erreur |
| `VERIFIE`/`ECHEC` sans preuve, état inconnu | résultat d'outil en erreur |
| reopen sans raison / sujet clos ou Libre | résultat d'outil en erreur |
| confirmer sans diagnostic en attente | 400 `journey_invalid` |
| gestes sur le terminal d'autrui | 404 |

## Critères d'acceptation
- [ ] Pas de plan sans diagnostic confirmé ; [Planifier] ouvre le Plan, [Continuer] reste en Investigation.
- [ ] Exécution → Vérification quand plus rien n'est à faire ; vérification sans preuve refusée.
- [ ] Toutes vérifiées → clôture proposée ; [Clore le sujet] → Clos ; le menu rouvre en Investigation.
- [ ] Échec → reopen : Investigation, plan gardé, journal.
- [ ] **ISOLATION** : Bob ne confirme ni le diagnostic ni la clôture d'Alice (404).

## Plan de test
- **Unitaires** : `JourneyPlanTest` (diagnostic et clôture rappelés, confiance).
- **Intégration** : `SubjectJourneyApiIntegrationTest` (diagnostic → plan ; exécuter → vérifier →
  clore → rouvrir ; échec → reopen ; isolation) — les tests SF-176-03 passent désormais par le diagnostic.
- **Front** : `terminal-journey.spec.ts` (carte « Prêt à planifier », carte de clôture, appels gateway).

## Impacts
- Aucune migration (colonnes posées en 145).
- Backend : `SubjectJourneyService` (`submitDiagnosis`, `confirm/dismissDiagnosis`, `updateStep`,
  `reopenInvestigation`, `close`, `dismissClose`) ; outils `submit_diagnosis`, `update_plan_step`,
  `reopen_investigation` (catalogue, exécuteur, liste blanche du mode Plan) ; réponse (`diagnosis`,
  `closeProposed`) ; contrôleur (4 routes) ; consigne du tour ; événements.
- Front : modèle, service (4 appels), bande du parcours (2 cartes), terminal.

### Préoccupations transversales
- Contexte tenant : **oui** — nouvelles routes : `requireOwned` d'abord ; outils : terminal du tour,
  lecture sous `user_id`. Auth / plans / navigation : non.

## Hors périmètre
Mesure (SF-176-06).

## Arbitrages (réversibles)
- Une étape qui attend un input **garde le sujet en Exécution** (sinon la porte, fermée en
  Vérification, empêcherait de l'exécuter quand l'input arrive).
- `VERIFIE` n'exige pas que l'étape ait été marquée `FAIT` d'abord (une lecture se vérifie directement).
- « Continuer l'investigation » n'efface pas le diagnostic : il reste lisible et l'agent peut en
  reposer un meilleur.
