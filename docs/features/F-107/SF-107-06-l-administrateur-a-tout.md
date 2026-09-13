# Mini-spec — [F-107 / SF-107-06] L'administrateur a tout

---

## Identifiant

`F-107 / SF-107-06`

## Feature parente

`F-107` — L'offre par espace : la plateforme se paie, en BYOK aussi
(cadrage : `CADRAGE-F-107-l-offre-par-espace.md`, **§3 bis**, décision du PO du 2026-09-13)

## Statut

`in-progress`

## Date de création

2026-09-13

## Branche Git

`feat/SF-107-06-l-administrateur-a-tout`

---

## Objectif

Un utilisateur de rôle `ADMIN` (`users.role`) a **tous les droits de fonctionnalité** (Forge, volet
Teams, Radar, et tout droit futur) quel que soit son plan, la règle vivant **dans les services de
droits** ; le quota de jetons n'est pas concerné.

---

## Contexte

Le bypass administrateur existe aujourd'hui **dans les services d'accès** (`AtelierAccessService`,
`TeamsAccessService`), et seulement quand le **principal courant** est l'administrateur. Il manque
partout où le droit est lu **hors requête** ou **par un autre chemin** :

- `TeamsAccessService.hasAccess(userId)` sans principal (relance du runner, flux SSE) retombe sur
  `TeamsEntitlementService.isEntitled`, qui ignore le rôle → l'agent d'un administrateur perd ses
  outils `teams_*` (`TeamsToolCatalog.toolsFor`) ;
- `RadarSyncPlanner` (nuit, aucun principal) et `RadarExchangeAnalyzer` passent par ce même chemin →
  le Radar d'un administrateur sans option ne se synchronise pas ;
- `RunnerTeamsMomentController` lit `TeamsEntitlementService.isEntitled` directement → les captures
  d'un administrateur sont refusées ;
- `AtelierOptionService.describe` (écran de facturation) lit `AtelierEntitlementService`.

`SpaceEntitlementService` (SF-107-02) **n'existe pas encore sur `main`** : la règle est posée dans
`AtelierEntitlementService` et `TeamsEntitlementService`, que SF-107-02 absorbera en la conservant
(cadrage §3 bis). Aucun autre service de droit de fonctionnalité n'existe (Radar et Vigie lisent le
droit Teams).

---

## Comportement attendu

### Cas nominal

1. **Une source unique du rôle** : composant `AdministratorEntitlement` (paquet `billing`),
   `isAdministrator(UUID userId)` → lit `users.role` par `UserRepository.findById` ; `true` ssi
   `role == ADMIN`. Utilisateur inconnu ou `userId` nul → `false` (fail-closed).
2. **`AtelierEntitlementService.isEntitled(userId)` et `isEntitled(Subscription)`** → `true` si
   administrateur, sinon règle inchangée (Gold, option sur plan porteur, accès offert).
3. **`TeamsEntitlementService.isEntitled(userId)`** → `true` si administrateur, sinon règle inchangée
   (option sur plan porteur, accès offert). Par ricochet : `TeamsAccessService.hasAccess(userId)`
   sans principal, `TeamsToolCatalog.toolsFor`, `RadarSyncPlanner`, `RadarExchangeAnalyzer`,
   `RunnerTeamsMomentController`.
4. **Écran de facturation honnête** : `AtelierOptionView` / `AtelierOptionResponse` gagnent
   `includedForAdministrator` (additif). Quand il est vrai et que le plan n'inclut pas la Forge, la
   carte « Option Forge » affiche le drapeau **« Incluse (administrateur) »**, une phrase disant que
   rien n'est facturé, et un bouton désactivé — aucune proposition d'achat.
5. **Ce que le rôle n'ouvre pas** : `EntitlementService` (quota de jetons), `QuotaService`, le
   supplément par poste — **aucune ligne modifiée**. `isIncludedInPlan` et `isGrantedByOption`
   restent des lectures d'abonnement pures (ils disent d'où vient le droit, pas s'il est ouvert).

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| `userId` inconnu en base | Pas administrateur → règle d'abonnement (fail-closed) | — |
| `userId` nul | Pas administrateur | — |
| `USER` sans option ni plan | Refus inchangé (`AtelierAccessDeniedException` / outils absents / 403 captures) | 403 |
| Rôle rétrogradé `ADMIN` → `USER` | Le droit se ferme à la lecture suivante (pas de cache) | — |

---

## Critères d'acceptation

- [ ] CA1 — ADMIN sans plan porteur ni option : `AtelierEntitlementService.isEntitled` → `true`
      (par `userId` et par `Subscription`).
