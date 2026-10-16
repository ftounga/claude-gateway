# Mini-spec — F-180 / SF-180-02 — Nature, qui agit, sujet

## Identifiant
`F-180 / SF-180-02` — feature parente `F-180` *Les accès et les interlocuteurs* (décisions **D1**, **D3**).
Dépend de SF-180-01 (mergée). Branche : `feat/SF-180-02-nature-qui-agit-sujet`. Statut : `in-progress`.
Date : 2026-10-16.

## Objectif
Dire de chaque attente **de quelle nature** elle est (accès, validation…), **qui doit agir** (moi, le
client, un tiers) et **ce qu'elle bloque** (sujet du Radar, étape du plan F-176).

## Comportement attendu

### Cas nominal
1. **Nature** `categorie` ∈ {`ACCES`, `VALIDATION`, `INFO`, `TRANSMISSION`, `INCIDENT`} et **qui agit**
   `qui` ∈ {`MOI`, `CLIENT`, `TIERS`} — colonnes nullable de `terminal_actions` (migration `159`).
2. **L'agent les propose** : `record_blocker` et `update_blocker` acceptent `categorie` et `qui`
   (énumérations au schéma). Le guide demande de distinguer les **gestes à toi** (SSO, redémarrer le
   runner : `qui=MOI`) des attentes envers autrui. Valeur inconnue → erreur d'outil lisible.
3. **Ajout / édition à la main** : `POST`/`PATCH …/actions` acceptent `categorie` et `qui`.
4. **Reprise validée** : les attentes **ouvertes sans nature** sont proposées une par une avec une
   nature et un « qui » **devinés par heuristique** (mots-clés de l'énoncé, présence d'une personne) —
   `GET /api/terminal-actions/qualify` → `[{ action, suggestedCategorie, suggestedQui }]` ;
   `POST /api/terminal-actions/qualify` `{ decisions: [{ id, categorie, qui }] }` → `{ applied, ignored }`.
   **Rien n'est écrit sans validation** ; un identifiant inconnu ou d'autrui est ignoré (compté).
5. **Sujet** (`subject_id`) **rempli à l'inscription** : si le terminal a **exactement un** sujet du
   Radar lié et confirmé (`radar_subject_projects`, état `CONFIRMED`, sous `user_id` + `host_id`), il est
   posé sur l'attente (agent et ajout à la main). Ambigu (0 ou ≥ 2) → nul.
6. **Étape bloquée** : au tableau, chaque attente porte `blockedStep` `{ number, title, terminal }`
   quand une étape du plan F-176 **de son terminal** dépend d'elle (`waitsOn` = sa clé) ; et
   `subjectName` (nom du sujet du Radar). La carte affiche « bloque : étape 3 « … » de data-platform ».
7. **À l'écran** : pastilles nature + « à toi / client / tiers » sur chaque carte ; la bande dit
   « · 2 gestes à toi » ; le tableau compte `moi` (ouvertes `qui=MOI`). La liste jointe au tour porte
   `ACCES · CLIENT` dans le crochet.

### Cas d'erreur
| Situation | Comportement |
|---|---|
| `categorie`/`qui` hors énumération (API) | 400 |
| idem (outil) | résultat d'outil en erreur, le tour continue |
| Décision de reprise sur une attente d'autrui / inconnue | ignorée, comptée |
| Plan illisible / sujet supprimé | `blockedStep` / `subjectName` nuls (best-effort) |

## Contraintes de validation
| Champ | Règle |
|---|---|
| `categorie` | énumération, nullable |
| `qui` | énumération, nullable |
| Reprise | 200 décisions max par appel |

## Critères d'acceptation
- [ ] L'agent inscrit une attente `ACCES`/`CLIENT` ; la carte et la liste du tour le montrent.
- [ ] Un geste `MOI` est distingué (pastille « à toi », compteur de la bande).
- [ ] Une attente sans nature est proposée avec une nature devinée ; validée, elle la prend ; rien sans validation.
- [ ] Un terminal lié à un seul sujet remplit `subject_id` à l'inscription.
- [ ] Une étape `waitsOn` = clé de l'attente → « bloque : étape N … de <terminal> ».
- [ ] Bob ne qualifie pas les attentes d'Alice.

## Plan de test
- **Unitaires** : `TerminalActionQualifierTest` (heuristique), `TerminalActionServiceTest` (sujet
  résolu / ambigu, catégorie à l'inscription), `TerminalActionToolTest` (paramètres, valeur inconnue),
  `TerminalActionTurnNoteTest` (crochet), `TerminalActionQueryServiceTest` (`blockedStep`, `moi`).
- **Intégration** : `TerminalActionQualifyApiIntegrationTest` — reprise proposée / appliquée,
  **isolation Bob** ; création avec `categorie`/`qui`, 400 sur valeur inconnue.
- **Front** : pastilles, « bloque : étape », bande « gestes à toi », section de reprise, formulaire.

## Impacts
- **Table** `terminal_actions` : `categorie varchar(16)`, `qui varchar(8)` (migration `159`, réversible).
- **Endpoints** : `GET/POST /api/terminal-actions/qualify` ; `categorie`/`qui` sur création, édition, réponses.
- **Composants** : `TerminalActionCategorie`, `TerminalActionQui`, `TerminalActionQualifier`,
  `TerminalActionSubjectResolver` (nouveaux) ; `TerminalActionService`, `TerminalActionToolCatalog`,
  `TerminalActionToolExecutor`, `TerminalActionTurnNote`, `TerminalActionQueryService`,
  `TerminalActionResponse`, `TerminalActionBoardResponse`, contrôleurs ; front modèles, service,
  panneau, bande, carte du fil.

### Préoccupations transversales
- **Contexte tenant** : oui — `TerminalActionSubjectResolver` (liens lus sous `user_id` + `host_id` du
  terminal possédé), `TerminalActionQualifier` (sous `user_id`), `TerminalActionQueryService`
  (plans lus sous `user_id`). Auth / plans / navigation : non.

## Hors périmètre
Les interlocuteurs normalisés (SF-180-03) ; le registre (SF-180-04).

## Arbitrages (réversibles)
- Reprise = attentes **ouvertes** sans nature (les fermées n'ont plus à être qualifiées).
- Sujet posé seulement s'il est **unique** : mieux vaut nul qu'un mauvais rattachement.
- L'étape bloquée est **calculée à la lecture** (pas stockée) : le plan peut changer.
