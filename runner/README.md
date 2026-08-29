# claude-runner — runner d'exécution sur machine connectée (F-38)

Le runner est un **client léger** posé sur la machine où vit le projet. Il ouvre lui-même une
connexion **sortante** WSS/443 vers la gateway : aucun port entrant, aucun installeur, aucun droit
administrateur, aucun service en arrière-plan.

> Périmètre livré : appairage, connexion, heartbeat, reconnexion, arrêt propre (**SF-38-03**) et
> **outils fichiers** `list_files` / `read_file` / `write_file` / `search_files` (**SF-38-04**).
> L'exécution de commandes (`bash`, SF-38-07) et les exclusions `.runnerignore` (SF-38-10) arrivent
> ensuite ; d'ici là le runner répond `unsupported_tool` à `bash`.

## Construire

```bash
cd runner && ./mvnw -q package
# → runner/target/claude-runner.jar (fat-jar autonome, Java 21)
```

Le module est **indépendant du build du backend** : pas de POM réacteur racine, pas de parent
Spring Boot. La CI backend (`cd backend && ./mvnw verify`) est inchangée.

## Lancer

```bash
java -jar claude-runner.jar \
  --gateway https://portal.ng-itconsulting.com/api \
  --workspace /chemin/vers/le/projet \
  --code AB2C3D4E \
  --label "poste-dev"
```

Le code d'appairage est généré depuis l'Atelier (usage unique, TTL 5 min). Il est échangé une
seule fois contre un **jeton** persisté localement : aux lancements suivants, `--code` devient
inutile tant que le jeton est valide et non révoqué.

`Ctrl-C` ferme la connexion et arrête le processus proprement.

## Options

| Option CLI | Variable d'environnement | Défaut | Rôle |
|---|---|---|---|
| `--gateway` | `CLAUDE_RUNNER_GATEWAY` | — (requis) | URL de la gateway, `/api` inclus |
| `--workspace` | `CLAUDE_RUNNER_WORKSPACE` | — (requis) | Racine du projet ; le runner refuse tout accès au-dessus |
| `--code` | `CLAUDE_RUNNER_CODE` | — (requis au premier appairage) | Code d'appairage à usage unique |
| `--label` | `CLAUDE_RUNNER_LABEL` | aucun | Libellé du jeton affiché dans l'UI (≤ 100 caractères) |
| `--heartbeat-interval` | `CLAUDE_RUNNER_HEARTBEAT_INTERVAL` | `30` (s) | Période du heartbeat |

L'argument CLI prime toujours sur la variable d'environnement.

## Outils fichiers et confinement (SF-38-04)

Le runner exécute quatre outils, reçus de la gateway sur la connexion WSS :

| Outil | Entrée | Sortie |
|---|---|---|
| `list_files` | — | chemins relatifs des fichiers de la racine, triés, un par ligne |
| `read_file` | `path` | contenu texte UTF-8 (borné à 512 Kio, au-delà `truncated`) |
| `write_file` | `path`, `content` | écrit le fichier, crée les dossiers parents manquants |
| `search_files` | `query` | lignes `chemin:ligne: texte`, bornées à 8 000 caractères |

**Confinement — la vérification qui fait foi est celle du runner** : tout chemin est résolu de façon
canonique (liens symboliques compris) et doit rester sous `--workspace`. Un `..`, un chemin absolu,
une lettre de lecteur Windows ou un lien qui sort de la racine sont refusés (`path_outside_root`) —
**rien n'est lu ni écrit**. Les messages d'erreur renvoyés à la gateway ne citent que des chemins
**relatifs** : le chemin absolu de votre machine ne sort jamais du poste.

Bornes appliquées localement : lecture refusée au-delà de 8 Mio (`too_large`), écriture refusée
au-delà de 512 Kio, 20 000 fichiers au plus pour `list_files`, fichiers binaires et fichiers de plus
d'1 Mio ignorés par `search_files`. Chaque appel a son propre délai (30 s par défaut) et peut être
interrompu depuis la session. La console affiche chaque appel exécuté et sa durée.

> Tant que SF-38-10 n'est pas livrée, il n'y a **pas** d'exclusions : tout fichier régulier sous la
> racine est visible du runner. Pointez `--workspace` sur le dossier de projet voulu, pas sur `$HOME`.

## Jeton

Persisté dans `<workspace>/.claude-runner/token.json` (repli `~/.claude-runner/token.json`),
en permissions `600`. Un jeton refusé par la gateway (révoqué ou expiré) est **effacé** : le
prochain lancement redemande un `--code`. Un jeton peut être révoqué à tout moment depuis l'UI.

## Proxy et truststore d'entreprise

`HTTPS_PROXY` / `HTTP_PROXY` / `NO_PROXY` (et leurs variantes minuscules) sont honorés pour
l'appairage HTTP **et** pour la connexion WSS. Le truststore JVM standard s'applique :

```bash
java -Djavax.net.ssl.trustStore=/chemin/truststore.jks \
     -Djavax.net.ssl.trustStorePassword=... \
     -jar claude-runner.jar --gateway ... --workspace ...
```

## Codes de sortie

| Code | Signification |
|---|---|
| `0` | Arrêt propre (`Ctrl-C`) |
| `1` | Erreur inattendue |
| `2` | Configuration invalide (option requise absente, workspace inexistant) |
| `3` | Appairage refusé ou injoignable |
| `4` | Jeton refusé au handshake et aucun code d'appairage fourni |

## Distribution

Le jar n'est **pas** empaqueté dans l'image du backend. Quand un jar est déposé sur la gateway au
chemin `app.runner.jar-path` (`APP_RUNNER_JAR_PATH`), il est servi par `GET /api/runner/download` ;
sinon cet endpoint répond `404 runner_jar_unavailable`.
