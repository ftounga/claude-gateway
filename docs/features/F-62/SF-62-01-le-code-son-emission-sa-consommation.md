# Mini-spec — F-62 / SF-62-01 — Le code d'accès : le générer, le consommer, le laisser expirer

## Identifiant

`F-62 / SF-62-01`

## Feature parente

`F-62` — Codes d'accès à durée limitée

## Statut

`ready`

## Date de création

2026-09-10

## Branche Git

`feat/SF-62-01-code-acces-24h`

---

## Objectif

Un **ADMIN** émet un code à usage unique et daté ; celui à qui il le remet le saisit et obtient le
**droit d'accès à la Forge pendant 24 h**, qui se referme tout seul au terme — sans qu'aucun job ne
tourne, sans que rien ne soit écrit dans son abonnement, sans qu'un tour en cours soit coupé.

---

## Comportement attendu

### Cas nominal — l'émission

1. `POST /admin/access-codes` avec `{ label, assignedEmail? }`, appelant **ADMIN**
   (`AdminService.assertAdmin()` — la garde unique du produit, F-20).
2. Le serveur tire un code aléatoire (`SecureRandom`) de forme `FORGE-XXXX-XXXX`, alphabet de 32
   caractères sans ambiguïté visuelle (ni `I`, `O`, `0`, `1`).
3. Il **n'enregistre pas le code en clair** : seul son SHA-256 est stocké. Le code en clair est
   renvoyé **une seule fois**, dans la réponse de création.
4. La ligne fige, au moment de l'émission : le plan offert (`GOLD`), la durée du droit
   (`duration_hours`, issue de la configuration), et la date au-delà de laquelle le code **non
   consommé** ne vaut plus rien (`valid_until`).

### Cas nominal — la consommation

1. `POST /access-code/redeem` avec `{ code }`, appelant **authentifié quelconque**.
2. Le serveur normalise (trim, majuscules), hashe, retrouve la ligne.
3. Il vérifie, dans cet ordre : code connu · pas déjà consommé · `valid_until` non dépassé · si le
   code est **nominatif**, l'e-mail visé est bien celui de l'appelant · l'appelant **n'a pas déjà**
   un droit en cours (pas de cumul).
4. Il consomme la ligne par un **UPDATE conditionnel** (`… WHERE id = ? AND redeemed_at IS NULL`) :
   c'est ce qui rend l'usage unique vrai même si deux requêtes arrivent en même temps. Zéro ligne
   modifiée ⇒ le code vient d'être consommé ailleurs ⇒ 409.
5. Il écrit la **trace** : qui (`redeemed_by_user_id`), quand (`redeemed_at`), pour quel compte
   revenir (`previous_plan_code`, `previous_status`, lus **en lecture seule** sur l'abonnement), et
   le terme du droit (`granted_until = redeemed_at + duration_hours`).
6. Il renvoie l'état du droit. **Aucune écriture dans `subscriptions`. Aucun appel à Stripe.**

### Cas nominal — l'expiration

Il n'y a **rien à exécuter** : `AccessGrantService` répond « droit ouvert » tant que
`now < granted_until`. Passé le terme, la même comparaison répond non. Le retour au plan précédent
survient donc même si personne ne se connecte, même si aucun job ne tourne, même si le pod a
redémarré.

**Grâce de tour** : le droit de Forge — et lui seul — reste ouvert jusqu'à
`granted_until + app.access-code.grace-minutes` (défaut 15). Un tour engagé avant le terme n'est
jamais coupé en plein milieu par la fermeture. La grâce n'ajoute aucun jeton (le quota n'a jamais
été touché).

### Cas d'erreur

| Situation | Comportement attendu | `error` | Code HTTP |
|---|---|---|---|
| Corps sans `code` / code vide | Message de validation | `validation_error` | 400 |
| Code inconnu | « Ce code d'accès est inconnu. » | `access_code_invalid` | 404 |
| Code déjà consommé | « Ce code a déjà été utilisé. » | `access_code_used` | 409 |
| Code périmé (`valid_until` dépassé, jamais consommé) | « Ce code a expiré. » | `access_code_expired` | 409 |
| Code **nominatif** saisi par un autre compte | « Ce code est réservé à un autre compte. » | `access_code_not_for_account` | 403 |
| L'appelant a **déjà** un droit en cours | « Un accès offert est déjà en cours sur ce compte. » | `access_code_already_granted` | 409 |
| Émission par un non-ADMIN | Refus | `forbidden` | 403 |
| Émission sans `label` / `label` > 120 car. | Message de validation | `validation_error` | 400 |
| Requête anonyme (émission ou consommation) | Rejet de la chaîne de sécurité | — | 401 |

