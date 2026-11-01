# SF-229-01 — Mesurer où et pourquoi le ruban diverge d'un tour à l'autre

**Feature :** F-229 · **Statut :** ready · **Estimation :** ≤ 1 j · **Branche :** `feat/SF-229-01-divergence-du-ruban`

## Objectif

Pour chaque tour, savoir à quel bloc l'historique rejoué cesse de coïncider avec ce qui a été mis en cache au tour précédent du même fil, combien de contexte cela fait réécrire, et pourquoi.

## Comportement

1. **Fin de tour.** On calcule l'**empreinte du ruban** de la dernière requête envoyée : pour chaque bloc des `messages` (hors consigne système et outils), un couple (hachage SHA-256 tronqué du bloc canonique sérialisé tel qu'envoyé, nombre de caractères).
   - L'empreinte est stockée dans la ligne `atelier_turn_measures` du tour (colonne `ribbon_fingerprint`, JSON de hachages et de tailles, **sans aucun contenu**).
2. **Premier appel du tour suivant** du même fil (`user_id`, terminal, `chantier_number`). On calcule l'empreinte du ruban construit par `buildReplayMessages` et on la compare à celle du tour précédent :
   - `prefix_divergence_index` : index du premier bloc différent, ou `-1` si le ruban précédent est intégralement prolongé ;
   - `prefix_lost_chars` : caractères du ruban précédent situés après la divergence, donc ceux qui seront réécrits ;
   - `prefix_divergence_cause`, la première cause qui s'applique :
     - `RESULT_TRUNCATED` : le bloc rejoué est un `tool_result` qui se termine par `TRUNCATION_MARK` ;
     - `TRACE_STEPS_DROPPED` : la trajectoire stockée a perdu des étapes (plafond `MAX_TRACE_CHARS`) ;
     - `REASONING_DROPPED` : un raisonnement présent pendant le tour est absent au rejeu ;
     - `CLEARED_IN_TURN` : `clear_tool_uses` a été appliqué pendant le tour précédent ;
     - `COMPACTION` : le résumé de tête a changé ;
     - `WINDOW_MOVED` : `firstTracedIndex` a bougé ;
     - `NO_PREVIOUS` : pas d'empreinte précédente (premier tour, ancienne ligne) ;
     - `OTHER`.
3. **Requête de lecture** ajoutée à `docs/ops/mesure-par-tour.md` (§6) : sur 7 jours, la part de `prefix_lost_chars` par cause et par délai entre tours. Elle doit pouvoir se rapprocher de `first_pass_cache_write`.
4. **Best-effort.** Un échec de calcul n'altère jamais le tour (journal en `debug`, colonnes laissées à `NULL`). La requête envoyée au fournisseur est **identique à l'octet** à celle d'avant.

## Erreurs

| Situation | Comportement |
|---|---|
| Échec du hachage ou de la sérialisation | Tour inchangé, colonnes `NULL` |
| Fil sans tour précédent mesuré | `NO_PREVIOUS`, index `NULL` |
| Empreinte précédente illisible | `NO_PREVIOUS` |

## Critères d'acceptation

- [ ] Deux tours consécutifs sans sortie d'outil longue : `prefix_divergence_index = -1` et `prefix_lost_chars = 0`.
- [ ] Un tour dont un `bash` rend plus de 8 000 caractères, suivi d'un autre tour : cause `RESULT_TRUNCATED`, avec un `prefix_lost_chars` au moins égal au surplus tronqué.
- [ ] Une compaction entre deux tours : cause `COMPACTION`.
- [ ] Deux chantiers parallèles d'un même terminal : chaque fil est comparé à **son** tour précédent, jamais à celui de l'autre chantier.
- [ ] Le corps envoyé au fournisseur est identique avant et après la SF (test de non-régression sur le corps sérialisé).
- [ ] Aucune colonne ne contient de texte issu de la conversation : seulement des hachages et des nombres.
- [ ] Isolation : un tour de Bob n'est jamais comparé à une empreinte d'Alice.

## Plan de test

- **Unitaires** :
  - `RibbonFingerprint` : hachage stable, ordre des blocs, détection de la première divergence ;
  - classification de la cause, un cas par valeur.
- **Intégration** : `TurnMeasureWriterIntegrationTest` étendu avec deux tours réels sur fournisseur bouchon (cas nominal, troncature, compaction, deux chantiers, isolation entre utilisateurs).
- **Suite backend complète** (un second constructeur ne se voit qu’avec la suite complète).

## Impact

- `atelier/measure/TurnMeasureWriter` et l'enregistrement de mesure (champs ajoutés).
- `AtelierChatService` (calcul à la fin du tour et au premier appel).
- Nouvelle classe `atelier/measure/RibbonFingerprint`.
- Migration : 4 colonnes nullables sur `atelier_turn_measures` (`ribbon_fingerprint` text, `prefix_divergence_index` int, `prefix_lost_chars` bigint, `prefix_divergence_cause` varchar(32)). Prendre le prochain numéro libre.
- `docs/ops/mesure-par-tour.md` §6.
- **Préoccupations transversales** : aucune (ni auth, ni tenant nouveau, ni plans, ni navigation).

## Hors périmètre

- Toute correction de la divergence : c'est SF-229-02, conditionnelle.
- Les appels de relance « cache au chaud » : écartés par la mesure (voir le cadrage).
- La sous-délégation (explore) et la compaction elle-même.
