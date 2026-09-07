# Mini-spec — F-38 / SF-38-26 — La console de démarrage dit vrai, une fois, et lisiblement

## Identifiant

`F-38 / SF-38-26`

## Feature parente

`F-38` — Exécution sur machine connectée (runner local)

## Statut

`livrée`

## Date de création

2026-09-08

## Branche Git

`feat/SF-38-26-console-de-demarrage`

---

## Objectif

> Que les quinze lignes que le runner affiche au démarrage soient **exactes**, **dites une seule
> fois**, et **lisibles sur la console Windows** — parce que c'est le seul endroit où l'utilisateur
> qui installe le runner apprend ce que sa machine vient d'accepter.

---

## Déclencheur

Trois défauts distincts, tous sur le même écran, tous constatés dans la même capture de démarrage.

### 1. Le drapeau annoncé n'existe plus

`ToolStack.create` (lignes 34-36) :

```java
console.info(bash.enabled()
        ? "Exécution de commandes ACTIVÉE (--allow-bash) — les commandes tournent avec vos droits."
        : "Exécution de commandes désactivée (relancez avec --allow-bash pour l'autoriser).");
```

Or `--allow-bash` **n'a plus d'effet depuis SF-38-19** (`RunnerConfig.java:62`) :

```java
pick(cli, "allow-bash", env, "CLAUDE_RUNNER_ALLOW_BASH"); // toléré, sans effet (D2)
```

Le drapeau est encore **accepté** pour ne pas casser une ligne de commande copiée d'hier, mais il ne
commande plus rien : l'exécution est le défaut, `--no-bash` est la restriction. La console attribue
donc l'état à un drapeau qui n'y est pour rien — et, dans la branche « désactivée », elle donne à
l'utilisateur une **consigne de réparation qui ne répare pas** : relancer avec `--allow-bash` en
gardant `--no-bash` ne changera rien, la restriction l'emporte (SF-38-19, D3).

Le même mensonge est servi à l'utilisateur **dans le chat**, par la gateway
(`RunnerToolGateway.java:138`) :

> « L'exécution de commandes n'est pas activée sur ce runner. Redémarre-le avec `--allow-bash` pour
> l'autoriser. »

### 2. La ponctuation revient en `?`

La console du runner écrit en français typographique : `…`, `—`, `’`. `Console.print` passe par
`System.out.println`, qui encode avec le jeu de caractères du terminal. Sur une console Windows —
**cp850** en France, cp437 aux États-Unis — ces trois caractères n'ont **aucune** correspondance :

```
[10:02:14] INFO  Appairage aupr?s de https://.../runner/pair?
```

Vérifié : en `IBM850`, `canEncode('…')`, `canEncode('—')`, `canEncode('’')` valent tous `false`.
`«`, `»` et les lettres accentuées, elles, passent (0xAE/0xAF et la plage 0x80-0xA5) — le défaut ne
touche donc **que** la ponctuation typographique, ce qui explique qu'il soit resté invisible en
relecture : les accents ont l'air corrects. Et sur un flux **US-ASCII** (redirection, `LANG=C`),
c'est l'inverse : les accents tombent aussi.

### 3. L'état d'exécution est annoncé deux fois

`RunnerMain.execute` (ligne 49) l'annonce au démarrage :

```
[10:02:12] INFO  Commandes : autorisées (chacune demande votre autorisation à l'écran)
```

puis `ToolStack.create` (ligne 34), monté à l'ouverture du canal, le réannonce :

```
[10:02:14] INFO  Exécution de commandes ACTIVÉE (--allow-bash) — les commandes tournent avec vos droits.
```

Deux formulations, deux vocabulaires, deux valeurs de vérité (la seconde est fausse), à deux
secondes d'intervalle. Pire : `ToolStack.create` est appelé **une fois par transport**
(`RunnerConnection.run` **et** `PollingConnection.run`) — sur un réseau qui force le repli
long-polling, la seconde ligne apparaît une troisième fois.

---

## Comportement attendu

### Nominal

Au démarrage, l'état de l'exécution de commandes est annoncé **exactement une fois**, par
`RunnerMain`, et il nomme le drapeau qui **agit** :

```
[10:02:12] INFO  Commandes : autorisees (chacune demande votre autorisation a l'ecran)
```

ou, sous `--no-bash` :

```
[10:02:12] INFO  Commandes : refusees (--no-bash) - seuls les outils fichiers sont disponibles.
[10:02:12] INFO  Relancez sans --no-bash pour autoriser l'execution de commandes.
```

`ToolStack` ne dit plus rien de l'exécution de commandes. Il continue d'annoncer ce que `RunnerMain`
ne peut pas connaître — racine confinée, interpréteur élu, exclusions chargées — et il le fait
toujours **à chaque montage de transport**, délibérément : ces trois lignes attestent que le repli
long-polling monte **les mêmes gardes** que la socket, ce qui est exactement la raison d'être de
`ToolStack` (D6/D10). Ce n'est pas une répétition, c'est une vérification.

