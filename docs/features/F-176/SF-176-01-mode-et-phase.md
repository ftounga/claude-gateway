# Mini-spec — F-176 / SF-176-01 — Le mode et la phase d'un sujet

## Identifiant
`F-176 / SF-176-01` — feature parente `F-176` *Le parcours du sujet* — dépend de **F-175** (Terminée).
Branche : `feat/SF-176-01-mode-et-phase`. Statut : `in-progress`. Date : 2026-10-05.

## Objectif
Donner à chaque terminal un **mode de sujet** (Libre par défaut, ou Guidé) et, en Guidé, une
**phase** (Investigation → Plan → Exécution → Vérification → Clos), visibles dans l'en-tête et
changeables à tout moment par le menu du terminal.

## Comportement attendu

### Cas nominal
1. **État en base** : table `subject_journeys`, une ligne par terminal (`workspace_id` = clé,
   `user_id`). **Pas de ligne = Libre** ; la lecture n'écrit jamais.
2. `GET /workspaces/{wid}/journey` → `{mode, phase, phaseLabel, phaseChangedAt}` (Libre, phase `null`
   si rien n'a été décidé).
3. `PUT /workspaces/{wid}/journey/mode {mode: LIBRE|GUIDE}` :
   - vers **Guidé** : phase `INVESTIGATION` si le sujet n'a jamais été guidé ou était `CLOS` ; sinon
     reprise de la phase où il était ;
   - vers **Libre** : la phase et le plan sont gardés (la porte cesse simplement de s'appliquer) ;
   - même mode qu'avant : rien n'est écrit.
   - chaque changement est journalisé (`subject_journey_events`, type `MODE_CHANGED`).
4. **L'agent lit le parcours** : en Guidé, un bloc « Parcours du sujet — Mode GUIDÉ · phase : X »
   est préfixé à la **consigne du tour** (jamais au système : cache F-134/F-171). En Libre : rien,
   consigne inchangée à l'octet près (Q4).
5. **Écran** : dans l'en-tête, un bouton « Libre » / « Guidé · Investigation » ouvre le menu (Libre /
   Guidé, avec une phrase chacun) ; au-dessus de la saisie, en Guidé seulement, la bande des phases
   marque la phase courante. Le parcours est relu à l'ouverture du terminal et à chaque fin de tour.

### Cas d'erreur
| Situation | Comportement |
|---|---|
| mode absent ou inconnu | 400 `journey_invalid`, message lisible |
| terminal d'autrui (lecture ou écriture) | 404, rien n'est écrit |
| échec de lecture du parcours pendant un tour | best-effort : consigne inchangée, le tour continue |
| échec de lecture à l'écran | l'affichage reste tel quel (jamais un faux « Guidé ») |
| journal indisponible | best-effort : le geste s'applique quand même |

## Critères d'acceptation
- [ ] Un terminal jamais décidé est Libre, sans phase, et rien n'est écrit en lisant.
- [ ] Passer en Guidé ouvre l'Investigation ; repasser en Libre garde la phase.
- [ ] Le mode est visible dans l'en-tête (desktop et mobile) et changeable par le menu.
- [ ] En Guidé, l'agent voit mode et phase dans la consigne du tour ; en Libre, rien.
- [ ] **ISOLATION** : le terminal d'Alice est introuvable pour Bob (GET et PUT → 404).

## Plan de test
- **Unitaires** : `JourneyTurnNoteTest` (Libre vide, Guidé rendu, lecture du mode).
- **Intégration** : `SubjectJourneyApiIntegrationTest` (défaut Libre sans écriture, bascule et
  journal, mode inconnu 400, Bob 404).
- **Front** : `terminal-journey.spec.ts` (libellé, pastille n'émet que sur changement, bande absente
  en Libre / phase courante marquée, service sans identifiant de compte).
- Suites complètes : `mvn -pl backend test`, `npm run build && npm test`.

## Contraintes de validation
| Champ | Règle |
|---|---|
| `mode` | `LIBRE` \| `GUIDE` (tolère « guidé », casse indifférente) |
| `phase` | `INVESTIGATION` \| `PLAN` \| `EXECUTION` \| `VERIFICATION` \| `CLOS` \| `null` |

## Impacts
- **Migration `145-subject-journeys.xml`** (n° pré-assigné pour toute la feature) : tables
  `subject_journeys` et `subject_journey_events`. Les colonnes des SF suivantes (proposition du
  guidé, plan et validation, diagnostic) sont posées ici, nullables, pour ne pas multiplier les
  numéros. Clés étrangères vers `workspaces` en cascade ; purge nommée à la suppression du compte.
  Rollback : `dropTable`.
- Backend : paquet `atelier.journey` (entité, dépôts, service, contrôleur, gestionnaire d'erreurs,
  `JourneyTurnNote`) ; `AtelierChatService.withJourney` ; `AccountService` (purge).
- Front : `journey.models.ts`, `journey.service.ts`, `terminal-journey-chip`, `terminal-journey-strip`,
  branchement dans `atelier-terminal`.

### Préoccupations transversales
- Contexte tenant : **oui** — nouvelle table sous `user_id`. Composants : `SubjectJourneyService`
  (`requireOwned` d'abord, puis `findByUserIdAndWorkspaceId`), `AtelierChatService.journeyOf`
  (terminal possédé du tour), `AccountService` (purge).
- Navigation : non (aucune route). Auth : non. Plans/limites : non.

## Hors périmètre
Qualification et carte [Passer en guidé] (SF-176-02) ; plan structuré (SF-176-03) ; porte (SF-176-04) ;
transitions (SF-176-05) ; mesure (SF-176-06).

## Arbitrages (réversibles)
- **Le sujet = le terminal** : une ligne par `workspace_id`. Un « Nouveau départ » ne remet pas le
  parcours à zéro (il repart sans contexte, le sujet reste le même) ; l'utilisateur repasse en Libre
  ou rouvre en Guidé s'il le souhaite.
- **Une seule migration pour la feature** (colonnes des SF suivantes nullables posées dès 145) : moins
  de numéros à réserver dans une vague parallèle.
- Les colonnes longues sont en `varchar(1000000)` (convention H2/PostgreSQL du dépôt, migration 107/139).
