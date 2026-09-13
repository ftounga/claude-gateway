# Mini-spec — [F-109 / SF-109-01] Ranger et servir une page en sécurité

---

## Identifiant

`F-109 / SF-109-01`

## Feature parente

`F-109` — Les pages : des documents graphiques, comme dans Claude Code
(cadrage validé par le PO : `CADRAGE-F-109-les-pages.md`, §3, §7, §8)

## Statut

`done` — mergée le 2026-09-13 (PR #563)

## Date de création

2026-09-13

## Branche Git

`feat/SF-109-01-ranger-et-servir`

---

## Objectif

La gateway **range** une page HTML (versions, stockage objet, quotas) et la **sert** dans une origine
opaque qui ne peut ni lire l'application, ni sortir sur le réseau — privée à son propriétaire.

---

## Comportement attendu

### Cas nominal

1. **Publier** (méthode de service, appelée par l'outil de SF-109-02 — aucun endpoint de dépôt : une
   route d'upload sans producteur serait une surface offerte pour rien, même doctrine que F-89 D2).
   `PageService.publish(PagePlace, pageId?, title, description, html, attachments)` :
   - sans `pageId` → crée la page (version 1) ;
   - avec le `pageId` d'une page **du même compte** → crée la version N+1 (le titre et la description
     sont mis à jour) ;
   - le HTML est rangé sous `pages/{userId}/{pageId}/v{N}/index.html`, chaque pièce jointe sous
     `pages/{userId}/{pageId}/v{N}/files/{nom}` — dans le **stockage objet existant**
     (`WorkspaceStorage` : S3 en cluster, mémoire en dev/test), préfixe distinct, comme les images de
     moments (F-89) ;
   - au-delà de **10 versions**, les plus anciennes sont purgées (lignes et objets).
2. **Le lieu** (`PagePlace`) : `userId`, `space` (`FORGE` | `VIGIE`), `hostId` (poste / client, peut
   être nul pour un projet sans poste), `workspaceId` (projet ou terminal d'origine).
3. **Lire** (propriétaire) :
   - `GET /pages/{id}` → métadonnées + `viewUrl` (ticket de lecture, voir 4) ;
   - `GET /pages/{id}/content[?version=N][&download=true]` → le HTML, avec **la politique de §3**
     (ci-dessous) ; `download=true` ajoute `Content-Disposition: attachment`.
4. **L'iframe de l'application ne peut pas porter le JWT** (en-tête `Authorization`) : l'écran obtient
   un **ticket de lecture** court (`viewUrl`, 10 minutes), signé HMAC-SHA256 avec une clé **dérivée**
   du secret JWT (jamais un JWT : un ticket ne peut pas authentifier l'API), lié à
   `(pageId, version, userId, expiration)`. Le ticket se lit sur **la seule route ouverte sans
   authentification** : `GET /p/{jeton}/` (le HTML) et `GET /p/{jeton}/{pièce jointe}`. SF-109-05
   branchera sur **cette même route** les liens de partage.
5. **La politique de §3**, posée sur **toute** réponse de contenu (propriétaire, ticket, pièce jointe,
   erreur de la route publique) :
   - `Content-Security-Policy: sandbox allow-scripts allow-popups; default-src 'none';
     script-src 'unsafe-inline' https://cdnjs.cloudflare.com https://cdn.jsdelivr.net 'self';
     style-src 'unsafe-inline' https://fonts.googleapis.com https://cdnjs.cloudflare.com https://cdn.jsdelivr.net 'self';
     font-src https://fonts.gstatic.com data: 'self'; img-src data: blob: 'self'; media-src data: blob: 'self';
     connect-src 'none'; form-action 'none'; frame-src 'none'; worker-src 'none'; object-src 'none';
     base-uri 'none'; frame-ancestors 'self'` — **jamais** `allow-same-origin`, `allow-forms`,
     `allow-top-navigation` ;
   - `X-Content-Type-Options: nosniff`, `Referrer-Policy: no-referrer`,
     `Cache-Control: private, no-store`, `Cross-Origin-Resource-Policy: same-origin`,
     `Permissions-Policy` fermant caméra, micro, géolocalisation, paiement, USB.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Page inconnue **ou** d'un autre compte (lecture, contenu, ticket, republication) | indiscernables | 404 |
| Version inconnue | introuvable | 404 |
| Ticket falsifié, expiré, mal formé, ou dont la page n'appartient plus au compte signé | page d'erreur minimale, **même politique CSP** | 404 |
| Pièce jointe inconnue ou nom invalide (`../`, `/`, caractère hors liste) | introuvable | 404 |
| Titre vide / > 120, description > 300, HTML vide | `PageRejectedException` (message qui dit quoi corriger) | — (service) |
| Page > **8 Mo**, pièces jointes comprises | refusée avec la borne | — (service) |
| Compte au-delà de **500 Mo** après purge des versions dépassant 10 | `PageQuotaExceededException` | — (service) |
| Pièce jointe : > 20, nom invalide, extension hors liste | refusée avec la règle | — (service) |
| Sans JWT sur `/pages/**` | 401 (inchangé) | 401 |

---

## Critères d'acceptation

- [ ] CA1 — `publish` sans `pageId` crée la version 1 ; avec le `pageId` du même compte, la version 2 ; la liste des versions a 2 lignes.
- [ ] CA2 — la 11e publication d'une même page purge la version 1 (ligne **et** objets du stockage).
- [ ] CA3 — une page de 8 Mo + 1 octet est refusée ; un compte dont l'usage dépasserait 500 Mo est refusé (limites injectées en test).
- [ ] CA4 — `GET /pages/{id}/content` rend le HTML avec **exactement** la CSP ci-dessus : contient `sandbox allow-scripts allow-popups`, `connect-src 'none'`, `form-action 'none'`, et **ne contient pas** `allow-same-origin`, `allow-forms`, `allow-top-navigation`.
- [ ] CA5 — `nosniff`, `no-referrer`, `private, no-store` présents ; `download=true` ajoute `attachment`.
- [ ] CA6 — **isolation** : Bob obtient 404 sur la page d'Alice (métadonnées, contenu, republication) ; un ticket d'Alice ne sert que la page d'Alice.
- [ ] CA7 — `GET /p/{ticket}/` sans JWT rend la page avec la même politique ; ticket expiré, falsifié ou tronqué → 404 avec la même politique.
- [ ] CA8 — **non-régression de la chaîne de filtres** : sans JWT, `/pages`, `/pages/{id}/content`, `/workspaces`, `/me`, `/billing/subscription` restent en 401 ; seul `GET /p/**` passe ; `POST /p/x/` n'est pas ouvert.
- [ ] CA9 — un ticket présenté comme `Authorization: Bearer` n'authentifie rien (401).
- [ ] CA10 — une pièce jointe se sert sous le ticket avec son type et la même politique ; `..` → 404.

---

## Périmètre

### Hors scope (explicite)

- L'outil de l'agent et le guide de conception → SF-109-02.
- Le bloc du terminal, le panneau, le plein écran → SF-109-03.
- Liste, renommage, suppression, versions à l'écran, export → SF-109-04.
- Liens de partage, journal → SF-109-05 (la route publique est posée ici, pour les tickets).
- Vignette générée côté serveur (aucun navigateur sans tête : la vignette sera l'iframe réduite).
- Tout endpoint de **dépôt** HTTP.

---

## Valeurs initiales

| Champ | Valeur initiale | Règle |
|-------|----------------|-------|
| `pages.current_version` | 1 | incrémentée à chaque republication |
| `pages.user_id` | utilisateur du tour | jamais un paramètre client |
| `page_versions.version` | N+1 de la plus haute existante | unique par page |
| `created_at` / `updated_at` | horodatage serveur | — |

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format / Valeurs autorisées | Unicité | Normalisation |
|-------|-------------|-------------|----------------------------|---------|---------------|
| `title` | Oui | 120 | texte, sans retour à la ligne | Non | `strip()`, espaces blancs repliés |
| `description` | Non | 300 | texte | Non | `strip()`, vide → `null` |
| `html` | Oui | 8 Mo (avec pièces jointes) | UTF-8 | — | — |
| `space` | Oui | — | `FORGE`, `VIGIE` | — | — |
| pièce jointe `name` | Oui | 100 | `[A-Za-z0-9][A-Za-z0-9._-]*`, extension parmi `css js mjs json svg png jpg jpeg gif webp csv txt md woff2` | par version | — |
| pièces jointes | Non | 20 | — | — | — |
| compte | — | 500 Mo | somme des `size_bytes` des versions conservées | — | — |
| versions | — | 10 par page | les plus anciennes purgées | — | — |

---

## Technique

### Endpoint(s)

| Méthode | URL (sous `/api`) | Auth | Rôle minimum |
|---------|-----|------|-------------|
| GET | `/pages/{id}` | JWT | propriétaire |
| GET | `/pages/{id}/content?version=&download=` | JWT | propriétaire |
| GET | `/p/{jeton}` (→ 302 `{jeton}/`), `/p/{jeton}/`, `/p/{jeton}/{nom}` | **aucune** (ticket signé) | — |

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| `pages` | INSERT / SELECT / UPDATE | `user_id` FK `users` (cascade), index `(user_id, host_id, space)`, `(user_id, workspace_id)` |
| `page_versions` | INSERT / SELECT / DELETE | FK `pages` (cascade), unique `(page_id, version)`, index `user_id` |

### Migration Liquibase

- [x] Oui — `098-pages.xml` (numéro libre sur `main` au 2026-09-13 : dernier = 097), rollback `dropTable`.

### Composants impactés

- Nouveau paquet `fr.claudegateway.pages` : `Page`, `PageVersion`, repositories, `PagePlace`, `PageSpace`,
  `PageService`, `PageStore` (sur `WorkspaceStorage`), `PageLimits` (`app.pages.*`),
  `PageViewTicketService`, `PageContentPolicy`, `PageController`, `PagePublicController`,
  `PageExceptionHandler`.
- `auth/SecurityConfig` : **une** ligne `GET /p/**` `permitAll`.

### Préoccupations transversales

- **Auth / Principal : OUI.** Composants : `SecurityConfig` (chaîne principale), `RunnerSecurityConfig`
  et `RelaySecurityConfig` (chaînes à `securityMatcher`, **non touchées**), `JwtAuthenticationFilter`
  (inchangé, lit uniquement `Authorization`). Endpoints vérifiés en non-régression : `/pages/**`,
  `/workspaces`, `/me`, `/billing/subscription` (401 sans JWT), et le verbe `POST` sur `/p/**`.
- **Contexte tenant : OUI.** Tout accès passe par `findByIdAndUserId` ; le ticket porte l'utilisateur
  signé et la page est relue **filtrée** sur lui.
- **Plans / limites : OUI** (quota de stockage). Composants : `PageService` seul (aucun service de
  limite existant modifié). Le droit Forge / Vigie est posé en SF-109-02 (`buildTools`).
- **Navigation / routing : non** (aucune route frontend dans cette subfeature).

---

## Plan de test

### Tests unitaires

- [ ] `PageServiceTest` — création v1, republication v2, purge au-delà de 10, 8 Mo + 1 refusé, quota compte refusé, titre vide / trop long, description trop longue, pièces jointes (nom `../x`, extension `.exe`, 21 pièces), republication d'une page d'autrui → introuvable.
- [ ] `PageViewTicketServiceTest` — aller-retour, expiration, signature altérée, format tronqué, clé différente.
- [ ] `PageContentPolicyTest` — la CSP contient ce qu'elle doit et **rien** de ce qu'elle ne doit pas.

### Tests d'intégration

- [ ] `PageApiIntegrationTest` — `GET /pages/{id}` 200 + `viewUrl` ; contenu + en-têtes ; `download` ; version inconnue 404 ; Bob 404 ×2.
- [ ] `PagePublicRouteIntegrationTest` — ticket valide sans JWT 200 + CSP ; expiré / falsifié 404 + CSP ; pièce jointe ; `..` 404 ; `/p/{t}` → 302 ; ticket en `Bearer` n'authentifie pas ; **non-régression** 401 sur les routes protégées ; `POST /p/x/` refusé.

### Isolation utilisateur

- [x] Applicable — Bob ne lit ni les métadonnées, ni le contenu, ni ne republie une page d'Alice ; un ticket signé pour Alice sur une page de Bob → 404.

---

## Dépendances

### Subfeatures bloquantes

- Aucune (première subfeature de F-109).

### Questions ouvertes impactées

- Aucune (`OPEN_QUESTIONS.md` ne traite pas des pages ; §3 du cadrage est tranché).

---

## Notes et décisions

- **D1 — Le ticket de lecture.** Une `iframe` ne peut pas porter l'en-tête `Authorization`. Les deux
  alternatives écartées : `srcdoc` / `blob:` (la politique ne peut plus être un **en-tête** — un `<meta>`
  injecté se contourne par un script placé avant `<head>`), et un cookie de session (l'application
  est sans état). Le ticket est court, lié à la page et au compte, et vit sur **la même** route
  publique que les partages : la chaîne de filtres n'ouvre qu'un préfixe.
- **D2 — Clé dérivée, format non-JWT.** `HMAC-SHA256(secret JWT, "claude-gateway/page-view-ticket/v1")`
  sert de clé ; le ticket est `t1.{payload base64url}.{mac base64url}`. Un script de la page peut lire
  son URL : le ticket ne donne alors accès **qu'à cette page**, dix minutes, et ne passe jamais pour un
  JWT.
- **D3 — Stockage.** Réemploi de `WorkspaceStorage` (S3 en cluster, mémoire en dev/test), préfixe
  `pages/`, comme `TeamsMomentImageService` : aucun bucket ni droit IAM nouveau à déployer.
- **D4 — `'self'`** dans `script-src`/`img-src` permet les pièces jointes servies **avec** la page ;
  une requête vers la gateway elle-même ne sort pas du périmètre.
- **Risque résiduel (documenté, non traité par CSP)** : un script peut **naviguer** (l'`iframe` elle-même,
  ou une fenêtre ouverte via `allow-popups`) vers une URL externe. CSP n'encadre plus la navigation
  (`navigate-to` abandonné par les navigateurs) ; le cadrage §3 retient `allow-popups` pour que les liens
  s'ouvrent. La page reste sans accès à l'application.
