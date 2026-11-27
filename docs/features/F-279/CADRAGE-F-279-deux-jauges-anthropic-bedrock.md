# Cadrage F-279 — Deux jauges : ce qui part chez Anthropic, ce qui part chez AWS Bedrock

**Date** : 2026-11-27. **Demande PO** : « est-ce que ça a du sens de mettre en place une jauge côté API key (Anthropic)
et une jauge côté AWS Bedrock ? Si ça a du sens, cadre ça et livre-le. »
**Réponse** : oui. Depuis F-277, l'IA hébergée est payée dans **deux pots** différents, et rien ne dit ce qui part où.

## 1. Le problème

| | Anthropic | AWS Bedrock |
|---|---|---|
| Mode de paiement | crédit **prépayé** (recharges par carte), épuisé depuis le 25/11 | **à l'usage**, facture AWS en fin de mois, compte facturé en **EUR** (fait par le PO le 26/11) |
| Ce qu'on sait aujourd'hui | le coût calculé de chaque tour (`usage_turns.provider_cost_usd`, F-133) ; le rapport de coût Admin API (F-224) **inactif en prod** (`provider_cost_reports` vide : clé Admin non posée) | le coût calculé de chaque tour, `usage_turns.provider = BEDROCK` (F-277) ; **aucune** lecture de la facture AWS |
| Ce que le PO voit | le budget hebdomadaire par poste (F-133 / SF-133-15) dans la barre du terminal : la **somme** des deux, sans distinction | rien |

Mesuré en prod le 27/11 (mois de novembre, modèles Claude seulement) : Anthropic ≈ 1 152 $ (5 057 tours), Bedrock
≈ 72 $ (140 tours depuis le 26/11 13 h). Cost Explorer (lecture faite avec le profil du PO) : 26/11 = **17,27 $**
facturés, contre **17,04 $** calculés par la passerelle — l'estimation interne tient à ~1,5 % près. Le 27/11 est
encore partiel chez AWS (24,9 $ lus à 16 h UTC contre ~55 $ calculés) : c'est le décalage d'environ un jour.

Besoin lié (26/11) : « quand je dis 200 €, ça doit être 200 € sur ma carte ». L'écart vient des recharges prépayées,
de la conversion $→€ et de la TVA.

## 2. Ce qui existe et est réutilisé (vérifié dans le code)

- `usage_turns.provider` (F-277) + `provider_cost_usd` (F-133) : l'estimation temps réel, par canal.
- `AnthropicAdminBillingReport` + `ProviderCostReconciliationService` + `provider_cost_reports` (F-224) : la
  facture Anthropic relue chaque matin (6 h 20), dès que `APP_ANTHROPIC_ADMIN_KEY` est posée.
- `ProviderPricingProperties.usdToEur` : le taux unique $→€ de la passerelle (montants en €, jamais en $).
- `BedrockSigner` (F-277) : la signature SigV4 par IRSA — réutilisée pour Cost Explorer (service `ce`).
- `ProviderFailover.currentRoute()` / `circuitOpen()` (F-277) : le canal qui sert en ce moment.
- `WeeklyBudgetComponent` dans la barre du terminal (SF-133-15) et l'écran admin « Coût réel »
  (`/admin` → Coût réel, carte « Facture Anthropic », F-224) : là où le PO lit sa consommation.

## 3. La solution

Deux jauges **du mois en cours**, côte à côte, en euros :

