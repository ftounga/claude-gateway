# TARIFS.md — La grille tarifaire de claude-gateway

> **Source de vérité tarifaire unique.** Plans, montants, quotas, recharges, périodicité, essai.
>
> **Règle : aucun document de référence ne recopie un montant.** Un document de **référence** est
> celui qu'on ouvre pour *connaître* un prix — `marketing.md`, `spec.md`, `PROJECT.md`. Ceux-là
> renvoient ici, et un montant qui y réapparaît est un bug de documentation : le corriger, c'est le
> remplacer par un lien vers cette page.
>
> **L'exception, et elle est étroite : ce qui est écrit à une date, pour rendre compte** —
> `STRATEGIE-TARIFAIRE.md`, les ADR, les mini-specs de `docs/features/**`, ainsi que le backlog et
> le journal d'évolutions de `PRODUCT_SPEC.md`. Ces textes *raisonnent* sur des chiffres à un
> instant donné (« vouloir l'Atelier depuis Solo imposait de passer à 199 € ») ; les en priver les
> rendrait illisibles, et les réécrire après coup falsifierait la trace d'une décision. Ils gardent
> donc leurs chiffres — **et ne font autorité sur rien**. En cas d'écart, **cette page fait foi**.
>
> Établie par **F-64 / SF-64-01** le 2026-09-11, en résolution des deux grilles incompatibles
> relevées dans `docs/STRATEGIE-TARIFAIRE.md` §5.

---

## 0. Comment lire ce document

**Trois natures de chiffre**, et il faut les distinguer sous peine de lire la configuration comme
une facture :

| Nature | Ce que c'est | Où elle vit |
|---|---|---|
| **Montant affiché** | Ce que l'écran de facturation montre au client | Configuration backend (`display-prices`) |
| **Montant débité** | Ce que la carte paie réellement | **Stripe seul**, via le price ID |
| **Quota** | Ce que le client reçoit pour son argent | Configuration backend (`app.quota`) |

Le **montant affiché et le montant débité sont deux valeurs distinctes**, reliées par rien d'autre
que la vigilance : la gateway relaie le price ID qu'on lui donne sans vérifier ce qu'il coûte
(décision OQ-07 — les prix sont externalisés, réversibles sans redéploiement). **Leur concordance
appartient au PO**, seul à voir le tableau de bord Stripe. Cette page dit ce que le produit
**affiche** ; elle ne peut pas certifier ce que Stripe **débite**.

**`À CONFIRMER PAR LE PO`** signale un chiffre qu'aucune source du dépôt ne donne. Il n'est
**jamais** remplacé par une estimation : dans une grille tarifaire, un chiffre inventé est pire que
son absence. La liste complète de ces points est en **§7**, et suivie en **OQ-16**.

---

## 1. Les plans

Quatre plans sont vendables. Ils viennent de `PlanCatalog` / `PlanCode`
(`backend/src/main/java/fr/claudegateway/billing/`).

| Plan | Code | Mode fournisseur | **Mensuel affiché** | **Annuel affiché** | **Quota mensuel de tokens** |
|---|---|---|---|---|---|
| Solo | `SOLO` | Hosted | **24 €** | **240 €** | **1 000 000** |
| Pro | `PRO` | Hosted | **99 €** | **990 €** | **5 000 000** |
| Gold | `GOLD` | Hosted | **199 €** | **1 990 €** | **12 000 000** |
| BYOK | `BYOK` | BYOK (clé du client) | **29 €** | *pas d'offre annuelle* | **0 — et c'est le contrat** |

**Sources, ligne à ligne** — toutes dans `backend/src/main/resources/application.yml` :

| Valeur | Clé de configuration | Variable d'environnement |
|---|---|---|
| Mensuel Solo / Pro / Gold | `app.billing.stripe.display-prices.{SOLO,PRO,GOLD}` | `STRIPE_DISPLAY_PRICE_{SOLO,PRO,GOLD}` |
| Mensuel BYOK | `app.billing.stripe.display-prices.BYOK` | `APP_BILLING_BYOK_PRICE` |
| Annuel Solo / Pro / Gold | `app.billing.stripe.yearly-display-prices.{…}` | `STRIPE_DISPLAY_PRICE_{…}_YEARLY` |
| Quotas | `app.quota.plans.{SOLO,PRO,GOLD,BYOK}` | `APP_QUOTA_{…}_TOKENS` |

**Ces défauts ne sont surchargés nulle part dans le dépôt** : `k8s/base/backend/configmap.yaml` ne
définit, côté facturation, que les URL de retour Stripe. Le pod reçoit aussi `backend-secrets`,
créé hors du dépôt et réservé aux **secrets** — les price IDs y vivent, pas les montants d'affichage
ni les quotas. Sauf variable injectée à la main dans ce secret, **les valeurs ci-dessus sont celles
servies en production**.

### Ce que chaque plan donne, au-delà du quota

| | Solo | Pro | Gold | BYOK |
|---|---|---|---|---|
| Passerelle, conversations, historique, fichiers | ✅ | ✅ | ✅ | ✅ |
| **Atelier** (F-28) | par l'**option** (§3) | par l'**option** (§3) | **inclus** | **inclus** |
| Jetons fournis par la plateforme | ✅ | ✅ | ✅ | ❌ — clé Anthropic du client, facturée sur son compte |

Le zéro de BYOK n'est **pas** un abonnement expiré : le pré-vol de quota distingue les deux
(`EntitlementService.isCustomerKeyBilled`), sans quoi un client BYOK payant serait bloqué comme un
impayé.

### Périodicité

- **Mensuel** — le régime par défaut, disponible sur les quatre plans.
- **Annuel** (F-43) — Solo, Pro, Gold uniquement. Payé d'avance pour douze mois. **Défaut : dix mois
  payés sur douze** (≈ −17 %) — et c'est un **défaut, pas une formule** : aucune remise n'est
  calculée dans le code, pour que le PO puisse passer à onze mois ou promouvoir un seul plan sans
  redéploiement.
- **BYOK n'a pas d'offre annuelle** : ni price ID (`yearly-prices` n'a pas d'entrée `BYOK`), ni
  montant. C'est un **constat**, pas une décision — voir §7.
- **L'engagement est annuel, l'allocation reste mensuelle.** Un abonné annuel reçoit son quota mois
  par mois ; il ne peut pas consommer douze mois de jetons dès le premier.

---

## 2. Les recharges (top-up)

Deux packs, achetés à l'unité, qui créditent le quota de la **période courante**. Ils viennent de
`TopUpCatalog`.

| Pack | Code | Tokens crédités | **Prix** | Source du prix |
|---|---|---|---|---|
| Recharge 200 k tokens | `DAY` | **200 000** | **4,99 €** *(à reconfirmer)* | Produit Stripe « Claude Proxy — Recharge 200 k », montant relevé dans `PRODUCT_SPEC.md` (F-09 / SF-09-04) |
| Recharge — 1 M tokens | `STANDARD` | **1 000 000** | **À CONFIRMER PAR LE PO** | **aucune source dans le dépôt** |

**Le code ne connaît aucun de ces deux prix** : `TopUpPackResponse` n'expose ni prix ni price ID —
« le prix vit côté fournisseur ». L'écran de rachat n'affiche donc **aucun montant** avant la page
de paiement Stripe. Les 4,99 € ci-dessus sont une valeur **relevée dans une note de livraison**, pas
une valeur configurée : elle doit être reconfirmée au tableau de bord Stripe.

Le code `DAY` est conservé bien qu'il ne désigne plus une journée : il voyage dans les métadonnées
Stripe des paiements **déjà encaissés** et dans `APP_QUOTA_ALERT_TOPUP_PACK`. Seul son nom commercial
a changé (SF-21-06).

**Alerte de consommation** (F-42) : à **80 %** du quota effectif (abonnement + recharges), une
alerte est émise une fois par période, proposant le pack **`STANDARD`** en un clic
(`app.quota.alert.threshold`, `app.quota.alert.top-up-pack`).

**Au-delà du quota : blocage, pas dépassement payant.** `POST /chat` répond `402 quota_exceeded`
sans appeler le fournisseur. La variante monétisée reste ouverte (**OQ-08**).

---

## 3. L'option Atelier

| | Valeur | Source |
|---|---|---|
| Montant mensuel affiché | **40 €** | `app.billing.stripe.atelier-option-display-price` (`APP_BILLING_ATELIER_OPTION_PRICE`) |
| Quota apporté | **aucun** | par construction — l'option ouvre un **droit d'accès**, pas une allocation |
| Abonnement Stripe | **distinct** de celui du plan | `app.billing.stripe.atelier-option-price-id` (`STRIPE_PRICE_ATELIER_OPTION`) |

Elle existe pour une raison précise : sans elle, accéder à l'Atelier depuis Solo imposait de passer
à Gold — une falaise ×8 devant la seule capacité différenciante du produit. Elle se souscrit
**en supplément** d'un plan Solo ou Pro. Gold et BYOK incluent déjà l'Atelier : l'option ne les
concerne pas.

---

## 4. L'essai gratuit

| | Valeur appliquée | Source |
|---|---|---|
| Durée | **5 jours** | `app.billing.trial-days` (`APP_BILLING_TRIAL_DAYS`) |
| Jetons alloués | **200 000** | `app.quota.trial-tokens` (`APP_QUOTA_TRIAL_TOKENS`) |

> ⚠️ **Contradiction ouverte, non tranchée ici.** Le produit **annonce 14 jours** — sur la page
> d'accueil (`frontend/src/app/landing/`, « Essai gratuit 14 jours », « aucune carte requise ») et
> dans `docs/marketing.md`. Il en **applique 5**. Les deux corrections possibles — aligner le code
> sur la promesse, ou la promesse sur le code — sont des décisions commerciales de sens opposé et
> appartiennent au PO. Voir §7 et **OQ-16**.

---

## 5. Ce qui n'est plus vendable

À garder ici, faute de quoi on le redécouvre dans six mois.

| Élément | État | Pourquoi il subsiste dans le code |
|---|---|---|
| Plan `DAILY` (« Pass journée ») | **Retiré du catalogue** le 2026-09-07 (SF-09-04). Il n'a **jamais eu de price ID** : il figurait au catalogue sans être vendable. Checkout et `change-plan` répondent `404 plan_unknown`. | `PlanCode.DAILY` et son quota (500 000) restent **déclarés** : `subscriptions.plan_code` est un `varchar(32)` sans contrainte, et retirer la constante ferait échouer la lecture d'un abonnement historique — un retrait commercial deviendrait un incident. |

**Ne pas confondre** le **plan** `DAILY` (retiré) et le **pack de recharge** `DAY` (§2, bien vendu).
C'est leur homonymie qui avait masqué, des mois durant, que le plan n'avait aucun prix.

---

## 6. Les grilles périmées

Consignées ici pour qu'un lecteur qui les aurait en tête sache qu'elles sont mortes, et pour
qu'aucun client ne puisse les opposer au produit.

| Grille périmée | Où elle vivait | Statut |
|---|---|---|
| Hosted : Solo 29 €, Pro 119 €, Daily 15 €/j — BYOK : Solo 9 €, Pro 49 €, Daily 7 €/j — essai 14 j | `docs/marketing.md` §4, `docs/spec.md` §2 et §9 | **Périmée et supprimée** par F-64. Appliquée **nulle part** : aucune de ces valeurs n'existe en configuration. Elle ignorait Gold, et proposait un axe « Hosted × BYOK » que le code ne connaît pas (BYOK est **un plan**, pas une déclinaison de chaque plan). |
| Solo 24 €, Gold 199 € | `docs/PRODUCT_SPEC.md`, en passant, dans le descriptif de F-40 et le journal | **Exacte mais partielle** — ces deux montants sont justes ; elle ignorait Pro, BYOK, l'annuel, l'option et les recharges. **Non supprimée** : ce sont des phrases datées qui argumentent une décision (« la falaise ×8 »), pas une grille. `PRODUCT_SPEC.md` porte désormais, en tête, le renvoi et la mention que ses montants ne font autorité sur rien. |

**La configuration a tranché, et ce n'est pas un arbitrage** : `display-prices` est ce que
l'application affiche réellement aujourd'hui. Écrire la grille, c'est écrire ce qui est facturé.
Le PO reste libre de la changer — cette page dit seulement laquelle des deux était vraie.

---

## 7. À confirmer par le PO

Aucun de ces points n'est tranché par F-64 : ce sont des décisions commerciales. Suivis en
**OQ-16** (`docs/OPEN_QUESTIONS.md`).

1. **Prix du pack `STANDARD` (recharge 1 M).** Aucune source dans le dépôt. Le pack est vendable
   (il a un price ID d'environnement) mais son montant n'est écrit nulle part ici.
2. **Prix du pack `DAY`.** 4,99 € relevé dans une note de livraison, jamais en configuration — à
   reconfirmer au tableau de bord Stripe.
3. **Durée de l'essai : 5 appliqués contre 14 annoncés** (§4). Aligner dans quel sens ?
4. **Concordance montants affichés ↔ prix Stripe**, pour les quatre plans, les trois prix annuels,
   l'option Atelier et les deux recharges. Le dépôt ne peut pas la vérifier ; le tableau de bord
   Stripe seul le peut.
5. **BYOK n'a pas d'offre annuelle** (§1). Absence subie ou voulue ?
6. **`markup` de décompte** = `1.0` (neutre) : le décompte n'applique **aucun multiplicateur**, la
   marge étant portée par l'allocation de chaque plan (`app.atelier.agent.cost.markup`). Le porter à 2.0
   doublerait la vitesse de consommation de chaque client — **levier de marge**, à actionner
   sciemment, jamais par inadvertance.

---

## 8. Places réservées

**Ne rien inscrire dans ces sections avant la livraison de la feature correspondante.** Elles
existent pour que la grille les accueille sans être réécrite.

### 8.1 F-63 — le quota compté au coût réel *(réservé)*

F-63 changera la **façon de compter** le quota : un token de sortie coûte cinq fois un token
d'entrée chez le fournisseur, et le décompte les traite aujourd'hui à l'identique. **Aucun montant
de cette page ne changera** — ni prix, ni quota affiché. Ce qui changera, c'est la **vitesse** à
laquelle un quota se consomme, selon le style d'usage. Quand F-63 sera livrée, décrire ici la règle
de conversion et l'effet sur la lecture d'un quota. *(Vide à ce jour.)*

### 8.2 F-65 — le supplément par poste supplémentaire *(réservé)*

**Ce que la grille pose déjà** : un abonnement couvre **un poste**. C'est vrai aujourd'hui —
aucun supplément n'existe — et c'est la prémisse de F-65.

F-65 ajoutera un **supplément mensuel par poste supplémentaire**, qui **apportera sa part de
quota** (tranché par le PO le 2026-09-11). Quand il sera livré, ce tableau accueillera : le montant
du supplément, le quota qu'il apporte, et le sort d'un poste **clôturé** (F-60) — qui ne se facture
pas. Trois points restaient à trancher côté PO : proratisation en cours de mois, dégressivité
au-delà de quelques postes, réouverture dans le même mois. *(Vide à ce jour ; aucun montant n'est
anticipé.)*

| Élément | Valeur |
|---|---|
| Supplément mensuel par poste supplémentaire | *(réservé — F-65)* |
| Quota apporté par le supplément | *(réservé — F-65)* |
| Poste clôturé | *(réservé — F-65)* |

---

## 9. Voisinage — des chiffres qui ne sont pas des tarifs

À ne pas confondre avec la grille, et à ne pas déplacer ici : ce sont des **garde-fous techniques**
ou des **estimations**, pas des prix de vente.

| Chiffre | Ce que c'est | Clé |
|---|---|---|
| 5 $ / 25 $ par million | Tarifs **fournisseur** (Opus, entrée/sortie), servant à **estimer** un coût dans le rapport d'usage (F-16) | `app.usage.report.{input,output}-cost-per-million-tokens` |
| 9 $ par million | Coût fournisseur de référence (« blended »), servant à convertir un quota restant en budget de session | `app.atelier.agent.cost.cost-per-million-tokens` |
| 2 $ / 5 $ / 0,10 $ | Plafonds de **dépense d'une session** Atelier (normal / avec délégation / plancher) | `app.atelier.agent.cost.max-run-cost*`, `min-run-cost` |
| 18 000 s (5 h) | Plafond de temps de bac à sable par période | `app.quota.max-sandbox-seconds` |
| 100 € | Capital social de l'éditeur, sur le site vitrine | `k8s/base/corporate/configmap.yaml` |

---

## 10. Quand cette page change

- **Un prix, un quota ou une durée change** → cette page d'abord, la configuration ensuite, Stripe
  enfin (ou l'inverse — mais les trois, jamais un seul).
- **Un plan ou un pack naît ou meurt** → §1, §2 ou §5, et la raison avec.
- **F-63 ou F-65 est livrée** → §8, qui cesse d'être vide.
- **Un point de §7 est tranché** → il quitte §7, entre dans la grille, et OQ-16 est mise à jour.

**Ce qui ne doit jamais arriver** : qu'une grille soit recopiée dans un document de référence. C'est
exactement ainsi que les deux grilles de §6 sont nées — chacune écrite de bonne foi, à deux moments
différents, par quelqu'un qui ne savait pas que l'autre existait.
