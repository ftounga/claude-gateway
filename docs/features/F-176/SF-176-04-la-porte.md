# Mini-spec — F-176 / SF-176-04 — La porte par classe de risque, amendements

## Identifiant
`F-176 / SF-176-04` — dépend de **SF-176-03** (#1076).
Branche : `feat/SF-176-04-porte`. Statut : `in-progress`. Date : 2026-10-05.

## Objectif
En mode Guidé, que le **harnais** — pas la consigne — refuse toute modification hors des notes du
sujet tant que le plan n'est pas validé (décision Q2), et qu'un amendement non revalidé referme la
porte (Q3). Mode Libre inchangé (Q4).

## Comportement attendu

### Cas nominal
1. **Classification** (`JourneyRiskClassifier`) de chaque appel d'outil :
   - `LECTURE` : `read_file`, `list_files`, `search_files`, `grep`, `glob`, `explore`, `task` en
     lecture seule, et `bash` dont **chaque** segment est une lecture reconnue (ls, cat, grep, git
     status/log/diff/show/fetch, kubectl get/describe/logs, terraform plan/show/validate, aws
     describe-/list-/get-, helm list/status, curl GET…) ;
   - `NOTES` : écriture de `STATE.md`, `PLAN-ACTION.md`, `NOTES.md`, `REPO-MAP.md`, ou d'un `.md`
     sous `notes/` ou `carte/` (sans `..`) ;
   - `EXTERNE` : push, merge, reset --hard, kubectl apply/delete/patch/scale/rollout restart…,
     terraform apply/destroy, helm install/upgrade, aws en écriture, curl POST/PUT/DELETE ou avec
     données, ssh/scp/rsync, rm, `| sh`, écritures Teams ;
   - `REVERSIBLE` : toute autre écriture ou commande — **une commande non reconnue est une
     modification** (prudence, cadrage §4) ; redirection vers un fichier, `$(…)`, sous-tâche
     écrivaine, dépôts de fichiers (deck, office, diagramme, image) ;
   - **hors porte** (`null`) : outils d'organisation de la gateway (plan, attentes, parcours, rappel,
     carte, pages, courriel à soi…).
2. **La porte** (`JourneyGate`) : en Guidé, `REVERSIBLE` et `EXTERNE` ne passent qu'en phase
   **Exécution** sur la version **validée** du plan. Sinon le résultat d'outil dit pourquoi, par phase
   (Investigation : investigue puis planifie ; Plan : le plan / l'amendement attend la validation ;
   Vérification : on ne modifie plus, amende ; Clos). Rien n'est émis vers le poste.
3. Posée dans la boucle **avant toute émission**, après la garde de fraîcheur ; une lecture ou une note
   ne consulte même pas le parcours.
4. Chaque refus est journalisé (`GATE_BLOCKED`, détail = classe · outil — **jamais la commande**).
5. Guide stable : la porte, ne pas chercher de contournement, préférer des commandes de lecture simples.
6. **Écran** : la bande dit « Lecture et notes libres · les modifications attendent un plan validé »
   tant que la porte est fermée.

### Cas d'erreur
| Situation | Comportement |
|---|---|
| parcours illisible pendant le tour | best-effort : Libre (porte ouverte) — le défaut sûr de Q4 |
| commande vide | lecture |
| journal indisponible | le refus s'applique quand même |

## Critères d'acceptation
- [ ] En Guidé · Investigation, `kubectl apply` est refusé sans rien émettre ; le refus est journalisé.
- [ ] En Guidé, `kubectl get` et l'écriture de `PLAN-ACTION.md` passent.
- [ ] En Exécution sur le plan validé, la modification passe ; un amendement non revalidé la bloque.
- [ ] En Libre, rien n'est refusé ni journalisé.
- [ ] Le journal ne contient jamais la commande.

## Plan de test
- **Unitaires** : `JourneyRiskClassifierTest` (lectures, externes, prudence, notes, outils ; porte par
  phase, amendement).
- **Boucle** : `AtelierChatServiceJourneyTest` (refus avant émission + journal ; Libre sans porte ;
  lecture libre ; plan validé ouvre ; parcours dans le message, jamais au système).
- **Front** : `terminal-journey.spec.ts` (porte dite / levée).
- Suites complètes.

## Impacts
- Aucune migration.
- Backend : `JourneyRiskClassifier`, `JourneyGate` (nouveaux) ; `AtelierChatService.journeyGateRefusal`
  (boucle) ; `SubjectJourneyService.recordGateBlocked` ; guide.
- Front : bande du parcours (porte).

### Préoccupations transversales
- Auth / tenant / plans / navigation : **non** — la porte lit le parcours du terminal **du tour**
  (déjà possédé), sous `user_id`. Les garde-fous existants (confirmation F-33, politique de
  permission F-121, points d'accroche F-52) restent inchangés et s'appliquent **après** la porte.

## Hors périmètre
Rattacher sémantiquement une commande à une étape précise du plan ; avancement des étapes et
transitions (SF-176-05) ; mesure (SF-176-06).

## Arbitrages (réversibles)
- **La porte vérifie qu'un plan validé et courant existe**, pas qu'une commande correspond à une étape
  donnée (le harnais ne peut pas lier sûrement une commande à une étape) : une modification hors plan
  est empêchée par la doctrine d'amendement, et un amendement referme la porte jusqu'au clic.
- Parcours illisible → porte ouverte (Libre) plutôt que tout bloquer sur une panne de base.
- Le courriel à soi-même (`email_me`) et la publication de pages ne sont pas soumis à la porte (ils
  ne modifient ni le poste ni un système du client).