- **Anthropic** — « consommé ce mois » ; mention **« crédit épuisé, relais Bedrock »** quand le disjoncteur est ouvert
  pour cause de crédit (ou que le dernier tour Claude a été servi par Bedrock en mode `auto`). **Pas de solde
  prépayé** : aucune API fiable ne le donne (l'Admin API rend le coût, pas le solde) ; on ne l'invente pas.
- **AWS Bedrock** — « consommé ce mois, facturé en fin de mois ».
- **Le canal en service** (Anthropic / Bedrock) : un point sur la jauge active.

### Exactitude : recalage + estimation

Pour chaque jauge, chaque jour du mois est compté :

- **recalé** sur la facture du fournisseur si le jour est **clos** (avant-hier ou plus ancien, UTC) et que la facture
  de ce jour a été relue ;
- **estimé** (coût calculé par la passerelle, temps réel) sinon.

Les sources de recalage :

- Anthropic : le rapport Admin existant (`provider_cost_reports`, kind `COST`), toute l'organisation.
- Bedrock : **AWS Cost Explorer** `GetCostAndUsage`, `UnblendedCost` quotidien, services dont le nom contient
  « Bedrock » (« Claude Opus 5.5 (Amazon Bedrock Edition) », etc.), tout le compte. Relu chaque matin avec la
  facture Anthropic, et sur « Relire maintenant ». Rangé dans `provider_cost_reports` (kind `BEDROCK`) : aucune table
  ni migration nouvelle.

Ce qui reste inexact, **écrit à l'écran** :

1. **Décalage d'un jour** : les deux derniers jours sont estimés.
2. **Hors TVA** : les montants sont hors taxes ; la carte est débitée TTC.
3. **Taux de change** : Cost Explorer et l'Admin API rendent des dollars ; ils sont convertis au taux de la passerelle.
   AWS facture en EUR à son propre taux du jour de facture, d'où un écart de quelques pour cent au plus.
4. **Prépayé** : la carte est débitée au moment des recharges Anthropic, pas de la consommation. La jauge Anthropic
   dit ce qui a été consommé sur le crédit, pas ce qui a été débité ce mois-ci.
5. **Périmètre** : la facture recalée couvre toute l'organisation Anthropic et tout le compte AWS (partagé avec
   legalCase) ; l'estimation ne couvre que la passerelle.

### Droit IAM

Le rôle IRSA `claude-gateway-backend-staging-irsa` **n'a pas** `ce:GetCostAndUsage` (vérifié le 27/11 :
quatre politiques inline, bedrock / kms / s3 / textract). La commande de pose est documentée dans
`docs/ops/bedrock-second-canal.md` ; elle **n'est pas lancée** par la livraison. Sans le droit, la jauge Bedrock reste
sur l'estimation, et l'écran le dit (« recalage indisponible : droit Cost Explorer manquant »). Coût : 0,01 $ par
appel, un appel par relecture.

## 4. Décisions

| # | Décision |
|---|---|
| D1 | Jauges **mensuelles** (mois civil UTC) : c'est le rythme de la facture AWS et du relevé de carte. Le budget hebdomadaire (F-133) reste le **seul plafond**, somme des deux canaux, inchangé. |
| D2 | Seuls les modèles **Claude** entrent dans les jauges (`model` contient `claude`). La voix et l'audio (ElevenLabs, TTS/STT OpenAI) en sont exclus : ils seront cadrés à part. |
| D3 | Montants plateforme : **administrateur seul** (`AdminService.assertAdmin`), comme F-133 et F-224. Pour un autre utilisateur : 403, rien ne s'affiche. |
| D4 | Jour « clos » = avant-hier ou plus ancien (`app.cost.providers.settle-days`, défaut 2). |
| D5 | Pas de solde prépayé Anthropic inventé. |
| D6 | Cost Explorer en HTTP signé (SigV4, région `us-east-1`), sans nouvelle dépendance SDK. Coupe-circuit `APP_COST_BEDROCK_BILLING_ENABLED=false`. |
| D7 | Le canal en service : la route de ce pod (disjoncteur, mode) ; à défaut, le canal du dernier tour Claude des 15 dernières minutes (deux pods). |

## 5. Découpage

| SF | Contenu | Estimation |
|---|---|---|
| **SF-279-01** | Backend : relecture Cost Explorer (Bedrock), rangée dans `provider_cost_reports` ; `GET /api/admin/cost/providers` (deux jauges du mois, recalé + estimé, notes d'exactitude, canal en service) ; `POST …/refresh` ; doc ops (droit IAM). | 1,5 j |
| **SF-279-02** | Frontend : les deux jauges dans la barre du terminal, à côté du budget de la semaine, et la carte « Les deux pots » sur l'écran Coût réel (détail, recalage, mentions d'exactitude, « Relire maintenant »). | 1 j |

## 6. Hors périmètre (décision PO)

Facture par client ; profils Bedrock par client ; la voix et l'audio ; Jarvis ; solde prépayé Anthropic ; brider à la
jauge (elle informe, elle n'arrête rien).
