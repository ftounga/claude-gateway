# Mini-spec — [F-01 / SF-01-07] Écrans Angular `auth/`

## Identifiant

`F-01 / SF-01-07`

## Feature parente

`F-01` — Authentification

## Statut

`in-progress`

## Date de création

2026-07-01

## Branche Git

`feat/SF-01-07-ecrans-auth`

---

## Objectif

Fournir les écrans Angular d'authentification (login, register, vérification d'e-mail, mot de passe oublié/reset, callback OAuth, profil) consommant les endpoints back de F-01, conformes au design system.

---

## Comportement attendu

### Cas nominal

- **Login** (`/login`) : formulaire e-mail/mot de passe → `POST /api/auth/login` → stocke le JWT → redirige `/profile`. Bouton « Continuer avec Google » → redirection navigateur vers `/api/oauth2/authorization/google`.
- **Register** (`/register`) : formulaire e-mail/mot de passe → `POST /api/auth/register` → message « vérifiez vos e-mails » → lien vers login.
- **Vérification** (`/auth/verify?token=`) : au chargement, appelle `GET /api/auth/verify?token=` → affiche succès ou échec.
- **Mot de passe oublié** (`/auth/forgot`) : e-mail → `POST /api/auth/password/forgot` → message générique.
- **Reset** (`/auth/reset?token=`) : nouveau mot de passe → `POST /api/auth/password/reset` → succès → lien login.
- **Callback OAuth** (`/auth/callback`) : lit le fragment `#token=` (ou `#error=`), stocke le JWT, redirige `/profile`.
- **Profil** (`/profile`, protégé) : `GET /api/me` affiche le compte ; édition e-mail → `PUT /api/me` ; boutons « Se déconnecter » (`/logout` + purge token) et « Déconnecter toutes les sessions » (`/logout-all` + purge token).

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Login invalide (401) | `MatSnackBar` erreur « Identifiants invalides » |
| Register e-mail déjà pris (409) | `MatSnackBar` erreur + `mat-error` |
| Champs invalides | `mat-error` sous le champ, bouton désactivé |
| 401 sur une route protégée | Interceptor purge le token et redirige `/login` |
| Token de vérif/reset invalide (400) | Message d'échec à l'écran |

---

## Critères d'acceptation

- [ ] Les 7 écrans existent et sont routés ; `/profile` est protégé par un guard.
- [ ] Le JWT est stocké (localStorage) et injecté en `Authorization: Bearer` par un interceptor.
- [ ] Un 401 sur une route protégée purge le token et redirige vers `/login`.
- [ ] Le bouton Google déclenche une redirection vers `/api/oauth2/authorization/google`.
- [ ] Le callback lit le token dans le fragment et connecte l'utilisateur.
- [ ] Couleurs/polices/espacements conformes `DESIGN_SYSTEM.md` ; `mat-form-field` `outline` + `mat-error` ; notifications `MatSnackBar` ; aucun `window.alert/confirm/prompt`.
- [ ] Tests unitaires (service + composants) avec `AuthService` mocké ; `npm run build` et `npm test -- --watch=false` verts.

---

## Périmètre

### Hors scope (explicite)

- Layout applicatif complet (header/sidenav) et interface de chat — hors F-01.
- Refresh token, « se souvenir de moi ».
- Internationalisation.

---

## Technique

### Composants Angular (standalone)

- `core/services/auth.service.ts` — appels API + gestion du token (store/clear/isAuthenticated).
- `core/models/auth.models.ts` — interfaces DTO (contrats back figés).
- `core/interceptors/auth.interceptor.ts` — injecte le Bearer, gère 401.
- `core/guards/auth.guard.ts` — protège `/profile`.
- `auth/login`, `auth/register`, `auth/verify-email`, `auth/forgot-password`, `auth/reset-password`, `auth/oauth-callback`, `auth/profile`.

### Contrats API consommés (figés par le back F-01)

| Méthode | URL | Corps | Réponse |
|---------|-----|-------|---------|
| POST | `/api/auth/register` | `{email,password}` | 201 `{id,email,emailVerified,provider,role}` |
| POST | `/api/auth/login` | `{email,password}` | 200 `{accessToken,tokenType,user}` |
| GET | `/api/auth/verify?token=` | — | 200 `{verified,email}` / 400 |
| POST | `/api/auth/password/forgot` | `{email}` | 200 `{message}` |
| POST | `/api/auth/password/reset` | `{token,password}` | 200 `{message}` / 400 |
| GET | `/api/me` | — | 200 `{id,email,emailVerified,provider,role}` |
| PUT | `/api/me` | `{email}` | 200 MeResponse / 409 / 400 |
| POST | `/api/me/logout` / `/logout-all` | — | 200 `{message}` |
| (redir) | `/api/oauth2/authorization/google` | — | 302 Google → callback `#token=` |

### Migration Liquibase

- [x] Non applicable (frontend).

---

## Plan de test

### Tests unitaires (mock du service / HttpTestingController)

- [ ] `AuthService` — login stocke le token ; logout purge ; isAuthenticated ; register ; verify ; forgot ; reset ; updateEmail.
- [ ] `LoginComponent` — soumet et redirige ; affiche l'erreur sur 401 (AuthService mocké).
- [ ] `RegisterComponent` — soumet ; message succès.
- [ ] `ProfileComponent` — charge le profil ; logout purge + navigate.
- [ ] `authGuard` — bloque sans token, laisse passer avec token.
- [ ] `authInterceptor` — ajoute l'en-tête ; 401 → purge + redirect.

### Isolation utilisateur

- [x] Non applicable côté front (l'isolation est garantie côté back via `user_id`/JWT). Le front n'expose que les données renvoyées pour le compte courant.

---

## Dépendances

### Subfeatures bloquantes

- `SF-01-02..06` — Done (contrats API figés).

### Questions ouvertes impactées

- Aucune.

---

## Notes et décisions

- **Stockage du JWT en `localStorage`** (arbitrage) : simple, adapté à une SPA stateless V1. Compromis XSS assumé et documenté ; une migration vers cookie `HttpOnly` + CSRF est possible plus tard (réversible). Pas de secret autre que le JWT côté client.
- **OAuth via redirection pleine page** (non XHR) puis lecture du token dans le fragment `#token=` au retour (`/auth/callback`).
- **Guard minimal** basé sur la présence du token ; la validité réelle est vérifiée par le back (401 → purge + redirect via interceptor).
