# claude-runner — runner d'exécution sur machine connectée (F-38)

Le runner est un **client léger** posé sur la machine où vit le projet. Il ouvre lui-même une
connexion **sortante** WSS/443 vers la gateway : aucun port entrant, aucun installeur, **aucun droit
administrateur pour l'installer**, aucun service en arrière-plan.

> ⚠ **Avec quels droits le runner agit** — voir la section dédiée plus bas. En résumé : ceux du
> compte qui l'a lancé, ni plus ni moins. Ne le lancez pas en `root`.

> Périmètre livré : appairage, connexion, heartbeat, reconnexion, arrêt propre (**SF-38-03**),
> **outils fichiers** `list_files` / `read_file` / `write_file` / `search_files` (**SF-38-04**),
> exclusions `.runnerignore` (**SF-38-10**) et **exécution de commandes** `bash` (**SF-38-07**,
> **autorisée par défaut** depuis **SF-38-19** — chaque commande demande votre autorisation à
> l'écran ; `--no-bash` restreint le runner à la lecture).

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

Le code d'appairage est généré depuis la Forge (usage unique, TTL 5 min). Il est échangé une
seule fois contre un **jeton** persisté localement : aux lancements suivants, `--code` devient
inutile tant que le jeton est valide et non révoqué.

`Ctrl-C` ferme la connexion et arrête le processus proprement.

## Reprendre (F-46 / SF-46-01)

Une fois l'appairage réussi, **plus aucun argument n'est nécessaire** :

```bash
cd /chemin/vers/le/projet
java -jar claude-runner.jar
```

L'appairage a mémorisé l'adresse de la passerelle et la racine du projet dans
`<workspace>/.claude-runner/session.json`, avec une copie dans `~/.claude-runner/session.json` —
c'est elle qui rend la reprise possible **hors** du dossier du projet, et notamment au **double-clic**
du lanceur d'un paquet autonome. Ces fichiers ne contiennent **aucun secret** : le jeton reste dans
`token.json`, là où il a été écrit.

La recherche part du répertoire courant, remonte ses **parents** (donc un sous-dossier du projet
convient), puis se replie sur `~/.claude-runner`. Précédence :
**argument CLI > variable d'environnement > mémoire de reprise**.

Le runner **refuse explicitement** — sans jamais redemander un code en silence — quand :

| Situation | Ce qu'il dit |
|---|---|
| Rien de mémorisé | Les deux gestes : relancer depuis le projet, ou reprendre la commande dans l'application |
| Jeton expiré | La **date** d'expiration, et de relancer avec `--code` |
| Jeton absent | Qu'aucun jeton n'existe pour cette racine, et de relancer avec `--code` |
| Racine mémorisée disparue | Le chemin mémorisé, et de préciser `--workspace` |

## Options

| Option CLI | Variable d'environnement | Défaut | Rôle |
|---|---|---|---|
| `--gateway` | `CLAUDE_RUNNER_GATEWAY` | mémoire de reprise (F-46) | URL de la gateway, `/api` inclus |
| `--workspace` | `CLAUDE_RUNNER_WORKSPACE` | mémoire de reprise (F-46) | Racine du projet ; le runner refuse tout accès au-dessus |
| `--code` | `CLAUDE_RUNNER_CODE` | — (requis au premier appairage) | Code d'appairage à usage unique |
| `--label` | `CLAUDE_RUNNER_LABEL` | aucun | Libellé du jeton affiché dans l'UI (≤ 100 caractères) |
| `--heartbeat-interval` | `CLAUDE_RUNNER_HEARTBEAT_INTERVAL` | `30` (s) | Période du heartbeat |
| `--allow-bash` | `CLAUDE_RUNNER_ALLOW_BASH` | **`false`** | Autorise l'exécution de commandes (`bash`) sur cette machine |
| `--no-system-trust` | `CLAUDE_RUNNER_NO_SYSTEM_TRUST` | **absent** | Confiance stricte : le `cacerts` de la JDK **seul**, sans le magasin du système (F-80 / SF-80-02) |

L'argument CLI prime toujours sur la variable d'environnement. `--allow-bash` est un **drapeau** :
il s'écrit seul (il n'avale pas l'argument suivant) ; `--allow-bash=false` le remet à l'état par
défaut.

## Confiance TLS (F-80 / SF-80-02)

Le runner **additionne** le `cacerts` de la JDK et le magasin de certificats du système :
`/etc/ssl/certs/ca-certificates.crt` (et les variantes RHEL) sous Linux/WSL, le trousseau sous
macOS, `Windows-ROOT` sous Windows. C'est **automatique**, et c'est **annoncé** au démarrage :

```
Confiance : magasin de la JDK + magasin du système (/etc/ssl/certs/ca-certificates.crt)
            — racine d'entreprise détectée : Zscaler Inc. (CN=Zscaler Intermediate Root CA)
```

**Ce n'est pas un relâchement** : c'est exactement la confiance que le navigateur et `curl`
accordent déjà sur ce poste. La JVM, elle, n'a jamais lu ce magasin — c'est la raison pour laquelle
le runner échouait seul là où `curl` répondait `200`, derrière un proxy qui déchiffre le TLS.

**Additionner, jamais remplacer** : une racine publique retirée du magasin d'un poste reste
reconnue. Magasin absent ou illisible (conteneur minimal) : repli silencieux sur le `cacerts` de la
JDK, jamais une erreur. `--no-system-trust` rétablit la confiance stricte.

La racine nommée dans la ligne ci-dessus est celle que **la gateway présente réellement**, lue sur
notre propre connexion — jamais une racine moissonnée dans le magasin du poste.

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
| `bash` | `command`, `cwd` (optionnel) | sortie diffusée **ligne à ligne** + code de sortie |

- La commande est passée à `/bin/sh -c` (`cmd.exe /c` sous Windows) et tourne **avec vos droits**,
  dans le dossier du projet par défaut. Un `cwd` demandé n'est qu'un **point de départ** : il doit
  exister, et il peut être relatif, absolu ou `~/…`.
- **Une commande n'est pas bornée à ce dossier** (F-73). Elle va où votre compte peut aller. Aucune
  inspection de la ligne de commande n'est faite, et c'est délibéré : une liste d'interdits se
  contourne par une variable ou un script intermédiaire — seule une mise en conteneur confinerait
  vraiment, et elle interdirait l'usage même de ce produit.
- `stdout` et `stderr` sont pompés sur **deux threads dédiés** et diffusés au fil de l'eau ; le
  heartbeat continue pendant une commande longue.
- `stdin` est fermé au démarrage : une commande qui lit l'entrée standard reçoit EOF au lieu de
  pendre jusqu'au délai.
- Bornes : **une seule** commande à la fois (`denied` sinon), 8 192 caractères de ligne de commande,
  256 Kio de sortie diffusée par appel (au-delà `truncated`), délai de **120 s** par défaut, ramené
  au temps restant du tour.
- Un code de sortie non nul est rendu tel quel à l'assistant : la commande a tourné, son échec est
  une information.
- Le bouton **Interrompre** de la Forge tue le processus (`destroyForcibly`) et arrête le tour.

## Outils fichiers (SF-38-04, revu par F-73)

Le runner exécute quatre outils, reçus de la gateway sur la connexion WSS :

| Outil | Entrée | Sortie |
|---|---|---|
| `list_files` | — | chemins des fichiers du dossier du projet, triés, un par ligne |
| `read_file` | `path` | contenu texte UTF-8 (borné à 512 Kio, au-delà `truncated`) |
| `write_file` | `path`, `content` | écrit le fichier, crée les dossiers parents manquants |
| `search_files` | `query` | lignes `chemin:ligne: texte`, bornées à 8 000 caractères |

**Aucun confinement de chemin depuis F-73.** Un chemin relatif se résout sous le dossier du projet ;
un `..`, un chemin absolu ou un `~/…` sont **acceptés**. Ces outils lisent et écrivent partout où
votre compte le peut — y compris un `.env`, une clé privée ou `~/.ssh/`. La garde qui existait ici
ne tenait que sur ces quatre outils : un `cat .env` passé à `bash` n'a jamais rien rencontré, et une
protection qui ne couvre que la moitié des chemins n'en est pas une. Ce qui s'interpose désormais est
la **porte de confirmation** (armée par défaut), le **journal d'audit**, le **coupe-circuit** — et le
fait que l'application le **dise**, au démarrage du runner comme au moment d'autoriser.

> **Ce que cela implique, dit franchement** : la porte ne couvre que `bash`. Une lecture de fichier
> ne demande rien, et son contenu part chez le fournisseur dans le contexte du tour. Lancez le runner
> avec le compte et sur la machine qui conviennent à ce régime.

Bornes appliquées localement : lecture refusée au-delà de 8 Mio (`too_large`), écriture refusée
au-delà de 512 Kio, 20 000 fichiers au plus pour `list_files`, fichiers binaires et fichiers de plus
d'1 Mio ignorés par `search_files`. Chaque appel a son propre délai (30 s par défaut) et peut être
interrompu depuis la session. La console affiche chaque appel exécuté et sa durée.

## Filtre de listage (SF-38-10, revu par F-73)

Le filtre n'élague plus que **ce qui est listé** : `list_files` et `search_files`. Il ne refuse
**aucune** lecture ni écriture d'un chemin **nommé** — un fichier écarté de la liste se lit très bien
en le demandant. C'est une question de lisibilité, pas de protection.

Deux jeux de règles, **toutes négociables** :

1. **Le bruit de construction** — `node_modules/`, `target/`, `build/`, `dist/`, `.angular/`,
   `.venv/`, `vendor/`, `.terraform/`… (SF-38-21). Évalué en premier ; un `!node_modules/` l'annule.
2. **Vos règles** — `.runnerignore` dans le dossier du projet. S'il est absent, **repli** sur le
   `.gitignore` du projet. Syntaxe gitignore : `#` commentaire, `!` négation, `/` final =
   dossier uniquement, `/` initial ou interne = motif ancré à la racine du projet, sinon nom de base
   à n'importe quelle profondeur, jokers `*`, `?`, `**`. La dernière règle qui correspond l'emporte.
   (Les classes de caractères `[a-z]` ne sont pas interprétées : elles sont comparées littéralement.)

Un dossier exclu est élagué du balayage : son contenu n'est ni listé ni parcouru par la recherche.
Les règles sont chargées **au premier appel sur le projet** — modifier `.runnerignore` demande un
redémarrage du runner.

> **La liste de secrets non désactivable a été retirée** le 2026-09-12 (F-73). `.env`, `*.pem`,
> `id_rsa*`, `.aws/`, `.kube/config` et `.ssh/` sont désormais traités comme n'importe quel fichier.
> Un `.runnerignore` peut toujours les écarter des **listes**, mais plus rien n'empêche leur lecture
> si le modèle les demande par leur nom.

## Jeton

Persisté dans `<workspace>/.claude-runner/token.json` (repli `~/.claude-runner/token.json`),
en permissions `600`. Le fichier voisin `session.json` (F-46) ne porte que la passerelle et la
racine — **jamais** le jeton. Un jeton refusé par la gateway (révoqué ou expiré) est **effacé** : le
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

---

## Avec quels droits le runner agit

**Le runner s'exécute avec les droits de l'utilisateur qui l'a lancé — rien de plus, rien de
moins.** Il ne bride aucun privilège et ne prétend pas le faire.

| Situation | `sudo apt install …` |
|---|---|
| Lancé par vous, `sudo` demande un mot de passe | **échoue** — pas de terminal pour le saisir |
| `sudo` configuré `NOPASSWD` | **passe** |
| Runner lancé en `root` (y compris dans un conteneur) | **passe**, et `sudo` est inutile |

L'échec du premier cas est un **effet de bord**, pas une garde : `BashTool` ferme l'entrée standard
des processus qu'il lance, si bien qu'aucune commande ne peut lire une saisie interactive. Ne comptez
pas dessus comme sur une protection.

### Ce qui protège réellement

- **La porte de confirmation** : aucune commande `bash` ne part sans un geste de l'utilisateur. Elle
  est **armée par défaut** sur tout projet créé depuis le 2026-09-12 (F-73), et reste réglable
  projet par projet.
- **Le journal d'audit** : chaque appel est tracé, y compris les refus.
- **Le coupe-circuit** : révoquer les jetons coupe la liaison immédiatement.
- **Ce qui est dit** : le bloc de démarrage ci-dessus, et l'écran au moment d'autoriser.

À noter, et c'est la limite à connaître : **la porte ne couvre que `bash`**. Les outils fichiers ne
demandent rien — une lecture de `.env` ou de `~/.ssh/id_rsa` ne pose aucune question, et son contenu
part chez le fournisseur dans le contexte du tour. Un shell reste un shell, et un runner reste un
programme qui travaille avec vos droits sur votre machine.

### Recommandation

Lancez le runner **avec votre compte habituel**. Si vous le lancez en `root` — dans un conteneur,
ou sur un projet appartenant à `root` — il vous le dit au démarrage, et l'écran le rappelle au
moment où vous autorisez une commande.
