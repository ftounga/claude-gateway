# F-62 — Codes d'accès à durée limitée : cadrage

**Date** : 2026-09-10 · **Source de vérité** : `docs/PRODUCT_SPEC.md` ligne F-62 · **Statut** : cadrée

---

## Le besoin, en une phrase

Le PO veut pouvoir **offrir un accès complet de 24 h** : un code remis à quelqu'un, saisi par lui,
qui lui ouvre le plan **GOLD** — donc la **Forge** — pendant une journée, puis le **ramène à son
plan précédent** sans rien casser.

Usages : une démonstration, un essai chez un prospect, un dépannage.

---

## Ce qui est déjà tranché (et qu'on ne rejoue pas)

| Point | Décision (PRODUCT_SPEC / prompt de lancement) |
|---|---|
| **Où l'on saisit le code** | **Sur l'écran du plan**, en alternative au paiement — « payer, ou entrer un code ». **Jamais à la connexion** : on imposerait un champ à tous ceux qui n'ont pas de code. |
| **Qui génère** | **ADMIN uniquement** (`AdminService.assertAdmin()`, la garde unique du produit — F-20). |
| **Nature du code** | Nominatif **ou non**, **à usage unique**, **daté**, consommation **tracée** (qui, quand, pour quel compte). |
| **Brique à réutiliser** | `AtelierEntitlementService` — il sait déjà qu'« une journée ne porte pas un abonnement mensuel » et porte le **droit** d'accès à la Forge, découplé du plan (F-40). |
| **Stripe** | **On n'y touche pas.** Un code n'est pas un paiement et ne doit rien changer côté facturation. |
| **Hors périmètre** | Codes de **réduction** sur abonnement (c'est Stripe) · **cumul** de plusieurs codes. |

---

## Le point délicat : le retour au plan précédent

Le retour doit survenir **même si personne ne se connecte** à l'échéance, et ne **jamais interrompre
un tour en cours**.

### Ce que la lecture naïve suggère — et pourquoi on ne le fait pas

La lecture naïve est : « à la consommation, on écrit `subscriptions.plan_code = GOLD` ; un job
planifié restaure le plan précédent 24 h plus tard ». Nous l'écartons, pour deux raisons dures :

1. **Cette colonne appartient à Stripe.** Le webhook `customer.subscription.updated` écrit
   `plan_code`. Un code écrirait donc dans la case que la facturation gouverne : soit le webhook
   efface le cadeau, soit la restauration efface un vrai changement d'offre payé entre-temps. Dans
   les deux cas, **un code aurait changé ce que le client paie** — précisément l'interdit.
2. **Un retour qui dépend d'un job est un retour qui peut ne pas avoir lieu.** Pod redémarré,
   déploiement, exception avalée : l'utilisateur garde GOLD indéfiniment. Or l'exigence est que le
   retour survienne « même si personne ne se connecte » — c'est-à-dire **sans dépendre de rien**.

### Ce qu'on fait à la place — le droit en surcouche

Le code n'ouvre **pas** un plan : il ouvre un **droit**, daté, rangé dans sa propre table, à côté du
plan sans jamais l'écraser. Rien n'est écrit dans `subscriptions`. Par conséquent :

- **le retour au plan précédent n'est pas un événement, c'est une propriété du temps** :
  passé `granted_until`, la comparaison `now < granted_until` devient fausse et le droit se ferme.
  Il n'y a **rien à exécuter**, donc rien qui puisse échouer. Aucun job planifié, aucune connexion
  requise. C'est strictement plus fort que le cron : un cron peut ne pas tourner, une comparaison
  ne le peut pas ;
- **le plan précédent est intact** — on n'y est jamais revenu parce qu'on ne l'a jamais quitté. Il
  est tout de même **recopié** dans la trace de consommation (`previous_plan_code` /
  `previous_status`), parce que l'admin doit pouvoir lire « ce compte reviendra à SOLO » ;
- **Stripe n'est ni lu ni écrit** sur ce chemin.

### « Ne jamais interrompre un tour en cours »

Le droit de Forge est relu à **chaque requête** du tour (les relances du runner, le flux SSE). Fermer
la porte à la seconde du terme couperait un tour engagé en plein milieu.

On pose donc une **grâce de tour** (`app.access-code.grace-minutes`, défaut **15**) : la porte de la
Forge reste ouverte jusqu'à `granted_until + grâce`. Ce que la grâce **n'offre pas** : de jetons —
le quota n'a jamais été modifié (voir ci-dessous), et il ne change pas davantage pendant la grâce.
Elle laisse finir ce qui était engagé, elle n'offre pas une seconde journée.

---

## L'arbitrage structurant : le code ouvre la **Forge**, pas le portefeuille

`PRODUCT_SPEC` dit : « ouvre le plan **GOLD** — **donc la Forge** ». Deux lectures :

| Lecture | Conséquence |
|---|---|
| **A — le code relève aussi le quota de jetons au niveau GOLD** | Le compteur de consommation est **mensuel et partagé**. Un invité qui brûle l'allocation GOLD en 24 h retombe ensuite sous le quota de SON offre, déjà dépassé : **un client Solo payant se retrouve bloqué jusqu'à la fin du mois** pour avoir accepté un cadeau. Dépense non bornée, dégât silencieux. |
| **B — le code ouvre le *droit* d'accès (la Forge), le quota reste celui du compte** | Exactement la sémantique de l'**option Forge (F-40)**, déjà en production et déjà dite à l'écran : « l'option ouvre l'accès, elle n'ajoute pas de tokens ». Aucun risque de blocage a posteriori, aucune dépense surprise, « ne rien changer côté facturation » devient littéralement vrai. |

**Décidé : B.** C'est réversible (une règle, un test), c'est cohérent avec la brique que le PO nous
demande justement de réutiliser, et c'est le seul des deux qui ne peut pas nuire à un client payant.
L'écran le dit en toutes lettres, avec la formule déjà employée par F-40.

Conséquence heureuse : la surcouche se branche en **un seul endroit** —
`AtelierEntitlementService` — et ne touche **ni** `EntitlementService` (quota) **ni** `QuotaService`
**ni** Stripe. Rayon d'explosion minimal.

---

## Découpage

| SF | Titre | Nature |
|---|---|---|
| **SF-62-01** | Le code d'accès : le générer, le consommer, le laisser expirer | Backend |
| **SF-62-02** | « Payer, ou entrer un code » — la saisie sur l'écran du plan | Frontend |
| **SF-62-03** | Générer et suivre les codes depuis la console d'administration | Frontend (admin) |

Ordre imposé : **SF-62-01 avant** les deux écrans (contrat d'API figé par le backend).

---

## Ce que F-62 ne fait pas

- Aucun code de **réduction** sur abonnement (Stripe s'en charge, hors périmètre).
- Aucun **cumul** : un compte qui a déjà un droit en cours ne peut pas en consommer un second.
- Aucune modification de `subscriptions`, d'abonnement Stripe, de quota ou de compteur d'usage.
- Aucune saisie de code **à la connexion**.
- Aucune **révocation** d'un code déjà consommé (le droit dure au plus 24 h ; le retirer avant terme
  n'a pas été demandé, et l'ajouter reste réversible).
