# Mini-spec — [F-111 / SF-111-02] Le lanceur

## Identifiant

`F-111 / SF-111-02`

## Feature parente

`F-111` — Le runner se met à jour d'un clic (cadrage : `CADRAGE-F-111-le-runner-se-met-a-jour.md`)

## Statut

`done` — PR #566 mergée le 2026-09-13

## Date de création

2026-09-13

## Branche Git

`feat/SF-111-02-lanceur`

---

## Objectif

`claude-runner.jar` devient, par défaut, un **lanceur** qui reste au premier plan et démarre le vrai
runner en processus enfant depuis `~/.claude-runner/versions/<version>/runner.jar`, avec les mêmes
arguments, le même environnement et la même console, et décide selon le code de sortie : s'arrêter,
démarrer la version mise à jour (75) ou relancer après un plantage.

---

## Comportement attendu

### Cas nominal

1. `java -jar claude-runner.jar …` (même commande qu'aujourd'hui, paquets Windows/macOS compris) :
   `RunnerLauncher` (Java 8) vérifie Java 21 puis appelle `launcher.Launcher.main` par réflexion.
2. **Mode direct** (sans lanceur) si : `--no-launcher` (retiré avant de passer les arguments au runner),
   `--check` ou `CLAUDE_RUNNER_CHECK=true`, `--releve-teams`, processus déjà enfant d'un lanceur
   (`CLAUDE_RUNNER_LAUNCHER_PID` posé), ou code non chargé depuis un jar (IDE).
3. **Installation initiale** : si `versions/<id du jar lancé>/runner.jar` manque ou ne correspond plus à
   son empreinte `runner.jar.sha256`, le jar lancé y est copié (écriture à côté puis déplacement).
   Échec (dossier personnel en lecture seule) : dit, et le runner démarre depuis le jar lancé.
4. **Version démarrée** : `current-version` si elle est installée, intacte et **plus récente** que le jar
   lancé ; sinon le jar lancé (et `current-version` le retient). Jamais une version plus ancienne.
5. **Enfant** : `<java.home>/bin/java[.exe]` (repli `ProcessHandle.current().info().command()`), options
   de JVM du lanceur utiles (`-D…`, `-X…`, agents ; lues sur sa ligne de commande, reconstruites depuis
   les propriétés proxy/confiance sous Windows), `-cp <jar de la version> fr.claudegateway.runner.RunnerMain`,
   mêmes arguments ; environnement hérité + `CLAUDE_RUNNER_LAUNCHER_PID`, `CLAUDE_RUNNER_HOME` ;
   `ProcessBuilder.inheritIO()`.
6. **Codes de sortie** (`LauncherPolicy`) :

   | Code | Geste |
   |---|---|
   | `75` | démarrer la version de `next-version` (installée, intacte, plus récente) ; `next-version` est consommé, `current-version` écrit |
   | `0`, `2`–`6` | s'arrêter avec ce code |
   | autre | relancer la même version après 2 s, **3 fois au plus en 5 minutes** ; au-delà, s'arrêter avec ce code en le disant |

7. **Arrêt** (`Ctrl+C`, fermeture) : crochet du lanceur → l'enfant est arrêté (Linux/macOS : SIGTERM,
   le runner exécute son propre crochet ; Windows : attente, `Ctrl+C` ayant déjà atteint l'enfant), forcé
   après 8 s. **Aucun orphelin** : en plus, l'enfant surveille son lanceur (`CLAUDE_RUNNER_LAUNCHER_PID`)
   et s'arrête s'il disparaît (lanceur tué brutalement).
8. **Déclaration** : la trame `ready` porte `launcher: true` sous lanceur (SF-111-01 le déclarait `false`).
9. `RunnerConfig` accepte `--no-launcher` sans effet (un runner direct lancé par `-cp` ne le refuse pas).

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| `next-version` absent, illisible, non installé ou altéré | la version courante redémarre, message dit | — |
| `next-version` plus ancien ou égal | refusé, la version courante redémarre | — |
| Dossier `~/.claude-runner` non inscriptible | runner démarré depuis le jar lancé, sans mise à jour automatique, message dit | — |
| Exécutable Java introuvable / démarrage impossible | message, sortie `1` | — |
| 4 plantages en 5 minutes | lanceur s'arrête avec le code du runner, message dit | — |
| Lanceur tué sans crochet (SIGKILL) | l'enfant détecte la disparition du lanceur et s'arrête proprement | — |

---

## Critères d'acceptation

