# Mini-spec — [F-40 / SF-01] Le droit d'Atelier, découplé du plan

---

## Identifiant

`F-40 / SF-01`

## Feature parente

`F-40` — Option Atelier (droit découplé du plan)

## Statut

`done` — livrée le 2026-09-07

## Date de création

2026-09-07

## Branche Git

`feat/SF-40-01-droit-atelier-decouple`

---

## Objectif

> En une phrase : que fait cette subfeature ?

Faire que le gating de l'Atelier teste un **droit** (`AtelierEntitlementService`) et non plus un
**plan** (`PlanCode == GOLD`), ce droit étant porté par le plan Gold **ou** par une option Atelier
active en supplément d'un plan Solo/Pro — sans toucher au quota.

---

## Contexte

`AtelierAccessService.isAllowed()` fait aujourd'hui, en une ligne :

```java
return subscription.getPlanCode() == PlanCode.GOLD
        && (status == ACTIVE || status == PAST_DUE);
```

Ce test de plan est appelé par **cinq contrôleurs** (voir §Préoccupations transversales). Le
remplacer par un test de droit est un changement à **rayon large** : la non-régression Gold est le
point le plus risqué de toute la feature, et elle est figée par des tests dédiés.

Cette subfeature livre la **règle** et le **portage** de l'option en base. Elle ne livre **pas** le
moyen de la souscrire (SF-40-02) : en fin de SF-40-01, seul un `UPDATE` en base (ou un test) peut
poser l'option. C'est volontaire — la règle change une fois, sous tests, avant que quoi que ce soit
ne puisse l'exercer en production.

---

## Comportement attendu

### Cas nominal

