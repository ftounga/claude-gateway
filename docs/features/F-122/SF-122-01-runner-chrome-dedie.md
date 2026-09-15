# Mini-spec — [F-122 / SF-122-01] Le runner lance et gère un Chrome dédié, tout seul

> Base : `project-governance/templates/subfeature-template.md`.
> Cadrage : `docs/features/F-122/CADRAGE-F-122-mise-en-service-vigie-automatique.md` (SF-122-01).

---

## Identifiant

`F-122 / SF-122-01`

## Feature parente

`F-122` — Mise en service automatique et guidée de la Vigie

## Statut

`ready`

## Date de création

2026-09-15

## Branche Git

`feat/SF-122-01-runner-chrome-dedie`

---

## Objectif

> Doter le runner de la capacité de **détecter, lancer, surveiller et arrêter lui-même** un Chrome
> **dédié** (profil isolé, port de débogage sur la boucle locale, fenêtre discrète) — sans qu'aucune
> commande ne soit demandée à l'utilisateur.

---

## Comportement attendu

### Cas nominal

1. Le runner résout le **chemin du navigateur** pour le système courant (macOS / Windows / Linux),
   avec possibilité de **surcharge par configuration** (chemin, port, dossier de profil).
2. Il construit la **ligne de commande** exacte : `--remote-debugging-port=<port>` **+
   `--user-data-dir=<dossier géré par le runner>`** (obligatoire : Chrome refuse le débogage sur le
   profil par défaut) + adresse de débogage bornée à `127.0.0.1` + fenêtre **discrète**
   (positionnée hors champ) + ouverture de `https://teams.microsoft.com`. **Jamais `--headless`**
   (la connexion SSO/MFA d'entreprise exige une vraie session interactive).
3. Il **lance** le processus (long-running, via `ProcessSession`) puis **vérifie que le port répond**
   (`/json/version` sur la boucle locale), en sondant jusqu'à un délai borné.
4. Une fois joignable, l'observation réseau existante (SF-87/89 : `BrowserLink`/`NetworkObserver`)
   peut se rattacher à **ce** Chrome managé — aucun changement d'attache CDP.
5. Cycle de vie exposé : `ensureRunning()` (démarrer si besoin), `isReachable()`, `relaunchIfDead()`
   (relancer si le port ne répond plus), `stop()` (arrêt propre).

`ensureRunning()` rend un **état explicite** :

| État | Signification |
|------|---------------|
| `REACHABLE` | Le port répondait déjà (Chrome managé déjà là) — aucun lancement |
| `LAUNCHED` | Lancé par le runner, le port a répondu dans le délai |
| `UNREACHABLE` | Lancé (ou déjà présent) mais le port n'a **jamais** répondu dans le délai |
| `NO_BROWSER` | Aucun exécutable Chrome/Chromium trouvé sur le système |

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Aucun exécutable Chrome trouvé (et pas de surcharge de chemin) | `ensureRunning()` rend `NO_BROWSER`, **aucun processus lancé**, aucune exception non maîtrisée (le message nommé « Chrome absent » est du ressort de SF-122-04) |
| Le port ne répond jamais après lancement (ex. remote-debugging bloqué par une policy) | `ensureRunning()` rend `UNREACHABLE` après le délai borné, sans boucler indéfiniment (le diagnostic « bloqué par policy » est du ressort de SF-122-04) |
| Le processus est mort / le port ne répond plus | `relaunchIfDead()` relance ; s'il redevient joignable → `LAUNCHED`, sinon `UNREACHABLE` |
| Hôte de débogage autre que la boucle locale demandé | Refusé (garde `BrowserPort` existante inchangée : jamais de navigateur distant) |

---

## Critères d'acceptation

- [ ] La détection du chemin rend un candidat plausible **par OS** (macOS : `/Applications/Google
      Chrome.app/...` ; Windows : `%ProgramFiles%\Google\Chrome\Application\chrome.exe` et variantes ;
      Linux : `google-chrome`/`chromium` standard), testée avec un système d'exploitation et un
      système de fichiers **injectés** (pas de vrai Chrome).
- [ ] Une **surcharge de chemin** par configuration est honorée quand elle est fournie.
- [ ] La ligne de commande construite contient : l'exécutable, `--remote-debugging-port=<port>`,
      `--user-data-dir=<profil géré>`, l'adresse `127.0.0.1`, un positionnement de fenêtre **hors
      champ**, `https://teams.microsoft.com`, et **jamais** `--headless`.
- [ ] Les arguments sont passés **un par un** (aucune ligne shell à découper).
- [ ] `ensureRunning()` ne lance **pas** de processus quand le port répond déjà (`REACHABLE`).
- [ ] `ensureRunning()` lance le processus et rend `LAUNCHED` quand le port répond après lancement.
- [ ] `ensureRunning()` rend `UNREACHABLE` quand le port ne répond jamais, **sans boucle infinie**.
- [ ] `ensureRunning()` rend `NO_BROWSER` et ne lance rien quand aucun exécutable n'est trouvé.
- [ ] `relaunchIfDead()` relance uniquement quand le port ne répond pas.
- [ ] Le dossier de profil managé est **par OS** et distinct du profil par défaut de l'utilisateur.
- [ ] Le port par défaut et la garde « boucle locale uniquement » restent ceux de `BrowserPort`.

---

## Périmètre

### Hors scope (explicite)

- Le **déclenchement automatique** à l'activation de la Vigie et le parcours UX vert/rouge → SF-122-02.
- Le fonctionnement en arrière-plan permanent et le **relogin** guidé à l'expiration → SF-122-03.
- Les **messages/diagnostics nommés** des cas d'entreprise (policy remote-debugging, Chrome absent,
  proxy NTLM) → SF-122-04. SF-122-01 rend seulement des **états** bruts (`NO_BROWSER`, `UNREACHABLE`).
