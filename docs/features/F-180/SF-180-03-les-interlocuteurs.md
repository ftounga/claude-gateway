# Mini-spec — F-180 / SF-180-03 — Les interlocuteurs

## Identifiant
`F-180 / SF-180-03` — feature parente `F-180` *Les accès et les interlocuteurs* (décision **D2**).
Dépend de SF-180-01, SF-180-02 (mergées). Branche : `feat/SF-180-03-les-interlocuteurs`.
Statut : `in-progress`. Date : 2026-10-16.

## Objectif
Remplacer les noms en texte libre et dédoublés par des **interlocuteurs normalisés par poste**, alimentés
par les attentes, fusionnables sur proposition, et reliés au Radar quand il connaît la personne.

## Comportement attendu

### Cas nominal
1. **Table `interlocuteurs`** (migration `160`) : `id`, `user_id`, `host_id` (NOT NULL — un interlocuteur
   appartient au poste), `display_name`, `normalized_name` (unique par `user_id` + `host_id`),
   `variants` (formes normalisées reconnues, une par ligne), `role`, `team`, `radar_person_id`,
   `distinct_from` (identifiants des interlocuteurs déclarés « deux personnes »), `created_at`,
   `updated_at`. `terminal_actions.interlocuteur_id` (nullable).
2. **Alimentation** : à l'inscription, à l'édition de la personne et au passage à « Demandé », la personne
   de l'attente (`requested_to`, à défaut `person`) est **résolue** sur le poste : forme normalisée
   (minuscules, sans accent, espaces simples) égale au nom ou à une variante → cet interlocuteur ; sinon
   il est créé. Terminal hébergé (sans poste) : rien (le texte libre reste).
3. **Reprise** (tâche idempotente au démarrage, `InterlocuteurBackfill`) : les attentes d'un poste avec
   une personne et sans `interlocuteur_id` sont rattachées (2 000 au plus par démarrage).
4. **Lien Radar** : à la création, si `radar_people` du même poste a une personne au même nom normalisé,
   elle est reliée (`radar_person_id`) et son intitulé de poste devient le rôle par défaut. Lien
   modifiable à la main (personne du Radar **du même poste** seulement).
5. **Fusion proposée** : deux interlocuteurs du poste sont proposés quand l'un prolonge l'autre
   (« Rémi » / « Rémi Dupont »), quand les initiales concordent (« R. Dupont » / « Rémi Dupont »), ou à
   une lettre près (≥ 4 lettres : « Remy » / « Rémi »). Le plus ancien (ou le plus long nom) est gardé.
   [Fusionner] : les attentes sont reportées, les variantes ajoutées, rôle/équipe/lien Radar repris s'ils
   manquaient, le fusionné supprimé. [Ce sont deux personnes] : la paire n'est plus proposée.
6. **API** (poste = celui du terminal possédé) :
   - `GET /api/workspaces/{id}/interlocuteurs` → `[{ id, displayName, role, team, radarPersonId,
     radarName, variants, openCount }]` (vide pour un terminal hébergé) ;
   - `PATCH /api/workspaces/{id}/interlocuteurs/{iid}` `{ displayName?, role?, team?, radarPersonId? }` ;
   - `GET /api/workspaces/{id}/interlocuteurs/merge-suggestions` → `[{ keep, merge, reason }]` ;
   - `POST /api/workspaces/{id}/interlocuteurs/{iid}/merge` `{ intoId }` ;
   - `POST /api/workspaces/{id}/interlocuteurs/{iid}/distinct` `{ otherId }`.
   Les attentes du tableau portent `interlocuteurId` et `interlocuteurName`.
7. **Panneau** : une vue « Personnes » à côté des colonnes : chaque interlocuteur, rôle / équipe
   modifiables, « dans le Radar », nombre d'attentes ouvertes ; les fusions proposées en tête.

### Cas d'erreur
| Situation | Comportement |
|---|---|
| Terminal / interlocuteur d'un autre compte ou d'un autre poste | 404 |
| Fusion avec soi-même, `intoId` absent | 400 |
| `displayName` vide ou > 120 ; rôle / équipe > 120 | 400 |
| `radarPersonId` d'un autre poste | 404 |
| Renommage vers un nom déjà porté par un autre interlocuteur du poste | 400 « proposez plutôt la fusion » |

## Contraintes de validation
| Champ | Règle |
|---|---|
| `displayName` | 1–120 |
| `role`, `team` | 0–120 |
| variantes | 20 au plus par interlocuteur |

## Critères d'acceptation
- [ ] « Rémi », « remi » et « Rémi » sur trois attentes du poste = **un** interlocuteur.
- [ ] « Rémi Dupont » et « Rémi » sont proposés à la fusion ; la fusion reporte les attentes.
- [ ] Une personne du Radar au même nom est reliée.
- [ ] Les attentes existantes sont rattachées au démarrage, une seule fois.
- [ ] Bob ne lit, ne modifie ni ne fusionne les interlocuteurs d'Alice (404).

## Plan de test
- **Unitaires** : `InterlocuteurServiceTest` (normalisation, résolution, variantes, Radar, suggestions,
  fusion, deux personnes, bornes, renommage en collision) ; `InterlocuteurBackfillTest`.
- **Intégration** : `InterlocuteurApiIntegrationTest` — inscription → interlocuteur, liste, édition,
  suggestions, fusion, **isolation Bob**.
- **Front** : vue Personnes, édition, fusion, deux personnes, erreurs.

## Impacts
- **Tables** : `interlocuteurs` (nouvelle) ; `terminal_actions.interlocuteur_id`. Migration `160`.
- **Endpoints** : 5 nouveaux sous `/api/workspaces/{id}/interlocuteurs`.
- **Composants** : `Interlocuteur`, `InterlocuteurRepository`, `InterlocuteurService`,
  `InterlocuteurController`, `InterlocuteurBackfill` (nouveaux) ; `TerminalActionService` (résolution),
  `TerminalActionQueryService` (nom), `TerminalActionResponse`, `TerminalActionRepository` ; front
  modèles, service, panneau.

### Préoccupations transversales
- **Contexte tenant** : oui — `InterlocuteurService` (toute lecture sous `user_id` + `host_id` du terminal
  **possédé** ; radar_people lu sous `user_id` + `host_id`), `InterlocuteurBackfill` (transverse en
  lecture ; chaque rattachement sous le `user_id` + `host_id` **de l'attente**), `TerminalActionService`,
  `TerminalActionQueryService`. Auth / plans / navigation : non.

## Hors périmètre
Vue par interlocuteur avec relance groupée et délais (SF-180-05) ; annuaire global multi-postes ;
détection automatique dans Teams.

## Arbitrages (réversibles)
- `host_id` NOT NULL : un interlocuteur vit sur un poste ; un terminal hébergé garde le texte libre.
- « Deux personnes » retenu dans une colonne `distinct_from` plutôt qu'une table de paires.
- Lien Radar automatique seulement au **même nom normalisé** (silencieux plutôt que faux).
