# claude-runner — runner d'exécution sur machine connectée (F-38)

Le runner est un **client léger** posé sur la machine où vit le projet. Il ouvre lui-même une
connexion **sortante** WSS/443 vers la gateway : aucun port entrant, aucun installeur, aucun droit
administrateur, aucun service en arrière-plan.

> Périmètre de **SF-38-03** : appairage, connexion, heartbeat, reconnexion, arrêt propre.
> Les outils fichiers (SF-38-04) et l'exécution de commandes (SF-38-07) arrivent ensuite.

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
