# Mini-spec — REPO / SF-REPO-02 — Purge des worktrees résiduels avant chaque vague

> Feature : `REPO` — housekeeping du dépôt Git (aucun code applicatif, aucune migration, aucun écran)

---

## Identifiant

`REPO / SF-REPO-02`

## Feature parente

`REPO` — Hygiène du dépôt (housekeeping Git).
Lignée : `worktree_doc_cleanup` (SF-CLEANUP-01, 2026-07-01), `stale-worktrees-housekeeping`
(SF-HK-01, 2026-07-10), `REPO / SF-REPO-01` (purge des branches `feat/SF-28-*`, 2026-08-25).

**Aucune feature nouvelle n'est créée** : cette subfeature prolonge la feature de housekeeping
`REPO` déjà référencée dans `docs/PRODUCT_SPEC.md` (historique du 2026-08-25).

## Statut

`done`

## Date de création

2026-08-26

## Branche Git

`chore/repo-prune-stale-worktrees`

---

## Objectif

Rendre la purge des worktrees résiduels **systématique et sûre avant chaque vague** de livraison
autonome, en fournissant un script idempotent et sécurisé plutôt qu'une suppression manuelle
ponctuelle, afin qu'aucun worktree de vague précédente ne subsiste face aux worktrees isolés
d'une nouvelle vague.

---

## Contexte

Le mandat initial était : « purger avant de lancer la vague (`git worktree prune`) pour éviter
toute collision avec les worktrees isolés de la nouvelle vague », visant les worktrees
`wf_70cf627f-*` et `wf_d6e012f1-*`.

### Constat de cadrage n°1 — `git worktree prune` seul est un no-op ici

