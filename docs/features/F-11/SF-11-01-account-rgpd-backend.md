# Mini-spec — [F-11 / SF-01] Compte & RGPD — export et suppression (backend)

## Identifiant

`F-11 / SF-01`

## Feature parente

`F-11` — Settings & compte (réglages compte, export/suppression des données — RGPD)

## Statut

`ready`

## Date de création

2026-07-01

## Branche Git

`feat/SF-11-01-account-backend`

---

## Objectif

> Donner à l'utilisateur le contrôle RGPD sur ses données : **exporter** l'intégralité de ses
> données personnelles (`GET /api/account/export`) et **supprimer définitivement** son compte et
> toutes les données qui lui sont rattachées (`DELETE /api/account`), dans le strict périmètre de
> son propre `user_id`.

---

## Comportement attendu

### Cas nominal — Export

1. `GET /api/account/export` avec JWT valide.
2. Le service agrège, **filtré sur le `user_id` du contexte de sécurité uniquement**, l'ensemble des
   données de l'utilisateur : compte, abonnement, compteurs d'usage, conversations + messages,
   métadonnées de fichiers téléversés.
3. Réponse `200` : un document JSON auto-portant (droit à la portabilité, art. 20 RGPD).
4. Aucune donnée interne sensible n'est exposée (hash de mot de passe, identifiants Stripe,
   identifiant de fichier chez le fournisseur, secrets).

### Cas nominal — Suppression

1. `DELETE /api/account` avec JWT valide.
2. Le service supprime, **dans une transaction**, toutes les données rattachées au `user_id`
   courant : messages, conversations, fichiers téléversés (métadonnées), compteurs d'usage,
   abonnement, jetons de vérification e-mail et de réinitialisation (cascade FK), puis le compte
   `users` lui-même.
