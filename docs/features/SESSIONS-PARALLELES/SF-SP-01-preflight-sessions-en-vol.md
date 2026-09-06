# Mini-spec — `SESSIONS-PARALLELES` / SF-SP-01 — Le contrôle de vol avant vague

> Feature : `REPO` — housekeeping du dépôt Git.
> Aucun code applicatif, aucune migration, aucun endpoint, aucun écran, aucun déploiement.

---

## Identifiant

`SESSIONS-PARALLELES / SF-SP-01`

## Feature parente

`REPO` — Hygiène du dépôt (housekeeping Git), déjà référencée dans `docs/PRODUCT_SPEC.md`
(historique des 2026-07-01, 2026-07-10, 2026-08-25, 2026-08-26 et 2026-09-06).

Lignée : `worktree_doc_cleanup` (SF-CLEANUP-01) → `stale-worktrees-housekeeping` (SF-HK-01) →
`REPO / SF-REPO-01` (purge `feat/SF-28-*`) → `REPO / SF-REPO-02`
(`scripts/prune-stale-worktrees.sh`) → `worktrees-orphelins / SF-WO-01` (balayage global des
branches) → **SF-SP-01**.

**Aucune feature nouvelle n'est créée.**

## Statut

`done`

## Date de création

2026-09-06

## Branche Git

`chore/SF-SP-01-preflight-sessions-en-vol`

---

## Objectif (une phrase)

Rendre **reproductible et vérifiable** le contrôle « une autre session Claude travaille-t-elle en
ce moment dans ce dépôt ? », aujourd'hui refait à la main en prose avant chaque vague, en le
livrant sous forme d'un script **strictement en lecture seule** qui rend un verdict binaire
`CLEAR` / `BUSY` motivé signal par signal.

---

## Contexte — ce que le mandat constate et ce qui manque

Le mandat de cette feature **est** le contrôle, écrit à la main :

> « HEAD == origin/main (9bd34ca), working tree propre, `gh pr list --state open` vide. Le
> reflog -10 ne contient que des opérations traçables aux PR #252/#253 mergées aujourd'hui —
> vague terminée, pas de session en vol. Aucun risque de collision pour démarrer une vague. »

Ce contrôle décide de deux choses coûteuses : **démarrer une vague** (donc écrire dans le dépôt
partagé) et **lancer `prune-stale-worktrees.sh --apply`** (donc effacer des répertoires de
travail). Il est aujourd'hui :

| Défaut | Conséquence |
|---|---|
| **Manuel**, rejoué de mémoire à chaque vague | deux vagues ne vérifient pas la même chose |
| **Non tracé** ailleurs que dans la prose d'un agent | rien à relire après coup en cas d'incident |
| **Incomplet** : il ne regarde que le checkout principal et les PR | il ne voit pas les 22 worktrees liés, où vivent justement les sessions parallèles |
| **Aveugle aux collisions de branche** | mesuré ci-dessous : la même branche est actuellement checked out dans **trois** worktrees |

L'incident du 2026-08-29 (mémoire `git-add-cible-sessions-paralleles`) est exactement ce que ce
contrôle existe pour prévenir : un `git add -A` d'une session a emporté 135 lignes écrites par une
session parallèle et non encore commitées. Le garde-fou `recently-active` de SF-REPO-02 protège les
worktrees **un par un** au moment de les détruire ; il ne répond jamais à la question globale
« puis-je démarrer ? ».

### Mesure du dépôt réel au 2026-09-06 (`origin/main` = `83d8590`, lecture seule)

| Constat | Valeur |
|---|---|
| Worktrees enregistrés | **23** (checkout principal + 22 liés) |
| PR ouvertes (`gh pr list --state open`) | **0** |
| Checkout principal | `9bd34ca` — **en retard** de 3 commits sur `origin/main` |
| Branche `verify-main` checked out simultanément dans | **3 worktrees** (`wf_15a24825-882-9`, `wf_15a24825-882-10`, `wf_d6e012f1-9e8-2`) |

