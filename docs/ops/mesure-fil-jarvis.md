# La mesure du fil Jarvis (F-245 / SF-245-05)

Le cadrage F-245 (§5, §8) demande de **mesurer avant tout levier** : le coût de cache des tours d'Opus dans le fil
Jarvis comparé aux sujets, et le nombre d'échanges sans tâche écrits dans un sujet (cible : 0). À relever à **J+7**
après le déploiement de F-245. Les requêtes sont en lecture seule (`q-prod-ro.sh`) et toujours bornées à un compte
(`:user_id`, l'UUID du PO). Elles ne lisent que des nombres et des marques, jamais de contenu.

Rappel : dans `usage_turns`, `input_tokens` est l'entrée **hors cache** (la boucle enregistre
`input − cache_read − cache_write`). L'entrée totale vaut donc `input_tokens + cache_read_tokens + cache_write_tokens`.

## 1. `cache_write / input` : fil Jarvis contre sujets (7 jours)

```sql
SELECT CASE WHEN w.jarvis_thread_user_id IS NOT NULL THEN 'fil Jarvis' ELSE 'sujets' END AS lieu,
       count(*) AS tours,
       sum(u.cache_write_tokens) AS cache_ecrit,
       sum(u.input_tokens + u.cache_read_tokens + u.cache_write_tokens) AS entree_totale,
       round(100.0 * sum(u.cache_write_tokens)
             / nullif(sum(u.input_tokens + u.cache_read_tokens + u.cache_write_tokens), 0), 1) AS pct_ecrit
FROM usage_turns u
JOIN workspaces w ON w.id = u.workspace_id AND w.user_id = u.user_id
WHERE u.user_id = :user_id
  AND u.occurred_at > now() - interval '7 days'
GROUP BY 1
ORDER BY 1;
```

Lecture : un `pct_ecrit` du fil Jarvis nettement plus haut que celui des sujets dit que ses tours, épars, trouvent le
cache froid (cadrage §5 c). **Aucun levier sans cette mesure** (pas de « nouveau départ » automatique, variante de
F-117, tant qu'elle ne le justifie pas).

## 2. Échanges sans tâche écrits dans un sujet (cible : 0)

Depuis SF-245-02, les échanges du cerveau rapide (`brain = 'FAST'`) ne s'écrivent plus que dans le fil Jarvis.

```sql
SELECT date_trunc('day', m.created_at) AS jour, count(*) AS echanges_rapides_dans_un_sujet
FROM atelier_messages m
JOIN workspaces w ON w.id = m.workspace_id AND w.user_id = m.user_id
WHERE m.user_id = :user_id
  AND m.brain = 'FAST'
  AND w.jarvis_thread_user_id IS NULL
  AND m.created_at > now() - interval '7 days'
GROUP BY 1
ORDER BY 1;
```

Attendu : aucune ligne après le déploiement (les lignes plus anciennes restent où elles sont, D12).

## 3. Les lignes-liens et leur issue (7 jours)

```sql
SELECT link_status, count(*) AS liens
FROM atelier_messages
WHERE user_id = :user_id
  AND role = 'LINK'
  AND created_at > now() - interval '7 days'
GROUP BY link_status
ORDER BY liens DESC;
```

Des liens restés `EN_COURS` au-delà de quelques heures signalent un tour délégué jamais lancé par l'écran ou coupé
par un redémarrage (le registre des délégations est en mémoire, arbitrage SF-245-03).