> **Arbitrage assumé** : les refus sont **distincts et actionnables** plutôt qu'uniformes. Un code
> déjà consommé ou périmé ne vaut plus rien : le distinguer d'un code inconnu ne livre aucun secret
> exploitable, et évite de renvoyer « inconnu » à quelqu'un qui tient un code parfaitement réel.

---

## Critères d'acceptation

- [ ] Un ADMIN obtient, sur `POST /admin/access-codes`, un code **en clair une seule fois** ; la base
      n'en contient que le SHA-256 (vérifié en test : aucune colonne ne contient le code).
- [ ] Un utilisateur non-ADMIN reçoit **403** sur `POST /admin/access-codes` et `GET /admin/access-codes`.
- [ ] Un utilisateur qui saisit un code valide obtient **200** et un droit dont `grantedUntil` vaut
      `redeemedAt + 24 h`.
- [ ] Immédiatement après consommation, `AtelierEntitlementService.isEntitled(userId)` est **vrai**
      pour cet utilisateur, **alors que son plan n'a pas changé** (`subscriptions` inchangée, vérifié
      champ à champ en test).
- [ ] Passé `granted_until + grâce`, `isEntitled` redevient **faux** sans qu'aucun code n'ait été
      exécuté entre-temps (test à horloge figée : aucune méthode d'expiration n'existe à appeler).
- [ ] Entre `granted_until` et `granted_until + grâce`, `isEntitled` est **encore vrai** (grâce de tour).
- [ ] Un abonné **Gold** ou porteur de l'**option Forge** garde exactement son accès : aucune
      régression de droit (les tests F-40 existants restent verts).
- [ ] Le **quota** de l'utilisateur est identique avant, pendant et après le droit
      (`EntitlementService.resolveMonthlyTokenQuota` non modifié — test explicite).
- [ ] Un second code saisi pendant un droit en cours est **refusé** (409, pas de cumul).
- [ ] Un code nominatif saisi par un autre compte est **refusé** (403).
- [ ] Un code consommé deux fois est **refusé** la seconde fois (409), y compris sous concurrence
      (UPDATE conditionnel).
- [ ] La liste admin montre la **trace** : e-mail du consommateur, date, terme, plan de retour — et
      **jamais** le code.
- [ ] **Isolation `user_id`** : la lecture du droit d'un utilisateur filtre toujours sur
      `redeemed_by_user_id = <utilisateur du contexte de sécurité>` ; jamais un paramètre client.
- [ ] Aucun appel au `BillingProvider` / à Stripe n'est déclenché par l'émission ou la consommation
      (vérifié : le service ne dépend pas du provider).

---

## Périmètre

### Hors scope (explicite)

- Codes de **réduction** sur abonnement (Stripe).
- **Cumul** de codes.
- Révocation d'un code **déjà consommé** (le droit dure au plus 24 h).
- Toute modification de `subscriptions`, du quota, des compteurs d'usage ou de Stripe.
- L'écran de saisie (SF-62-02) et l'écran d'émission (SF-62-03).
- Toute notification (e-mail de fin de droit) : non demandée.

---

## Valeurs initiales

| Champ | Valeur initiale | Règle |
|---|---|---|
| `granted_plan_code` | `GOLD` | Figé à l'émission — le produit peut changer demain sans réécrire l'histoire des codes déjà émis. |
| `duration_hours` | `app.access-code.duration-hours` (défaut **24**) | Figé à l'émission, pour la même raison. |
| `valid_until` | `now + app.access-code.validity-days` (défaut **30 j**) | Un code « daté » : non consommé, il finit par ne plus rien valoir. |
| `created_by_user_id` | ADMIN du contexte de sécurité | Jamais un paramètre client. |
| `redeemed_by_user_id`, `redeemed_at`, `granted_until`, `previous_plan_code`, `previous_status` | `null` | Peuplés **à la consommation**, jamais avant. |
| `assigned_email` | `null` (code non nominatif) | Normalisé en minuscules s'il est fourni. |

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format / Valeurs | Unicité | Normalisation |
|---|---|---|---|---|---|
| `label` (émission) | Oui | 120 | texte libre non vide | Non | `trim()` |
| `assignedEmail` (émission) | Non | 255 | e-mail valide si fourni | Non | `trim()` + minuscules |
| `code` (consommation) | Oui | 32 | non vide | — | `trim()` + majuscules |
| `code_hash` (base) | Oui | 64 | SHA-256 hexadécimal | **Oui** | — |

Notes :
- Le code en clair n'est **jamais** persisté, ni journalisé (les logs nomment l'`id` de la ligne).
- La comparaison d'un code nominatif est **insensible à la casse** sur l'e-mail.

---

## Technique

