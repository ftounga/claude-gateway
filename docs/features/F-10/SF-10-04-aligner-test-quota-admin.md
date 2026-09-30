# Mini-spec — [F-10 / SF-10-04] Aligner le test de quota admin sur l'exemption livrée en SF-10-03

## Identifiant

`F-10 / SF-10-04`

## Feature parente

`F-10` — Quotas & entitlements

## Statut

`ready`

## Date de création

2026-09-30

## Branche Git

`feat/SF-10-04-aligner-test-quota-admin`

---

## Objectif

> En une phrase : que fait cette subfeature ?

Aligner le test d'intégration `AtelierOptionBillingApiIntegrationTest#theAdministratorRoleDoesNotChangeTheTokenQuota` sur le comportement **déjà livré** en SF-10-03 (l'ADMIN a un quota de jetons **illimité**, la mesure restant intacte) — correction d'une scorie de test, **aucun changement de comportement runtime**.

---

## Contexte / décision

SF-10-03 (PR #1024, commit `7869a4bf`, décision PO du 2026-09-30 « l'admin a tout ») a rendu le rôle ADMIN **exempt du bridage de quota** : `EntitlementService.resolveEffectiveMonthlyTokenQuota(subscription)` renvoie `UNLIMITED_TOKEN_QUOTA` dès que le titulaire est ADMIN — check placé **en tête**, donc primant sur tous les cas (essai, expiré/résilié, BYOK). Le test `theAdministratorRoleDoesNotChangeTheTokenQuota` encode l'**ancien** comportement (quota ADMIN = quota USER ; 0 si abonnement résilié) et échoue depuis SF-10-03. Ce test n'a pas été mis à jour dans SF-10-03 : c'est la scorie corrigée ici. Échec déjà constaté et documenté dans l'historique PRODUCT_SPEC (SF-169-02 : « unique échec pré-existant et sans rapport : `AtelierOptionBillingApiIntegrationTest`, quota admin »).

---

## Comportement attendu

### Cas nominal

- Le test réécrit affirme, via `GET /api/usage`, que pour un **ADMIN** le `quotaTokens` exposé vaut `EntitlementService.UNLIMITED_TOKEN_QUOTA` (l'ADMIN n'est jamais bridé), tandis qu'un **USER** sur le même plan (SOLO) voit le quota **fini** de son plan (`< UNLIMITED_TOKEN_QUOTA` et strictement positif) — le rôle change donc bien le quota, à l'inverse de ce qu'affirmait l'ancien test.
- Le test vérifie qu'un ADMIN dont l'abonnement est **résilié** (`CANCELED`) conserve son quota **illimité** (l'exemption est placée avant le contrôle de statut) — là où l'ancien test attendait 0.
- La classe complète `AtelierOptionBillingApiIntegrationTest` passe au vert.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Un USER (non-ADMIN) sur SOLO | `quotaTokens` = quota fini du plan, inchangé (aucune régression du comportement de quota non-admin, garanti par les autres tests de la classe et de `EntitlementServiceTest`) |
| Régression : réintroduction du bridage admin | Le test échouerait (il exige désormais l'illimité) — filet anti-retour-arrière |

---

## Critères d'acceptation

- [ ] `theAdministratorRoleDoesNotChangeTheTokenQuota` est renommé/réécrit pour refléter l'exemption : ADMIN → `quotaTokens` = `UNLIMITED_TOKEN_QUOTA`.
- [ ] Le test vérifie que le quota USER (SOLO) est fini et distinct de l'illimité (le rôle change le quota).
- [ ] Le test vérifie qu'un ADMIN **résilié** reste illimité (et non 0).
- [ ] Aucune autre assertion de la classe ne dépend de l'ancien comportement admin (vérifié).
- [ ] Aucun code de production modifié — test uniquement.
- [ ] La classe `AtelierOptionBillingApiIntegrationTest` passe intégralement au vert.

---

## Périmètre

### Hors scope (explicite)

- **Aucun** changement de comportement runtime (pas de modification d'`EntitlementService`, `QuotaService`, ni d'aucun code de production).
- Pas de nouvelle table, endpoint, migration, ni composant frontend.
- Pas de modification des autres tests de quota (`QuotaServiceTest`, `EntitlementServiceTest`…), déjà alignés en SF-10-03.

---

## Technique

### Endpoint(s)

Aucun créé/modifié. Le test exerce le `GET /api/usage` existant.

### Tables impactées

Aucune.

### Migration Liquibase

- [ ] Oui
- [x] Non applicable

### Composants Angular (si applicable)

Aucun.

---

## Plan de test

### Tests unitaires

- Sans objet (correction d'un test d'intégration existant).

### Tests d'intégration

- [x] `AtelierOptionBillingApiIntegrationTest#theAdministratorRoleGrantsAnUnlimitedTokenQuota` (renommé) — ADMIN → `quotaTokens` illimité ; USER → quota fini distinct ; ADMIN résilié → toujours illimité.
- [x] Rejouer la **classe complète** `AtelierOptionBillingApiIntegrationTest` → verte.

### Isolation workspace / user_id

- [x] Applicable — le rôle est résolu côté serveur depuis l'utilisateur authentifié (jeton JWT), jamais un paramètre client ; les tokens de chaque utilisateur (alice, admin) sont distincts et isolés. Inchangé par cette correction.

---

## Préoccupation transversale — « Plans / limites »

Correction de test uniquement, **aucun gate de limite modifié**. Les gates de quota (pré-vol, jauge, alerte, borne du tour) restent ceux livrés et testés par SF-10-03 (`QuotaServiceTest`, `EntitlementServiceTest`). Composant impacté : **le seul fichier de test** `AtelierOptionBillingApiIntegrationTest.java`. Aucun autre.

---

## Dépendances

### Subfeatures bloquantes

- `SF-10-03` — statut : done (comportement de référence).

### Questions ouvertes impactées

- Aucune.

---

## Notes et décisions

- `UNLIMITED_TOKEN_QUOTA` = `Long.MAX_VALUE / 2` : le `quotaTokens` exposé par `GET /usage` pour un ADMIN vaut cette valeur (+ bonus/report = 0 dans le test). L'assertion lit `quotaTokens` en `Long` (comme `theOptionDoesNotChangeTheQuota`) et le compare à la constante publique `EntitlementService.UNLIMITED_TOKEN_QUOTA`.
