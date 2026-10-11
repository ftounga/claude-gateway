# Mini-spec — [F-188 / SF-188-03] La mesure par tour

## Identifiant

`F-188 / SF-188-03`

## Feature parente

`F-188` — Les gros tours (cadrage `CADRAGE-F-188-gros-tours.md`, levier L0)

## Statut

`in-progress`

## Date de création

2026-10-11

## Branche Git

`feat/SF-188-03-mesure-par-tour`

---

## Objectif

Chaque tour d'agent laisse une ligne de mesure en base : étapes, motif d'arrêt, contexte par passe, réécritures du cache, refus et attentes du poste, recherches web. On pourra ainsi **prouver** les prochains leviers (répétitions, `clear_tool_uses`, point d'étape) avant d'y toucher, conformément à la règle « justesse avant coût ».

---

## Comportement attendu

### Cas nominal

1. En fin de tour (`runLoop`, au même point que la ligne de log « Tour d'atelier terminé »), une ligne est écrite dans **`atelier_turn_measures`** :
   - **qui** : `user_id`, `workspace_id`, `host_id` (nullable), `model`, `occurred_at` ;
   - **durée** : `duration_ms` ;
   - **étapes** : `iterations` (passes de la boucle principale) et `stop_cause` (le même libellé que le log : réponse finale, plafond d'étapes, interruption, refus du fournisseur, budget de temps…) ;
   - **contexte** : `max_context_tokens` (plus grand prompt d'une passe principale, cache compris) et `total_context_tokens` (somme sur les passes principales ; la moyenne s'en déduit) ;
   - **cache** : `first_pass_cache_write` (réécriture du cache à la 1re passe) et `later_cache_write` (somme des passes suivantes) ;
   - **totaux du tour**, délégations comprises : `input_tokens` (cache compris), `output_tokens`, `cache_read_tokens`, `cache_write_tokens`, `web_search_requests` ;
   - **poste** : `runner_refusals` (résultats `denied` rendus au modèle) et `runner_busy_waits` (attentes de SF-188-02).
2. L'écriture **ne peut jamais faire échouer le tour** : une erreur est journalisée puis ignorée.
3. Elle a lieu pour tout tour de la boucle maison, hébergé ou BYOK, interrompu compris. Aucun contenu (ni texte, ni commande) n'est enregistré : seulement des nombres et le motif d'arrêt.
4. **Lecture** : des requêtes SQL documentées dans `docs/ops/mesure-par-tour.md` :
   - tours longs ;
   - distribution des étapes et motifs d'arrêt ;
   - contexte maximal ;
   - part de réécriture du cache après la 1re passe ;
   - refus et attentes du poste.

   Lecture en SQL seulement : pas d'écran ni d'endpoint dans cette SF.
5. **Rétention** de 180 jours, purgée chaque jour par un nettoyage borné. Purge à la suppression du compte (`AccountService`), par le même patron de mutateur facultatif que F-185.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Base indisponible à l'écriture | Journal d'avertissement, tour intact |
| Tour sans aucune passe (refus avant appel) | Ligne avec `iterations = 0` |
| Compte supprimé | Ses mesures sont purgées |

---

## Critères d'acceptation

- [ ] Un tour de 3 passes écrit `iterations = 3`, le bon motif, `max_context_tokens` égal au plus grand prompt, et `first_pass_cache_write` et `later_cache_write` séparés.
- [ ] Les refus `denied` et les attentes « poste occupé » du tour sont comptés.
- [ ] Une panne d'écriture ne change rien au tour.
- [ ] Les mesures d'un compte supprimé sont purgées ; celles de plus de 180 jours aussi.
- [ ] Les requêtes de `docs/ops/mesure-par-tour.md` s'exécutent sur la base réelle (vérifié après déploiement).

---

## Plan de test

- `TurnMeasureWriterTest` (intégration, base de test) : écriture, purge par compte, purge par âge.
- `AtelierChatServiceTurnMeasureTest` (unitaire) :
  - trois passes avec usages distincts : valeurs écrites ;
  - un refus `denied` et une attente « occupé » comptés ;
  - un écrivain qui lève une exception : tour intact.
- **Isolation utilisateur** : la purge par compte ne touche que ce compte (test).

---

## Composants impactés

- **Table** `atelier_turn_measures` (migration `153-atelier-turn-measures.xml`, index `(occurred_at)` et `(user_id, occurred_at)`), à reporter dans ARCHITECTURE_CANONIQUE.
- `atelier/measure/TurnMeasure` (record), `TurnMeasureWriter` (JDBC) et `TurnMeasurePurgeWorker`.
- `AtelierChatService` : compteurs par tour, écriture en fin de tour.
- `AccountService` : purge.
- `docs/ops/mesure-par-tour.md`.

## Préoccupations transversales

Aucune (pas d'auth, de tenant, de quota ni de route modifiés). La donnée est rattachée au `user_id`.

---

## Périmètre

### Hors scope (explicite)

- Écran ou endpoint d'administration.
- `context_management.applied_edits` (effacements appliqués par le fournisseur) : non remonté par le relais actuel ; à ajouter si la décision sur `clear_tool_uses` le demande.
- Toute action à partir de la mesure (détecteur de répétition, réglages) : décision du PO après lecture.
