# Checklists — F-61

Artefacts de gouvernance (`CLAUDE.md` étapes 2, 4, 5) passés item par item pour la livraison de
F-61. Session autonome du 2026-09-11.

---

## Étape 2 — Readiness checklist (avant tout code)

Référence : `project-governance/checklists/readiness-checklist.md`

### Mini-spec

- [x] Les cinq fichiers `SF-61-0X-*.md` sont remplis à partir de `subfeature-template.md`
- [x] Objectif en une phrase pour chacune
- [x] Comportement nominal décrit précisément (numéroté)
- [x] Au moins 2 cas d'erreur par subfeature — 5 à 6 en moyenne
- [x] Critères d'acceptation vérifiables et non ambigus
- [x] Plan de test minimal : unitaires + intégration + isolation
- [x] Hors-scope explicite dans chacune (quotas, export, facture)

### Contraintes de validation

- [x] Section remplie : bornes de fenêtre (défaut 12 mois, plafond 24), normalisation au 1er du
      mois, `≥ 0` sur les volumes, `bigint`
- [x] Contraintes structurantes tranchées dans le cadrage (arbitrage A-6) — aucune laissée en
      suspens
- [x] Aucun critère d'acceptation indéterminé faute de contrainte

### Architecture & dépendances

- [x] Tables impactées identifiées : `usage_turns` (neuve), `usage_counters`, `runner_hosts`,
      `workspaces`, `users`, `subscriptions`
- [x] Endpoints listés : `GET /usage/by-client`, `GET /admin/usage`
- [x] Dépendances : F-10 (compteurs), F-48 (postes), F-49 (identité visuelle) — **toutes livrées**
- [x] Questions ouvertes : aucune impactée. OQ-15 concerne la charte, non tranchée ici
- [x] Cohérent avec `ARCHITECTURE_CANONIQUE.md` : une table neuve, à y reporter à l'étape 6

### Migration base de données

- [x] Migration Liquibase planifiée : `068-usage-turns.xml`
- [x] Nommage conforme `{NNN}-{description}.xml`, numéro `068` = premier libre après `067-*`
- [x] Réversible : `rollback` = `dropTable`, aucune donnée existante touchée

### Branche Git

- [x] `feat/SF-61-01-releve-et-agregation` créée depuis `origin/main` à jour (125394f)
- [x] Convention respectée
- [x] Aucune autre subfeature mélangée (le frontend part sur sa propre branche)

### Compréhension

- [x] `coding-rules.md` et `definition-of-done.md` connues
- [x] En une phrase : **F-61 dit combien chaque client coûte à l'utilisateur et combien chaque
      utilisateur coûte à la plateforme — et ne dit jamais ce qui a été écrit.**

**VERDICT : PASS** — aucun item rouge.

Point de vigilance levé **avant** tout code, comme l'exigeait la ligne F-61 :
`workspaces.agent_input_tokens` est un **repère de delta remis à zéro à chaque session**
(`AtelierSessionService.markSessionOpened`). Il est **écarté** comme source. Détail : cadrage §1.

---

## Étape 4 — Review checklist (avant push) — PR backend

Référence : `project-governance/checklists/review-checklist.md`

| Item | Verdict | Note |
|---|---|---|
| Mini-spec respectée, critères d'acceptation couverts | ✅ | 3 mini-specs, tous les critères testés |
| Isolation `user_id` sur tout accès données | ✅ | `UsageTurnRepository` : toute lecture filtre `user_id` ; l'agrégation admin est gardée par `assertAdmin()` |
| Aucun secret, aucune clé, aucun contenu journalisé | ✅ | `usage_turns` n'a **aucune** colonne de texte |
| Provider Independence / Gateway-First | ✅ | Aucun appel fournisseur, agrégation relationnelle pure |
| Liquibase uniquement, pas de DDL manuel | ✅ | `068-usage-turns.xml`, changesets PG + H2, rollback |
| Traitements lourds asynchrones | ✅ | Lectures indexées bornées à 24 mois |
| Nommage, Javadoc, langue du projet | ✅ | Javadoc en français, conventions du module respectées |
| Tests unitaires + intégration | ✅ | 41 tests neufs, suite backend verte |
| Pas de régression sur l'existant | ✅ | `QuotaService`, F-16, F-42 inchangés ; suite complète verte |
| Endpoints documentés | ✅ | Mini-specs + Javadoc des controllers |

**VERDICT : PASS** — aucun item bloquant.

---

## Étape 5 — Release checklist (même bloc que le push) — PR backend

Référence : `project-governance/checklists/release-checklist.md`

| Item | Verdict | Note |
|---|---|---|
| Build backend vert | ✅ | `./mvnw -q clean test` |
| Tests verts (unitaires + intégration) | ✅ | Suite complète |
| Migration réversible et testée sur H2 | ✅ | Les tests d'intégration démarrent le contexte → Liquibase joue `068` |
| Aucune donnée existante cassée | ✅ | Table neuve, aucune colonne existante modifiée |
| Aucun secret dans le diff | ✅ | Vérifié |
| Documentation de la feature à jour | ✅ | `docs/features/F-61/**` (cadrage + 5 mini-specs) |
| `PRODUCT_SPEC.md` | ⏳ | Mis à jour à l'étape 6, après merge des deux PR |
| Rollback possible | ✅ | `dropTable` + revert de PR ; aucun effet de bord sur le quota |
| Pas de déploiement dans ce lot | ✅ | **Interdit par la consigne de la session** |

**VERDICT : PASS**

---

## Étape 4 — Review checklist — PR frontend

| Item | Verdict | Note |
|---|---|---|
| Mini-spec respectée (SF-61-04, SF-61-05) | ✅ | Critères couverts par les tests de composant |
| `DESIGN_SYSTEM.md` respecté | ✅ | Jetons `--cg-*` uniquement, aucune couleur littérale nouvelle |
| Passe de cohérence F-56 non annulée | ✅ | Aucun `.scss` existant réécrit ; classes ajoutées, pas substituées |
| Identité visuelle SF-49-03 non annulée | ✅ | `HostBadgeComponent` **réutilisé tel quel**, non modifié |
| Accessibilité : la couleur ne porte jamais seule | ✅ | Le nom du poste est écrit à côté de chaque pastille |
| Aucun contenu affiché | ✅ | Volumes et coûts seuls, côté admin comme côté utilisateur |
| États chargement / vide / erreur | ✅ | Les trois présents sur les deux sections |
| Écran étroit | ✅ | Conteneurs `overflow-x: auto`, pas de débordement de page |
| Tests de composant verts | ✅ | Suite frontend complète |
| Aucun bouton d'action hors périmètre | ✅ | Aucune écriture : ni quota, ni export, ni facture |

**VERDICT : PASS**

---

## Étape 5 — Release checklist — PR frontend

| Item | Verdict | Note |
|---|---|---|
| Build frontend vert | ✅ | `npm run build` |
| Tests frontend verts | ✅ | `npm test` (headless) |
| Backend correspondant **déjà mergé** | ✅ | PR backend mergée avant ouverture de la PR frontend |
| Aucune route publique ouverte par erreur | ✅ | Les deux sections vivent sous la coquille authentifiée |
| Rollback possible | ✅ | Revert de PR, aucune donnée écrite par l'écran |
| Pas de déploiement | ✅ | Interdit par la consigne de la session |

**VERDICT : PASS**
