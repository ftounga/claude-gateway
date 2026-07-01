# Mini-spec — [F-01 / SF-01-05] Connexion OAuth2/OIDC Google

## Identifiant

`F-01 / SF-01-05`

## Feature parente

`F-01` — Authentification

## Statut

`in-progress`

## Date de création

2026-07-01

## Branche Git

`feat/SF-01-05-oauth-google`

---

## Objectif

Permettre la connexion via Google (OAuth2/OIDC), en fédérant l'identité Google vers un `User` de la plateforme et en émettant un **JWT plateforme** (même `JwtService`), sans casser la chaîne d'authentification stateless existante.

---

## Comportement attendu

### Cas nominal

- L'utilisateur est redirigé vers `GET /api/oauth2/authorization/google` (bouton « Se connecter avec Google »).
- Après consentement Google, le callback `GET /api/login/oauth2/code/google` est traité par Spring Security OAuth2 Client.
- L'`OAuth2LoginSuccessHandler` récupère l'e-mail OIDC, appelle `UserService.findOrCreateGoogleUser` (crée le compte si absent : `provider=GOOGLE`, `emailVerified=true`), émet un JWT via `JwtService`, puis **redirige** vers `${app.frontend-url}/auth/callback#token=<jwt>` (le token est passé en fragment d'URL, non journalisé côté serveur).
- La SPA lit le token dans le fragment et le stocke.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Google non configuré (client-id absent) | OAuth **dormant** : aucun bean OAuth, le démarrage réussit, `/oauth2/**` n'existe pas | — (démarrage OK) |
| E-mail absent du profil OIDC | Redirection `.../auth/callback#error=email_unavailable` | 302 |
| Échec OAuth (consentement refusé) | Comportement par défaut Spring (redirection d'erreur) | 302 |

---

## Critères d'acceptation

- [ ] Sans `GOOGLE_CLIENT_ID`, l'application **démarre normalement** (OAuth dormant) et les tests existants passent inchangés.
- [ ] Avec `GOOGLE_CLIENT_ID`/`SECRET`, `GET /api/oauth2/authorization/google` → 302 vers `accounts.google.com`.
- [ ] Le succès OAuth fédère vers un `User` (créé si absent, `provider=GOOGLE`, `emailVerified=true`) et émet un JWT plateforme via `JwtService`.
- [ ] La chaîne stateless JWT existante est préservée : `GET /api/me` sans token → **401 JSON** même quand OAuth est activé (non-régression du `RestAuthenticationEntryPoint`).
- [ ] `GOOGLE_CLIENT_ID`/`SECRET` proviennent de la config (`${...:}`), jamais en dur ni journalisés.

---

## Périmètre

### Hors scope (explicite)

- Autres fournisseurs (GitHub, Microsoft…) : V1 = Google uniquement, mais via l'abstraction Spring OAuth2 Client (extensible).
- Écran de login/bouton Google (SF-01-07).
- Liaison manuelle de comptes / dé-liaison : la fédération se fait par e-mail (voir décisions).

---

## Préoccupation transversale — Auth / Principal (analyse d'impact)

Ajout d'un **nouveau mode d'authentification** (OAuth2 login). Composants d'auth impactés et vérifiés :

| Composant | Impact | Vérification |
|-----------|--------|--------------|
| `SecurityConfig` | Ajout conditionnel de `oauth2Login` + matchers publics `/oauth2/**`, `/login/**` | Chaîne inchangée si Google non configuré ; entry point 401 conservé |
| `RestAuthenticationEntryPoint` | Doit rester l'entry point même sous oauth2Login | Test non-régression : `/api/me` sans token → 401 avec Google activé |
| `JwtAuthenticationFilter` | Inchangé — le JWT émis pour un compte GOOGLE est identique (claims `sub/email/role`) | Réutilise `JwtService.generateToken` |
| `CurrentUser` / isolation `user_id` | Inchangé — un compte GOOGLE a un `users.id` comme n'importe quel compte | `findOrCreateGoogleUser` renvoie un `User` persisté |
| `SessionCreationPolicy.STATELESS` | Le handshake OAuth stocke l'`authorization request` dans une `HttpSession` transitoire (créée par le conteneur), l'auth API reste stateless (aucun `SecurityContext` en session) | Redirection 302 vérifiée |

---

## Technique

### Endpoints (fournis par Spring Security OAuth2 Client)

| Méthode | URL | Auth | Rôle |
|---------|-----|------|------|
| GET | `/api/oauth2/authorization/google` | Non (public) | — |
| GET | `/api/login/oauth2/code/google` | Non (public, callback) | — |

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| users | SELECT / INSERT | **aucune migration** (colonnes `provider`/`email_verified` existent depuis 001) |

### Migration Liquibase

- [x] Non applicable.

### Composants backend

- `pom.xml` — dépendance `spring-boot-starter-oauth2-client`.
- `auth/OAuth2ClientConfig` (`@ConditionalOnProperty app.oauth2.google.client-id`) — `ClientRegistrationRepository` (Google via `CommonOAuth2Provider`) + `OAuth2LoginSuccessHandler` (bean).
- `auth/OAuth2LoginSuccessHandler` — fédération + émission JWT + redirection.
- `auth/SecurityConfig` — matchers publics + activation conditionnelle d'`oauth2Login` (via `ObjectProvider`).
- `user/UserService.findOrCreateGoogleUser`.
- `application.yml` — `app.oauth2.google.client-id/secret` (`${...:}`, dormant si absent).

---

## Plan de test

### Tests unitaires

- [ ] `OAuth2LoginSuccessHandlerTest` — fédère (crée/retrouve) un `User` GOOGLE, émet un JWT, redirige vers `frontend-url/auth/callback#token=...` ; e-mail absent → redirection d'erreur.
- [ ] `UserServiceGoogleTest` (ou intégration) — `findOrCreateGoogleUser` crée un compte absent (`GOOGLE`, `emailVerified=true`) et retrouve un compte existant.

### Tests d'intégration

- [ ] Sans Google configuré : contexte démarre, aucun `ClientRegistrationRepository` (couvert par la suite existante inchangée).
- [ ] Avec Google configuré (`@SpringBootTest(properties=...)`) : `GET /api/oauth2/authorization/google` → 302 vers `accounts.google.com` ; `GET /api/me` sans token → 401 (non-régression).

### Isolation utilisateur

- [x] Un compte GOOGLE possède un `users.id` isolé comme tout autre compte ; le JWT porte `sub = users.id`.

---

## Dépendances

### Subfeatures bloquantes

- `SF-01-01` (socle JWT/SecurityConfig) — Done.

### Questions ouvertes impactées

- OQ-05 (mode d'auth) — tranchée.

---

## Notes et décisions

- **Fédération par e-mail** : si un compte existe déjà pour l'e-mail Google (ex. compte LOCAL), la connexion Google **réutilise** ce compte (pas de doublon). Décision assumée V1 : l'e-mail est l'identité pivot. Réversible (on pourrait exiger une liaison explicite plus tard).
- **JWT passé en fragment d'URL** (`#token=`) plutôt qu'en query (`?token=`) : le fragment n'est pas envoyé au serveur ni journalisé dans les access logs / `Referer`. La SPA le lit côté client.
- **Config dormante** : `app.oauth2.google.*` via `${GOOGLE_CLIENT_ID:}` — un client-id vide n'active pas le bean (`@ConditionalOnProperty`), donc pas d'échec de démarrage. On n'utilise pas `spring.security.oauth2.client.registration.google.*` directement pour éviter l'échec de démarrage sur client-id vide.
- **Stateless préservé** : politique `STATELESS` conservée ; seule une `HttpSession` transitoire du conteneur porte l'`authorization request` le temps du handshake.
