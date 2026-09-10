# Mini-spec — F-59 / SF-59-01 — Empaqueter `px` et le servir depuis la gateway

## Identifiant

`F-59 / SF-59-01`

## Feature parente

`F-59` — Le relais servi par la gateway

## Statut

`ready`

## Date de création

2026-09-10

## Branche Git

`feat/SF-59-01-servir-le-relais`

---

## Objectif

> Que le relais `px` se télécharge **depuis le domaine de la gateway** — avec sa notice MIT — pour
> l'utilisateur dont le poste n'atteint pas GitHub.

---

## Comportement attendu

### Cas nominal

**Au build de l'image** (`runner/package-relay.sh`, appelé par `backend/Dockerfile`), pour chacune
des trois plateformes retenues :

1. téléchargement de l'archive **amont figée** — `px v0.11.0`, dont l'URL contient la version, jamais
   `latest` ;
2. **vérification de la somme SHA-256** publiée à côté de l'archive par le projet ;
3. **vérification de la notice** : l'archive contient `LICENSE.txt` à sa racine, et ce fichier porte
   la notice MIT (`MIT License` + la clause « above copyright notice ») ;
4. extraction de cette notice vers `px-LICENSE.txt`, servie à part ;
5. l'archive est copiée **verbatim**, sous son nom amont.

**À l'exécution** :

| Route | Sert | Nom du fichier |
|---|---|---|
| `GET /runner/relay/windows` | l'archive Windows amd64 | `px-v0.11.0-windows-amd64.zip` |
| `GET /runner/relay/macos-aarch64` | l'archive macOS Apple Silicon | `px-v0.11.0-mac-arm64.tar.gz` |
| `GET /runner/relay/linux-x64` | l'archive Linux glibc x86_64 | `px-v0.11.0-linux-glibc-x86_64.tar.gz` |
| `GET /runner/relay/license` | la notice MIT, **en clair, dans le navigateur** (`text/plain`, `inline`) | `px-LICENSE.txt` |
| `GET /runner/relay/formats` | ce que cette gateway sert réellement, plus la version servie | — |

Ces routes sont **publiques**, servies par la chaîne de sécurité `/runner/**` existante
(`RunnerSecurityConfig`), déclarées **une par une** — jamais par un joker qui couvrirait d'avance
toute route future du préfixe.

Elles **n'enlèvent rien** : `/runner/download`, `/runner/download/windows`, `/runner/download/macos-*`
et `/runner/download/formats` sont inchangés.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Archive amont tronquée, ou somme SHA-256 différente de celle publiée | **Le build échoue.** Aucune image n'est produite. | — (build) |
| L'archive amont ne contient pas `LICENSE.txt`, ou son contenu n'est pas la notice MIT | **Le build échoue.** Redistribuer sans la notice n'est pas une option. | — (build) |
| Plateforme inconnue passée au script | Le build échoue, en nommant les plateformes acceptées. | — (build) |
| Chemin non configuré, fichier absent ou illisible | `404` avec le code **`runner_relay_unavailable`** et un message qui **nomme la plateforme** | 404 |
| **La notice est absente** alors que l'archive est là | `404` avec le code **`runner_relay_license_unavailable`** sur **toutes** les routes d'archive : sans notice, rien n'est servi | 404 |
| `GET /runner/relay/license` alors que la notice est absente | `404` `runner_relay_license_unavailable` | 404 |
| Requête sur une route `/runner/relay/*` inexistante | `404` de routage, jamais une erreur serveur | 404 |

Aucune stacktrace n'entre dans une réponse ; un format absent est un **état de déploiement**, pas une
panne.

---

## Critères d'acceptation

- [ ] `GET /runner/relay/windows` sert l'archive Windows amd64, sans authentification, sous son nom
      amont (version comprise), avec `Content-Length` exact.
- [ ] `GET /runner/relay/macos-aarch64` et `GET /runner/relay/linux-x64` font de même, chacune avec
      **son** contenu — deux routes distinctes, jamais un `?platform=`.
- [ ] `GET /runner/relay/license` répond `200 text/plain` avec la notice MIT, **affichable** dans le
      navigateur (pas de `attachment`).
- [ ] Une archive absente répond `404` `runner_relay_unavailable`, le message nommant la plateforme,
      **sans emporter** les autres routes ni aucun format de `/runner/download/*`.