La collision de branche n'est visible dans **aucun** des contrôles existants — ni dans le contrôle
manuel du mandat, ni dans `prune-stale-worktrees.sh`, qui raisonne worktree par worktree.

---

## Comportement attendu

### Cas nominal

`scripts/check-parallel-sessions.sh`, sans argument, depuis n'importe quel répertoire du dépôt
(checkout principal **ou** worktree lié) :

1. Récupération depuis `origin` (sauf `--no-fetch`), puis résolution de `origin/main`.
2. Énumération des worktrees enregistrés (format porcelaine).
3. Évaluation des signaux ci-dessous, chacun imprimé avec son motif.
4. Ligne de verdict finale, puis sortie `0` (`CLEAR`) ou `1` (`BUSY`).

### Signaux qui rendent le verdict `BUSY` — « session en vol »

| # | Signal | Motif |
|---|---|---|
| **W1** | Worktree lié (autre que celui d'où l'on tourne) avec **activité < N min** | une session y écrit ; démarrer dessus, ou le détruire, casse son travail |
| **W2** | Checkout principal **sale** (fichiers non commités) | une session y a du travail en cours — le scénario exact du 2026-08-29 |
| **W3** | Checkout principal **en avance** sur `origin/main` (commits non poussés) | une session a commité sans pousser ; repartir de `origin/main` perdrait le fil |
| **W4** | Au moins une **PR ouverte** | une vague est en cours de livraison |
| **W5** | Une même branche **checked out dans ≥ 2 worktrees** | deux sessions partagent une branche : collision certaine |

### Signaux informatifs — n'inversent **jamais** le verdict

| # | Signal | Traitement |
|---|---|---|
| I1 | Worktree `dirty` / `unmerged` / `locked` **sans** activité récente | **RESIDU** — c'est du déchet de vague, pas une session ; renvoi vers `prune-stale-worktrees.sh` |
| I2 | Checkout principal **en retard** sur `origin/main` | **INFO** — il manque une mise à jour locale, ce n'est pas une session en vol |
| I3 | Worktree dont le répertoire a disparu | **INFO** — enregistrement périmé, traité par le prune |

### Cas d'erreur

| Situation | Comportement attendu | Sortie |
|---|---|---|
| Option inconnue | message + aide sur `stderr` | **2** |
| `--age-minutes` non entier ≥ 0 | message explicite | **2** |
| La récupération depuis `origin` échoue | STOP — la comparaison à `origin/main` ne serait pas fiable | **4** |
| `origin/main` (ou `--base`) introuvable | STOP, message explicite | **4** |
| Hors d'un dépôt Git | message explicite | **4** |
| `gh` absent, non authentifié ou hors ligne | signal W4 marqué `INDISPONIBLE`, verdict rendu sur les seuls signaux locaux, mention explicite dans la ligne de verdict | inchangé |
| `gh` indisponible **et** `--require-gh` | verdict `BUSY` — on refuse de conclure sans le signal PR | **1** |
| Statut Git d'un worktree illisible | worktree marqué `ILLISIBLE` et compté comme **BUSY** (prudence) | **1** |
| Répertoire d'un worktree absent | I3, jamais `BUSY` | inchangé |

---

## Critères d'acceptation vérifiables