- Le **lancement réel** de Chrome en test (interdit : aucun binaire lancé en test unitaire).
- Toute modification de `TeamsAdapterV1` (parsing) et du relevé (SF-89-*).

---

## Valeurs initiales / défauts sûrs

| Réglage | Défaut | Surcharge |
|---------|--------|-----------|
| Port de débogage | `9222` (`BrowserPort.DEFAULT_PORT`) | `--teams-port` / `CLAUDE_TEAMS_DEBUG_PORT` |
| Chemin du navigateur | détecté par OS | `CLAUDE_TEAMS_CHROME_PATH` |
| Dossier de profil | `ClaudeGateway/chrome-teams` par OS (aligné sur `BrowserLaunchAdvice`) | `CLAUDE_TEAMS_CHROME_PROFILE` |
| Adresse de débogage | `127.0.0.1` (jamais surchargeable — garde `BrowserPort`) | — |
| Délai de disponibilité du port | borné (défaut ~15 s, pas sûr configurable) | — |

---

## Technique

### Endpoint(s)

Aucun (composant runner interne).

### Tables impactées

Aucune. Aucune migration Liquibase.

### Composants (runner)

- `ChromePaths` (nouveau) — détection de l'exécutable par OS (+ surcharge) et dossier de profil managé.
- `ManagedChrome` (nouveau) — construction de la ligne de commande + cycle de vie
  (`ensureRunning`/`isReachable`/`relaunchIfDead`/`stop`) au-dessus de `ProcessSession` et d'une
  **sonde** de port injectable ; fabrique `real()` branchée sur `ProcessSession.real()` et la
  découverte `/json/version` existante.
- `ManagedChromeSettings` (nouveau) — résolution des réglages (chemin/port/profil) depuis
  cli/environnement avec défauts sûrs.
- Réutilise sans les modifier : `BrowserPort`, `OperatingSystem`, `ProcessSession`,
  `BrowserLaunchAdvice.TEAMS_URL`.

### Migration Liquibase

- [x] Non applicable

---

## Plan de test

### Tests unitaires (runner, `./mvnw -q test`)

- [ ] `ChromePathsTest` — candidat correct par OS (env + `exists` injectés), surcharge de chemin
      honorée, aucun candidat → `Optional.empty()`, dossier de profil par OS.
- [ ] `ManagedChromeTest` — ligne de commande (flags corrects, `user-data-dir` présent, hors champ,
      loopback, pas de `--headless`, args un par un).
- [ ] `ManagedChromeTest` — santé/lancement : `REACHABLE` sans lancement ; `LAUNCHED` après
      lancement ; `UNREACHABLE` si le port ne répond jamais ; `NO_BROWSER` si pas d'exécutable.
- [ ] `ManagedChromeTest` — `relaunchIfDead()` relance si le port ne répond pas, pas sinon ; `stop()`
      arrête le processus.
- [ ] `ManagedChromeSettingsTest` — port/chemin/profil résolus depuis cli/env, défauts sûrs.

### Tests d'intégration

- Sans objet (composant runner, aucun endpoint HTTP). Le **lancement réel de Chrome** est
  explicitement non testable en unitaire (validation sur machine réelle uniquement).

### Isolation utilisateur / tenant

- [x] Applicable — le Chrome managé et son profil sont **par poste/utilisateur** : profil isolé, port
      borné à la **boucle locale** (garde `BrowserPort` réutilisée, aucun navigateur distant). Aucune
      donnée cross-tenant : le composant ne lit ni n'écrit de données utilisateur en base.

---

## Préoccupations transversales

| Préoccupation | Impact | Composants |
|---------------|--------|------------|
| **Auth / Principal** | Aucun. Le runner **n'automatise jamais** l'authentification Teams (login manuel une fois, session portée par le profil). Aucune socket, aucun cookie remonté (garde SF-87 inchangée). | — |
| **Contexte tenant** | Le profil et le port sont locaux au poste ; aucune résolution de tenant modifiée. | `BrowserPort` (réutilisé, inchangé) |
| **Plans / limites** | Aucun. | — |
| **Navigation / routing** | Aucun (pas de frontend dans cette SF). | — |

---

## Dépendances

### Subfeatures bloquantes

- Aucune. Construit au-dessus de F-87/F-90/F-91 (attache CDP, ports de processus) déjà livrés.

### Questions ouvertes impactées

- Aucune de `docs/OPEN_QUESTIONS.md`. Le choix « un Chrome managé par poste » vs « multi-onglets »
  (cadrage §5) reste tranché plus tard (impact SF-122-02/03) : SF-122-01 gère **un** Chrome managé.

---

## Notes et décisions

- **Pas de headless** : décision technique du cadrage (SSO/MFA d'entreprise). La fenêtre est **rendue
  discrète** par positionnement hors champ, pas masquée par headless.
- **Chrome dédié, jamais le navigateur perso** : un `--user-data-dir` managé garantit un profil isolé.
- Détection : Chrome d'abord, puis les autres canaux Chromium (Chromium, Edge) comme repli **nommé**
  et documenté — le repli reste Chromium (un navigateur non-Chromium est hors périmètre, cadrage §6).
- SF-122-01 rend des **états bruts** ; l'habillage en messages actionnables est délégué à SF-122-04
  pour éviter toute duplication.
