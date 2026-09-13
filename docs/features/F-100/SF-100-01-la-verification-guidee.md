# Mini-spec — F-100 / SF-100-01 — La vérification guidée

> Base : `docs/features/F-99/CADRAGE-le-radar.md` §5 et §12 bis (SF-100-01, correction du PO : **rien
> à déclarer à l'avance, aucune adresse demandée ni détectée**). Le cadrage est validé : cette
> mini-spec l'applique.

## Identifiant

`F-100 / SF-100-01`

## Feature parente

`F-100` — Le Radar : la synchro du soir

## Statut

`done` — livrée le 2026-09-13 (PR #515)

## Date de création

2026-09-13

## Branche Git

`feat/SF-100-01-verification-guidee`

---

## Objectif

Dire, à l'activation d'un client, **ce que le runner voit vraiment** de sa session Microsoft —
session active, conversations, réunions, transcriptions — pendant que l'utilisateur ouvre un fil puis
une réunion passée et sa transcription, **sans rien demander ni détecter comme adresse**, et nommer
pourquoi une case reste vide.

---

## Contexte

Ce qui change d'un client à l'autre, ce ne sont pas les adresses (identiques pour tous, reconnues par
motif) mais **la session et les droits** : la politique du tenant peut désactiver la transcription,
l'accès dépend du rôle dans la réunion. La vérification sert à cela : un « ✗ transcriptions » doit
dire *désactivées ou non produites* ou *accès refusé*, pour que le Radar l'annonce au lieu de laisser
croire que les réunions sont vides.

**Écran** : la vérification est rendue par l'activation d'un client dans la **Vigie** (F-106, pas
encore livrée). Cette sous-feature livre l'API et le runner ; l'écran est **planifié** avec
l'activation F-106 (tracé dans le cadrage F-100 et dans `PRODUCT_SPEC.md`).

---

## Comportement attendu

### Cas nominal

1. L'écran (F-106) appelle `POST /api/radar/hosts/{hostId}/verification` au démarrage, puis toutes les
   quelques secondes pendant que l'utilisateur suit les étapes affichées (ouvrir un fil suivi, puis une
   réunion passée et sa transcription).
2. La gateway vérifie le droit (Teams, comme tout le Radar) et la possession du poste, puis demande au
   runner du poste l'outil **`teams_radar_verify`** (cible : le poste, aucun projet ; délai 20 s ;
   appel journalisé dans l'audit runner).
3. Le runner se rattache au navigateur (liaison F-87), récolte **sans rien déplacer** (coup de coude
   F-88, la vue revient d'elle-même) et répond, **à partir de ce que Teams a servi depuis le
   rattachement** :
   - `session` : ✓ si la liaison est établie et que l'adaptateur lit ce qu'il reçoit ; sinon ✗ avec
     l'état (`BROWSER_NOT_DETECTED`, `TEAMS_NOT_OPEN`, `NOT_SIGNED_IN`, `TEAMS_CHANGED`) et le remède ;
   - `conversations` : ✓ si au moins une conversation ou un message a été lu ;
   - `meetings` : ✓ si au moins une réunion a été lue ;
   - `transcripts` : ✓ si au moins une réplique de transcription a été lue ; sinon la raison :
     `ACCESS_DENIED` (une réponse de transcription refusée 401/403 a été vue), `DISABLED_OR_NOT_PRODUCED`
     (des réunions ont été vues et **aucune** n'annonce de transcription), `NOT_SEEN` (rien encore :
     « ouvrez la transcription d'une réunion passée »).
   Rien d'autre ne remonte : ni titre, ni nom, ni message, ni adresse — **des compteurs et des états**.
4. La gateway **fusionne** avec la vérification en cours du poste : une case cochée **reste cochée**
   (l'utilisateur est passé du fil à la réunion, le fil ne s'affiche plus mais il a été vu) ; la session,
   elle, reflète toujours le dernier appel. Chaque case garde l'instant où elle a été cochée.
5. Le résultat est enregistré sur le poste (`radar_host_settings.verification`, `verified_at`) et
   rendu : les quatre cases, leur phrase en français, `complete` (les quatre cochées).
6. `GET …/verification` relit le dernier état sans appeler le runner ; `DELETE …/verification`
   recommence la vérification (cases décochées).

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Poste d'autrui ou inconnu | `not_found` | 404 |
| Compte sans droit Teams | refus du droit (comme le reste du Radar) | 403 |
| Runner du poste hors ligne | `radar_runner_unavailable` : « Poste hors ligne : lancez le runner, puis recommencez » ; rien n'est enregistré | 409 |
| Volet Teams désactivé sur le poste (`--no-teams`, capacité absente) | `radar_teams_disabled` ; rien n'est enregistré | 409 |
| Délai dépassé / réponse du runner illisible | `radar_runner_unavailable` avec la raison ; rien n'est enregistré | 409 |
| Navigateur non relié, session expirée | **200** : case `session` ✗ avec l'état et le remède (c'est un résultat de vérification, pas une panne) | 200 |
| `GET` sans vérification faite | 200, quatre cases non cochées, `verifiedAt` nul | 200 |

---

## Critères d'acceptation

- [ ] Migration `086-radar-host-settings.xml` : `radar_host_settings` (`user_id`, `host_id` uniques
      ensemble, `verification`, `verified_at`), PostgreSQL et H2, rollback ; `ddl-auto: validate` passe.
- [ ] `POST /verification` appelle `teams_radar_verify` sur **le poste du chemin**, après vérification de
      possession ; le résultat est fusionné (case cochée reste cochée, session = dernier appel) et
      enregistré.
- [ ] Runner hors ligne, volet Teams désactivé, délai dépassé, réponse illisible → 409 nommé, **rien
      n'est enregistré**.
- [ ] Session non reliée → 200 avec `session` ✗, état et remède.
- [ ] Transcriptions ✗ : `ACCESS_DENIED` si une réponse de transcription 401/403 a été vue,
      `DISABLED_OR_NOT_PRODUCED` si des réunions vues n'annoncent aucune transcription, `NOT_SEEN` sinon.
- [ ] La réponse du runner ne porte que des compteurs, des états et des phrases : ni titre, ni nom, ni
      adresse.
- [ ] `DELETE /verification` remet les cases à zéro ; `GET` relit sans appeler le runner.
- [ ] **Isolation** : Bob ne vérifie ni ne lit la vérification du poste d'Alice (404) ; la vérification du
      poste A d'Alice n'apparaît pas sur son poste B ; la purge du Radar d'un poste efface ses réglages.

---

## Périmètre

### Hors scope (explicite)

- L'écran de la vérification (activation F-106).
- L'activation elle-même et la planification (SF-100-02).
- La reconnaissance par motif de SharePoint / OneDrive et l'observation des cadres (SF-100-03) : la
  vérification en profitera sans changement.
- Toute navigation : la vérification **observe** ce que l'utilisateur ouvre lui-même.

---

## Valeurs initiales

| Champ | Valeur initiale | Règle |
|-------|----------------|-------|
| `verification` | `NULL` | JSON des quatre cases, écrit à chaque `POST` |
| `verified_at` | `NULL` | instant du dernier `POST` réussi |
| délai de l'appel runner | 20 s | celui des outils Teams qui observent |

---

## Contraintes de validation

| Champ | Obligatoire | Règle |
|-------|-------------|-------|
| `hostId` (chemin) | Oui | UUID d'un poste possédé |
| réponse runner | — | JSON objet avec `checks` ; tout autre forme = illisible (409) ; compteurs bornés à 1 000 000 ; phrases tronquées à 500 caractères |
| `verification` stocké | — | ≤ 8 000 caractères |

---

## Technique

### Endpoint(s)

| Méthode | URL | Auth | Rôle minimum |
|---------|-----|------|-------------|
| POST | `/api/radar/hosts/{hostId}/verification` | Oui | droit Teams (Radar) |
| GET | `/api/radar/hosts/{hostId}/verification` | Oui | droit Teams (Radar) |
| DELETE | `/api/radar/hosts/{hostId}/verification` | Oui | droit Teams (Radar) |

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| `radar_host_settings` | INSERT / SELECT / UPDATE / DELETE | nouvelle (086) ; une ligne par poste |

### Migration Liquibase

- [x] Oui — `086-radar-host-settings.xml`

### Composants

| Composant | Rôle |
|-----------|------|
| `radar/sync/RadarHostSettings` (+ repository) | réglages Radar d'un poste (vérification ici ; planification en SF-100-02) |
| `radar/sync/RadarRunnerCalls` | appels runner du Radar, cible poste, audit |
| `radar/sync/RadarVerificationService` | appel, lecture, fusion, enregistrement |
| `radar/sync/RadarSyncController` | routes de la synchro du soir |
| `radar/RadarExceptionHandler` | `radar_runner_unavailable`, `radar_teams_disabled` (409) |
| `radar/RadarPurgeService` | purge des réglages du poste et du compte |
| runner `teams/RadarTools` | `teams_radar_verify` |
| runner `teams/TeamsTools` | aiguille `teams_radar_*` (hors catalogue de l'agent) |
| runner `teams/NetworkObserver` | compte les réponses refusées (401/403) par nature, sans lire leur corps |

### Composants Angular

- Aucun dans cette sous-feature (écran : activation F-106).

---

## Plan de test

### Tests unitaires

- [ ] runner `RadarVerifyTest` (Teams de papier) — conversations/réunions/transcriptions ✓ depuis les
      échantillons ; transcription refusée 403 → `ACCESS_DENIED` ; réunions sans transcription annoncée →
      `DISABLED_OR_NOT_PRODUCED` ; rien → `NOT_SEEN` ; navigateur absent → `session` ✗ avec remède ;
      volet désactivé → ✗ ; **aucun titre ni nom** dans la réponse.
- [ ] runner `NetworkObserverTest` — une réponse 403 est comptée refusée et son corps n'est pas demandé.
- [ ] backend `RadarVerificationMergeTest` — fusion (cochée reste cochée, session = dernier appel,
      instants conservés), réponse illisible refusée, bornes.

### Tests d'intégration

- [ ] `RadarVerificationApiIntegrationTest` (runner simulé) — nominal ; fusion sur deux appels ; runner
      hors ligne / capacité absente / délai → 409 et rien d'enregistré ; `GET` sans vérification ;
      `DELETE` ; appel journalisé.

### Isolation utilisateur

- [x] Applicable — Bob → 404 sur le poste d'Alice (POST, GET, DELETE) et le runner n'est pas appelé ;
      poste B d'Alice sans la vérification du poste A ; purge du poste A efface ses réglages, pas ceux de B.

---

## Dépendances

### Subfeatures bloquantes

- SF-100-00 — `done`. F-99 (registre, purge) — `done`. F-87/F-88 (liaison, récolte) — `done`.

### Questions ouvertes impactées

- Aucune.

---

## Préoccupations transversales

- **Contexte tenant : oui.** Composants impactés : `RadarScopeResolver` (réutilisé, non modifié),
  `RadarVerificationService` (toute lecture/écriture à `user_id` + `host_id`), `RadarPurgeService`
  (purge des réglages), `RadarRunnerCalls` (cible = le poste du périmètre, jamais un identifiant venu du
  corps).
- **Plans / limites : oui (réemploi).** Composants : `TeamsAccessService.requireAccess` (droit Teams
  provisoire du Radar, inchangé) ; `RunnerCallDispatcher` (capacité `teams` exigée pour `teams_*`,
  inchangé).
- **Auth / Principal : non.** **Navigation / routing : non** (aucun écran).

---

## Notes et décisions

- **Cases « collantes »** : l'utilisateur ouvre les éléments l'un après l'autre ; exiger que tout soit
  visible au même instant rendrait la vérification impossible. La session, elle, n'est jamais collante.
- **Des compteurs, pas des objets** : la vérification n'a pas à faire remonter ce qu'elle a vu, seulement
  qu'elle l'a vu. Réversible.
- **Outils `teams_radar_*` hors du catalogue de l'agent** : ce sont des appels de la gateway, pas des
  outils qu'un tour de conversation peut appeler.