- [ ] CA2 — ADMIN sans option Teams : `TeamsEntitlementService.isEntitled` → `true` ; `TeamsToolCatalog.toolsFor`
      rend les outils `teams_*` **même sans principal** (chemin `hasAccess(userId)`).
- [ ] CA3 — USER sans option : refus inchangé sur les deux services (tests existants verts).
- [ ] CA4 — Quota inchangé pour ADMIN : `EntitlementService.resolveEffectiveMonthlyTokenQuota` d'un
      abonnement ADMIN expiré vaut `0` ; aucun fichier du paquet `quota` modifié.
- [ ] CA5 — `GET /api/billing/atelier-option` d'un ADMIN sans option : `entitled=true`,
      `includedForAdministrator=true`, `includedInPlan=false` ; l'écran affiche « Incluse (administrateur) »
      sans bouton d'achat actif.
- [ ] CA6 — `isIncludedInPlan` / `isGrantedByOption` ne lisent pas le rôle.

---

## Périmètre

### Hors scope (explicite)

- `SpaceEntitlementService` et les espaces FORGE/VIGIE (SF-107-02).
- Quota de jetons, crédit manuel, supplément par poste (§3 bis : non ouverts par le rôle).
- Refus de la souscription d'option par un administrateur (il peut toujours payer ; l'écran ne le
  propose pas).
- Modification des bypass par principal d'`AtelierAccessService` / `TeamsAccessService` (conservés :
  ils évitent une lecture et restent cohérents avec la nouvelle règle).

---

## Valeurs initiales

Aucune.

## Contraintes de validation

Aucun champ saisi.

---

## Technique

### Endpoint(s)

| Méthode | URL | Changement |
|---------|-----|------------|
| GET | `/api/billing/atelier-option` (+ `checkout`, `cancel`) | Champ additif `includedForAdministrator` |

### Tables impactées

`users` (lecture de `role`) ; `subscriptions` (lecture, inchangée). **Aucune migration.**

### Migration Liquibase

Aucune.

### Composants Angular (si applicable)

`billing.component` (carte Option Forge), `billing.models.ts` (`AtelierOptionView`).

### Préoccupations transversales

- [x] **Plans / limites** — appels aux services de droits de fonctionnalité impactés :
  `AtelierAccessService`, `AtelierAgentController`, `AtelierChatController` (via `AtelierAccessService`),
  `AtelierOptionService` (`describe`, `startCheckout` lit `isIncludedInPlan` seul — inchangé),
  `TeamsAccessService` → `TeamsAccessController`, `TeamsToolCatalog`, `RadarSyncPlanner`,
  `RadarExchangeAnalyzer` ; `RunnerTeamsMomentController` (direct). Gates de quota
  (`EntitlementService`, `QuotaService`, `QuotaWindowService`, `QuotaAlertService`) : **non touchés**.
- [ ] Auth / Principal — non : le principal n'est pas modifié, le rôle est lu en base.
- [ ] Contexte tenant — non.
- [ ] Navigation / routing — non.

---

## Plan de test

### Tests unitaires

- [ ] `AtelierEntitlementServiceTest` — ADMIN sans option → accès (userId et Subscription) ; USER sans
      option → refus ; `isIncludedInPlan`/`isGrantedByOption` restent faux pour ADMIN sans plan.
- [ ] `TeamsEntitlementServiceTest` — ADMIN sans option → accès sans lire l'abonnement ; USER → refus.
- [ ] `AdministratorEntitlementTest` — ADMIN → vrai ; USER, inconnu, nul → faux.
- [ ] `TeamsToolCatalogTest` — ADMIN, aucun principal → outils `teams_*` présents (service réel).
- [ ] `AtelierOptionServiceTest` — `describe` d'un ADMIN : `includedForAdministrator=true`.
- [ ] `EntitlementServiceTest` (quota) — inchangé, vert.
- [ ] `billing.component.spec` — ADMIN : « Incluse (administrateur) », pas d'achat.

### Tests d'intégration

- [ ] `AtelierOptionBillingApiIntegrationTest` — ADMIN sans option : `entitled`, `includedForAdministrator`.

### Isolation workspace

- [x] Applicable — le rôle est lu pour le `userId` du contexte de sécurité ou du tour, jamais d'un
      paramètre client ; aucun accès nouveau à des données d'un autre utilisateur.

---

## Dépendances

### Subfeatures bloquantes

Aucune. SF-107-02 devra conserver la règle.

### Questions ouvertes impactées

Aucune.

---

## Notes et décisions

- **Rôle lu en base, pas dans le principal** : les chemins sans requête (nuit, runner) n'ont pas de
  principal ; une seule source (`users.role`) évite deux vérités.
- **Composant dédié plutôt que `UserService`** : les deux services de droits le partagent, SF-107-02
  l'absorbera tel quel.
