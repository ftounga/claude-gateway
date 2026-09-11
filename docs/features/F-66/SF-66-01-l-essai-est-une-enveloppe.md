# Mini-spec — SF-66-01 · L'essai est une enveloppe, pas un abonnement mensuel gratuit

## Identifiant

`F-66 / SF-66-01`

## Feature parente

`F-66` — L'essai tient sa promesse (cadrage : `docs/features/F-66/F-66-cadrage.md`)

## Statut

`done` — PR #370, mergée le 2026-09-11

## Date de création

2026-09-11

## Branche Git

`feat/SF-66-01-enveloppe-essai`

---

## Objectif

Faire que le plafond de l'essai gratuit (`app.quota.trial-tokens`, 200 000 jetons) s'oppose à la
consommation **cumulée depuis le début de l'essai**, et non au seul mois calendaire courant — de
sorte qu'un essai à cheval sur un 1er du mois cesse de valoir deux fois son plafond.

---

## Le défaut, en une phrase

Le compteur opposé au quota a pour clé `(user_id, premier jour du mois UTC)`. L'essai, lui, n'est pas
un mois : c'est une fenêtre de *n* jours posée n'importe où dans le calendrier. Un essai commencé le
28 août dispose de 200 000 jetons en août **et de 200 000 autres le 1er septembre** — 400 000 jetons,
≈ 3,60 $ au lieu de ≈ 1,80 $.

Ce défaut est **antérieur** à F-66 et indépendant de la durée. Mais il est **aggravé** par elle :
passer de 5 à 14 jours fait passer la part des essais à cheval de ≈ 13 % à ≈ 47 %. D'où l'ordre :
cette subfeature **précède** le changement de durée (SF-66-02).

---

## Comportement attendu

### Cas nominal

1. Un abonnement **en essai actif** (`TRIALING` et `trial_ends_at` non dépassé) se voit attribuer une
   **fenêtre de quota** qui commence au **premier jour du mois où l'essai a commencé**, et qui court
   jusqu'au mois courant inclus.
2. Le **début de l'essai** est la **création de l'abonnement** (`subscriptions.created_at`) — date
   exacte de tout essai provisionné par la gateway (`SubscriptionService.provisionTrial`) — ou, à
   défaut, `trial_ends_at − app.billing.trial-days`.
   La fenêtre est en outre **bornée dure** : elle ne remonte **jamais** avant le **mois précédent**.
   Un essai se compte en jours, il ne peut chevaucher qu'un seul 1er du mois ; et si un abonnement
   ancien se retrouvait `trialing`, cette borne empêche la fenêtre de remonter à la naissance du
   compte. Le bornage est volontairement **conservateur** : dans ce cas limite, la consommation du
   mois précédent compte contre l'essai. Mieux vaut un essai trop strict qu'un plafond qui fuit.
3. Pendant cet essai, la **consommation opposée** est la somme des `billed_tokens` de toutes les
   lignes `usage_counters` de la fenêtre, et le **quota opposé** est l'allocation d'essai augmentée
   des **jetons rachetés** (bonus, F-21) crédités sur la **même** fenêtre — un pack acheté en août ne
   doit pas s'évaporer le 1er septembre pour un essai qui court encore.
4. Hors essai actif (abonnement payant, essai expiré, abonnement résilié), la fenêtre reste **le mois
   calendaire courant** : rien ne change pour les clients payants, dont l'allocation *est* mensuelle.
5. Les trois lectures du quota disent le **même** chiffre, parce qu'elles utilisent la même fenêtre :
   le **pré-vol** (`assertWithinQuota`), la **jauge** (`GET /api/usage`) et le **seuil d'alerte**
   (F-42).
