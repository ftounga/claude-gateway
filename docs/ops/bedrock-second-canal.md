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
