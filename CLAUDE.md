# CLAUDE.md — Instructions projet claude-gateway

## Documents à lire en priorité

Lire ces documents avant toute réponse impliquant du code, une spec ou une décision technique.

### Vision & périmètre produit — SOURCE DE VÉRITÉ ABSOLUE
0. `docs/PROJECT.md` — **source de vérité produit. Prévaut sur tout autre document en cas de conflit.**

Documents de support (subordonnés à `PROJECT.md`) :
- `docs/ARCHITECTURE.md` — principes architecturaux (Gateway-First, Provider Independence, Single Responsibility…)
- `docs/CODING_RULES.md` — standards d'ingénierie
- `docs/DEVELOPMENT_PLAN.md` — séquence de phases V1 (Foundation → Auth → Conversations → Gateway → Hosted/BYOK → Billing → Admin → Monitoring → Prod)
- `docs/ADR.md` — décisions architecturales
- `docs/API_DESIGN.md` — philosophie API
- `docs/PROJECT_STRUCTURE.md` — organisation du code
- `docs/AI_CONTEXT.md` — état courant du projet
- `docs/TECH_STACK.md` — choix technologiques et justifications
- `docs/AI_PROMPTS.md` — prompts standardisés

### Architecture & fonctionnel (⚠️ À RÉCONCILIER avec PROJECT.md — voir §« Réconciliation »)
1. `docs/ARCHITECTURE_CANONIQUE.md` — antérieur à `PROJECT.md`. Décrit OCR/RAG/pgvector **en V1** → **contredit** `PROJECT.md` (ces capacités sont V2). **Ne pas suivre son périmètre V1.**
2. `docs/PRODUCT_SPEC.md` — liste des features. F-04→F-08 (OCR/RAG/pgvector/ask) à re-scoper en V2. Statuts à maintenir.
3. `docs/OPEN_QUESTIONS.md` — registre des sujets non tranchés (OQ-01/02/03/10, liés au RAG, deviennent sans objet en V1)
4. `docs/DESIGN_SYSTEM.md` — charte graphique et règles UI (obligatoire pour tout travail frontend)

### Process
5. `project-governance/playbooks/feature-lifecycle.md` — cycle de vie des features
6. `project-governance/playbooks/definition-of-done.md` — critères de complétion
7. `project-governance/playbooks/coding-rules.md` — conventions de code
8. `project-governance/playbooks/review-rules.md` — critères de review
9. `project-governance/playbooks/testing-strategy.md` — stratégie de test
10. `project-governance/playbooks/dev-workflow.md` — workflow de développement

### Prompts
11. `project-governance/prompts/PROMPTS_CATALOG.md` — catalogue des prompts disponibles

### Checklists
10. `project-governance/checklists/readiness-checklist.md` — avant de démarrer le dev
11. `project-governance/checklists/review-checklist.md` — avant toute PR
12. `project-governance/checklists/release-checklist.md` — avant tout merge

---

## Règles impératives

### Architecture
- Backend : Spring Boot 3.5 / Java 21 | Frontend : Angular 19 | Base : PostgreSQL | Stockage : object storage S3-compatible si applicable
- **Gateway-First** : le backend est une Gateway. Il orchestre, sécurise, facture, journalise, supervise. Il ne devient **jamais** un moteur d'IA ni un clone de Claude.
- **Provider-First** : avant d'implémenter, se demander « Claude fournit-il déjà cette capacité ? ». Si oui → la **relayer**, ne pas la réimplémenter.
- **Provider Independence** : le code métier dépend d'une interface abstraite `AIProvider`, jamais directement d'Anthropic (préparer OpenAI/Gemini/… sans réécriture).
- **Périmètre V1 = passerelle uniquement.** Exclus de V1 (→ V2) : OCR, Textract, embeddings, pgvector, RAG, chunking, recherche/indexation vectorielle, bibliothèque documentaire, mémoire permanente, connecteurs, support multi-LLM.
- **Fichiers V1** : upload + validation + transmission au fournisseur. **Aucun traitement du contenu, aucune indexation.**
- Multi-tenant : chaque client est un utilisateur isolé — filtre `user_id` obligatoire sur tout accès aux données
- Les traitements lourds sont asynchrones
- **Auth (tranché 2026-07-01)** : supporter **les deux** — OAuth2/OIDC (Google) **et** compte email/mot de passe (inscription, reset, vérification email) avec **JWT**. Spring Security. Isolation `user_id` quel que soit le mode.
- Ne pas réinventer la stack sans signaler explicitement une variante

---

## Réconciliation `PROJECT.md` ↔ existant (conflits ouverts)

