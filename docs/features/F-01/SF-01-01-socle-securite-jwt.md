# SF-01-01 — Socle sécurité : User, Spring Security stateless, JWT, isolation user_id

> Feature parente : **F-01 Authentification** · Statut : À développer · Type : socle transversal (pose les patterns V1)

## Objectif (une phrase)
Poser le socle d'authentification de la plateforme : une entité `User`, sa persistance (Liquibase), une configuration Spring Security **stateless** validant un **JWT Bearer**, la résolution du **`CurrentUser`** (isolation `user_id`), et un endpoint protégé `GET /api/me` de validation — sans encore d'écran ni de flux d'inscription (SF-01-02+).

## Comportement nominal
- Un JWT valide (signé HS256 avec le secret plateforme, non expiré) présenté en `Authorization: Bearer <token>` authentifie la requête ; le `userId` est extrait du claim `sub` et exposé via un `CurrentUser` injectable dans les services.
- `GET /api/me` retourne `200` avec `{ id, email, emailVerified, provider, role }` de l'utilisateur courant.
- Les endpoints publics restent accessibles sans token : `/api/actuator/health`, `/api/auth/**` (réservé aux SF suivantes).
- Tout autre endpoint `/api/**` exige un JWT valide.

## Cas d'erreur
- Absence de header `Authorization` sur un endpoint protégé → **401** (corps JSON `{ error, message }`, pas de stacktrace).
- JWT malformé / signature invalide / expiré → **401**.
- JWT valide mais `userId` inconnu en base → **401** (utilisateur supprimé).
- Secret JWT absent de la config au démarrage → **échec de démarrage explicite** (fail-fast), jamais de secret par défaut en dur.

## Critères d'acceptation (vérifiables)
1. `GET /api/me` sans token → 401 ; avec un JWT valide émis pour un `User` seedé → 200 + payload attendu.
2. Un JWT expiré ou de signature invalide → 401.
3. Le secret JWT provient de la config (`APP_JWT_SECRET` / `application.yml`), jamais commité ; démarrage impossible si absent.
4. `CurrentUser` fournit le `userId` du token ; un service peut filtrer sur `user_id` à partir de lui.
5. La table `users` est créée par une migration **Liquibase** (changeset Postgres), `ddl-auto: validate`.
6. Aucun mot de passe/secret/JWT en clair dans les logs.
7. `mvn -pl backend test` vert ; couverture des cas ci-dessus.

## Tables / endpoints / composants impactés
- **Table `users`** (nouvelle) : `id UUID PK`, `email VARCHAR UNIQUE NOT NULL`, `password_hash VARCHAR NULL` (null si OAuth-only), `email_verified BOOLEAN NOT NULL DEFAULT false`, `provider VARCHAR NOT NULL` (`LOCAL`/`GOOGLE`), `role VARCHAR NOT NULL DEFAULT 'USER'`, `created_at`, `updated_at`.
- **Migration** : `backend/src/main/resources/db/changelog/migrations/001-users.xml` (changeset `dbms="postgresql"`), référencée par le changelog maître.
- **Endpoints** : `GET /api/me` (protégé).
- **Backend (`fr.claudegateway`)** :
  - `user/` : `User` (entity), `UserRepository`, `UserService`.
  - `auth/` : `SecurityConfig` (SecurityFilterChain stateless), `JwtService` (issue/parse/validate), `JwtAuthenticationFilter`, `CurrentUser` (+ résolution depuis le SecurityContext), `MeController`.
  - `shared/` : `GlobalExceptionHandler` (401/erreurs JSON homogènes), DTO d'erreur.
- **Config** : `APP_JWT_SECRET` (env, déjà à câbler dans le secret K8s `backend-secrets`), `APP_JWT_EXPIRATION`.

## Plan de test minimal
- **Unitaires** : `JwtService` (émission → parsing round-trip ; rejet expiré ; rejet signature altérée). `CurrentUser` extraction.
- **Intégration** (`@SpringBootTest` + MockMvc, profil `dev`/H2) : `/api/me` 401 sans token ; 200 avec token valide (User seedé) ; 401 token expiré ; 401 userId inconnu.
- **Isolation utilisateur** : test qu'un service utilisant `CurrentUser` ne renvoie que les données du `user_id` courant (pattern posé ici pour toutes les SF suivantes).

## Hors périmètre (SF suivantes)
- Inscription / connexion email-mot de passe (SF-01-02), vérification email (SF-01-03), reset (SF-01-04), OAuth Google (SF-01-05), édition profil / logout global (SF-01-06), écrans Angular (SF-01-07).
- Refresh tokens, rotation de secret : non requis V1 (JWT court + re-login).

## Décisions techniques (arbitrages posés)
- **JWT HS256** (secret symétrique plateforme) plutôt que RS256 : plus simple, suffisant V1 (`PROJECT.md` Simplicity First). Réversible.
- **Stateless** (pas de session serveur) : aligné cloud-native / scaling horizontal (`ARCHITECTURE.md` Stateless Services).
- **`provider` sur `User`** dès le socle pour accueillir LOCAL + GOOGLE sans migration ultérieure.
