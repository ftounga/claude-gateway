# Mini-spec — `SESSIONS-PARALLELES` / SF-SP-02 — La purge refuse de détruire pendant qu'une session vole

> Feature : `REPO` — housekeeping du dépôt Git.
> Aucun code applicatif, aucune migration, aucun endpoint, aucun écran, aucun déploiement.

---

## Identifiant

`SESSIONS-PARALLELES / SF-SP-02`

## Feature parente

`REPO` — Hygiène du dépôt (housekeeping Git), déjà référencée dans `docs/PRODUCT_SPEC.md`.

Lignée : … → `REPO / SF-REPO-02` (`prune-stale-worktrees.sh`) → `worktrees-orphelins / SF-WO-01`
(balayage global) → `SESSIONS-PARALLELES / SF-SP-01` (contrôle de vol, PR #256) → **SF-SP-02**.

**Aucune feature nouvelle n'est créée.**

## Statut

`done`

## Date de création

2026-09-06

## Branche Git

`chore/SF-SP-02-purge-refusee-si-session-en-vol`

---

## Objectif (une phrase)

Empêcher `prune-stale-worktrees.sh --apply` — la seule opération **destructive** du dépôt — de
s'exécuter pendant qu'une session parallèle travaille, en le faisant consulter le contrôle de vol
livré par SF-SP-01 avant d'effacer quoi que ce soit.

---

## Contexte — le trou que SF-SP-01 laisse ouvert

SF-SP-01 livre un contrôle qui **répond juste** : au moment où le mandat déclarait la voie libre,
il a détecté 3 sessions en vol et 1 collision de branche. Mais il ne **fait** rien : rien n'oblige
l'opérateur à le lancer avant `--apply`.

Or les quatre garde-fous de `prune-stale-worktrees.sh` sont **locaux à chaque candidat** :
`locked`, `recently-active`, `dirty`, `unmerged`. Ils protègent le worktree qu'ils examinent, un
par un. Aucun ne répond à la question globale « une vague est-elle en cours ? ». Deux trous
concrets subsistent :

| Trou | Conséquence |
|---|---|
| Un agent vivant **entre deux SF** — arbre propre, HEAD mergé, dernière écriture il y a 61 min | les 4 garde-fous passent au vert : `git worktree remove` **efface son répertoire de travail sous ses pieds** |
| Une branche **partagée par deux worktrees** | invisible du prune, qui raisonne worktree par worktree ; le retrait de l'un laisse l'autre dans un état incohérent |

Le garde-fou d'âge a déjà dû être corrigé une fois (SF-REPO-02, 2026-08-26) précisément parce
qu'il laissait passer une session vivante. Le durcir encore, worktree par worktree, ne ferait que
déplacer la borne : la bonne réponse est de **poser la question au niveau du dépôt** avant de
détruire.

---

## Comportement attendu

### Cas nominal

Nouveau **garde-fou 5**, évalué **uniquement en mode `--apply`**, avant toute action destructive
(donc avant le retrait du premier worktree) :

1. `prune-stale-worktrees.sh` invoque `scripts/check-parallel-sessions.sh` avec
   `--quiet --no-fetch --no-gh --age-minutes <le sien>`.
2. Verdict `CLEAR` (sortie 0) → la purge se déroule comme avant, **strictement inchangée**.
3. Verdict `BUSY` (sortie 1) → **STOP avant toute destruction**, sortie **5**, avec la ligne de
   verdict du contrôle et le rappel de `--force-busy`.
4. `--force-busy` passe outre, en traçant explicitement que le garde-fou a été forcé.

Le **dry-run n'est pas affecté** : il ne détruit rien, il doit rester consultable à tout moment —
c'est même le seul moyen de voir ce que la purge ferait pendant qu'une vague tourne.

### Migration de `is_recently_active` vers la bibliothèque partagée

`prune-stale-worktrees.sh` abandonne sa copie locale de `is_recently_active` au profit de
`gw_is_recently_active` (`scripts/lib/git-worktrees.sh`, livrée par SF-SP-01). **Comportement
identique** — c'est la même fonction, déplacée : les 33 assertions existantes sont le filet.

### Cas d'erreur

| Situation | Comportement attendu | Sortie |
|---|---|---|
| Verdict `BUSY` et pas de `--force-busy` | STOP **avant** toute destruction, motif imprimé | **5** |
| Verdict `BUSY` et `--force-busy` | poursuite, avec une ligne `FORCE` tracée | inchangé |
| `check-parallel-sessions.sh` **absent ou non exécutable** | avertissement sur `stderr`, poursuite : les 4 garde-fous locaux et le dry-run par défaut subsistent | inchangé |
| Le contrôle sort en 2 ou 4 (usage, état non évaluable) | traité comme **indisponible** : avertissement, poursuite | inchangé |
| Mode dry-run (défaut) | garde-fou **non évalué**, aucune ligne parasite | 0 |
| `--age-minutes 0` | le contrôle est invoqué avec `0` : le signal d'activité est désactivé des deux côtés, cohérence garantie | — |

---

## Critères d'acceptation vérifiables

- [x] Avec un worktree lié **actif**, `--apply` s'arrête en sortie **5** et **rien n'est détruit**.
- [x] Le message d'arrêt nomme le motif (verdict du contrôle) et mentionne `--force-busy`.
- [x] `--force-busy` fait aboutir la même purge, et trace la ligne `FORCE`.
- [x] Avec une **collision de branche** (même branche dans 2 worktrees), `--apply` s'arrête en **5**.
- [x] Le **dry-run** n'est jamais bloqué, même avec une session en vol, et n'imprime aucune ligne de garde-fou 5.
- [x] Le garde-fou s'évalue **avant** le premier `git worktree remove` (rien n'est détruit avant le refus).
- [x] Contrôle absent → avertissement, purge exécutée quand même (aucune régression de disponibilité).
- [x] Les **33 assertions** de `prune-stale-worktrees.test.sh` restent vertes après la migration vers la bibliothèque partagée.
- [x] Les **40 assertions** de `check-parallel-sessions.test.sh` restent vertes.
- [x] Consulter le contrôle **ne neutralise pas** la purge : quand aucune session ne vole, le worktree résiduel est bien retiré (contrôle négatif du cas 13).

