# F-65 — Supplément par poste supplémentaire (cadrage)

> **Date** : 2026-09-11 · **Source de vérité tarifaire** : [`docs/TARIFS.md`](../../TARIFS.md)
> (F-64) · **Stratégie** : [`docs/STRATEGIE-TARIFAIRE.md`](../../STRATEGIE-TARIFAIRE.md) §6
>
> ⚠️ **Aucun montant n'est décidé ici.** Ce cadrage livre un **mécanisme** et ses **clés de
> configuration**. Les valeurs — supplément mensuel, jetons apportés, paliers de dégressivité —
> appartiennent au PO et à Stripe. Les **défauts préservent le comportement actuel** : tant que
> rien n'est configuré, **aucun supplément n'est facturé et aucun jeton n'est apporté**.

---

## 1. Ce que la stratégie a arrêté

Le prix suit **deux axes** (`STRATEGIE-TARIFAIRE.md` §6) :

| Axe | Ce qu'il mesure | Facturation |
|---|---|---|
| **Largeur** | combien de **missions** en parallèle | l'abonnement couvre **un poste** ; chaque poste supplémentaire ajoute un **supplément mensuel**, qui **apporte sa part de quota** |
| **Profondeur** | combien on **travaille** sur chacune | la consommation, décomptée au coût réel (F-63), bornée par le quota ; au-delà, recharges |

F-65 livre le **premier axe**, et lui seul. La consommation reste facturée exactement comme
aujourd'hui.

**L'unité facturée est le POSTE, jamais le projet.** Un poste est physiquement une machine chez un
client — son réseau, son proxy, son runner ; en régie, deux clients ne peuvent pas être réunis sous
un poste, ce qui rend la règle non contournable (objection (a) de `STRATEGIE-TARIFAIRE.md` §3,
corrigée par le PO).

## 2. Le support existe déjà

**F-60** (livrée le 2026-09-10) a donné aux postes un **état de mission** déclaré par leur
propriétaire : `ACTIVE`, `PENDING`, `CLOSED` (colonne `runner_hosts.mission_status`).

> **Un poste clôturé ne se facture pas.** Clôturer une mission terminée est *le* geste par lequel
> le consultant cesse de payer.

`PENDING` **est** facturable : une mission en attente est une mission qu'on garde ouverte — le poste
reste appairé, le projet reste rattaché, le travail reprend sans rien réinstaller. Seul `CLOSED`
sort du décompte. C'est aussi ce qui donne au consultant un geste **honnête** et **réversible**
plutôt qu'une suppression de poste.

## 3. Les trois points tranchés

### (1) Proratisation d'un poste ouvert en cours de mois → **prorata temporis, à la journée**

**Décision** : un poste qui devient facturable le jour *j* compte pour la **fraction de mois qui
reste** — `(jours du mois − j + 1) ÷ jours du mois` — **sur le quota comme sur l'argent**.

**Pourquoi**, et c'est un raisonnement de sécurité avant d'être un raisonnement de justice : quota
**entier** pour un poste ouvert le 28 serait une **faille** — ouvrir un poste la veille de la fin du
mois, encaisser une part pleine de jetons, recommencer le mois suivant. Argent entier pour un poste
ouvert le 2 serait, à l'inverse, une petite injustice que personne ne défendrait. **La même fraction
des deux côtés** est la seule combinaison qui ne crée ni l'une ni l'autre.

**Elle s'aligne sur Stripe par construction** : augmenter la quantité d'un abonnement y est proraté
par défaut sur le **temps restant de la période**. Nous n'avons donc rien à répliquer — nous
appliquons la même règle au quota. Clé `app.seat.proration` (`DAILY` par défaut, `NONE` si le PO
choisit un jour `proration_behavior=none` chez Stripe : les deux côtés doivent bouger ensemble).

### (2) Dégressivité au-delà de quelques postes → **une table de paliers, vide par défaut**

