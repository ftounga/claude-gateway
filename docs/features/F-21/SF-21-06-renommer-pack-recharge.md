# Mini-spec — F-21 / SF-21-06 — Le pack de recharge porte enfin son nom

## Identifiant

`F-21 / SF-21-06`

## Feature parente

`F-21` — Facturation & rachat de tokens

## Statut

`ready`

## Date de création

2026-09-07

## Branche Git

`feat/SF-21-06-renommer-pack-recharge`

---

## Objectif

> Que le pack de 200 k jetons s'appelle « Recharge 200 k » dans l'application, comme il s'appelle
> désormais dans Stripe et sur le reçu du client.

---

## Déclencheur

Le pack `DAY` s'appelait **« Pass journée — 200 k tokens »**, exactement comme le **plan** `DAILY`
retiré la veille (SF-09-04). C'est cette homonymie qui avait masqué pendant des mois le fait que le
plan n'avait aucun price ID : on croyait voir le plan dans Stripe, on y voyait le pack.

Le produit Stripe a été renommé **« Claude Proxy — Recharge 200 k »** le 2026-09-07. Sans cette
subfeature, l'application dirait « Pass journée » là où le reçu dit « Recharge 200 k » : **deux noms
pour le même achat**, ce qui est pire que l'homonymie de départ.

---

## Comportement attendu

### Cas nominal

1. `GET /billing/topup-packs` renvoie le libellé **« Recharge 200 k tokens »** pour le pack `DAY`.
2. L'écran de facturation et la bannière d'alerte de quota (F-42) affichent ce libellé — ils le
   lisent déjà de l'API, aucun texte n'est écrit en dur côté écran.
3. Le pack `STANDARD` reste **« Recharge — 1 M tokens »**, inchangé.

### Ce qui ne change pas

| Élément | Pourquoi |
|---|---|
| Le **code** du pack (`DAY`) | Clé technique présente dans les métadonnées Stripe des paiements **déjà encaissés** ; la renommer casserait le rapprochement d'un webhook tardif |
| Le nombre de jetons (200 000) | Aucune décision commerciale ici |
| Le price ID `STRIPE_PRICE_TOPUP_DAY` | Le prix ne change pas, seul le nom du produit a changé côté Stripe |
| Le libellé de périodicité `periodLabel('DAILY')` | Il nomme la période d'un **abonnement historique**, pas le pack — il doit rester « Pass journée » (SF-09-04) |

### Cas d'erreur

Aucun : le libellé est une donnée de catalogue en mémoire, sans validation ni appel externe.

---

## Critères d'acceptation

- [ ] `TopUpCatalog` expose « Recharge 200 k tokens » pour le pack `DAY`.
- [ ] Le **code** `DAY` est inchangé.
- [ ] Le pack `STANDARD` est inchangé, libellé compris.
- [ ] Le montant de jetons du pack `DAY` reste 200 000.
- [ ] Aucun libellé de pack n'est écrit en dur dans l'écran : il vient de l'API.
- [ ] `periodLabel('DAILY')` continue de rendre « Pass journée » — c'est un abonnement, pas un pack.

---

## Périmètre

### Hors scope

- Le prix du pack, son volume, son price ID.
- Le renommage du produit dans Stripe : **déjà fait** le 2026-09-07, hors code.
- Le libellé de périodicité d'un abonnement historique (SF-09-04 / D1).

---

## Technique

### Classes impactées

| Classe | Changement |
|--------|-----------|
| `billing/TopUpCatalog` | Libellé du pack `DAY` |

### Migration Liquibase

- [x] **Non applicable** — le catalogue est une liste immuable en mémoire, aucune donnée stockée.

---

## Préoccupations transversales

| Préoccupation | Concernée | Composants impactés |
|--------------|-----------|--------------------|
| Auth / Principal | Non | — |
| Contexte tenant | Non | — |
| **Plans / limites** | **Oui** | Le pack est proposé par la bannière d'alerte de quota (F-42, `APP_QUOTA_ALERT_TOPUP_PACK`) et par l'écran de facturation (F-21). Les deux lisent le libellé de l'API — vérifié : aucun texte de pack en dur côté écran. Le **code** `DAY` reste la clé partout : configuration de l'alerte, métadonnées Stripe, rapprochement du webhook. |
| Navigation / routing | Non | — |

---

## Plan de test

### Tests unitaires

- [ ] `TopUpCatalogTest` : le pack `DAY` a le nouveau libellé, le code `DAY` et 200 000 jetons.
- [ ] Le pack `STANDARD` est inchangé.

### Tests d'intégration

- [ ] `GET /billing/topup-packs` renvoie le nouveau libellé (non-régression du contrat).

### Tests frontend

- [ ] Non applicable : aucun libellé de pack n'est écrit dans l'écran.

### Isolation workspace

- [x] Non applicable.

---

## Notes et décisions

**D1 — Le nom change, le code ne change pas.** `DAY` voyage dans les métadonnées Stripe des
paiements déjà encaissés et dans `APP_QUOTA_ALERT_TOPUP_PACK`. Un webhook tardif portant `DAY`
doit continuer de se rapprocher ; renommer la clé pour faire joli casserait un encaissement.

**D2 — « Recharge 200 k », pas « Recharge — 200 k tokens ».** Le pack `STANDARD` s'appelle
« Recharge — 1 M tokens » ; on garde la forme de la famille tout en collant au nom Stripe. Retenu :
**« Recharge 200 k tokens »** — même famille que `STANDARD`, et le client retrouve « Recharge » et
« 200 k » sur son reçu.
