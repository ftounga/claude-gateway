# Skill — parallel-frontback-delivery (claude-gateway)

**Quand l'invoquer** : une feature comporte ≥ 2 SF indépendantes dont les contrats peuvent être figés avant le dev — typiquement 1 backend (endpoint API) + 1 frontend (UI). Complète `feature-autonome.md` : celle-ci enchaîne séquentiellement, celle-là lance en parallèle quand les contrats sont figés.

## Règle d'or
> **Contrat figé = parallélisation possible.** Si le contrat API est noir-sur-blanc dans la mini-spec backend (méthode, URL, body, response, erreurs, codes), backend et frontend partent en parallèle dans 2 worktrees isolés sans divergence.

## Pré-requis non négociables
| # | Item | Si absent |
|---|------|-----------|
| 1 | Mini-spec backend avec section « Contrat API » complète | REFUS |
| 2 | Mini-spec frontend référençant le contrat (`"contrat importé de SF-XX-YY-backend"`) | REFUS |
| 3 | 2 branches distinctes `feat/SF-XX-YY-backend` + `-frontend` depuis `origin/main` | REFUS (partage de branche interdit) |
| 4 | 2 worktrees isolés (`isolation: "worktree"`) | Fortement recommandé (évite conflits `node_modules`/`.mvn`) |
| 5 | Tests frontend sur **mock** du service (indépendants du backend mergé) | REFUS |

## Procédure
### Étape 0 — Annoncer le plan
Lister : les SF back/front, les **contrats API figés**, l'estimé par SF (lecture code avant), l'engagement « 2 agents en //, merge des 2 PR, staging, docs — sans pause ».

### Étape 1 — Vérifier les artefacts pré-dev
`PRODUCT_SPEC` contient F-XX ; mini-specs back + front existent (contrat complet côté back, référencé côté front) ; readiness PASS affichée pour les 2 SF ; `main` à jour (`git fetch && git pull`).

### Étape 2 — Lancer les 2 agents en parallèle
Un seul message, 2 invocations `Agent` (`isolation: "worktree"`) :
- **Backend** : lire mini-spec → branche `feat/SF-XX-YY-backend` depuis `origin/main` → endpoint + service + repository + tests unitaires/intégration (layering strict, isolation `user_id`, provider via `AIProvider`, Liquibase avec UUID/n° migration pré-assignés) → `mvn -pl backend test` vert → commit + push + `gh pr create` → retourner URL PR + arbitrages.
- **Frontend** : lire mini-spec (avec contrat) → branche `feat/SF-XX-YY-frontend` depuis `origin/main` → composant + service avec **mock** backend + tests → `npm run build && npm test` vert → commit + push + `gh pr create` → retourner URL PR + arbitrages.

Briefer chaque agent avec un **self-check grep pré-commit** (vérifier patterns canoniques, imports orphelins, handlers manquants).

### Étape 3 — Vérifs avant merge
Endpoints consommés côté front existent côté back (sur `main` ou dans la PR back) ; `gh pr checks <PR#>` verts ; review checklist affichée par PR.

### Étape 4 — Merger (backend AVANT frontend)
```bash
gh pr merge <PR-backend>  --squash --delete-branch
gh pr merge <PR-frontend> --squash --delete-branch
```
CI rouge post-merge → `fix/SF-XX-YY-nom` dans le même flow.

### Étape 5 — Staging (une fois)
`gh workflow run backend.yml --ref main` + `frontend.yml` ; healthcheck `portal.ng-itconsulting.com/api/actuator/health`.

### Étape 6 — Docs groupées + Étape 7 — Récap unique
Voir `feature-autonome.md` §4 et §6.

## Anti-patterns interdits
| ❌ | ✅ |
|---|---|
| « Multi-agent ou séquentiel ? » | Lancer en // directement quand contrat figé |
| 2 agents sans contrat API figé | Figer le contrat dans la mini-spec back d'abord |
| Merger frontend avant backend | Backend d'abord (évite 404 runtime) |
| Partager 1 worktree entre 2 agents | 1 worktree isolé par agent |
| Récaps intermédiaires | 1 récap unique à la fin |

## Cas d'arrêt légitime
Contrat API à modifier en cours → stopper les 2 agents, MAJ mini-spec, relancer. Risque sécurité critique. Conflit de fichier non trivial avec une autre branche. « stop » explicite.