L'ajout de `PROJECT.md` (source de vérité) a créé des divergences avec l'existant et l'infra déployée. **Arbitrées le 2026-07-01** :

| # | Conflit | Décision |
|---|---------|----------|
| C1 | **Périmètre V1 : OCR/RAG/pgvector/Textract** | **V1 = passerelle pure.** OCR/RAG/pgvector/embeddings/Textract → **V2** (re-scope `PRODUCT_SPEC` F-05/06/07/08). **Infra laissée en l'état** : IRSA Textract + `pgvector` restent en place mais **dormants** (pas de nettoyage maintenant). |
| C2 | **Authentification** | **Les deux** : OAuth2/OIDC (Google) **+** email/mot de passe (reset, vérif email) via **JWT**. |
| C3 | **Monitoring / logging** | **V1 = CloudWatch + Fluent Bit** (infra legalcase réutilisée). Prometheus/Grafana/Loki (`TECH_STACK`) = **cible V2+**. |
| C4 | **Sources de vérité** | `PROJECT.md` prévaut ; `ARCHITECTURE_CANONIQUE`/`PRODUCT_SPEC` subordonnés et réconciliés. |

**F-04 (Upload)** reste en V1 mais **redéfini** : upload + validation + transmission au fournisseur uniquement. **Pas d'OCR, pas d'indexation, pas de persistance documentaire pour RAG.**

---

## Séquence obligatoire par subfeature

Ce cycle est non négociable. Chaque étape produit un artefact visible dans la conversation.
**Sans l'artefact de l'étape N, l'étape N+1 est refusée.**

```
[1] Mini-spec → [2] Readiness → [3] Dev → [4] Review → [5] Push → [6] Merge
```

### Étape 1 — Mini-spec (ARTEFACT : document SF-XX rempli)

**Avant d'écrire la moindre ligne de code**, produire le fichier mini-spec en utilisant `project-governance/templates/subfeature-template.md` comme base.

Le fichier doit contenir :
- Objectif en une phrase
- Comportement nominal + cas d'erreur
- Critères d'acceptation vérifiables
- Plan de test minimal (unitaires + intégration + isolation utilisateur)
- Tables / endpoints / composants impactés
- Ce qui est hors périmètre

Le fichier est créé dans `docs/features/F-XX/SF-XX-YY-nom.md` et son contenu est affiché dans la conversation.

**REFUS si** : le dev démarre sans que ce fichier soit produit et visible dans la conversation.

---

### Étape 2 — Readiness checklist (ARTEFACT : checklist passée item par item)

Avant de créer la branche et d'écrire le code, passer `project-governance/checklists/readiness-checklist.md` et afficher le résultat dans la conversation avec un verdict PASS / FAIL explicite.

**REFUS si** : le premier commit est créé sans que la readiness checklist ait été passée dans cette conversation.

---

### Étape 3 — Dev

Travailler sur une branche `feat/SF-XX-YY-nom-court` créée depuis `master` à jour.
Respecter `project-governance/playbooks/coding-rules.md`.
Toute décision technique non prévue dans la mini-spec est documentée dans la PR.

---

### Étape 4 — Review checklist (ARTEFACT : checklist passée item par item)

Avant tout `git push`, lire `project-governance/checklists/review-checklist.md` et afficher le résultat dans la conversation avec un verdict PASS / FAIL explicite et les items bloquants identifiés.

Les items bloquants doivent être corrigés avant le push. Un item non bloquant peut être poussé avec une note explicite.

**REFUS si** : `git push` est exécuté sans que la review checklist ait été passée et affichée dans cette conversation.

---

### Étape 5 — Push, Release checklist et PR (étape atomique, non séparable)

Ces trois actions forment un bloc indivisible exécuté dans cet ordre exact :

1. `git push -u origin feat/SF-XX-YY-nom-court`
2. Passer `project-governance/checklists/release-checklist.md` item par item et afficher le résultat avec verdict PASS / FAIL — **ARTEFACT obligatoire**
3. Afficher le template PR rempli dans la conversation (titre, corps, checklist)

L'utilisateur ne voit le template PR qu'après avoir vu la release checklist.

**REFUS si** : le push est effectué sans que la release checklist soit produite dans la même réponse.

**REFUS si** : une nouvelle subfeature démarre alors que la release checklist de la subfeature précédente n'a pas été passée dans cette conversation.

---

### Étape 6 — Mise à jour documentation post-merge (ARTEFACT : PRODUCT_SPEC.md à jour)

Dès que l'utilisateur confirme le merge ("mergé", "PR mergée", ou équivalent) :

