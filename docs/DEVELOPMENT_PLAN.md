# DEVELOPMENT_PLAN.md

## 1. Purpose
Ce document définit la séquence de développement officielle de Claude Gateway. Objectif : implémenter chaque fonctionnalité dans le bon ordre en minimisant le rework et la dérive architecturale. Chaque tâche de développement appartient à l'une des phases ci-dessous. L'IA ne saute jamais de phase sans approbation explicite.

## 2. Development Strategy
Développement incrémental. Chaque phase produit une version fonctionnelle, testable et déployable. Chaque phase s'appuie sur la précédente. Aucune phase n'introduit de dépendances inachevées sur des travaux futurs. La plateforme reste production-ready tout au long du cycle.

## 3. Phase 1 — Foundation
**Objectif** : créer la fondation technique.

**Livrables** : projet Spring Boot ; projet Angular ; Dockerfiles ; pipeline CI de base ; endpoint de santé ; squelette Helm chart ; connexion PostgreSQL ; gestion de configuration ; configuration du logging.

**Critères d'acceptation** : le backend démarre ; le frontend démarre ; les images Docker se construisent ; la CI passe ; le déploiement Kubernetes réussit.

## 4. Phase 2 — Authentication
**Objectif** : gestion de l'identité utilisateur.

**Livrables** : inscription, connexion, réinitialisation de mot de passe, vérification email, authentification JWT, profil utilisateur, gestion des rôles.

**Critères d'acceptation** : les utilisateurs s'authentifient de façon sécurisée et accèdent aux API protégées.

## 5. Phase 3 — Conversations
**Objectif** : gestion des conversations.

**Livrables** : créer / renommer / supprimer / archiver une conversation, historique, persistance des messages.

**Critères d'acceptation** : les utilisateurs gèrent plusieurs conversations indépendamment.

## 6. Phase 4 — Claude Gateway
**Objectif** : communication avec Anthropic.

**Livrables** : abstraction provider, implémentation Anthropic, endpoint de chat, réponses en streaming, sélection du modèle, transfert de fichiers, traduction des erreurs.

**Critères d'acceptation** : les utilisateurs interagissent avec Claude via la Gateway avec une expérience aussi proche que possible de l'interface Claude officielle.

## 7. Phase 5 — Hosted & BYOK
**Objectif** : supporter les clés gérées par la plateforme et par le client.

**Livrables** : mode Hosted, gestion BYOK, stockage sécurisé des clés API, validation des clés, sélection du provider à l'exécution.

**Critères d'acceptation** : les utilisateurs basculent librement entre Hosted et BYOK.

## 8. Phase 6 — Billing
**Objectif** : capacités commerciales.

**Livrables** : intégration Stripe, gestion des abonnements, gestion des essais, daily pass, webhooks de paiement, cycle de vie de l'abonnement.

**Critères d'acceptation** : les abonnements contrôlent automatiquement l'accès à la plateforme.

## 9. Phase 7 — Administration
**Objectif** : capacités de gestion opérationnelle.

**Livrables** : gestion des utilisateurs, vue d'ensemble des abonnements, tableau de bord plateforme, statistiques d'usage, audit logs.

**Critères d'acceptation** : les administrateurs gèrent la plateforme sans accès à la base de données.

## 10. Phase 8 — Monitoring
**Objectif** : préparer la production.

**Livrables** : métriques, health checks, logging, dashboards, alertes.

**Critères d'acceptation** : la santé de la plateforme est monitorable en temps réel.

## 11. Phase 9 — Production Readiness
**Objectif** : préparer la première release production.

**Livrables** : tests de performance, revue de sécurité, revue documentaire, stratégie de backup, validation de la reprise après sinistre, release checklist.

**Critères d'acceptation** : la plateforme est prête au déploiement production.

## 12. Version 2 Roadmap
La V2 introduit des capacités qui n'appartiennent pas à la V1 : bibliothèque documentaire persistante, base de connaissances, RAG, fallback OCR, recherche sémantique, connecteurs entreprise, recherche multi-documents, mémoire long terme. Ces fonctionnalités ne doivent jamais retarder la livraison de la V1.

## 13. AI Development Workflow
Avant toute implémentation, l'IA vérifie :
1. Quelle phase de développement est active ?
2. La fonctionnalité demandée appartient-elle à cette phase ?
3. La fonctionnalité existe-t-elle déjà dans Claude ?
4. L'implémentation est-elle compatible avec `PROJECT.md` ?
5. Toutes les dépendances des phases précédentes sont-elles complètes ?

## 14. Completion Criteria
Une phase est complète seulement quand : toutes les fonctionnalités prévues sont implémentées ; les tests automatisés passent ; la documentation est à jour ; les points de revue sont résolus ; le déploiement production réussit ; les critères d'acceptation sont satisfaits. Les phases incomplètes ne sont jamais considérées terminées.

> Le plan de développement garantit une livraison prévisible, incrémentale et production-ready de Claude Gateway.
