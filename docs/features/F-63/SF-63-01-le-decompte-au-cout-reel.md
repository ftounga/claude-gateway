# Mini-spec — F-63 / SF-63-01 — Le décompte au coût réel

---

## Identifiant

`F-63 / SF-63-01`

## Feature parente

`F-63` — Le quota compte au coût réel (`docs/PRODUCT_SPEC.md`)

## Statut

`ready`

## Date de création

2026-09-11

## Branche Git

`feat/SF-63-01-decompte-cout-reel`

---

## Objectif

Opposer au quota un décompte où **chaque nature de token pèse son propre coût** — entrée, sortie,
lecture et écriture de cache — sans toucher aux volumes que montrent les écrans de F-16 et F-61, et
sans changer aucun montant.

---

## Comportement attendu

### Cas nominal

1. Un tour est servi. `QuotaService.recordUsage` reçoit, en plus des tokens d'entrée et de sortie,
   les tokens de **lecture** et d'**écriture de cache** du tour, et — quand le fournisseur le
   rapporte — le **coût réel** du tour.
2. Les compteurs de période sont incrémentés **comme avant** :
   `input_tokens += entrée + lecture_cache + écriture_cache`, `output_tokens += sortie`. Ce sont des
   volumes ; F-16, la consommation par client et la console admin ne changent pas d'un chiffre.
3. Le **coût du tour** est calculé : soit celui rapporté par le fournisseur, soit
   `(entrée×Pe + sortie×Ps + lecture×Pc + écriture×Pw) ÷ 1e6`, aux tarifs de configuration.
4. Les **tokens facturés** valent `coût × markup ÷ Pq × 1e6` (arrondi au plus proche, jamais
   négatif) et s'ajoutent à `usage_counters.billed_tokens`.
5. `assertWithinQuota`, l'alerte de consommation (F-42) et `GET /usage` lisent **`billed_tokens`**.
   Le journal `usage_turns` (F-61) reste sur les volumes : il sert à refacturer un client, pas à
   opposer un quota.
6. `GET /usage` expose en plus `processedTokens` — le volume traité de la période — pour que l'écran
   puisse dire que les deux chiffres existent et ne sont pas le même (SF-63-03).

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|---|---|---|
| Tarif de configuration absent, nul ou négatif | le défaut documenté s'applique (jamais 0 : diviser par zéro, ou facturer zéro, seraient l'un et l'autre des incidents) | — |
| `quota-token-cost-per-million-tokens` absent | repli sur `APP_ATELIER_AGENT_COST_PER_M`, puis sur `9.00` | — |
| Tour sans aucun token ni coût | aucune écriture (ni compteur, ni journal) — comportement actuel conservé | — |
| Coût réel rapporté à 0 ou négatif | aucun token facturé, les volumes restent enregistrés | — |
| Arrondi : coût strictement positif mais < 1 token de quota | au moins **1** token facturé — un tour servi ne peut pas être gratuit | — |

---

## Critères d'acceptation

- [ ] Un tour de 1 000 tokens d'entrée et 1 000 de sortie facture `(1000×5 + 1000×25)/9 = 3 333`
      tokens au lieu de 2 000 — la sortie pèse **cinq fois** l'entrée.
- [ ] Un tour de 100 000 tokens lus en cache facture `100000×0,5/9 = 5 556` tokens, contre 100 000
      auparavant : une lecture de cache pèse **un dixième** d'une entrée.
- [ ] Les colonnes `input_tokens` / `output_tokens` reçoivent exactement ce qu'elles recevaient
      avant pour les mêmes tokens rapportés (aucune régression de F-16, F-61, admin).
- [ ] `assertWithinQuota` bloque quand `billed_tokens ≥ quota + bonus`, et pas avant.
- [ ] L'alerte F-42 se déclenche à 80 % du quota **facturé**, une seule fois par période.
- [ ] Une ligne `usage_counters` existante voit `billed_tokens = input_tokens + output_tokens` après
      migration (aucun quota offert, aucune consommation inventée).
- [ ] BYOK : rien ne change — aucun quota plateforme n'est opposé, la consommation reste enregistrée.
- [ ] Le chemin Managed Agents décompte le coût réel du fournisseur et range les **vrais** deltas de
      tokens dans les compteurs (fin de l'« équivalent token » au prorata).
- [ ] Aucun prix, aucun quota, aucun `markup` n'est modifié ; tous les défauts actuels sont préservés.

---

## Plan de test minimal

**Unitaires**
- `BilledTokensCalculator` : entrée seule, sortie seule, cache seul, mélange ; markup ≠ 1 ;
  coût fournisseur fourni ; coût nul ; arrondi au plancher de 1 ; valeurs négatives ramenées à 0.
- `TokenPricingProperties` : défauts appliqués sur valeurs absentes/nulles/négatives ; surcharge.
- `AtelierCostPropertiesTest` : le renommage garde la valeur 9.00 et les plafonds inchangés.

**Intégration**
- `QuotaServiceTest` : les compteurs bruts sont inchangés ; `billed_tokens` suit la pondération ;
  `assertWithinQuota` s'appuie sur `billed_tokens` ; alerte F-42 sur le facturé ; BYOK exempté.
- `UsageControllerTest` / `UsageResponse` : `usedTokens` = facturé, `processedTokens` = volume.
- `AtelierSessionServiceTest` : coût rapporté → compteurs bruts réels + facturé issu du coût.
- Migration : contexte Spring démarre, colonne présente, reprise vérifiée sur une ligne existante.

**Isolation utilisateur**
- Deux utilisateurs, même période : le décompte facturé de l'un n'apparaît jamais chez l'autre ;
  toute lecture filtre sur `user_id` (aucun identifiant ne vient du client).

---

## Tables / endpoints / composants impactés

| Élément | Nature |
|---|---|
| `usage_counters.billed_tokens` | **colonne neuve** (migration `069-usage-counters-billed-tokens`), `NOT NULL DEFAULT 0`, reprise `input+output` |
| `app.atelier.agent.cost.*` | tarifs par nature + `quota-token-cost-per-million-tokens` (remplace `cost-per-million-tokens`) |
| `TokenPricingProperties`, `BilledTokensCalculator`, `TurnTokens` | **neufs** (`fr.claudegateway.quota`) |
| `QuotaService`, `UsageCounter`, `QuotaAlertService`, `UsageSnapshot`, `UsageResponse` | modifiés |
| `AtelierCostProperties`, `AtelierSessionService` | renommage + décompte au coût réel explicite |
| `ChatService`, `AskService`, `AtelierChatService` | appellent la nouvelle signature (cache à 0 tant que SF-63-02 n'est pas livrée) |

---

## Préoccupations transversales

| Préoccupation | Cochée | Composants impactés |
|---|---|---|
| Auth / Principal | non | — |
| Contexte tenant | **oui** | tout accès à `usage_counters` passe déjà par `user_id` (`QuotaService`, `UsageCounterRepository`) ; aucune nouvelle route, aucun nouvel identifiant client |
| Plans / limites | **oui** | `QuotaService.assertWithinQuota`, `QuotaAlertService.evaluateAfterUsage`, `EntitlementService` (inchangé), `AtelierTurnBudget.hosted` et `AtelierSessionService.sessionBudget` (lisent le restant) — chacun vérifié et testé |
| Navigation / routing | non | — |

---

## Hors périmètre

Changer un prix, un quota, le `markup` ; toucher à Stripe ; déployer ; modifier ce que montrent les
écrans (SF-63-03) ; faire voyager le cache depuis les fournisseurs (SF-63-02).
