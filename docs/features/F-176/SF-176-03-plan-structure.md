# Mini-spec — F-176 / SF-176-03 — Le plan structuré

## Identifiant
`F-176 / SF-176-03` — dépend de **SF-176-01** (#1074) et **SF-176-02** (#1075), de **F-175** (attentes).
Branche : `feat/SF-176-03-plan-structure`. Statut : `in-progress`. Date : 2026-10-05.

## Objectif
Donner au sujet guidé un **plan structuré** — étapes avec action, classe de risque, vérification,
retour arrière et dépendance à une attente F-175 — que l'utilisateur **valide d'un clic** (Q3), et
dont toute modification après validation est un **amendement** à revalider.

## Comportement attendu

### Cas nominal
1. **Outil `set_subject_plan(steps[])`** (mode Guidé) : chaque étape porte `title`, `risk`
   (`LECTURE` | `NOTES` | `REVERSIBLE` | `EXTERNE`), `verify`, `rollback`, `waits_on` (clé d'attente).
   Le plan **remplace** le précédent, version + 1, le sujet passe (ou revient) en phase **Plan**.
   Le plan peut être **partiel** : une inconnue est une étape `waits_on`.
2. Normalisation (jamais d'échec de tour) : ≤ 20 étapes, champs bornés (300 car.), étape sans action
   ignorée, **risque inconnu = modification réversible** (prudence).
3. **Amendement** : un plan posé alors qu'une version avait été validée garde la version validée pour
   comparaison ; l'écran marque les étapes **nouvelles ou modifiées** ; une étape inchangée garde son
   avancement.
4. `POST /workspaces/{wid}/journey/plan/validate {version}` : valide **la version vue** (une version
   dépassée → 400 « le plan a changé depuis ») ; phase → **Exécution** ; journal `PLAN_VALIDATED`.
5. **Les attentes liées** : la réponse résout chaque `waits_on` en état d'attente (ce terminal d'abord,
   puis le poste) et compte les étapes qui attendent un input encore ouvert : la bande dit « en attente
   de N inputs ».
6. **L'agent voit le plan** dans la consigne du tour (version, validé / en attente / amendement, étapes
   avec état, risque et attente) — borné à 3 000 caractères. Guide stable étendu : phases, plan partiel,
   petit geste = plan d'une ligne, garder `PLAN-ACTION.md` cohérent, amender plutôt que sortir du plan.
7. **Écran** : sous la bande des phases, « Plan vN · K étapes · à valider / validé », déplié tant qu'il
   attend une validation ; chaque étape montre état, risque, vérif, retour arrière, attente et preuve ;
   bouton **[Valider le plan]** ou **[Valider l'amendement]**.

### Cas d'erreur
| Situation | Comportement |
|---|---|
| sujet Libre | résultat d'outil en erreur (« utilise set_plan ou propose le mode guidé ») |
| sujet clos | résultat d'outil en erreur |
| aucune étape lisible | résultat d'outil en erreur |
| validation sans plan / hors phase Plan / version dépassée | 400 `journey_invalid` |
| validation sur le terminal d'autrui | 404 |
| attente illisible | best-effort : la clé est montrée sans état |

## Critères d'acceptation
- [ ] Un plan posé met le sujet en Plan, « en attente de validation ».
- [ ] La validation à la bonne version passe en Exécution ; une version dépassée est refusée.
- [ ] Un plan modifié après validation est un amendement, étapes changées marquées, à revalider.
- [ ] Une étape `waits_on` affiche l'état de son attente ; « en attente de N inputs ».
- [ ] **ISOLATION** : Bob ne peut pas valider le plan d'Alice (404) ; l'état d'une attente est lu sous `user_id`.

## Plan de test
- **Unitaires** : `JourneyPlanTest` (normalisation, bornes, aller-retour JSON, amendement et report
  d'avancement, rappel du plan dans la consigne).
- **Intégration** : `SubjectJourneyApiIntegrationTest` (Libre refusé ; plan → attente résolue →
  version dépassée 400 → validation → Exécution ; amendement ; Bob 404).
- **Front** : `terminal-journey.spec.ts` (plan déplié et validation émise, amendement marqué, version envoyée).

## Impacts
- Aucune migration (colonnes posées en 145).
- Backend : `JourneyPlan` (nouveau) ; `SubjectJourneyService` (`setPlan`, `validatePlan`, `waitsOn`,
  `carryProgress`, réouverture d'un sujet clos par le menu) ; `JourneyToolCatalog` / `Executor`
  (`set_subject_plan`, guide) ; `JourneyTurnNote` (plan) ; `SubjectJourneyResponse` (`plan`) ;
  contrôleur (`plan/validate`) ; `TerminalActionService.statusOfKey` (lecture seule).
- Front : modèle (`JourneyPlan`), service (`validatePlan`), bande du parcours (plan).

### Préoccupations transversales
- Contexte tenant : **oui** — résolution d'une attente par clé à l'échelle du poste. Composants :
  `TerminalActionService.statusOfKey` (filtre `user_id` + terminal possédé, puis `host_id` de ce
  terminal), `SubjectJourneyService.waitsOn` (`requireOwned` côté route).
- Auth / plans / navigation : non.

## Hors périmètre
La porte qui bloque les modifications (SF-176-04) ; diagnostic, avancement des étapes et clôture
(SF-176-05) ; mesure (SF-176-06).

## Arbitrages (réversibles)
- **Le plan validé vit en base** (source de la porte et de l'écran) ; `PLAN-ACTION.md` reste le
  fichier de notes de l'agent, tenu cohérent par la doctrine — la gateway n'écrit pas chez le client.
- Tant que SF-176-05 n'est pas là, `set_subject_plan` est accepté dès l'Investigation (la sortie
  d'investigation par diagnostic arrive en SF-176-05).
- Le plan est déplié automatiquement tant qu'il attend une validation, replié ensuite.
