# F-107 — L'offre par espace : la plateforme se paie, en BYOK aussi

> Cadrage du 2026-09-13. **Livrée le 2026-09-13 (SF-107-01 à 06, PR #489, #526, #533 à #536).** **Les montants ont été
> décidés par le PO le 2026-09-13, un par un, sur propositions argumentées : voir §9.** Aucun price
> Stripe n'est créé par un agent.
> Source de vérité de la grille : `docs/TARIFS.md`, à mettre à jour à la livraison.

## 1. Le constat du PO

> « Et ça a du sens ? Donc toute notre mécanique de runner, on ne la facture qu'à 29 euros ? »

**Non, ça n'a pas de sens.** Vérifié dans `docs/TARIFS.md` §1 et §3, et `AtelierEntitlementService.java:57`
(`PLANS_INCLUDING_ATELIER = {GOLD, BYOK}`) :

| Formule | Prix affiché | Part payée pour la plateforme |
|---|---|---|
| Solo + option Atelier | 24 € + 40 € = **64 €** | ≈ 40 € (l'option) |
| Gold | **199 €** | ≈ 138 € (12 M jetons ≈ 66 $ ≈ 61 € de coût, `STRATEGIE-TARIFAIRE.md` §1) |
| **BYOK** | **29 €** | **29 €, runner, Forge, gouvernance compris** |

Trois failles :
1. **Le moins cher donne le plus** : BYOK à 29 € comprend l'Atelier, Solo le paie 64 €.
2. **Gold est contournable** : un client Gold qui a une clé Anthropic passe en BYOK et économise
   170 € par mois sans rien perdre de la plateforme.
3. **La largeur n'est pas facturée** : le supplément par poste (F-65) existe mais ses valeurs sont
   inertes ; un consultant BYOK a tous ses clients pour 29 €.

**L'origine** : F-41 a défini BYOK comme « la plateforme entière, le client apporte ses jetons ».
Retirer les jetons était juste ; vendre la plateforme au prix d'une passerelle ne l'était pas.

**Exposition réelle, relevée en base de production le 2026-09-13** : **aucun abonnement BYOK**, aucun
abonnement Stripe ; un Gold actif (sans Stripe) et un essai. **La correction ne touche aucun client
payant** : pas de maintien de tarif à prévoir.

## 2. Le principe

**La plateforme se paie sur ce qu'elle apporte ; les jetons sur ce qu'ils coûtent.** BYOK retire les
jetons, jamais la plateforme.

## 3. La structure retenue : deux espaces, deux lignes d'offre (proposition du PO)

Avec la Vigie (F-106), le produit a deux espaces ; chacun se vend comme une ligne complète.

| | **Passerelle** | **Espace Forge** | **Espace Vigie** |
|---|---|---|---|
| Contenu | conversations, fichiers, historique | runner, projets, terminaux, carte, gouvernance (l'actuel Atelier) | Teams, Radar, réunions |
| Sur Solo / Pro | inclus | **option Forge** (l'actuelle option Atelier) | **option Vigie** |
| Sur BYOK | inclus | **option Forge** — *correction* | **option Vigie** |
| **Gold Forge** (l'actuel Gold) | inclus | **inclus** | option Vigie |
| **Gold Vigie** (nouveau) | inclus | option Forge | **inclus**, avec l'enveloppe de synchro |
| Gold complet (à décider) | inclus | inclus | inclus |

**Les deux Gold ont le même quota de jetons Hosted** par défaut (celui de Gold, 12 M) : un Gold se
distingue par l'**espace** qu'il inclut, pas par sa profondeur. Un **Gold complet** (les deux espaces)
est une question commerciale laissée au PO : le proposer, et à quelle remise.

**L'option Vigie remplace les options Teams et Radar** décidées plus tôt dans la journée. Elle garde
**tout ce qui a été tranché** :
- la Vigie **suppose** la liaison Teams, donc l'option Vigie comprend Teams **et** le Radar ;
- **le Radar a sa propre enveloppe de synchro**, qui ne mange jamais le quota des conversations ;
- **l'essai de deux semaines** par code d'accès (F-62), qui mesure le coût réel avant de fixer le
  montant.

*Variante écartée* : garder deux options séparées Teams et Radar dans la Vigie. Un client qui n'a que
Teams dans la Vigie a un espace à moitié vide (pas de Radar, pas de résumé) ; la ligne d'offre perd sa
lisibilité. **Le PO peut la rétablir** si la mesure de l'essai montre un public « Teams sans Radar ».

## 3 bis. Le compte administrateur a tout — décision du PO, 2026-09-13

> *« Dorénavant donne-moi accès à toutes les fonctionnalités qu'on développe par défaut. Je suis
> l'admin. »*

**Un utilisateur de rôle `ADMIN` a tous les droits de fonctionnalité**, quel que soit son plan : Forge,
Vigie (Teams, Radar, réunions), et **tout droit futur** créé par une feature. La règle vit **dans le
service de droits lui-même** (`SpaceEntitlementService`, et d'ici là `AtelierEntitlementService` et
`TeamsEntitlementService`), pas dans chaque écran : un droit ajouté demain l'hérite sans qu'on y pense.
**Ce que le rôle n'ouvre pas** : le quota de jetons (inchangé, crédit manuel si besoin) et le
supplément par client (rien n'est facturé à l'administrateur, rien n'est bloqué non plus).
**Livrée en avance** par le correctif « l'administrateur a tout » de la vague du 2026-09-13 ; SF-107-02
doit la **conserver** en absorbant les deux services.

## 4. La largeur : le supplément par poste s'applique partout

- Le supplément par poste (F-65) **s'applique à tous les plans, BYOK compris**. En BYOK il n'apporte
  aucun jeton : il paie la place.
- ~~Un client compte une fois, quel que soit l'espace.~~ **Révisé avec le PO (§9)** : le supplément est
  **par espace**. Un client en plus dans la Forge coûte des jetons ; un client en plus suivi par la
  Vigie coûte une synchro **chaque nuit**, qu'il soit aussi dans la Forge ou non. Chaque espace facture
  ce qu'il coûte. L'**appairage** et la **clôture de mission** restent uniques (F-106 §3).
- Montants, paliers et jetons apportés : **décidés**, §9.

## 5. Le découpage

| SF | Titre | Contenu | Peut partir seule |
|---|---|---|---|
| **SF-107-01** | BYOK ne comprend plus la Forge | `AtelierEntitlementService` : BYOK passe de `PLANS_INCLUDING_ATELIER` à `OPTION_CARRIER_PLANS` ; écran d'abonnement et page tarifs ; `TARIFS.md` §1 et §3 ; tests (BYOK sans option → pas d'accès, BYOK + option → accès, Gold inchangé) | **Oui**, dès le go : la faille existe aujourd'hui, et personne n'est touché |
| SF-107-02 | Les droits deviennent des droits d'espace | Un service `SpaceEntitlementService` (`FORGE`, `VIGIE`) qui absorbe `AtelierEntitlementService` et `TeamsEntitlementService` sans changer leurs réponses ; garde au niveau des outils conservée (`buildTools`) | après F-106 SF-106-01 |
| SF-107-03 | Gold Vigie et option Vigie | `PlanCode.GOLD_VIGIE` (le code `GOLD` reste l'actuel Gold, rebaptisé « Gold Forge » à l'affichage : aucun abonnement ne change) ; option Vigie ; clés de configuration des montants et price IDs **vides par défaut** ; enveloppe de synchro liée au droit Vigie | après SF-107-02 |
| SF-107-04 | L'essai Vigie | Code d'accès ouvrant la Vigie deux semaines avec enveloppe d'essai ; relevé du coût réel par synchro visible par le PO | avec F-100 |
| SF-107-05 | Le supplément par poste en BYOK | Application du supplément à BYOK, sans jetons ; un client compte une fois quel que soit l'espace | valeurs au PO |

## 6. Ce qui reste au PO

| Décision | État |
|---|---|
| Option Radar à part, essai de deux semaines | **Tranché** (2026-09-13), porté par l'option Vigie |
| Nom « Vigie » | **Tranché** (2026-09-13) |
| Gold complet et sa remise | **Tranché** : oui, −25 % sur la Vigie (§9) |
| Tous les montants | **Tranchés** (§9) ; la réserve Vigie est **revalidée après l'essai** |
| Création des prices Stripe | PO |

## 7. Préoccupations transversales

- **Plans / limites : oui.** Composants impactés : `AtelierEntitlementService`,
  `TeamsEntitlementService`, `EntitlementService`, `PlanCode`, `PlanCatalog`, `SubscriptionService`,
  accès offerts (F-62), supplément par poste (F-65, `host_seat_months`), écran d'abonnement
  (`billing.component`), page tarifs publique, `docs/TARIFS.md`. Test de non-régression : Solo, Pro,
  Gold et un essai gardent exactement leurs droits actuels.
- **Navigation : oui**, uniquement la page d'un espace non souscrit (F-106 SF-106-05).
- **Auth / Principal, tenant : non.**

## 8. Hors périmètre

- Toute modification d'un montant existant (Solo, Pro, Gold, option Atelier, recharges).
- Toute action dans Stripe.
- Des offres d'équipe ou d'organisation (V3, F-17).

## 9. La grille décidée avec le PO — 2026-09-13

Décidée **montant par montant**, sur propositions chiffrées. Coûts au pire calculés à la **valeur d'un
jeton de quota, 9 $/M** (`TARIFS.md` §8.1), 1 $ ≈ 0,92 €.

| Élément | Décision | Raison retenue |
|---|---|---|
| Nom du second espace | **Vigie** | fait la paire avec la Forge : un lieu où l'on fabrique, un lieu d'où l'on voit venir |
| **Option Forge sur BYOK** | **70 €** → BYOK + Forge = **99 €** | BYOK n'a aucune marge sur les jetons pour porter la plateforme ; le contournement de Gold passe de 170 € à 39 € d'économie pour un usage maximal |
| Option Forge sur Solo / Pro | **40 €, inchangée** | — |
| **Option Vigie** (Solo, Pro, BYOK) | **69 €**, même prix partout, comprend **un client suivi** | la Vigie coûte chaque nuit, la Forge non ; marge au pire 64 % |
| **Gold Forge** | **199 €**, l'actuel Gold, inchangé | aucun abonnement ne change |
| **Gold Vigie** | **229 €** (2 290 €/an) | déduit : 199 − 40 (Forge) + 69 (Vigie) = 228, arrondi |
| **Gold complet** | **249 €** | Gold Forge + Vigie remisée de **25 %** (199 + 51,75) ; **un seul** quota de 12 M ; la remise ne porte que sur la plateforme, jamais sur les jetons |
| **Réserve de synchro Vigie** | **3 M jetons / mois / client suivi** (≈ 25 € au pire) | valeur de départ ; **définitive après l'essai = consommation mesurée × 1,5**, revalidée avec le PO |
| Première synchro (30 jours) | **hors réserve**, une fois par client | la seule vraiment longue ; ne doit pas vider le premier mois |
| Réserve épuisée | synchro arrêtée proprement, position gardée, résumé qui le dit ; **complétée par une recharge** ; le quota des conversations **jamais** touché | — |
| **Essai Vigie** | **2 semaines**, code d'accès, réserve d'essai **3 M**, première synchro offerte | mesurer le coût réel avant la réserve définitive |
| **Client en plus — Forge** | 2e et 3e : **39 €** (2,0 M jetons) · 4e à 6e : **29 €** (1,5 M) · 7e et suivants : **19 €** (1,0 M) ; BYOK : même prix, **0 jeton** | même nombre de jetons par euro à chaque palier (`STRATEGIE-TARIFAIRE.md` §6) ; marge au pire ≈ 56 % à tous les paliers |
| **Client en plus — Vigie** | **39 € fixe**, apporte **3 M jetons de synchro** | pas de dégressivité : chaque synchro coûte vraiment ; marge au pire 36 % |

**Deux erreurs de calcul relevées pendant la décision, et corrigées avec le PO** : (1) une dégressivité
Vigie à 19 € aurait fait perdre de l'argent au-delà de 6 clients (synchro jusqu'à 25 €) ; (2) les
premières valeurs de jetons (5 M Vigie, 2 M à tous les paliers Forge) avaient été calculées au coût
moyen observé (≈ 5,5 $/M) au lieu de la valeur d'un jeton de quota (9 $/M) — elles auraient laissé
passer jusqu'à 41 € de synchro pour 39 € facturés. **Aucun prix n'a bougé ; les jetons ont été recalés.**

**Exemple — le cas du PO** : Gold complet, 4 clients dans la Forge, 2 suivis par la Vigie :
249 + (39 + 39 + 29) + 39 = **395 € / mois**.

### Conséquences pour la livraison

- **Le prix d'une option dépend du plan** (Forge : 40 € sur Solo / Pro, 70 € sur BYOK) : une clé de
  montant et un price ID **par plan porteur**, là où F-40 n'en a qu'un.
- **Le supplément devient un supplément par espace** : deux prices à paliers chez Stripe (Forge
  dégressif, Vigie fixe), et côté quota la table `app.seat.quota-tiers` alimentée pour la Forge
  (2,0 / 1,5 / 1,0 M), une réserve de synchro par client suivi pour la Vigie. `host_seat_months` compte
  désormais un mois-poste **par espace**.
- **SF-107-01** (BYOK) peut partir seule : elle ne demande que le price « option Forge BYOK ».
- `docs/TARIFS.md` et **OQ-16 point 8** sont mis à jour à la livraison ; d'ici là, `TARIFS.md` porte
  cette grille comme **décidée, non servie**.