`git worktree prune` ne supprime que les **enregistrements administratifs** de worktrees dont le
répertoire a déjà disparu. Les répertoires des worktrees visés **existent toujours** sur disque :
`prune` seul ne les retire donc pas. La suppression effective exige `git worktree remove`, qui est
une opération **destructive** (elle efface le répertoire de travail, donc toute modification non
commitée qu'il contient).

### Constat de cadrage n°2 — état réel des worktrees visés (2026-08-26)

`git fetch origin` OK, `origin/main` = `79aac39`.

| Worktree | Branche | HEAD | `git cherry origin/main` | Ancêtre de `origin/main` ? |
|---|---|---|---|---|
| `.claude/worktrees/wf_70cf627f-484-2` | `verify-final` | `ff775f1` | vide (0 `+`) | **oui** |
| `.claude/worktrees/wf_70cf627f-484-3` | `verify-f35` | `a4c6d3c` | vide (0 `+`) | **oui** |
| `.claude/worktrees/wf_d6e012f1-9e8-2` | `verify-main` | `0a0d856` | vide (0 `+`) | **oui** |
| `.claude/worktrees/wf_d6e012f1-9e8-3` | `verif-main-f36` | `d45a91b` | vide (0 `+`) | **oui** |

Aucun des 4 n'est verrouillé (`git worktree list --porcelain` ne produit aucune ligne `locked`).
**Aucun ne porte de commit non mergé** : les 4 HEAD sont des ancêtres directs de `origin/main`.

### Constat de cadrage n°3 — la collision annoncée n'existe pas

Les worktrees de la vague courante portent le préfixe `wf_2ad40587-*`. Les préfixes
`wf_70cf627f-*` / `wf_d6e012f1-*` sont **disjoints** : il n'y a **aucune collision de chemin ni de
branche** possible. Le gain réel de la purge est l'hygiène du dépôt et l'espace disque, pas le
déblocage de la vague. Le mandat est donc rempli par un dispositif **récurrent** plutôt que par un
geste ponctuel.

### Constat de cadrage n°4 — l'exécution ne peut pas venir d'un agent isolé

L'agent de livraison s'exécute dans un worktree isolé (`wf_2ad40587-519-4`). Le harnais **refuse
par conception** toute opération Git visant le checkout partagé ou un autre worktree
(`git -C <autre-worktree> …`, `git worktree remove <autre-worktree>`). Un agent isolé ne peut donc
ni vérifier la propreté d'un autre worktree, ni le supprimer. Supprimer sans pouvoir vérifier la
propreté violerait la règle de sécurité posée par SF-HK-01 (« worktree sale → NE PAS supprimer »).

**Conséquence** : le livrable est le script, exécuté par l'orchestrateur depuis le checkout
principal. Voir « Arbitrages ».

---

## Comportement attendu

Livrables : `scripts/prune-stale-worktrees.sh` et son test `scripts/prune-stale-worktrees.test.sh`.

### Cas nominal

1. `git fetch origin` (sauf `--no-fetch`) — raisonner sur `origin/main`, jamais sur un working tree.
2. Refuser de s'exécuter depuis un worktree lié : le script doit tourner dans le checkout principal.
3. Énumérer les worktrees via `git worktree list --porcelain`, ne retenir que ceux situés sous
   `.claude/worktrees/`.
4. Pour chaque candidat, appliquer **quatre garde-fous cumulatifs** ; un seul qui échoue ⇒ SKIP :
   - non verrouillé (`locked` absent) ;
   - **inactif** : aucune activité depuis plus de `--age-minutes` (défaut 60) — protège les
     sessions concurrentes vivantes. Trois signaux testés, du moins cher au plus cher :
     (a) mtime de la racine du worktree, (b) métadonnées Git du worktree
     (`index`, `HEAD`, `logs/HEAD`, `ORIG_HEAD` — rafraîchies par quasiment toute commande Git
     exécutée dedans), (c) parcours récursif du contenu élagué des répertoires de build
     (`node_modules`, `target`, `dist`, `.angular`, `.git`) et arrêté au premier fichier récent
     (`-quit`) ;
   - **propre** : `git -C <wt> status --porcelain` vide ;
   - **déjà dans `main`** : `git merge-base --is-ancestor <HEAD> origin/main`.
5. `git worktree remove` des candidats retenus.
6. Pour la branche de chaque worktree retiré : `git branch -D` seulement si elle a **0 ligne `+`**
   face à `origin/main` et n'est plus checked out nulle part. `main` est protégée en dur.
7. `git worktree prune` final (nettoyage des enregistrements orphelins).
8. Rapport : SHA de chaque worktree/branche traité (réversibilité) et motif de chaque SKIP.

**Le script est en dry-run par défaut** ; `--apply` est requis pour toute action destructive.

### Cas d'erreur

| Situation | Comportement attendu |
|---|---|
| Worktree **sale** (modifications non commitées) | **NE PAS supprimer** — SKIP + motif `dirty` au rapport |
| HEAD du worktree **non ancêtre** de `origin/main` (travail non mergé) | **NE PAS supprimer** — SKIP + motif `unmerged` |
| Worktree **verrouillé** | **NE PAS forcer** — SKIP + motif `locked` |
| Worktree **récemment actif** (< `--age-minutes`) | **NE PAS supprimer** — SKIP + motif `recently-active` (session vivante) |
| `git fetch` en échec (réseau) | **STOP** (code ≠ 0) — la comparaison à `origin/main` ne serait pas fiable |
| Script lancé depuis un worktree lié | **STOP** — refus explicite |
| Worktree/branche déjà absent (purge concurrente) | Ignorer sans erreur — **idempotence** |
| Branche encore checked out ailleurs | Ne pas supprimer (Git refuse de toute façon) |

---

## Critères d'acceptation

- [x] `scripts/prune-stale-worktrees.sh` existe, est exécutable et passe `bash -n` (syntaxe).
- [x] `scripts/prune-stale-worktrees.test.sh` existe et passe : **21 assertions vertes, 0 échec**.
- [x] Sans `--apply`, le script **ne modifie rien** (dry-run par défaut, vérifié : `git worktree list`
      et `git branch` identiques avant/après).
- [x] Le script refuse de s'exécuter depuis un worktree lié.
- [x] Le script n'agit que sur les worktrees situés sous `.claude/worktrees/` — le checkout
      principal et `main` ne sont jamais candidats.
- [x] Les 4 garde-fous (`locked`, `recently-active`, `dirty`, `unmerged`) sont implémentés et
      chacun produit un SKIP tracé.
- [x] Les SHA des 4 worktrees visés sont consignés ci-dessus → restauration par
      `git worktree add <chemin> <sha>` / `git branch <nom> <sha>` (opération **réversible**).
- [x] Aucun worktree portant du travail non mergé ne peut être supprimé (garde-fou `unmerged`).
- [x] `origin/main` inchangé : `git rev-parse origin/main` = `79aac39` avant et après.
- [x] Aucun fichier source backend/frontend, aucune migration Liquibase, aucun écran Angular touché.

## Plan de test minimal

Test automatisé : `scripts/prune-stale-worktrees.test.sh` monte un dépôt Git **jetable** dans un
répertoire temporaire (`mktemp -d`, jamais dans ce dépôt, config utilisateur neutralisée par
`GIT_CONFIG_GLOBAL=/dev/null` + `GIT_CONFIG_NOSYSTEM=1`) avec trois worktrees : `wf_test-1`
(propre + mergé), `wf_test-2` (commit non mergé), `wf_test-3` (fichier non commité).

| # | Cas | Attendu | Résultat |
|---|---|---|---|
| 1 | Garde-fou `recently-active` (défaut 60 min) | Les 3 worktrees récents écartés, 0 retrait | ✅ |
| 2 | Dry-run (`--age-minutes 0`, sans `--apply`) | État Git **strictement identique** avant/après ; plan correct (`wf_test-1` REMOVE, `wf_test-2` `unmerged`, `wf_test-3` `dirty`, branche `wt-clean` DELETE prédite) | ✅ |
| 3 | `--apply` | `wf_test-1` retiré ; `wf_test-2` et `wf_test-3` **conservés** ; `scratch.txt` non commité **intact** ; `wt-clean` supprimée ; `wt-unmerged` et `main` conservées | ✅ |
| 4 | Idempotence | Ré-exécution `--apply` sans erreur (code 0) | ✅ |
| 5 | Lancement depuis un worktree lié | Code de sortie **3**, aucun effet | ✅ |
| 6 | `git fetch` en échec (remote inexistant) | Code de sortie **4** (STOP) | ✅ |
| 7 | Worktree propre + mergé, **racine backdatée de 3 h**, fichier **imbriqué** touché à l'instant | Écarté en `recently-active` ; redevient candidat avec `--age-minutes 0` | ✅ |

**21 assertions, 0 échec.** `bash -n` passe sur les deux scripts.

### Correctif issu du cas 7 (post-merge PR #179)

Le cas 7 a été ajouté après coup et a **révélé un défaut réel** de la première version : le
garde-fou d'inactivité testait `find "$wt" -maxdepth 0 -newermt …`, c'est-à-dire la seule mtime
du **répertoire racine** du worktree. Or celle-ci ne change que lorsqu'une entrée est créée ou
supprimée *à la racine* : un agent éditant `backend/src/main/java/…` pendant des heures la laisse
intacte. Le worktree d'une session vivante était donc jugé **inactif**, et le garde-fou ne
remplissait pas sa fonction — les garde-fous `dirty` et `unmerged` restaient la seule protection
effective.

Corrigé par la fonction `is_recently_active()` (trois signaux, cf. « Cas nominal » étape 4).
**Le cas 7 est un test de non-régression vérifié** : exécuté contre l'implémentation d'avant le
correctif, il **échoue** (2 assertions rouges) ; contre la version corrigée, il passe. Un test
qui ne peut pas échouer ne vaut rien — le contrôle a été fait explicitement.

Vérifications manuelles complémentaires :

- **Sécurité pré-suppression** sur le dépôt réel : pour chacun des 4 worktrees visés,
  `git cherry origin/main <branche>` == 0 ligne `+` et
  `git merge-base --is-ancestor <HEAD> origin/main` == vrai. **Exécuté et tracé**
  (tableau « Constat de cadrage n°2 »).
- **Contrôle négatif** : le worktree de la session courante (`wf_2ad40587-519-4`) et le checkout
  principal ne figurent jamais dans les candidats (filtre de préfixe + exclusion de `repo_root`).
- **Garde-fou worktree lié, dépôt réel** : `bash scripts/prune-stale-worktrees.sh` depuis
  `wf_2ad40587-519-4` → sortie 3, aucun effet.
- **Isolation `user_id`** : sans objet — housekeeping Git pur, aucun accès aux données
  applicatives, aucun endpoint, aucune requête SQL.
- **Tests unitaires/intégration Java & Angular** : sans objet — aucun fichier `backend/**` ni
  `frontend/**` dans le diff (mêmes justifications que SF-REPO-01 et SF-HK-01).

---

## Tables / endpoints / composants impactés

Aucun. Pas de table, pas de migration Liquibase, pas d'endpoint, pas de composant Angular,
pas d'appel fournisseur (ni `AIProvider` ni Anthropic direct), pas de secret manipulé.

## Préoccupations transversales

| Préoccupation | Impacté ? | Justification |
|---|---|---|
| Auth / Principal | Non | Aucun code d'authentification touché |
| Contexte tenant / `user_id` | Non | Aucun accès aux données applicatives |
| Plans / limites | Non | Aucun gate ni quota touché |
| Navigation / routing | Non | Aucune route Angular touchée |

---

## Arbitrages (gates 🟠 réversibles, décidés par défaut)

| # | Décision | Pourquoi | Alternative écartée | Réversible |
|---|---|---|---|---|
| A | **Livrer un script versionné** plutôt qu'une suppression manuelle ponctuelle des 4 worktrees | Le mandat dit « purger **avant de lancer la vague** » : c'est une étape **récurrente**, donc outillée. De plus, un agent isolé ne peut pas exécuter la suppression (constat n°4) | Suppression manuelle one-shot : non rejouable, et impossible depuis un agent isolé | Oui — le script peut être supprimé ou remplacé |
| B | **Dry-run par défaut**, `--apply` obligatoire | Opération destructive : le défaut doit être inoffensif | `--apply` implicite | Oui |
| C | **Garde-fou d'âge** (`--age-minutes`, défaut 60) en plus des garde-fous de SF-HK-01 | Plusieurs agents travaillent en parallèle ; un worktree touché il y a 5 min appartient probablement à une session vivante | Se fier au seul `status --porcelain` : un agent peut avoir un HEAD propre entre deux commits | Oui — paramètre CLI |
| D | **Branches distantes conservées** | Cohérence avec l'arbitrage (A) de SF-REPO-01 : la purge distante est moins réversible | Purge locale + distante | Oui — faisable plus tard |

---

## Périmètre

### Hors scope (explicite)

- **Exécution effective du `--apply`** sur les 4 worktrees `wf_70cf627f-*` / `wf_d6e012f1-*` :
  revient à l'orchestrateur depuis le checkout principal (`scripts/prune-stale-worktrees.sh --apply`).
  Un agent isolé en est empêché par conception (constat n°4) et ne doit pas supprimer un worktree
  dont il ne peut pas vérifier la propreté.
- **Worktrees hors `.claude/worktrees/`** : jamais candidats.
- **Branches distantes** : conservées (arbitrage D).
- **Autres familles de branches locales** (`feat/*`, `docs/*`, `fix/*`) : hors périmètre, traitées
  par SF-REPO-01 pour la famille `feat/SF-28-*`.
- **Aucune modification** de `docs/PROJECT.md`, du code backend/frontend ou de la configuration.
- **Aucun déploiement staging** (l'orchestrateur déploie une seule fois en fin de vague).
