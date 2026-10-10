# Requêtes CloudWatch de la Forge (F-186)

Groupe : **`/aws/eks/legalcase-shared/claude-gateway`** (région `eu-west-3`, rétention 30 jours).
Il reçoit tous les pods du namespace `claude-gateway-staging` depuis le 2026-10-10. Avant cette date,
les journaux ne vivaient que dans les pods et disparaissaient à chaque déploiement.

Lancement (Logs Insights, console ou CLI) :

```bash
export AWS_PROFILE=legalcase-terraform
Q=$(aws logs start-query --region eu-west-3 \
  --log-group-name /aws/eks/legalcase-shared/claude-gateway \
  --start-time $(date -d '7 days ago' +%s) --end-time $(date +%s) \
  --query-string "<requête>" --query 'queryId' --output text)
aws logs get-query-results --region eu-west-3 --query-id "$Q"
```

Le message d'origine est dans le champ `log`. Seuls les pods du backend nous intéressent ici :
on filtre sur `kubernetes.container_name = "backend"`.

## 1. Tours terminés : étapes, durée, tokens, cause d'arrêt

```
fields @timestamp, log
| filter kubernetes.container_name = "backend" and log like /Tour d'atelier terminé/
| parse log /terminé : (?<etapes>\d+) étape\(s\), (?<secondes>\d+) s, (?<tokens>\d+) tokens — arrêt : (?<arret>.*)/
| stats count() as tours, avg(etapes) as etapes_moy, max(etapes) as etapes_max,
        avg(tokens) as tokens_moy by arret
```

**Plafond d'étapes atteint** (100 depuis le 2026-10-04) :

```
fields @timestamp, log
| filter kubernetes.container_name = "backend" and log like /Tour d'atelier terminé/
| parse log /terminé : (?<etapes>\d+) étape/
| filter etapes >= 100
| stats count() as tours_au_plafond by bin(1d)
```

## 2. Refus du fournisseur

```
fields @timestamp, log
| filter kubernetes.container_name = "backend" and log like /stop_reason=refusal/
| parse log /catégorie=(?<categorie>[^,]*), modèle=(?<modele>[^)]*)/
| stats count() by categorie, modele
```

## 3. Replis de modèle

```
fields @timestamp, log
| filter kubernetes.container_name = "backend"
    and (log like /Repli côté serveur/ or log like /Tour servi par un modèle de repli/
         or log like /repli non streamé/)
| stats count() by bin(1d)
```

## 4. Raisonnement écarté par le fournisseur

```
fields @timestamp, log
| filter kubernetes.container_name = "backend" and log like /thinking_dropped=/
| parse log /thinking_dropped=(?<blocs>\d+)/
| stats count() as tours, sum(blocs) as blocs_ecartes by bin(1d)
```

## 5. Compactions

```
fields @timestamp, log
| filter kubernetes.container_name = "backend"
    and (log like /Fil d'Atelier compacté/ or log like /Compaction du fil ignorée/
         or log like /Compaction sans résumé exploitable/ or log like /Contexte débordé : fil compacté/)
| parse log /(?<issue>Fil d'Atelier compacté|Compaction du fil ignorée|Compaction sans résumé exploitable|Contexte débordé)/
| stats count() by issue
```

## 6. Postes perdus en cours de tour

```
fields @timestamp, log
| filter kubernetes.container_name = "backend" and log like /Tour arrêté net : le poste ne répond plus/
| stats count() by bin(1d)
```

## Notes

- Ces requêtes lisent les messages tels qu'écrits dans le code (`AtelierChatService`,
  `AnthropicAgentProvider`, `AtelierCompactionService`). Si un message change, la requête doit
  suivre.
- Les journaux ne contiennent jamais le contenu d'un tour (règle existante) : seulement des compteurs,
  des modèles et des identifiants opaques.
- Coût : au repos, environ 175 Ko/h pour toute la gateway. Le groupe se lit par IAM, comme celui de
  legalcase.
