# AI_PROMPTS.md

## Purpose
Ce document contient des prompts standardisés pour le développement logiciel assisté par IA. Tous les prompts supposent que `PROJECT.md` est la source de vérité primaire.

## Prompt — Build a New Module
Lire `PROJECT.md`, `ARCHITECTURE.md` et `CODING_RULES.md`. Implémenter le module demandé en suivant l'architecture du projet. Produire du code production-ready. Inclure des tests unitaires. Documenter les décisions architecturales importantes. Ne pas implémenter de fonctionnalités hors de la phase de développement courante.

## Prompt — Implement a Feature
Lire `PROJECT.md` et `DEVELOPMENT_PLAN.md`. Implémenter uniquement la fonctionnalité demandée. Respecter la phase de développement courante. Éviter les dépendances inutiles. Mettre à jour la documentation si nécessaire.

## Prompt — Code Review
Revoir l'implémentation par rapport à `PROJECT.md`, `ARCHITECTURE.md`, `CODING_RULES.md`. Vérifier : cohérence architecturale, sécurité, maintenabilité, couverture de test, simplicité. Suggérer des améliorations quand c'est approprié.

## Prompt — Refactoring
Améliorer l'implémentation en préservant le comportement existant. Réduire la complexité. Améliorer la lisibilité. Respecter tous les principes architecturaux. Ne pas introduire d'abstractions inutiles.

## Prompt — Bug Fix
Identifier la cause racine. Expliquer le problème. Implémenter la plus petite correction sûre. Ajouter des tests de régression. Vérifier que le correctif respecte l'architecture du projet.

## Prompt — API Development
Lire `PROJECT.md` et `API_DESIGN.md`. Concevoir l'API selon les conventions du projet. Fournir : endpoint, requête, réponse, validation, gestion d'erreurs, tests, documentation.

## Prompt — Database Evolution
Lire `PROJECT.md` et `ADR.md`. Concevoir l'évolution de la base. Préserver la compatibilité ascendante quand c'est possible. Fournir des scripts de migration. Mettre à jour la documentation.

## Prompt — Production Readiness Review
Évaluer l'implémentation pour le déploiement production. Vérifier : sécurité, logging, monitoring, tests, documentation, performance, gestion d'erreurs. Lister toutes les améliorations restantes avant release.

## Prompt — Release Preparation
Revoir l'ensemble du projet. Confirmer que : la documentation est à jour ; les tests passent ; la CI réussit ; la version est cohérente ; les release notes sont prêtes. Le projet doit être considéré production-ready avant d'approuver la release.