### Endpoint(s)

| Méthode | URL | Auth | Rôle minimum |
|---|---|---|---|
| POST | `/api/admin/access-codes` | Oui | **ADMIN** |
| GET | `/api/admin/access-codes` | Oui | **ADMIN** |
| POST | `/api/access-code/redeem` | Oui | USER |
| GET | `/api/access-code/grant` | Oui | USER (le sien seulement) |

### Tables impactées

| Table | Opération | Notes |
|---|---|---|
| `access_codes` | CREATE / INSERT / SELECT / UPDATE | Nouvelle table. |
| `subscriptions` | **SELECT uniquement** | Lecture du plan précédent pour la trace. **Jamais d'écriture.** |

### Migration Liquibase

- [x] Oui — `067-access-codes.xml` (PostgreSQL + H2, chaque changeSet avec son `rollback`).

### Composants Angular

Aucun (SF-62-02 / SF-62-03).

---

## Plan de test

### Tests unitaires

- [ ] `AccessGrantServiceTest` — droit ouvert avant le terme ; fermé après le terme + grâce ; **encore
      ouvert** entre le terme et le terme + grâce (horloge figée).
- [ ] `AccessGrantServiceTest` — aucun droit si le code n'a jamais été consommé.
- [ ] `AtelierEntitlementServiceTest` — non-régression Gold / option Forge (tests existants) **plus** :
      un compte sans plan ni option, mais avec un droit en cours, est entitled ; le même compte,
      droit expiré, ne l'est plus.
- [ ] `AccessCodeServiceTest` — émission : code renvoyé une fois, hash stocké, `valid_until` et
      `duration_hours` figés.
- [ ] `AccessCodeServiceTest` — consommation : nominal, inconnu, déjà consommé, périmé, nominatif
      pour un autre, droit déjà en cours.
- [ ] `AccessCodeServiceTest` — la consommation n'écrit **rien** dans `subscriptions` (le repository
      d'abonnement n'est jamais appelé en écriture).
- [ ] `AccessCodeGeneratorTest` — format, alphabet sans caractères ambigus, entropie (deux tirages
      consécutifs diffèrent).

### Tests d'intégration

- [ ] `POST /api/admin/access-codes` → **200** pour un ADMIN, **403** pour un USER, **401** anonyme.
- [ ] `POST /api/access-code/redeem` → **200** puis **409** au second essai avec le même code.
- [ ] `POST /api/access-code/redeem` → **404** code inconnu, **403** code nominatif d'autrui.
- [ ] `GET /api/access-code/grant` → le droit de l'appelant, et **jamais** celui d'un autre.
- [ ] `GET /api/admin/access-codes` → la trace, sans aucun code en clair dans la réponse.
- [ ] Après consommation, `GET /api/billing/subscription` renvoie **exactement** le même corps
      qu'avant (le plan n'a pas bougé).

### Isolation utilisateur

- [x] Applicable — test : un utilisateur B qui appelle `GET /api/access-code/grant` ne voit **pas**
      le droit consommé par A ; toutes les lectures filtrent sur `redeemed_by_user_id`.

---

## Dépendances

### Subfeatures bloquantes

- F-40 (option Forge) — **done** : `AtelierEntitlementService` existe et porte le droit.
- F-20 (admin) — **done** : `AdminService.assertAdmin()` est la garde unique.

### Questions ouvertes impactées

- [ ] Aucune. F-62 ne touche à aucune entrée de `docs/OPEN_QUESTIONS.md`.

---

## Notes et décisions

- **D1 — Surcouche, pas mutation de plan.** Voir `F-62-cadrage.md`. Rien n'est écrit dans
  `subscriptions` : la colonne `plan_code` appartient au webhook Stripe, et un retour qui dépend d'un
  job planifié est un retour qui peut ne pas avoir lieu.
- **D2 — Le code ouvre le droit, pas le quota.** Même sémantique que l'option Forge (F-40).
  Réversible.
- **D3 — Le code est hashé.** C'est un porteur de valeur : il est montré une fois à l'émission, puis
  seul son SHA-256 subsiste. L'identification dans la liste passe par le `label`, pas par un préfixe
  du code — un préfixe réduirait l'espace de recherche pour rien.
- **D4 — Une seule table.** Un code à usage unique a au plus une consommation : une table de
  rachats séparée n'ajouterait qu'une jointure.
- **D5 — Pas de job planifié.** Délibéré, et c'est le cœur de la feature : l'expiration est une
  comparaison, pas un événement.
- **D6 — Paquet `fr.claudegateway.access`.** `AccessGrantService` n'y dépend **que** du repository
  et de l'horloge : c'est ce qui permet à `billing` de le consommer sans cycle de beans.
