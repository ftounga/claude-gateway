# Mini-spec — F-279 / SF-279-01 — Les deux pots, côté passerelle

## Identifiant
`F-279 / SF-279-01`

## Feature parente
`F-279` — Deux jauges : ce qui part chez Anthropic, ce qui part chez AWS Bedrock
(cadrage `CADRAGE-F-279-deux-jauges-anthropic-bedrock.md`)

## Statut
`in-progress`

## Date de création
2026-11-27

## Branche Git
`feat/SF-279-01-deux-pots-passerelle`

---

## Objectif
La passerelle rend, pour l'administrateur, ce que le mois en cours a coûté **chez Anthropic** et **chez AWS Bedrock**,
en euros, recalé sur la facture de chaque fournisseur pour les jours clos et estimé en temps réel pour le reste, avec
le canal qui sert en ce moment.

---

## Comportement attendu

### Cas nominal
1. **Relecture Bedrock** (`AwsCostExplorerBillingReport`) : `POST https://ce.us-east-1.amazonaws.com/`,
   `X-Amz-Target: AWSInsightsIndexService.GetCostAndUsage`, signé SigV4 (service `ce`, région `us-east-1`, IRSA), corps
   `{TimePeriod, Granularity: DAILY, Metrics: [UnblendedCost], GroupBy: SERVICE}`. Les groupes dont le nom contient
   « Bedrock » sont sommés par jour (dollars). Pagination `NextPageToken` suivie (≤ 20 pages).
2. **Rangement** : un jour = une ligne `provider_cost_reports` (`kind = BEDROCK`, `api_key_id = ''`), remplacée à chaque
   relecture (idempotent, deux pods sans doublon). Le rapprochement Anthropic (F-224) ignore ces lignes.
3. **Quand** : chaque matin avec la facture Anthropic (`ProviderCostReconciliationJob`, chaque relecture dans son propre
   `try`), et sur `POST /api/admin/cost/providers/refresh`.