---

## Plan de test minimal

Cas ajoutés à `scripts/prune-stale-worktrees.test.sh` (13 → 15) :

| Cas | Contenu |
|---|---|
| 13 | **Session en vol** : worktree actif (fichier imbriqué touché, racine backdatée) + worktree résiduel supprimable → `--apply` sort en **5**, le résiduel est **toujours là** ; contrôle négatif : `--force-busy` le retire bien |
| 14 | **Collision de branche** : même branche dans 2 worktrees → `--apply` sort en **5** ; le dry-run, lui, s'exécute normalement en sortie 0 |
| 15 | **Contrôle indisponible** : `check-parallel-sessions.sh` rendu introuvable → avertissement et purge exécutée (sortie 0) |

**Résultat** : **33 → 45 assertions vertes, 0 échec** sur `prune-stale-worktrees.test.sh` ;
`check-parallel-sessions.test.sh` reste à **40 assertions vertes**.

**Contrôle d'utilité** : chaque refus a son contrôle négatif (`--force-busy`, dry-run). La
migration vers la bibliothèque est couverte par les 33 assertions préexistantes — dont le cas 7,
qui est précisément le test de non-régression du garde-fou d'activité. Deux **mutations** ont été
exécutées contre le jeu de tests :

| Mutation | Assertions rouges |
|---|---|
| Garde-fou 5 désactivé (jamais évalué) | **3** (cas 13 et 14) |
| Retour à un `git status` qui réécrit l'index (le défaut ci-dessous) | **2** (contrôles négatifs du cas 13) |

**Isolation `user_id`** : sans objet — housekeeping Git pur.

---

## Défaut trouvé en cours de développement — le contrôle neutralisait la purge

Le cas 13 a échoué à sa première exécution, sur ses **contrôles négatifs** : dépôt au repos,
verdict `CLEAR`, purge lancée… et **aucun worktree retiré**.

Cause : `git status` **rafraîchit et réécrit l'index** du worktree qu'il inspecte. Le contrôle de
vol inspecte *tous* les worktrees ; il touchait donc la mtime de `.git/worktrees/*/index` de
chacun, juste avant que le garde-fou d'âge de la purge ne lise ces mêmes mtimes pour décider qui
est « actif ». Résultat : **tous** les worktrees paraissaient actifs, tous étaient écartés, et la
purge ne retirait plus rien — sans le moindre message d'erreur.

Un garde-fou de sécurité qui **désarme silencieusement** l'outil qu'il protège est pire que pas de
garde-fou du tout : le dry-run aurait continué d'annoncer des retraits que `--apply` n'aurait
jamais faits.

Corrigé dans `check-parallel-sessions.sh` : les deux `git status` d'inspection passent par
`git --no-optional-locks`, qui rafraîchit l'index **en mémoire seulement**. La sortie est
identique, l'index n'est plus écrit. C'est aussi ce qui rend enfin exacte la promesse
« strictement en lecture seule » de SF-SP-01 — le test de non-destructivité de SF-SP-01 comparait
worktrees, branches et statut, pas les mtimes d'index, et ne pouvait donc pas voir cet effet de
bord.

**Ce que l'incident révèle** : deux outils qui lisent le même signal physique (des mtimes) se
perturbent l'un l'autre dès qu'ils s'appellent. Le test qui l'a attrapé n'est pas le test du
refus — c'est son **contrôle négatif**, celui qui vérifie que la purge marche encore quand elle
est autorisée.