1. Mettre à jour le statut de la feature parente dans `docs/PRODUCT_SPEC.md` si toutes ses subfeatures sont Done
2. Ajouter une ligne dans l'historique des évolutions de `docs/PRODUCT_SPEC.md`
3. Si une nouvelle table a été créée : vérifier et mettre à jour `docs/ARCHITECTURE_CANONIQUE.md`
4. Commiter ces mises à jour directement sur master

**REFUS si** : la feature parente est complète et PRODUCT_SPEC.md n'a pas été mis à jour avant de démarrer la feature suivante.

---

## Blocages automatiques

Ces situations déclenchent un refus immédiat. Répondre avec le format de refus standard.

| Situation | Réponse |
|-----------|---------|
| Demande brute couvrant plusieurs features distinctes | REFUS — séparer les features avant tout découpage |
| Demande de code sans mini-spec produite dans la conversation | REFUS — produire la mini-spec d'abord (`subfeature-template.md`) |
| Demande de code sans critères d'acceptation dans la mini-spec | REFUS — compléter la mini-spec |
| Demande de code sans plan de test dans la mini-spec | REFUS — compléter la mini-spec |
| Feature non découpée en subfeatures | REFUS — demander le découpage (`feature-splitter`) |
| Subfeature estimée > 2 jours | REFUS — demander un redécoupage |
| `git push` sans review checklist passée dans la conversation | REFUS — passer la review checklist d'abord |
| Push sans release checklist produite dans la même réponse | REFUS — release checklist fait partie du même bloc que le push |
| Démarrage d'une nouvelle subfeature sans release checklist passée pour la précédente | REFUS — produire la release checklist avant de continuer |
| Merge confirmé sans mise à jour PRODUCT_SPEC.md si feature parente complète | REFUS — mettre à jour PRODUCT_SPEC.md d'abord |
| Question ouverte non tranchée et bloquante | BLOCAGE — signaler, ne pas avancer |
| Incohérence avec `ARCHITECTURE_CANONIQUE.md` | BLOCAGE — signaler la divergence |
| Feature non référencée dans `PRODUCT_SPEC.md` | REFUS — ajouter la feature au PRODUCT_SPEC avant tout dev |
| Traitement lourd demandé de façon synchrone | REFUS — rappeler la règle async |
| Accès données sans filtre `user_id` | REFUS — rappeler la règle d'isolation |
| Fonctionnalité de traitement documentaire en V1 (OCR, Textract, embeddings, pgvector, RAG, chunking, recherche vectorielle, indexation) | REFUS — hors périmètre V1 (`PROJECT.md` §1.6/§11.15) → V2 |
| Réimplémentation d'une capacité déjà fournie par Claude (analyse PDF/image, Q&A document, contexte) | REFUS — règle Provider-First (`PROJECT.md` §3.3) : relayer, ne pas réimplémenter |
| Code métier dépendant directement d'Anthropic (pas d'interface `AIProvider`) | BLOCAGE — Provider Independence (`ARCHITECTURE.md` Principle 2) |
| Le backend implémente une logique de « moteur IA » / clone de Claude | BLOCAGE — Gateway-First (`PROJECT.md` §3.2) |
| Composant frontend utilisant couleurs/polices hors `DESIGN_SYSTEM.md` | BLOCAGE — signaler la divergence |
| Écran produit sans layout conforme au design system | BLOCAGE — signaler la divergence |
| Feature avec écran utilisateur marquée `Terminée` sans composant Angular implémenté | REFUS — implémenter les écrans manquants avant de marquer Terminée |
| Subfeature backend mergée sans subfeature frontend planifiée (si la feature a une UI) | BLOCAGE — planifier et créer la subfeature frontend correspondante avant de continuer |
| Préoccupation transversale cochée sans liste de composants impactés dans la mini-spec | BLOCAGE — compléter l'analyse d'impact avant de continuer |

**Format de refus standard :**
```
REFUS [contexte]
Motif : [raison précise]
Artefact manquant : [ce qui doit être produit]
Référence : [fichier de gouvernance concerné]
```

---

## Détection des demandes multi-features

Avant tout traitement, analyser si la demande brute couvre une seule feature ou plusieurs.

Une demande doit être considérée comme **potentiellement multi-features** si elle contient :
- plusieurs comportements visibles distincts et indépendants (ex : "upload ET consultation ET notification")
- plusieurs responsabilités métier séparables (ex : "validation des fichiers ET déclenchement d'un traitement")
- plusieurs écrans ou endpoints indépendants qui ne partagent pas de flux unique
- plusieurs entités principales impactées de façon indépendante

**Règle :** Une demande multi-features ne doit jamais être traitée comme une feature unique sans arbitrage préalable.

