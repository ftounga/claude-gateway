# Mini-spec — [F-220 / SF-220-01] Qui débloque ?

## Identifiant
SF-220-01

## Feature parente
F-220 — Le tableau de bord du client

## Statut
Done

## Date de création
2026-10-27

## Branche Git
`feat/SF-220-01-qui-debloque`

---

## Objectif
Calculer côté serveur, pour chaque chantier ouvert et chacune de ses étapes non faites, **qui doit agir** pour qu'il avance — l'outil, l'utilisateur, ou quelqu'un d'autre — et l'exposer dans le parcours du sujet.

## Comportement attendu

### Cas nominal
- Nouveau calcul pur `ChantierUnblocker` (aucun accès aux données) : règle écrite dans le cadrage (§ « Règle Qui débloque ? »), étapes puis chantier.
- Une marque = `{ who, label, detail, step, waitingOn, since }` :
  - `who` ∈ `OUTIL`, `MOI`, `AUTRUI` ;
  - `label` court : « L'outil peut avancer », « Ça attend votre décision », « On attend quelqu'un » ;
  - `detail` : la phrase lisible (« Étape 3 : en attente de « cert-gino » auprès de Gino, demandé »…) ;
  - `step` : rang concerné (à partir de 1) ou `null` ;
  - `waitingOn` : à qui (attente `requested_to`, sinon `person`), seulement pour `AUTRUI` ;
  - `since` : depuis quand (attente `requested_at`, sinon `created_at`).
- `GET /api/workspaces/{ws}/journey` : chaque `chantiers[]` porte `unblocker` ; chaque étape de `plan.steps[]` et de `chantiers[].plan.steps[]` porte `unblocker` (`null` pour une étape faite ou vérifiée). Le chantier au focus tient compte de la question en suspens sur le tour vivant du sujet.
- Les attentes sont relues **avec qui / à qui / depuis quand** (`TerminalActionService.actionOfKey`, même recherche que `statusOfKey` : ce terminal d'abord, puis le poste).

### Cas d'erreur
| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Terminal d'autrui | `requireOwned` d'abord, aucune donnée | 404 |
| Attentes illisibles (service absent ou erreur) | best-effort : la clé est « non suivie » → `AUTRUI` | 200 |
| Plan illisible / vide | pas d'étape ; chantier en Plan sans plan → `OUTIL` (l'agent rédige) | 200 |
| `depends_on` hors bornes | ignoré (règle F-208 existante) | 200 |

## Critères d'acceptation
1. Étape à faire, rien ne la bloque → `OUTIL` ; chantier en Exécution sur plan validé → `OUTIL` avec `step` = cette étape.
2. Étape `waits_on=k` avec attente ouverte `qui=CLIENT`, demandée à « Gino » → étape `AUTRUI`, `waitingOn = "Gino"`, `since` = date de la demande ; les étapes qui en dépendent héritent `AUTRUI` ; chantier sans autre étape exécutable → `AUTRUI`.
3. Même attente avec `qui=MOI` → `MOI`.
4. `waits_on` sans attente → `AUTRUI` (« personne ne le suit »).
5. Étape `BLOQUE` → `MOI` avec la raison dans `detail`.
6. Plan posé non validé (Plan) → chantier `MOI` ; amendement en attente → `MOI`.
7. Question en suspens sur le tour vivant → chantier au focus `MOI`.
8. Investigation → `OUTIL` ; toutes étapes vérifiées → `MOI` (« à clore ») ; clôture proposée → `MOI`.
9. Une étape exécutable et une autre bloquée en même temps → chantier `OUTIL` (l'outil peut avancer).
10. Bob → 404 sur `GET …/journey` d'Alice (inchangé).

## Périmètre
### Hors scope (explicite)
- Le tableau agrégé par poste (SF-220-02), les écrans (SF-220-03/04), reporter et rappel (SF-220-05).
- Toute modification du préflight F-219 (inchangé).

## Valeurs initiales
Aucune (lecture seule, aucune donnée persistée).

## Contraintes de validation
| Champ | Règle |
|-------|-------|
| `who` | `OUTIL`, `MOI`, `AUTRUI` — jamais `null` sur un chantier ouvert |
| `detail` | texte borné (titres et raisons déjà bornés par le plan : 200 / 500 caractères) |

## Technique
### Endpoint(s)
| Méthode | URL | Auth | Rôle minimum |
|---------|-----|------|-------------|
| GET | `/api/workspaces/{ws}/journey` (enrichi) | JWT | USER |

### Tables impactées
| Table | Opération | Notes |
|-------|-----------|-------|
| `terminal_actions` | SELECT | par `user_id` + clé, comme `statusOfKey` |
| `subject_journeys`, `subject_journey_chantiers` | SELECT | inchangé |

### Migration Liquibase
- [x] Non applicable

### Composants
- `journey/ChantierUnblocker` (calcul pur), `journey/WaitInfo` (attente lue), `SubjectJourneyService.waitInfos`, `TerminalActionService.actionOfKey`, `SubjectJourneyResponse` (champ `unblocker`), `SubjectJourneyController` (question en suspens).

## Préoccupations transversales
- Auth / Principal : non. Contexte tenant : non (lecture sous `user_id` inchangée). Plans / limites : non. Navigation : non.

## Plan de test
### Tests unitaires
- `ChantierUnblockerTest` : critères 1 à 9 (étapes et chantier), héritage par `depends_on` et par ordre, attente `FAIT` qui ne bloque plus.
### Tests d'intégration
- `ChantierUnblockerIntegrationTest` : `GET …/journey` → `chantiers[0].unblocker.who` = `OUTIL` ; après attente ouverte `CLIENT` demandée → `AUTRUI` + `waitingOn` ; question en suspens → `MOI` ; étape `BLOQUE` → `MOI`.
### Isolation utilisateur
- [x] Applicable — Bob → 404 sur le parcours d'Alice.

## Dépendances
- F-219 (statut `BLOQUE`) — Done.
- Questions ouvertes : aucune.

## Notes et décisions
- Arbitrages 1 à 3 du cadrage (EXTERNE ≠ attente, `qui=MOI` = ma décision, information non suivie = quelqu'un).
