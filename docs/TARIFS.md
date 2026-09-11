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
7. **Valeur d'un token de quota** = `9,00 $/M` (`app.atelier.agent.cost.quota-token-cost-per-million-tokens`,
   §8.1). Inchangée par F-63, qui s'est interdit d'y toucher. C'est le **second levier de marge** :
   l'abaisser à `5,00` — « un token de quota = un token d'entrée » — multiplierait par 1,8 la
   vitesse de consommation de tout utilisateur de la Forge. Les **ratios** entre natures de tokens
   sont des tarifs fournisseur ; l'**échelle**, elle, est une décision commerciale.

---

## 8. Places réservées

**Ne rien inscrire dans ces sections avant la livraison de la feature correspondante.** Elles
existent pour que la grille les accueille sans être réécrite.

### 8.1 F-63 — le quota compté au coût réel *(livrée le 2026-09-11)*

**Aucun montant de cette page n'a changé** — ni prix, ni quota affiché, ni `markup`. Ce qui a changé
est la **façon de compter**, et donc la **vitesse** à laquelle un quota se consomme selon le style
d'usage.

**La règle.** Le quota reste libellé en tokens, mais chaque nature de token y entre **au prix de sa
nature** :

```
coût du tour ($) = (entrée×Pe + sortie×Ps + lecture_cache×Pc + écriture_cache×Pw) ÷ 1 000 000
                   ou, quand le fournisseur rapporte lui-même le coût, CE coût
tokens décomptés = coût du tour × markup ÷ Pq × 1 000 000
```

| Symbole | Ce que c'est | Clé de configuration (`app.atelier.agent.cost.*`) | Défaut |
|---|---|---|---|
| `Pe` | tarif fournisseur de l'**entrée** | `input-cost-per-million-tokens` | **5,00 $/M** |
| `Ps` | tarif de la **sortie** — cinq fois l'entrée, c'est tout le sujet | `output-cost-per-million-tokens` | **25,00 $/M** |
| `Pc` | tarif d'une **lecture de cache** (0,1× l'entrée) | `cache-read-cost-per-million-tokens` | **0,50 $/M** |
| `Pw` | tarif d'une **écriture de cache** (1,25× l'entrée) | `cache-write-cost-per-million-tokens` | **6,25 $/M** |
| `Pq` | **ce que vaut un token de quota** | `quota-token-cost-per-million-tokens` | **9,00 $/M** |
| `markup` | multiplicateur commercial | `markup` | **1.0** (neutre) |

`Pq` **remplace** l'ancien `cost-per-million-tokens` : même valeur, ancienne variable
d'environnement honorée en repli, rôle explicité. Il ne prétend plus être un « coût blended » —
approximation sans objet une fois l'entrée et la sortie distinguées.

**Comment lire un quota, maintenant.** Deux chiffres coexistent, tous deux justes :

| | Ce que c'est | Où on le voit |
|---|---|---|
| **Volume traité** | les tokens que le fournisseur a traités, cache compris | le rapport d'usage et la consommation par client (F-61), avec leur coût estimé |
| **Décompte** | ce que le quota oppose, pondéré au coût réel | la jauge de l'écran d'abonnement, `GET /usage` |

Les deux écrans le disent explicitement (SF-63-03) : sans cela, un client comparant sa jauge au
total de ses rapports prendrait deux chiffres justes pour une contradiction.

**Ce que cela change pour un client**, à quota inchangé : le point de bascule est à **4 entrées pour
1 sortie**. Au-delà — usage agentique, ratio 38:1 relevé en production, a fortiori servi par le
cache — le quota se consomme **moins vite** qu'avant. En deçà — génération de texte long, 3:1 — il
se consomme **plus vite**. C'est l'effet recherché : le coût fournisseur maximal d'un quota devient
`quota × Pq`, **quel que soit le style d'usage**, là où il variait auparavant du simple au
quintuple.

**Ce qui n'a pas été décidé** : la valeur de `Pq` elle-même. C'est le second levier de marge après
le `markup`, et il appartient au PO — suivi en **OQ-16 (point 7)**.

### 8.2 F-65 — le supplément par poste supplémentaire *(livrée le 2026-09-11)*

**Aucun montant n'a été décidé, et le tableau ci-dessous le dit ligne à ligne.** F-65 a livré le
**mécanisme** et ses clés de configuration ; les valeurs appartiennent au PO et à Stripe. **Les
défauts livrés sont inertes** : aucun jeton apporté, aucun supplément facturé, quota rigoureusement
identique à celui d'avant la livraison.

**La règle.** L'abonnement couvre **un poste** (`app.seat.included-seats`, défaut **1**) ; au-delà,
chaque poste **facturable** apporte sa part de jetons. Est facturable tout poste dont l'**état de
mission** (F-60) n'est pas `CLOSED` — une mission *en attente* compte, elle est gardée ouverte.
**Un poste clôturé ne se facture plus le mois suivant** : c'est le geste par lequel le consultant
cesse de payer une mission terminée.

