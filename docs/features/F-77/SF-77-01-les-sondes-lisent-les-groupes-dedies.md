# Mini-spec — F-77 / SF-77-01 — Les sondes lisent les groupes dédiés, le courrier ne peut plus les bloquer

## Identifiant

`F-77 / SF-77-01`

## Feature parente

`F-77` — La santé du service ne dépend plus du courrier

## Statut

`ready`

## Date de création

2026-09-12

## Branche Git

`feat/SF-77-01-sondes-groupes-dedies`

---

## Objectif

Porter dans le dépôt le correctif appliqué à la main sur le cluster le 2026-09-12 — sondes sur les
groupes `liveness`/`readiness`, délai d'attente à 5 s — et **borner l'indicateur de courrier** pour
qu'il ne puisse plus jamais faire attendre qui que ce soit.

---

## Comportement attendu

### Cas nominal

1. `GET /api/actuator/health/liveness` rend `200 {"status":"UP","components":{"livenessState":…}}`.
   Un et un seul composant : l'état du processus. Ni base, ni courrier, ni rien de joignable par le
   réseau.
2. `GET /api/actuator/health/readiness` rend `200` avec **exactement** `readinessState` et `db`.
   Base injoignable → `503` → Kubernetes retire le pod du Service **sans le tuer** : il reviendra
   quand la base reviendra.
