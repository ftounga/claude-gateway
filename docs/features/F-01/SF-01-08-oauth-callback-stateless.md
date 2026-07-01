# Mini-spec — [F-01 / SF-01-08] Durcissement du callback OAuth Google en stateless (cookie)

## Identifiant

`F-01 / SF-01-08`

## Feature parente

`F-01` — Authentification

## Statut

`ready`

## Date de création

2026-07-02

## Branche Git

`feat/SF-01-08-oauth-callback-stateless`

---

## Objectif

Rendre le handshake OAuth2/OIDC Google **réellement stateless** en stockant l'`authorization request` dans un **cookie signé/court** au lieu de la `HttpSession` serveur, afin que le callback fonctionne de façon fiable derrière l'ingress et **avec plusieurs réplicas** (HPA), sans session collante.

---

## Contexte / motivation

La chaîne de sécurité est `SessionCreationPolicy.STATELESS` (`SecurityConfig`). Le flux `oauth2Login` par défaut stocke l'`OAuth2AuthorizationRequest` (et le paramètre `state`) dans une `HttpSession` en mémoire du pod. En staging le backend tourne derrière l'ingress avec un HPA (réplicas ≥ 1, scale-up possible) : si le callback `GET /api/login/oauth2/code/google` atterrit sur un **autre pod** que celui qui a initié la redirection, l'`authorization request` est introuvable → `authorization_request_not_found` → échec de connexion Google. Le stockage cookie supprime toute dépendance à l'état serveur.

> Note : ce correctif ne débloque **pas** à lui seul la connexion — le prérequis reste la présence de `GOOGLE_CLIENT_ID`/`GOOGLE_CLIENT_SECRET` dans le pod (config/ops, hors code). Il élimine le **second** point de rupture, au retour du callback.

---

## Comportement attendu

### Cas nominal

- `GET /api/oauth2/authorization/google` : Spring génère l'`OAuth2AuthorizationRequest`, la sérialise dans un cookie `oauth2_auth_request` (HttpOnly, Secure si requête HTTPS, `SameSite=Lax`, `Max-Age` court ≈ 180 s, `Path=/`), puis redirige (302) vers `accounts.google.com`.
- Retour Google `GET /api/login/oauth2/code/google?code=...&state=...` : le cookie est renvoyé par le navigateur (navigation top-level GET → cookie `Lax` transmis), l'`authorization request` est **désérialisée depuis le cookie** (aucune session serveur requise), le `state` est validé, puis le cookie est **supprimé**.
- La suite est inchangée : `OAuth2LoginSuccessHandler` fédère l'identité, émet le JWT plateforme et redirige vers `${app.frontend-url}/auth/callback#token=<jwt>`.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Cookie `oauth2_auth_request` absent au callback (expiré / bloqué) | Échec OAuth standard Spring → redirection d'erreur (pas de 500) | 302 |
| Cookie présent mais illisible / corrompu | Traité comme absent : `null` renvoyé, cookie supprimé, échec OAuth standard | 302 |
| `state` non concordant | Rejet Spring standard (`invalid_state`) → redirection d'erreur | 302 |
| Google non configuré (client-id absent) | OAuth reste dormant : le repository cookie n'est jamais câblé, démarrage OK | — |

---

## Critères d'acceptation

- [ ] Après `GET /api/oauth2/authorization/google` (OAuth configuré), la réponse 302 pose un cookie `oauth2_auth_request` **HttpOnly** et **non vide** ; `Secure` est positionné quand la requête est HTTPS.
- [ ] Le callback lit l'`authorization request` **exclusivement depuis le cookie** : aucune `HttpSession` n'est requise (le flux réussit avec `SessionCreationPolicy.STATELESS`).
- [ ] À la fin du handshake (succès **ou** échec), le cookie `oauth2_auth_request` est supprimé (`Max-Age=0`).
- [ ] Un cookie absent ou corrompu ne provoque **jamais** de 500 : `load`/`remove` renvoient `null` proprement.
- [ ] Sans `GOOGLE_CLIENT_ID`, l'application démarre normalement (OAuth dormant) et la suite de tests existante passe **inchangée** (non-régression).
- [ ] Non-régression auth stateless : `GET /api/me` sans token → **401 JSON** même avec OAuth activé (`RestAuthenticationEntryPoint` conservé).
- [ ] Aucune donnée sensible durable dans le cookie : durée de vie ≤ 180 s, contenu limité à l'`authorization request` OAuth (pas de JWT, pas de secret).

---

## Périmètre

### Hors scope (explicite)

- La configuration ops (`GOOGLE_CLIENT_ID`/`SECRET` dans `backend-secrets`, redirect URI Google Console) : hors code, traité côté déploiement.
- Le correctif « catch-all masque un 404 en `internal_error` » (`GlobalExceptionHandler`) : concerne la gestion d'erreurs globale, **subfeature distincte** (non traité ici pour respecter la responsabilité unique).
- Le nettoyage des règles ingress `/oauth2` et `/login/oauth2` (sans `/api`) : ajustement manifeste k8s, hors code applicatif.
- Autres fournisseurs OAuth (GitHub/Microsoft…) : hors V1.

---

## Préoccupation transversale — Auth / Principal (analyse d'impact)

