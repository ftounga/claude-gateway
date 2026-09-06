# Banc d'essai — Runner local (F-38)

**Date de rédaction** : 2026-09-06 · **Statut** : **exécuté deux fois le 2026-09-06** — ce qu'il a produit est au §8
**Rédigé avant** la livraison de la vague F-39, délibérément : un banc d'essai écrit après coup teste
ce que le produit fait, pas ce qu'il doit faire.

---

## 1. Objet

Prouver, en conditions réelles, que le mode runner permet à Claude de **construire un projet complet
sur la machine de l'utilisateur** — pas de lire trois fichiers et de commenter.

**Scénario retenu** : créer de zéro, dans `~/dev/runner-claude`, une application fullstack conforme à
ce que produit la skill `/init-fullstack` — Java 21 / Spring Boot 3.5 / Angular 19 / Maven / H2 /
Liquibase / Docker Compose, plus le framework de gouvernance et le design system.

Ce scénario a été choisi parce qu'il exerce **tout ce que le sandbox ne peut pas faire** :

| Ce qu'il exige | Pourquoi c'est décisif |
|---|---|
| Accès réseau sortant depuis la machine | `start.spring.io`, `npm`, Maven Central |
| Accès à des fichiers **hors du projet** | le template vit dans `~/dev/.legalcase-template/` |
| Outils installés localement | `java 21`, `node 22` via `nvm`, `npm`, `docker` |
| Des dizaines d'étapes | 13 étapes de skill, builds compris |
| Écriture massive de fichiers | arborescence complète d'un monorepo |

Un bac à sable hébergé échouerait sur les lignes 2 et 3. C'est exactement la raison d'être de F-38.

---

## 2. Ce que le banc d'essai valide

| # | Point | Origine | Statut avant essai |
|---|---|---|---|
| V1 | Appairage réel : code → jeton persisté | SF-38-01/03 | jamais testé hors tests unitaires |
| V2 | Connexion WSS sortante + heartbeat | SF-38-03 | idem |
| V3 | Porte de confirmation avant chaque `bash` | SF-38-08 | idem |
| V4 | Sortie de commande relayée au fil de l'eau | SF-38-07 | idem |
| V5 | `Ctrl-C` / interruption d'une commande longue | SF-38-07/11 | idem |
| V6 | Bascule long-polling derrière un proxy | SF-38-09 | idem |
| V7 | Journal d'audit alimenté | SF-38-08 | idem |
| V8 | Exclusions `.runnerignore` | SF-38-10 | idem |
| **V9** | **Un `write_file` volumineux ne condamne plus le projet** | **SF-28-18** | déployé le 2026-09-06 |
| **V10** | **Une tâche de plus de 12 étapes aboutit** | **SF-28-19** | déployé le 2026-09-06 |
| V11 | Le cache de prompt mord réellement | SF-39-01 | mergé, à déployer |

---

## 3. Deux constats à faire avant de commencer

### 3.1 Un projet runner se crée aujourd'hui par un détour

À la création d'un projet, l'Atelier ne propose que **deux sources** : archive `.zip` et dépôt
GitHub (`WorkspaceSource` = `ARCHIVE` | `GIT`). Aucune des deux n'a de sens quand le projet vit déjà
sur la machine de l'utilisateur — et ici, il n'existe même pas encore.

**Contournement pour ce banc d'essai** : créer un projet à partir d'une archive `.zip` minimale
(un `README.md` suffit), puis basculer sa **cible d'exécution** sur `RUNNER`. La source devient alors
sans objet : en cible `RUNNER`, les outils lisent et écrivent sur la machine, jamais dans le stockage.

C'est un détour, pas une fonctionnalité. **Voir §7 — ce que le banc d'essai doit faire remonter.**

### 3.2 `bash` n'est pas confiné dans ce qu'il touche

Vérifié dans le code : le `PathGuard` confine le **répertoire de travail** (`cwd`) et les **outils
fichiers** (`read_file`, `write_file`) à la racine `--workspace`. Il ne filtre pas ce qu'une commande
`bash` fait ensuite : `cp -r ~/dev/.legalcase-template/project-governance .` fonctionne.