| Élément | Valeur | Clé de configuration |
|---|---|---|
| Postes couverts par l'abonnement | **1** | `app.seat.included-seats` (`APP_SEAT_INCLUDED_SEATS`) |
| Supplément mensuel par poste supplémentaire | **À CONFIRMER PAR LE PO** — le montant n'existe que chez Stripe, sous un price ID à créer | `app.seat.price-id` (`STRIPE_PRICE_EXTRA_SEAT`), montant affiché : `app.seat.display-price` (`APP_BILLING_EXTRA_SEAT_PRICE`) |
| Quota apporté par le supplément | **À CONFIRMER PAR LE PO** — défaut **0** (rien n'est apporté). *Calibrage recommandé par `STRATEGIE-TARIFAIRE.md` §6 : le **même nombre de tokens par euro** que le plan de base* | `app.seat.tokens-per-extra-seat` (`APP_SEAT_TOKENS_PER_EXTRA_SEAT`) |
| Dégressivité au-delà de quelques postes | **À CONFIRMER PAR LE PO** — table de paliers **vide** par défaut (apport plat). Côté argent, c'est un price **à paliers** chez Stripe ; cette table en est le **miroir côté quota**, et **le PO tient les deux alignées** | `app.seat.quota-tiers` |
| Poste **clôturé** (F-60) | **Ne compte plus dès le mois suivant.** Le mois en cours, lui, reste engagé | — (`runner_hosts.mission_status`) |
| Poste ouvert **en cours de mois** | **Prorata temporis à la journée**, sur le quota comme sur l'argent | `app.seat.proration` (`APP_SEAT_PRORATION`), défaut `DAILY` |
| **Réouverture** dans le même mois | **Un mois-poste se paie une fois** : rouvrir ne refacture rien et n'apporte aucun jeton ; clôturer ne rembourse rien et ne reprend rien | — (unicité `(host_id, period_start)` de `host_seat_months`) |

**Pourquoi la proratisation s'applique aussi au quota** : une part **pleine** de jetons pour un poste
ouvert le 28 serait une faille — on ouvrirait un poste la veille de la fin du mois pour encaisser la
part entière. La même fraction des deux côtés ferme la porte, et elle s'aligne sur ce que Stripe
proratise par défaut quand la quantité d'un abonnement augmente. Passer `proration` à `NONE` n'a de
sens que si Stripe est mis, lui aussi, en `proration_behavior=none`.

**Pourquoi la réouverture ne refacture pas** : refacturer serait un **piège à utilisateur**, ne rien
facturer une **faille exploitable**. La règle « un mois-poste se paie une fois » ferme les deux avec
la même phrase — et elle est **écrite à l'écran**, sous la liste des postes comptés.

**L'unité facturée est le poste, jamais le projet** : un projet de plus sous un poste reste gratuit
(F-48). Et **F-65 ne coupe rien** : un supplément non payé ne bloque aucun poste — le quota reste la
seule borne d'usage.

**Ce que F-65 n'a pas touché** : Stripe. Aucun price créé, aucune quantité poussée, aucun price ID
lu. Les trois valeurs manquantes sont suivies en **OQ-16 point 8**.

---

## 9. Voisinage — des chiffres qui ne sont pas des tarifs

À ne pas confondre avec la grille, et à ne pas déplacer ici : ce sont des **garde-fous techniques**
ou des **estimations**, pas des prix de vente.

| Chiffre | Ce que c'est | Clé |
|---|---|---|
| 5 $ / 25 $ par million | Tarifs **fournisseur** (Opus, entrée/sortie), servant à **estimer** un coût dans le rapport d'usage (F-16) | `app.usage.report.{input,output}-cost-per-million-tokens` |
| 5 $ / 25 $ / 0,50 $ / 6,25 $ par million | Tarifs **fournisseur** par nature de token (entrée, sortie, lecture et écriture de cache), servant au **décompte du quota** (F-63, §8.1) | `app.atelier.agent.cost.{input,output,cache-read,cache-write}-cost-per-million-tokens` |
| 9 $ par million | **Ce que vaut un token de quota** (§8.1) : il convertit un coût en tokens décomptés, et un quota restant en budget de session. Remplace l'ancien `cost-per-million-tokens` (« blended »), même valeur | `app.atelier.agent.cost.quota-token-cost-per-million-tokens` |
| 2 $ / 5 $ / 0,10 $ | Plafonds de **dépense d'une session** Atelier (normal / avec délégation / plancher) | `app.atelier.agent.cost.max-run-cost*`, `min-run-cost` |
| 18 000 s (5 h) | Plafond de temps de bac à sable par période | `app.quota.max-sandbox-seconds` |
| 100 € | Capital social de l'éditeur, sur le site vitrine | `k8s/base/corporate/configmap.yaml` |

---

## 10. Quand cette page change

- **Un prix, un quota ou une durée change** → cette page d'abord, la configuration ensuite, Stripe
  enfin (ou l'inverse — mais les trois, jamais un seul).
- **Un plan ou un pack naît ou meurt** → §1, §2 ou §5, et la raison avec.
- **F-63 ou F-65 est livrée** → §8, qui cesse d'être vide. *(Les deux livrées le 2026-09-11 :
  §8.1 et §8.2. §8.2 décrit un mécanisme et ses clés ; elle n'annonce aucun montant, faute de
  source — OQ-16 point 8.)*
- **Un point de §7 est tranché** → il quitte §7, entre dans la grille, et OQ-16 est mise à jour.

**Ce qui ne doit jamais arriver** : qu'une grille soit recopiée dans un document de référence. C'est
exactement ainsi que les deux grilles de §6 sont nées — chacune écrite de bonne foi, à deux moments
différents, par quelqu'un qui ne savait pas que l'autre existait.
