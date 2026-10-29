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
