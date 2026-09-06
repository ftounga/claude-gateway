# Mini-spec — `worktrees-orphelins` / SF-WO-01 — Balayage global des branches locales orphelines

> Feature : `REPO` — housekeeping du dépôt Git.
> Aucun code applicatif, aucune migration, aucun endpoint, aucun écran, aucun déploiement.

---

## Identifiant

`worktrees-orphelins / SF-WO-01`

## Feature parente

`REPO` — Hygiène du dépôt (housekeeping Git), déjà référencée dans `docs/PRODUCT_SPEC.md`
(historique des 2026-08-25 et 2026-08-26).

Lignée : `worktree_doc_cleanup` (SF-CLEANUP-01, 2026-07-01) → `stale-worktrees-housekeeping`
(SF-HK-01, 2026-07-10) → `REPO / SF-REPO-01` (purge `feat/SF-28-*`, 2026-08-25) →
`REPO / SF-REPO-02` (`scripts/prune-stale-worktrees.sh`, 2026-08-26) → **SF-WO-01**.

**Aucune feature nouvelle n'est créée.** Cette subfeature prolonge l'outil livré par SF-REPO-02.

## Statut

`done`

## Date

2026-09-06

## Branche Git

`chore/SF-WO-01-branches-orphelines`

---

## Objectif (une phrase)

Faire de `scripts/prune-stale-worktrees.sh` un nettoyage **complet** avant chaque vague, en
balayant **toutes** les branches locales — et non plus seulement celles libérées par un worktree
retiré — pour supprimer celles dont `git cherry origin/main` ne produit **aucun commit `+`**.

---

## Contexte — ce que l'outil existant ne couvre pas

SF-REPO-02 a livré `scripts/prune-stale-worktrees.sh` : retrait des worktrees résiduels sous
`.claude/worktrees/` derrière quatre garde-fous (`locked`, `recently-active`, `dirty`,
`unmerged`), puis `git worktree prune` final. Sa purge de branches est **dérivée** : elle ne
considère que les branches qui étaient checked out dans un worktree **que ce même run vient de
retirer** (tableau `branches_to_check`).

Conséquence mesurée sur le dépôt au 2026-09-06 (`origin/main` = `ece5f50`) :

| Constat | Valeur |
|---|---|
| Branches locales | **171** |
| Worktrees enregistrés | 15 (dont le checkout principal) |
| Branches supprimables par l'outil actuel | au plus 14 (une par worktree candidat) |

Le reste — l'écrasante majorité — est constitué de branches **déjà squash-mergées** dont plus
aucun worktree ne dépend : `feat/SF-01-02-inscription-connexion-email` (0 commit `+`),
`feat/SF-02-*`, `docs/F-*-complete`, `worktree-wf_*` de vagues éteintes… Elles sont invisibles
pour l'outil actuel parce qu'aucun worktree ne les porte plus.

**Pourquoi cela nuit à la vague suivante** : `git branch --no-merged` les affiche à tort comme non
mergées (squash-merge), l'espace de nommage `worktree-wf_*` se remplit, et un agent parallèle qui
crée son worktree tombe sur un nom déjà pris ou repart d'une branche périmée — le « worktree
pollué » que ce mandat vise. Le `git worktree prune` seul ne règle rien : il ne touche pas aux
branches.

---

## Comportement nominal

Étape 3 du script, réécrite en **balayage global** (activé par défaut) :

