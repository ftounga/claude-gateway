# claude-runner — runner d'exécution sur machine connectée (F-38)

Le runner est un **client léger** posé sur la machine où vit le projet. Il ouvre lui-même une
connexion **sortante** WSS/443 vers la gateway : aucun port entrant, aucun installeur, aucun droit
administrateur, aucun service en arrière-plan.

> Périmètre livré : appairage, connexion, heartbeat, reconnexion, arrêt propre (**SF-38-03**),
> **outils fichiers** `list_files` / `read_file` / `write_file` / `search_files` (**SF-38-04**),
> exclusions `.runnerignore` (**SF-38-10**) et **exécution de commandes** `bash` (**SF-38-07**,
> désactivée par défaut — voir `--allow-bash`).

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
| `--allow-bash` | `CLAUDE_RUNNER_ALLOW_BASH` | **`false`** | Autorise l'exécution de commandes (`bash`) sur cette machine |

L'argument CLI prime toujours sur la variable d'environnement. `--allow-bash` est un **drapeau** :
il s'écrit seul (il n'avale pas l'argument suivant) ; `--allow-bash=false` le remet à l'état par
défaut.

## Exécution de commandes (SF-38-07)

**Désactivée par défaut.** Démarrer un runner autorise l'assistant à lire et écrire les fichiers de
la racine ; exécuter des commandes arbitraires est un cran au-dessus, et c'est **la machine** qui le
décide — pas la gateway :

```bash
java -jar claude-runner.jar --gateway … --workspace … --allow-bash
```

Sans ce drapeau, le runner n'annonce pas la capacité `bash` et la gateway refuse l'appel **avant de
l'émettre**, avec un message qui rappelle comment l'activer.

Une fois activée :

| Outil | Entrée | Sortie |
|---|---|---|
| `bash` | `command`, `cwd` (optionnel, relatif) | sortie diffusée **ligne à ligne** + code de sortie |

- La commande est passée à `/bin/sh -c` (`cmd.exe /c` sous Windows) et tourne **avec vos droits**,
  dans la racine `--workspace` par défaut. Un `cwd` demandé passe par la même garde de confinement
  que les fichiers : ni `..`, ni chemin absolu, ni dossier exclu.
- `stdout` et `stderr` sont pompés sur **deux threads dédiés** et diffusés au fil de l'eau ; le
  heartbeat continue pendant une commande longue.
- `stdin` est fermé au démarrage : une commande qui lit l'entrée standard reçoit EOF au lieu de
  pendre jusqu'au délai.
- Bornes : **une seule** commande à la fois (`denied` sinon), 8 192 caractères de ligne de commande,
  256 Kio de sortie diffusée par appel (au-delà `truncated`), délai de **120 s** par défaut, ramené
  au temps restant du tour.
- Un code de sortie non nul est rendu tel quel à l'assistant : la commande a tourné, son échec est
  une information.
- Le bouton **Interrompre** de l'Atelier tue le processus (`destroyForcibly`) et arrête le tour.

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

## Exclusions (SF-38-10)

Le filtre d'exclusion est appliqué **sur votre machine, avant toute lecture, écriture ou listing** :
ce qui est exclu ne quitte jamais le poste. Il est traversé par **les quatre** outils — deviner le
chemin d'un fichier exclu ne le rend pas lisible, il répond `excluded`.

Deux jeux de règles :

1. **Vos règles** — `.runnerignore` à la racine `--workspace`. S'il est absent, **repli** sur le
   `.gitignore` de la racine. Syntaxe gitignore : `#` commentaire, `!` négation, `/` final =
   dossier uniquement, `/` initial ou interne = motif ancré à la racine, sinon nom de base à
   n'importe quelle profondeur, jokers `*`, `?`, `**`. La dernière règle qui correspond l'emporte.
   (Les classes de caractères `[a-z]` ne sont pas interprétées : elles sont comparées littéralement.)
2. **La liste par défaut, non désactivable** (décision D10) — `.env`, `*.pem`, `id_rsa*`, `.aws/`,
   `.kube/config`, `.ssh/`. Elle est évaluée **en dernier** et **gagne toujours** : un `!.env` dans
   votre `.runnerignore` ne la réactive pas.

Un dossier exclu est élagué du balayage : son contenu n'est ni listé, ni ouvert, ni lu. Les règles
sont chargées **au démarrage** — modifier `.runnerignore` demande un redémarrage du runner, qui
affiche alors la source et le nombre de règles retenues.

> La liste par défaut est volontairement littérale et courte. Elle ne couvre **pas** `.env.local`,
> `id_ed25519`, `*.key`, `.npmrc`… : ajoutez-les à votre `.runnerignore`. Et pointez `--workspace`
> sur le dossier de projet voulu, pas sur `$HOME`.

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