4. **`GET /api/admin/cost/providers`** (admin seul) : pour le mois civil UTC en cours, deux jauges
   `ANTHROPIC` (prépayé) et `BEDROCK` (fin de mois) :
   - `spentEur` = Σ jours recalés (facture) + Σ jours estimés (calcul de la passerelle, `usage_turns`, `provider` du
     canal, modèles dont le nom contient `claude`) ;
   - un jour est **recalé** s'il est clos (≤ aujourd'hui − `settle-days`, défaut 2) et que sa facture a été relue ;
   - `billedEur`, `estimatedEur`, `billedThrough` (dernier jour recalé), `calculatedEur` (tout le mois au calcul),
     `gapEur` (facture − calcul sur les jours recalés), `status` (`RECALE` / `ESTIME`), `statusNote` (pourquoi :
     clé Admin absente, droit Cost Explorer manquant, relecture pas encore passée) ;
   - `totalEur` = somme des deux ; `usdToEur` ; `caveats` (décalage, HT, change, prépayé, périmètre) ;
   - `channel` : `mode` (OFF / AUTO / BEDROCK), `current` (ANTHROPIC / BEDROCK), `circuitOpen`, `cause` (dernière
     cause d'ouverture sur ce pod), `creditExhausted`, `lastServed` + `lastServedAt` (dernier tour Claude).
5. **Canal en service** : la route de ce pod ; si elle dit Anthropic mais que le dernier tour Claude des 15 dernières
   minutes a été servi par Bedrock en mode `auto`, `current = BEDROCK` (l'autre pod a le disjoncteur ouvert).
   `creditExhausted` vrai si la cause d'ouverture est `CREDIT`, ou si `current = BEDROCK` en mode `auto` sans cause
   connue sur ce pod (relais en cours ; la note dit « relais Bedrock »).

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|---|---|---|
| Appelant non administrateur | refus, aucun montant | 403 |
| Cost Explorer refuse (`AccessDeniedException`) | relecture abandonnée, journal WARN sans corps, jauge Bedrock `ESTIME`, `statusNote` « droit ce:GetCostAndUsage manquant » | 200 (GET) |
| Identifiants AWS absents (local, tests) | idem, « identifiants AWS introuvables » | 200 |
| Cost Explorer injoignable / 5xx | idem, « Cost Explorer injoignable » ; les lignes déjà rangées restent | 200 |
| Clé Admin Anthropic absente | jauge Anthropic `ESTIME`, `statusNote` = l'aide de création de la clé (F-224) | 200 |
| Coupe-circuit `APP_COST_BEDROCK_BILLING_ENABLED=false` | aucun appel AWS, `statusNote` « relecture désactivée » | 200 |
| Aucun tour ce mois | jauges à 0 € | 200 |

---

## Critères d'acceptation
- [ ] Cost Explorer : requête signée (`Authorization`, `X-Amz-Target`), services Bedrock seuls sommés par jour, pages suivies.
- [ ] Refus / panne Cost Explorer ⇒ exception typée avec une raison lisible, jamais le corps ni les identifiants dans le message.
- [ ] Relecture idempotente (`kind = BEDROCK`), ignorée par le rapprochement Anthropic (`/admin/cost/reconciliation` inchangé).
- [ ] Jauges : jour clos et facturé ⇒ facture ; sinon ⇒ calcul ; voix/audio et autres modèles exclus ; un canal ne compte pas les tours de l'autre.
- [ ] Montants en euros au taux `usdToEur`, au centime ; `totalEur` = somme des deux jauges.
- [ ] `channel` : disjoncteur ouvert pour crédit ⇒ `current = BEDROCK`, `creditExhausted = true` ; dernier tour récent sur Bedrock ⇒ `current = BEDROCK`.
- [ ] Non-administrateur ⇒ 403 sur GET et POST refresh.
- [ ] Doc ops : commande `aws iam put-role-policy` exacte du droit Cost Explorer (non lancée).

---

## Périmètre

### Hors scope (explicite)
- L'affichage (SF-279-02).
- Solde prépayé Anthropic (aucune source fiable), facture par client, profils Bedrock par client, voix/audio, Jarvis.
- Recalage du budget hebdomadaire par poste (F-133) : il reste l'estimation interne, somme des deux canaux.
- Pose du droit IAM (manuelle, PO / opérateur).

---

## Contraintes de validation
| Champ | Contrainte |
|---|---|
| — | aucune saisie : lecture seule ; `refresh` sans corps |

---

## Impacts techniques

| Composant | Changement |
|---|---|
| `ai/bedrock/BedrockSigner` | surcharge `aws(region, service, credentials)` (service `ce`) ; `aws(region)` inchangé |
| `ai/failover/ProviderFailover` | retient la dernière cause d'ouverture (`lastCause()`), expose `currentChannel()` |
| `quota/billing/BedrockBillingReport` *(nouveau, interface)* + `AwsCostExplorerBillingReport` *(nouveau)* | relecture Cost Explorer |
| `quota/billing/ProviderSpendService` *(nouveau)* + `ProviderSpendController` *(nouveau)* | jauges + refresh |
| `quota/billing/ProviderCostReport` | constante `BEDROCK` |
| `quota/billing/ProviderCostReconciliationService` | ignore `kind = BEDROCK` ; relecture Bedrock |
| `quota/billing/ProviderCostReconciliationJob` | relit aussi Bedrock (bloc séparé) |
| `quota/UsageTurnRepository` | `claudeCostsByProviderBetween` (agrégat plateforme) + dernier tour Claude |
| `application.yml` / `application-test.yml` | `app.cost.bedrock-billing.*`, `app.cost.providers.settle-days` ; désactivé en tests |
| `docs/ops/bedrock-second-canal.md` | droit Cost Explorer, vérification |

Endpoints : `GET /api/admin/cost/providers`, `POST /api/admin/cost/providers/refresh`.
Tables : aucune nouvelle, **aucune migration** (`provider_cost_reports.kind` VARCHAR(8) accueille `BEDROCK`).

---

## Plan de test
- **Unitaires** `AwsCostExplorerBillingReportTest` (serveur simulé) : en-têtes signés, corps, somme des services Bedrock,
  pages, `AccessDeniedException` ⇒ raison « droit manquant », 5xx ⇒ « injoignable », signataire sans identifiants.
- **Unitaires** `ProviderFailoverTest` : `lastCause` après ouverture CREDIT ; `currentChannel`.
- **Intégration** `ProviderSpendApiIntegrationTest` (`@SpringBootTest`, `@MockitoBean BedrockBillingReport` et
  `ProviderBillingReport`) : recalé / estimé par jour, exclusion audio, séparation des canaux, totaux en €,
  refresh range `BEDROCK` sans polluer `/admin/cost/reconciliation`, panne Cost Explorer ⇒ `ESTIME` + note,
  canal Bedrock par dernier tour récent.
- **Isolation / droits** : non-admin ⇒ 403 (GET et refresh). Agrégat plateforme : aucune donnée de compte rendue
  (sommes seulement).

---

## Préoccupations transversales

| Préoccupation | Cochée | Composants impactés |
|---|---|---|
| Auth / Principal | non | garde `AdminService.assertAdmin()` existante, réutilisée |
| Contexte tenant | non | agrégat de plateforme admin (comme F-224) ; aucune donnée d'un compte rendue |
| Plans / limites | **oui** (lecture) | `CostBudgetService`, `CostSummaryService`, `QuotaService` : **non touchés** ; les jauges informent, ne bloquent rien ; le budget hebdomadaire reste la somme des deux canaux |
| Navigation / routing | non | — |
