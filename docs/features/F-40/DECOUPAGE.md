# F-40 — Option Atelier (droit découplé du plan) — découpage en subfeatures

> Feature référencée dans `docs/PRODUCT_SPEC.md` (ligne F-40). Ce document ne remplace aucune
> mini-spec : il donne l'ordre de livraison et ce que chaque étage laisse à la suivante.

## Le problème, en une phrase

`AtelierAccessService` teste un **plan** (`PlanCode == GOLD`) là où il devrait tester un **droit** :
vouloir l'Atelier depuis Solo (24 €) impose de passer à 199 €, une falaise ×8 devant la seule
capacité différenciante du produit.

## La cible

Le droit d'Atelier est porté par :

- le **plan Gold**, exactement comme aujourd'hui — aucune régression de droit n'est acceptable ; **ou**
- une **option « Atelier »** souscrite en supplément d'un plan **Solo** ou **Pro**, **sans changer
  le quota** (défaut `APP_BILLING_ATELIER_OPTION_PRICE=40` €/mois).

## Subfeatures

| # | Titre | Ce qu'elle livre | Effort |
|---|-------|------------------|--------|
| **SF-40-01** | Le droit d'Atelier, découplé du plan | La **règle** : `AtelierAccessService` délègue à un `AtelierEntitlementService` qui lit un droit (plan Gold **ou** option active) et non plus un plan. Colonnes de portage de l'option + migration. Tests de non-régression Gold et de refus Solo-sans-option, sur le service **et** sur les cinq contrôleurs qui appellent `requireAccess()`. | ~1 j |
| **SF-40-02** | Souscrire et résilier l'option (Stripe) | Le **chemin de paiement** : price ID et prix d'affichage en configuration, endpoints `GET/POST /billing/atelier-option{,/checkout,/cancel}`, abonnement Stripe **distinct** du plan, webhook qui active/résilie l'option **sans jamais toucher au plan**. | ~1,5 j |
| **SF-40-03** | L'option sur l'écran de facturation | L'**écran** : une section « Option Atelier » qui dit le prix, l'état, souscrit (redirection Stripe) et résilie (confirmation `MatDialog`), et qui dit « incluse » quand l'offre est Gold. | ~1 j |

## Ordre et dépendances

`SF-40-01` → `SF-40-02` → `SF-40-03`. Chaque étage est livrable seul :

- après SF-40-01, l'option existe en base et ouvre le droit, mais ne se souscrit pas encore ;
- après SF-40-02, elle se souscrit et se résilie par l'API ;
- après SF-40-03, elle se souscrit depuis l'écran.

## Hors périmètre de toute la feature

- Toute modification des **quotas de jetons** : l'option ouvre un droit, elle n'ajoute pas un jeton.
- Le **partage de l'option entre utilisateurs** (→ F-17, V3, hors scope).
- Le prorata de résiliation en cours de mois : c'est Stripe qui l'applique, la gateway n'en calcule rien.
