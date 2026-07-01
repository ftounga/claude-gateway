# Mini-spec — [worktree_doc_cleanup / SF-CLEANUP-01] Fermeture des worktrees et branches docs post-merge

> Feature de maintenance (chore). Aucun code fonctionnel produit. Housekeeping git.

---

## Identifiant

`worktree_doc_cleanup / SF-CLEANUP-01`

## Feature parente

`worktree_doc_cleanup` — Nettoyage des restes de la vague de livraison V1 précédente (6 worktrees actifs sur branches `docs/F-XX-complete-2026-07-01`, post-merge, non bloquants).

## Statut

`done`

## Date de création

2026-07-01

## Branche Git

`chore/worktree-doc-cleanup`

---

## Objectif

> En une phrase : supprimer les worktrees et branches de documentation résiduels de la vague V1 précédente, dont le contenu est déjà intégralement présent sur `main`, sans rien perdre.

---

## Comportement attendu

### Cas nominal

1. Constater que les 6 worktrees `wf_5a5cc935-4d7-2..7` sont des restes post-merge (répertoires déjà physiquement absents, métadonnées git orphelines).
2. Vérifier que chaque branche `docs/F-XX-complete-2026-07-01` est **entièrement superseded** : les lignes d'historique et le passage des features en `Terminée` qu'elles portent sont déjà présents sur `main` (grep : 6/6 présents). Les diffs `main..branche` ne montrent que des suppressions dues au retard de la branche (branche en arrière de `main`), aucun contenu unique.
3. Enregistrer les SHA des branches supprimées (réversibilité via reflog/remote).
4. `git worktree prune` pour purger les métadonnées orphelines des 6 worktrees.
5. Supprimer les branches locales : `docs/F-{02,04,09,10,11,12}-complete-2026-07-01`, `verify-f09-main`, et les branches d'assistance `worktree-wf_5a5cc935-4d7-{2..7}`.
6. Supprimer les branches distantes : `origin/docs/F-{02,04,09,10,11,12}-complete-2026-07-01`.
7. `git fetch --prune` pour nettoyer les références de suivi.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Une branche docs porte un contenu unique non présent sur `main` | BLOCAGE — ne pas supprimer, escalader (non rencontré : 6/6 superseded) |
| Un worktree contient des modifications non commitées | Ne pas supprimer, signaler (non rencontré : répertoires déjà absents) |
| Suppression distante refusée (protection de branche) | Journaliser, continuer sur le reste, signaler dans le récap |

---

## Critères d'acceptation

- [x] Les 6 branches `docs/F-XX-complete` confirmées superseded (contenu déjà sur `main`, grep 6/6).
- [x] SHA de toutes les branches supprimées enregistrés avant suppression (réversibilité).
- [x] `git worktree list` ne liste plus que le repo principal et le worktree courant après `prune`.
- [x] Branches locales et distantes `docs/F-XX-complete` supprimées ; `verify-f09-main` et branches `worktree-wf_5a5cc935-*` supprimées.
- [x] `main` inchangé côté code/produit ; aucune régression (aucun fichier de code touché).
- [x] Aucune branche `feat/SF-*` (PR de features livrées) ni `chore/*` hors périmètre supprimée.

---

## Périmètre

### Hors scope (explicite)

- Suppression des branches `feat/SF-*` distantes (PR de features livrées) — cleanup distinct, non demandé.
- Suppression de `chore/F-01-docs-post-merge`, `docs/amend-scope-v2`, `docs/project-foundation-v1`, autres `chore/*` — hors du groupe « 6 worktrees docs » ciblé par la feature.
- Toute modification de code backend/frontend, migration, table, endpoint.

---

## Technique

### Tables impactées

Aucune. Aucune migration Liquibase. Aucun accès données, donc pas de règle d'isolation `user_id` applicable.

### Préoccupations transversales

Aucune (auth / tenant / plans / navigation non touchés). Feature purement opérationnelle git.

---

## Plan de test

### Vérifications (housekeeping — pas de test unitaire applicable)

- [x] `grep` des 6 lignes « F-XX … terminée » dans `docs/PRODUCT_SPEC.md` → 6/6 présentes (contenu des branches déjà sur `main`).
- [x] `git worktree list` après prune → seuls repo principal + worktree courant.
- [x] `git branch --list 'docs/F-*'` et `'worktree-wf_5a5cc935-*'` → vides après suppression.
- [x] `git ls-remote origin 'refs/heads/docs/F-*'` → vide après suppression distante.
- [x] `git log --oneline -1 main` inchangé (aucun commit produit sur `main` par la feature hormis l'entrée d'historique doc).

### Isolation user_id

Non applicable — aucun accès données.

---

## Notes et décisions

- **Arbitrage (réversible)** : suppression décidée par défaut. Les branches sont 100 % superseded ; leurs SHA sont enregistrés (recréation possible via `git branch <nom> <sha>` depuis le reflog). Risque produit nul.
- Décision conforme à la règle GATE : gate réversible → décider par défaut, implémenter, tracer.
- Aucun déploiement staging (l'orchestrateur déploie une seule fois en fin de vague).

### SHA enregistrés (réversibilité)

| Branche | SHA |
|---------|-----|
| docs/F-02-complete-2026-07-01 | 19e2ae2 |
| docs/F-04-complete-2026-07-01 | 1c639d6 |
| docs/F-09-complete-2026-07-01 | ff128e6 |
| docs/F-10-complete-2026-07-01 | d5f7df0 |
| docs/F-11-complete-2026-07-01 | ce125ce |
| docs/F-12-complete-2026-07-01 | 27e7c99 |
| verify-f09-main | bcb2416 |