3. Réponse `200 { "message": "..." }`.
4. Effet immédiat : tout JWT existant devient inopérant (le filtre JWT ne retrouve plus
   l'utilisateur → `401` sur les requêtes suivantes). Droit à l'effacement, art. 17 RGPD.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| `GET /api/account/export` sans JWT | Refus | 401 |
| `DELETE /api/account` sans JWT | Refus | 401 |
| JWT valide mais compte déjà supprimé (course) | Refus (utilisateur introuvable) | 401 |
| Erreur interne pendant la suppression | Rollback transactionnel, message métier neutre, aucune stacktrace | 500 |

---

## Critères d'acceptation

- [ ] `GET /api/account/export` renvoie `200` avec le compte, l'abonnement (ou `null`), les compteurs
      d'usage, les conversations avec leurs messages, et les métadonnées de fichiers de l'utilisateur **courant uniquement**.
- [ ] L'export **n'expose jamais** : `passwordHash`, `stripeCustomerId`, `stripeSubscriptionId`,
      `providerFileId`, ni aucun secret.
- [ ] `DELETE /api/account` supprime **toutes** les données rattachées au `user_id` (messages,
      conversations, fichiers, compteurs d'usage, abonnement, jetons) **et** la ligne `users`.
- [ ] Après suppression, une requête portant l'ancien JWT est refusée `401`.
- [ ] Isolation `user_id` : l'export et la suppression ne portent **que** sur les données de
      l'utilisateur du JWT ; les données d'un autre utilisateur ne sont ni lues ni supprimées.
- [ ] `GET`/`DELETE` sans JWT → `401`.
- [ ] La suppression est **transactionnelle** : en cas d'échec partiel, rien n'est supprimé.
- [ ] Aucun secret ni donnée sensible n'apparaît dans les logs ; aucune stacktrace dans les réponses.

---

## Périmètre

### Hors scope (explicite)

- **Gestion de la clé BYOK** : relève de **F-03 (BYOK, « À spécifier »)**, non livrée. Aucune table
  BYOK n'existe encore. Décision tracée : F-11 ne gère pas la clé BYOK (sera ajoutée à l'écran
  Settings quand F-03 sera livrée). Réversible.
- **Purge programmée « rétention 90 j »** (soft-delete + worker de purge planifié) : hors V1. V1 =
  **suppression immédiate et définitive** (choix plus protecteur pour la vie privée, art. 17). La
  rétention 90 j est documentée comme politique (logs/sauvegardes infra), sans planificateur applicatif.
  Décision tracée, réversible.
- **Suppression du fichier chez le fournisseur** (Anthropic Files API) : on ne stocke que des
  métadonnées ; la rétention côté fournisseur relève de sa politique. Best-effort hors périmètre V1.
- **Écran / UI** : traité en `SF-11-02` (frontend).
- **Édition du profil / e-mail, logout-all** : déjà livrés en F-01 (`/api/me`), non redéveloppés ici.

---

## Contraintes de validation

| Champ | Obligatoire | Format / Valeurs | Notes |
|-------|-------------|------------------|-------|
| `user_id` (export & suppression) | Oui | uuid | **jamais** un paramètre client — issu du `SecurityContext` |

Aucune nouvelle table, **aucune migration Liquibase** : la feature lit et supprime des données
existantes. Les cascades FK existantes (`email_verification_tokens`, `password_reset_tokens` →
`users` ; `messages` → `conversations`) sont complétées par des suppressions explicites par
`user_id` pour les tables sans FK vers `users` (`conversations`, `uploaded_files`,
`usage_counters`, `subscriptions`).

---

## Technique

### Endpoint(s)

| Méthode | URL | Auth | Rôle minimum |
|---------|-----|------|-------------|
| GET | `/api/account/export` | Oui | USER |
| DELETE | `/api/account` | Oui | USER |

Contrat `GET /api/account/export` (réponse 200) — **figé** (importé par SF-11-02 frontend) :

```json
{
  "exportedAt": "2026-07-01T12:00:00Z",
  "account": {
    "id": "uuid",
    "email": "user@example.com",
    "emailVerified": true,
    "provider": "LOCAL",
    "role": "USER",
    "createdAt": "2026-06-01T10:00:00Z"
  },
  "subscription": {
    "status": "TRIALING",
    "planCode": null,
    "trialEndsAt": "2026-07-15T10:00:00Z",
    "currentPeriodEnd": null
  },
  "usage": [
    { "periodStart": "2026-07-01", "inputTokens": 1200, "outputTokens": 800 }
  ],
  "conversations": [
    {
      "id": "uuid",
      "title": "Analyse de contrat",
      "model": "claude-sonnet-4-20250514",
      "createdAt": "2026-06-20T09:00:00Z",
      "messages": [
        { "role": "USER", "content": "Bonjour", "model": null, "createdAt": "2026-06-20T09:00:01Z" }
      ]
    }
  ],
  "uploadedFiles": [
    { "filename": "contrat.pdf", "mediaType": "application/pdf", "sizeBytes": 20480, "createdAt": "2026-06-20T09:05:00Z" }
  ]
}
```

`subscription` vaut `null` si l'utilisateur n'en a pas. `planCode`, `trialEndsAt`,
`currentPeriodEnd` peuvent être `null`.

Contrat `DELETE /api/account` (réponse 200) — **figé** :

```json
{ "message": "Votre compte et toutes vos données ont été supprimés." }
```

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| `users` | SELECT / DELETE | racine ; cascade FK vers jetons |
| `subscriptions` | SELECT / DELETE (by user_id) | pas de FK → suppression explicite |
| `usage_counters` | SELECT / DELETE (by user_id) | pas de FK → suppression explicite |
| `conversations` | SELECT / DELETE (by user_id) | pas de FK → suppression explicite (cascade DB vers `messages`) |
| `messages` | SELECT / DELETE (by user_id) | suppression explicite (belt-and-suspenders) |
| `uploaded_files` | SELECT / DELETE (by user_id) | pas de FK → suppression explicite |
| `email_verification_tokens`, `password_reset_tokens` | DELETE | cascade FK via `users` |

### Migration Liquibase

- [x] Non — aucune modification de schéma.

### Composants Angular

- Aucun (frontend en SF-11-02).

---

## Préoccupations transversales

| Préoccupation | Impacté ? | Composants |
|---------------|-----------|------------|
| **Auth / Principal** | Non modifié — réutilise `CurrentUser.requireId()`. La suppression s'appuie sur le comportement existant du filtre JWT (utilisateur introuvable → 401), non modifié. |
| **Contexte tenant** | Oui (lecture/suppression par `user_id`). Résolution inchangée via `CurrentUser`. Composants vérifiés : `AccountService` (nouveau) filtre exclusivement sur `currentUser.requireId()`. |
| **Plans / limites** | Non (lecture de `subscriptions`/`usage_counters` pour export, suppression à la demande — pas de nouveau gate). |
| **Navigation / routing** | Non (backend). |

---

## Plan de test

### Tests unitaires (`AccountServiceTest`)

- [ ] `export` agrège compte + abonnement + usage + conversations/messages + fichiers pour le bon `user_id`.
- [ ] `export` renvoie `subscription = null` quand l'utilisateur n'en a pas.
- [ ] `export` n'inclut aucun champ sensible (vérif sur le DTO : pas de passwordHash/stripe/providerFileId).
- [ ] `delete` invoque la suppression sur toutes les tables rattachées puis supprime le `users`.

### Tests d'intégration (`AccountApiIntegrationTest`)

- [ ] `GET /api/account/export` → 200, contenu conforme (conversation + message + fichier + usage seedés).
- [ ] `GET /api/account/export` → 401 sans JWT.
- [ ] `DELETE /api/account` → 200 ; le compte et toutes ses données ont disparu de la base.
- [ ] Après `DELETE`, l'ancien JWT → 401 sur `GET /api/account/export`.
- [ ] `DELETE /api/account` → 401 sans JWT.
- [ ] **Isolation** : la suppression du compte d'Alice ne touche pas les données de Bob ; l'export d'Alice ne contient pas les données de Bob.

### Isolation utilisateur

- [x] Applicable — tests d'isolation `user_id` sur export et suppression.

---

## Dépendances

### Subfeatures bloquantes

- `F-01` (users, jetons) — done.
- `F-02` (conversations, messages) — done.
- `F-04` (uploaded_files) — done.
- `F-09` (subscriptions) — done.
- `F-10` (usage_counters) — done.

### Questions ouvertes impactées

- Aucune (OQ inchangées). La sémantique « rétention 90 j » est tranchée par défaut (suppression
  immédiate) et tracée ci-dessus ; réversible.

---

## Notes et décisions

- **Suppression immédiate vs soft-delete 90 j** : V1 = hard-delete transactionnel immédiat. Plus
  simple, sans planificateur (traitement asynchrone évité), et plus protecteur (aucune PII résiduelle).
  Réversible : un mode « corbeille 90 j » pourra être ajouté ultérieurement.
- **BYOK hors périmètre** : reporté à F-03 pour éviter la dérive multi-features.
- **Provider-First / isolation** : aucune dépendance directe à Anthropic ; tout accès filtré `user_id`.
