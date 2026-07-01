# Mini-spec — [F-01 / SF-01-06] Profil utilisateur + déconnexion

## Identifiant

`F-01 / SF-01-06`

## Feature parente

`F-01` — Authentification

## Statut

`in-progress`

## Date de création

2026-07-01

## Branche Git

`feat/SF-01-06-profil-logout`

---

## Objectif

Permettre à l'utilisateur de consulter/modifier son profil (`GET`/`PUT /api/me`) et de se déconnecter, y compris de **toutes** ses sessions via une invalidation par `token_version`.

---

## Comportement attendu

### Cas nominal

- `GET /api/me` : inchangé (socle) — renvoie le compte courant.
- `PUT /api/me` `{ email }` : si l'e-mail change, vérifie l'unicité, met à jour `users.email`, repasse `email_verified=false` et **déclenche un nouvel e-mail de vérification** (SF-01-03). Si l'e-mail est identique, no-op. Renvoie le profil à jour (200).
- `POST /api/me/logout` : déconnexion de la session courante. JWT stateless → **le client supprime son token** ; le serveur renvoie 200 (aucun état serveur).
- `POST /api/me/logout-all` : incrémente `users.token_version`. Tous les JWT émis précédemment (portant l'ancien `tv`) deviennent invalides à la requête suivante. Renvoie 200.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| `PUT`/`POST` sans JWT valide | `unauthorized` | 401 |
| `PUT /api/me` e-mail invalide | `validation_error` | 400 |
| `PUT /api/me` e-mail déjà utilisé par un autre compte | `email_already_used` | 409 |
| JWT avec `tv` ≠ `users.token_version` (après logout-all) | `unauthorized` | 401 |

---

## Critères d'acceptation

- [ ] `PUT /api/me` avec un nouvel e-mail valide → 200, `email` mis à jour, `email_verified=false`, un token de vérification est créé.
- [ ] `PUT /api/me` avec e-mail déjà pris par un autre compte → 409.
- [ ] `PUT /api/me` avec e-mail invalide → 400.
- [ ] `POST /api/me/logout` → 200 (le token reste techniquement valide jusqu'à expiration ; c'est le client qui l'oublie).
- [ ] `POST /api/me/logout-all` → 200 puis l'ancien token → 401 ; un nouveau login redonne un token valide.
- [ ] Non-régression : `GET /api/me` avec un token courant → 200 (le `tv` du token correspond).
- [ ] Migration `004-user-token-version.xml` ajoute `token_version` (défaut 0) ; `ddl-auto: validate` OK.

---

## Périmètre

### Hors scope (explicite)

- Suppression de compte, changement de mot de passe depuis le profil (reset existe déjà en SF-01-04).
- Champs de profil additionnels (nom, avatar…) : hors V1 (Simplicity First) — seul l'e-mail est éditable.
- Écrans Angular (SF-01-07).

---

## Préoccupation transversale — Auth / Principal (analyse d'impact)

Ajout d'un `token_version` vérifié **dans le filtre JWT** → impacte l'authentification de **tous** les endpoints protégés.

| Composant | Impact | Vérification |
|-----------|--------|--------------|
| `JwtService` | Ajoute le claim `tv` (token_version) à l'émission | Test unitaire `tv` présent |
| `JwtAuthenticationFilter` | Rejette le token si `tv` ≠ `users.token_version` (null traité comme 0, rétro-compatible) | Test intégration : token invalidé après logout-all → 401 |
| `User` (entity) + migration 004 | Nouvelle colonne `token_version` | `ddl-auto: validate` |
| Endpoints protégés actuels : `GET /api/me` (+ nouveaux `PUT /api/me`, `POST /api/me/logout`, `/logout-all`) | Tous passent par le filtre → tous soumis à la vérif `tv` | Non-régression : `GET /api/me` avec token courant → 200 |
| Tokens émis par l'auth locale ET Google | Portent tous `tv` (via `JwtService.generateToken`) | Couvert par la suite auth existante (tokens régénérés incluent `tv=0`) |

---

## Valeurs initiales

| Champ | Valeur initiale | Règle |
|-------|----------------|-------|
| token_version | 0 | à la création du compte ; incrémenté à chaque logout-all |

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format | Unicité | Normalisation |
|-------|-------------|-------------|--------|---------|---------------|
| email (PUT) | Oui | 320 | `@Email` | Oui (autre compte) | trim + toLowerCase |

---

## Technique

### Endpoints

| Méthode | URL | Auth | Rôle |
|---------|-----|------|------|
| GET | `/api/me` | Oui | USER |
| PUT | `/api/me` | Oui | USER |
| POST | `/api/me/logout` | Oui | USER |
| POST | `/api/me/logout-all` | Oui | USER |

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| users | UPDATE (`email`, `email_verified`, `token_version`) | migration 004 ajoute `token_version` |
| email_verification_tokens | INSERT | via re-vérification au changement d'e-mail |

### Migration Liquibase

- [x] Oui — `004-user-token-version.xml` (changesets `postgresql` + `h2`), `addColumn token_version int default 0 not null`.

### Composants backend

- `user/User` — champ `tokenVersion`.
- `auth/JwtService` — claim `tv`.
- `auth/JwtAuthenticationFilter` — vérif `tv`.
- `auth/ProfileService` — update e-mail (+ re-vérif) et logout-all.
- `auth/MeController` — `PUT /me`, `POST /me/logout`, `POST /me/logout-all`.
- `auth/dto/UpdateProfileRequest`.
- `user/UserService` — `updateEmail`, `incrementTokenVersion`.

---

## Plan de test

### Tests unitaires

- [ ] `ProfileServiceTest` — updateEmail change + re-vérif ; e-mail identique → no-op ; e-mail pris → exception ; logoutAll → increment.
- [ ] `JwtServiceTest` — le token émis porte le claim `tv`.

### Tests d'intégration

- [ ] `PUT /api/me` nouvel e-mail → 200 + `email_verified=false` + token de vérif créé.
- [ ] `PUT /api/me` e-mail pris → 409 ; e-mail invalide → 400.
- [ ] `POST /api/me/logout` → 200.
- [ ] `POST /api/me/logout-all` → 200, ancien token → 401, re-login → token valide.
- [ ] `GET /api/me` avec token courant → 200 (non-régression `tv`).

### Isolation utilisateur

- [x] Toutes les opérations passent par `CurrentUser.requireId()` : un utilisateur ne modifie/déconnecte que son propre compte.

---

## Dépendances

### Subfeatures bloquantes

- `SF-01-01` (socle), `SF-01-02` (login), `SF-01-03` (vérif e-mail) — Done.

### Questions ouvertes impactées

- Aucune.

---

## Notes et décisions

- **Logout-all par `token_version`** (arbitrage) : approche stateless simple et documentée. Un entier `token_version` sur `users`, injecté comme claim `tv` dans le JWT et comparé dans le filtre. Incrémenter invalide tous les tokens antérieurs, sans liste noire ni store de tokens. Alternative (blacklist de JTI) écartée : plus lourde, nécessite un store — non justifié V1 (Simplicity First).
- **Logout simple = côté client** : un JWT stateless ne peut pas être « révoqué » individuellement sans store ; `POST /api/me/logout` renvoie 200 et le client oublie le token. Documenté comme tel.
- **Rétro-compatibilité `tv`** : un token sans claim `tv` est traité comme `tv=0` (comptes au `token_version` initial).
- **Changement d'e-mail** : repasse `email_verified=false` et renvoie un e-mail de vérification (réutilise SF-01-03).
