# Skill — feature-autonome (claude-gateway)

**Quand l'invoquer** : implémenter une feature complète **en autonomie sans pause** :
- *« Implémente F-XX totalement en autonome. Si tu vois des arbitrages, fais au mieux »*
- *« Pars du principe que je suis tes recos »* / *« Vas-y jusqu'au bout »* / *« Toutes les SF d'affilée »*

## Règle d'or
> **Une feature ≠ une SF.** « Implémenter la feature en autonome » = livrer **toutes les SF** de la feature jusqu'au déploiement staging, **sans confirmation entre chaque SF**, avec un récap unique à la fin.

## Procédure

### Étape 0 — Vérifier la définition
1. Localiser la feature dans `docs/PRODUCT_SPEC.md` (features **V1** uniquement ; refuser V2/hors-scope).
2. Lister explicitement **toutes les SF** prévues.
3. Annoncer : *« F-XX = N SF. J'enchaîne SF-XX-01 → SF-XX-N + staging + docs sans pause. »*

### Étape 1 — Recalibrer les estimés AVANT chaque SF
Les bornes des mini-specs sont calibrées « humain découvrant le code ». **Lire le code cible (5-15 min) avant chaque SF** pour estimer le vrai effort (souvent l'infra/pattern existe déjà). Si effort réel > 4h après lecture → signaler avant de coder ; sinon continuer.

### Étape 2 — Séquence CLAUDE.md par SF, sans pause
1. **Mini-spec** `docs/features/F-XX/SF-XX-YY-nom.md` (template `project-governance/templates/subfeature-template.md`)
2. **Readiness checklist** (`project-governance/checklists/readiness-checklist.md`) — verdict PASS
3. **Branche** `feat/SF-XX-YY-nom` depuis `origin/main` à jour
4. **Code** + tests unitaires (respecter `PROJECT.md`/`ARCHITECTURE.md`/`CODING_RULES.md` : layering Controller→Service→Repository, isolation `user_id`, provider via `AIProvider`, secrets hors code)
5. **Compile + tests verts** (`mvn -pl backend test` + `npm run build && npm test`)
6. **Review checklist** (`project-governance/checklists/review-checklist.md`) — verdict PASS
7. **Commit** (Co-Authored-By: Claude), **push**, **PR** (`gh pr create`)
8. **Release checklist** (`project-governance/checklists/release-checklist.md`) — verdict PASS
9. **Merge** `gh pr merge --squash --delete-branch`
10. **SF suivante** sans demander

### Étape 3 — Hotfix CI = nouvelle branche dans le même flow
CI rouge après merge → `fix/SF-XX-YY-nom`, fixer, merger via la même séquence. Ne pas s'interrompre.

### Étape 4 — Docs post-merge groupé après la dernière SF
1 seul commit `docs/F-XX-complete-YYYY-MM-DD` : feature `Terminée` dans `PRODUCT_SPEC.md` si toutes SF done ; 1 entrée historique par SF ; arbitrages ; MAJ `ARCHITECTURE_CANONIQUE.md` si nouvelles tables.

### Étape 5 — Déploiement staging final
`gh workflow run backend.yml --ref main` + `frontend.yml` ; healthcheck `curl https://portal.ng-itconsulting.com/api/actuator/health` (background). Ne pas demander « je continue ? » entre-temps.

### Étape 6 — Récap unique à la fin
N PR mergées + commits ; état staging (URL, runs verts) ; **arbitrages** pris ; risques résiduels ; SF suivantes débloquées.

## Anti-patterns interdits
| ❌ | ✅ |
|---|---|
| « SF-XX-01 livrée. Je continue ? » | Enchaîner sans demander |
| Se fier à l'estimé spec sans lire le code | Ré-estimer sur le code, continuer |
| Récap intermédiaire par SF | Récap unique à la fin |
| Nouvelle SF sans avoir mergé la précédente | Toujours merger avant la suivante |

## Exception légitime (stopper + demander)
- Décision **produit** non écrite (ni mini-spec ni PRODUCT_SPEC) → prendre la reco par défaut, la tracer, continuer (pas de pause).
- Conflit avec une autre session éditant les mêmes fichiers.
- Risque **sécurité critique** non documenté, ou dérive **hors périmètre V1** (OCR/RAG/pgvector).
- Build cassé non trivial > 30 min de debug → signaler avant de partir loin.