- [x] Un dépôt sans worktree lié, checkout propre et synchrone, sans PR ouverte → `CLEAR`, sortie **0**.
- [x] Un worktree lié touché à l'instant (fichier **imbriqué**) → `EN VOL`, verdict `BUSY`, sortie **1**.
- [x] Le worktree **depuis lequel le script tourne** n'est jamais compté comme en vol, sauf `--include-self`.
- [x] Un fichier non commité dans le checkout principal → `BUSY` (W2).
- [x] Un commit non poussé dans le checkout principal → `BUSY` (W3).
- [x] Une branche checked out dans deux worktrees → `BUSY` (W5), les deux chemins nommés.
- [x] Un worktree `dirty` **ancien** (aucune activité récente) → `RESIDU`, verdict `CLEAR`.
- [x] Un worktree `unmerged` **ancien** → `RESIDU`, verdict `CLEAR`.
- [x] Un checkout principal en retard sur `origin/main` → `INFO`, verdict `CLEAR`.
- [x] `gh` absent → verdict rendu, mention `INDISPONIBLE` ; avec `--require-gh` → `BUSY`.
- [x] Un `gh` simulé renvoyant une PR ouverte → `BUSY` (W4).
- [x] `--age-minutes 0` désactive W1 (le worktree actif redevient `CLEAR` s'il est propre et mergé).
- [x] Le script **ne modifie rien** : état Git (worktrees + branches + statut) identique avant/après.
- [x] Le script s'exécute **depuis un worktree lié** sans refus (contrairement à `prune-stale-worktrees.sh`).
- [x] Option inconnue → sortie **2** ; base introuvable → sortie **4**.
- [x] Les 33 assertions de `prune-stale-worktrees.test.sh` restent vertes (aucune régression).

---

## Plan de test minimal

Test d'intégration bash (`scripts/check-parallel-sessions.test.sh`), dépôt jetable en `mktemp -d`,
config Git de l'utilisateur neutralisée (`GIT_CONFIG_GLOBAL=/dev/null`,
`GIT_CONFIG_NOSYSTEM=1`), même harnais que `prune-stale-worktrees.test.sh`.

| Cas | Contenu |
|---|---|
| 1 | Dépôt propre, aucun worktree lié → `CLEAR`, sortie 0 |
| 2 | Worktree lié avec fichier imbriqué touché à l'instant → `BUSY`, motif « activite recente » |
| 3 | `--age-minutes 0` sur le même dépôt → le worktree n'est plus en vol |
| 4 | Auto-exclusion : script lancé **depuis** le worktree actif → `CLEAR` ; avec `--include-self` → `BUSY` |
| 5 | Checkout principal sale → `BUSY` (W2) ; propre → le signal disparaît |
| 6 | Checkout principal en avance → `BUSY` (W3) ; en retard → `CLEAR` + `INFO` (I2) |
| 7 | Même branche dans 2 worktrees → `BUSY` (W5), les 2 chemins nommés |
| 8 | Worktree `dirty` **backdaté** → `RESIDU`, verdict `CLEAR` |
| 9 | Worktree `unmerged` **backdaté** → `RESIDU`, verdict `CLEAR` |
| 10 | `gh` simulé (stub `PATH`) renvoyant une PR → `BUSY` (W4) ; renvoyant vide → `CLEAR` |
| 11 | `gh` absent du `PATH` → `INDISPONIBLE` + verdict local ; `--require-gh` → `BUSY` |
| 12 | Non-destructivité : instantané des worktrees, des branches et du statut identique avant/après |
| 13 | Exécution depuis un worktree lié : acceptée (pas de sortie 3) |
| 14 | Sorties d'erreur : option inconnue → 2, `--age-minutes -1` → 2, `--base` inexistante → 4 |

**Résultat** : **40 assertions vertes, 0 échec** sur les 14 cas. `prune-stale-worktrees.test.sh`
reste à **33 assertions vertes** (aucune régression).

**Contrôle d'utilité du test** : chaque cas `BUSY` est accompagné de son **contrôle négatif** (le
même dépôt sans le signal doit rendre `CLEAR`). Un test qui ne peut pas échouer ne vaut rien —
leçon des correctifs SF-REPO-02 (2026-08-26) et SF-WO-01. Le contrôle a été fait explicitement,
par **trois mutations** de l'implémentation, chacune exécutée contre le jeu de tests :

| Mutation | Assertions rouges |
|---|---|
| W5 exige 3 worktrees au lieu de 2 (collision affaiblie) | **3** (cas 7) |
| Auto-exclusion neutralisée | **2** (cas 4) |
| Les résidus comptent comme sessions en vol | **4** (cas 8, 9, et par ricochet 10 et 11) |

Aucune mutation ne passe inaperçue.