3. `GET /api/actuator/health` (l'agrégat) continue de porter **tout**, courrier compris. Son
   contrat ne change pas — c'est le tableau de bord, pas la sonde.
4. Le SMTP ne répond pas : `MailHealthIndicator` rend `DOWN` **en 5 s au plus**, jamais en 70.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|---|---|---|
| SMTP lent ou injoignable | `liveness` et `readiness` restent `UP` ; l'agrégat passe `DOWN` en ≤ 5 s ; **aucun pod n'est tué** | 200 / 200 / 503 |
| Base injoignable | `liveness` reste `UP` (le processus vit) ; `readiness` passe `DOWN` → pod retiré du trafic, **pas redémarré** | 200 / 503 |
| Démarrage encore en cours | `startupProbe` échoue et **suspend** la sonde de vivacité (30 × 10 s = 300 s de marge) | 503 |
| Base ET SMTP debout mais réponse en 800 ms | Sonde verte : `timeoutSeconds: 5` laisse la marge que `1` ne laissait pas | 200 |
| Un contributeur de groupe disparaît (renommage, dépendance retirée) | Le contexte Spring **refuse de démarrer** (`validate-group-membership`, actif par défaut) — la panne est visible au build, pas à 09 h 07 en production | — |

---

## Critères d'acceptation

- [ ] `k8s/base/backend/deployment.yaml` : `livenessProbe` → `/api/actuator/health/liveness`.
- [ ] `k8s/base/backend/deployment.yaml` : `readinessProbe` → `/api/actuator/health/readiness`.
- [ ] `k8s/base/backend/deployment.yaml` : `startupProbe` → `/api/actuator/health/liveness` (voir A1).
- [ ] Les **trois** sondes portent `timeoutSeconds: 5`.
- [ ] Aucune autre valeur des sondes ne change (`initialDelaySeconds`, `periodSeconds`,
      `failureThreshold` identiques à l'état porté par `main` avant cette PR).
- [ ] `application.yml` pose **explicitement** `management.endpoint.health.probes.enabled: true`,
      `management.health.livenessstate.enabled: true`, `management.health.readinessstate.enabled: true`.
- [ ] `application.yml` pose **explicitement** `group.liveness.include: livenessState` et
      `group.readiness.include: readinessState,db` — aucun défaut de Spring Boot n'est supposé.
- [ ] `application.yml` pose `connectiontimeout`, `timeout` et `writetimeout` SMTP à 5 000 ms,
      surchargeables par variable d'environnement.
- [ ] Un test d'intégration prouve que `readiness` contient `db` et **ne contient pas** `mail`.
- [ ] Un test d'intégration prouve que `liveness` ne contient **ni** `db` **ni** `mail`.
- [ ] Un test relit `k8s/base/backend/deployment.yaml` et échoue si une sonde repointe l'agrégat ou
      perd son `timeoutSeconds`.
- [ ] Aucun endpoint, écran, DTO ou table ne change : ce sont des correctifs.

---

## Périmètre

### Hors scope (explicite)

- Changer de fournisseur de courrier (dit hors périmètre par PRODUCT_SPEC).
- L'alerte qui aurait prévenu (idem).
- **Toucher au cluster.** Aucun `kubectl`, aucun déploiement : le correctif y vit déjà, cette PR le
  rend seulement reproductible.
- Les sondes du frontend et du site corporate — non impliquées dans la panne, inchangées.
- Le chemin `additional-path` (`/livez`, `/readyz`) que Spring ajoute aux groupes auto-configurés :
  définir les groupes à la main le retire, et **personne ne s'en sert** (aucune occurrence dans
  `k8s/`, `.github/`, ni dans le code).

---

## Valeurs initiales

Sans objet : aucune entité, aucune ressource créée.

---

## Contraintes de validation

| Réglage | Valeur | Règle |
|---|---|---|
| `timeoutSeconds` des 3 sondes | `5` | Imposé par PRODUCT_SPEC. Doit rester **strictement inférieur** au `periodSeconds` le plus court (10 s), sinon deux sondes se chevauchent. |
| `group.readiness.include` | `readinessState,db` | La base **doit** y être (un pod sans base ne sert rien). Le courrier n'y sera **jamais**. |
| `group.liveness.include` | `livenessState` | Rien de joignable par le réseau : sinon on retombe sur la panne, avec un autre indicateur. |
| `mail.smtp.*timeout` | `5000` ms | Borné, surchargeable par `MAIL_*_TIMEOUT_MS`. Jamais vide : vide = attente **infinie** en JavaMail. |

---

## Technique

### Endpoint(s)

| Méthode | URL | Auth | Rôle minimum |
|---|---|---|---|
| GET | `/api/actuator/health/liveness` | Non (déjà `permitAll` via `/actuator/health/**`) | — |
| GET | `/api/actuator/health/readiness` | Non (idem) | — |

Aucun endpoint créé ni modifié : ces chemins sont rendus par l'actuator et étaient **déjà** ouverts
par `SecurityConfig`. Seule leur **composition** est désormais écrite explicitement.

### Tables impactées

Aucune.

### Migration Liquibase

- [x] Non applicable — aucun changement de schéma.

### Composants Angular

Aucun.

---

## Plan de test

### Tests unitaires

- [ ] `KubernetesProbeManifestTest` — les trois sondes de `k8s/base/backend/deployment.yaml`
      pointent le chemin attendu et portent `timeoutSeconds: 5`.
- [ ] `KubernetesProbeManifestTest` — **aucune** sonde du backend ne pointe l'agrégat
      `/api/actuator/health`.
- [ ] `KubernetesProbeManifestTest` — `timeoutSeconds < periodSeconds` pour chaque sonde.

### Tests d'intégration

- [ ] `GET /api/actuator/health/liveness` → 200, composants = `{livenessState}` exactement.
- [ ] `GET /api/actuator/health/readiness` → 200, composants = `{readinessState, db}` exactement.
- [ ] `mail` n'apparaît dans **aucune** des deux réponses.
- [ ] Les trois propriétés de délai SMTP valent 5 000 dans l'`Environment` chargé.

### Isolation workspace / `user_id`

- [x] Non applicable — raison : la subfeature ne lit ni n'écrit aucune donnée utilisateur. Les deux
      chemins touchés sont des sondes d'infrastructure, sans porteur de données, déjà publiques, et
      qui ne rendent que des états techniques (`UP`/`DOWN`) — jamais un contenu client.

---

## Dépendances

### Subfeatures bloquantes

Aucune.

### Questions ouvertes impactées

Aucune.

---

## Notes et décisions

### A1 — La sonde de démarrage lit `liveness`, pas `readiness` (réversible)

Kubernetes **tue** le conteneur quand la sonde de démarrage échoue : elle pose donc la même
question que la vivacité — « le processus a-t-il fini de démarrer ? » — et non « peut-il recevoir
du trafic ? ». La brancher sur `readiness` ferait tuer, au bout de 300 s, un pod dont seule la
**base** tarde à répondre : exactement la faute de raisonnement qui a causé la panne, déplacée d'un
indicateur à l'autre. `readinessProbe` suffit à retenir le trafic pendant le démarrage.
**Alternative écartée** : `startupProbe` → `/readiness`, qui n'admettrait un pod que lorsqu'il peut
servir. Réversible : une ligne de manifeste.

### A2 — Les groupes sont écrits à la main, pas hérités

Le bytecode de `AvailabilityProbesHealthEndpointGroups` (Spring Boot 3.5.0) donne `liveness →
{livenessState}` et `readiness → {readinessState}`. Deux raisons de ne pas s'en contenter : la base
**manque** dans `readiness`, et un défaut qui change de version rouvrirait la panne sans qu'aucune
ligne du dépôt n'ait bougé. Écrits à la main, les groupes sont en plus **validés au démarrage**
(`validate-group-membership`) : un contributeur disparu fait échouer le contexte, donc les tests.

### A3 — Le bloc `management` disparaît de `application-staging.yml`

Il y dupliquait mot pour mot celui de `application.yml`, à `probes.enabled` près. Laisser deux
copies, c'est garantir qu'un jour l'une des deux sera corrigée seule. La configuration des sondes
vit désormais à **un** endroit, valable pour tous les profils — y compris `dev` et `test`, où elle
est donc réellement testée.
