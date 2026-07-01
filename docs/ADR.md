# ADR.md — Architecture Decision Records

Ce document enregistre les décisions architecturales majeures de Claude Gateway. Chaque ADR explique le contexte, la décision, la justification et les conséquences. Toute évolution architecturale future met à jour ce document plutôt que de changer silencieusement la direction du projet.

---

## ADR-001 — Gateway Architecture
**Status** : Accepted

**Context** — L'objectif est de fournir un accès professionnel à Claude tout en ajoutant des capacités plateforme. Deux approches étaient envisagées : (1) construire une nouvelle plateforme IA ; (2) construire une Gateway devant des fournisseurs IA existants.

**Decision** — Claude Gateway est implémenté comme une Gateway. Il ne remplace pas les fournisseurs IA ; il les orchestre.

**Consequences** — La logique métier reste séparée des capacités IA. Les futurs fournisseurs s'intègrent sans re-concevoir la plateforme.

---

## ADR-002 — Provider Abstraction
**Status** : Accepted

**Context** — Les versions futures supporteront plusieurs fournisseurs IA. Une dépendance directe à Anthropic rendrait l'évolution difficile.

**Decision** — Chaque fournisseur IA implémente une interface commune. Les modules métier ne communiquent qu'avec cette abstraction.

**Consequences** — L'ajout de nouveaux fournisseurs devient simple. La logique métier reste indépendante du fournisseur.

---

## ADR-003 — Cloud-Native Architecture
**Status** : Accepted

**Context** — La plateforme est destinée à tourner sur Kubernetes.

**Decision** — Tous les services sont conçus cloud-native. Les instances restent stateless. La configuration est externalisée.

**Consequences** — Scaling simplifié, résilience améliorée, déploiements automatisés.

---

## ADR-004 — Version 1 Scope
**Status** : Accepted

**Context** — Plusieurs capacités IA avancées étaient initialement envisagées pour la V1 : OCR, Textract, RAG, embeddings, bases vectorielles.

**Decision** — La V1 se concentre exclusivement sur la reproduction de l'expérience Claude tout en ajoutant les capacités plateforme. Les fonctionnalités IA avancées sont reportées.

**Consequences** — Complexité réduite, livraison plus rapide, coûts opérationnels plus bas, maintenance simplifiée.

---

## ADR-005 — Hosted and BYOK
**Status** : Accepted

**Context** — Les utilisateurs ont des préférences de facturation différentes. Certains préfèrent leur propre compte Anthropic ; d'autres un abonnement plateforme tout compris.

**Decision** — Supporter les modes Hosted et BYOK.

**Consequences** — Flexibilité accrue, adoption élargie, meilleur positionnement commercial.

---

## ADR-006 — Modular Architecture
**Status** : Accepted

**Context** — La plateforme évoluera sur plusieurs années. De gros modules monolithiques deviendraient difficiles à maintenir.

**Decision** — La plateforme est divisée en modules métier indépendants, chacun avec une responsabilité unique.

**Consequences** — Maintenabilité améliorée, tests simplifiés, évolution indépendante.

---

## ADR-007 — PostgreSQL
**Status** : Accepted

**Context** — La V1 requiert une persistance transactionnelle.

**Decision** — Utiliser PostgreSQL comme base relationnelle principale.

**Consequences** — Persistance fiable, garanties transactionnelles fortes, compatibilité avec un service managé cloud-native.

---

## ADR-008 — Angular Frontend
**Status** : Accepted

**Context** — Le frontend doit supporter une application d'entreprise à longue durée de vie.

**Decision** — Angular est sélectionné comme framework frontend.

**Consequences** — Forte modularité, outillage orienté entreprise, maintenabilité long terme.

---

## ADR-009 — Spring Boot Backend
**Status** : Accepted

**Context** — Le backend requiert un framework d'entreprise mature.

**Decision** — Spring Boot est sélectionné.

**Consequences** — Large écosystème, excellent support cloud-native, fortes capacités de test.

---

## ADR-010 — Documentation as Code
**Status** : Accepted

**Context** — Claude Gateway est intentionnellement développé avec assistance IA. L'IA requiert une documentation stable et structurée.

**Decision** — La documentation fait partie de l'architecture logicielle. `PROJECT.md` est la source de vérité primaire ; les documents de support définissent les détails d'implémentation.

**Consequences** — Meilleure collaboration IA, onboarding amélioré, dérive architecturale réduite, évolution cohérente long terme.

---

## Maintaining ADRs
Chaque décision architecturale significative est documentée avant l'implémentation. Les décisions historiques ne sont jamais supprimées : de nouveaux ADR supersèdent les précédents tout en préservant l'historique du projet. La connaissance architecturale fait partie du logiciel lui-même.
