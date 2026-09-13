# F-107 — L'offre par espace : la plateforme se paie, en BYOK aussi

> Cadrage du 2026-09-13. **Cadrage seul : la livraison attend le go du PO.** **Tous les montants sont
> À CONFIRMER PAR LE PO** : ce cadrage fixe une structure, jamais un prix. Aucun price Stripe n'est
> créé par un agent.
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
| Contenu | conversations, fichiers, historique | runner, projets, terminaux, carte, gouvernance (l'actuel Atelier) | Teams, Radar, réunions, Outlook |
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

## 4. La largeur : le supplément par poste s'applique partout

- Le supplément par poste (F-65) **s'applique à tous les plans, BYOK compris**. En BYOK il n'apporte
  aucun jeton : il paie la place.
- **Un client compte une fois**, qu'il soit activé dans la Forge, la Vigie ou les deux (F-106 §3) :
  c'est une mission, une machine.
- Montant, dégressivité et quota apporté : **À CONFIRMER PAR LE PO** (déjà suivis en OQ-16 point 8).

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
| Nom « Vigie » | À confirmer |
| Montant option Forge sur BYOK (40 € aujourd'hui sur Solo / Pro) | **À CONFIRMER PAR LE PO** |
| Montant Gold Vigie, option Vigie, enveloppe de synchro | **À CONFIRMER PAR LE PO**, après l'essai |
| Gold complet : oui / non, remise | **À CONFIRMER PAR LE PO** |
| Supplément par poste : montant, dégressivité | **À CONFIRMER PAR LE PO** (OQ-16 point 8) |
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
