# Mini-spec — [F-274 / SF-274-01] Le couloir hors chantier

## Identifiant
`F-274 / SF-274-01`

## Feature parente
`F-274` — Les demandes hors plan s'exécutent dans un sujet à plusieurs chantiers

## Statut
`in-progress`

## Date de création
2026-11-21

## Branche Git
`feat/SF-274-01-couloir-hors-chantier`

---

## Objectif
Une demande explicite de l'utilisateur qui n'appartient au plan d'aucun chantier, ou à plusieurs, s'exécute dans le fil
où elle est faite, sans amendement ni renvoi, et un tour qui ne fait rien en dit la vraie raison.

## Cause racine (prouvée, cadrage §1-2)
C1 (doctrine sans issue : « reste sur ce chantier » + « n'exécute que le plan validé ») et C4 (« ce tour s'est terminé »
inventé) : alarm4tech 18/11 10:02, 10:04, 10:13 — 1 itération, 0 outil, porte ouverte, aucune coupure.

## Comportement attendu

### Cas nominal
1. Nouvel outil du parcours `geste_hors_chantier` : `user_words` (requis, mots exacts de l'utilisateur dans ce tour),
   `objet` (requis, ≤ 200 car.), `chantiers` (tableau d'entiers, optionnel : les chantiers ouverts que le geste sert ;
   vide = aucun chantier).
2. Le harnais vérifie la citation (`JourneyToolExecutor.writtenRefusal`, comme `cloturer_chantier`) ; le sujet doit
   être en Guidé actif.
3. Effet : la porte du parcours est **ouverte pour le reste de ce tour** (`Workspace.turnOutOfPlan`, transitoire) ; un
   événement `OUT_OF_PLAN` est journalisé **par chantier cité** (`detail` = « chantier N · objet ») ou une fois
   « aucun chantier · objet ». Résultat rendu au modèle : exécute la demande telle qu'elle est formulée, rien de plus,
   annonce-le en une ligne.
4. Guide système (littéral stable) : un paragraphe **HORS CHANTIER** (le couloir, ses limites, `chantier: N` pour un
   autre chantier, jamais de renvoi vers un autre fil) et une règle **ARRÊT** (un refus est ton choix, dit avec sa
   raison ; jamais « le tour s'est terminé / a été coupé » : les coupures réelles sont annoncées par la gateway).
5. Note du fil (`laneNote`, dans le message) : remplace « si la demande relève d'un autre, dis-le » par la conduite à
   tenir (autre chantier → `chantier: N` ici ; aucun ou plusieurs → couloir). Le fil du sujet (lane `null`) d'un sujet
   à plusieurs chantiers ouverts reçoit une note « [Fil du sujet] » équivalente.

### Cas d'erreur
| Situation | Comportement attendu |
|---|---|
| `user_words` absent ou introuvable dans ce tour | erreur d'outil, porte inchangée, rien journalisé |
| Course autonome / routine (tour sans utilisateur) | refus « personne n'écrit dans une course autonome » |
| Sujet en Libre ou clos | erreur « le couloir n'existe qu'en mode guidé : en Libre, fais simplement la demande » |
| `objet` absent | erreur d'outil |
| Chantier cité inconnu ou clos | ignoré dans la liste, signalé dans le résultat ; geste journalisé pour les autres |

## Critères d'acceptation
- [ ] Avec citation valide, la porte ne refuse plus une modification dans ce tour, même si aucun chantier n'est en
      Exécution (test `journeyGateRefusal`).
- [ ] Sans appel du couloir, la porte se comporte exactement comme avant (tests F-176/F-206 existants verts).
- [ ] Citation absente/inventée, tour sans utilisateur, sujet Libre → erreur, porte fermée (tests).
- [ ] Un événement `OUT_OF_PLAN` par chantier cité ; « aucun chantier » sinon (test).
- [ ] `GUIDE` contient HORS CHANTIER et ARRÊT ; reste un littéral `static final` (test de stabilité existant).
- [ ] `laneNote` ne contient plus « dis-le » ; nomme `chantier: N` et `geste_hors_chantier` (test).
- [ ] Isolation : le geste ne lit/écrit que le parcours du sujet du tour (`userId` + `workspace` du tour).

## Plan de test
### Tests unitaires
`JourneyToolExecutorTest` (couloir : nominal, citations, headless, Libre, chantiers inconnus), `JourneyToolCatalogTest`
(outil donné, guide), `AtelierChatServiceJourneyGateTest` ou équivalent (porte ouverte par le couloir), note du fil.
### Tests d'intégration
Suite backend complète (`scripts/build-slot.sh ./mvnw -q test`).
### Isolation utilisateur
Le `userId`/`workspace` viennent du tour ; aucun identifiant n'est lu dans l'entrée de l'outil (test : chantier d'un
autre sujet introuvable).

## Tables / endpoints / composants impactés
Aucune migration (`subject_journey_events.type` est un `varchar`). `JourneyToolCatalog`, `JourneyToolExecutor`,
`SubjectJourneyEvent`, `SubjectJourneyService`, `Workspace` (transitoire), `AtelierChatService` (porte, notes).

## Préoccupations transversales
- Auth / Principal : non. Contexte tenant : non (userId du tour). Plans/limites : non. Navigation : non.

## Hors périmètre
Relecture des gestes par les autres chantiers et lecture du plan d'un autre chantier (SF-274-02) ; écran (SF-274-03).
