# Mini-spec — F-235 / SF-235-06 — Le fil, côté terminal (D12)

## Identifiant
`F-235 / SF-235-06` · Feature parente `F-235` · Statut `in-review` · 2026-11-01 · Branche `feat/SF-235-06-fil-terminal`

## Objectif
> Que le terminal montre ce que Jarvis a fait : quelles réponses étaient rapides (non vérifiées) et quels tours Opus
> il a lancés, dans le fil et dans l'encart « Pendant ton absence ».

## Comportement attendu
### Cas nominal
- `GET /api/workspaces/{id}/chat` : chaque message porte `brain` (`FAST`, `DELEGATED`, absent sinon — champ additif).
- Fil du terminal : sous « Votre question · 🎙 via Jarvis », la mention `· réponse rapide` (`FAST`) ou
  `· → délégué à Opus` (`DELEGATED`) — typographie seule, aucune couleur nouvelle (classe `terminal-via-jarvis`
  existante). La question déléguée **est** le tour Opus : elle porte sa réponse juste dessous (lien naturel du fil).
- Encart « Pendant ton absence, via Jarvis » (`GET …/jarvis/absence`) : chaque demande porte `brain` ; une réponse
  rapide est rendue telle quelle (pas de ligne 🎙), la méta ajoute « réponse rapide » ou « délégué à Opus ».

### Cas d'erreur
| Situation | Comportement |
|---|---|
| Message d'avant F-235 | aucun `brain`, affichage inchangé |

## Critères d'acceptation
- [x] `brain` exposé dans l'historique (absent quand nul).
- [x] Le fil distingue réponse rapide et délégation à Opus.
- [x] L'encart distingue réponses rapides et délégations ; la réponse rapide est lisible.

## Plan de test
- Intégration `JarvisReturnApiIntegrationTest` : `brain` dans l'historique et l'encart (sous `user_id`, tests
  d'isolation existants inchangés).
- Front : `thread-item.spec.ts` (mapping), `terminal-jarvis-absence.component.spec.ts` (méta), suite `atelier`
  (1 733 tests).

## Tables / endpoints / composants
Aucune table. `AtelierMessageResponse` (+ `brain`), `JarvisReturnService.Turn` (+ `brain`). Front :
`atelier.models.ts`, `atelier.types.ts`, `thread-item.ts`, `atelier-terminal.component.html`, `jarvis.models.ts`,
`terminal-jarvis-absence.component.ts`.

## Préoccupations transversales
Aucune (lecture sous `user_id` existante, aucune route ni garde modifiée).

## Hors périmètre
Mesure (SF-235-07).

## Arbitrages (par défaut, réversibles)
- Pas de lien explicite « vers le tour » : la question `DELEGATED` est elle-même le début du tour Opus dans le fil ;
  le libellé de délégation n'est pas persisté (SF-235-04).
