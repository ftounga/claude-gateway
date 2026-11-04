# Mini-spec — F-232 / SF-232-05 — La session Jarvis du compagnon

## Identifiant

`F-232 / SF-232-05`

## Feature parente

`F-232` — Le compagnon Android de Jarvis (cadrage `CADRAGE-F-232-compagnon-android.md`, **amendement du 2026-11-04**, D7, D9)

## Statut

`in-review`

## Date de création

2026-11-04

## Branche Git

`feat/SF-232-05-session-jarvis-compagnon`

---

## Objectif

> Le compagnon Android appairé obtient, contre son jeton dédié, une **session JWT courte à portée `jarvis-compagnon`**
> qui n'ouvre que les routes de l'écran `/jarvis`, meurt avec la révocation de l'appareil, et dont chaque émission est
> tracée pour la mesure J+7.

---

## Comportement attendu

### Cas nominal

1. `POST /runner/jarvis/session` (chaîne runner, route nommée, `permitAll`), en-tête `X-Jarvis-Token` vérifié **par le
   contrôleur** (même vérification que la relève des annonces, échéance glissante repoussée). Corps facultatif
   `{ locked?: boolean, purpose?: "OPEN" | "UNLOCK" | "RENEW" }`.
2. Rend `{ token, expiresAt, scope: "jarvis-compagnon", locked }`. Le JWT (HS256, même clé) porte `sub` (compte),
   `email`, `role`, `tv` (version de jeton), `scope = jarvis-compagnon`, `cid` (compagnon), `lk` (verrouillé).
   Échéance **30 min** (`app.jarvis.companion.session-ttl`, défaut `PT30M`).
3. `locked` absent ⇒ **verrouillé** (défaut sûr). `purpose` absent ou inconnu ⇒ `OPEN`.
4. Une ligne `jarvis_companion_openings (user_id, companion_id, purpose, locked, created_at)` par émission, et une ligne
   de journal `jarvis_companion_session purpose=… locked=… companion=…` (jamais le jeton).
5. Le **filtre JWT** de la chaîne principale (`JwtAuthenticationFilter`), pour un jeton portant `scope` :
   - portée autre que `jarvis-compagnon` ⇒ non authentifié (401) ;
   - route hors **liste fermée** (`JarvisCompanionScope`) ⇒ **403** `jarvis_companion_scope` écrit par le filtre ;
   - compagnon inconnu, d'un autre compte, non appairé ou révoqué ⇒ non authentifié (401) ;
   - sinon : même authentification qu'une session ordinaire (même `AuthenticatedUser`, même contrôle `tv`), avec des
     **détails** `CompanionSession(companionId, locked)` lus par `CurrentUser.companionSession()`.
6. Session ordinaire (sans `scope`) : **strictement inchangée**.

### Endpoints de la portée `jarvis-compagnon` (liste fermée, exhaustive)

Relevé de tous les appels de l'écran `/jarvis` (composant, volets, services injectés, pièces) le 2026-11-04. Chemins
sans `/api`. `*` = un segment.

| Groupe | Routes ouvertes |
|---|---|
| Fil et tour | `GET /workspaces` · `GET /workspaces/*/chat` · `POST /workspaces/*/chat/stream` · `POST /workspaces/*/chat/interrupt` · `POST /workspaces/*/chat/confirm` (gardé par SF-232-06) |
| Écoute et voix | `POST /atelier/transcription` · `GET /jarvis/voice` · `POST /jarvis/speech` · `GET /jarvis/clips/*` · `POST /jarvis/stt-session` · `POST /jarvis/stt-session/*/close` · `POST /jarvis/utterance` |
| Cerveau rapide | `POST /jarvis/converse` · `POST /jarvis/converse/warm` |
| Scène, activité, mesure | `GET /jarvis/universe` · `GET /jarvis/activity` · `GET /jarvis/watches` · `GET /jarvis/thread/today` · `POST /jarvis/usage` · `POST /jarvis/timings` · `POST /jarvis/captures` · `PUT /jarvis/captures/latest/correction` |
| Préférences, coût | `GET /jarvis/preferences` · `PUT /jarvis/preferences` · `GET /jarvis/costs` · `POST /jarvis/budget/confirm` |
| Alertes de veille | `GET /jarvis/alerts/*` · `POST /jarvis/alerts/*/open` |
| Lecture courrier / agenda | `GET /jarvis/mail/brief` · `GET /jarvis/mail/items/*` · `GET /jarvis/mail/accounts` · `GET /jarvis/calendar/events` · `GET /jarvis/calendar/feeds` · `GET /jarvis/calendar/grants` · `GET /jarvis/companions` |
| Portrait | `GET /jarvis/portrait` · `POST /jarvis/portrait/souvenirs` · `DELETE /jarvis/portrait/souvenirs/*` · `GET /jarvis/portrait/interview` · `POST /jarvis/portrait/interview/offered` · `POST /jarvis/portrait/interview/draft` |
| Pièces | `GET /jarvis/pieces` · `GET /jarvis/pieces/*/thumb|text|image` · `GET /workspaces/*/deposits/*/content` · `GET /workspaces/*/proofs/*` · `GET /generated-images/*/image` · `GET /presentations/*/slides/*` · `GET /videos/*` · `POST /videos/*/comments` · `GET /pages/*` |
| Compagnon (public) | `GET /runner/download/android` · `GET /runner/download/android/version` |