1. Énumérer **toutes** les branches locales (`git for-each-ref refs/heads/`).
2. Écarter les branches **protégées en dur** : `main`, `master`, `HEAD`.
3. Écarter toute branche **encore checked out dans un worktree conservé** — c'est-à-dire tout
   worktree que ce run ne retire pas, checkout principal compris. Les branches libérées par un
   worktree planifié au retrait restent, elles, candidates (y compris en dry-run, où le worktree
   n'a pas encore disparu).
4. Pour chaque candidate restante : `git cherry origin/main <branche>`.
   - au moins un commit `+` → **KEEP**, avec le nombre de commits non mergés affiché ;
   - aucun commit `+` → **DELETE**, avec le SHA de restauration affiché.
5. En dry-run (défaut) rien n'est exécuté : le plan est simplement imprimé.

`--no-sweep-branches` restaure le comportement de SF-REPO-02 (seules les branches libérées par un
retrait sont examinées).

### Réordonnancement des étapes (défaut découvert en développement)

`git worktree prune` passe **avant** la purge des branches (étape 3), là où SF-REPO-02 le plaçait
en dernier. Tant qu'un enregistrement de worktree survit, **Git refuse de supprimer la branche
qu'il déclare checked out**, même si le répertoire a été effacé à la main :

```
error: Cannot delete branch 'stale' checked out at '…/wt'
```

Avec le balayage global, ces branches deviennent candidates : sous `set -e`, l'échec de
`git branch -D` **tuait le run entier**. Le prune est donc déplacé en amont, et l'échec de
`git branch -D` devient non fatal (`ECHEC` tracé, branche conservée, balayage poursuivi).

---

## Cas d'erreur / cas limites

| Cas | Comportement attendu |
|---|---|
| Branche `main` / `master` sans commit `+` | **KEEP** — protection en dur, jamais évaluée |
| Branche du checkout principal (branche courante) | **KEEP** — checked out dans un worktree conservé |
| Branche d'un worktree écarté (`dirty`, `unmerged`, `locked`, `recently-active`) | **KEEP** — le worktree survit, sa branche aussi |
| Branche d'un worktree planifié au retrait, 0 commit `+` | **DELETE** (comportement SF-REPO-02 préservé) |
| Branche portant au moins 1 commit `+` | **KEEP** — jamais de perte de travail non mergé |
| Branche sans ancêtre commun avec `origin/main` | **KEEP** — tous ses commits ressortent en `+` |
| Branche déjà supprimée (2ᵉ run) | ignorée silencieusement — idempotence |
| Dry-run | **aucune** modification de l'état Git |
| Lancement depuis un worktree lié | refus, sortie 3 (inchangé) |
| Échec du fetch `origin` | STOP, sortie 4 (inchangé) — la comparaison à `origin/main` ne serait pas fiable |

---

## Critères d'acceptation vérifiables

- [ ] Une branche locale orpheline (0 commit `+`, rattachée à **aucun** worktree) est planifiée
      au `DELETE` puis effectivement supprimée avec `--apply`.
- [ ] Une branche locale portant au moins 1 commit `+` est **conservée**, motif et compte affichés.
- [ ] Une branche checked out dans un worktree **conservé** est **conservée**, même avec
      0 commit `+`.
- [ ] `main` est conservée en toutes circonstances.
- [ ] Le dry-run laisse l'état Git strictement identique (branches + worktrees).
- [ ] `--no-sweep-branches` ne supprime que les branches libérées par un retrait de worktree.
- [ ] Chaque `DELETE` affiche la commande de restauration.
- [ ] Le résumé final compte les branches supprimées et confirme `origin/main` inchangé.
- [ ] Le test d'intégration existant reste vert (aucune régression sur les 4 garde-fous).

---

## Plan de test minimal

Test d'intégration bash (`scripts/prune-stale-worktrees.test.sh`), dépôt jetable en `mktemp -d`,
config Git utilisateur neutralisée. Cas ajoutés au jeu existant (1 → 7) :

- **Cas 8 — orphelin détaché** : branche `orphan-merged` créée sur `HEAD`, aucun worktree ne la
  porte → prédite `DELETE` en dry-run, absente après `--apply`.
- **Cas 8 (contrôle négatif)** : branche `orphan-ahead` avec un commit propre → `KEEP`, présente
  après `--apply`.
- **Cas 8 (protection)** : `main` toujours présente après `--apply`.
- **Cas 9 — branche d'un worktree conservé** : la branche du worktree `dirty` a 0 commit `+`
  mais son worktree survit → `KEEP` ; contrôle : elle existe toujours après `--apply`.
- **Cas 10 — `--no-sweep-branches`** : l'orpheline détachée n'est **pas** planifiée au `DELETE`.
- **Cas 11 — `--apply`** : `orphan-merged` absente, `orphan-ahead` / `wt-dirty` / `main`
  présentes, aucun fichier non commité perdu.
- **Cas 12 — enregistrement de worktree périmé** : répertoire effacé à la main, run `--apply`
  terminé **sans erreur** et branche `wt-stale` supprimée (preuve que le prune passe avant la
  purge).
- **Non-régression** : les cas 1 à 7 de SF-REPO-02 restent verts (dry-run non destructif,
  garde-fous `dirty` / `unmerged` / `recently-active`, refus depuis un worktree lié, sortie 4 sur
  échec du fetch, idempotence).

**Contrôle d'utilité du test** : les cas 8 à 12 ont été exécutés contre l'implémentation
d'avant-correctif ; ils **échouent** (4 assertions rouges au cas 8-9, puis arrêt dur au cas 10 sur
`option inconnue '--no-sweep-branches'`). Le mécanisme du cas 12 est prouvé séparément :
`git branch -D` sort en **1** sur un enregistrement périmé non prunné, en **0** après le prune.
Un test incapable d'échouer ne vaut rien (même leçon que SF-REPO-02, correctif du 2026-08-26).

