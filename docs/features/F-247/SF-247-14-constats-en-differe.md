# Mini-spec — F-247 / SF-247-14 Les constats rédigés en différé (+ filtre des secrets à l'enregistrement)

## Identifiant
`F-247 / SF-247-14`

## Feature parente
`F-247` — Le registre d'état (cadrage §4.7, D1, D16, porte Z1) ; risque résiduel signalé par SF-248-09
(« le registre n'applique pas de filtre de secrets à l'enregistrement »)

## Statut
`done` (2026-11-05) · **Estimation :** 2 j (réestimé 0,75 j : file, couverture et `JarvisStateSpend` existent) ·
**Branche :** `feat/SF-247-14-constats-en-differe` · **Dépend de :** SF-247-04, SF-247-12 (`JarvisStateSpend`)

## Objectif
Couvrir la longue traîne que les extracteurs ne reconnaissent pas par des constats rédigés hors du tour, dont chaque
fait est prouvé par une citation vérifiée mot pour mot — et garantir qu'aucun secret n'entre jamais au registre, quelle
que soit la source.

## Comportement attendu

### Cas nominal
1. Après l'extraction d'un tour (`STATE_EXTRACT`, déjà hors du chemin), si au moins une sortie d'état exploitable
   (code 0, non tronquée, non vide) n'a donné aucun fait et que les constats sont actifs (coupe-circuit
   `APP_JARVIS_STATE_NOTES_ENABLED`, réglage `notes_enabled` du client), un travail `STATE_NOTES` entre dans la file
   (références seulement), au plus **200 par jour** et par compte.
2. `StateNotesJobHandler` relit le tour **sous le `user_id` du travail**, recalcule les sorties non couvertes (sans rien
   écrire), les numérote, les borne (≈ 8 000 jetons au total, 8 000 car. par sortie), vérifie le plafond propre de
   **15 €/mois** (part `ETAT`) et l'arrêt dur du budget Jarvis, puis fait **un** appel au modèle rapide via
   `AiAgentProvider` (consigne fixe `notes-v1`, 1 500 jetons de sortie).
3. **Garde de citation déterministe** (`StateNotesGuard`) : un fait n'est retenu que si sa `citation` figure **mot pour
   mot** dans la sortie numérotée, sa `valeur` dans la citation, son `objet` dans la sortie ; famille et attribut dans le
   vocabulaire fermé (jamais `ACCESS`) ; aucune forme de secret. Sinon rejeté et compté.
4. Rangement : clé `NOTE:<objet>`, source `NOTE`, confiance `MOYENNE`, `observed_at` = heure de la sortie (journal du
   runner). Coût au journal (`JARVIS/ETAT`, hors quota) et dans `jarvis_usage_days.notes_cost_usd` (total du budget).
   Plafond atteint : arrêt, notification `JARVIS_NOTES_CAP` une fois par mois.
5. **Filtre « aucun secret » à l'enregistrement** : `JarvisStateRegistry.normalize` rejette toute observation dont la
   valeur **ou la clé** ressemble à un secret (`SecretFilter`, désormais public), quelle que soit la source (tour,
   constat, relevé, veille) ; compteur `secretsRejected()`.

### Cas d'erreur
| Situation | Comportement attendu |
|---|---|
| Aucune sortie non couverte exploitable | aucun travail ; travail rejoué ⇒ `DONE` sans appel |
| Travail d'un autre compte | `DROPPED no_client`, aucun appel |
| Client coupé / constats coupés | `DROPPED disabled` |
| 200 travaux du jour | pas de mise en file |
| Plafond 15 € ou budget Jarvis dur | `DROPPED cost_cap`, une notification par mois |
| Sortie du modèle illisible | aucun fait |
| Fait déduit, reformulé, inventé, mauvaise sortie, secret, famille inconnue | rejeté, compté |
| Valeur ou clé à forme de secret, toute source | jamais écrite |

## Contraintes de validation
200 travaux/jour ; 15 €/mois ; 30 faits par réponse ; objet ≤ 200 car. ; valeur ≤ 300 ; citation ≥ 4 car. ; entrée
≤ 30 000 car.

## Critères d'acceptation
- [x] Banc NOTES : 0 fait sans citation vérifiée (5 retenus, 8 rejetés sur 13 propositions).
- [x] Un appel par travail, par `AiAgentProvider`, hors du tour.
- [x] NOTE / MOYENNE / heure de la sortie ; coût au budget Jarvis.
- [x] Isolation à deux comptes ; coupure.
- [x] Aucun secret à l'enregistrement, quelle que soit la source (valeur et clé).

## Plan de test
- Unitaires : `StateNotesBenchTest` (3 : banc NOTES, illisible, bornes).
- Intégration : `StateNotesJobIntegrationTest` (4 : fait cité seul, mise en file depuis l'extraction, isolation et
  coupure, secrets à l'enregistrement).
- Non-régression : `StateExtract*`, `JarvisState*`, `CaptureReadJobIntegrationTest`.

## Impact
- Backend : `jarvis/state/extract/StateNotesGuard`, `StateNotesJobHandler` (nouveaux) ; `StateExtractJobHandler`
  (`uncovered`, mise en file) ; `SecretFilter` (public) ; `JarvisStateRegistry` (filtre à l'enregistrement).
- Aucune table, aucun endpoint, aucune migration (colonne `notes_cost_usd` posée par SF-247-12).
- Préoccupations transversales :
  - **Contexte tenant** : `AtelierMessageRepository.findByIdAndUserId`, `WorkspaceRepository.findByIdAndUserId`,
    `JarvisStateRegistry.settingsOf` / `record` (client vérifié au compte), `runner_audit` sous `user_id`.
  - **Plans / limites** : `JarvisStateSpend` (part `NOTES` → `JarvisPart.ETAT`), `JarvisBudgetService.hardStop`.

## Arbitrages (réversibles)
- La valeur doit figurer **dans la citation** (plus strict que « dans la sortie »).
- Clé des constats `NOTE:<objet>` : un constat ne se fond jamais avec un fait d'extracteur (HAUTE).
- Le filtre de secrets s'applique aussi à la **clé** (une URL portant `api_key=` est écartée).

## Hors périmètre
L'écran de la dépense (SF-247-15 lot 2).
