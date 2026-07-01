# Mini-spec — [F-01 / SF-01-02] Inscription + connexion email/mot de passe

## Identifiant

`F-01 / SF-01-02`

## Feature parente

`F-01` — Authentification

## Statut

`in-progress`

## Date de création

2026-07-01

## Branche Git

`feat/SF-01-02-inscription-connexion-email`

---

## Objectif

Permettre à un visiteur de créer un compte local (email + mot de passe, hash BCrypt) et de se connecter en recevant un JWT plateforme émis par le `JwtService` du socle.

---

## Comportement attendu

### Cas nominal

- `POST /api/auth/register` : reçoit `{ email, password }`, normalise l'email (trim + minuscule), vérifie qu'aucun compte n'existe, crée un `User` (`provider=LOCAL`, `emailVerified=false`, `role=USER`, `passwordHash=BCrypt(password)`), renvoie `201` avec la vue publique du compte (jamais le hash).
- `POST /api/auth/login` : reçoit `{ email, password }`, retrouve le compte LOCAL par email, vérifie le mot de passe via BCrypt, émet un JWT (`JwtService.generateToken`) et renvoie `200` avec `{ accessToken, tokenType: "Bearer", user }`.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Email absent / format invalide | `validation_error` | 400 |
| Mot de passe absent / < 8 / > 72 caractères | `validation_error` | 400 |
| Email déjà utilisé (register) | `email_already_used` | 409 |
| Identifiants invalides (login : email inconnu ou mauvais mot de passe) | `invalid_credentials` (message générique, pas d'énumération) | 401 |
| Compte OAuth-only (passwordHash null) tentant un login local | `invalid_credentials` | 401 |

---

## Critères d'acceptation

- [ ] `POST /api/auth/register` avec payload valide → 201, compte créé `provider=LOCAL`, `emailVerified=false`, hash BCrypt en base, jamais renvoyé.
- [ ] `POST /api/auth/register` avec email déjà pris → 409 `email_already_used`.
- [ ] `POST /api/auth/register` email invalide ou mot de passe trop court → 400 `validation_error`.
- [ ] `POST /api/auth/login` bons identifiants → 200 + JWT valide (parsable par `JwtService`, `sub = user.id`).
- [ ] `POST /api/auth/login` mauvais mot de passe ou email inconnu → 401 `invalid_credentials` (même message dans les deux cas).
- [ ] Le mot de passe n'apparaît jamais en clair ni dans les logs ; seul le hash BCrypt est persisté.
- [ ] Endpoints `/auth/**` publics (déjà `permitAll` dans `SecurityConfig`).

---

## Périmètre

### Hors scope (explicite)

- Vérification d'email et envoi de mail (SF-01-03).
- Réinitialisation de mot de passe (SF-01-04).
- OAuth Google (SF-01-05).
- Écrans Angular (SF-01-07).
- Blocage du login tant que l'email n'est pas vérifié : non requis V1 (Simplicity First).

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format | Unicité | Normalisation |
|-------|-------------|-------------|--------|---------|---------------|
| email | Oui | 320 | RFC email (`@Email`) | Oui (table users) | trim + toLowerCase |
| password | Oui | 72 (limite BCrypt) | min 8 caractères | — | aucune (jamais stocké en clair) |

---

## Valeurs initiales

| Champ | Valeur initiale | Règle |
|-------|----------------|-------|
| provider | LOCAL | toujours à la création via register |
| emailVerified | false | tant que SF-01-03 n'a pas validé |
| role | USER | rôle par défaut |
| passwordHash | BCrypt(password) | jamais null pour un compte LOCAL |

---

## Technique

### Endpoints

| Méthode | URL | Auth | Rôle |
|---------|-----|------|------|
| POST | `/api/auth/register` | Non (public) | — |
| POST | `/api/auth/login` | Non (public) | — |

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| users | SELECT / INSERT | table existante (001) — **aucune migration** |

### Migration Liquibase

- [x] Non applicable (table `users` déjà créée en 001).

### Composants backend

- `auth/AuthService` — logique register/login (encodage BCrypt, unicité, émission JWT).
- `auth/AuthController` — endpoints `/auth/register`, `/auth/login`.
- `auth/dto/RegisterRequest`, `LoginRequest`, `AuthResponse`, `RegisteredUserResponse`.
- `auth/EmailAlreadyUsedException`, `auth/InvalidCredentialsException`.
- `user/UserService` — ajout `emailExists`, `createLocalUser`.
- `auth/SecurityConfig` — ajout bean `PasswordEncoder` (BCrypt).
- `shared/error/GlobalExceptionHandler` — mapping 400 (validation), 409, 401.

---

## Plan de test

### Tests unitaires

- [ ] `AuthServiceTest` — register nominal (encode + create), register email déjà pris → exception, login bon mot de passe → token, login mauvais mot de passe → exception, login email inconnu → exception, login compte sans hash → exception.

### Tests d'intégration

- [ ] `POST /api/auth/register` → 201 payload valide.
- [ ] `POST /api/auth/register` doublon → 409.
- [ ] `POST /api/auth/register` email invalide / mot de passe court → 400.
- [ ] `POST /api/auth/login` → 200 + token exploitable sur `GET /api/me`.
- [ ] `POST /api/auth/login` mauvais mot de passe → 401.

### Isolation utilisateur

- [x] Non applicable directement (pas de lecture de données tierces) — mais le token émis porte `sub = user.id`, base de l'isolation vérifiée en SF-01-01. Un login ne renvoie que le compte authentifié.

---

## Dépendances

### Subfeatures bloquantes

- `SF-01-01` — Done (socle JWT/User/SecurityConfig).

### Questions ouvertes impactées

- OQ-05 (mode d'auth) — tranchée : OAuth + email/mot de passe via JWT.

---

## Notes et décisions

- **Register ne renvoie pas de JWT** : le compte est créé mais l'email non vérifié ; le client enchaîne avec `login`. Décision Simplicity First, réversible.
- **Message d'erreur login générique** (`invalid_credentials` identique pour email inconnu et mauvais mot de passe) : évite l'énumération de comptes.
- **Cap mot de passe à 72 caractères** : limite intrinsèque de BCrypt (au-delà, octets ignorés silencieusement) → validé en amont pour un comportement prévisible.
