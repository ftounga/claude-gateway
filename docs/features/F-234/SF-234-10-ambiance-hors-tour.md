# Mini-spec — F-234 / SF-234-10 — Ambiance hors tour (A1-A5)

## Identifiant
`F-234 / SF-234-10`

## Feature parente
`F-234` — L'écran vivant de Jarvis (cadrage §3.3, §6.1, D14, D-PO-6 ; vague C)

## Statut
`in-review`

## Date de création
2026-11-01

## Branche Git
`feat/SF-234-10-ambiance-hors-tour`

---

## Objectif

> Au repos, l'écran Jarvis vit avec l'application : le poste qui ne répond pas s'assombrit, les autres sujets qui
> travaillent respirent, un sujet qui appelle clignote une fois, le coût du tour s'écrit (administrateur), la veille
> qui sonne envoie une onde de son client vers l'orbe — sans aucun relevé nouveau.

## Comportement attendu

- **D14** : rien de tout cela **pendant un tour** (`turnActive`), sauf A5 à l'ouverture par alerte.
- **A1 Poste** : déjà, client déconnecté atténué (univers) ; en plus, un tour arrêté par la **porte du runner**
  (F-161 : `runner_offline`, `runner_missing_capability`, `runner_unresponsive`) assombrit le client du sujet
  (liseré pointillé) et écrit « Le poste X ne répond pas » ; le tour suivant abouti l'efface.
- **A2 Terminaux vivants** : un sujet respire si l'univers le dit `live` **ou** s'il figure au registre des
  terminaux vivants **déjà relevé** par l'application (`LiveTerminalService.registry`, F-70) — jamais le sujet courant.
- **A3 Cloche** : une **nouvelle** notification `AUTHORIZATION_REQUESTED` / `QUESTION_ASKED` / `QUESTION_REMINDER` /
  `PLAN_AWAITING` / `VALIDATION_AWAITING` (centre F-185 **déjà relevé**) d'un **autre** sujet ⇒ il clignote une fois
  (3 s) et « Doc archi vous appelle » ; le premier relevé n'est jamais une nouveauté.
- **A4 Coût du tour** : `done.costEur` (servi à l'administrateur seul, F-133) ⇒ « ≈ 0,42 € » discret sous la scène,
  jusqu'au tour suivant ; absent ⇒ rien.
- **A5 La veille sonne** : `?alerte=` (SF-231-05) ⇒ le client du sujet de l'alerte s'allume, une onde part vers
  l'orbe, « Veille Jira AGENOR — 2 changements · CAGIP ».
- **Aucun relevé nouveau** : l'écran lit les **signaux** des services existants (`NotificationCenterService.items`,
  `LiveTerminalService.registry`) sans démarrer leur relevé ; hors coquille, s'ils ne tournent pas, l'ambiance A2/A3
  se tait (l'univers relu à chaque fin de tour reste la source).

### Cas d'erreur
| Situation | Comportement |
|---|---|
| Notification sans sujet, ou du sujet courant | rien |
| Alerte d'un sujet inconnu de l'univers | ligne écrite, aucune onde |
| Autre erreur de tour (quota, refus) | pas d'A1 |

## Critères d'acceptation
1. Aucune scène A pendant un tour.
2. Un autre sujet actif au registre ⇒ il respire.
3. `QUESTION_ASKED` d'un autre sujet ⇒ il clignote une fois.
4. `costEur` absent (non-admin) ⇒ rien.
5. `?alerte=` ⇒ A5 sur le bon client.
6. Aucun relevé nouveau (espion HTTP : seule la relecture de l'univers en fin de tour).

## Plan de test
- `jarvis-ambiance.spec.ts` : pendant/au repos, coût admin/non-admin, A2, A3 (premier relevé, autre sujet, durée),
  A1 + A5 rendus ; espion `HttpTestingController.verify()`.
- `jarvis.component.spec.ts` : services de relevé fournis par leurs signaux (aucun appel), non-régression.
- Suite `jarvis/**`, build.

## Impacts
- **Tables / endpoints** : aucun. **Back** : aucun.
- **Front** : fil (`ambient`, `turnActive`, `notifications`, `liveTerminals`, `alert`, `done(costEur)`,
  `aborted(code)`), constellation (`is-down`, `is-ringing`, onde, `is-calling`, respiration), scène (ligne
  d'ambiance), `jarvis.component.ts` (relais).

## Préoccupations transversales
- Plans / limites : **non** (le coût affiché vient de `done.costEur`, déjà réservé à l'administrateur côté passerelle).
- Les autres : non.

## Arbitrages (réversibles)
- Lire les signaux des relevés existants plutôt que relire l'univers périodiquement (« aucun relevé nouveau »).
- A3 limité aux événements qui **demandent** (ACTION_EVENTS F-185), pas aux simples réponses arrivées.

## Hors périmètre
Sonnerie (D-PO-5 : aucun effet sonore) ; ouvrir le sujet qui appelle (toucher un sujet suffit, D12).