---

## Fichiers impactés

| Fichier | Nature |
|---|---|
| `scripts/prune-stale-worktrees.sh` | garde-fou 5 + option `--force-busy` + sortie 5 ; `is_recently_active` remplacée par la bibliothèque partagée |
| `scripts/prune-stale-worktrees.test.sh` | cas 13 à 15 ; `.gitignore` ajouté au dépôt jetable (comme le dépôt réel), sans quoi le checkout principal du bac à sable est perpétuellement sale et le garde-fou 5 refuserait sur du bruit de test |
| `scripts/check-parallel-sessions.sh` | **correctif** `--no-optional-locks` (voir « Défaut trouvé en cours de développement ») |
| `docs/features/SESSIONS-PARALLELES/SF-SP-02-*.md` | cette mini-spec |
| `docs/PRODUCT_SPEC.md` | ligne d'historique (post-merge) |

Aucun fichier `backend/`, `frontend/`, `infra/`. Aucune migration Liquibase.

---

## Préoccupations transversales

| Préoccupation | Impacté ? | Analyse |
|---|---|---|
| Auth / Principal | **Non** | aucun code applicatif |
| Contexte tenant / `user_id` | **Non** | aucun accès aux données |
| Plans / limites / quotas | **Non** | — |
| Navigation / routing | **Non** | aucun écran |
| **Outillage développeur** | **Oui** | composants impactés : `scripts/prune-stale-worktrees.sh` (garde-fou 5, `--force-busy`, migration vers la bibliothèque), `scripts/check-parallel-sessions.sh` (**appelé**, et **corrigé** : `--no-optional-locks`), `scripts/lib/git-worktrees.sh` (**consommée, non modifiée**). Aucun job CI ne référence `scripts/` — vérifié par `grep` sur `.github/workflows/` |

---

## Arbitrages (gates réversibles, décidés par défaut)

| # | Décision | Motif | Réversible |
|---|---|---|---|
| A | Le garde-fou 5 **ne s'évalue qu'en `--apply`** | le dry-run ne détruit rien ; le bloquer priverait l'opérateur du seul moyen de voir l'état pendant qu'une vague tourne | oui |
| B | Le prune appelle le contrôle avec **`--no-gh`** : une PR ouverte **ne bloque pas** la purge | une PR peut rester ouverte des jours ; en faire un blocage rendrait le housekeeping impossible. Et une branche à PR ouverte porte des commits non mergés, donc `git cherry` la protège déjà. Ce sont W1/W2/W3/W5 — les signaux d'écriture concurrente — qui doivent bloquer | oui |
| C | **`--force-busy`** plutôt qu'aucune échappatoire | l'opérateur du checkout principal peut savoir ce que la machine ignore (une session qu'il vient de tuer). Le refus par défaut suffit à rendre l'accident improbable ; l'interdiction absolue ferait contourner l'outil | oui |
| D | Contrôle **absent ⇒ avertissement, pas blocage** | c'est une défense en profondeur ajoutée par-dessus 4 garde-fous et un dry-run par défaut ; rendre le prune inutilisable quand un fichier manque coûterait plus qu'il ne protège | oui |
| E | Le contrôle reçoit **le même `--age-minutes`** que le prune | deux seuils différents produiraient l'incohérence exacte que cette SF corrige : un worktree écarté par un outil et détruit par l'autre | oui |
| F | **Sortie 5** (et non 1) | 1 est déjà l'échec générique de bash ; 3 et 4 sont pris (worktree lié, base non évaluable). 5 est lisible dans un script appelant | oui |
| G | **Migration de `is_recently_active` incluse ici** | c'est la SF qui touche déjà `prune-stale-worktrees.sh` ; la faire ailleurs rouvrirait le fichier pour rien. Suite directe de l'arbitrage (I) de SF-SP-01 | oui |

---

## Dépendances

- **Subfeature bloquante** : `SESSIONS-PARALLELES / SF-SP-01` — **done**, mergée (PR #256).
- **Questions ouvertes** (`docs/OPEN_QUESTIONS.md`) impactées : **aucune**.

---

## Hors périmètre

- **Élargir les signaux** du contrôle de vol : SF-SP-02 le consomme tel quel.
- **Exécution effective `--apply`** sur ce dépôt : refusée à un agent isolé (sortie 3), et de toute
  façon refusée par le nouveau garde-fou tant que des sessions volent. Reste à l'opérateur, depuis
  le checkout principal.
- **Câblage dans la CI** : aucun job ne référence `scripts/`, et ce n'est pas souhaitable — le
  contrôle parle de l'état d'une machine de développement, pas d'un runner.
- **Toute modification du produit** : aucune. V1 = passerelle, périmètre intact.