C'est cohérent — un shell est un shell, et c'est la **porte de confirmation** qui joue le rôle de
garde, pas un filtre de chemins. Mais il faut le savoir avant de lancer l'essai, et le banc d'essai
doit le **confirmer explicitement** (V3) : chaque commande passe par une autorisation.

---

## 4. Préparation

`~/dev/runner-claude` **existe déjà et est vide** (créé le 2026-09-06 à 01:29). Il n'est pas encore
un dépôt git — et la procédure de référence **s'arrête net** si ce n'est pas le cas (étape 2a :
« ERREUR : ce répertoire n'est pas un dépôt git »). C'est donc le seul geste de préparation qui reste
sur le dossier lui-même.

```bash
# 1. Le dépôt git, exigé par la procédure (le dossier, lui, existe déjà et reste vide)
cd ~/dev/runner-claude && git init && git status --short

# 2. Une archive minimale pour créer le projet côté Atelier — fabriquée DANS /tmp,
#    pour ne rien déposer dans le dossier que l'agent doit construire lui-même
mkdir -p /tmp/rc-seed && printf '# runner-claude\n' > /tmp/rc-seed/README.md
(cd /tmp/rc-seed && zip -q /tmp/runner-claude-seed.zip README.md) && ls -la /tmp/runner-claude-seed.zip

# 3. Vérifier l'outillage que la procédure exige
java -version 2>&1 | head -1        # attendu : 21
source ~/.nvm/nvm.sh && nvm use 22 && node -v
docker --version
```

**Le dossier doit rester vide** (hors `.git`) au moment de lancer l'essai : tout ce qui s'y trouvera
ensuite aura été écrit par l'agent, et c'est précisément ce qu'on mesure.

Puis, dans l'application :

1. **Atelier → nouveau projet** → importer `/tmp/runner-claude-seed.zip`, le nommer `runner-claude`.
2. Basculer sa **cible d'exécution** sur **« ma machine (runner) »**.
3. Télécharger le jar depuis l'écran d'appairage et relever le **code d'appairage**.
4. Lancer le runner :

```bash
java -jar ~/Downloads/claude-runner.jar \
  --gateway https://portal.ng-itconsulting.com/api \
  --workspace ~/dev/runner-claude \
  --code <CODE_AFFICHÉ> \
  --label poste-dev
```

**Attendu** : `runner connecté` en console, et l'écran de l'Atelier signale le runner présent.
→ **V1 et V2 validés ici**, avant même le premier message.

---

## 5. Le prompt à coller dans le terminal de l'Atelier

> Tu travailles sur ma machine, dans le dossier `runner-claude`, via le runner. Tu as `bash`.
>
> **Objectif** : construire ici une application fullstack complète, conforme à la stack et à la
> gouvernance standard de mes projets.
>
> La procédure de référence est un fichier de mon disque :
> `~/.claude/skills/init-fullstack/SKILL.md`. **Lis-le d'abord** (`cat`), puis exécute-le de bout en
> bout. Le template de gouvernance qu'il copie se trouve dans `~/dev/.legalcase-template/`.
>
> Ces deux chemins sont **hors du dossier du projet** : utilise `bash` pour les lire, pas les outils
> fichiers, qui sont confinés à la racine du projet.
>
> **Valeurs à utiliser, ne me les redemande pas** (la procédure prévoit un questionnaire — saute-le) :
> - nom du projet : `runnerclaude`
> - répertoire cible : le dossier courant
> - description : plateforme de démonstration servant de banc d'essai au runner local ; utilisateurs
>   internes ; pas de domaine métier particulier
> - package Java : `fr.runnerclaude`
> - colonne d'isolation multi-tenant : `none`
> - concept tenant : `none`
>
> **Contraintes** :
> - Suis les 13 étapes dans l'ordre, y compris l'étape 13 (builds backend **et** frontend).
> - Commence par un plan en 5 lignes, puis exécute sans me redemander de valider chaque étape.
> - Si une commande échoue, dis-le, diagnostique, corrige — ne fais pas semblant d'avoir réussi.
> - À la fin, affiche l'arborescence produite et le résultat exact des deux builds.

**Note** : ce prompt demande délibérément à l'agent de **lire une procédure sur le disque** plutôt
que de la lui recopier. C'est la manière la plus honnête de tester le runner — un agent qui n'aurait
pas réellement accès à la machine échouerait dès le `cat`.

---

## 6. Grille de relevé

Cocher au fil de l'essai. Une case non cochée est un résultat, pas un oubli.

### Phase A — Connexion

| | Attendu | Constaté |
|---|---|---|
| A1 | Appairage accepté, jeton écrit dans `~/dev/runner-claude/.claude-runner/token.json`, permissions `600` | |
| A2 | `runner connecté` en console ; l'écran signale le runner | |
| A3 | Un `heartbeat_ack` toutes les 30 s dans la console | |
| A4 | Relancer le runner **sans** `--code` : il réutilise le jeton, ne réappaire pas | |

### Phase B — Premier travail

| | Attendu | Constaté |
|---|---|---|
| B1 | Le `cat` de `SKILL.md` **hors racine** aboutit via `bash` | |
| B2 | **Chaque** commande déclenche une demande d'autorisation, et rien ne part avant la réponse | |
| B3 | La sortie des commandes s'affiche **au fil de l'eau**, pas d'un bloc à la fin | |
| B4 | Refuser une commande : l'agent reçoit le motif et propose autre chose, sans se bloquer | |

### Phase C — Le travail long (le cœur de l'essai)

| | Attendu | Constaté |
|---|---|---|
| C1 | La tâche dépasse **12 étapes** sans être coupée — *ce qui était impossible avant SF-28-19* | |
| C2 | Les fichiers volumineux (`application.yml`, `CLAUDE.md`, composants Angular) s'écrivent **entièrement** — *impossible avant SF-28-18 au-delà de ~12 Ko* | |
| C3 | Aucune réponse vide ni tronquée en silence ; si coupure il y a, elle est **dite** | |
| C4 | `curl start.spring.io` aboutit → le backend est généré | |
| C5 | `npm install` puis `ng build` aboutissent | |
| C6 | Le projet est réellement sur le disque : `ls -R ~/dev/runner-claude` le montre | |

### Phase D — Contrôle et garde-fous

| | Attendu | Constaté |
|---|---|---|
| D1 | Interrompre pendant un `npm install` : la commande est **tuée**, la console du runner le montre | |
| D2 | Après interruption, un nouveau message repart normalement | |
| D3 | Le journal d'audit liste les commandes, y compris les refusées | |
| D4 | Poser un `.runnerignore` (ex. `.env`) puis demander la lecture du fichier exclu → refus | |
| D5 | Couper le runner en pleine tâche : message clair côté écran, pas de plantage | |

### Phase E — Ce qu'on découvre malgré nous

| | Attendu | Constaté |
|---|---|---|
| E1 | L'agent se souvient-il, au **deuxième message**, de ce qu'il a fait au premier ? *(non attendu avant SF-39-03)* | |
| E2 | L'explorateur de fichiers montre-t-il quelque chose ? *(non — le stockage est vide en mode runner)* | |
| E3 | Combien de tokens le tour a-t-il coûté ? Le cache a-t-il mordu ? | |

### Vérification finale, sur la machine

```bash
cd ~/dev/runner-claude
ls -la && git status --short | head -20
cd backend && ./mvnw -q clean package -DskipTests && echo "BACKEND OK"
source ~/.nvm/nvm.sh && nvm use 22 && cd ../frontend && npm run build && echo "FRONTEND OK"
```

**Critère de réussite global** : les deux builds passent, et le projet a été construit **par
l'agent**, pas réparé à la main. Toute intervention manuelle est notée dans la grille — elle
transforme une réussite en réussite assistée, ce qui n'est pas la même chose.

---

## 7. Ce que le banc d'essai doit faire remonter

Ces points ne sont **pas** des tests : ce sont des questions produit que l'essai va rendre concrètes.

### 7.1 Créer un projet runner sans détour — source `LOCAL`

Le §3.1 le montre : aujourd'hui, on crée un projet runner en important une archive dont on n'a que
faire. Il manque une troisième source : **désigner un dossier de la machine**.

Ce que ça suppose : à la création, l'utilisateur choisit « sur ma machine », obtient un code
d'appairage, lance le runner avec `--workspace <son dossier>`, et **c'est le runner qui déclare la
racine** — la gateway ne connaît jamais le chemin absolu, conformément au principe du `PathGuard`
(les messages d'erreur ne citent que des chemins relatifs). Le projet n'a alors ni archive, ni dépôt :
il a une machine.

### 7.2 L'explorateur en mode runner — lire, plutôt que synchroniser

Le besoin est juste : en mode runner, l'explorateur de fichiers ne sert à rien puisqu'il lit le
stockage objet, vide par construction.

Deux moyens, et ils ne se valent pas :

| | Synchroniser les deux répertoires | **Lire à la demande via le runner** |
|---|---|---|
| Source de vérité | deux (disque + stockage) | une (le disque) |
| Divergence possible | oui — c'est exactement le problème que F-31 SF-31-12/13 a mis trois subfeatures à résoudre | non |
| Fichiers privés copiés chez nous | oui, tout le projet | non |
| Volume / coût | proportionnel au projet | nul |
| Runner déconnecté | consultation possible | « projet hors ligne » |
| Travail à faire | synchro, conflits, purge, quota | brancher l'explorateur sur `list_files` / `read_file`, **qui existent déjà** |

**Recommandation** : lire à la demande. Le runner expose déjà `list_files` et `read_file` ; les
brancher sur l'explorateur suffit, sans copier une ligne de code source de l'utilisateur dans notre
stockage. La consultation hors ligne, si elle est un jour demandée, sera un cache explicite et
consenti — pas un effet de bord de l'architecture.

L'objection à la synchronisation n'est pas théorique : c'est littéralement le défaut que F-31 a mis
trois subfeatures à corriger — « le stockage porte le travail en cours, la branche porte le publié ».
Ajouter une troisième copie relancerait la même bataille.

### 7.3 Points à trancher après l'essai

Les trois points ont été instruits à la clôture du 2026-09-06. **Deux sont tranchés, un reste ouvert.**

- **Un projet `LOCAL` doit-il pouvoir changer de machine, ou reste-t-il lié à une seule ?**
  → **Toujours ouvert.** Rien ne l'interdit dans le code : un nouveau code d'appairage échangé
  depuis une autre machine délivre un **jeton de plus** sur le même workspace et réécrit
  `runner_root_name` / `runner_elevated` avec ce que le nouveau runner déclare. Mais **aucun écran
  ne le dit et aucun test ne le fige** — c'est un comportement subi, pas une décision. À trancher
  avant qu'un utilisateur ne le découvre seul, notamment : que devient le jeton de l'ancienne
  machine, et l'écran doit-il montrer *les* runners d'un projet plutôt que *le* runner.
- **Que voit l'écran quand le runner d'un projet `LOCAL` est déconnecté depuis longtemps ?**
  → **Tranché par SF-38-17** (PR #240) : « Projet hors ligne : lancez le runner pour parcourir les
  fichiers » — jamais une arborescence vide, qui laisserait croire que le projet a été effacé.
  La consultation hors ligne, si elle devient un besoin avéré, sera un **cache explicite et
  consenti**, pas un effet de bord de l'architecture.
- **Le `.runnerignore` doit-il avoir des exclusions par défaut (`.env`, `node_modules`, `.git`) ?**
  → **Tranché par SF-38-21** (PR #251) : oui pour le **bruit de construction** — vingt motifs
  (`node_modules`, `target`, `build`, `dist`, `.angular`, `.gradle`, `.venv`, `__pycache__`,
  `vendor`, `coverage`, `.terraform`…) actifs sans aucun fichier de règles. Et **négociables** :
  ils sont évalués **avant** les règles de l'utilisateur, si bien qu'un `!node_modules/` explicite
  les annule. La liste de **secrets** de SF-38-10 reste, elle, non désactivable — on écarte du
  bruit, on ne protège pas un secret de la même façon.

---

## 8. Ce que les deux passages ont produit (clôture, 2026-09-06)

Le banc d'essai a été **joué deux fois le 2026-09-06**. Il a tenu la promesse du §1 : l'application
fullstack a fini par être construite sur la machine de l'utilisateur — sept étapes de procédure,
**2,9 Mo écrits sur le disque** — mais pas du premier coup, et **aucun** des défauts rencontrés
n'était visible en CI.

**Premier passage — avant même la première commande.** Les deux constats du §3 se sont vérifiés sur
pièces : un projet runner ne se créait que par un détour (§3.1) et l'explorateur lisait un stockage
objet vide par construction (§3.2). Trois subfeatures en sont sorties le matin même —
**SF-38-15** source `LOCAL`, le runner déclare la racine et la gateway n'apprend jamais le chemin
absolu (PR #238, migration `052`) ; **SF-38-16** écran « sur ma machine » (PR #239) ; **SF-38-17**
explorateur branché sur `list_files` / `read_file` du runner au lieu de copier le projet chez nous
(PR #240), c'est-à-dire la recommandation du §7.2 retenue telle quelle.

**Second passage — le scénario joué en entier.** Huit défauts, dans l'ordre où ils sont tombés :

| # | Ce que le banc d'essai a montré | Réponse | PR |
|---|---|---|---|
| 1 | La commande d'appairage copiée depuis l'écran menait au **frontend**, pas à l'API : `405` au premier geste | correctif — l'écran compose `--gateway <origine>/api` | #244 |
| 2 | Le runner refusait d'exécuter : « redémarre-le avec `--allow-bash` », alors que le mode runner **existe pour exécuter** | **SF-38-19** — exécution par défaut, `--no-bash` pour restreindre à la lecture ; la garde réelle est la porte de confirmation | #249 |
| 3-4 | Un tour long se perdait : écran figé sans message, et un rechargement ne rendait que la dernière ligne | **SF-39-17** — chaîne de délais *tour 600 s < flux 900 s ≤ ingress 900 s*, transcription enfin persistée **sur la boucle maison** | #245 |
| 5 | Basculer sur l'explorateur **pendant** un tour détruisait le composant terminal, donc le flux SSE : un geste normal tuait le travail en cours | **SF-39-18** — l'explorateur devient un **panneau dans la vue**, ouvert par un paramètre de requête | #246 (correctif #250) |
| 6 | Treize étapes de procédure, plusieurs commandes chacune, **une demande d'autorisation à cliquer par commande** | **SF-38-20** — « Tout autoriser pour ce message » et projet qui ne demande plus ; amende D7 de SF-38-08 **sans toucher** au journal d'audit ni au coupe-circuit : c'est le clic qui disparaît, jamais la trace | #247, #248 |
| 7 | L'explorateur montrait **40 112** fichiers de `node_modules` pour **478** de projet, et se **taisait** en tronquant — dix minutes à chercher un dossier que le système savait ne pas avoir envoyé | **SF-38-21** — bruit de construction écarté par défaut (négociable), troncature annoncée | #251 |
| 8 | Rien ne disait **avec quels droits** le runner agit (question posée pendant la préparation ; le README était ambigu) | **SF-38-18** — droits détectés par le runner, déclarés à l'appairage, affichés là où l'on autorise une commande ; informatif, jamais une garde | #252 (migration `053`) |

**Ce que cela dit du banc d'essai lui-même.** Neuf subfeatures et trois correctifs dans la journée,
et pas un seul de ces défauts n'aurait été trouvé par la suite de tests : ils demandent tous une
vraie machine, un vrai projet et un tour qui **dure**. C'est la raison de le rejouer après chaque
lot qui touche au runner ou à la boucle maison.

**Ce qui reste hors de sa portée** : le smoke d'**exploitation** — appairage sur une machine tierce,
repli long-polling derrière un proxy qui coupe l'`Upgrade`, `Ctrl-C` — protocole
`SMOKE-manuel-bout-en-bout.md`, remis au niveau du lot livré le 2026-09-06, planification demandée
au PO en **OQ-13**.