**Fermées volontairement** (appelées par des volets de réglage de l'écran, mais qui engagent hors de la conversation) :
`POST/DELETE /jarvis/companions…` (appairer, révoquer), `POST/DELETE /jarvis/mail/accounts…` et `…/test`,
`POST/DELETE /jarvis/calendar/feeds…`, `GET /jarvis/calendar/oauth/start`, `DELETE /jarvis/calendar/grants/*`
(révoque chez Google), `PUT/DELETE /jarvis/portrait`, `PATCH /jarvis/portrait/souvenirs/*`,
`POST /jarvis/portrait/souvenirs/*/integrate`, `PUT /jarvis/budget`. Et tout le reste : `/me/**`, `/admin/**`,
`/notifications`, création/suppression de sujets, etc. Les médias par ticket (`/video-media/**`, `/p/**`) ne portent
pas de jeton : inchangés.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| `X-Jarvis-Token` absent, inconnu, révoqué, expiré ; ou JWT ordinaire présenté | `jarvis_companion_unauthorized` (générique) | 401 |
| Session `jarvis-compagnon` sur une route hors liste | `jarvis_companion_scope` | 403 |
| Session d'un compagnon révoqué | non authentifiée | 401 |
| « Déconnecter toutes les sessions » puis session du compagnon | non authentifiée | 401 |
| Portée inconnue dans un JWT | non authentifiée | 401 |

---

## Critères d'acceptation

- [x] Le jeton du compagnon s'échange contre un JWT de portée `jarvis-compagnon`, ≤ 30 min, `lk = true` sans corps.
- [x] La session ouvre les routes de l'écran (ex. `GET /jarvis/preferences`, `GET /workspaces`) et répond 403 `jarvis_companion_scope` hors liste (`/me`, `/admin/**`, appairage, suppression du portrait, budget, comptes de courrier, agenda Google, `/notifications`…).
- [x] Révoquer le compagnon (par son propriétaire) coupe la session à la requête suivante ; Bob ne peut pas le révoquer (404).
- [x] « Déconnecter toutes les sessions » coupe aussi la session du compagnon.
- [x] Isolation : la session d'Alice ne lit que les données d'Alice.
- [x] Chaque émission est tracée (motif, verrouillé) ; aucun jeton dans la table ni dans le journal.
- [x] Une session ordinaire est inchangée (non-régression `GET /me`).

---

## Contraintes de validation

| Champ | Règle |
|---|---|
| `locked` | booléen facultatif ; absent ⇒ `true` |
| `purpose` | ≤ 16 caractères ; `OPEN`/`UNLOCK`/`RENEW` (casse ignorée) ; autre ⇒ `OPEN` |
| Échéance | `PT30M` (configurable) |

---

## Plan de test minimal

- **Unitaires** : `JarvisCompanionScopeTest` — 13 routes ouvertes, 23 fermées (dont `..`, joker multi-segments, méthode), aucun `**` dans la liste.
- **Intégration** : `JarvisCompanionSessionApiIntegrationTest` — émission et claims, refus 401 (absent, inconnu, JWT ordinaire), portée fermée (12 routes hors liste ⇒ 403), révocation, logout-all, isolation, mesure, non-régression.
- **Non-régression** : `JarvisCompanionApiIntegrationTest`, `JwtServiceTest`, suite backend complète.
- **Isolation utilisateur** : compagnon d'un autre compte refusé (`findByIdAndUserId`), révocation chez autrui 404, lecture limitée au compte.

---

## Impacts

- **Tables** : `jarvis_companion_openings` (nouvelle, migration 246).
- **Endpoints** : `POST /runner/jarvis/session` (nouveau, chaîne runner, déclaré seul).
- **Backend** : `JwtService.generateCompanionToken`, `JwtAuthenticationFilter` (portée), `CompanionSession`,
  `CompanionSessionVerifier`, `CurrentUser.companionSession()`, `JarvisCompanionScope`, `JarvisCompanionSessionGuard`,
  `JarvisCompanionSessionService`, `JarvisCompanionDeviceController`, `RunnerSecurityConfig`.
- **Préoccupations transversales** :
  - [x] **Auth / Principal** — nouvelle portée de JWT. Composants vérifiés : `JwtAuthenticationFilter` (seul lecteur
    des JWT : `grep parseClaims`), `SecurityConfig` (inchangée), `RunnerSecurityConfig` (+1 route nommée),
    `McpPersonalTokenAuthenticationFilter` / `McpResourceServerConfig` (jetons `cgmcp_`/OAuth, autre chaîne,
    inchangés), `RelaySecurityConfig` (inchangée), `AuthService` / `OAuth2LoginSuccessHandler` (émettent des sessions
    ordinaires, inchangés), `CurrentUser` (+ `companionSession()`). Le filtre est aussi enregistré au niveau servlet :
    les routes publiques `/runner/download/android*` sont dans la liste pour que l'écran n'y reçoive pas de 403.
    Tous les endpoints accessibles à la portée : tableau ci-dessus. Non-régression : session ordinaire.
  - [x] **Contexte tenant** — `user_id` = `sub` du JWT (inchangé) ; le compagnon doit appartenir à ce compte.
    Composants : `JarvisCompanionSessionGuard`, `JarvisCompanionSessionService`.
  - [ ] Plans / limites — sans objet.
  - [ ] Navigation — sans objet (SF-232-07).

---

## Hors périmètre

- Le refus des actions irréversibles téléphone verrouillé (SF-232-06).
- L'écran `/jarvis` dans le compagnon (SF-232-07) et l'APK (SF-232-08).
- Un écran d'administration de la mesure (lecture SQL de `jarvis_companion_openings` à J+7).
