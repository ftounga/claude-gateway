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

## ADR-011 — Extension du périmètre au traitement documentaire (supersède ADR-004)
**Status** : Accepted (2026-07-01) — **supersède ADR-004**

**Context** — ADR-004 excluait de la V1 l'OCR, le RAG, les embeddings et les bases vectorielles pour livrer vite une passerelle pure. La passerelle (F-01→F-12) étant livrée, le PO décide d'intégrer le traitement documentaire au produit.

**Decision** — Le traitement documentaire **entre dans le périmètre** via **F-05 (OCR Textract), F-06 (Ingestion RAG : chunking + embeddings + pgvector), F-07 (Q&A documenté), F-08 (Statut documents)**, plus **F-13 (Templates), F-14 (Export), F-15 (Embeddings locaux), F-16 (Rapports d'usage)**. Restent hors périmètre : V3 (F-17 équipes, F-18 on-prem).

**Contraintes conservées** — Gateway-First (le backend orchestre, ne devient pas un LLM) ; Provider Independence (interface `AIProvider`) ; isolation `user_id` ; traitements lourds asynchrones (workers) ; secrets hors code. pgvector et l'IRSA Textract, provisionnés puis laissés dormants, sont réactivés.

**Consequences** — Complexité et coûts en hausse (pgvector, Textract, workers async) assumés pour la valeur documentaire. `PROJECT.md` amendé en conséquence (§Amendement).

---

## ADR-012 — Claude Code Lite (« Atelier ») — offre Gold / premium
**Status** : Proposed (2026-07-10) — **amende le périmètre V3**

**Context** — Offrir un espace de travail hébergé « façon Claude Code » : l'utilisateur uploade un projet (`.zip`) + `CLAUDE.md` + skills, et fait travailler Claude dessus dans un **flux unique** (fidèle à Claude Code ; panneau Fichiers repliable en bonus). Objectif : différencier une offre **Gold / premium +++**. Principe directeur : **Gateway-First / Provider-First** — on **relaie** la capacité d'Anthropic, on ne construit ni moteur d'agent ni sandbox maison.

**Decision**
- **Périmètre** : Claude Code Lite entre comme **offre Gold distincte** (au-dessus de V1/V2). Restent hors scope : multi-LLM runtime, on-prem (F-18), espaces d'équipe (F-17).
- **Clé / fournisseur : les deux modes, BYOK par défaut sur l'Atelier.** L'utilisateur choisit : **BYOK** (sa clé Anthropic → il porte tokens **et** sandbox ; défaut recommandé) ou **Hosted** (clé plateforme, **compté + marqué + plafonné**). Réutilise `AIProvider` + BYOK chiffré KMS (existant).
- **Tarification (rentabilité garantie)** — 3 briques : (1) **Accès Gold** (abonnement fixe = marge fixe couvrant le socle) ; (2) **Conso Hosted** facturée ~**2× le coût Anthropic** (tokens) + **frais par run / minute de sandbox** ; (3) **BYOK** = accès Gold seul (coût conso nul). Garde-fous obligatoires : **plafond de dépense par user et par tâche**, ceilings tokens/minutes par run, imputation sur le quota F-10 + surcompteur sandbox.
- **Architecture** : workspace **par user + projet** en **S3 isolé** ; **Phase 1** = tool-runner backend d'**opérations fichiers** (`list/read/write/search`) via tool-use, **sans exécution**, UI flux unique ; **Phase 2** = relais des **Managed Agents / code execution d'Anthropic** (sandbox + boucle hébergés) via une abstraction `AgentProvider` (parallèle d'`AIProvider`).
- **Sécurité** : protection **zip-slip** + **zip-bomb** + plafonds taille/nb fichiers ; outils Phase 1 = fichiers only (pas de réseau/shell) ; sandbox Phase 2 = celui d'Anthropic ; clés BYOK chiffrées KMS ; isolation `user_id` stricte, workspaces éphémères.
- **Phasage** : **Phase 1 d'abord** (faisable, sûr, gros ROI) ; **Phase 2** conditionnée à la confirmation, côté compte Anthropic, de la disponibilité Managed Agents / code execution et de la **grille de coût sandbox**.

**Consequences** — Aligné Gateway-First (relais) ; BYOK et quota réutilisés. Variance de coût (agents) maîtrisée par plafonds + markup + BYOK. Dépendance API Anthropic assumée (Provider-First). À lever avant Phase 2 : accès Managed Agents + tarif sandbox. Grille exacte (accès Gold €, markup, prix sandbox) figée au branchement Stripe de l'offre Gold.

---

## ADR-013 — Claude Code Lite Phase 2 : exécution via Managed Agents d'Anthropic (précise ADR-012)

**Status** : Proposed (2026-07-11) — **précise la Phase 2 d'ADR-012**. Cadrage détaillé : `docs/features/F-28/PHASE-2-cadrage.md`.

**Context** — La Phase 1 (F-28, SF-28-01→07, livrée) offre un Atelier où Claude lit/édite les fichiers **sans exécution** (boucle tool-use orchestrée par notre backend). La Phase 2 doit apporter l'**exécution réelle** (bash, tests, build, exécution de code) pour faire de l'Atelier un véritable agent de développement (offre Gold premium +++). Deux verrous étaient ouverts dans ADR-012 : disponibilité Managed Agents et grille de coût sandbox.

**Verrous levés** — Managed Agents est **disponible en β sur l'API Anthropic first-party** (notre clé `ANTHROPIC_API_KEY`) et Claude Platform on AWS (❌ Bedrock/Vertex/Foundry — non pertinent). SDK Java officiel (`client.beta().agents()/environments()/sessions()/files()`). Pont fichiers natif (Files API + `resources` montés + `/mnt/session/outputs/`).

**Decision**
- **Relais, pas de sandbox maison** (Gateway-First/Provider-First) : la boucle d'agent ET le conteneur d'exécution sont **hébergés par Anthropic** (Managed Agents, environment `config.type: "cloud"`). On ne construit ni moteur d'agent ni sandbox.
- **Abstraction `AgentProvider`** (parallèle d'`AIProvider`) : impl. `AnthropicManagedAgentProvider` via le SDK Java beta (header `managed-agents-2026-04-01`). Le code métier de l'Atelier ne dépend jamais directement d'Anthropic.
- **Flux Agent→Session** respecté : **Environment** (template cloud, `networking: limited` + `allow_package_managers`) et **Agent** (config versionnée : model, system = `CLAUDE.md`+skills, tools exécution, skills) créés **une fois** au bootstrap ; une **Session éphémère** par tâche, référençant l'agent, avec le workspace S3 **monté** en `resources` et le resync des sorties (`/mnt/session/outputs/`) vers S3.
- **Streaming** : events de session (SSE Anthropic) relayés au frontend via le patron SSE existant (SF-28-05), avec **reconnexion consolidée** (pas de replay côté Anthropic).
- **Économie** : 3 briques (accès Gold fixe déjà livré ; Hosted = tokens ~2× coût **+ minutes de sandbox** ; BYOK = accès seul). **Garde-fous obligatoires** : plafonds dépense/utilisateur et /tâche, ceilings minutes+tokens/session, timeout dur, **surcompteur sandbox** distinct du quota tokens.
- **Sécurité** : sandbox Anthropic éphémère, `networking limited` (deny-by-default), isolation `user_id` (session créée pour un workspace `requireOwned`, fichiers du seul préfixe S3 du user), secrets via **Vaults** (jamais dans le conteneur), tokens Git via git-proxy.
- **Découpage** : SF-28-08 (`AgentProvider` + bootstrap env/agent) → SF-28-09 (cycle session + pont fichiers) → SF-28-10 (streaming events UI) → SF-28-11 (surcompteur sandbox + plafonds + billing — ZONE ARGENT) → SF-28-12 (bascule Phase 1↔2 par workspace, flag + Gold).

**Coût sandbox — CONFIRMÉ** — Grille officielle Managed Agents (pricing Anthropic, 2026-07) : **tokens au tarif standard** (Opus 4.8 = 5/25 $/M) **+ runtime de session à 0,08 $/heure**, facturé uniquement pendant `running` (idle non facturé), remplaçant la facturation conteneur du code-execution. Le runtime est **marginal** vs les tokens → offre Gold très rentable ; le surcompteur sandbox sert de garde-fou. **Décisions owner restantes (non bloquantes pour la fondation SF-28-08→10)** : valider le markup Hosted (~2×) + plafonds par défaut avant **SF-28-11**, et confirmer l'accès β Managed Agents **sur le compte mutualisé legalcase**.

**Consequences** — Réutilise BYOK/quota/S3/SSE existants ; l'abstraction `AgentProvider` isole le risque beta. Nouvelle dimension de coût (minutes sandbox) maîtrisée par plafonds + markup + BYOK par défaut. Dépendance API Anthropic assumée (Provider-First). Aucun dev Phase 2 ne démarre avant la levée du verrou coût sandbox.

---

## Maintaining ADRs
Chaque décision architecturale significative est documentée avant l'implémentation. Les décisions historiques ne sont jamais supprimées : de nouveaux ADR supersèdent les précédents tout en préservant l'historique du projet. La connaissance architecturale fait partie du logiciel lui-même.
