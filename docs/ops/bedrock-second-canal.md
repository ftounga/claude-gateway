# Bedrock, le second canal d'accès à Claude (F-277)

Bedrock sert les mêmes modèles Claude que l'API Anthropic, avec le profil d'inférence **mondial**
`global.anthropic.<modèle>` (prix de l'API Anthropic). Voir ADR-022 et
`docs/features/F-277/CADRAGE-F-277-second-acces-bedrock.md`.

## Réglages (`app.ai.bedrock.*`)

| Variable | Défaut | Rôle |
|---|---|---|
| `APP_AI_BEDROCK_MODE` | `auto` | Voir les trois modes ci-dessous. |
| `APP_AI_BEDROCK_REGION` | `eu-west-3` | Région de l'appel. |
| `APP_AI_BEDROCK_MODEL_PREFIX` | `global.anthropic.` | Profil d'inférence. `eu.anthropic.` assure la résidence UE, au prix de +10 %. |
| `APP_AI_BEDROCK_OPEN_MINUTES` | `15` | Disjoncteur : temps passé sur Bedrock après une panne de crédit ou de clé. Ensuite, la passerelle retente Anthropic. |
| `APP_AI_BEDROCK_ALLOWED_BETAS` | 3 bêtas vérifiées | Bêtas Anthropic transmises à Bedrock. Les autres sont retirées. |

Les trois modes :

- `off` : Anthropic seul, comme avant F-277.
- `auto` : Anthropic d'abord, puis bascule automatique sur Bedrock.
- `bedrock` : Bedrock forcé.

**Coupe-circuit** : `APP_AI_BEDROCK_MODE=off` dans le configmap `backend-config`, puis redémarrage du déploiement.
Pour forcer Bedrock sans attendre une panne : `APP_AI_BEDROCK_MODE=bedrock`.

## Droits IAM (appliqués le 2026-11-26)

Le pod s'authentifie par IRSA avec le rôle `claude-gateway-backend-staging-irsa` (compte 504895205419). Ce rôle a reçu
une politique **inline** `claude-gateway-backend-staging-bedrock-policy`, posée par la CLI. Elle n'a **pas** été
mergée dans `legalCase/k8s/**`, car un tel merge redéploie le staging (incident du 10/10).

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {"Sid": "BedrockInvokeAnthropicViaInferenceProfiles", "Effect": "Allow",
     "Action": ["bedrock:InvokeModel", "bedrock:InvokeModelWithResponseStream"],
     "Resource": ["arn:aws:bedrock:eu-west-3:504895205419:inference-profile/global.anthropic.*",
                  "arn:aws:bedrock:eu-west-3:504895205419:inference-profile/eu.anthropic.*"]},
    {"Sid": "BedrockInvokeAnthropicFoundationModelsAllRegions", "Effect": "Allow",
     "Action": ["bedrock:InvokeModel", "bedrock:InvokeModelWithResponseStream"],
     "Resource": ["arn:aws:bedrock:*::foundation-model/anthropic.*",
                  "arn:aws:bedrock:::foundation-model/anthropic.*"]}
  ]
}
```

Commande de pose (ou de mise à jour) :

```bash
AWS_PROFILE=legalcase-terraform aws iam put-role-policy --role-name claude-gateway-backend-staging-irsa \
  --policy-name claude-gateway-backend-staging-bedrock-policy --policy-document file://bedrock-policy.json
