# Mini-spec — [F-107 / SF-107-01] BYOK ne comprend plus la Forge

---

## Identifiant

`F-107 / SF-107-01`

## Feature parente

`F-107` — L'offre par espace : la plateforme se paie, en BYOK aussi
(cadrage validé : `CADRAGE-F-107-l-offre-par-espace.md`, §1, §5, §9)

## Statut

`done` — livrée le 2026-09-13 (PR #489)

## Date de création

2026-09-13

## Branche Git

`feat/SF-107-01-byok-option-forge`

---

## Objectif

BYOK cesse de comprendre la Forge : elle s'y achète par l'**option Forge**, affichée **70 €/mois**
sur BYOK (40 € sur Solo/Pro, inchangé), avec son propre price ID, vide par défaut.

---

## Contexte

`AtelierEntitlementService` classe BYOK dans `PLANS_INCLUDING_ATELIER = {GOLD, BYOK}` : le plan à
29 € ouvre la Forge que Solo paie 64 €, et un Gold qui a une clé Anthropic économise 170 €/mois en
passant en BYOK (cadrage §1). **Exposition relevée en production le 2026-09-13 : aucun abonnement
BYOK** — la correction ne retire rien à personne.

Le cadrage §9 fixe que le prix de l'option **dépend du plan porteur** : une clé de montant et une clé
de price ID **par plan porteur**, là où F-40 n'en a qu'une.

---

## Comportement attendu

### Cas nominal

1. **Droit (`AtelierEntitlementService`)** : `PLANS_INCLUDING_ATELIER = {GOLD}` ;
   `OPTION_CARRIER_PLANS = {SOLO, PRO, BYOK}`. Un BYOK actif sans option n'a **pas** accès à la
   Forge ; un BYOK actif (ou en sursis `PAST_DUE`) avec option `ACTIVE`/`PAST_DUE` y a accès.
   L'accès offert (F-62) reste une source de droit indépendante du plan.
2. **Configuration (`app.billing.stripe`)** — deux clés nouvelles, propres à BYOK :
   - `atelier-option-byok-price-id: ${STRIPE_PRICE_ATELIER_OPTION_BYOK:}` — **vide par défaut** ;
   - `atelier-option-byok-display-price: ${APP_BILLING_ATELIER_OPTION_BYOK_PRICE:70}`.
   Les clés existantes (`atelier-option-price-id`, `atelier-option-display-price` = 40) restent
   celles de Solo/Pro.
3. **`GET /billing/atelier-option`** renvoie le montant et la disponibilité **du plan porteur de
   l'utilisateur** : BYOK → 70 € et `available` selon le price ID BYOK ; tout autre plan → 40 € et le
   price ID Solo/Pro (comportement actuel). Nouveau champ additif `byokCarrier` (vrai si le plan de
   l'utilisateur est BYOK) pour que l'écran adapte son libellé.
4. **`POST /billing/atelier-option/checkout`** accepte un BYOK actif et utilise le price ID BYOK.
5. **Écran d'abonnement (`billing.component`)** :
   - la carte d'offre BYOK ne dit plus « Forge incluse » mais « Forge en option » ;
   - la section *Option Forge* affiche le montant renvoyé (70 € pour un BYOK), « S'ajoute à votre offre
     BYOK » ; si l'option n'est pas vendable sur BYOK (`available=false`), une phrase le dit
     (« L'option Forge n'est pas encore proposée sur l'offre BYOK ») et le bouton reste désactivé —
     **aucune erreur, aucun snack**.
6. **`docs/TARIFS.md`** §1 (tableau « Ce que chaque plan donne ») et §3 (option : montant par plan
   porteur, deux clés) sont à jour ; §7 bis marque la ligne « Option Forge sur BYOK » comme servie.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| BYOK actif sans option ouvre la Forge (`/workspaces`, flux…) | Refus d'accès Forge existant (`AtelierAccessDeniedException`) | 403 |
| BYOK checkout option, price ID BYOK vide | `BillingProviderUnavailableException` (écran : bouton désactivé en amont) | 503 |
| BYOK checkout option, option déjà active | `atelier_option_already_active` | 409 |
| BYOK annulé (`CANCELED`) checkout option | `no_active_subscription`, message « Solo, Pro ou BYOK » | 409 (inchangé) |
| Gold checkout option | `atelier_option_included` | 409 (inchangé) |
| Non authentifié | 401 (inchangé) | 401 |

---

## Critères d'acceptation

- [ ] BYOK `ACTIVE` sans option → `isEntitled` faux, `isIncludedInPlan` faux.
- [ ] BYOK `ACTIVE` + option `ACTIVE` → `isEntitled` vrai via `isGrantedByOption`.
- [ ] BYOK `CANCELED` + option `ACTIVE` → refusé (l'option ne tient pas seule).
- [ ] Solo, Pro (avec/sans option), Gold (inclus), essai (refusé sauf code F-62) : réponses inchangées.
- [ ] `GET /billing/atelier-option` pour un BYOK → `priceEur = "70"`, `includedInPlan = false`,
      `byokCarrier = true`, `available = false` quand le price ID BYOK est vide.
- [ ] `GET /billing/atelier-option` pour un Solo → `priceEur = "40"`, `byokCarrier = false` (inchangé).
- [ ] Checkout option BYOK avec price ID BYOK configuré → session créée avec **ce** price ID.
- [ ] Clé BYOK vide par défaut dans `application.yml` ; montant BYOK par défaut 70.
- [ ] Écran : carte BYOK « Forge en option » ; option à 70 € et phrase « pas encore proposée » sans
      erreur quand indisponible.
- [ ] `TARIFS.md` §1 et §3 à jour.
- [ ] Isolation : toutes les lectures passent par `getOrCreateForUser(userId)` du contexte de sécurité.

---

## Périmètre

### Hors scope (explicite)

- Toute action ou création de price dans Stripe (le price ID BYOK reste vide).
- Tout changement de montant existant (Solo, Pro, Gold, option à 40 €, recharges).
- Renommage « Atelier » → « Forge » dans le code (SF-107-02 : `SpaceEntitlementService`).
- Option Vigie, Gold Vigie, Gold complet (SF-107-03), essai Vigie (SF-107-04), supplément par poste en
  BYOK (SF-107-05).
- Webhook : inchangé — l'option BYOK est le même abonnement d'option (`kind=atelier_option`).
- Maintien de tarif : aucun abonnement BYOK en production.

---

## Valeurs initiales

| Champ | Valeur initiale | Règle |
|-------|----------------|-------|
| `atelier-option-byok-price-id` | vide | aucun price créé par un agent |
| `atelier-option-byok-display-price` | `70` | cadrage §9, décidé par le PO ; blanc → 70 |

---

## Contraintes de validation

Aucun champ saisi par l'utilisateur. Montant d'affichage blanc → défaut (40 Solo/Pro, 70 BYOK) ;
price ID blanc → option non souscriptible sur ce plan porteur.

---

## Technique

### Endpoint(s)

| Méthode | URL | Auth | Rôle minimum |
|---------|-----|------|-------------|
| GET | `/api/billing/atelier-option` | Oui | USER — champ additif `byokCarrier`, montant par plan |
| POST | `/api/billing/atelier-option/checkout` | Oui | USER — BYOK accepté, price ID par plan |

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| `subscriptions` | SELECT | aucune écriture nouvelle |

### Migration Liquibase

- [x] Non applicable (aucun changement de schéma)

### Composants impactés

- Backend : `AtelierEntitlementService`, `AtelierOptionService` (+ `AtelierOptionView`),
  `BillingProperties.Stripe`, `AtelierOptionResponse`, `application.yml`.
- Frontend : `billing.component.html/.ts`, `billing.models.ts` (`AtelierOptionView.byokCarrier`).
- Docs : `TARIFS.md` §1, §3, §7 bis ; commentaires `PlanCode.BYOK`, `TeamsEntitlementService`.

### Préoccupations transversales

- **Plans / limites : oui.** Composants appelant le droit Forge : `AtelierEntitlementService`
  (source unique), `AtelierAccessService` (garde de toutes les routes Forge), `AtelierOptionService`,
  `BillingController` (`/billing/atelier-option*`). Non-régression : Solo, Pro, Gold, essai, accès
  offert. `TeamsEntitlementService` n'est pas touché (sa liste de porteurs inclut déjà BYOK).
- **Auth / Principal, tenant, navigation : non.**

---

## Plan de test

### Tests unitaires

- [ ] `AtelierEntitlementServiceTest` — BYOK sans option refusé ; BYOK + option ouvert ; BYOK
      `PAST_DUE` + option ouvert ; BYOK `CANCELED` + option refusé ; Gold/Solo/Pro/essai inchangés.
- [ ] `AtelierOptionServiceTest` — BYOK : vue à 70, `byokCarrier`, `available` selon le price BYOK ;
      checkout BYOK utilise le price BYOK ; price BYOK vide → 503 ; Solo inchangé.
- [ ] `BillingProperties` — défaut 70 si blanc ; `atelierOptionPriceId(BYOK)` ≠ Solo/Pro.

### Tests d'intégration

- [ ] `ByokPlanApiIntegrationTest` — BYOK sans option → `/workspaces` 403 ; `GET /billing/atelier-option`
      → `entitled=false`, `includedInPlan=false`, `priceEur=70`, `byokCarrier=true`, `available=false` ;
      BYOK + option → `/workspaces` 200.
- [ ] `ByokKeyRequiredApiIntegrationTest` — les abonnés BYOK des chemins Forge portent l'option (le
      refus « clé requise » reste mesuré après la garde d'accès).

### Frontend

- [ ] `billing.component.spec` — carte BYOK « Forge en option » ; option BYOK indisponible → phrase
      « pas encore proposée », bouton désactivé.

### Isolation

- [x] Applicable — aucune nouvelle lecture : tout passe par `SubscriptionService.getOrCreateForUser`
      avec le `userId` du contexte de sécurité ; test existant `AtelierOptionBillingApiIntegrationTest`
      (401 sans jeton) conservé.

---

## Dépendances

### Subfeatures bloquantes

- Aucune (cadrage §5 : « peut partir seule »).

### Questions ouvertes impactées

- [x] OQ-16 — non concerné par cette SF (point 8 = supplément par poste, SF-107-05).

---

## Notes et décisions

- **Clés par plan porteur** : deux clés nommées BYOK plutôt qu'une table `plan → price` — le cadrage
  ne connaît que deux prix (Solo/Pro, BYOK) et SF-107-03 refondera les clés par espace.
- **« Page tarifs »** : le produit n'a pas de page tarifs publique distincte ; la grille visible par le
  client est la liste des offres de l'écran d'abonnement (`billing.component`), mise à jour ici.
