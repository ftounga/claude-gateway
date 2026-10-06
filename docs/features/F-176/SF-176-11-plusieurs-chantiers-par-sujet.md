# Mini-spec — F-176 / SF-176-11 — Plusieurs chantiers par sujet

## Identifiant
`F-176 / SF-176-11` — rouverture du 2026-10-06 (D9, précision PO : *« beaucoup de mes sujets sont
tellement vastes qu'ils auront beaucoup de chantiers, donc beaucoup de plans d'action »*), validée PO.
Dépend de SF-176-07 (clore = Libre). Branche : `feat/SF-176-11-plusieurs-chantiers`. Date : 2026-10-06.

## Objectif
Qu'un sujet porte une **suite de chantiers** — un seul actif à la fois — : clore un chantier puis
repasser en Guidé ouvre un **nouveau** chantier (plan et diagnostic neufs), et les chantiers clos
restent consultables depuis l'en-tête.

## Comportement attendu

### Cas nominal
1. **Chantier courant** porté par `subject_journeys` : `chantier_number` (0 = jamais guidé), `chantier_title`,
   `chantier_opened_at`.
2. **Ouvrir un chantier** : passer en Guidé quand le sujet n'a jamais été guidé **ou** que le chantier est
   clos → numéro + 1, titre (fourni, sinon la raison de la proposition de l'agent, sinon « Chantier N »),
   Investigation, **plan, versions, validation, diagnostic et clôture proposée remis à zéro**.
   Repasser en Guidé après un passage par Libre **au milieu** d'un chantier le reprend (rien ne change).
3. **Clore** (SF-176-07 : retour en Libre) **archive** le chantier dans `subject_journey_chantiers` : numéro,
   titre, ouverture, clôture, diagnostic + confiance, plan validé final (à défaut, le dernier plan) et sa
   version. Idempotent par numéro. La clôture efface aussi le « rester libre » : l'agent pourra proposer
   le chantier suivant.
4. **Reprise** : un parcours déjà guidé devient le chantier 1 (migration) ; un chantier clos avant la
   migration est archivé à l'ouverture du suivant (paresseux, portable H2/PostgreSQL).
5. **API** :
   - `PUT /api/workspaces/{id}/journey/mode` accepte `{ "mode": "GUIDE", "title": "…" }` (`title` facultatif, ≤ 200).
   - `GET /api/workspaces/{id}/journey` ajoute `chantier: { number, title, openedAt } | null` et `closedChantiers: number`.
   - `GET /api/workspaces/{id}/journey/chantiers` → `[{ number, title, openedAt, closedAt, diagnosis,
     diagnosisConfidence, planVersion, plan: Step[] }]`, le plus récent d'abord. 404 sur un terminal d'autrui.
6. **L'agent** : le guide stable dit « plusieurs chantiers, un actif ; clore ramène en Libre ; un chantier
   distinct → `propose_guided_mode` ; une section par chantier dans PLAN-ACTION.md ». En Libre après un
   chantier clos, le bloc du tour (message, jamais le système) rappelle en une ligne « chantier N clos ;
   chantier distinct → propose_guided_mode ».
7. **Écran** : le menu de l'en-tête montre « Chantier N — titre » et « Chantiers clos : N — voir » ; la liste
   (lecture seule) montre titre et dates, et déplie diagnostic et plan. La carte de proposition dit
   « Cette demande ouvre un nouveau chantier. » quand des chantiers clos existent.

### Cas d'erreur
| Situation | Comportement |
|---|---|
| terminal d'autrui (liste) | 404 |
| titre vide | « Chantier N » |
| titre > 200 caractères | tronqué |
| lecture de la liste en échec | message, la liste se referme |
| archivage d'un numéro déjà archivé | ignoré (idempotent) |

## Contraintes de validation
`title` ≤ 200 caractères (tronqué), retours à la ligne remplacés par des espaces.

## Critères d'acceptation
- [ ] Clore un chantier puis repasser en Guidé → chantier 2, Investigation, plan et diagnostic vides.
- [ ] L'ancien chantier est listé (titre, dates, plan validé final) dans « Chantiers clos ».
- [ ] Bob ne lit pas les chantiers d'Alice (404).
- [ ] Repasser par Libre au milieu d'un chantier ne l'efface pas.
- [ ] La suppression du compte purge les chantiers.

## Plan de test
- **Intégration** : `SubjectJourneyApiIntegrationTest` (clôture → archive ; nouveau chantier remis à zéro ;
  liste ; isolation Bob 404 ; Libre au milieu reprend).
- **Unitaires** : `JourneyTurnNoteTest` (ligne « chantier N clos », silence si l'utilisateur a refusé).
- **Front** : `terminal-journey-chantiers.spec.ts` (service, liste, vide, en-tête).
- Suites complètes.

## Impacts
- Migration `147-subject-journey-chantiers.xml` : 3 colonnes sur `subject_journeys`, table
  `subject_journey_chantiers` (`user_id`, `workspace_id` FK cascade, index `(user_id, workspace_id, number)`).
- Backend : `SubjectJourney`, `SubjectJourneyChantier(+Repository)`, `SubjectJourneyService`
  (openChantier, archive, closedChantiers), `SubjectJourneyController` (`/chantiers`, `title`),
  `SubjectJourneyResponse`, `JourneyTurnNote`, `JourneyToolCatalog.GUIDE`, `AccountService` (purge).
- Front : `journey.models.ts`, `journey.service.ts`, `terminal-journey-chip`, `terminal-journey-strip`,
  nouveau `terminal-journey-chantiers.component.ts`, `atelier-terminal`.

### Préoccupations transversales
- **Contexte tenant** : nouvelle table sous `user_id` + `workspace_id` ; composants qui la lisent :
  `SubjectJourneyService.closedChantiers` (après `requireOwned`), `closedChantierCount` (route déjà
  possédée), `archive` (parcours possédé). Purge compte : `AccountService`.
- Auth / plans / navigation : **non**.

## Hors périmètre
Chantiers **simultanés** dans un même terminal (une porte par terminal ; paralléliser = un autre sujet) ;
renommer un chantier ; rouvrir un chantier archivé.

## Arbitrages (réversibles)
- Titre par défaut = raison de la proposition de l'agent (acceptée) ou « Chantier N » (menu).
- Archivage paresseux des chantiers clos avant la migration (pas d'UUID généré en SQL).
- La clôture remet à zéro le « rester libre » : chaque chantier clos rouvre la question.
