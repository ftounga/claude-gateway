# F-41 — Plan BYOK (plateforme seule) — découpage en subfeatures

> Feature référencée dans `docs/PRODUCT_SPEC.md` (ligne F-41). Ce document ne remplace aucune
> mini-spec : il donne l'ordre de livraison et ce que chaque étage laisse à la suivante.

## Le problème, en une phrase

`fr.claudegateway.billing.ProviderMode` déclare `HOSTED` **et** `BYOK` depuis F-09, mais
`PlanCatalog` n'expose que des plans `HOSTED` : un client qui apporte sa clé Anthropic (F-03,
livrée) paie **exactement le même prix** qu'un client dont la gateway paie les jetons.

## La cible

Un plan **BYOK** au catalogue :

- accès plateforme et **Atelier** compris (même mécanisme de droit que F-40) ;
- **allocation de jetons nulle** — la gateway ne paie aucun jeton ;
- appels servis par la **clé du client** (F-03) ;
- prix en configuration, défaut `APP_BILLING_BYOK_PRICE=29` €/mois.

## Les deux pièges, nommés d'avance

1. **Le zéro ambigu.** `EntitlementService.resolveMonthlyTokenQuota` rend `0` pour un abonnement
   expiré *et* rendra `0` pour un plan BYOK payant. `QuotaService.assertWithinQuota` bloque dès que
   `used >= quota`, donc `0 >= 0` : **un client BYOK payant serait bloqué comme un impayé**. C'est
   le cœur de la feature.
2. **Le plan BYOK sans clé.** Aujourd'hui, `resolveActiveApiKey` rend `Optional.empty()` et
   l'appelant retombe silencieusement sur la clé **plateforme** : un client qui ne paie aucun jeton
   consommerait ceux de la gateway. Il faut un refus **propre et actionnable**, jamais un 500,
   jamais un appel envoyé sans clé.

## Subfeatures

| # | Titre | Ce qu'elle livre | Effort |
|---|-------|------------------|--------|
| **SF-41-01** | Le plan BYOK au catalogue, et le zéro qui n'est pas un impayé | La **règle** : `PlanCode.BYOK`, entrée catalogue `ProviderMode.BYOK`, prix et quota (0) en configuration. `EntitlementService` distingue « zéro parce que BYOK » de « zéro parce qu'expiré » ; `QuotaService` cesse de bloquer le premier. Le droit d'Atelier est porté par le plan BYOK au même titre que Gold. | ~1 j |
| **SF-41-02** | Plan BYOK sans clé : un refus qui dit quoi faire | Le **garde-fou** : `ByokKeyRequiredException` → `409 byok_key_required` avec un message qui nomme l'écran où déposer la clé. Posé sur le pré-vol, donc **avant** tout appel fournisseur, dans les trois chemins servis (chat JSON, chat SSE, Atelier SSE). | ~1 j |
| **SF-41-03** | Le plan BYOK sur l'écran de facturation | L'**écran** : la carte d'offre BYOK dit « aucun jeton inclus, vos appels sur votre clé » au lieu de « 0 tokens inclus », la jauge de consommation ne montre plus un quota nul comme un blocage, et un rappel actionnable s'affiche quand l'offre est BYOK sans clé enregistrée. | ~1 j |

## Ordre et dépendances

`SF-41-01` → `SF-41-02` → `SF-41-03`. Chaque étage est livrable seul :

- après SF-41-01, le plan existe, se souscrit et ouvre l'accès sans jeton ;
- après SF-41-02, l'absence de clé se dit au lieu de se deviner ;
- après SF-41-03, l'écran de facturation cesse de mentir sur le « 0 ».

## Hors périmètre de toute la feature

- **Facturer l'usage réel de la clé du client** : la gateway ne voit pas la facture Anthropic du
  client et ne la refacture pas. Seul l'abonnement plateforme est facturé (PROJECT.md §11.8).
- **Tout partage de clé entre comptes** : une clé appartient à un `user_id`, jamais à un groupe
  (→ F-17, V3, hors scope).
- La **période annuelle** du plan BYOK (→ F-43) et l'**alerte de quota** (→ F-42).
