# Mini-spec — `BRANCHES-ORPHELINES` / SF-BO-01 — Le ménage des branches, exécutable pendant une vague

> Feature : `REPO` — housekeeping du dépôt Git.
> Aucun code applicatif, aucune migration, aucun endpoint, aucun écran, aucun déploiement.

---

## Identifiant

`BRANCHES-ORPHELINES / SF-BO-01`

## Feature parente

`REPO` — Hygiène du dépôt (housekeeping Git), déjà référencée dans `docs/PRODUCT_SPEC.md`
(historique des 2026-08-25, 2026-08-26 et 2026-09-06).

Lignée : `worktree_doc_cleanup` (SF-CLEANUP-01) → `stale-worktrees-housekeeping` (SF-HK-01) →
`REPO / SF-REPO-01` (purge de branches résiduelles) → `REPO / SF-REPO-02`
(`scripts/prune-stale-worktrees.sh`) → `worktrees-orphelins / SF-WO-01` (balayage global des
branches, PR #225) → `SESSIONS-PARALLELES / SF-SP-01` (contrôle de vol, PR #256) →
`SESSIONS-PARALLELES / SF-SP-02` (garde-fou 5, PR #257) → **SF-BO-01**.

**Aucune feature nouvelle n'est créée.**

## Statut

`done`

## Date de création

2026-09-06

## Branche Git

`chore/SF-BO-01-purge-branches-pendant-vague`

---

## Objectif (une phrase)

Rendre la purge des **branches locales orphelines** exécutable **pendant qu'une vague tourne**, en
séparant ce qui détruit un répertoire de travail (le retrait de worktrees) de ce qui ne touche
qu'une référence Git (la suppression d'une branche que personne n'a checked out).

---

## Contexte — un outil complet que personne ne peut lancer

La chaîne d'outillage est livrée : SF-WO-01 balaie **toutes** les branches locales, SF-SP-02 pose
le garde-fou 5 qui refuse de détruire pendant qu'une session parallèle travaille. Les deux
mini-specs se terminent pourtant sur le **même reliquat**, mot pour mot : « exécution effective
`--apply` : … refusée par le nouveau garde-fou tant que des sessions volent ».

Ce reliquat n'est pas un oubli, c'est un **effet de conception** : le garde-fou 5 est évalué une
fois pour tout le run, avant la première action destructive, et il refuse **le run entier**.
Or dans ce dépôt les vagues s'enchaînent en parallèle — au 2026-09-06, **22 worktrees liés** et
au moins une session active en permanence. Le verdict est donc `BUSY` pratiquement à toute heure,
et la purge n'a **jamais** tourné.

Mesure du jour (simulation en lecture seule, `origin/main` = `9720bdc`) :

| Catégorie | Branches |
|---|---|
| Total local | **197** (172 au 2026-09-06 matin, soit **+25 en une journée**) |
| Protégées en dur (`main`) | 1 |
| Détenues par un worktree | 20 |
| **KEEP** — travail non mergé (≥ 1 commit `+`) | **6** |
| **DELETE** — orphelines | **170** |

Les six conservées sont les mêmes qu'au matin (`chore/canvas-flaky-fix-and-library-rename` +2,
`docs/F-31-cloture-sf-31-05`, `feat/SF-06-01-ingestion-core`, `feat/SF-12-01-landing`,
`feat/SF-31-05-creation-pull-request`, `feat/SF-31-05-pull-request-mcp-back`). Le reste est du
bruit qui croît d'environ vingt-cinq branches par vague.

### Ce que le garde-fou 5 protège réellement

Il protège **un répertoire de travail** : `git worktree remove` efface les fichiers d'un agent
vivant. C'est la seule opération réellement dangereuse de l'outil.

Supprimer une branche locale qui (a) n'est checked out **dans aucun worktree** et (b) ne porte
**aucun commit `+`** face à `origin/main` ne détruit ni fichier, ni travail, ni historique : tout
son contenu est déjà dans la base, et son SHA est imprimé pour restauration. Faire dépendre cette
opération-là de « une vague tourne-t-elle ? » revient à interdire de ranger l'atelier parce que
quelqu'un y travaille — au prix de ne jamais le ranger.

---

## Comportement attendu

### Cas nominal — nouvelle option `--branches-only`

`scripts/prune-stale-worktrees.sh --branches-only [--apply]` :

1. **Aucun worktree n'est retiré, aucun n'est même candidat.** Les worktrees sont énumérés en
   lecture seule, et la branche de **chaque** enregistrement — y compris un enregistrement périmé
   dont le répertoire a disparu — est marquée **détenue**, donc intouchable.
2. **`git worktree prune` n'est pas exécuté** : il réécrit les enregistrements de worktrees, ce
   que ce mode s'interdit.
3. **Le garde-fou 5 n'est pas évalué** : rien de destructif ne peut se produire, une vague en vol
   n'est pas un motif de refus. C'est l'objet même de l'option.
4. Le balayage des branches (SF-WO-01) se déroule ensuite **à l'identique** — protection en dur,
   détention, `git cherry origin/main` — avec **un filtre de plus** décrit ci-dessous.
5. Le dry-run reste le défaut ; `--apply` supprime effectivement les branches planifiées.

### Filtre supplémentaire — branche touchée récemment

Entre le moment où le script photographie les worktrees et celui où il supprime la dernière
branche, un agent vivant peut créer une branche : elle ne figurerait pas dans la photo, serait
sans commit `+` (elle vient de partir de `main`) et serait supprimée **sous ses pieds**. La
fenêtre vaut la durée du balayage — 197 `git cherry`, quelques dizaines de secondes.

En mode `--branches-only`, une branche dont le **reflog** a été écrit il y a moins de
`--age-minutes` (défaut 60) est donc **conservée**, motif `recently-touched`. Elle sera balayée au
run suivant. `--age-minutes 0` désactive ce filtre comme il désactive déjà le garde-fou d'âge des
worktrees — les deux seuils restent le même nombre, comme l'exige l'arbitrage (E) de SF-SP-02.

Le signal est la **mtime du reflog** (`.git/logs/refs/heads/<branche>`), pas la date du commit de
tête : une branche créée à l'instant depuis `main` porte un commit vieux de plusieurs heures, la
date de commit ne dirait rien. Reflog absent → branche réputée **non** récente : sans trace
d'écriture, il n'y a pas d'écriture récente à protéger.

### Ce qui ne change pas

Sans `--branches-only`, le comportement est **strictement celui de SF-SP-02** : worktrees
candidats, garde-fou 5, prune, balayage. Les 45 assertions existantes sont le filet.

---

## Cas d'erreur / cas limites

| Cas | Comportement attendu | Sortie |
|---|---|---|
| `--branches-only --no-sweep-branches` | **refus d'usage** : sans balayage global, la liste de candidates est celle des branches libérées par un retrait — vide par construction dans ce mode. Le run ne ferait rien en silence | **2** |
| Session parallèle en vol, `--branches-only --apply` | purge des branches exécutée, **aucun worktree touché** | 0 |
| Branche checked out dans un worktree, même périmé | **KEEP** — détenue | 0 |
| Branche au reflog écrit il y a moins de `--age-minutes` | **KEEP** — `recently-touched` | 0 |
| Branche sans reflog, 0 commit `+` | **DELETE** | 0 |
| Branche portant ≥ 1 commit `+` | **KEEP** — inchangé | 0 |
| `main` / `master` / `HEAD` | **KEEP** — protection en dur, jamais évaluée | 0 |
| Lancement depuis un worktree lié | refus, sortie **3** (inchangé) | 3 |
| Échec du fetch `origin` | STOP, sortie **4** (inchangé) | 4 |
| Refus de `git branch -D` par Git | `ECHEC` tracé, branche conservée, balayage poursuivi | 0 |

---

## Critères d'acceptation vérifiables

- [x] Avec une session parallèle **en vol**, `--branches-only --apply` sort en **0** et supprime
      la branche orpheline détachée.
- [x] Dans ce même run, **aucun worktree n'est retiré** — y compris un worktree résiduel que le
      mode normal aurait supprimé — et le worktree actif est intact.
- [x] Le même run **sans** `--branches-only` sort en **5** (contrôle négatif : le garde-fou 5 est
      bien toujours armé là où il doit l'être).
- [x] `--branches-only` **ne prune pas** les enregistrements de worktrees périmés.
- [x] Une branche dont le reflog vient d'être écrit est **conservée** (`recently-touched`) ; avec
      `--age-minutes 0` la même branche est supprimée.
- [x] `--branches-only --no-sweep-branches` sort en **2** sans rien modifier.
- [x] Une branche portant du travail non mergé est conservée en mode `--branches-only`.
- [x] Les **45 assertions** existantes restent vertes (aucune régression du mode normal) — le
      jeu passe à **59**.

---

## Plan de test minimal

Cas ajoutés à `scripts/prune-stale-worktrees.test.sh` (15 → 18) :

| Cas | Contenu |
|---|---|
| 16 | **Purge de branches pendant une vague** : worktree actif + worktree résiduel supprimable + branche orpheline détachée + branche « en avance ». `--branches-only --apply` → sortie **0**, orpheline supprimée, branche en avance conservée, **worktree résiduel toujours là**, worktree actif intact, enregistrement périmé **non** prunné. Contrôle négatif : le même run sans l'option sort en **5** |
| 17 | **Garde-fou `recently-touched`** : branche orpheline dont le reflog vient d'être écrit → **KEEP** en `--branches-only` ; avec `--age-minutes 0` → **DELETE** |
| 18 | **Usage** : `--branches-only --no-sweep-branches` → sortie **2**, état Git inchangé |

**Résultat** : **45 → 59 assertions vertes, 0 échec**.

**Contrôle d'utilité du test** — trois mutations exécutées contre le jeu de tests :

| Mutation | Assertions rouges |
|---|---|
| Garde-fou 5 évalué même en `--branches-only` | **2** (cas 16 : sortie 5, orpheline non supprimée) |
| Filtre `recently-touched` désactivé | **1** (cas 17) |
| `--branches-only` laissant les worktrees redevenir candidats | **2** (cas 16 : worktree retiré, branche arrachée) |

Un test incapable d'échouer ne vaut rien (leçon SF-REPO-02, 2026-08-26).

**Isolation `user_id`** : sans objet — housekeeping Git pur, aucun accès aux données
applicatives, aucune route, aucune table.

---

## Fichiers impactés

| Fichier | Nature |
|---|---|
| `scripts/prune-stale-worktrees.sh` | option `--branches-only`, filtre `recently-touched`, refus d'usage combiné |
| `scripts/prune-stale-worktrees.test.sh` | cas 16 à 18 |
| `docs/features/BRANCHES-ORPHELINES/SF-BO-01-*.md` | cette mini-spec |
| `docs/PRODUCT_SPEC.md` | ligne d'historique |

Aucun fichier `backend/`, `frontend/`, `infra/`. Aucune migration Liquibase. Aucun secret.

---

## Contraintes de validation

| Champ / réglage | Contrainte | Statut |
|---|---|---|
| `--age-minutes` | entier ≥ 0, déjà validé par le script (sortie 2 sinon) — **réutilisé tel quel** pour le filtre de branche | tranché |
| Signal d'ancienneté d'une branche | mtime de `.git/logs/refs/heads/<branche>` ; absent ⇒ non récente | tranché (arbitrage B) |
| Branches protégées | `main`, `master`, `HEAD` — inchangé | tranché |
| Combinaison `--branches-only --no-sweep-branches` | interdite, sortie 2 | tranché (arbitrage C) |

Aucune contrainte structurante ne reste indéterminée ; aucune entrée de `docs/OPEN_QUESTIONS.md`
n'est impactée.

---

## Préoccupations transversales

| Préoccupation | Impacté ? | Analyse |
|---|---|---|
| Auth / Principal | **Non** | aucun code applicatif |
| Contexte tenant / `user_id` | **Non** | aucun accès aux données |
| Plans / limites / quotas | **Non** | — |
| Navigation / routing | **Non** | aucun écran |
| **Outillage développeur** | **Oui** | composants impactés : `scripts/prune-stale-worktrees.sh` (**modifié**), `scripts/prune-stale-worktrees.test.sh` (**étendu**), `scripts/check-parallel-sessions.sh` (**appelé, non modifié** — et **non appelé** en `--branches-only`), `scripts/lib/git-worktrees.sh` (**non modifiée**, toujours consommée par le mode normal). Aucun job CI ne référence `scripts/` — vérifié par `grep` sur `.github/workflows/` |

---

## Arbitrages (gates réversibles, décidés par défaut)

| # | Décision | Motif | Réversible |
|---|---|---|---|
| A | **Un mode explicite** (`--branches-only`) plutôt que d'assouplir le garde-fou 5 | le garde-fou 5 doit rester un refus **sans nuance** devant `git worktree remove` ; le nuancer, c'est le rendre discutable. Séparer les deux opérations laisse chacune avec la règle qui lui convient | oui — un flag |
| B | **mtime du reflog** comme signal d'ancienneté d'une branche | la date du commit de tête ne dit rien (une branche neuve part d'un commit ancien) ; le reflog est écrit à chaque mise à jour de la référence, et `core.logAllRefUpdates` est actif par défaut hors dépôt nu | oui |
| C | `--branches-only --no-sweep-branches` = **erreur d'usage** (sortie 2) plutôt que run vide | la combinaison ne peut rien produire : sans retrait de worktree, la liste dérivée est vide. Un outil qui ne fait rien en rendant 0 ment sur son résultat | oui |
| D | Le filtre `recently-touched` **ne s'applique qu'en `--branches-only`** | en mode normal le garde-fou 5 refuse déjà tout run pendant une vague ; ajouter le filtre partout changerait le comportement de 45 assertions pour une protection redondante | oui |
| E | **`git worktree prune` non exécuté** en `--branches-only` | il réécrit des enregistrements de worktrees ; l'option promet de ne toucher qu'aux branches, et la promesse doit être littérale. Conséquence assumée : la branche d'un enregistrement périmé est conservée (elle est détenue) — elle tombera au premier run complet | oui |
| F | **Branches distantes toujours conservées** | inchangé depuis SF-REPO-01 (A) : c'est ce qui rend les suppressions locales sans conséquence | oui |

---

## Dépendances

- **Subfeatures bloquantes** : `worktrees-orphelins / SF-WO-01` (PR #225) et
  `SESSIONS-PARALLELES / SF-SP-02` (PR #257) — **done**, mergées.
- **Questions ouvertes** (`docs/OPEN_QUESTIONS.md`) impactées : **aucune**.

---

## Hors périmètre

- **Exécution effective `--apply`** sur ce dépôt : un agent isolé en worktree lié en est refusé
  par conception (sortie 3). Elle reste à l'opérateur, depuis le checkout principal — mais elle
  n'est plus **conditionnée à un dépôt au repos**, ce qui était l'objet de cette subfeature.
- **Suppression de branches distantes** : conservées (arbitrage F).
- **Retrait de worktrees pendant une vague** : jamais. Le garde-fou 5 reste intact.
- **Câblage dans la CI** : aucun job ne référence `scripts/`, et ce n'est pas souhaitable — ces
  outils parlent de l'état d'une machine de développement, pas d'un runner.
- **Toute modification du produit** : aucune. V1 = passerelle, périmètre intact.
