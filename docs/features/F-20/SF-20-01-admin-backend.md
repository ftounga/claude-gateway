# Mini-spec — [F-20 / SF-20-01] Console admin & super-admin — backend

## Identifiant

`F-20 / SF-20-01`

## Feature parente

`F-20` — Console admin & super-admin

## Statut

`ready`

## Date de création — Branche

2026-07-03 — `feat/SF-20-01-admin-backend`

---

## Objectif

Exposer une API d'administration réservée au rôle `ADMIN` (et au **super-admin** `ntounga@gmail.com`)
donnant, pour tous les utilisateurs, leur abonnement et leur consommation de tokens — sans jamais
affaiblir l'isolation des utilisateurs standard.

## Comportement attendu

### Cas nominal

- `GET /api/admin/users` (ADMIN uniquement) renvoie la liste des utilisateurs : `id`, `email`, `role`,
  `createdAt`, plan d'abonnement (`planCode`, `status`, `currentPeriodEnd`) et consommation totale de
  tokens (`totalTokens`).
- **Super-admin** : l'utilisateur dont l'e-mail vaut `app.admin.super-admin-email` (défaut
  `ntounga@gmail.com`) est autorisé même si son rôle stocké n'est pas encore `ADMIN`. Au démarrage, un
  bootstrap **promeut** cet e-mail au rôle `ADMIN` s'il existe (le JWT porte alors `ROLE_ADMIN`).

### Cas d'erreur

| Situation | Comportement | Code |
|-----------|--------------|------|
| Utilisateur `USER` (non super-admin) | Accès refusé | 403 |
| Non authentifié | Rejeté par la chaîne JWT | 401 |

---

## Critères d'acceptation

- [ ] `GET /api/admin/users` renvoie 200 + la liste agrégée pour un `ADMIN`.
- [ ] Le même appel par un `USER` non super-admin renvoie **403** (aucune donnée).
- [ ] Le super-admin (`ntounga@gmail.com`) est autorisé et, après bootstrap, porte le rôle `ADMIN`.
- [ ] Chaque ligne agrège l'abonnement (`SubscriptionRepository`) et l'usage total (`UsageCounterRepository`).
- [ ] Aucune donnée sensible (clé, token) exposée ; l'isolation `user_id` des endpoints existants est inchangée.

---

## Périmètre / Hors scope

- Écran admin (SF-20-02, frontend). Édition d'abonnement / actions admin (annulation, remboursement) — ultérieur.
- Pagination : liste simple en V1 (volume faible).

## Préoccupation transversale — Auth / rôles (analyse d'impact)

| Composant | Impact | Vérification |
|-----------|--------|--------------|
| `SecurityConfig` | `/admin/**` reste `authenticated()` ; l'autorisation fine (ADMIN) est appliquée en service | 403 pour USER, 200 pour ADMIN |
| `JwtAuthenticationFilter` | Inchangé ; porte déjà `ROLE_ADMIN`/`ROLE_USER` | — |
| `CurrentUser` | Utilisé pour lire rôle + email et autoriser | Test |
| Bootstrap super-admin | Promotion idempotente de l'e-mail configuré au démarrage | Test unitaire |
| Endpoints existants | Aucun changement (l'admin est un ajout, isolation user standard inchangée) | Suite existante inchangée |

---

## Technique

### Endpoint

| Méthode | URL | Auth | Rôle |
|---------|-----|------|------|
| GET | `/api/admin/users` | Oui | ADMIN (ou super-admin par e-mail) |

### Composants backend

- `admin/AdminService` : `assertAdmin()` (rôle ADMIN **ou** email == super-admin) ; `listUsers()` agrège
  `UserRepository.findAll` + `SubscriptionRepository.findByUserId` + `UsageCounterRepository.findByUserId`.
- `admin/AdminController` : `GET /admin/users`.
- `admin/dto/AdminUserView` : record de réponse.
- `admin/AdminForbiddenException` → 403 dans `GlobalExceptionHandler`.
- `admin/SuperAdminBootstrap` : `ApplicationReadyEvent` → promeut l'e-mail configuré en `ADMIN` si présent.
- `application.yml` : `app.admin.super-admin-email: ntounga@gmail.com`.

### Migration

- [x] Non applicable (aucune table ; le rôle `ADMIN` existe déjà dans `UserRole`).

---

## Plan de test

- [ ] `AdminServiceTest` — `assertAdmin` : ADMIN autorisé, super-admin (email) autorisé, USER → `AdminForbiddenException` ; `listUsers` agrège abonnement + usage.
- [ ] `AdminApiIntegrationTest` — `GET /api/admin/users` : 200 pour ADMIN, **403** pour USER, 401 sans token.
- [ ] `SuperAdminBootstrap` — promeut l'e-mail configuré au rôle ADMIN (idempotent).

### Isolation

- [x] L'admin est un ajout gaté ; les endpoints utilisateur restent isolés par `user_id` (inchangés).

## Notes

- **Autorisation en service** (cohérent avec le codebase, pas de method-security activée). Le super-admin par e-mail garantit l'accès même sans promotion préalable ; le bootstrap aligne le rôle stocké pour que l'UI (lien Admin conditionné au rôle) fonctionne.
