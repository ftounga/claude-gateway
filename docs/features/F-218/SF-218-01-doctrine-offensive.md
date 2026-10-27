# Mini-spec — [F-218 / SF-218-01] La doctrine offensive

## Identifiant
SF-218-01

## Feature parente
F-218 — Le mode offensif (`CADRAGE-F-218-le-mode-offensif.md`)

## Statut
Done (PR #1305)

## Date de création
2026-10-27

## Branche Git
`feat/SF-218-01-doctrine-offensive`

---

## Objectif
Faire aller toute action demandée ou planifiée jusqu'à **done**, irréversible compris, sans que l'agent demande à l'utilisateur de la confirmer, sauf si l'utilisateur a demandé à valider avant.

## Inventaire prouvé des confirmations d'action (périmètre 1 du cadrage, sur `origin/main` 128e3698)

| # | Source | Fichier:ligne | Nature | Sort dans F-218 |
|---|--------|---------------|--------|-----------------|
| 1 | `DECIDE_BY_DEFAULT_DOCTRINE` (F-167), garde-fou « jamais seul sur l'irréversible → question structurée » | `AtelierChatService.java:611-632` | **Doctrine** (choix de l'agent) — c'est la cause principale des « je valide ? / je pousse ? » | **Révisé ici** (SF-218-01) |
| 2 | `ASK_QUESTION_DOCTRINE` (F-164), « ne demande que si vraiment bloqué » | `AtelierChatService.java:569-586` | Doctrine | **Précisé ici** : jamais pour confirmer une action |
| 3 | `JourneyToolCatalog.GUIDE`, phase EXÉCUTION | `JourneyToolCatalog.java:90-94` | Doctrine du parcours guidé | **Précisé ici** : le plan validé vaut feu vert, l'étape EXTERNE s'exécute sans redemander |
| 4 | `JourneyTurnNote` « diagnostic en attente de confirmation », « clôture attend la confirmation » | `JourneyTurnNote.java:117,144,148` | Harnais — passage de **phase**, pas une action | **Gardé** (cadrage : la validation du plan n'est pas une confirmation d'action) |
| 5 | Porte `journeyGateRefusal` / `JourneyGate.refusal` | `AtelierChatService.java:2191` ; `JourneyGate.java:27-59` | Harnais — bloque toute modification tant que le plan n'est pas validé ; **ne bloque rien** en Exécution sur plan validé | **Gardé** (porte du plan F-176, `GATE_BLOCKED` = 1 en 7 j : pas la cause) |
| 6 | Guides `savoir-durable` | `governance/savoir-durable/**` | Doctrine de paquet. Aucune consigne n'impose de confirmer push/MR/apply. Seul `guides/cours-video.md:32-39,130-134` impose « fiche + devis, un seul accord » avant fabrication | **Gardé** : c'est une **dépense** (service payant F-216), pas une action irréversible de la liste PO — arbitrage tracé |
| 7 | `defaultDecisionReport` (timeout de `demander`) : ne pas exécuter l'irréversible sur un défaut deviné | `AtelierChatService.java:991-1013` (≈ l. 5006) | Harnais (texte de résultat d'outil) | **Gardé** : la question restante est un vrai arbitrage ; exécuter l'irréversible sur une réponse **devinée** dégraderait la justesse |
| 8 | `RunnerConfirmationGate` via `askPermission` — **écriture Microsoft 365** (F-108) confirmée à chaque fois | `AtelierChatService.java:7598-7612` | **Harnais** (envoi) | **Aligné en SF-218-02** |
| 9 | `askPermission` — publier une **page** (F-109), privée | `AtelierChatService.java:5160` | Harnais | **Retiré en SF-218-02** |
| 10 | `askPermission` — publier une **vidéo / des sons** (F-213), privés | `AtelierChatService.java:5290` | Harnais | **Retiré en SF-218-02** |
| 11 | `askPermission` — **voix off** (F-215), images (F-142) : « service payant » | `AtelierChatService.java:5225` ; `ImageToolCatalog.java:53` | Harnais — **dépense** | **Gardé** (arbitrage : dépense ≠ liste PO ; voir SF-218-02) |
| 12 | `askPermission` — `bash`/rendu vidéo selon `agent_ask_before_bash` et règles `ASK` persistées (F-121) | `AtelierChatService.java:7740-7790` | Harnais — **réglage explicite de l'utilisateur** | **Gardé** (garde-fou conservé du cadrage) |
| 13 | Écran : [Faire cette étape] / [Lancer] sur une étape **EXTERNE** ouvrent un dialogue « Confirmer ? » (F-208/F-209) | `terminal-chantiers-board.component.ts:151,854` ; `terminal-next-steps.component.ts:306,503,584` | Harnais (écran) | **Retiré en SF-218-03** |
| 14 | `exit_plan_mode` (F-121) : l'agent soumet un plan à approbation | `AtelierChatService.java` `applyExitPlan` | Doctrine (outil que l'agent choisit d'appeler) | Inchangé ; couvert par la doctrine (pas pour une action unique) |

