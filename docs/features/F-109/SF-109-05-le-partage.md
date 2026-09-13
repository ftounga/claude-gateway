# Mini-spec — [F-109 / SF-109-05] Le partage

---

## Identifiant

`F-109 / SF-109-05`

## Feature parente

`F-109` — Les pages : des documents graphiques, comme dans Claude Code
(cadrage validé par le PO : `CADRAGE-F-109-les-pages.md`, §3, §6)

## Statut

`done` — mergée le 2026-09-14 (PR #575)

## Date de création

2026-09-14

## Branche Git

`feat/SF-109-05-partage`

---

## Objectif

Le propriétaire d'une page crée un **lien de partage** non devinable, révocable et à expiration, qu'on ouvre
**sans compte**, sous la même politique de sécurité — après un avertissement sur les données du client, et
avec un journal de la page.

---

## Comportement attendu

### Cas nominal

1. **Créer un lien** — `POST /pages/{id}/shares` `{expiresInDays}` (1 à 90, 7 par défaut) :
   - jeton de **32 octets aléatoires** (`SecureRandom`, base64url, 43 caractères) ; seule son **empreinte
     SHA-256** est conservée (`page_shares.token_hash`) — le jeton n'est rendu **qu'une fois**, à la création ;
   - réponse : `{id, url: "/p/{jeton}", createdAt, expiresAt}` ; l'écran compose `https://<origine>/p/{jeton}`.
2. **Ouvrir sans compte** — `/p/{jeton}` (écran Angular public, hors coquille, sans garde) : un bandeau
   « Page partagée » et l'`iframe` bac à sable sur `GET /api/p/{jeton}/` — **la même route publique** que
   les tickets (SF-109-01), qui sert la **version courante** sous la politique de §3 ; les pièces jointes
   sous `GET /api/p/{jeton}/{nom}`.
3. **Compter les ouvertures** : chaque `GET /api/p/{jeton}/` valide incrémente `open_count`, pose
   `last_opened_at` et écrit un événement `OPENED` — **sans rien du visiteur** (ni IP, ni agent, ni
   référent). Les pièces jointes ne comptent pas.
4. **Lister et révoquer** — `GET /pages/{id}/shares` (sans jeton : création, expiration, révocation,
   ouvertures, dernière ouverture, état `ACTIVE` | `EXPIRED` | `REVOKED`) ; `DELETE /pages/{id}/shares/{shareId}`
   pose `revoked_at` : le lien cesse **immédiatement**.
5. **Journal** — `GET /pages/{id}/journal` : `CREATED` (publication de la version 1), `VERSION` (republication),
   `SHARED`, `OPENED`, `REVOKED`, avec leur date et, pour les trois derniers, le lien concerné ; le plus récent
   d'abord, 200 au plus.
6. **Avertissement au moment de partager** (dialogue) : « Cette page contient peut-être des données de votre
   client ; vérifiez qu'il autorise leur diffusion. » — le bouton *Créer le lien* n'est actif qu'une fois la case
   « J'ai vérifié » cochée.
7. **Où** : *Partager* dans le menu d'une carte de l'onglet Pages, dans le panneau du terminal et dans le plein
   écran. Supprimer une page supprime ses liens (cascade).

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Jeton inconnu, révoqué, expiré, tronqué | page d'erreur minimale **sous la politique**, indiscernables | 404 |
| `expiresInDays` < 1 ou > 90 | refus avec la borne | 400 |
| Page d'un autre compte (créer, lister, révoquer, journal) | introuvable | 404 |
| Révoquer un lien d'une autre page ou déjà révoqué | 404 / sans effet (204) | 404 / 204 |
| `POST`, `PUT`, `DELETE` sur `/api/p/**` sans JWT | refusés (non-régression) | 401 |
| Page supprimée | ses liens répondent 404 | 404 |

---

## Critères d'acceptation

- [ ] CA1 — créer un lien rend une URL `/p/{43 caractères}` ; la base ne contient que l'empreinte, jamais le jeton.
- [ ] CA2 — `GET /api/p/{jeton}/` sans JWT sert la version **courante** avec **exactement** la CSP de SF-109-01 ; une pièce jointe aussi.
- [ ] CA3 — révoqué → 404 immédiat ; expiré → 404 ; jeton inconnu → 404 ; tous sous la politique.
- [ ] CA4 — chaque ouverture du HTML incrémente le compteur et écrit `OPENED` ; une pièce jointe non ; aucune donnée du visiteur n'est stockée.
- [ ] CA5 — expiration 1 à 90 jours, 7 par défaut ; hors borne 400.
- [ ] CA6 — isolation : Bob ne crée, ne liste, ne révoque ni ne lit le journal d'une page d'Alice (404).
- [ ] CA7 — journal : `CREATED`, `VERSION`, `SHARED`, `OPENED`, `REVOKED` dans l'ordre inverse.
- [ ] CA8 — non-régression de la chaîne : seul `GET /p/**` est ouvert ; les autres verbes et routes restent 401.
- [ ] CA9 — l'écran `/p/:token` est **public** (déclaré avant le parent authentifié, sans garde) et n'affiche la page que dans l'`iframe` bac à sable.
- [ ] CA10 — dialogue : avertissement affiché, création bloquée tant que la case n'est pas cochée ; lien copiable ; liens listés avec leur état et révocables ; journal lisible.

---

## Périmètre

### Hors scope (explicite)

- Commentaires, édition à plusieurs (cadrage §6).
- Protection par mot de passe, liste d'adresses autorisées.
- Partage d'une version précise (le lien suit la version courante).
- Notification à chaque ouverture.

---

## Valeurs initiales

| Champ | Valeur initiale | Règle |
|-------|----------------|-------|
| `expiresInDays` | 7 | 1 à 90 |
| `open_count` | 0 | +1 par ouverture du HTML |
| `revoked_at` | `null` | posé à la révocation, jamais effacé |
| case « J'ai vérifié » | décochée | la diffusion n'est jamais présumée |

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format / Valeurs autorisées | Unicité | Normalisation |
|-------|-------------|-------------|----------------------------|---------|---------------|
| `expiresInDays` | Non | — | entier 1 à 90 | — | défaut 7 |
| jeton | — | 43 | base64url, 32 octets | empreinte unique | — |
| `token_hash` | Oui | 64 | hex SHA-256 | Oui | minuscules |

---

## Technique

### Endpoint(s)

| Méthode | URL (sous `/api`) | Auth | Rôle minimum |
|---------|-----|------|-------------|
| POST | `/pages/{id}/shares` | JWT | propriétaire |
| GET | `/pages/{id}/shares` | JWT | propriétaire |
| DELETE | `/pages/{id}/shares/{shareId}` | JWT | propriétaire |
| GET | `/pages/{id}/journal` | JWT | propriétaire |
| GET | `/p/{jeton}/`, `/p/{jeton}/{nom}` | **aucune** (jeton) | — (route de SF-109-01) |

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| `page_shares` | INSERT / SELECT / UPDATE | FK `pages` cascade ; unique `token_hash` ; index `(user_id, page_id)` |
| `page_events` | INSERT / SELECT | FK `pages` cascade ; index `(user_id, page_id, occurred_at)` |

### Migration Liquibase

- [x] Oui — `099-page-shares.xml` (libre sur `main` au 2026-09-14), rollback `dropTable`.

### Composants

- Backend : `PageShare`, `PageEvent`, `PageEventKind`, repositories, `PageShareService`, `PageShareController`,
  `PagePublicController` (résolution du jeton de partage), `PageService` (événements `CREATED` / `VERSION`), DTO.
- Frontend : `PagesService` (+ shares, createShare, revokeShare, journal), `shared/pages/page-share-dialog.component`,
  `pages/shared-page.component` (route publique `p/:token`), `host-pages`, `page-panel`, `page-viewer` (*Partager*),
  `app.routes.ts`.

### Préoccupations transversales

- **Auth / Principal : OUI.** Composants : `SecurityConfig` (**inchangé** : `GET /p/**` posé en SF-109-01),
  `PagePublicController` (seul consommateur du jeton), `JwtAuthenticationFilter` (inchangé). Non-régression :
  `POST/PUT/DELETE /p/**`, `/pages/**`, `/workspaces`, `/me` en 401 sans JWT ; un jeton de partage en `Bearer`
  n'authentifie rien.
- **Navigation / routing : OUI.** Composants : `app.routes.ts` — route **publique** `p/:token` déclarée **avant**
  le parent authentifié (comme les pages légales), sans garde ; `app.routes.spec.ts` le vérifie. Routes
  existantes inchangées.
- **Contexte tenant : OUI.** Composants : `PageShareService` (propriétaire filtré `user_id` ; la résolution
  publique rend le `user_id` de la ligne, la page est relue filtrée dessus).
- **Plans / limites : non** (partager ce qu'on a produit n'exige pas de droit, comme le relire).

---

## Plan de test

### Tests unitaires

- [ ] `PageShareServiceTest` (contexte Spring, horloge fixe) — création (jeton, empreinte, bornes), résolution (active, révoquée, expirée, inconnue), compteur, révocation, journal, isolation.

### Tests d'intégration

- [ ] `PageShareApiIntegrationTest` — endpoints propriétaires (201/200/204/400/404), lecture publique sans JWT et politique, révoqué / expiré 404, pièce jointe sans comptage, jeton en `Bearer`, non-régression des verbes.
- [ ] `page-share-dialog.component.spec.ts` — avertissement, case, création, copie, liste, révocation, journal.
- [ ] `shared-page.component.spec.ts` — `iframe` bac à sable sur `/api/p/{jeton}/` ; `app.routes.spec.ts` — route publique avant le parent.

### Isolation utilisateur

- [x] Applicable — Bob ne crée, ne liste, ne révoque ni ne consulte le journal d'une page d'Alice.

---

## Dépendances

### Subfeatures bloquantes

- SF-109-01 à SF-109-04 — mergées.

### Questions ouvertes impactées

- Aucune.

---

## Notes et décisions

- **D1 — Empreinte seule** : un vol de la base ne donne aucun lien ouvrable ; le jeton n'est montré qu'une fois
  (comme un jeton d'API). Perdu, on en crée un autre.
- **D2 — Même route que les tickets** : la chaîne de filtres n'ouvre toujours qu'un préfixe ; le format
  distingue (`t1.` = ticket signé ; sans point = partage).
- **D3 — Aucune donnée du visiteur** : le cadrage ne veut identifier le visiteur « au-delà du nombre
  d'ouvertures » ; ni IP ni agent ne sont écrits.
- **D4 — L'écran public encadre la page** d'un bandeau sobre et d'une `iframe` bac à sable : la page reste dans
  son origine opaque même si l'adresse `/api/p/…` est ouverte directement (elle porte alors sa CSP).