- [ ] **Notice absente ⇒ aucune archive servie** : les trois routes répondent `404`
      `runner_relay_license_unavailable`, et `/runner/relay/formats` annonce tout à `false`.
- [ ] `GET /runner/relay/formats` reflète la présence **fichier par fichier** et cite la version
      servie ; l'écran peut donc masquer un lien mort sans déclencher 21 Mo.
- [ ] Le code d'erreur des archives est **distinct** de `runner_jar_unavailable` et de
      `runner_package_unavailable` : trois incidents d'exploitation différents.
- [ ] `runner/package-relay.sh` échoue — et ne laisse **aucune** archive derrière lui — si la somme
      SHA-256 ne correspond pas, ou si la notice manque.
- [ ] Aucune route `/runner/download/*` n'est modifiée (tests F-38 / F-44 verts sans retouche).

---

## Périmètre

### Hors scope (explicite)

- **Empaqueter `cntlm`** : GPL, non redistribué (le cadrage tranche, l'assistant garde le lien).
- **Fabriquer un `px` que le projet ne publie pas** (macOS Intel, musl, Linux aarch64).
- **Choisir la plateforme à la place de l'utilisateur** : c'est l'affaire de SF-59-02, et le
  navigateur ne sait pas distinguer un Mac Intel d'un Mac Apple Silicon (constaté en F-44).
- **Mettre à jour `px` automatiquement** : la version est figée dans le script, changée par un commit.
- **Déployer** : la subfeature s'arrête au merge.

---

## Valeurs initiales

Sans objet — aucune entité, aucune table, aucun état persistant.

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format / Valeurs autorisées | Unicité | Normalisation |
|-------|-------------|-------------|----------------------------|---------|---------------|
| `app.runner.proxy-relay.windows-path` | Non | — | chemin filesystem ; vide ⇒ format non servi | — | `trim()` |
| `app.runner.proxy-relay.macos-aarch64-path` | Non | — | idem | — | `trim()` |
| `app.runner.proxy-relay.linux-x64-path` | Non | — | idem | — | `trim()` |
| `app.runner.proxy-relay.license-path` | Non | — | chemin de la notice ; vide ⇒ **rien n'est servi** | — | `trim()` |
| `app.runner.proxy-relay.version` | Non | 32 | version amont citée dans `/formats` (défaut `v0.11.0`) | — | `trim()` |
| plateforme (argument du script) | Oui | — | `windows` \| `macos-aarch64` \| `linux-x64` | — | — |

Notes :
- Tous les chemins sont **vides par défaut** : une gateway déployée avant F-59 continue de
  fonctionner, l'écran masquant simplement le lien.
- La version est une **chaîne d'affichage** ; elle ne pilote aucune résolution de fichier à
  l'exécution (le fichier est celui que le déploiement désigne).

---

## Technique

### Endpoint(s)

| Méthode | URL | Auth | Rôle minimum |
|---------|-----|------|-------------|
| GET | `/api/runner/relay/windows` | Non (public) | — |
| GET | `/api/runner/relay/macos-aarch64` | Non (public) | — |
| GET | `/api/runner/relay/linux-x64` | Non (public) | — |
| GET | `/api/runner/relay/license` | Non (public) | — |
| GET | `/api/runner/relay/formats` | Non (public) | — |

### Tables impactées

Aucune.

### Migration Liquibase

- [ ] Oui
- [x] **Non applicable** — aucune donnée persistée.

### Composants Angular (si applicable)

Aucun — SF-59-02 s'en charge.

### Fichiers

- `runner/package-relay.sh` (nouveau)
- `backend/Dockerfile` (étape `runner-build` + étape `run`)
- `backend/src/main/java/fr/claudegateway/runner/RunnerProxyRelayController.java` (nouveau)
- `backend/src/main/java/fr/claudegateway/runner/RunnerSecurityConfig.java` (cinq routes déclarées)
- `backend/src/main/resources/application.yml` (`app.runner.proxy-relay.*`)
- `k8s/base/backend/configmap.yaml` (chemins dans l'image)

---

## Plan de test

### Tests unitaires

- [ ] Sans objet côté service — le contrôleur n'a **aucune** logique métier : il lit un fichier et le
      sert. La logique testable est celle du **script de build**, couverte par ses propres garde-fous
      (le build échoue) et par les tests d'intégration ci-dessous pour la partie servie.

### Tests d'intégration

- [ ] `GET /runner/relay/windows` → `200`, `Content-Disposition` au nom amont, contenu exact.
- [ ] `GET /runner/relay/macos-aarch64` → `200`, contenu **différent** de celui de Windows.
- [ ] `GET /runner/relay/linux-x64` → `200`, contenu propre.
- [ ] `GET /runner/relay/license` → `200`, `text/plain`, **pas** de `attachment`, notice lisible.
- [ ] Archive supprimée → `404` `runner_relay_unavailable`, message nommant la plateforme, les autres
      routes toujours `200`.
- [ ] **Notice supprimée** → les trois archives répondent `404` `runner_relay_license_unavailable`, et
      `/runner/relay/formats` annonce `windows/macosAarch64/linuxX64/license` à `false`.
- [ ] `/runner/relay/formats` → présence fichier par fichier + `version` citée.
- [ ] Les routes de `/runner/download/*` répondent toujours (non-régression F-38 / F-44).

### Isolation workspace

- [x] **Non applicable** — raison : aucune donnée utilisateur n'est lue ni écrite. Les archives sont
      des binaires tiers publics, servis comme le `.jar` du runner depuis SF-38-03.

---

## Dépendances

### Subfeatures bloquantes

- `F-44 / SF-44-02` — statut : done (le mécanisme copié)
- `F-38 / SF-38-01` — statut : done (chaîne de sécurité `/runner/**`)

### Questions ouvertes impactées

- [ ] Aucune.

---

## Préoccupations transversales

| Préoccupation | Concernée | Composants impactés / vérification |
|---|---|---|
| Auth / Principal | **Oui, en lecture** | `RunnerSecurityConfig` seulement : cinq `permitAll` **nommés** ajoutés dans la chaîne `/runner/**` déjà existante. La chaîne principale n'est pas touchée, aucun filtre utilisateur n'est traversé, `anyRequest().denyAll()` reste le mot de la fin. Non-régression : les tests des routes `/runner/pair`, `/runner/ws`, `/runner/poll|send|disconnect` et `/runner/download/*` restent verts. |
| Contexte tenant | Non | Aucun accès aux données. |
| Plans / limites | Non | Aucun quota, aucun gate. |
| Navigation / routing | Non | Aucune route frontend. |

---

## Notes et décisions

**D1 — Une route par plateforme, jamais `?platform=`.** Même raison qu'en SF-44-03 : le cache et les
liens directs resteraient ambigus, et une valeur inconnue devrait être arbitrée.

**D2 — Deux codes d'erreur, pas un.** `runner_relay_unavailable` (l'archive n'est pas empaquetée) et
`runner_relay_license_unavailable` (la notice manque) désignent deux incidents opposés : le second
n'est pas un défaut d'empaquetage mais un **refus délibéré de servir**. Les confondre ferait chercher
au mauvais endroit — et, pire, ferait croire à un simple oubli.

**D3 — Sans notice, rien n'est servi.** C'est la seule façon de tenir la condition MIT *par
construction* plutôt que par discipline. Le prix est nul en pratique (la notice est dans la même
image que les archives), et le bénéfice est catégorique.

**D4 — Archive verbatim.** L'utilisateur reçoit octet pour octet ce que le projet publie, somme
SHA-256 comprise. C'est aussi ce qui permet à un client méfiant de vérifier lui-même auprès de
l'amont.

**D6 — `RunnerProxyRelayController`, pas `RunnerRelayController`.** Ce dernier nom est déjà pris par
le relais **interne entre pods** (`fr.claudegateway.runner.relay.RunnerRelayController`, SF-38-12) —
et Spring refuse deux beans de même nom simple, ce que le contexte de test a signalé immédiatement.
Le nom retenu dit ce dont il s'agit : le relais **proxy**, celui du poste client.

**D5 — `app.runner.proxy-relay.*`, pas `app.runner.relay.*`.** Ce dernier existe déjà et désigne le
**relais interne entre pods** (SF-38-12, porteur d'un secret). Deux choses sans rapport ne partagent
pas un préfixe de configuration.
