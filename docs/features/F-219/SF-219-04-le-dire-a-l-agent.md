# Mini-spec — [F-219 / SF-219-04] Le dire à l'agent

## Identifiant
SF-219-04

## Feature parente
F-219 — Exécuter un chantier en autonome

## Statut
Done

## Date de création
2026-10-27

## Branche Git
`feat/SF-219-04-dire-a-l-agent`

---

## Objectif
Que le PO puisse dire dans la conversation « exécute-moi le chantier 2 en autonome » : l'agent vérifie le préflight et programme le départ, qui part dès la fin du tour courant.

## Comportement attendu

### Cas nominal
- Nouvel outil du parcours `lancer_chantier_autonome` (`chantier`: N, requis), donné avec les autres outils du parcours (mêmes droits).
- L'outil relit le préflight (SF-219-01) du chantier N et les gardes de départ (une course déjà en cours sur le sujet, 4 courses en cours sur le compte, poste hors ligne).
- Tout est vert → le départ est **programmé** sur le sujet ; résultat : « Départ programmé : le chantier N partira en autonome dès la fin de ce tour. Termine ton tour maintenant, en une phrase ; n'exécute pas les étapes toi-même. »
- Un passage toutes les 10 s (sur le pod qui porte le tour) lance les départs programmés dont le sujet est libre — même chemin que le bouton (`AutonomyService.launch`). Un départ programmé expire au bout de 10 min sans que le sujet se libère ; un départ qui échoue au moment de partir (préflight devenu rouge, limite atteinte) est abandonné et notifié (`AUTONOMY_STOPPED`).

### Cas d'erreur
- Préflight rouge → erreur outil listant les raisons (« dis-le à l'utilisateur »), rien n'est programmé.
- Course déjà en cours sur ce sujet / limite de 4 / poste hors ligne → erreur outil avec la phrase du refus.
- `chantier` absent ou chantier non ouvert → erreur outil.
- Un deuxième appel sur le même sujet remplace le départ programmé (un seul chantier autonome par sujet).
- Lanceur absent (contexte sans autonomie) → erreur outil « indisponible ».

## Critères d'acceptation
1. Chantier prêt → résultat « Départ programmé », un départ en attente sur le sujet.
2. Le passage lance le départ quand le sujet est libre (course `RUNNING` écrite), pas avant.
3. Préflight rouge → erreur avec les raisons, rien de programmé.
4. Un départ programmé expiré n'est pas lancé.
5. Un départ dont le préflight est devenu rouge au moment de partir est abandonné et notifié.

## Périmètre
### Hors scope (explicite)
- L'écran (SF-219-03). La persistance des départs programmés (mémoire du pod : un redémarrage les perd ; l'outil le dit à l'agent qui le dit au PO).

## Valeurs initiales
Passage toutes les 10 s ; expiration 10 min (`app.autonomy.deferred-ttl=PT10M`).

## Contraintes de validation
| Champ | Règle |
|-------|-------|
| `chantier` | entier ≥ 1, chantier ouvert |

## Technique
### Endpoint(s)
Aucun.
### Tables impactées
Aucune.
### Migration Liquibase
Aucune.
### Composants
- `journey.ChantierAutonomyLauncher` (interface, évite la dépendance de paquet journey → autonomy) ; `JourneyToolCatalog` (outil + `NAMES`) ; `JourneyToolExecutor` (aiguillage).
- `autonomy.AutonomyService` implémente le lanceur (`schedule`, `launchDeferred`) ; `AutonomyDeferredWorker` (tâche de fond, désactivée en tests).

### Préoccupation transversale — Plans / limites
Composants impactés et vérifiés : la limite de 4 courses (`AutonomyService`), vérifiée à la programmation **et** au départ. Aucun autre service de limite.

## Plan de test
### Tests unitaires
- `JourneyToolCatalogTest`/existant : l'outil est listé avec les outils du parcours.
### Tests d'intégration
- `AutonomyDeferredIntegrationTest` : programmé (prêt) ; refusé (pas prêt, avec raisons) ; lancé seulement sujet libre ; expiré non lancé ; préflight devenu rouge → abandonné.
### Isolation utilisateur
L'outil n'agit que sur le terminal du tour (déjà possédé) ; aucun identifiant de compte en entrée. Le départ programmé porte `(user_id, workspace_id)` du tour.

## Dépendances
SF-219-01, SF-219-02 (mergées).
