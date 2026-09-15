# Mini-spec — [F-122 / SF-122-02] Parcours UX guidé à l'activation de la Vigie

> Base : `project-governance/templates/subfeature-template.md`.
> Cadrage : `docs/features/F-122/CADRAGE-F-122-mise-en-service-vigie-automatique.md` (SF-122-02).

---

## Identifiant

`F-122 / SF-122-02`

## Feature parente

`F-122` — Mise en service automatique et guidée de la Vigie

## Statut

`ready`

## Date de création

2026-09-15

## Branche Git

`feat/SF-122-02-parcours-ux-guide`

---

## Objectif

> Offrir, dans le volet Vigie, un **assistant de mise en service** qui vérifie une check-list
> vert/rouge **avant** de démarrer et ne débloque « démarrer » que **tout est vert**.

---

## Comportement attendu

### Cas nominal

1. Pour un client sélectionné (poste), l'assistant appelle
   `GET /api/runner-hosts/{hostId}/vigie/readiness` et affiche une check-list de **4 vérifications** :
   1. **runner connecté** ;
   2. **Chrome managé lancé et joignable** ;
   3. **Teams connecté** (sinon un bouton « Se connecter à Teams ») ;
   4. **test de lecture Teams de bout en bout** (trouver une réunion → source « réseau »).
2. Chaque vérification a un statut **`OK` (vert)**, **`KO` (rouge)** ou **`PENDING` (en attente)**.
3. Le backend calcule **runner connecté** lui-même (battement, `RunnerLiveness`) ; les trois autres
   proviennent du **dernier instantané rapporté par le runner** pour ce poste
   (`POST /api/runner/vigie/readiness`, authentifié par le jeton runner). Sans instantané récent →
   `PENDING`.
4. Le bouton **« Démarrer la Vigie »** n'est **actif que si les 4 vérifications sont `OK`**
   (`canStart == true`). Tant que ce n'est pas le cas, on ne démarre pas.
5. Quand Teams n'est pas connecté (`teamsSignInRequired`), l'assistant affiche le bouton
   **« Se connecter à Teams »** et le mode d'emploi (le runner ouvrira la fenêtre managée pour le
   login une fois — la mécanique d'ouverture/relogin est SF-122-03).

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| `hostId` d'un poste **appartenant à un autre compte** ou inexistant | Accès refusé / introuvable, indiscernables (isolation `user_id`) | 404 |
| Compte sans accès runner (droit `AtelierAccessService`) | Refus | 403 |
| Instantané runner absent/périmé | Les vérifications 2-4 rendent `PENDING`, `canStart == false`, jamais une fausse validation | 200 |
| `POST /runner/vigie/readiness` sans jeton runner valide | Refus générique | 401 |
| `POST /runner/vigie/readiness` avec un jeton runner : l'instantané est rangé pour **le poste du jeton**, jamais un poste nommé dans le corps | 200 |

---

## Critères d'acceptation

- [ ] `GET /api/runner-hosts/{hostId}/vigie/readiness` rend, pour le propriétaire, une check-list de 4
      items chacun avec un statut `OK`/`KO`/`PENDING`, plus `canStart` et `teamsSignInRequired`.
- [ ] `runner connecté` est calculé côté backend via le battement (`RunnerLiveness`).
- [ ] Quand aucun instantané runner n'existe (ou périmé), les vérifications 2-4 sont `PENDING` et
      `canStart` est `false`.
- [ ] Quand un instantané `OK` complet existe **et** le runner est connecté, `canStart` est `true`.
- [ ] Un utilisateur ne peut pas lire la readiness du poste d'un autre compte (404).
- [ ] `POST /api/runner/vigie/readiness` range l'instantané pour le **poste du jeton** (isolation), et
      refuse un jeton absent/invalide (401).
- [ ] Frontend : l'assistant affiche les 4 états (vert/rouge/en attente) avec la charte (aucune
      couleur nouvelle : `--cg-success`, `--cg-error`, statut « en attente »).
- [ ] Frontend : le bouton « Démarrer la Vigie » est **désactivé** tant que tout n'est pas vert, et
      **actif** quand `canStart` est vrai.
- [ ] Frontend : un bouton « Se connecter à Teams » apparaît quand `teamsSignInRequired`.

---

## Périmètre

### Hors scope (explicite)

