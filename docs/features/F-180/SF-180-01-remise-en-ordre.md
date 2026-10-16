# Mini-spec — F-180 / SF-180-01 — Remise en ordre

## Identifiant
`F-180 / SF-180-01` — feature parente `F-180` *Les accès et les interlocuteurs* (cadrage
`CADRAGE-F-180-les-acces-et-les-interlocuteurs.md`, décision **D8**). Évolution de F-175.
Branche : `feat/SF-180-01-remise-en-ordre`. Statut : `in-progress`. Date : 2026-10-16.

## Objectif
Remettre le fil des attentes en ordre **par du code livré** — jamais par une écriture manuelle en
prod : purge des « à vérifier » restés en suspens, embeddings manquants rattrapés, doublons détectés
et **proposés** à la fusion.

## Comportement attendu

### Cas nominal
1. **Purge des `review_pending`** (migration Liquibase `158`) : les attentes encore marquées « à
   vérifier » depuis la reprise de F-175 (12 en prod) quittent cet état — **leur état n'est pas
   modifié** (elles restent À faire / Demandé telles quelles). Idempotent (`UPDATE … WHERE
   review_pending = true`). La reprise de SF-175-07 ne se représente plus.
2. **Backfill des embeddings** (tâche idempotente au démarrage, `TerminalActionEmbeddingBackfill`) :
   au `ApplicationReadyEvent`, si le chemin sémantique est actif (clé d'embeddings présente), les
   attentes **de tout état** sans vecteur sont vectorisées par lots (50), au plus 500 par démarrage,
   sur l'exécuteur `atelierEmbeddingExecutor` (jamais sur le fil de démarrage). Chaque vecteur est
   rangé sur **sa propre ligne** — aucune donnée ne passe d'un compte à l'autre. Sans clé, ou en H2 :
   rien. Coupe-circuit `app.atelier.actions.embedding-backfill.enabled` (défaut `true`).
3. **Doublons proposés à la fusion** (`TerminalActionDuplicateService`) : dans la portée du terminal
   (son poste, ou lui seul s'il est hébergé), deux attentes **ouvertes** sont un doublon probable si :
   même clé normalisée (`KEY`), ou mêmes mots significatifs (Jaccard ≥ 0,6 sur les mots de ≥ 3 lettres
   hors mots vides, `WORDS`), ou même sens (distance cosine ≤ 0,30 si le sémantique est actif,
   `MEANING`). La plus ancienne est gardée, la plus récente est proposée à la fusion.
   - `GET /api/workspaces/{id}/actions/duplicates` → `[{ keep, duplicate, reason }]` (20 au plus).
   - `POST /api/workspaces/{id}/actions/{actionId}/merge` `{ "intoId": "…" }` : l'attente fusionnée
     passe **Annulé** avec la raison « Fusionnée avec « … » » et `merged_into_id` ; la gardée hérite
     de la demande (si la fusionnée était « Demandé » et la gardée « À faire » : à qui, par où, quand)
     et de « ce que ça débloque » s'il lui manquait.
   - `POST /api/workspaces/{id}/actions/{actionId}/not-duplicate` : « Ce n'est pas pareil » —
     `duplicate_dismissed_at` posé, la paire n'est plus proposée.
4. **Panneau** : section « Doublons probables (N) » au-dessus des colonnes : les deux énoncés, la
   raison (« même clé », « mêmes mots », « même sens »), [Fusionner] [Ce n'est pas pareil].

### Cas d'erreur
| Situation | Comportement |
|---|---|
| Terminal d'un autre compte | 404 (`requireOwned` / lecture sous `user_id`) |
| `intoId` absent, égal à l'attente, hors portée (autre poste, autre compte) | 400 « Fusion impossible » / 404 |
| Une des deux attentes déjà fermée | 400 « Seules deux attentes ouvertes se fusionnent » |
| Fournisseur d'embeddings en panne au backfill | journal `debug`, démarrage intact, reprise au démarrage suivant |
| Sémantique éteint | détection par clé et mots seulement |

## Contraintes de validation
| Champ | Règle |
|---|---|
| `intoId` | UUID requis, ≠ `actionId`, même portée |
| Backfill | 500 lignes max par démarrage, lots de 50 |
| Doublons | 20 paires max ; seuil sémantique `app.atelier.actions.duplicate-max-distance` (0,30) |

## Critères d'acceptation
- [ ] Après migration, aucune attente n'est `review_pending` ; aucun statut n'a changé.
- [ ] Au démarrage avec clé, les attentes sans vecteur (ouvertes **et** fermées) sont vectorisées ; sans clé, aucun appel.
- [ ] Deux attentes ouvertes de même clé (ou mêmes mots) sur le poste sont proposées ; la plus ancienne est gardée.
- [ ] [Fusionner] annule la plus récente avec sa raison et transfère la demande ; [Ce n'est pas pareil] ne la repropose plus.
- [ ] Bob ne voit ni ne fusionne les attentes d'Alice (404).

## Plan de test
- **Unitaires** : `TerminalActionDuplicateServiceTest` (clé, mots, seuil de Jaccard, garde/ancienneté,
  fusion avec héritage de la demande, refus fermée / hors portée / soi-même, écartée non reproposée) ;
  `TerminalActionEmbeddingBackfillTest` (éteint sans clé, lots, borne, échec silencieux).
- **Intégration** : `TerminalActionDuplicateApiIntegrationTest` — paire proposée, fusion, pas-pareil,
  **isolation Bob → 404**. Migration rejouée par le contexte de test (H2).
- **Front** : panneau — section doublons, fusion optimiste + retour arrière en erreur, pas-pareil.

## Impacts
- **Table** `terminal_actions` : colonnes `merged_into_id uuid`, `duplicate_dismissed_at timestamptz`
  (migration `158-terminal-actions-hygiene.xml`, réversible : `dropColumn`).
- **Endpoints** : 3 nouveaux sous `/api/workspaces/{id}/actions`.
- **Composants** : `TerminalActionDuplicateService` (nouveau), `TerminalActionEmbeddingBackfill`
  (nouveau), `TerminalActionEmbeddingStore` (lectures sans filtre d'état, paires proches),
  `TerminalActionController` ; front `terminal-actions.models`, `terminal-actions.service`,
  `terminal-actions-panel`.

### Préoccupations transversales
- **Contexte tenant** : oui — composants : `TerminalActionDuplicateService` (portée = poste **du
  terminal possédé**, `user_id` sur chaque lecture), `TerminalActionEmbeddingBackfill` (transverse en
  lecture, écriture ligne à ligne sur son `id`, aucun contenu d'un compte vers un autre),
  `TerminalActionEmbeddingStore`. Auth / plans / navigation : non.

## Hors périmètre
Suppression d'attentes ; fusion automatique sans geste ; fusion d'interlocuteurs (SF-180-03).

## Arbitrages (réversibles)
- « Purge » = **retrait du marqueur** « à vérifier », pas suppression des attentes (non destructif).
- Seuil de proposition sémantique 0,30 (plus large que 0,20 du dédoublonnage à l'inscription : ici
  l'utilisateur valide).
- La fusionnée passe **Annulé** (pas Fait) : elle n'a pas été obtenue, elle était en double.
