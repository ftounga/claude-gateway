# PROJECT_STRUCTURE.md

## 1. Purpose
Ce document définit l'organisation physique du code source de Claude Gateway. Objectif : garder le projet cohérent à mesure qu'il grandit. Chaque nouvelle fonctionnalité doit s'insérer naturellement dans la structure existante. La structure des répertoires fait partie de l'architecture.

## 2. Repository Structure
```
claude-gateway/
├── docs/
├── backend/
├── frontend/
├── deployment/
├── infrastructure/
├── scripts/
└── .github/
```
Chaque répertoire de premier niveau a une responsabilité unique.

## 3. Documentation
La documentation vit dans `docs/` :
```
docs/
  PROJECT.md
  ARCHITECTURE.md
  CODING_RULES.md
  PROJECT_STRUCTURE.md
  API.md
  DATABASE.md
  SECURITY.md
  DEPLOYMENT.md
  ROADMAP.md
  ADR.md
```
La documentation est versionnée et évolue avec le logiciel.

## 4. Backend Structure
Le backend contient toute la logique métier côté serveur :
```
backend/
  src/
    main/
      java/
        com.ngconsulting.claudegateway
```
La structure des packages Java reflète les **modules métier** plutôt que les couches techniques.

## 5. Backend Modules
Modules métier : `auth/`, `users/`, `conversation/`, `gateway/`, `billing/`, `subscription/`, `administration/`, `monitoring/`, `common/`.

Chaque module possède ses propres : controllers, services, modèles de domaine, repositories, DTOs, tests. Les capacités métier restent isolées.

## 6. Frontend Structure
Le frontend suit la même philosophie modulaire :
```
app/
  authentication/
  dashboard/
  chat/
  conversations/
  billing/
  settings/
  administration/
  shared/
```
Les composants partagés restent génériques ; les composants métier restent dans leur module.

## 7. Infrastructure
Le code d'infrastructure reste isolé :
```
deployment/
  helm/
  kubernetes/
  docker/
```
L'infrastructure ne doit jamais contenir de logique métier.

## 8. Configuration
La configuration est externalisée : ConfigMaps Kubernetes, Secrets, variables d'environnement. Jamais de configuration en dur.

## 9. Shared Code
Le code partagé reste minimal. Seule une fonctionnalité réellement réutilisable appartient aux modules `common`. Éviter les packages utilitaires génériques contenant des fonctions sans rapport.

## 10. Package Responsibilities
Chaque package répond à une question : « quelle capacité métier ce package implémente-t-il ? ». Éviter autant que possible les packages organisés autour de concepts purement techniques.

## 11. Feature Growth
Chaque nouvelle fonctionnalité s'intègre naturellement dans un module existant. Créer un nouveau module exige une justification architecturale. Objectif : éviter la fragmentation inutile.

## 12. Long-Term Stability
La structure du dépôt reste stable pendant toute la vie du projet. Les versions futures ajoutent des fonctionnalités sans réorganiser tout le code. Une structure stable améliore la maintenabilité, l'onboarding et le développement assisté par IA.