- [ ] CA1 — Sans option, `claude-runner.jar` démarre un enfant `java -cp versions/<id>/runner.jar RunnerMain` avec les mêmes arguments et l'environnement hérité (test d'intégration Linux).
- [ ] CA2 — Sortie 75 → l'enfant de la version de `next-version` démarre ; `current-version` mis à jour ; `next-version` consommé (test d'intégration Linux).
- [ ] CA3 — `0`/`2`–`6` arrêtent le lanceur ; autre code → relance, 3 fois au plus en 5 minutes (tests unitaires + intégration).
- [ ] CA4 — `--no-launcher`, `--check`, `CLAUDE_RUNNER_CHECK`, `--releve-teams`, enfant d'un lanceur : mode direct (tests unitaires).
- [ ] CA5 — Installation initiale dans `versions/`, empreinte relue ; jamais de démarrage d'une version plus ancienne que le jar lancé (tests unitaires).
- [ ] CA6 — Arrêt du lanceur → l'enfant s'arrête, aucun orphelin (test d'intégration Linux avec le crochet réel) ; l'enfant s'arrête si le lanceur disparaît (test unitaire de la surveillance).
- [ ] CA7 — Commande de l'enfant : exécutable `java.exe` sous Windows, options de JVM filtrées (tests unitaires sur la logique, Windows/macOS/Linux).
- [ ] CA8 — La trame `ready` déclare `launcher: true` sous lanceur.

---

## Périmètre

### Hors scope (explicite)

- Signature et téléchargement (SF-111-03), commande `update` (SF-111-04).
- Contrôle de santé en 90 s, retour à la version précédente, rapport, rétention de deux versions (SF-111-05).
- Remplacement du lanceur lui-même : il reste celui du jar lancé à la main (cadrage §4).
- Aucun changement côté gateway ni écran (la déclaration `launcher` est déjà lue par SF-111-01).

---

## Valeurs initiales

| Élément | Valeur initiale | Règle |
|-------|----------------|-------|
| `~/.claude-runner/versions/<id>/` | créé au premier lancement | copie du jar lancé + empreinte |
| `current-version` | id du jar lancé | réécrit à chaque mise à jour démarrée |
| `next-version` | absent | écrit par le runner (SF-111-04), consommé par le lanceur |

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format / Valeurs autorisées | Unicité | Normalisation |
|-------|-------------|-------------|----------------------------|---------|---------------|
| identifiant de version (fichiers) | Oui | — | `\d+(.\d+){0,2}(-\d{12}(-[A-Za-z0-9]{1,40})?)?` — jamais un chemin | — | trim |
| `CLAUDE_RUNNER_HOME` | Non | — | chemin | — | trim |

---

## Technique

### Endpoint(s)

Aucun.

### Tables impactées

Aucune.

### Migration Liquibase

- [x] Non applicable

### Composants

- Runner : `RunnerLauncher` (Java 8, délègue), `launcher.Launcher`, `launcher.LauncherPolicy`, `launcher.LauncherHome`, `launcher.JavaChild`, `launcher.LauncherWatch` (surveillance côté enfant), `RunnerConfig` (drapeau accepté), `RunnerConnection`/`PollingConnection` (déclaration).

### Préoccupations transversales

- Auth / Principal : non (le jeton du poste est relu par l'enfant comme aujourd'hui, F-46). Tenant : non. Plans : non. Navigation : non.

---

## Plan de test

### Tests unitaires

- [ ] `LauncherPolicyTest` — 75, arrêts, plantages (fenêtre de 5 min glissante, 4e plantage).
- [ ] `LauncherHomeTest` — installation, empreinte altérée, identifiants hostiles, `next-version`.
- [ ] `LauncherTest` — mode direct (drapeaux, env), version de départ (jamais plus ancienne), `withoutLauncherFlag`.
- [ ] `JavaChildTest` — commande, `java.exe` sous Windows, options de JVM filtrées.
- [ ] `LauncherWatchTest` — lanceur disparu → arrêt.

### Tests d'intégration

- [ ] `LauncherIntegrationTest` (Linux) — lanceur → enfant → 75 → nouvel enfant ; relances ; arrêt sans orphelin.

### Isolation workspace

- [x] Non applicable — aucun accès à des données de la gateway ; les fichiers vivent sous le compte de l'utilisateur du poste.

---

## Dépendances

### Subfeatures bloquantes

- SF-111-01 — identifiant de version (`RunnerBuild`) — done (mergée).

### Questions ouvertes impactées

- Aucune.

---

## Notes et décisions

- **D1 — Le lanceur est Java 21**, derrière la vérification Java 8 existante : `RunnerLauncher` garde son
  seul rôle (dire « il vous faut Java 21 ») puis délègue. Aucune API récente n'entre dans la classe Java 8.
- **D2 — Enfant sur `RunnerMain`, pas sur le lanceur** : un enfant ne peut pas se relancer lui-même, et
  `CLAUDE_RUNNER_LAUNCHER_PID` force de toute façon le mode direct.
- **D3 — Options de JVM filtrées** plutôt que recopiées : `-cp`/`-jar` changent de sens chez l'enfant ;
  seuls `-D`, `-X`, agents et ouvertures de modules sont transmis.
- **D4 — Surveillance du lanceur par l'enfant** : un crochet d'arrêt ne s'exécute pas sur SIGKILL ; sans
  elle, tuer le lanceur laisserait un runner orphelin connecté.