**Lecture des mesures** : 20 « Question posée » en 48 h et 35 `demander` en 7 j pour 1 seul `GATE_BLOCKED` : l'essentiel des attentes vient de la **doctrine** (#1), pas du harnais.

## Comportement attendu

### Cas nominal
- Le préfixe système (deux cibles, universel) porte, à la place du garde-fou F-167, un bloc **MODE OFFENSIF** : une action demandée ou prévue au plan va jusqu'à done (commenter + pousser, plan + apply, MR + merge si c'est l'usage du dépôt, envoyer, supprimer, déployer en prod) sans demander de confirmation.
- **Exception unique** : l'utilisateur a écrit dans sa demande (ou les règles du projet) qu'il veut valider avant → l'agent s'arrête au point fixé.
- `demander` reste réservé aux vrais arbitrages (information manquante que les outils ne donnent pas, choix métier que seul l'utilisateur tranche) ; jamais pour confirmer une action.
- **On supprime l'attente, pas la vérification** : lire le plan terraform avant l'apply, lancer les tests avant de pousser, vérifier le résultat après ; un constat qui met en cause la justesse (plan qui détruit plus que prévu, tests rouges, mauvaise cible) arrête et se dit.
- Le guide du parcours (`JourneyToolCatalog.GUIDE`) dit que le plan validé vaut feu vert : une étape EXTERNE s'exécute sans redemander.

### Cas d'erreur
- Consigne explicite de validation dans la demande → l'agent la respecte (texte de la doctrine).
- Vérification rouge (tests, plan destructeur) → l'agent s'arrête et le dit (pas une confirmation : un constat).
- Plan non validé en mode guidé → la porte F-176 refuse toujours (inchangé).

## Critères d'acceptation
1. Le préfixe système ne contient plus « décider-par-défaut est INTERDIT » ni « QUESTION STRUCTURÉE (outil « demander » » pour l'irréversible, ni « traite le choix comme IRRÉVERSIBLE et demande ».
2. Il contient « MODE OFFENSIF », l'exception de la consigne explicite, la réservation de `demander` aux vrais arbitrages, et la règle « on supprime l'attente, pas la vérification ».
3. La partie « faible enjeu / réversible → défaut + annonce » de F-167 est conservée.
4. Bloc byte-stable entre deux constructions (cache F-134), présent sur SANDBOX et RUNNER.
5. `JourneyToolCatalog.GUIDE` dit que le plan validé vaut feu vert pour les étapes EXTERNE.

## Périmètre
### Hors scope (explicite)
- Retrait des confirmations du harnais (SF-218-02) et de l'écran (SF-218-03).
- Exécution autonome d'un chantier entier (F-219).
- Validation du plan d'un chantier (F-176) : gardée.

## Valeurs initiales
Sans objet.

## Contraintes de validation
Sans objet (texte de prompt).

## Technique
### Endpoint(s)
Aucun.
### Tables impactées
Aucune.
### Migration Liquibase
Aucune.
### Composants Angular (si applicable)
Aucun.

### Préoccupation transversale — prompt système partagé
Composants impactés : `AtelierChatService.DECIDE_BY_DEFAULT_DOCTRINE`, `AtelierChatService.ASK_QUESTION_DOCTRINE` (injectés sur les deux cibles, `buildSystemPrompt`), `JourneyToolCatalog.GUIDE` (injecté quand les outils du parcours sont ouverts). Littéraux stables, aucun contenu volatil (F-134 / F-171).

## Plan de test
### Tests unitaires
- `AtelierChatServiceSystemPromptTest` : assertions F-167 réécrites (présence du mode offensif sur SANDBOX et RUNNER, absence de l'ancien garde-fou, exception consigne explicite, vérification gardée, byte-stable).
- Test `JourneyToolCatalog` : le guide contient le feu vert du plan validé.
### Tests d'intégration
- Suite backend complète (`mvn -pl backend test`) verte.
### Isolation workspace
Inchangée (aucun accès données).

## Dépendances
### Subfeatures bloquantes
Aucune.
### Questions ouvertes impactées
Aucune.

## Notes et décisions
- **Révise F-167** sur décision PO du 2026-10-27 (le garde-fou irréversible → `demander` est retiré).
- Arbitrage : la **dépense** (voix off, images, devis vidéo) n'est pas dans la liste PO (MR/PR, apply/déploiement prod, envoi, suppression) ; ses accords sont gardés. Réversible.
