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

## Bibliothèques ciblées (variantes de pile signalées)
- **ical4j 4.0.8** (licence BSD, F-243 / SF-243-01) : analyse des agendas iCal de Jarvis (récurrences `RRULE`/`EXDATE`,
  occurrences modifiées, fuseaux). Confinée à `fr.claudegateway.jarvis.calendar.JarvisIcsParser`. `groovy` et `jparsec`
  (filtres d'expressions inutilisés) sont exclus ; aucune mise à jour de fuseau par le réseau (`ical4j.properties`).
  Écrire un analyseur iCal maison aurait réinventé l'existant.
- **Apache POI `poi-scratchpad` 5.3.0** (licence Apache 2.0, F-249 / SF-249-01) : lecture des mails Outlook `.msg`
  (OLE2/MAPI, HSMF) déposés par le runner — en-têtes, texte, pièce `.ics`, demandes de réunion. **Variante de pile
  signalée** : aucune autre bibliothèque ne lit ce format ; la lecture reste côté serveur (runner léger, correctifs sans
  mise à jour des postes). Les `.eml` passent par `jakarta.mail`, déjà présent.
- **Python 3.12 + Pipecat** (licence BSD-2, F-257, ADR-021) : service vocal séparé `jarvis-voice` (flux audio
  permanent, VAD Silero, fin de tour smart-turn v3 sur CPU, session de transcription longue, préemption,
  interruption). **Variante de pile signalée et acceptée par le PO le 2026-11-11 (OQ-24)** : aucun cadre Java ne
  fournit l'orchestration du tour vocal ; le service ne fait que du signal, sans clé fournisseur ni état durable.
  Construit et déployé sur le modèle de `diagram-renderer` (Node + Python, F-142).

## Guiding Principle
Les choix technologiques sont guidés par : simplicité, fiabilité, maintenabilité, production readiness, évolution long terme. De nouvelles technologies ne sont introduites que si elles améliorent clairement ces objectifs.