Modification du **mécanisme de persistance du handshake OAuth** (composant d'auth). Composants recensés et vérifiés :

| Composant | Impact | Vérification |
|-----------|--------|--------------|
| `SecurityConfig` | Câblage `authorizationEndpoint().authorizationRequestRepository(cookieRepo)` **à l'intérieur** du bloc conditionnel `if (clientRegistrationRepository != null)` | Chaîne strictement inchangée si Google non configuré |
| `HttpCookieOAuth2AuthorizationRequestRepository` (nouveau) | Remplace le repository session par défaut, **uniquement** pour le flux OAuth login | Aucun autre flux ne l'utilise |
| `OAuth2LoginSuccessHandler` | **Inchangé** — reçoit la même `Authentication` en fin de flux | Tests existants conservés |
| `RestAuthenticationEntryPoint` / `JwtAuthenticationFilter` | **Inchangés** — l'auth API reste JWT stateless | Non-régression `/api/me` sans token → 401 |
| `CurrentUser` / isolation `user_id` | **Inchangé** — la fédération et le JWT sont identiques | — |
| `SessionCreationPolicy.STATELESS` | **Conservé** et désormais réellement respecté (plus aucune `HttpSession` créée pour OAuth) | Test : flux OAuth sans session |

---

## Technique

### Endpoints (fournis par Spring Security OAuth2 Client — inchangés)

| Méthode | URL | Auth | Rôle |
|---------|-----|------|------|
| GET | `/api/oauth2/authorization/google` | Non (public) | Démarrage handshake (pose le cookie) |
| GET | `/api/login/oauth2/code/google` | Non (public) | Callback (lit + supprime le cookie) |

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| — | — | **Aucune** table, aucune migration. |

### Migration Liquibase

- [x] Non applicable.

### Composants backend

- `auth/HttpCookieOAuth2AuthorizationRequestRepository` (nouveau) — implémente `AuthorizationRequestRepository<OAuth2AuthorizationRequest>` : `saveAuthorizationRequest` (sérialise → cookie), `loadAuthorizationRequest` (cookie → objet), `removeAuthorizationRequest` (lit puis efface). Sérialisation via `OAuth2AuthorizationRequest` (Serializable) → Base64 URL-safe. `Secure` dérivé de `request.isSecure()` (compatible `forward-headers-strategy: framework`).
- `auth/SecurityConfig` — dans le bloc OAuth conditionnel : `oauth2Login(o -> o.authorizationEndpoint(a -> a.authorizationRequestRepository(cookieRepo)).successHandler(successHandler))`.

### Cookie

| Attribut | Valeur | Justification |
|----------|--------|---------------|
| Nom | `oauth2_auth_request` | — |
| HttpOnly | `true` | Inaccessible au JS (anti-XSS) |
| Secure | `request.isSecure()` | HTTPS en staging/prod ; permet le test local http |
| SameSite | `Lax` | Transmis sur la navigation top-level GET de retour Google |
| Path | `/` | Couvre `/api/...` |
| Max-Age | 180 s | Handshake court ; pas de rémanence |

---

## Plan de test

### Tests unitaires

- [ ] `HttpCookieOAuth2AuthorizationRequestRepositoryTest` — `save` pose un cookie non vide ; `load` restitue une `OAuth2AuthorizationRequest` équivalente (clientId, state, redirectUri, authorizationUri) ; `remove` renvoie la requête et émet un cookie `Max-Age=0`.
- [ ] Idem — cookie absent → `load` renvoie `null` (pas d'exception) ; cookie corrompu (Base64 invalide) → `load` renvoie `null` proprement.
- [ ] Idem — `Secure` vrai quand `request.isSecure()` vrai, faux sinon.

### Tests d'intégration

- [ ] Sans Google configuré : le contexte démarre, aucun `ClientRegistrationRepository`, suite existante inchangée.
- [ ] Avec Google configuré (`@SpringBootTest(properties=...)`) : `GET /api/oauth2/authorization/google` → 302 vers `accounts.google.com` **et** header `Set-Cookie: oauth2_auth_request=...; HttpOnly`.
- [ ] Non-régression : `GET /api/me` sans token → 401 JSON avec OAuth activé.

### Isolation utilisateur

- [x] Non applicable directement (pas d'accès données) ; la fédération aval (`findOrCreateGoogleUser`) et l'isolation `user_id` sont inchangées et déjà couvertes par SF-01-05.

---

## Dépendances

### Subfeatures bloquantes

- `SF-01-01` (socle JWT/SecurityConfig) — Done.
- `SF-01-05` (OAuth Google) — Done (cette SF la durcit).

### Questions ouvertes impactées

- Aucune (`OQ-05` mode d'auth déjà tranchée).

---

## Notes et décisions

- **Pourquoi cookie plutôt que session** : `STATELESS` + réplicas HPA rendent la `HttpSession` mémoire non fiable au callback (pas de session collante configurée sur l'ingress). Le cookie rend le handshake auto-porteur.
- **Sécurité du cookie** : HttpOnly + Secure(HTTPS) + `SameSite=Lax` + durée ≤ 180 s. Le cookie ne contient que l'`authorization request` OAuth (pas de secret, pas de JWT). Le `state` reste vérifié par Spring pour la protection CSRF du flux OAuth.
- **`SameSite=Lax` (et non `Strict`)** : indispensable pour que le cookie soit renvoyé lors de la redirection top-level depuis `accounts.google.com`.
- **Correctif `GlobalExceptionHandler` (404 masqué en 500)** : identifié mais **volontairement hors scope** ici — sera traité en subfeature dédiée si validé.