```

Aucun droit Marketplace n'est requis : l'accès aux modèles Anthropic est déjà ouvert au niveau du compte.

⚠️ Si le rôle passe un jour sous Terraform avec `aws_iam_role_policies_exclusive`, il faut reporter cette politique
dans Terraform, sinon elle sera retirée.

## La facture AWS Bedrock (F-279) — droit Cost Explorer

Les jauges « Anthropic » et « AWS Bedrock » (`GET /api/admin/cost/providers`, écran Coût réel et barre du terminal)
recalent la jauge Bedrock sur la facture AWS lue dans **Cost Explorer** (`GetCostAndUsage`, région `us-east-1`,
`UnblendedCost` quotidien des services dont le nom contient « Bedrock », tout le compte). Relecture chaque matin
à 6 h 20 avec la facture Anthropic, ou sur « Relire maintenant ». Un appel coûte 0,01 $.

**État au 2026-11-27 : le droit n'est PAS posé.** Le rôle `claude-gateway-backend-staging-irsa` n'a que quatre politiques
inline (bedrock, kms-byok, s3, textract). Tant qu'il manque, la jauge Bedrock reste sur l'estimation de la passerelle
et l'écran affiche « Recalage indisponible : droit ce:GetCostAndUsage manquant ».

Politique à poser (`ce-read-policy.json`) :

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {"Sid": "CostExplorerReadBedrockBill", "Effect": "Allow",
     "Action": ["ce:GetCostAndUsage"],
     "Resource": "*"}
  ]
}
```

Commande (à lancer par le PO ou un opérateur ; la livraison ne la lance pas) :

```bash
AWS_PROFILE=legalcase-terraform aws iam put-role-policy --role-name claude-gateway-backend-staging-irsa \
  --policy-name claude-gateway-backend-staging-cost-explorer-policy --policy-document file://ce-read-policy.json
```

Aucun redémarrage n'est nécessaire : les identifiants IRSA sont relus à chaque appel. Ensuite, « Relire maintenant »
sur l'écran Coût réel, ou attendre 6 h 20. Vérification :

```sql
SELECT report_day, amount_usd, fetched_at FROM provider_cost_reports WHERE kind = 'BEDROCK' ORDER BY report_day DESC;
```

Coupe-circuit : `APP_COST_BEDROCK_BILLING_ENABLED=false` (aucun appel AWS, la jauge reste estimée).
Ce qui reste inexact, écrit à l'écran : décalage d'environ un jour (les deux derniers jours sont estimés,
`APP_COST_PROVIDERS_SETTLE_DAYS`), montants hors TVA, conversion $→€ au taux de la passerelle (AWS facture en EUR à son
propre taux), compte AWS partagé avec legalCase.

## Vérifier en production

- **Quel canal a servi** :

  ```sql
  SELECT provider, count(*), sum(provider_cost_usd) FROM usage_turns
  WHERE occurred_at > now() - interval '1 day' GROUP BY provider;
  ```

  Ou avec `bash ~/.local/share/claude-gateway/q-prod-ro.sh "<sql>"`.
- **Les bascules** : dans le groupe CloudWatch `/aws/eks/legalcase-shared/claude-gateway`, chercher `Bascule IA` ou
  `Disjoncteur IA`. Exemple de requête Insights :

  ```text
  fields @timestamp, log | filter log like /Bascule IA|Disjoncteur IA/ | sort @timestamp desc | limit 50
  ```

## Ce que le canal ne fait pas

- **Recherche et lecture web côté serveur** : elles ne sont pas proposées au modèle. Le tour continue sans elles, et
  une note INFO est journalisée au plus toutes les 10 min.
- **Files API** : une pièce jointe envoyée par `file_id` est remplacée par une note « pièce jointe indisponible ».
  L'envoi d'un fichier (`uploadFile`) reste sur Anthropic.
- **Fonctions absentes** : repli serveur (`fallbacks`), diagnostic de cache et contrôle de liaison du raisonnement.
  Elles sont retirées du corps envoyé à Bedrock.
- **Managed Agents** : ils restent sur Anthropic.
- **BYOK** : un client qui a sa propre clé Anthropic n'est **jamais** servi sur le compte AWS de la plateforme.

## Essai réel en local

```bash
cd backend && BEDROCK_LIVE_TEST=1 AWS_PROFILE=legalcase-terraform ./mvnw -q test -Dtest=BedrockLiveTest
```

L'essai fait trois appels, sur Haiku 5.5 et Opus 5.5, pour quelques centimes.