Sur un terminal UTF-8 (Linux, macOS, Windows Terminal), la typographie est **conservée à
l'identique** : rien ne change.

Sur un terminal cp850/cp437, chaque caractère que le jeu ne sait pas écrire est **translittéré**
avant impression, jamais remplacé par `?` :

| Écrit dans le code | cp850 / cp437 | US-ASCII |
|---|---|---|
| `…` | `...` | `...` |
| `—` `–` | `-` | `-` |
| `’` `‘` | `'` | `'` |
| `“` `”` | `"` | `"` |
| `«` `»` | `«` `»` (conservés) | `<<` `>>` |
| `é` `à` `ç` | conservés | `e` `a` `c` |
| `€` | `EUR` | `EUR` |

### Cas d'erreur

| Cas | Comportement |
|---|---|
| `stdout.encoding` absent, vide ou illisible | on essaie `native.encoding`, puis `file.encoding`, puis `Charset.defaultCharset()`. Aucune exception ne remonte : une console est un confort, elle ne fait pas échouer un runner. |
| Nom de jeu de caractères inconnu de la JVM (`Charset.forName` lève) | jeu suivant dans la liste ; en dernier recours `Charset.defaultCharset()`. |
| Caractère hors table de translittération et non encodable (idéogramme dans un nom de dossier) | décomposition Unicode NFD et retrait des diacritiques ; si le résultat reste inencodable, le caractère est **retiré** — jamais un `?`, qui est précisément le symptôme qu'on supprime. |
| `--allow-bash` toujours passé sur la ligne de commande | **inchangé** : accepté, sans effet, sans avertissement. La compatibilité de SF-38-19 (D2) n'est pas touchée ; seule la *description* de l'état cesse de le citer. |

---

## Décisions

### D1 — Une seule voix pour l'état d'exécution : `RunnerMain`

L'état vient de `RunnerConfig`, il est connu avant toute connexion, et il ne change plus de la vie du
processus. Il appartient donc au démarrage, pas au montage d'un transport. `ToolStack` le perd.

L'alternative — supprimer la ligne de `RunnerMain` et garder celle de `ToolStack` — a été écartée :
elle aurait laissé l'utilisateur sans réponse pendant la phase d'appairage, précisément là où il se
demande ce que sa machine vient d'accepter, et elle aurait réintroduit la répétition au repli.

### D2 — Translittérer, pas reconfigurer le terminal

On ne cherche pas à imposer UTF-8 : depuis Java, on ne change pas la page de code d'une console
Windows, et écrire des octets UTF-8 dans une console cp850 produit du mojibake — un défaut pire que
celui qu'on corrige, parce qu'il touche aussi les accents. On adapte donc le **texte** au terminal,
et seulement quand c'est nécessaire : si le jeu sait écrire le caractère, il passe intact.

### D3 — La translittération vit dans `Console`, pas dans les messages

Réécrire les messages en ASCII à la source aurait « réglé » le symptôme là où on regardait, et laissé
le prochain message écrit en typographie française rouvrir le défaut. Le point de passage obligé,
c'est `Console.print` : un seul endroit, toute la sortie, y compris les messages futurs.

### D4 — Le message de la gateway est corrigé dans la même livraison

`RunnerToolGateway` sert la même consigne périmée, mais **dans le chat**. C'est le même défaut, une
ligne, un test ; le laisser aurait signifié corriger la console et laisser l'utilisateur lire la
version fausse à l'endroit où il la lira le plus souvent. La correction est backend, elle part avant
le reste.

### D5 — Les javadocs périmées sont corrigées, sans changer un comportement

`BashTool`, `ToolDispatcher` et `RunnerConfig` décrivent encore `--allow-bash` comme l'opt-in — et
`RunnerConfig.allowBash()` se contredit dans sa propre phrase (« **Faux par défaut** […]
**autorisée par défaut** »). Ce sont des commentaires ; ils sont alignés ici parce que le prochain
lecteur de ce code, c'est celui qui écrira le message suivant.

---

## Critères d'acceptation

