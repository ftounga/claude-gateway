# Mini-spec — F-38 / SF-38-03 — Runner : connexion

## Identifiant
`F-38 / SF-38-03`

## Feature parente
`F-38` — Exécution sur machine connectée (runner local)

## Statut
`in-review`

## Date de création
2026-08-29

## Branche Git
`feat/SF-38-03-runner-connexion`

---

## Objectif

> Livrer le **runner** lui-même — un module Maven `runner/` produisant un fat-jar autonome
> `claude-runner.jar` (Java 21) qui s'appaire, ouvre une connexion **sortante** WSS vers la gateway,
> maintient un heartbeat, affiche son activité en clair et se coupe proprement au `Ctrl-C` — plus un
> endpoint de téléchargement du jar côté gateway. **Aucun outil ni exécution** (SF-38-04+).

---

## Comportement attendu

### Cas nominal

1. L'opérateur lance le runner :
   `java -jar claude-runner.jar --gateway https://portal.ng-itconsulting.com/api --workspace /chemin/projet --code AB2C3D4E --label "poste-dev"`.
   Les mêmes valeurs sont lisibles depuis l'environnement (`CLAUDE_RUNNER_GATEWAY`,
   `CLAUDE_RUNNER_WORKSPACE`, `CLAUDE_RUNNER_CODE`, `CLAUDE_RUNNER_LABEL`) ; l'argument CLI prime.
2. **Appairage** : si aucun jeton n'est déjà stocké pour cette gateway+workspace, le runner échange
   son code contre un jeton via `POST {gateway}/runner/pair` (`{code,label}` → `{token,workspaceId,expiresAt}`).
   Le jeton est **persisté localement** (fichier `.claude-runner/token.json` sous la racine du
   workspace, repli `~/.claude-runner/`, permissions `600`) pour ne pas réappairer à chaque lancement.
   Si un jeton valide (non expiré) est déjà présent, l'appairage est **sauté** ; `--code` devient
   alors facultatif.
3. **Connexion** : le runner ouvre une connexion sortante WSS vers `{gateway-ws}/runner/ws?token=<jeton>`
   (le schéma `http(s)` de `--gateway` est converti en `ws(s)`). Le handshake est authentifié par le
   jeton (SF-38-02). À l'établissement, la console affiche « runner connecté » en clair.
4. **Heartbeat** : toutes les `--heartbeat-interval` (défaut 30 s), le runner envoie
   `{"type":"heartbeat"}` et journalise l'ack `{"type":"heartbeat_ack"}`.
5. **Proxy & truststore d'entreprise** : le runner honore `HTTPS_PROXY`/`HTTP_PROXY`/`NO_PROXY`
   (et variantes minuscules) pour l'appel HTTP d'appairage **et** pour l'ouverture WSS ; il honore le
   truststore JVM standard `-Djavax.net.ssl.trustStore(/Password/Type)`.
6. **Reconnexion** : à la perte de socket, le runner retente la connexion avec un backoff plafonné
   (jusqu'à l'arrêt), en réutilisant le jeton persisté.
7. **Ctrl-C** : `SIGINT` déclenche un arrêt propre — fermeture WSS (close 1000), arrêt de
   l'ordonnanceur heartbeat, message « arrêt » en console, code de sortie 0.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|----------------------|
