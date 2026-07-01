# Mini-spec — [F-01 / SF-01-04] Réinitialisation de mot de passe

## Identifiant

`F-01 / SF-01-04`

## Feature parente

`F-01` — Authentification

## Statut

`in-progress`

## Date de création

2026-07-01

## Branche Git

`feat/SF-01-04-reset-mot-de-passe`

---

## Objectif

Permettre à un utilisateur ayant oublié son mot de passe d'en définir un nouveau via un token à usage unique reçu par e-mail.

---

## Comportement attendu

### Cas nominal

- `POST /api/auth/password/forgot` `{ email }` : si un compte LOCAL (avec mot de passe) existe pour cet email, génère un token (expirant à 1h), le persiste (`password_reset_tokens`) et envoie le lien `${app.frontend-url}/auth/reset?token=...` via l'`EmailService`. **Réponse 200 générique dans tous les cas** (existant ou non).
- `POST /api/auth/password/reset` `{ token, password }` : si le token est valide (non expiré, non consommé), remplace le hash BCrypt du compte par celui du nouveau mot de passe et consomme le token. Réponse `200`.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Email absent / invalide (forgot) | `validation_error` | 400 |
| Email inconnu ou compte OAuth-only (forgot) | **200 générique** (pas de fuite d'existence, aucun mail) | 200 |
| Token / mot de passe absent ou trop court (reset) | `validation_error` | 400 |
| Token inconnu / expiré / déjà consommé (reset) | `invalid_token` | 400 |

---

## Critères d'acceptation

- [ ] `forgot` sur un compte LOCAL existant → 200, un token est créé et l'e-mail (stub) est envoyé.
- [ ] `forgot` sur un email inconnu → 200 identique, **aucun token créé, aucun mail** (anti-énumération).
- [ ] `reset` avec token valide + nouveau mot de passe → 200, le hash change, `used_at` renseigné, login avec le nouveau mot de passe fonctionne, l'ancien échoue.
- [ ] `reset` avec token inconnu / expiré / consommé → 400 `invalid_token`.
- [ ] `reset` mot de passe < 8 caractères → 400 `validation_error`.
- [ ] Aucun mot de passe en clair persisté ou journalisé.

---

## Périmètre

### Hors scope (explicite)

- Reset pour un compte OAuth-only (provider GOOGLE sans mot de passe) : `forgot` est un no-op silencieux (200, pas de mail). Décision : ne pas convertir silencieusement un compte OAuth en compte local.
- Invalidation des sessions/JWT existants après reset (traité par le `token_version` de SF-01-06).
- Écrans Angular (SF-01-07).

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format | Unicité | Normalisation |
|-------|-------------|-------------|--------|---------|---------------|
| email (forgot) | Oui | 320 | `@Email` | — | trim + toLowerCase |
| token (reset) | Oui | 255 | opaque | Oui (table) | — |
| password (reset) | Oui | 72 | min 8 | — | jamais stocké en clair |

---

## Valeurs initiales

| Champ | Valeur initiale | Règle |
|-------|----------------|-------|
| expires_at | now + 1h | TTL configurable `app.password-reset.token-ttl` |
| used_at | null | renseigné à la consommation |

---

## Technique

### Endpoints

| Méthode | URL | Auth | Rôle |
|---------|-----|------|------|
| POST | `/api/auth/password/forgot` | Non (public) | — |
| POST | `/api/auth/password/reset` | Non (public) | — |

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| password_reset_tokens | INSERT / SELECT / UPDATE | **nouvelle** (migration 003) |
| users | UPDATE (`password_hash`) | via `UserService.updatePassword` |

### Migration Liquibase

- [x] Oui — `003-password-reset-tokens.xml` (changesets `postgresql` + `h2`).

### Composants backend

- `auth/PasswordResetToken` (entity), `auth/PasswordResetTokenRepository`.
- `auth/PasswordResetService` (forgot + reset).
- `auth/InvalidPasswordResetTokenException` → 400 `invalid_token`.
- `auth/AuthController` — ajout `POST /auth/password/forgot`, `POST /auth/password/reset`.
- `auth/dto/ForgotPasswordRequest`, `ResetPasswordRequest`, `MessageResponse`.
- `user/UserService.updatePassword`.
- `email/EmailService.sendPasswordReset` (déjà déclaré en SF-01-03, câblé ici).

---

## Plan de test

### Tests unitaires

- [ ] `PasswordResetServiceTest` — forgot compte LOCAL → token + mail ; forgot email inconnu → no-op ; forgot compte OAuth → no-op ; reset valide → hash mis à jour + token consommé ; reset inconnu/expiré/consommé → exception.

### Tests d'intégration

- [ ] `forgot` compte existant → 200 + token en base.
- [ ] `forgot` email inconnu → 200 + aucun token.
- [ ] `reset` valide → 200, login nouveau mot de passe OK, ancien KO.
- [ ] `reset` token invalide → 400 `invalid_token`.

### Isolation utilisateur

- [x] Le token porte le `user_id` cible ; le reset n'affecte que ce compte.

---

## Dépendances

### Subfeatures bloquantes

- `SF-01-01` (socle), `SF-01-02` (login/BCrypt), `SF-01-03` (EmailService) — Done.

### Questions ouvertes impactées

- Aucune.

---

## Notes et décisions

- **Anti-énumération** : `forgot` renvoie toujours 200, qu'un compte existe ou non.
- **TTL court (1h)** pour un token de reset (plus sensible qu'une vérification d'e-mail).
- **Comptes OAuth-only** : `forgot` no-op — pas de conversion implicite en compte local.
- **Réutilisation** : `SecureTokenGenerator` (SF-01-03) et `EmailService.sendPasswordReset`.
