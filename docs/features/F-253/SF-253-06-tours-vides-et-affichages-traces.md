# Mini-spec — F-253 / SF-253-06 Tours vides et affichages tracés

## Identifiant
`F-253 / SF-253-06`

## Feature parente
`F-253` — Jarvis montre vraiment (cadrage §3 #6, audit #47 et #48)

## Statut
`in-review` · **Estimation :** 1 j · **Branche :** `feat/SF-253-06-tours-vides-traces` · **Dépend de :** SF-253-01

## Objectif
Rendre diagnosticable un tour où le cerveau rapide n'a rien dit, et tracer chaque ordre d'écran montré ou refusé.

## Comportement attendu

### Cas nominal
1. Un tour vide part en délégation d'office comme avant ; sa délégation porte un diagnostic
   `stop=<end_turn|tool_use|max_tokens> outils=<noms|aucun> rejets=<motif:n,…|aucun>`, écrit au journal (INFO), relayé par
   l'événement `delegated` et enregistré dans `jarvis_turn_timings.empty_diagnostic`.
2. Motifs de rejet : ancrage, remplissage, répétition, imitation, portrait, allusion, texte d'outil.
3. Chaque ordre d'écran donne un événement de journal d'écoute : `show` (détail : action) ou `show_refused` (détail :
   action et raison).

### Cas d'erreur
| Situation | Comportement attendu |
|---|---|
| Diagnostic hors vocabulaire ou trop long | Colonne laissée vide, tour enregistré |
| Délégation non automatique | Aucun diagnostic |
| Refus sans raison connue de l'écran (présentation, courriel, agenda) | `show_refused` avec la raison `ecran` |

## Critères d'acceptation
- [ ] Un tour vide `max_tokens` sans outil donne `stop=max_tokens outils=aucun rejets=aucun`.
- [ ] Un tour vide après rejet par la porte nomme chaque motif et son nombre.
- [ ] La colonne `empty_diagnostic` est remplie pour ce tour, jamais avec du contenu.
- [ ] `show` et `show_refused` apparaissent au journal d'écoute pour un ordre appliqué ou refusé.

## Plan de test
- Unitaires backend : `JarvisFastBrainDiagnosticTest` (`emptyDiagnostic`), `JarvisLatencyServiceTest` (vocabulaire).
- Intégration : l'enregistrement d'une mesure passe par l'API existante (`JarvisLatencyApiIntegrationTest`), colonne additive.
- Écran : `jarvis.component.spec` (`applyShow` trace).
- Isolation utilisateur : sans objet, aucune lecture nouvelle ; la mesure reste sous le compte de l'appelant.

## Impact
Table `jarvis_turn_timings` (+ `empty_diagnostic`, migration `253-06`), `JarvisFastBrainService`,
`JarvisFastBrainController`, `JarvisLatencyService`, `JarvisTurnTiming` ; écran : `jarvis.component.ts`,
`jarvis-fast-brain.ts`, `jarvis-turn-clock.ts`, `jarvis.models.ts`. Aucune consigne ni outil du cerveau rapide : pas de banc.
Préoccupations transversales : aucune.

## Hors périmètre
Corriger les causes des tours vides ; le poste éteint (SF-253-05).
