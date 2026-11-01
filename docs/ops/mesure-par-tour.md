# La mesure par tour (F-188 / SF-188-03)

Une ligne par tour d'agent dans `atelier_turn_measures`, contenant des nombres seulement. Ces requêtes servent à **prouver** un levier avant d'y toucher (règle « justesse avant coût »). Elles s'exécutent en lecture seule (`q-prod-ro.sh`).

## 1. Les tours longs de la semaine

```sql
SELECT occurred_at, h.name AS poste, iterations, stop_cause,
       max_context_tokens, round(total_context_tokens::numeric / greatest(iterations, 1)) AS contexte_moyen,
       input_tokens + output_tokens AS tokens, runner_refusals, runner_busy_waits, web_search_requests,
       round(duration_ms / 60000.0, 1) AS minutes
FROM atelier_turn_measures m LEFT JOIN runner_hosts h ON h.id = m.host_id
WHERE occurred_at > now() - interval '7 days'
ORDER BY input_tokens + output_tokens DESC
LIMIT 20;
```

## 2. Étapes et motifs d'arrêt

```sql
SELECT stop_cause, count(*) AS tours,
       percentile_cont(0.5) WITHIN GROUP (ORDER BY iterations) AS etapes_mediane,
       max(iterations) AS etapes_max
FROM atelier_turn_measures
WHERE occurred_at > now() - interval '7 days'
GROUP BY stop_cause ORDER BY tours DESC;
```

## 3. Réécriture du cache après la première passe

C'est le levier `clear_tool_uses` et le rejeu entre tours (décision du PO, F-188).

```sql
SELECT count(*) AS tours,
       sum(first_pass_cache_write) AS ecriture_1re_passe,
       sum(later_cache_write) AS ecriture_passes_suivantes,
       round(100.0 * sum(later_cache_write) / nullif(sum(cache_write_tokens), 0), 1) AS pct_suivantes
FROM atelier_turn_measures
WHERE occurred_at > now() - interval '7 days' AND iterations > 1;
```

## 4. Le poste : refus et attentes

Effet de SF-188-02 : les refus doivent tendre vers zéro.

```sql
SELECT date_trunc('day', occurred_at) AS jour, sum(runner_refusals) AS refus,
       sum(runner_busy_waits) AS attentes, count(*) FILTER (WHERE runner_refusals >= 3) AS tours_a_3_refus_ou_plus
FROM atelier_turn_measures
WHERE occurred_at > now() - interval '14 days'
GROUP BY 1 ORDER BY 1;
```

## 5. Contexte maximal par poste

```sql
SELECT h.name AS poste, count(*) AS tours,
       percentile_cont(0.5) WITHIN GROUP (ORDER BY max_context_tokens) AS contexte_max_median,
       max(max_context_tokens) AS contexte_max
FROM atelier_turn_measures m LEFT JOIN runner_hosts h ON h.id = m.host_id
WHERE occurred_at > now() - interval '7 days'
GROUP BY h.name ORDER BY tours DESC;
```

## 6. Divergence du ruban d'un tour à l'autre (F-229 / SF-229-01)

Chaque tour range l'**empreinte** de sa dernière requête (`ribbon_fingerprint` : `[[hachage, taille], …]`, aucun contenu). Au premier appel du tour suivant **du même fil** (`user_id`, `workspace_id`, `chantier_number`), le ruban rejoué est comparé à cette empreinte :

- `prefix_divergence_index` : premier bloc du ruban précédent qui n'est pas repris (`-1` = prolongé intégralement, `NULL` = rien à comparer) ;
- `prefix_lost_chars` : caractères du ruban précédent à partir de ce bloc, donc réécrits par le fournisseur ;
- `prefix_divergence_cause` : `RESULT_TRUNCATED`, `TRACE_STEPS_DROPPED`, `REASONING_DROPPED`, `CLEARED_IN_TURN`, `COMPACTION`, `WINDOW_MOVED`, `NO_PREVIOUS`, `OTHER` (première qui s'applique) ;
- `replay_cut_chars` : caractères que le rangement du tour a retirés par rapport à ce qui a été envoyé (résultats coupés à 8 000, étapes abandonnées au plafond de 60 000). C'est le contexte qu'**ajouterait** un rejeu intégral.

Une divergence classée `OTHER` à l'index même de la consigne précédente signale une consigne **augmentée à l'envoi** (note de chantier, parcours, contexte du sujet…) alors que l'historique ne rejoue que la parole de l'utilisateur.

**Part des caractères perdus, par cause et par délai entre tours** (7 jours). Le délai se mesure de la fin du tour précédent du fil au début de celui-ci. La colonne `ecriture_1re_passe` permet le rapprochement avec `first_pass_cache_write` (des tokens, quand `prefix_lost_chars` est en caractères : compter ~3,5 caractères par token).

```sql
WITH fil AS (
    SELECT m.*,
           (occurred_at - duration_ms * interval '1 millisecond')
             - lag(occurred_at) OVER (PARTITION BY user_id, workspace_id, chantier_number ORDER BY occurred_at)
             AS delai
    FROM atelier_turn_measures m
    WHERE occurred_at > now() - interval '8 days'
)
SELECT coalesce(prefix_divergence_cause, 'PROLONGE') AS cause,
       CASE WHEN delai IS NULL THEN 'premier'
            WHEN delai < interval '5 minutes' THEN '< 5 min'
            WHEN delai < interval '60 minutes' THEN '5-60 min'
            ELSE '> 60 min' END AS delai,
       count(*) AS tours,
       sum(prefix_lost_chars) AS car_perdus,
       round(100.0 * sum(prefix_lost_chars) / nullif(sum(sum(prefix_lost_chars)) OVER (), 0), 1) AS pct_perdus,
       sum(first_pass_cache_write) AS ecriture_1re_passe
FROM fil
WHERE occurred_at > now() - interval '7 days' AND prefix_divergence_cause IS DISTINCT FROM 'NO_PREVIOUS'
GROUP BY 1, 2 ORDER BY car_perdus DESC NULLS LAST;
```

**Ce que coûterait un rejeu intégral** (porte de décision de SF-229-02) : le contexte qu'il ajouterait, sur 7 jours.

```sql
SELECT count(*) AS tours,
       count(*) FILTER (WHERE replay_cut_chars > 0) AS tours_coupes,
       sum(replay_cut_chars) AS car_coupes,
       percentile_cont(0.5) WITHIN GROUP (ORDER BY replay_cut_chars)
         FILTER (WHERE replay_cut_chars > 0) AS median_si_coupe
FROM atelier_turn_measures
WHERE occurred_at > now() - interval '7 days' AND replay_cut_chars IS NOT NULL;
```

Les colonnes sont nulles pour les tours d'avant F-229, les tours coupés et en cas de panne du calcul (best-effort).

## Par fil de chantier (F-221 / SF-221-01)

`chantier_number` dit dans quel fil le tour a tourné (`NULL` = fil commun du sujet). La part relue du cache, par fil, sur 7 jours :

```sql
SELECT coalesce(chantier_number::text, 'commun') AS fil, count(*) AS tours,
       round(100.0 * sum(cache_read_tokens)
             / nullif(sum(cache_read_tokens + cache_write_tokens + input_tokens), 0), 1) AS relu_pct,
       sum(cache_write_tokens) AS ecrit
FROM atelier_turn_measures
WHERE occurred_at > now() - interval '7 days'
GROUP BY 1 ORDER BY 2 DESC;
```
