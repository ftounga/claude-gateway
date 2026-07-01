# ARCHITECTURE.md

## 1. Architecture Philosophy

### 1.1 Purpose
Ce document définit les principes architecturaux de Claude Gateway. Il ne décrit pas les détails d'implémentation ; il définit comment le système doit être conçu, comment les modules interagissent et comment les évolutions futures doivent être intégrées. En cas de choix d'implémentation possibles, ce document prévaut (sous réserve de `PROJECT.md`).

### 1.2 Architectural Vision
Claude Gateway est une plateforme SaaS cloud-native. Sa responsabilité première est d'orchestrer les interactions entre utilisateurs et fournisseurs IA tout en offrant des capacités plateforme professionnelles. L'architecture est intentionnellement modulaire : chaque module possède une responsabilité métier unique et peut évoluer indépendamment. Elle doit rester compréhensible, maintenable et extensible.

### 1.3 Architectural Objectives
1. **Simplicity** — l'architecture la plus simple satisfaisant les exigences est toujours préférée.
2. **Maintainability** — chaque module reste compréhensible par un nouveau développeur.
3. **Extensibility** — fournisseurs et capacités futurs ajoutés par extension plutôt que re-conception.
4. **Reliability** — chaque composant de production tolère gracieusement les échecs.
5. **Scalability** — l'infrastructure scale horizontalement sans changement architectural.

### 1.4 Architectural Principles (obligatoires)
- **Principle 1 — Gateway First** : Claude Gateway est toujours l'unique point d'entrée. Les clients ne communiquent jamais directement avec les fournisseurs IA. Chaque requête traverse la Gateway, responsable de l'auth, de l'autorisation, du billing, du monitoring et de l'orchestration.
- **Principle 2 — Provider Independence** : les modules métier ne dépendent jamais directement d'Anthropic. Tous les fournisseurs implémentent une interface commune :

  ```
  AIProvider
      ├── AnthropicProvider
      ├── OpenAIProvider
      ├── GeminiProvider
      └── FutureProvider
  ```

  Remplacer un fournisseur ne doit exiger qu'un minimum de changements hors de son implémentation.
- **Principle 3 — Single Responsibility** : chaque module possède exactement une responsabilité métier (Auth authentifie ; Billing facture ; Conversation gère les conversations ; Gateway communique avec les fournisseurs ; Monitoring collecte les métriques). Jamais de chevauchement.
- **Principle 4 — Stateless Services** : services stateless autant que possible ; l'état métier appartient au stockage persistant. Simplifie scaling, déploiements et récupération.
- **Principle 5 — API First** : chaque capacité métier est exposée via des API bien définies. Le frontend consomme les mêmes API que de futurs clients. Les règles métier n'existent jamais uniquement dans le frontend.
- **Principle 6 — Security by Design** : la sécurité est intégrée à l'architecture, jamais ajoutée après. Auth, autorisation, chiffrement, audit et gestion des secrets sont des préoccupations architecturales obligatoires.
- **Principle 7 — Cloud Native** : conçu pour Kubernetes. Instances jetables, configuration externalisée, infrastructure reproductible, déploiement automatisé.
- **Principle 8 — Loose Coupling** : les modules communiquent via des contrats explicites ; les implémentations internes restent cachées. Changer un module a un impact minimal sur les autres.
- **Principle 9 — High Cohesion** : chaque module regroupe des responsabilités étroitement liées ; jamais une collection de fonctionnalités sans rapport.
- **Principle 10 — Replaceability** : chaque composant d'infrastructure est remplaçable (PostgreSQL, Anthropic, Stripe, Kubernetes) avec un impact minimal sur la logique métier. L'infrastructure est un détail d'implémentation ; la logique métier ne l'est pas.

### 1.5 Architectural Layers
```
Presentation Layer
    ↓
Application Layer
    ↓
Domain Layer
    ↓
Infrastructure Layer
    ↓
External Services
```
Chaque couche a des responsabilités clairement définies. Les dépendances pointent toujours vers le bas. Les couches basses ne dépendent jamais des couches hautes.

### 1.6 Design Philosophy
L'architecture optimise l'évolution long terme plutôt que la commodité court terme. En cas d'arbitrage entre simplicité et sophistication inutile, la simplicité gagne. L'architecture reste stable pendant que les fonctionnalités évoluent : le produit grandit par de nouveaux modules plutôt que par re-conception.

> Ce document définit la fondation architecturale sur laquelle chaque future version de Claude Gateway sera construite.
