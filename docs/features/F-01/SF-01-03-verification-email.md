# Mini-spec — [F-01 / SF-01-03] Vérification d'email

## Identifiant

`F-01 / SF-01-03`

## Feature parente

`F-01` — Authentification

## Statut

`in-progress`

## Date de création

2026-07-01

## Branche Git

`feat/SF-01-03-verification-email`

---

## Objectif

À l'inscription, générer un token de vérification à usage unique et l'envoyer (via un `EmailService`), puis valider l'email d'un compte via `GET /api/auth/verify?token=...`.

---

## Comportement attendu

### Cas nominal

- À la fin de `register` (SF-01-02), l'`AuthService` demande à l'`EmailVerificationService` de créer un token (aléatoire, expirant à 24h) rattaché au `user_id`, de le persister (`email_verification_tokens`) et d'envoyer le lien de vérification `${app.frontend-url}/auth/verify?token=...` via l'`EmailService`.
- `GET /api/auth/verify?token=...` : si le token existe, n'est ni expiré ni déjà consommé → `users.email_verified = true`, le token est marqué consommé (`used_at`), réponse `200 { verified: true, email }`.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Token absent (paramètre manquant) | `validation_error` | 400 |
| Token inconnu | `invalid_token` | 400 |
| Token expiré | `invalid_token` | 400 |
| Token déjà consommé (double-clic) | `invalid_token` | 400 |

---

## Critères d'acceptation

- [ ] `register` crée une ligne dans `email_verification_tokens` rattachée au `user_id` du nouveau compte et déclenche l'envoi (EmailService).
- [ ] `GET /api/auth/verify?token=<valide>` → 200, `users.email_verified` passe à `true`, `used_at` renseigné.
- [ ] Un token inconnu / expiré / déjà consommé → 400 `invalid_token`.
- [ ] Le token n'est jamais renvoyé par une autre API ni journalisé hors du stub e-mail de dev (voir décisions).
- [ ] L'`EmailService` par défaut n'envoie pas de SMTP réel : il journalise l'action (stub dev) ; le vrai SMTP se branchera via config `MAIL_*` plus tard.
- [ ] Table créée par migration Liquibase `002-email-verification-tokens.xml` (changesets postgresql + h2).

---

## Périmètre

### Hors scope (explicite)

- Blocage du login tant que l'email n'est pas vérifié (non requis V1).
- Renvoi d'un nouvel email de vérification à la demande (re-send) — hors périmètre (le changement d'email en SF-01-06 régénère un token).
- SMTP réel (branché plus tard via `MAIL_*`).

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format | Unicité | Normalisation |
|-------|-------------|-------------|--------|---------|---------------|
| token (query param) | Oui | 255 | opaque (Base64 URL, 32 octets aléatoires) | Oui (table) | — |

---

## Valeurs initiales

| Champ | Valeur initiale | Règle |
|-------|----------------|-------|
| expires_at | now + 24h | TTL configurable `app.verification.token-ttl` |
| used_at | null | renseigné à la consommation |

---

## Technique

### Endpoints

| Méthode | URL | Auth | Rôle |
|---------|-----|------|------|
| GET | `/api/auth/verify?token=...` | Non (public) | — |

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| email_verification_tokens | INSERT / SELECT / UPDATE | **nouvelle** (migration 002) |
| users | UPDATE (`email_verified`) | via `UserService.markEmailVerified` |

### Migration Liquibase

- [x] Oui — `002-email-verification-tokens.xml` (changesets `postgresql` + `h2`).

### Composants backend

- `email/EmailService` (interface), `email/LoggingEmailService` (impl dev par défaut).
- `auth/EmailVerificationToken` (entity), `auth/EmailVerificationTokenRepository`.
- `auth/EmailVerificationService` (création + envoi + validation).
- `auth/InvalidVerificationTokenException` → 400 `invalid_token`.
- `auth/AuthController` — ajout `GET /auth/verify`.
- `auth/dto/VerifyEmailResponse`.
- `auth/AuthService.register` — déclenche `createAndSend`.
- `user/UserService.markEmailVerified`.

---

## Plan de test

### Tests unitaires

- [ ] `EmailVerificationServiceTest` — createAndSend persiste un token + appelle EmailService avec un lien contenant le token ; verify nominal marque vérifié + consommé ; verify inconnu/expiré/consommé → exception.

### Tests d'intégration

- [ ] `register` crée un token en base pour le nouvel utilisateur.
- [ ] `GET /api/auth/verify?token=<réel>` → 200 + `email_verified=true` en base.
- [ ] `GET /api/auth/verify?token=bogus` → 400 `invalid_token`.

### Isolation utilisateur

- [x] Le token porte le `user_id` cible ; la vérification n'affecte que ce compte. Pas de lecture de données tierces.

---

## Dépendances

### Subfeatures bloquantes

- `SF-01-01` (socle), `SF-01-02` (register) — Done.

### Questions ouvertes impactées

- Aucune.

---

## Notes et décisions

- **Journalisation du lien dans le stub e-mail (arbitrage)** : le lien de vérification embarque un token à usage unique et court (24h). L'impl par défaut `LoggingEmailService` **journalise ce lien** au niveau INFO — c'est le fallback dev explicitement demandé (aucun SMTP en V1). Le marqueur `[EMAIL:DEV-STUB]` signale que ce comportement est réservé au dev ; l'impl SMTP de production enverra l'email et ne journalisera pas le lien. Aucun autre secret (mot de passe, hash) n'est jamais journalisé.
- **Token opaque** : 32 octets `SecureRandom` encodés en Base64 URL sans padding.
- **Double-clic** : un token consommé renvoie 400 (`invalid_token`) — la page frontend affiche « lien déjà utilisé ou invalide ». Simplicity First.