| `--gateway` absent | Message clair, sortie code 2 (usage) |
| `--workspace` absent ou chemin inexistant/non-dossier | Message clair, sortie code 2 |
| `--code` absent **et** aucun jeton stocké valide | Message clair (« code d'appairage requis »), sortie code 2 |
| `POST /runner/pair` répond 401 (code invalide/expiré/déjà consommé) | Message générique « appairage refusé », sortie code 3 ; aucun jeton écrit |
| `POST /runner/pair` répond 4xx/5xx autre | Message avec le code HTTP, sortie code 3 |
| Handshake WSS refusé (401 : jeton révoqué/expiré) | Message clair ; si un jeton stocké est refusé, il est **effacé** et, si `--code` fourni, réappairage tenté ; sinon sortie code 4 |
| Perte de connexion en cours de vie | Reconnexion avec backoff (n'arrête pas le process) |
| Jeton stocké illisible/corrompu | Ignoré (traité comme absent), réappairage si `--code` fourni |

---

## Critères d'acceptation

- [x] Le module `runner/` se construit en fat-jar autonome `runner/target/claude-runner.jar`
      (toutes dépendances incluses) par une commande Maven documentée, **sans** toucher au build du backend.
- [x] Le parsing de configuration lit CLI + env, applique la priorité CLI > env, et rejette les
      configurations invalides avec un code de sortie non nul (couvert par tests unitaires).
- [x] L'URL WSS est dérivée de `--gateway` par conversion de schéma (`https`→`wss`, `http`→`ws`) et
      suffixe `/runner/ws` + `?token=` (couvert par tests unitaires).
- [x] La résolution de proxy honore `HTTPS_PROXY`/`HTTP_PROXY`/`NO_PROXY` et sélectionne (ou non) un
      proxy selon l'hôte cible (couvert par tests unitaires).
- [x] Le jeton est persisté après appairage et **rechargé** au lancement suivant (pas de réappairage
      si le jeton stocké est encore valide) ; fichier en permissions restreintes (couvert par tests unitaires).
- [x] Un `Ctrl-C` ferme la socket et arrête le process proprement (code 0) — vérifié par revue + smoke manuel.
- [x] La console affiche en clair : cible gateway, workspace, état de connexion, heartbeats, arrêt.
- [x] Un endpoint `GET /runner/download` est exposé sur la **chaîne de sécurité dédiée `/runner/**`**
      et sert le jar quand il est disponible ; renvoie 404 explicite sinon (couvert par test d'intégration).
- [x] La chaîne principale et les endpoints utilisateurs restent inchangés (test de non-régression
      `/me`, `/workspaces`, `/runner/pair`).

---

## Périmètre

### Hors scope (explicite)
- Outils fichiers `read`/`write`/`list`/`search` (**SF-38-04**).
- Routage des outils par la boucle d'agent / cible `RUNNER` backend (**SF-38-05**).
- Écran d'appairage et de téléchargement frontend (**SF-38-06**).
- Outil `bash` et exécution (**SF-38-07**), garde-fous/audit (**SF-38-08**), repli long-polling
  (**SF-38-09**), exclusions `.runnerignore` (**SF-38-10**).
- **Câblage build→ressource servie** : le jar n'est **pas** empaqueté automatiquement dans l'image du
  backend (voir Notes/décisions). L'endpoint sert un jar déposé à un chemin configurable.

---

## Valeurs initiales

| Champ | Valeur initiale | Règle |
|-------|-----------------|-------|
| `--heartbeat-interval` | 30 s | configurable CLI/env |
| backoff reconnexion | 1 s → 30 s (plafonné) | interne |
| fichier jeton | `<workspace>/.claude-runner/token.json` | repli `~/.claude-runner/token.json` ; perms 600 |
| `app.runner.jar-path` (backend) | vide (endpoint → 404) | chemin filesystem du jar à servir |

---

## Contraintes de validation

| Champ | Obligatoire | Format / Valeurs | Normalisation |
|-------|-------------|------------------|---------------|
| `--gateway` | Oui | URL absolue http(s) | trim ; suppression du `/` final |
| `--workspace` | Oui | chemin d'un dossier existant | résolu en absolu |
| `--code` | Conditionnel | non vide si pas de jeton stocké | trim, upper |
| `--label` | Non | ≤ 100 car. | trim |
| `--heartbeat-interval` | Non | entier > 0 (secondes) | — |

---

## Technique

### Endpoint(s) — backend

| Méthode | URL | Auth | Chaîne | Rôle min |
|---------|-----|------|--------|----------|
| GET | `/runner/download` | aucune (binaire client public) | **dédiée `/runner/**` @Order(1)** | — |

### Module runner (nouveau, hors backend)

| Élément | Rôle |
|---------|------|
| `runner/pom.xml` | module Maven autonome, fat-jar (shade), main-class, Java 21 |
| `RunnerConfig` | parsing CLI + env, validation, dérivation URL WSS |
| `ProxyResolver` | sélection proxy depuis `*_PROXY`/`NO_PROXY` selon l'hôte |
| `TokenStore` | persistance/lecture du jeton (fichier JSON, perms 600) |
| `PairingClient` | `POST /runner/pair` (HTTP, proxy-aware) |
| `RunnerConnection` | WSS sortant, heartbeat, reconnexion, arrêt propre |
| `RunnerMain` | orchestration + hook `Ctrl-C` + affichage console |

### Tables impactées
Aucune (pas de migration).

### Migration Liquibase
- [x] Non applicable

### Composants Angular
Aucun (SF-38-06).

---

## Plan de test

### Tests unitaires (module runner)
- [x] `RunnerConfig` — CLI seul / env seul / priorité CLI>env / champs manquants (codes d'erreur).
- [x] `RunnerConfig` — dérivation URL WSS (`https`→`wss`, `http`→`ws`, port conservé, suffixe/token).
- [x] `ProxyResolver` — `HTTPS_PROXY` honoré ; `NO_PROXY` exclut l'hôte ; absence de proxy.
- [x] `TokenStore` — écriture puis relecture ; jeton expiré/corrompu traité comme absent ; perms restreintes.

### Tests d'intégration (backend)
- [x] `GET /runner/download` → 404 quand aucun jar configuré/présent.
- [x] `GET /runner/download` → 200 + octets quand un jar existe au chemin configuré.
- [x] Non-régression : `/me` (401 sans JWT), `/workspaces` (401 sans JWT), `POST /runner/pair`
      (401 code invalide) se comportent comme avant l'ajout de `/runner/download`.

### Smoke manuel (à FLAGGER — non testable en CI sans gateway live)
- [ ] Appairage réel + connexion WSS bout-en-bout contre une gateway déployée ; heartbeat visible ;
      `Ctrl-C` propre ; traversée d'un proxy d'entreprise + truststore.

### Isolation
- [x] Applicable côté backend : `/runner/download` est sur la chaîne dédiée `/runner/**`, ne partage
      aucun filtre avec la chaîne utilisateur ; le jeton runner reste lié à `user_id`+`workspace_id`
      (SF-38-01) et n'est jamais résolu comme un `AuthenticatedUser`.

---

## Dépendances

### Subfeatures bloquantes
- `SF-38-01` — appairage & jetons — **done**.
- `SF-38-02` — canal WS + registre — **done**.

### Questions ouvertes impactées
- Aucune de `docs/OPEN_QUESTIONS.md`.

---

## Préoccupation transversale — Auth / Principal

SF-38-03 **n'introduit pas** de nouveau type de porteur côté backend (fait en SF-38-01). Le seul ajout
backend est l'endpoint public `GET /runner/download` posé sur la **chaîne dédiée `/runner/**`**
(`@Order(1)`, `securityMatcher("/runner/**")`), en `permitAll`, sans état ni cookie.

**Composants d'auth / tenant — impact :**
- `RunnerSecurityConfig` (chaîne dédiée) — **modifié** : ajout d'un `requestMatchers(GET,"/runner/download").permitAll()`. Aucun autre changement.
- `SecurityConfig` (chaîne principale, `anyRequest().authenticated()`) — **inchangé**.
- `JwtAuthenticationFilter`, `CurrentUser`, `AtelierAccessService` — **inchangés**.
- Non-régression exigée sur `/me`, `/workspaces`, `/runner/pair` (dans le plan d'intégration).

---

## Notes et décisions

- **Build `-pl runner`** : le dépôt n'a **pas** de POM réacteur racine (backend et frontend sont des
  builds indépendants ; le CI fait `cd backend && ./mvnw verify`). Pour ne **pas** risquer le build du
  backend, le module `runner/` est **autonome** avec son propre wrapper Maven ; il se construit par
  `cd runner && ./mvnw -q package` (équivalent fonctionnel de `./mvnw -pl runner package`). Décision
  réversible : un POM réacteur racine pourra être ajouté plus tard sans changer le module.
- **Distribution / endpoint de téléchargement** : câbler automatiquement `build → ressource servie`
  (empaqueter le fat-jar dans l'image du backend) est trop lourd pour cette SF et coûteux à maintenir.
  Décision par défaut : `GET /runner/download` sert le jar depuis un chemin **configurable**
  (`app.runner.jar-path`, vide par défaut → 404 explicite). Le dépôt du jar (build + copie) est
  documenté ; l'écran de téléchargement (lien + commande) arrive en SF-38-06. **À flagger.**
- **WSS via truststore/proxy** : on s'appuie sur le comportement JVM standard (`javax.net.ssl.*`,
  `java.net.ProxySelector`) plutôt que de réimplémenter TLS — cohérent avec la simplicité
  d'exploitation en entreprise.
- **Connexion réelle non testable en CI** : implémentée + testée sur la logique pure (config, proxy,
  URL, persistance) ; la boucle WSS bout-en-bout est un **smoke manuel flaggé**.