| # | Critère | Vérification |
|---|---------|--------------|
| CA-1 | Aucune sortie console du runner ne cite `--allow-bash` | `ToolStackAnnouncementTest` — aucune ligne émise par `ToolStack.create` ne contient `allow-bash`, dans les deux états |
| CA-2 | L'état d'exécution est annoncé zéro fois par `ToolStack` | `ToolStackAnnouncementTest` — aucune ligne ne contient « xécution de commandes » |
| CA-3 | Sous `--no-bash`, la console nomme `--no-bash` et dit comment revenir en arrière | `RunnerStartupLinesTest` — les lignes citent `--no-bash` et jamais `--allow-bash` |
| CA-4 | Sur un terminal cp850, `…`, `—`, `’` ne deviennent jamais `?` | `ConsoleEncodingTest` — `IBM850` rend `...`, `-`, `'` |
| CA-5 | Sur un terminal cp850, les accents et les guillemets français sont **conservés** | `ConsoleEncodingTest` — `é` et `«` intacts en `IBM850` |
| CA-6 | Sur un terminal UTF-8, le message est rendu **à l'identique** | `ConsoleEncodingTest` — égalité stricte entrée/sortie |
| CA-7 | Sur un flux US-ASCII, aucun `?` ne subsiste et le texte reste lisible | `ConsoleEncodingTest` — `Réappairage…` → `Reappairage...` |
| CA-8 | Un jeu de caractères illisible ou inconnu ne fait pas échouer le runner | `ConsoleEncodingTest` — propriétés vides / `"charabia-42"` → repli sans exception |
| CA-9 | La gateway ne conseille plus `--allow-bash` dans le chat | `RunnerToolGatewayTest` — le message cite `--no-bash` |
| CA-10 | `--allow-bash` reste accepté sans effet et sans erreur | `RunnerConfigTest` (existant) — inchangé et vert |

---

## Plan de test

### Unitaires — runner

- `ConsoleEncodingTest`
  - `utf8_leaves_the_message_untouched`
  - `cp850_transliterates_what_it_cannot_write` (`…` → `...`, `—` → `-`, `’` → `'`)
  - `cp850_keeps_accents_and_french_quotes`
  - `ascii_strips_accents_instead_of_printing_question_marks`
  - `never_emits_a_question_mark_for_a_character_it_dropped`
  - `unknown_or_blank_charset_falls_back_without_throwing`
- `RunnerStartupLinesTest`
  - `allowed_mode_does_not_mention_the_flag_that_does_nothing`
  - `restricted_mode_names_the_flag_that_acts_and_how_to_undo_it`
- `ToolStackAnnouncementTest`
  - `mounting_the_stack_says_nothing_about_the_execution_state` (les deux états)
  - `mounting_the_stack_never_mentions_allow_bash` (les deux états)
  - `mounting_the_stack_still_announces_root_shell_and_exclusions`

### Unitaires — backend

- `RunnerToolGatewayTest#bash_unsupported_is_translated` — l'assertion passe de `--allow-bash` à
  `--no-bash`.

### Non-régression

- Suite `runner` complète et suite `backend` complète vertes. Aucun test existant modifié hors
  l'assertion ci-dessus.

### Isolation utilisateur

**Sans objet** : aucun accès aux données, aucune requête, aucune table. Le périmètre est
l'affichage local d'un processus lancé par l'utilisateur sur sa propre machine.

---

## Impacts

| Type | Détail |
|------|--------|
| Tables | **aucune** — pas de migration Liquibase |
| Endpoints | **aucun** |
| Composants Angular | **aucun** |
| Classes runner | `Console` (sortie injectable + translittération), `ConsoleEncoding` (**nouveau**), `RunnerMain`, `ToolStack`, javadocs de `BashTool` / `ToolDispatcher` / `RunnerConfig` |
| Classes backend | `RunnerToolGateway` (un message) |
| Contrat runner ↔ gateway | **inchangé** — aucune trame modifiée |

### Préoccupations transversales

| Préoccupation | Concernée ? | Composants impactés |
|---|---|---|
| Auth / Principal | non | — |
| Contexte tenant | non | — |
| Plans / limites | non | — |
| Navigation / routing | non | — |

Aucune préoccupation transversale n'est cochée : la subfeature ne touche ni l'authentification, ni la
résolution du tenant, ni les quotas, ni le routage. `RunnerToolGateway.bash` est modifié **après**
l'appel, sur le libellé d'une erreur déjà produite — ni sa signature, ni son contrôle d'accès, ni son
`workspaceId` ne bougent.

---

## Hors périmètre

- **Ne pas retirer `--allow-bash`.** Il reste accepté sans effet ; le supprimer casserait les lignes
  de commande copiées d'écrans et de notes antérieurs à SF-38-19 (D2 de SF-38-19).
- **Ne pas déplacer les lignes « racine / interpréteur / exclusions » hors de `ToolStack`.** Leur
  répétition par transport est voulue (D1).
- **Ne pas toucher `RunnerLauncher`.** Il est compilé pour Java 8, s'exécute avant tout le reste et
  est déjà écrit en ASCII pur : il ne peut pas et n'a pas besoin d'utiliser `Console`.
- **Ne pas translittérer la sortie des commandes exécutées.** Elle ne passe pas par `Console` : elle
  part sur le canal vers la gateway, en UTF-8, et y reste.
- **Aucune réécriture du protocole, du transport, de l'appairage ou du confinement.**

---

## Journal

| Date | Événement |
|------|-----------|
| 2026-09-08 | Mini-spec rédigée à partir de la capture de démarrage du banc d'essai |
| 2026-09-08 | Livrée — PR #285 |