**Décision** : la dégressivité existe comme **mécanisme**, pas comme **chiffre**. Côté argent elle
appartient à Stripe (un price ID à paliers *graduated/volume* : la quantité part, Stripe applique sa
grille) ; côté quota, `app.seat.quota-tiers` est son **miroir** — combien de jetons apporte le
supplément n° 1, le n° 2, le n° 5. Vide par défaut → apport **plat**, celui de
`app.seat.tokens-per-extra-seat`.

**Pourquoi une table plutôt qu'une formule** : une remise commerciale n'est pas une fonction
mathématique, c'est une liste de paliers négociés. Et une formule dans le code serait un montant
déguisé — exactement ce que F-65 s'interdit.

**Risque résiduel assumé et tracé** : les deux grilles (prix chez Stripe, jetons ici) doivent être
tenues alignées **par le PO**. Le dépôt ne peut pas le vérifier — c'est déjà le régime de tous les
montants d'affichage (OQ-07, OQ-16 point 4).

### (3) Réouverture d'un poste clôturé dans le même mois → **un mois-poste se paie une fois**

**Décision** : dès qu'un poste a été compté pour un mois, il l'est **pour tout ce mois**.
Conséquences symétriques :

- **rouvrir** dans le mois ne **refacture rien** et n'apporte **aucun jeton supplémentaire** — ce
  serait le piège à utilisateur ;
- **clôturer** dans le mois ne **rembourse rien** et **ne reprend pas** les jetons déjà apportés —
  le mois est engagé, il est dû jusqu'au bout (même règle que la résiliation de l'option Atelier) ;
- il n'existe donc **aucune** séquence ouvrir/clôturer/rouvrir qui remette le compteur à zéro : la
  faille est fermée par la même règle qui ferme le piège.

**La mémoire est une ligne**, pas une intention : `host_seat_months` retient « ce poste a été
facturable pendant cette période, à partir de telle date ». Rouvrir ne réécrit jamais cette ligne.

**Et c'est écrit à l'écran** (SF-65-02), au mot près : *« comptée pour ce mois — la rouvrir ne
refacture rien »*. Un utilisateur qui n'a pas lu la règle ne doit pas avoir à la deviner.

### (4) Décision implicite qu'il fallait tout de même prendre : **quel poste est celui du plan ?**

Le plan couvre **un** poste. Lequel, quand il y en a cinq ? **Le plus ancien facturable de la
période** — les suppléments sont donc les plus récents. C'est déterministe, c'est ce que l'intuition
attend (« le premier poste, c'est celui que j'avais »), et c'est ce que Stripe proratise quand la
quantité augmente : le poste marginal est le dernier arrivé.

## 4. Ce que F-65 ne fait pas

| Hors périmètre | Pourquoi |
|---|---|
| **Fixer un montant** | PO + Stripe. Défauts inertes, clés documentées (`TARIFS.md` §8.2, OQ-16 point 8) |
| **Toucher à Stripe** | Aucun appel, aucun price créé, aucune quantité poussée. Le mécanisme expose un **point de branchement** (`app.seat.price-id`) que le PO remplira |
| **Facturer les projets** | L'unité est le poste. Un projet de plus sous un poste reste gratuit (F-48) |
| **Couper quoi que ce soit** | Un poste non payé n'est ni coupé ni bloqué : F-65 **compte**, il ne sanctionne pas. Le quota reste la seule borne d'usage |

## 5. Découpage

| SF | Titre | Portée |
|---|---|---|
| **SF-65-01** | Le mois-poste, compté et doté de sa part de quota | Backend : table `host_seat_months`, décompte des postes facturables, apport de quota, configuration, `GET /billing/seats` |
| **SF-65-02** | L'écran dit quels postes sont comptés | Frontend : le volet « Postes » de l'écran de facturation — postes comptés, poste inclus, quota apporté, règle de réouverture |
