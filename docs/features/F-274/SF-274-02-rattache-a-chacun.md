# Mini-spec — [F-274 / SF-274-02] Rattaché à chacun : le chantier relit ses gestes hors plan, le plan d'un autre se lit

## Identifiant
`F-274 / SF-274-02`

## Feature parente
`F-274` — Les demandes hors plan s'exécutent dans un sujet à plusieurs chantiers

## Statut
`in-progress`

## Date de création
2026-11-21

## Branche Git
`feat/SF-274-02-rattache-a-chacun`

---

## Objectif
Un geste hors plan ou transverse est connu de chacun des chantiers qu'il sert (au tour suivant de leur fil et à
l'écran), et le plan complet d'un autre chantier ouvert se lit depuis n'importe quel fil pour pouvoir l'amender.

## Cause racine (prouvée, cadrage §1-2)
C5 : le fil d'un chantier ne relit que le commun et le sien (`ChantierThread.visibleTo`) — un geste transverse fait
ailleurs lui est invisible (data-platform 30/10 : ticket « hors chantier » sorti des listes). C3 : `JourneyTurnNote`
résume les autres chantiers en une ligne ; data-platform 04/11 18:48 : « le texte exact des étapes 1 à 15 n'est
consultable ni dans ce fil ni dans les fichiers » → amendement du chantier 9 impossible depuis le fil 11.

## Comportement attendu

### Cas nominal
1. `SubjectJourneyService.outOfPlanByChantier(userId, workspaceId)` : les gestes `OUT_OF_PLAN` des 14 derniers jours,
   rangés par chantier (`detail` « chantier N · objet · … ») ; « aucun chantier » rangés sous la clé 0. Au plus 5 par
   chantier, les plus récents d'abord.
2. Dans le **message** du tour (avant le bloc « Parcours du sujet ») : pour le fil du chantier N, une ligne
   « [Hors plan] Gestes faits hors du plan et rattachés à ce chantier : 18/11 page État des lieux v7 ; … » (3 au
   plus) ; dans le fil du sujet, tous les gestes récents, chacun avec ses chantiers ou « (hors chantier) ». Rien si
   aucun geste.
3. API `GET …/journey` : chaque chantier ouvert porte `horsPlan` = `[{at, objet}]` (5 au plus) pour l'écran (SF-274-03).
4. Nouvel outil `lire_plan_chantier` `{chantier}` : rend le plan complet du chantier ouvert (version, validé ou non,
   phase, et les étapes au format de `set_subject_plan`, statuts compris) — lecture, jamais soumis à la porte.

### Cas d'erreur
| Situation | Comportement attendu |
|---|---|
| `chantier` absent | erreur d'outil « chantier est requis » |
| Chantier inconnu ou clos | « Le chantier N n'est pas ouvert. » |
| Chantier sans plan | « Le chantier N n'a pas encore de plan (phase …). » |
| Journal illisible | best-effort : pas de ligne, pas d'erreur de tour |
| Sujet d'un autre utilisateur | `GET …/journey` 404 (inchangé) ; l'outil ne lit que le sujet du tour |

## Critères d'acceptation
- [ ] Un geste rattaché aux chantiers 1 et 2 apparaît dans la note du fil 1 **et** du fil 2, pas dans celle d'un 3
      (test).
- [ ] Un geste « aucun chantier » apparaît dans la note du fil du sujet seulement (test).
- [ ] `horsPlan` exposé par chantier dans la réponse du parcours (test d'intégration).
- [ ] `lire_plan_chantier` rend les étapes complètes au format de `set_subject_plan` ; erreurs lisibles (tests).
- [ ] Le guide système reste un littéral stable : une seule mention de `lire_plan_chantier` ajoutée (test).
- [ ] Isolation : Bob n'obtient rien du parcours d'Alice (test existant + `horsPlan`).

## Plan de test
### Tests unitaires
`JourneyTurnNoteTest` (ligne des gestes), `JourneyToolCatalogGuideTest`.
### Tests d'intégration
`SubjectJourneyChantiersIntegrationTest` (rangement par chantier, `horsPlan`, `lire_plan_chantier`) ; suite complète.
### Isolation utilisateur
Requêtes du journal filtrées par `userId` **et** `workspaceId` du tour / du token.

## Tables / endpoints / composants impactés
Aucune migration (lecture de `subject_journey_events`, repository : nouvelle requête filtrée user + workspace + type +
date). `GET /api/workspaces/{id}/journey` (champ ajouté, rétrocompatible). `JourneyTurnNote`, `JourneyToolCatalog`,
`JourneyToolExecutor`, `SubjectJourneyService`, `SubjectJourneyResponse`, `SubjectJourneyController`,
`AtelierChatService.withJourney`.

## Préoccupations transversales
- Auth / tenant : lecture filtrée `user_id` + `workspace_id` (rien de nouveau dans la résolution). Plans/limites,
  navigation : non.

## Hors périmètre
Recopier les messages d'un fil dans un autre ; affichage (SF-274-03).