6. La jauge d'un essai annonce la fenêtre de l'**essai** (du début de l'essai à `trial_ends_at`) et
   non celle du mois : afficher « période du 1er au 30 septembre » sur un plafond qui ne se
   renouvelle pas au 1er octobre serait un mensonge de plus.

### Cas d'erreur

| # | Situation | Comportement attendu |
|---|---|---|
| E1 | `trial_ends_at` est `null` (essai sans fin enregistrée) | La fenêtre part de la création de l'abonnement ; aucun calcul ne lève. L'essai reste actif (comportement existant de `EntitlementService`). |
| E2 | `created_at` est `null` (entité non encore persistée, données anciennes) | Repli sur `trial_ends_at − trial-days` ; si les deux manquent, repli sur le **mois courant** — c'est-à-dire exactement le comportement d'avant cette subfeature. Jamais d'exception. |
| E3 | La fenêtre calculée commencerait **après** le mois courant (horloge, date de fin aberrante) | Elle est ramenée au mois courant : une fenêtre vide ne doit pas rendre un quota infini ni un usage nul. |
| E4 | Essai **expiré** (`trial_ends_at` dépassé) | Quota `0` et blocage, inchangé. La fenêtre redevient le mois courant : on ne ressuscite pas un essai en changeant de fenêtre. |
| E5 | Offre **BYOK** en cours (F-41) | Aucun quota plateforme n'est opposé : le pré-vol sort avant tout calcul de fenêtre, comportement strictement inchangé. |

---

## Critères d'acceptation

1. Un essai actif commencé le mois précédent et ayant consommé `trial-tokens` le mois précédent est
   **bloqué** ce mois-ci : le pré-vol lève `QuotaExceededException`.
2. Le même essai, ayant consommé la moitié du plafond le mois précédent, peut consommer l'autre
   moitié ce mois-ci, puis est bloqué.
3. `GET /api/usage` pour cet essai annonce `usedTokens` = cumul des deux mois, `quotaTokens` =
   allocation d'essai (+ bonus de la fenêtre), `remainingTokens` cohérent, et une fenêtre bornée par
   `trial_ends_at`.
4. Un **abonnement payant** (`ACTIVE`) qui a consommé son quota le mois précédent **n'est pas**
   bloqué ce mois-ci : son allocation est mensuelle, et elle le reste.
5. Un pack de jetons racheté le mois précédent pendant l'essai reste crédité ce mois-ci, tant que
   l'essai court.
6. Le seuil d'alerte (F-42) d'un essai à cheval se juge sur le **cumul** de la fenêtre : un essai à
   95 % de son enveloppe est signalé, même si la ligne du mois courant est presque vide.
7. Aucun changement de comportement pour : abonnement payant, offre BYOK, essai expiré, essai qui ne
   traverse aucun 1er du mois.
8. Aucune modification de schéma, aucune migration, aucune valeur commerciale modifiée
   (`trial-tokens` reste à 200 000, `trial-days` reste à 5 — c'est SF-66-02 qui le changera).

---

## Plan de test minimal

### Unitaires

- `QuotaWindowServiceTest` : fenêtre d'un essai commencé le mois courant ; d'un essai commencé le mois
  précédent ; d'un abonnement `ACTIVE` (mois courant) ; d'un essai expiré (mois courant) ; cas E1,
  E2, E3.
- `TrialEnvelopeTest` : pré-vol bloquant sur cumul inter-mois pour un essai (critère 1) ; pré-vol
  passant pour un `ACTIVE` dans la même situation (critère 4) ; bonus de la fenêtre pris en compte
  (critère 5) ; instantané d'usage cumulé et bornes de fenêtre d'essai (critère 3).
- `QuotaAlertServiceTest` : seuil franchi sur le cumul de la fenêtre d'essai (critère 6).

### Intégration

- `TrialEnvelopeApiIntegrationTest` : un utilisateur en essai avec une ligne d'usage du mois
  précédent — `GET /api/usage` rend le cumul et la fenêtre d'essai ; un appel servi est refusé
  `429/402` selon le mapping existant de `QuotaExceededException` lorsque l'enveloppe est épuisée.

### Isolation utilisateur

- Les lignes d'usage d'un **autre** utilisateur, même sur les mêmes mois, n'entrent jamais dans la
  fenêtre : toutes les lectures passent par une méthode de repository filtrant `user_id`. Test
  dédié : deux utilisateurs en essai, l'un épuisé, l'autre intact.

---

## Tables / endpoints / composants impactés

### Tables

Aucune création, aucune modification. **Lecture** supplémentaire de `usage_counters` sur plusieurs
périodes (`user_id` + `period_start >= début de fenêtre`) — au plus deux lignes pour un essai de
14 jours.

### Endpoints

Aucun endpoint créé ni modifié dans son contrat. `GET /api/usage` change la **valeur** de
`periodStart` / `periodEnd` **pendant un essai** (bornes de l'essai au lieu du mois), les types
restant identiques.

### Composants

| Composant | Changement |
|---|---|
| `quota/QuotaWindowService` (nouveau) | Résout la fenêtre de quota d'un abonnement et le **report** (consommation, volume traité et bonus des périodes antérieures de la fenêtre). |
| `quota/QuotaWindow` (nouveau, record) | Porte la fenêtre : mois de début, bornes d'affichage, report (`billedTokens`, `processedTokens`, `bonusTokens`). |
| `quota/QuotaService` | Pré-vol et instantané d'usage raisonnent sur la fenêtre. |
| `quota/QuotaAlertService` | Seuil jugé sur le cumul de la fenêtre. |
| `quota/EntitlementService` | Expose `hasActiveTrial(Subscription)` (prédicat déjà présent en privé). |
| `quota/UsageCounterRepository` | Ajout de `findByUserIdAndPeriodStartGreaterThanEqual` (filtre `user_id`). |

---

## Préoccupation transversale — **Plans / limites** (déclenchée)

Composants qui consomment une limite, et vérification de chacun :

| Appelant | Ce qu'il appelle | Effet de cette SF |
|---|---|---|
| `chat/ChatService` (2 points) | `assertWithinQuota` | Un essai à cheval est désormais bloqué au bon moment. Aucun autre changement. |
| `ask/AskService` | `assertWithinQuota` | idem |
| `atelier/AtelierChatService` | `assertWithinQuota`, `currentUsage().remainingTokens()` | Le budget de tour d'un essai reflète l'enveloppe réelle au lieu d'un plafond neuf. |
| `atelier/agent/AtelierSessionService` | `assertWithinQuota`, `currentUsage()` | idem |
| `quota/UsageController` (`GET /usage`) | `currentUsage` | Jauge cumulée pendant un essai. |
| `quota/QuotaAlertService` | `resolveEffectiveMonthlyTokenQuota` + compteur | Seuil jugé sur la fenêtre. |
| `billing/*` (catalogue de plans) | `resolveMonthlyTokenQuota` | **Non touché** : le catalogue annonce l'allocation du plan, indépendante de toute fenêtre. |

---

## Contraintes de validation

| Élément | Contrainte | Source |
|---|---|---|
| `app.quota.trial-tokens` | **200 000**, inchangé | `docs/TARIFS.md` §4 |
| `app.billing.trial-days` | **5** à cette étape, inchangé ici | `docs/TARIFS.md` §4 |
| Grain de la fenêtre | Le **mois** — c'est le grain des compteurs (F-10) ; prétendre au jour laisserait croire à une précision qui n'existe pas | `UsageCounter`, `UsageWindow` |
| Dimensionnement de l'essai | **À CONFIRMER PAR LE PO** — non tranché ici | `OQ-16` point 9 |

---

## Hors périmètre

- Changer la **durée** de l'essai (SF-66-02) et son **affichage** (SF-66-03).
- Changer `trial-tokens`, ou tout autre montant commercial.
- Changer le grain des compteurs d'usage (passer la période au jour, ou à une période glissante par
  abonnement). Ce serait une refonte de F-10 dont l'essai n'a pas besoin.
- Rattraper **rétroactivement** les essais déjà à cheval : aucun correctif de données.

---

## Décisions techniques

1. **Fenêtre calculée, pas stockée.** Aucune colonne, aucune migration : la fenêtre se déduit de
   l'abonnement, et le report se lit sur des lignes qui existent déjà. Réversible en retirant un
   service.
2. **Début d'essai = création de l'abonnement, fenêtre bornée au mois précédent.** La création est
   la date exacte de nos essais ; le bornage protège le cas limite d'un abonnement ancien devenu
   `trialing`, sans faire dépendre la fenêtre d'une durée configurée qui peut changer (elle change
   en SF-66-02 précisément).
4. **Le volume traité est reporté lui aussi**, pas seulement le décompte facturé : afficher un
   volume du mois à côté d'un décompte de tout l'essai ferait dire deux durées différentes à la
   même jauge (F-63 / SF-63-03).
3. **Le report inclut les bonus.** Un pack racheté pendant l'essai appartient à l'essai, pas au mois
   où il a été acheté.
