# AI_CONTEXT.md

## 1. Purpose
Ce document fournit aux assistants IA le contexte opérationnel nécessaire pour travailler efficacement sur Claude Gateway. Contrairement à `PROJECT.md`, qui définit la vision produit, `AI_CONTEXT.md` contient l'**état courant** du projet. Il est mis à jour tout au long de la vie du projet.

## 2. Product
- **Product Name** : Claude Gateway
- **Company** : NG IT Consulting
- **Current Version** : Version 1
- **Current Phase** : Foundation / Core Platform
- **Project Status** : Active Development

## 3. Product Vision
Claude Gateway est une plateforme professionnelle offrant un accès sécurisé à Claude. La plateforme est une **Gateway** : pas un modèle IA, pas un remplaçant de Claude, pas une plateforme RAG. Sa responsabilité est d'orchestrer les interactions entre utilisateurs et fournisseurs IA tout en offrant des services plateforme professionnels.

## 4. Current Technology Stack
- **Backend** : Java 21, Spring Boot, Maven
- **Frontend** : Angular
- **Database** : PostgreSQL
- **Infrastructure** : Docker, Kubernetes (Amazon EKS), Helm
- **Cloud** : AWS
- **Billing** : Stripe
- **AI Provider** : Anthropic Claude

## 5. Current Product Scope
**V1 inclut** : Authentication, User Management, Conversations, Chat, File Upload, Hosted Mode, BYOK, Billing, Administration, Monitoring.

**V1 exclut explicitement** : OCR, Textract, Embeddings, pgvector, RAG, Knowledge Bases, Document Libraries, Connectors.

## 6. Current Development Phase
Objectif actif : livrer une V1 production-ready reproduisant l'expérience Claude tout en ajoutant les capacités plateforme professionnelles. Aucune fonctionnalité V2 ne doit être implémentée sauf demande explicite.

## 7. Architectural Principles
- Le backend est une Gateway.
- Claude fournit l'intelligence ; Claude Gateway fournit l'orchestration.
- La simplicité est préférée.
- La modularité est obligatoire.
- Les services stateless sont préférés.
- L'architecture cloud-native est requise.

## 8. Reference Documents
`PROJECT.md`, `ARCHITECTURE.md`, `CODING_RULES.md`, `PROJECT_STRUCTURE.md`, `DEVELOPMENT_PLAN.md`, `ADR.md`, `API_DESIGN.md`.

## 9. Working Rules
Avant d'implémenter une fonctionnalité :
1. Lire `PROJECT.md`.
2. Vérifier la phase de développement courante.
3. Vérifier si Claude fournit déjà la capacité demandée.
4. Respecter les frontières de modules.
5. Produire du code production-ready.
6. Mettre à jour la documentation si nécessaire.

## 10. Long-Term Objective
Claude Gateway évoluera progressivement vers une AI Gateway indépendante du fournisseur, supportant plusieurs fournisseurs IA tout en préservant une expérience utilisateur professionnelle unique.