**Substitution assumée dans le cas 11** : l'indisponibilité de `gh` est éprouvée par un **stub
sortant en 1** (le chemin « hors ligne / non authentifié ») plutôt qu'en retirant `gh` du `PATH` —
un `PATH` amputé priverait aussi le script de `git`, `find` et `awk`, et ne testerait plus rien.
Les deux chemins convergent vers le même état `INDISPONIBLE`.

**Isolation `user_id`** : sans objet — housekeeping Git pur, aucun accès aux données applicatives,
aucune route, aucune table, aucun `workspace_id`.

---

## Fichiers impactés

| Fichier | Nature |
|---|---|
| `scripts/check-parallel-sessions.sh` | **nouveau** — le contrôle de vol, lecture seule |
| `scripts/check-parallel-sessions.test.sh` | **nouveau** — test d'intégration |
| `scripts/lib/git-worktrees.sh` | **nouveau** — bibliothèque partagée (détection d'activité récente, arbitrage I) |
| `docs/features/SESSIONS-PARALLELES/SF-SP-01-*.md` | cette mini-spec |
| `docs/PRODUCT_SPEC.md` | ligne d'historique (post-merge) |

Aucun fichier `backend/`, `frontend/`, `infra/` n'est touché. Aucune migration Liquibase.

---

## Préoccupations transversales

| Préoccupation | Impacté ? | Analyse |
|---|---|---|
| Auth / Principal | **Non** | aucun code applicatif |
| Contexte tenant / `user_id` | **Non** | aucun accès aux données |
| Plans / limites / quotas | **Non** | — |
| Navigation / routing | **Non** | aucun écran |
| **Outillage développeur** | **Oui** | composants impactés : `scripts/check-parallel-sessions.sh` (nouveau) et `scripts/prune-stale-worktrees.sh` (**inchangé** par cette SF). Aucun job CI ne référence `scripts/` — vérifié par `grep` sur `.github/workflows/` |

---

## Arbitrages (gates réversibles, décidés par défaut)

| # | Décision | Motif | Réversible |
|---|---|---|---|
| A | **Lecture seule stricte**, donc **autorisé depuis un worktree lié** — à l'inverse de `prune-stale-worktrees.sh` (sortie 3) | le refus de SF-REPO-02 protège d'une opération **destructive** ; ici il n'y en a aucune. Interdire l'outil aux agents isolés le rendrait inutilisable là où il sert le plus : dans l'agent qui se demande s'il peut démarrer | oui |
| B | **Seule l'activité récente vaut « session en vol »** ; `dirty` / `unmerged` / `locked` sans activité = `RESIDU` | sinon le verdict serait `BUSY` en permanence : 22 worktrees liés survivent aux vagues éteintes, dont plusieurs sales ou non mergés. Un outil qui répond toujours « occupé » ne répond rien | oui |
| C | Checkout principal : **`dirty` et « en avance » = BUSY**, **« en retard » = INFO** | sale ou en avance = travail d'une session non encore poussé (incident du 2026-08-29). En retard = il manque une mise à jour locale : gênant, pas une collision | oui |
| D | **PR ouverte = BUSY**, `gh` indisponible = signal `INDISPONIBLE` non bloquant, `--require-gh` pour le rendre bloquant | le contrôle manuel du mandat s'appuie déjà sur `gh pr list`. Mais faire dépendre d'un binaire externe et du réseau un verdict qui a des réponses locales valables bloquerait le hors-ligne ; l'opérateur qui exige le signal a un drapeau | oui |
| E | **Auto-exclusion par défaut** (`--include-self` pour l'inverser) | le script tourne dans un worktree, donc y crée de l'activité : sans exclusion il se déclarerait lui-même en vol, à tous les coups | oui |
| F | **Collision de branche (W5) = BUSY** | mesurée sur le dépôt réel (3 worktrees sur `verify-main`). Deux sessions sur une branche partagée, c'est le mode d'échec que cette feature nomme | oui |
| G | Le signal « **reflog** » du contrôle manuel n'est **pas** repris | il n'est ni fiable ni interprétable par une machine (« traçable aux PR mergées » est un jugement humain) ; les signaux W1–W5 couvrent la même question par des faits vérifiables | oui |
| H | **Pas de sortie JSON** en SF-SP-01 | aucun consommateur machine aujourd'hui ; les codes de sortie 0/1 suffisent à scripter. Ajoutable sans rupture | oui |
| I | **Bibliothèque partagée `scripts/lib/git-worktrees.sh`** plutôt qu'une copie de `is_recently_active` | cette fonction a **déjà été corrigée une fois** (SF-REPO-02, 2026-08-26 : la seule mtime de la racine ne bouge pas quand un agent édite un fichier imbriqué). La dupliquer garantit que le prochain correctif n'en corrigera qu'une des deux. `prune-stale-worktrees.sh` n'est **pas** migré ici — sa migration appartient à SF-SP-02, qui le touche déjà ; le migrer maintenant élargirait le rayon d'impact sans nécessité | oui |

---

## Dépendances

- **Subfeature bloquante** : aucune. SF-REPO-02 et SF-WO-01 sont `done` et **ne sont pas modifiées** par cette SF.
- **Questions ouvertes** (`docs/OPEN_QUESTIONS.md`) impactées : **aucune**.

---

## Résultat sur le dépôt réel — le contrôle manuel était faux

Exécution du script livré depuis ce worktree (`--no-fetch`, lecture seule), au moment même où le
mandat affirmait « **vague terminée, pas de session en vol. Aucun risque de collision pour
démarrer une vague** » :

```
== Verdict ==
  sessions en vol  : 3
  collisions       : 1
  PR ouvertes      : 0
  residus (inactifs, non bloquants) : 6
VERDICT: BUSY — au moins une session est en vol ou en collision.
```

| Signal | Détail |
|---|---|
| **W1 — en vol** | `wf_20297436-65a-2`, `wf_20297436-65a-3`, `wf_20297436-65a-4` : activité de moins de 60 min |
| **W5 — collision** | `verify-main` checked out dans **3 worktrees** : `wf_15a24825-882-9`, `wf_15a24825-882-10`, `wf_d6e012f1-9e8-2` |
| W4 — PR | 0 ouverte — **le seul point où le contrôle manuel disait vrai** |
| I2 — info | le checkout principal est **en retard de 3 commits** sur `origin/main` |
| I1 — résidus | 6 worktrees inactifs (`dirty`, `unmerged`, `locked`) → matière pour `prune-stale-worktrees.sh` |

Le contrôle manuel concluait juste sur les deux signaux qu'il regardait (checkout principal, PR)
et **manquait les 22 worktrees liés**, c'est-à-dire l'endroit exact où vivent les sessions
parallèles. C'est la justification empirique de cette subfeature : trois sessions écrivaient
pendant que le mandat déclarait la voie libre.

**Note de méthode** : cette exécution est possible parce que le script est en lecture seule ; le
`git status` d'un autre worktree passe, là où toute commande destructive serait refusée à un agent
isolé. La contrainte notée en « hors périmètre » ci-dessous s'est donc révélée plus étroite que
prévu — seule l'action de purge reste hors de portée.

---

## Hors périmètre

- **Toute action corrective** : le script ne retire, ne supprime, ne pousse et ne commite rien.
  La purge reste le métier de `scripts/prune-stale-worktrees.sh`.
- **Le câblage du contrôle dans `prune-stale-worktrees.sh`** (refuser `--apply` quand une session
  est en vol) — c'est **SF-SP-02**, qui suit.
- **Toute action sur les résidus détectés** : les 6 worktrees résidus et la collision `verify-main`
  mesurés ci-dessus sont **signalés, pas corrigés**. Leur purge demande `--apply` depuis le
  checkout principal, hors de portée d'un agent isolé (sortie 3 par conception, SF-REPO-02).
- **Sortie JSON / intégration CI** : aucun consommateur, arbitrage (H).
- **Toute modification du produit** : aucune. V1 = passerelle, périmètre intact.