1. Un contrôleur d'Atelier appelle `atelierAccess.requireAccess()` (ou `hasAccess()`).
2. `AtelierAccessService` garde son **bypass administrateur** inchangé (aucune consultation
   d'abonnement pour un `ADMIN`).
3. Pour tout autre rôle, il délègue à `AtelierEntitlementService.isEntitled(userId)`, qui lit
   l'unique abonnement de l'utilisateur (`SubscriptionService.getOrCreateForUser`, filtre `user_id`)
   et répond **vrai** dans exactement deux cas :
   - **plan Gold** dont le statut est `ACTIVE` ou `PAST_DUE` — *strictement le comportement actuel* ;
   - **option Atelier** de statut `ACTIVE` ou `PAST_DUE`, **et** plan porteur `SOLO` ou `PRO`
     lui-même `ACTIVE` ou `PAST_DUE`.
4. Toute autre situation est refusée (fail-closed), comme aujourd'hui.
5. Le quota n'est **jamais** consulté ni modifié par ce chemin : l'option ouvre un droit, pas un jeton.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Aucun utilisateur authentifié (`principal()` vide) | `AtelierAccessDeniedException`, aucun accès à l'abonnement | 403 |
| Plan Solo/Pro **sans** option | Refus | 403 |
| Option `CANCELED` / `INCOMPLETE` / absente | Refus | 403 |
| Option active mais plan porteur `CANCELED`/`INCOMPLETE` | Refus — l'option est un supplément, pas un plan | 403 |
| Option active mais utilisateur encore en essai (`TRIALING`, `planCode` nul) | Refus | 403 |
| Option active sur un `DAILY` (pass journée) | Refus — le pass journée n'est pas un plan porteur | 403 |
| Plan Gold `CANCELED`/`INCOMPLETE` | Refus (inchangé) | 403 |

---

## Critères d'acceptation

- [x] **Non-régression Gold** : un abonné `GOLD` `ACTIVE` et un abonné `GOLD` `PAST_DUE` conservent
      l'accès à l'identique ; `GOLD` `CANCELED` et `GOLD` `INCOMPLETE` restent refusés.
- [x] **Refus Solo sans option** : un abonné `SOLO` `ACTIVE` sans option est refusé, sur le service
      et sur les cinq contrôleurs.
- [x] Un abonné `SOLO` `ACTIVE` **avec** option `ACTIVE` a accès ; idem `PRO`.
- [x] Une option `ACTIVE` posée sur un plan `CANCELED`, sur un essai, ou sur `DAILY` **ne donne pas**
      l'accès.
- [x] Le bypass `ADMIN` est inchangé et ne consulte toujours aucun abonnement.
- [x] Le quota de l'utilisateur est **inchangé** par la présence de l'option : `QuotaProperties`
      n'est pas touché, et un test le prouve (`tokensForPlan(SOLO)` identique avec et sans option).
- [x] La migration ajoute deux colonnes **nullables** : aucun abonnement existant n'est modifié, et
      la relecture d'un abonnement antérieur donne exactement le droit qu'il avait avant.
- [x] Les cinq contrôleurs de la §Préoccupations transversales sont vérifiés **avec et sans** option.

---

## Périmètre

### Hors scope (explicite)

- La **souscription** et la **résiliation** de l'option (Stripe, endpoints, webhook) → SF-40-02.
- L'**écran** de facturation → SF-40-03.
- Toute modification des **quotas de jetons** (l'option n'ajoute pas un jeton).
- Le **partage de l'option** entre utilisateurs (→ F-17, V3).
- Le prix de l'option : introduit en configuration en SF-40-02, inutile tant que rien ne se souscrit.

---

## Valeurs initiales

| Champ | Valeur initiale | Règle |
|-------|----------------|-------|
| `subscriptions.atelier_option_status` | `NULL` | Aucune option tant qu'elle n'a pas été souscrite. `NULL` ≡ pas d'option. |
| `subscriptions.atelier_option_stripe_subscription_id` | `NULL` | Peuplé en SF-40-02 par le webhook de souscription. |

Comportements à la création : aucun changement au provisionnement de l'essai
(`SubscriptionService.provisionTrial`) — un essai naît sans option, comme avant.

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format / Valeurs autorisées | Unicité | Normalisation |
|-------|-------------|-------------|----------------------------|---------|---------------|
| `atelier_option_status` | Non | 16 | `SubscriptionStatus` (`TRIALING`, `ACTIVE`, `PAST_DUE`, `CANCELED`, `INCOMPLETE`) ou `NULL` | Non | Enum JPA `STRING` |
| `atelier_option_stripe_subscription_id` | Non | 64 | Identifiant fournisseur opaque, **jamais exposé au client** | Oui (index unique) | — |

Notes :
- Le statut de l'option réutilise l'énumération `SubscriptionStatus` : elle est déjà la traduction
  des statuts d'abonnement du fournisseur, et l'option **est** un abonnement chez lui. Créer un
  second vocabulaire aurait imposé une seconde table de traduction pour rien.
- L'index **unique** sur l'identifiant fournisseur est posé dès cette migration, avec la colonne :
  il sert la résolution du webhook (SF-40-02) et interdit qu'un même abonnement fournisseur soit
  revendiqué par deux utilisateurs. Dans cette subfeature la colonne n'est jamais écrite.

---

## Technique

### Endpoint(s)

Aucun endpoint créé ni modifié. Le **comportement** de neuf endpoints change (ceux gardés par
`requireAccess()`), sans changement de contrat : un Solo avec option reçoit 200 là où il recevait 403.

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| `subscriptions` | `ALTER TABLE` (2 colonnes nullables) + `SELECT` | Aucune écriture dans cette subfeature |

### Migration Liquibase

- [x] Oui — `054-subscriptions-atelier-option.xml` (2 `addColumn` nullables, `rollback` par `dropColumn`)
- [ ] Non applicable

### Composants Angular (si applicable)

Aucun.

### Classes touchées

| Classe | Nature |
|--------|--------|
| `fr.claudegateway.billing.AtelierEntitlementService` | **Nouveau** — porte la règle du droit |
| `fr.claudegateway.billing.Subscription` | 2 champs |
| `fr.claudegateway.atelier.AtelierAccessService` | Délègue au service de droit ; bypass admin inchangé |

---

## Préoccupations transversales

| Préoccupation | Concernée | Composants impactés et vérification |
|--------------|-----------|-----------------------------------|
| Auth / Principal | Non | Aucun changement d'authentification, de `Principal` ni de session. Le bypass `ADMIN` est repris **à l'identique** et son test existant (`adminBypassesGatingWithoutConsultingSubscription`) est conservé sans modification. |
| Contexte tenant | Non | Le `userId` vient toujours du `CurrentUser` (JWT), jamais d'un paramètre client ; `SubscriptionService.getOrCreateForUser(userId)` filtre déjà sur `user_id` unique. Aucun nouvel accès aux données. |
| **Plans / limites** | **Oui** | **C'est le cœur de la subfeature.** Cinq contrôleurs appellent `atelierAccess.requireAccess()` / `hasAccess()` — tous sont vérifiés **avec et sans** option : <br>1. `AtelierController` (14 appels — projets, fichiers, import, arborescence)<br>2. `AtelierChatController` (7 appels + 1 `hasAccess()` sur `/stream`, relayé comme booléen dans le flux SSE)<br>3. `AtelierAgentController` (1 `hasAccess()` sur `/stream`, relayé dans le flux SSE)<br>4. `GitWorkspaceController` (3 appels — clone, publication, pull request)<br>5. `RunnerManagementController` (6 appels — code d'appairage, jetons, statut, coupe-circuit, audit)<br>**Vérification** : test d'intégration qui, pour chacun des cinq, exerce un endpoint représentatif en Solo-sans-option (403 attendu, ou `error: forbidden` **dans le flux** pour les deux endpoints SSE) puis en Solo-avec-option (accès accordé). Les deux endpoints SSE sont traités à part parce qu'ils ne lèvent jamais d'exception synchrone : leur refus voyage dans le flux, et un 403 y serait un bug. |
| | | **Quota** : aucun gate de quota n'est touché. `EntitlementService`/`QuotaService`/`QuotaProperties` ne sont ni lus ni modifiés par le chemin du droit. Un test fige que le quota d'un Solo est identique avec et sans option. |
| Navigation / routing | Non | Aucune route ajoutée ou modifiée, aucun guard touché (le frontend n'est pas dans cette subfeature). |

---

## Plan de test

### Tests unitaires

`AtelierEntitlementServiceTest` (nouveau) :

- [x] Gold `ACTIVE` ⇒ droit — **non-régression**
- [x] Gold `PAST_DUE` ⇒ droit — **non-régression**
- [x] Gold `CANCELED` ⇒ refus — **non-régression**
- [x] Gold `INCOMPLETE` ⇒ refus — **non-régression**
- [x] Solo `ACTIVE` sans option ⇒ refus
- [x] Pro `ACTIVE` sans option ⇒ refus
- [x] Solo `ACTIVE` + option `ACTIVE` ⇒ droit
- [x] Pro `ACTIVE` + option `PAST_DUE` ⇒ droit
- [x] Solo `ACTIVE` + option `CANCELED` ⇒ refus
- [x] Solo `CANCELED` + option `ACTIVE` ⇒ refus (l'option ne tient pas seule)
- [x] Essai (`TRIALING`, plan nul) + option `ACTIVE` ⇒ refus
- [x] `DAILY` `ACTIVE` + option `ACTIVE` ⇒ refus
- [x] Gold `ACTIVE` + option `ACTIVE` ⇒ droit (cumul sans effet de bord)

`AtelierAccessServiceTest` (existant, **tous les tests conservés**) :

- [x] Les sept tests existants passent **sans modification de leurs attentes**
- [x] Ajout : Solo + option ⇒ `hasAccess()` vrai et `requireAccess()` sans exception
- [x] Ajout : le bypass `ADMIN` ne consulte **ni** l'abonnement **ni** le service de droit

### Tests d'intégration

`AtelierOptionAccessApiIntegrationTest` (nouveau) — les cinq contrôleurs, avec et sans option :

- [x] `GET /api/workspaces` — Solo sans option ⇒ 403 ; Solo avec option ⇒ 200
- [x] `POST /api/workspaces/{id}/chat` — Solo sans option ⇒ 403 ; avec option ⇒ pas de 403
- [x] `POST /api/workspaces/{id}/chat/stream` — Solo sans option ⇒ 200 SSE portant `error: forbidden` ;
      avec option ⇒ pas de `forbidden` dans le flux
- [x] `POST /api/workspaces/{id}/agent/stream` — même paire (refus dans le flux, jamais un 403)
- [x] `POST /api/workspaces/git` (clone) — Solo sans option ⇒ 403 ; avec option ⇒ pas de 403
- [x] `GET /api/workspaces/{id}/runner/status` — Solo sans option ⇒ 403 ; avec option ⇒ 200
- [x] **Non-régression Gold** : les mêmes endpoints en Gold `ACTIVE` se comportent exactement comme
      avant la subfeature (les suites `AtelierApiIntegrationTest`, `AtelierChatApiIntegrationTest`,
      `RunnerStatusApiIntegrationTest` provisionnent déjà du Gold et doivent passer inchangées)
- [x] Quota : `GET /api/usage` d'un Solo avec option renvoie le **même** `quotaTokens` que sans option

### Isolation utilisateur

- [x] Applicable — test : l'option d'Alice n'ouvre aucun droit à Bob. Bob (Solo sans option) reste
      refusé alors qu'Alice (Solo avec option) est acceptée, dans la même exécution. Le droit est lu
      depuis l'abonnement **du contexte de sécurité**, jamais depuis un identifiant fourni par le client.

---

## Dépendances

### Subfeatures bloquantes

Aucune. F-09 (abonnements) et F-28/SF-28-06 (gating Atelier) sont livrées.

### Questions ouvertes impactées

- [x] Aucune question de `docs/OPEN_QUESTIONS.md` n'est tranchée ni contournée par cette subfeature.

---

## Notes et décisions

- **D1 — L'option vit sur `subscriptions`, pas dans une table dédiée.** Il y a exactement une ligne
  d'abonnement par utilisateur (contrainte `unique(user_id)`) : y porter l'option garde **une seule
  source de vérité** pour le droit de facturation, évite une jointure sur le chemin le plus chaud du
  produit, et évite une seconde résolution de webhook. Une table dédiée deviendra justifiée le jour
  où il y aura plusieurs options — pas avant.
- **D2 — L'option exige un plan porteur actif (`SOLO` ou `PRO`).** Une option qui tiendrait seule
  serait un plan déguisé à 40 € sans quota : l'utilisateur paierait pour un Atelier qu'aucun jeton
  ne peut faire tourner. `DAILY` est exclu — un pass journée ne porte pas un abonnement mensuel.
  **Arbitrage commercial pris par défaut, à revoir par le product owner.**
- **D3 — Le statut de l'option réutilise `SubscriptionStatus`, y compris `PAST_DUE`.** Le sursis de
  paiement de l'option se comporte comme celui du plan : c'est déjà la règle de Gold, et en faire une
  autre pour l'option aurait créé deux politiques de sursis dans le même produit.
- **D4 — La règle sort de `AtelierAccessService`.** Ce service garde ce qui lui appartient — la
  résolution du principal, le bypass admin, l'exception. La question « cet utilisateur a-t-il le
  droit d'Atelier ? » est une question de **facturation** et vit dans le paquet `billing`, à côté de
  l'abonnement qu'elle lit. Le paquet `atelier` cesse ainsi de connaître `PlanCode`.

- **D5 — Le message de refus change avec la règle.** `AtelierAccessDeniedException` disait « Accès à
  l'Atelier réservé à l'offre Gold. » : c'est devenu faux à la seconde où le droit a cessé d'être un
  plan. Le message dit désormais l'offre **ou** l'option. Le texte d'*upsell* de l'écran
  (`atelier.component.html`) porte la même phrase périmée ; il est corrigé en **SF-40-03**, avec le
  bouton qui rend l'option réellement achetable — l'avancer ici aurait montré une porte qui n'ouvre
  pas encore.