**Résultat** : **33 assertions vertes, 0 échec** (21 avant).

**Isolation `user_id`** : sans objet — housekeeping Git pur, aucun accès aux données applicatives,
aucune route, aucune table.

---

## Fichiers impactés

| Fichier | Nature |
|---|---|
| `scripts/prune-stale-worktrees.sh` | purge de branches réécrite en balayage global, `git worktree prune` remonté avant elle, option `--no-sweep-branches` |
| `scripts/prune-stale-worktrees.test.sh` | cas 8 à 12 ajoutés |
| `docs/features/worktrees-orphelins/SF-WO-01-*.md` | cette mini-spec |
| `docs/PRODUCT_SPEC.md` | ligne d'historique |

---

## Préoccupations transversales

| Préoccupation | Impacté ? | Analyse |
|---|---|---|
| Auth / Principal | **Non** | aucun code applicatif |
| Contexte tenant / `user_id` | **Non** | aucun accès aux données |
| Plans / limites / quotas | **Non** | — |
| Navigation / routing | **Non** | aucun écran |
| **Outillage développeur** | **Oui** | `scripts/prune-stale-worktrees.sh` est le seul consommateur ; il n'est appelé par aucun job CI (`.github/workflows/backend.yml` et `frontend.yml` ne le référencent pas) — vérifié par `grep` |

---

## Effet mesuré sur le dépôt (évaluation read-only, 2026-09-06)

Simulation du balayage à `origin/main` = `ece5f50`, sans rien modifier :

| Catégorie | Branches |
|---|---|
| Total local | **172** |
| Protégées en dur (`main`) | 1 |
| Détenues par un worktree conservé | 14 |
| **KEEP** — travail non mergé (≥ 1 commit `+`) | **6** |
| **DELETE** — orphelines | **151** |

Les 6 conservées, nominativement : `chore/canvas-flaky-fix-and-library-rename` (+2, le contrôle
négatif historique de SF-REPO-01), `docs/F-31-cloture-sf-31-05` (+1),
`feat/SF-06-01-ingestion-core` (+1), `feat/SF-12-01-landing` (+1),
`feat/SF-31-05-creation-pull-request` (+1), `feat/SF-31-05-pull-request-mcp-back` (+1).

**Aucune branche portant du travail non mergé n'entre dans le plan de suppression.**

---

## Arbitrages (gates réversibles, décidés par défaut)

| # | Décision | Motif | Réversible |
|---|---|---|---|
| A | **Balayage global activé par défaut**, `--no-sweep-branches` pour l'ancien comportement | c'est l'objet même du mandat ; un nettoyage qui laisse 151 branches sur 172 ne nettoie rien. Le dry-run par défaut reste le vrai garde-fou : l'opérateur voit le plan avant d'agir | oui — un flag |
| B | **Branches distantes toujours conservées** | cohérent avec l'arbitrage (A) de SF-REPO-01 ; c'est aussi ce qui rend les 151 suppressions locales sans conséquence (les `feat/*` et `docs/*` restent sur `origin`) | oui |
| C | **Détention calculée depuis le plan, pas d'un `git worktree list` relu** | en dry-run le worktree candidat n'a pas encore été retiré : le relire annoncerait un `KEEP` pour une branche que `--apply` supprimerait. Le rapport de dry-run doit prédire exactement ce que fait `--apply` | oui |
| D | **Pas de plafond `--max-deletions`** | le dry-run obligatoire montre déjà les 151 lignes une par une, avec leur SHA de restauration ; un plafond arbitraire n'ajouterait qu'une friction à franchir | oui |
| E | **Les branches détenues ne sont pas listées en balayage global** | 14 lignes `KEEP` sans information noieraient les quelques `KEEP` qui comptent (travail non mergé). Elles restent affichées avec `--no-sweep-branches`, où le volume est faible | oui |

---

## Hors périmètre

- **Suppression de branches distantes** : moins réversible, retire le contexte de diff des PR
  fermées. Conservé, cohérent avec l'arbitrage (A) de SF-REPO-01.
- **Exécution effective `--apply`** sur ce dépôt : un agent isolé en worktree lié ne peut pas la
  lancer (sortie 3 par conception). Reste à faire depuis le checkout principal.
- Élargissement des garde-fous de worktree (déjà couverts par SF-REPO-02).
- Toute modification du produit : aucune. V1 = passerelle, périmètre intact.