- La **production et l'envoi** de l'instantané par le runner (exécution réelle des sondes Chrome/Teams
  et du test de lecture, sur la boucle en arrière-plan) → **SF-122-03** (« tourne seule »). SF-122-02
  livre le **contrat** (endpoint d'ingestion) et l'**agrégation** + l'**UI**.
- L'**ouverture effective** de la fenêtre managée pour le login/relogin → SF-122-03.
- Les **messages nommés** des cas d'entreprise (policy, Chrome absent, NTLM) → SF-122-04.
- Le démarrage réel de la boucle de veille (la Vigie utilise déjà F-100 pour la synchro) — ici
  « démarrer » signale que tout est prêt.

---

## Valeurs initiales / défauts

| Réglage | Valeur | Règle |
|---------|--------|-------|
| `app.vigie.readiness.stale-after` | `PT2M` | Un instantané plus vieux → vérifications 2-4 en `PENDING` |
| Statut par défaut des vérifications 2-4 | `PENDING` | Tant qu'aucun instantané frais |
| `canStart` | `false` par défaut | Vrai seulement si les 4 sont `OK` |

---

## Contraintes de validation

| Champ | Obligatoire | Format / Valeurs |
|-------|-------------|------------------|
| `hostId` (chemin) | Oui | UUID ; possession vérifiée (`requireOwned`) |
| corps de `POST /runner/vigie/readiness` | Oui | JSON `{chromeReachable, teamsConnected, teamsSignInRequired, teamsReadTest, detail?}` (booléens) |

---

## Technique

### Endpoint(s)

| Méthode | URL | Auth | Rôle minimum |
|---------|-----|------|-------------|
| GET | `/api/runner-hosts/{hostId}/vigie/readiness` | JWT utilisateur | accès runner + possession du poste |
| POST | `/api/runner/vigie/readiness` | Jeton runner (`X-Runner-Token`) | — (isolation par jeton → `hostId`) |

### Tables impactées

Aucune. L'instantané de readiness est **transitoire** (en mémoire, périmable) — il se re-vérifie à la
demande ; aucune migration Liquibase. *(Décision documentée : pas de persistance d'un état volatil.)*

### Composants (backend)

- `VigieReadinessCheck`, `VigieCheckStatus` (enums), `VigieReadinessSnapshot` (rapport runner).
- `VigieReadinessStore` (@Component, mémoire, périmable), `VigieReadinessService` (@Service).
- `VigieReadinessResponse`, `VigieReadinessItem` (DTO).
- `VigieReadinessController` (utilisateur, `/runner-hosts/{hostId}/vigie/readiness`).
- `RunnerVigieReadinessController` (jeton runner, `/runner/vigie/readiness`).
- `RunnerSecurityConfig` — ajout `POST /runner/vigie/readiness` en `permitAll` (auth faite dans le
  contrôleur, rien posé dans le `SecurityContext`, D9).
- Réutilise : `RunnerLiveness`, `RunnerHostService.requireOwned`, `AtelierAccessService`,
  `CurrentUser`, `RunnerTokenAuthenticator`.

### Composants (frontend)

- `vigie-readiness.models.ts` — types de la check-list.
- `VigieService.readiness(hostId)` — appel de l'endpoint.
- `VigieReadinessComponent` (standalone) — affiche la check-list vert/rouge/en attente, le bouton
  « Se connecter à Teams » conditionnel, et le bouton « Démarrer la Vigie » débloqué seulement si
  `canStart`. Intégré dans `VigieComponent` (bloc `@if (host.id; as hostId)` du détail client).

### Migration Liquibase

- [x] Non applicable

---

## Plan de test

### Tests unitaires (backend)

- [ ] `VigieReadinessServiceTest` — runner déconnecté → tout `PENDING`/`KO`, `canStart` false ;
      instantané complet + runner connecté → tout `OK`, `canStart` true ; instantané périmé → 2-4
      `PENDING` ; `teamsSignInRequired` propagé.

### Tests d'intégration (backend)

- [ ] `GET /api/runner-hosts/{hostId}/vigie/readiness` → 200 pour le propriétaire.
- [ ] `GET …/vigie/readiness` → 404 pour un poste d'un autre compte (**isolation `user_id`**).
- [ ] `POST /api/runner/vigie/readiness` sans jeton → 401 ; avec jeton → range l'instantané, puis le
      `GET` propriétaire le reflète.

### Tests frontend

- [ ] `VigieReadinessComponent` — affiche les 4 états ; « Démarrer » désactivé tant que `canStart`
      est faux, actif quand vrai ; « Se connecter à Teams » visible si `teamsSignInRequired`.
- [ ] `npm run build` vert.

### Isolation utilisateur

- [x] Applicable — `GET` filtre `user_id` via `requireOwned` (404 sinon) ; `POST` runner range par
      `hostId` **du jeton**, jamais d'un champ du corps.

---

## Préoccupations transversales

| Préoccupation | Impact | Composants vérifiés |
|---------------|--------|--------------------|
| **Navigation / routing** | Aucune route ajoutée. L'assistant est **rendu dans le détail client existant** du volet Vigie (`vigie.component.html`, bloc `@if (host.id; as hostId)`), à côté de `app-radar-schedule` et `app-host-mail-address`. Aucun guard, aucune redirection. Chemins de navigation existants (`/vigie`, `/vigie?host=…`) inchangés. | `VigieComponent` (imports + un tag ajouté), `app.routes` **inchangé** |
| **Auth / Principal** | Aucun nouveau type d'auth. `GET` sur la chaîne principale (JWT) comme les endpoints frères ; `POST` runner sur la chaîne `/runner/**` (jeton runner, D9), rien dans le `SecurityContext`. Endpoints utilisant l'auth **inchangés**. | `RunnerSecurityConfig` (une ligne `permitAll`), `VigieReadinessController`, `RunnerVigieReadinessController` |
| **Contexte tenant** | Le poste est résolu par `requireOwned(userId, hostId)` (GET) et par le jeton (POST) — aucun nouveau moyen de résoudre le tenant. | `RunnerHostService`, `RunnerTokenAuthenticator` |
| **Plans / limites** | `AtelierAccessService.requireRunnerAccess()` réutilisé (même gate que les endpoints frères) ; aucun nouveau plan/quota. | `AtelierAccessService` |

---

## Dépendances

### Subfeatures bloquantes

- `SF-122-01` — **Done** (le Chrome managé ; ses états alimenteront l'instantané côté runner en SF-03).

### Questions ouvertes impactées

- Aucune de `docs/OPEN_QUESTIONS.md`.

---

## Notes et décisions

- **Séparation SF-02 / SF-03** conforme au cadrage : SF-02 = *parcours/endpoints/UI* (le backend rend
  un statut par vérification, le frontend affiche et débloque) ; la *production runtime* des signaux
  Chrome/Teams et l'ouverture de la fenêtre = SF-03 (« tourne seule » + relogin).
- **Instantané transitoire** (pas de table) : un état de readiness est volatil et se re-vérifie ;
  le persister introduirait une migration pour une donnée jetable.
