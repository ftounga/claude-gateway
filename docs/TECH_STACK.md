# TECH_STACK.md

## 1. Purpose
Ce document explique pourquoi chaque technologie majeure a été retenue pour Claude Gateway. Les choix technologiques restent stables sauf raison architecturale forte de changement.

## Backend
**Java 21 / Spring Boot.** Raisons : écosystème mature ; fiabilité entreprise ; excellent support de test ; capacités cloud-native ; large communauté ; maintenabilité long terme.

## Frontend
**Angular.** Raisons : architecture orientée entreprise ; forte modularité ; maintenabilité long terme ; excellente intégration TypeScript ; structure de projet prévisible.

## Database
**PostgreSQL.** Raisons : transactions ACID ; écosystème mature ; excellent support cloud ; fortes capacités SQL ; extensibilité future.

## Containerization
**Docker.** Raisons : builds reproductibles ; cohérence des environnements ; compatibilité Kubernetes.

## Orchestration
**Amazon EKS.** Raisons : Kubernetes managé ; haute disponibilité ; intégration AWS native ; scaling automatique ; production readiness.

## Deployment
**Helm.** Raisons : déploiements répétables ; releases versionnées ; gestion de configuration simplifiée.

## Monitoring
**Prometheus + Grafana** *(cible V2+)*. Raisons : standard de l'industrie ; intégration Kubernetes ; visibilité opérationnelle.

> **V1 (décision 2026-07-01)** : réutilisation de l'observabilité **AWS CloudWatch** déjà déployée sur le cluster partagé `legalcase-shared` (health probes, métriques, alarmes). Prometheus/Grafana est la cible d'évolution.

## Logging
**Loki** *(cible V2+)*. Raisons : logging natif Kubernetes ; intégration simple ; requêtage efficace.

> **V1 (décision 2026-07-01)** : logs applicatifs via **Fluent Bit → CloudWatch Logs** (infra legalcase existante). Loki est la cible d'évolution.

## Billing
**Stripe.** Raisons : gestion d'abonnements ; paiements sécurisés ; support des webhooks ; API mature.

## AI Provider
**Anthropic Claude API.** Raisons : capacité métier principale ; raisonnement de haute qualité ; support documentaire natif ; grandes fenêtres de contexte.

## Guiding Principle
Les choix technologiques sont guidés par : simplicité, fiabilité, maintenabilité, production readiness, évolution long terme. De nouvelles technologies ne sont introduites que si elles améliorent clairement ces objectifs.