**Action requise si multi-features détectée :**
```
REFUS [contexte]
Motif : La demande couvre plusieurs features distinctes.
Features identifiées : [liste des features détectées]
Action requise : Séparer en features indépendantes et traiter chacune séparément.
Référence : CLAUDE.md — Détection des demandes multi-features
```

Si la séparation est ambiguë → escalader au delivery-orchestrator avant de continuer.

---

## Préoccupations transversales — règle anti-régression

Certaines modifications impactent silencieusement des composants existants qui n'ont pas été touchés.
Ces **préoccupations transversales** doivent être traitées explicitement à chaque subfeature.

### Déclencheurs obligatoires

| Préoccupation | Exemples concrets | Action requise |
|--------------|------------------|----------------|
| **Auth / Principal** | Nouveau type d'auth, modification du Principal, changement de session | Lister tous les endpoints utilisant l'auth. Vérifier chacun. Test de non-régression. |
| **Contexte tenant** | Nouveau moyen de résoudre le tenant, changement de `user_id` | Lister tous les composants qui résolvent le tenant. Vérifier leur comportement. |
| **Plans / limites** | Nouveau plan, changement de quota, nouveau gate | Lister tous les appels aux services de limites. Vérifier les gates. |
| **Navigation / routing** | Nouvelle route, guard modifié, redirection ajoutée | Vérifier tous les chemins de navigation existants. |

### Règle de blocage automatique

Si une subfeature coche une préoccupation transversale dans sa mini-spec **sans liste de composants impactés** → BLOCAGE.

---

## Sujets non tranchés

- Toute décision touchant à `docs/OPEN_QUESTIONS.md` doit être explicitement posée avant implémentation
- Ne jamais implémenter silencieusement une solution à un sujet ouvert

## Features — règle d'existence

- Toute feature implémentée doit être référencée dans `docs/PRODUCT_SPEC.md`
- Toute nouvelle feature doit être ajoutée à `docs/PRODUCT_SPEC.md` et validée avant tout dev
- Le statut de chaque feature dans `docs/PRODUCT_SPEC.md` doit être maintenu à jour

---

## Quand tu proposes une modification

1. Rappeler la décision actuelle (architecture ou process)
2. Expliquer la variante proposée et son impact
3. Ne jamais remplacer silencieusement une décision existante
4. Si la modification touche un sujet ouvert, le signaler

---

## Agents et skills disponibles

### Agents
- `ai-agents/orchestrator/delivery-orchestrator.md` — point d'entrée de tout dev
- `ai-agents/backend/backend-agent.md` — implémentation Spring Boot
- `ai-agents/frontend/frontend-agent.md` — implémentation Angular
- `ai-agents/qa/qa-agent.md` — validation qualité
- `ai-agents/review/review-agent.md` — review de code
- `ai-agents/docs/docs-agent.md` — cohérence documentaire

### Skills
- `ai-skills/feature-splitter.md` — découper une feature en subfeatures
- `ai-skills/story-writer.md` — rédiger une mini-spec
- `ai-skills/test-case-generator.md` — générer un plan de test
- `ai-skills/review-checklist-runner.md` — évaluer une PR
- `ai-skills/definition-of-done-checker.md` — valider la complétude

---

## Commandes de développement

### Démarrer le backend

**Profil `dev` (H2 en mémoire — pas besoin de Docker)**
```bash
source .env.local
cd backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```
- Port : 8080 | Base : H2 en mémoire (données perdues à chaque redémarrage)
- Console H2 : http://localhost:8080/h2-console

**Profil `local` (PostgreSQL + services via docker compose)**
```bash
source .env.local
cd backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```
- Port : 8080 | Base : PostgreSQL (données persistantes)
- Requiert : `docker compose up -d`

### Démarrer le frontend
```bash
source ~/.nvm/nvm.sh && nvm use 22
cd frontend && npm start
```
- Port : 4200
- Node 22 requis (géré via nvm)

### Démarrer PostgreSQL (prod locale)
```bash
docker compose up -d
```
- Port : 5432
- DB : `claudegatewaydb` / User : `claudegateway` / Password : `claudegateway`

### Accès base de données H2 (dev uniquement)
- URL : http://localhost:8080/h2-console
- JDBC URL : `jdbc:h2:mem:claudegatewaydb`
- Utilisateur : `sa` / Mot de passe : (vide)

### Builder le backend sans tests
```bash
cd backend && ./mvnw clean package -DskipTests
```

### Builder le frontend
```bash
source ~/.nvm/nvm.sh && nvm use 22
cd frontend && npm run build
```

---

## Priorité

```
Cohérence architecture > nouveauté
Process > vitesse
Testabilité > complétude
Refuser explicitement > laisser passer silencieusement
```
