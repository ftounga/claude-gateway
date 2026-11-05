# Inventaire des commandes d'état (F-247 / SF-247-04, SF-247-05)

But : classer, sur 30 jours, les appels `bash` **réels** par forme de commande, pour écrire les extracteurs des formes
qui reviennent — sans en supposer d'autres (cadrage F-247 §4.2). Lecture seule, à lancer sur la base de production
par l'administrateur (jamais par un agent de vague).

## 1. Les formes, par fréquence (30 j)

`runner_audit.target` porte la commande `bash` (bornée à 1 000 caractères). La forme = l'outil et son sous-verbe, ou
le chemin d'API générique.

```sql
WITH calls AS (
  SELECT lower(split_part(regexp_replace(target, '^(sudo\s+)?(\w+=\S+\s+)*', ''), ' ', 1)) AS tool,
         lower(split_part(regexp_replace(target, '^(sudo\s+)?(\w+=\S+\s+)*', ''), ' ', 2)) AS verb,
         substring(target from '(/rest/api/\w+/\w+|/api/v4/\w+|/repos/[^/ ]+/[^/ ]+/\w+|/rest/api/content)') AS api,
         outcome, exit_code
  FROM runner_audit
  WHERE tool = 'bash' AND created_at >= now() - interval '30 days'
)
SELECT CASE WHEN tool IN ('curl', 'invoke-restmethod', 'irm') THEN 'curl ' || coalesce(api, '?')
            ELSE tool || ' ' || coalesce(nullif(verb, ''), '') END AS form,
       count(*) AS calls,
       count(*) FILTER (WHERE exit_code = 0) AS ok
FROM calls
WHERE tool IN ('jira', 'acli', 'glab', 'gh', 'kubectl', 'oc', 'helm', 'argocd', 'aws', 'az', 'terraform',
               'psql', 'liquibase', 'flyway', 'git', 'curl', 'invoke-restmethod', 'irm')
GROUP BY 1
ORDER BY calls DESC
LIMIT 80;
```

## 2. La couverture réelle, une fois l'extraction active

`GET /api/admin/jarvis/state/measure?days=7` → `coverage` : appels d'état vus, couverts (au moins un fait), faits, taux,
et les 20 formes les plus fréquentes **restées sans fait** (`uncoveredForms`). C'est la liste de travail de
l'extracteur suivant ; ce que les extracteurs ne savent pas lire part aux constats rédigés (SF-247-14).

```sql
SELECT form, sum(calls) AS calls, sum(covered) AS covered, sum(facts) AS facts
FROM jarvis_state_coverage
WHERE day >= current_date - 7
GROUP BY form ORDER BY calls DESC;
```

## 3. Ce que les extracteurs lisent (lots 1 et 2)

| Famille | Formes reconnues | Sortie exigée |
|---|---|---|
| Tickets | `curl …/rest/api/2|3|latest/issue|search`, `jira … --json/--raw`, `acli jira … --json` | JSON |
| Pages | `curl …/rest/api/content`, `…/wiki/api/v2/pages` | JSON |
| Revues | `glab mr view|list -F json`, `…/merge_requests`, `gh pr view|list --json`, `…/repos/…/pulls` | JSON |
| CI | `…/pipelines`, `…/jobs`, `glab ci list|view -F json`, `gh run list|view --json`, `…/actions/runs` | JSON |

Les formes en texte libre ne sont lues que sur un motif strict prouvé par un échantillon réel : en cas de doute, rien
n'est écrit.
