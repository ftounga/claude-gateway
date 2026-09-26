# Mini-spec — `SESSIONS-PARALLELES` / SF-SP-03 — La pile de remise est partagée : la voir avant de la dépiler

> Feature : `REPO` — housekeeping du dépôt Git.
> Aucun code applicatif, aucune migration, aucun endpoint, aucun écran, aucun déploiement.

---

## Identifiant

`SESSIONS-PARALLELES / SF-SP-03`

## Feature parente

`REPO` — Hygiène du dépôt (housekeeping Git), déjà référencée dans `docs/PRODUCT_SPEC.md`
(historique des 2026-08-25, 2026-08-26, 2026-09-06). Lignée `REPO` : pas de ligne de statut dans
le tableau des features, une entrée d'historique par subfeature.

Lignée : `REPO / SF-REPO-01` → `SF-REPO-02` (`prune-stale-worktrees.sh`) →
`worktrees-orphelins / SF-WO-01` → `SESSIONS-PARALLELES / SF-SP-01` (contrôle de vol, PR #256) →
`SF-SP-02` (la purge le respecte, PR #257) → `BRANCHES-ORPHELINES / SF-BO-01` (PR #258) →
**SF-SP-03**.

**Aucune feature nouvelle n'est créée.**

## Statut

`done`

## Date de création

2026-09-26

## Branche Git

`chore/SF-SP-03-pile-de-remise-partagee`

---

## Objectif (une phrase)

Rendre **visible et bloquante au bon moment** la dernière zone partagée entre sessions parallèles
que le contrôle de vol ne regardait pas — la **pile de remise `git stash`**, commune au checkout
principal et à **tous** les worktrees — pour qu'aucune session ne dépile, n'écrase ni ne détruise
le travail mis de côté par une autre.

---

## Contexte — ce que le mandat demande et ce qui manquait

Le mandat de la feature tient en trois règles :

> « Vague lançable immédiatement ; travailler en **worktrees isolés** (jamais le checkout partagé),
> **`git add` ciblé**, **jamais `git stash` nu**. »

État constaté avant cette subfeature (vérifié : recherche du mot « stash » sur tout le dépôt) :

| Règle | Outillée ? | Écrite ? |
|---|---|---|
| Vague lançable immédiatement | **oui** — `scripts/check-parallel-sessions.sh` (SF-SP-01) rend `CLEAR`/`BUSY` | non citée dans la skill de vague |
| Worktrees isolés | partiellement — `git worktree` utilisé par la vague | écrite **pour le déploiement seulement** (`ai-skills/autonomous-delivery-wave.md`) |
| `git add` ciblé | non outillable simplement | **oui**, tableau des règles apprises (commit `b17b677d`) |
| **Jamais `git stash` nu** | **non** | **nulle part** — une seule occurrence du mot « stash » dans tout le dépôt, en incise d'une autre règle |

La troisième règle était donc la seule à n'exister ni en script, ni en consigne. Or elle porte sur
la zone la plus dangereuse du dépôt, et la seule que les cinq signaux de SF-SP-01 (W1 worktree
actif, W2 checkout sale, W3 checkout en avance, W4 PR ouverte, W5 branche partagée) ne couvrent
pas.

**Fait vérifié expérimentalement** (dépôt jetable, un worktree lié) : `refs/stash` vit dans le
**git-dir commun**. Une remise poussée depuis un worktree lié apparaît telle quelle dans la liste
des remises du checkout principal et de tous les autres worktrees. La pile n'est pas
per-worktree : elle est **globale au dépôt**.

Trois conséquences, toutes des pertes de travail silencieuses :

| Situation | Conséquence |
|---|---|
| Session A remise nue ; session B dépile | B **restaure le travail de A dans son propre arbre**, sur une autre branche, et **retire l'entrée** : A ne retrouve rien |
| Deux sessions remisent puis dépilent | l'ordre `stash@{0}` a changé entre-temps : chacune dépile l'entrée de l'autre |
| Une remise anonyme traîne (`WIP on main: …`) | **personne ne peut dire à qui elle appartient** : ni la garder en conscience, ni la jeter |

Le pire moment est précis : **entre la mise de côté et la restauration, le travail d'une session
n'existe que dans la pile** — pas dans un commit, pas dans un arbre de travail. C'est exactement
l'instant où `prune-stale-worktrees.sh --apply` verrait un worktree « propre et mergé » et le
jugerait supprimable.

---

## Comportement attendu

### Cas nominal — le contrôle de vol regarde aussi la pile

`scripts/check-parallel-sessions.sh` gagne une section « Pile de remise » et deux signaux :

| Signal | Définition | Effet sur le verdict |
|---|---|---|
| **W6** | au moins une remise **créée il y a moins de `--age-minutes`** | **BUSY** — une session est en plein cycle « mettre de côté / restaurer » |
| **I4** | remises plus anciennes | **informatif** — résidu de pile, jamais bloquant |

Pour chaque entrée sont affichés : son sélecteur (`stash@{n}`), son âge en clair, son message
(qui porte la branche d'origine) et sa **nature** :

* **nommée** — message `On <branche>: <texte>`, poussée avec un message explicite : attribuable ;
* **anonyme** — message `WIP on <branche>: <sha> <sujet>`, poussée par une **remise nue** :
  **non attribuable**, donc à ne jamais dépiler.

Le nombre de remises anonymes est repris dans le bloc verdict, qu'elles soient récentes ou non :
c'est le compteur de la règle « jamais de remise nue ».

`--no-stash` ignore le signal (symétrie avec `--no-gh`). `--age-minutes 0` désactive W6 comme il
désactive déjà W1.

### Cas nominal — la purge hérite du garde-fou, sans une ligne de code

`prune-stale-worktrees.sh --apply` appelle déjà le contrôle (garde-fou 5, SF-SP-02) en lui
passant son propre `--age-minutes`, et sort **5** sur `BUSY`. W6 entrant dans le verdict, **une
remise récente refuse désormais la purge** : aucune modification du script de purge n'est requise,
seulement un test qui fige l'héritage.

### Cas d'erreur

| Cas | Comportement |
|---|---|
| `refs/stash` absente (aucune remise jamais poussée) | section affichée « (pile vide) », aucun signal, verdict inchangé |
| Pile illisible (lecture du reflog en échec) | **compte comme occupé par prudence** — même doctrine que le statut Git illisible de SF-SP-01 |
| `--no-stash` passé | section affichée « (signal ignoré) », W6 et I4 muets |
| Horodatage de remise dans le futur (horloge faussée) | âge borné à 0 → traité comme récent, donc BUSY : le sens du garde-fou est de se tromper du côté prudent |

### Lecture seule — inchangé

Le contrôle reste **strictement en lecture seule**, y compris sur l'index (`--no-optional-locks`,
correctif du cas 13 de SF-SP-02). La lecture du reflog de `refs/stash` n'écrit rien et ne modifie
jamais la pile. La non-destructivité de la pile est testée explicitement (cas 17).

### Les trois règles du mandat, écrites là où les agents les lisent

`ai-skills/autonomous-delivery-wave.md` — tableau « Règles apprises en production » :

1. **nouvelle ligne** « jamais de remise nue », avec le protocole de survie :
   commit WIP de préférence ; sinon une remise **taguée** (`push -u -m "<tag unique>"`), relever
   son SHA, restaurer par `apply <sha>` (**jamais `pop`**), puis retrouver l'entrée **par son tag**
   avant de la retirer ;
2. **ligne « worktree isolé » élargie** : elle ne parlait que du déploiement, elle vaut pour
   **tout** le travail d'un agent ;
3. **Phase 0** : `scripts/check-parallel-sessions.sh` cité comme premier geste — l'outil existait
   depuis SF-SP-01 et la skill ne le nommait nulle part.

`CLAUDE.md` n'est **pas** modifié : un agent ne modifie pas les instructions projet ni la
configuration sur la seule foi d'un mandat de script.

---

## Critères d'acceptation vérifiables

1. Une remise poussée il y a moins de `--age-minutes` fait rendre **BUSY** (sortie 1) au contrôle,
   avec la mention `W6`.
2. La même remise, avec `--age-minutes 0`, laisse le verdict **CLEAR** (sortie 0).
3. La même remise, avec `--no-stash`, laisse le verdict **CLEAR** (sortie 0).
4. Une remise ancienne (créée 3 h plus tôt) laisse le verdict **CLEAR** et apparaît en `I4`.
5. Une remise anonyme est signalée **anonyme** et comptée dans le verdict, qu'elle soit récente ou
   ancienne.
6. Une remise nommée est signalée **nommee**.
7. Une remise poussée **depuis un worktree lié** est vue par le contrôle exécuté depuis le
   **checkout principal** (preuve que la pile est partagée).
8. Le contrôle ne modifie pas la pile : liste des remises identique avant/après.
9. `prune-stale-worktrees.sh --apply` sort **5** et ne détruit rien tant qu'une remise récente est
   en pile ; contrôle négatif : la même purge, remise vieillie, retire bien le worktree candidat.
10. Aucune régression : les assertions existantes (contrôle + purge) restent vertes.
11. `ai-skills/autonomous-delivery-wave.md` contient la règle « jamais de remise nue » avec son
    protocole, la règle worktree élargie et l'appel au contrôle en Phase 0.

---

## Plan de test minimal

Tests d'intégration en dépôt jetable (`mktemp -d`, config utilisateur neutralisée), dans les
suites existantes — **chaque cas `BUSY` accompagné de son contrôle négatif**, doctrine SF-REPO-02.

`scripts/check-parallel-sessions.test.sh` :

| Cas | Contenu |
|---|---|
| 15 | **W6** : remise récente anonyme poussée dans le checkout principal → BUSY, mention `W6`, mention `anonyme` ; contrôles négatifs `--age-minutes 0` → CLEAR et `--no-stash` → CLEAR |
| 16 | **I4** : remise nommée vieillie de 3 h → CLEAR, mention `I4`, mention `nommee`, comptée en résidu de pile |
| 17 | **pile partagée + non-destructivité** : remise poussée **depuis un worktree lié**, vue depuis le principal ; liste des remises identique avant/après exécution |

`scripts/prune-stale-worktrees.test.sh` :

| Cas | Contenu |
|---|---|
| 19 | **héritage du garde-fou 5** : remise récente en pile → `--apply` sort **5**, worktree candidat toujours présent ; contrôle négatif : remise vieillie → la purge retire le candidat |

Pas de test unitaire Java ni Angular : **aucun code applicatif n'est touché**. Pas de test
d'isolation `user_id` : **aucun accès aux données**.

**Contrôle d'utilité obligatoire** (leçon SF-REPO-02) : chaque nouveau cas est rejoué contre
l'implémentation *d'avant* le correctif, par mutation, et doit échouer.

---

## Fichiers impactés

| Fichier | Nature |
|---|---|
| `scripts/check-parallel-sessions.sh` | section « Pile de remise », signaux W6 / I4, option `--no-stash`, verdict enrichi |
| `scripts/check-parallel-sessions.test.sh` | cas 15, 16, 17 |
| `scripts/prune-stale-worktrees.test.sh` | cas 19 (héritage) |
| `ai-skills/autonomous-delivery-wave.md` | règle « jamais de remise nue », règle worktree élargie, contrôle de vol en Phase 0 |
| `docs/features/SESSIONS-PARALLELES/SF-SP-03-pile-de-remise-partagee.md` | cette mini-spec |
| `docs/PRODUCT_SPEC.md` | une ligne d'historique (étape 6, après merge) |

Aucun fichier `backend/`, `frontend/`, `k8s/`, aucune migration Liquibase.

---

## Préoccupations transversales

| Préoccupation | Cochée ? | Analyse d'impact |
|---|---|---|
| Auth / Principal | non | aucun code applicatif |
| Contexte tenant / `user_id` | non | aucun accès aux données |
| Plans / limites | non | — |
| Navigation / routing | non | aucun écran |
| **Outillage partagé** | **oui** | **composants impactés listés** : `scripts/check-parallel-sessions.sh` (modifié), `scripts/prune-stale-worktrees.sh` (**non modifié**, consommateur du verdict via garde-fou 5 — l'héritage est figé par le cas 19), `scripts/lib/git-worktrees.sh` (**non modifié** : la pile n'est pas per-worktree, rien à mutualiser), `ai-skills/autonomous-delivery-wave.md` (consigne). Les deux suites de tests sont rejouées en entier. |

---

## Arbitrages (gates réversibles, décidés par défaut)

| # | Décision | Pourquoi | Alternative écartée | Réversible |
|---|---|---|---|---|
| A | Une remise **récente** vaut « session en vol » → **BUSY** | seul instant où du travail n'existe **que** dans la pile ; c'est aussi la doctrine de SF-SP-01 (« seule l'activité récente vaut session en vol ») | tout en informatif : le garde-fou ne protégerait rien au seul moment où il compte | oui |
| B | Une remise **ancienne** reste **informative** | la pile accumule des résidus comme les worktrees ; les compter BUSY rendrait le verdict rouge en permanence — défaut déjà évité en SF-SP-01 | BUSY dès qu'une remise existe | oui |
| C | Le **même seuil `--age-minutes`** que W1, pas un seuil dédié | un seuil de plus à régler pour la même notion (« récent ») ; `0` désactive les deux d'un coup | `--stash-age-minutes` séparé | oui |
| D | `--no-stash` fourni, **pas** de `--require-stash` | symétrie avec `--no-gh` ; la pile est toujours lisible en local, contrairement à `gh` — il n'y a pas d'indisponibilité à exiger | option symétrique inutile | oui |
| E | La **nature anonyme** est signalée mais **ne suffit pas** à rendre BUSY | une remise anonyme ancienne est un déchet, pas une session ; la signaler suffit à appliquer la règle du mandat | anonyme ⇒ BUSY : verdict rouge permanent dès qu'une remise nue traîne | oui |
| F | **Aucune modification** de `prune-stale-worktrees.sh` | il consomme déjà le verdict ; ajouter une lecture de la pile de son côté dupliquerait la logique — la duplication est exactement l'erreur corrigée par la bibliothèque partagée en SF-SP-02 | garde-fou 6 dédié dans la purge | oui |
| G | `CLAUDE.md` **non modifié** ; les règles vont dans la skill de vague | un agent ne modifie pas les instructions projet sur mandat d'un script ; la skill est le document que lisent les agents de vague | écrire la règle dans `CLAUDE.md` | oui |

---

## Dépendances

* SF-SP-01 (contrôle de vol) — livrée, PR #256.
* SF-SP-02 (garde-fou 5 dans la purge) — livrée, PR #257 : c'est elle qui rend l'héritage gratuit.
* Aucune dépendance applicative.

---

## Hors périmètre

* **Empêcher techniquement** une remise nue par un hook : aucun point d'accroche Git n'existe pour
  la mise en remise, et un hook global sortirait du dépôt.
* **Nettoyer** la pile (retrait d'entrées) : opération destructive, jamais faite par un agent
  isolé — cohérent avec « l'exécution `--apply` reste à l'opérateur ».
* Toute modification de `CLAUDE.md`, de la configuration du harnais ou des permissions.
* Tout code applicatif, toute migration, tout écran, tout déploiement.
