# PROJECT.md — Claude Gateway

**AI-Native Product Specification**

- Version : 1.0
- Status : Living Document
- Owner : NG IT Consulting

> `PROJECT.md` est la **source de vérité** du produit. Aucune implémentation ne doit le contredire.
> En cas de conflit entre un document et `PROJECT.md`, `PROJECT.md` prévaut.

---

## 1. Executive Summary

### 1.1 Vision
Claude Gateway est une plateforme SaaS permettant aux consultants et professionnels d'accéder aux capacités de Claude à travers une passerelle sécurisée, tout en ajoutant les fonctionnalités nécessaires à une utilisation professionnelle : authentification, gestion des abonnements, BYOK, administration, historique, supervision et gouvernance.

- Claude Gateway n'est **pas** un nouveau modèle d'intelligence artificielle.
- Claude Gateway n'est **pas** un concurrent de Claude.
- Claude Gateway est une **couche d'orchestration** située entre l'utilisateur et les API Anthropic.

Sa mission est de reproduire fidèlement l'expérience Claude tout en supprimant les contraintes techniques et organisationnelles rencontrées par les consultants en mission.

### 1.2 Le problème
Les consultants travaillent fréquemment dans des environnements où :
- Claude est bloqué par les politiques réseau ;
- les accès aux services IA publics sont restreints ;
- les utilisateurs doivent utiliser leur téléphone personnel ;
- les documents sont transférés manuellement entre plusieurs équipements ;
- les équipes perdent énormément de temps.

Le problème n'est pas la qualité de Claude. Le problème est **l'accès** à Claude. Claude Gateway résout précisément ce problème.

### 1.3 La solution
Claude Gateway agit comme un proxy intelligent. Le navigateur communique uniquement avec Claude Gateway. Claude Gateway communique ensuite avec les API Anthropic. L'utilisateur retrouve une expérience quasiment identique à Claude, mais avec des fonctionnalités supplémentaires destinées aux professionnels.

### 1.4 Philosophie fondamentale
Le principe le plus important de ce projet est le suivant :

> **Ne jamais développer une fonctionnalité déjà fournie par Claude, sauf si cela apporte une valeur métier clairement identifiée.**

Cette règle est prioritaire sur toutes les autres. Elle guide chaque décision technique, chaque évolution du produit, chaque développement réalisé par une IA.

### 1.5 Les objectifs de la Version 1
La Version 1 poursuit un objectif unique : **reproduire fidèlement l'expérience Claude**.

La V1 ne cherche pas à être plus intelligente, ni à remplacer Claude. Elle cherche uniquement à devenir la meilleure passerelle possible vers Claude.

Fonctionnalités attendues :
- authentification des utilisateurs ;
- gestion des conversations ;
- proxy des conversations vers Claude ;
- proxy des fichiers vers Claude ;
- gestion des abonnements ;
- mode Hosted ;
- mode BYOK ;
- historique des conversations ;
- tableau de bord utilisateur ;
- administration ;
- supervision ;
- journalisation ;
- facturation via Stripe.

### 1.6 Ce que la Version 1 ne doit jamais implémenter
Explicitement **exclus** de la Version 1 :
OCR ; AWS Textract ; moteur RAG ; embeddings ; pgvector ; indexation documentaire ; recherche vectorielle ; chunking ; pipeline documentaire ; mémoire permanente ; connecteurs Git ; connecteurs SharePoint ; connecteurs Confluence ; connecteurs Google Drive ; connecteurs OneDrive ; connecteurs Jira.

Ces fonctionnalités appartiennent exclusivement aux versions futures.

### 1.7 Vision long terme
- **Phase 1 — Claude Gateway** : une passerelle sécurisée vers Claude. Permettre aux consultants d'utiliser Claude dans leur environnement de travail avec une expérience fidèle à l'original.
- **Phase 2 — Smart Workspace** : mémoire documentaire, bibliothèque personnelle, recherche globale, gestion avancée des documents.
- **Phase 3 — Enterprise Gateway** : connecteurs professionnels (GitHub, GitLab, Confluence, SharePoint, Google Drive, OneDrive, S3, Jira). La plateforme devient un espace de travail intelligent.
- **Phase 4 — Universal LLM Gateway** : Claude Gateway devient indépendant du fournisseur (Anthropic Claude, OpenAI, Gemini, futurs fournisseurs). L'utilisateur choisit son fournisseur sans changer son interface. La Gateway devient le cœur de la plateforme.

### 1.8 Définition du succès
La Version 1 est réussie lorsqu'un consultant utilisant Claude Gateway peut **oublier qu'il n'utilise pas directement Claude**. L'expérience doit être aussi proche que possible de celle de Claude. Les fonctionnalités supplémentaires (authentification, facturation, BYOK, historique, administration) doivent enrichir l'expérience sans modifier le fonctionnement naturel de Claude.

---

## 2. AI Instructions
Instructions applicables à toute IA développant ce projet.

1. **Règle n°1** — Toujours considérer `PROJECT.md` comme la source de vérité. Aucune implémentation ne doit contredire ce document.
2. **Règle n°2** — Avant d'implémenter une fonctionnalité, toujours répondre à : *Claude fournit-il déjà cette fonctionnalité ?* Si OUI, la Gateway doit uniquement la relayer, pas la réimplémenter.
3. **Règle n°3** — Toujours privilégier la simplicité. La solution la plus simple respectant les objectifs est toujours la meilleure.
4. **Règle n°4** — Toute nouvelle fonctionnalité doit appartenir explicitement à la roadmap. Aucune fonctionnalité ajoutée « par anticipation ».
5. **Règle n°5** — Le backend est une Gateway. Il ne doit jamais devenir un clone de Claude.
6. **Règle n°6** — Le code doit toujours permettre l'arrivée future de plusieurs fournisseurs LLM sans réécriture majeure de l'architecture.

---

## 3. Product Philosophy

### 3.1 Mission
Permettre aux consultants de retrouver toute la puissance de Claude dans des environnements professionnels où son utilisation directe est difficile, tout en ajoutant les fonctionnalités nécessaires à une utilisation professionnelle. Le produit n'a pas vocation à remplacer Claude, ni à développer un meilleur LLM, ni à devenir une alternative à Anthropic. Le produit existe parce que Claude est excellent ; Claude Gateway le rend accessible dans davantage de contextes professionnels.

### 3.2 Product Identity
Claude Gateway est une **Gateway**. Le backend orchestre, sécurise, facture, journalise, supervise, et choisit éventuellement quel fournisseur appeler — mais il ne remplace jamais le fournisseur. Le projet ne doit jamais dériver vers une architecture où le backend devient lui-même un moteur d'IA. Cette séparation est fondamentale.

### 3.3 Build vs Buy
Avant chaque développement : *cette fonctionnalité existe-t-elle déjà chez le fournisseur IA ?* Si oui, elle doit être utilisée, pas redéveloppée.
- Claude sait déjà : analyser un PDF, analyser une image, comprendre un document Word, répondre à des questions sur ces documents, maintenir le contexte d'une conversation → **ne pas réimplémenter en V1**.
- Claude ne fournit pas : gestion des abonnements, BYOK, facturation Stripe, gestion multi-utilisateurs, tableaux de bord, administration → **relèvent de Claude Gateway**.

### 3.4 Simplicity First
Chaque nouvelle fonctionnalité augmente la complexité, le coût de maintenance, les coûts AWS, les risques de bugs et la difficulté des tests. Par défaut, retenir la solution la plus simple. Une architecture simple est préférée à une complexe. Un service existant est préféré à un développement spécifique. Une API officielle est préférée à une implémentation maison.

### 3.5 Evolution Strategy
Le produit évolue progressivement. Chaque version doit être exploitable en production. La V2 ne doit jamais être un prérequis pour la V1. La V3 ne doit jamais casser la V2. Chaque évolution est additive. Aucune fonctionnalité future ne doit empêcher la livraison rapide de la V1.

### 3.6 Gateway First
La Gateway reste le centre du système. Même avec plusieurs fournisseurs (Anthropic, OpenAI, Gemini, Mistral, futurs modèles), les applications clientes ne communiqueront jamais directement avec eux — uniquement avec Claude Gateway. Cette abstraction garantit une architecture stable, une migration facilitée, une gouvernance centralisée, une sécurité homogène et une expérience utilisateur identique.

---

## 4. Product Scope

### 4.1 Scope de la Version 1
La V1 reproduit fidèlement les fonctionnalités de Claude et ajoute uniquement ce qui est nécessaire à l'exploitation commerciale.

- **Authentification** : connexion, inscription, gestion du compte, réinitialisation du mot de passe.
- **Conversations** : création, historique, suppression, renommage, archivage.
- **Chat** : envoi de messages, réception des réponses, streaming, choix du modèle Claude lorsque disponible.
- **Fichiers** : téléversement, transmission au fournisseur, gestion des erreurs. Aucun traitement documentaire local.
- **BYOK** : ajout, modification, suppression, validation, chiffrement.
- **Hosted** : gestion des clés de la plateforme, sélection automatique, contrôle des quotas.
- **Billing** : Stripe, abonnements, facturation, essais gratuits, gestion des quotas.
- **Administration** : gestion des utilisateurs, consultation des abonnements, supervision, journalisation.

### 4.2 Hors périmètre Version 1
OCR ; Textract ; embeddings ; recherche vectorielle ; RAG ; chunking ; pipeline documentaire ; mémoire permanente ; bibliothèque documentaire ; connecteurs Git ; connecteurs SharePoint ; connecteurs Google Drive ; connecteurs Confluence ; connecteurs Jira ; analyse multi-documents ; cache sémantique ; optimisation automatique des coûts ; support multi-LLM.

Ces fonctionnalités appartiennent aux versions futures.

---

## 5. Personas

- **Persona 1 — Consultant indépendant** : travaille seul, intervient chez plusieurs clients, dispose parfois de sa propre licence Anthropic, utilise intensivement Claude, souhaite retrouver immédiatement son environnement. **Cible prioritaire de la V1.**
- **Persona 2 — Consultant en ESN ou cabinet** : intervient en équipe, souhaite partager des pratiques, utilise Claude quotidiennement, apprécie simplicité et rapidité.
- **Persona 3 — Architecte Cloud / DevOps** : utilise Claude pour Kubernetes, AWS, Terraform, Java, OpenShift, GitLab, Jenkins. Les réponses doivent être rapides, le contexte conservé.

---

## 6. Product Success Criteria
Une V1 est réussie lorsqu'un nouvel utilisateur peut : créer un compte ; choisir Hosted ou BYOK ; commencer une conversation ; envoyer un fichier ; recevoir une réponse de Claude ; retrouver son historique ; gérer son abonnement — **sans avoir l'impression d'utiliser autre chose que Claude**.

---

## 7. Decision Framework
Avant toute implémentation, chaque fonctionnalité est analysée selon les règles suivantes.

1. **Provider First** — Le fournisseur IA propose-t-il déjà cette fonctionnalité ? Si oui, l'utiliser, ne pas la réimplémenter (PDF, images, Q&A document, contexte).
2. **Gateway Value** — Si Claude ne la fournit pas : apporte-t-elle une valeur propre à la Gateway ? Oui : BYOK, Billing, Auth, Historique, Monitoring, Administration. Non : refaire un OCR ou un chat déjà disponibles.
3. **Lowest Complexity** — Choisir le moins de code, de composants, d'infrastructure, de maintenance. La simplicité est une exigence d'architecture.
4. **Future Compatible** — Rester compatible avec plusieurs fournisseurs IA, plusieurs modèles, plusieurs modes de facturation.
5. **Business Driven** — Une fonctionnalité n'est développée que si elle répond à un besoin utilisateur clairement identifié. Les développements « par anticipation » sont interdits.
6. **Cost Awareness** — Évaluer coût AWS, coût Anthropic, coût de maintenance, impact perf. La meilleure solution est souvent la plus rentable.
7. **Security by Default** — Moindre privilège, chiffrement, isolation des utilisateurs, journalisation, conformité RGPD.
8. **Modularity** — Chaque fonctionnalité développée indépendamment ; modules faiblement couplés ; une fonctionnalité supprimée ne casse pas l'application.
9. **Production First** — Toute fonctionnalité conçue pour la production. Les PoC ne sont pas intégrés au produit.

---

## 8. Product Modules
Modules indépendants, chacun avec une responsabilité unique.

- **Authentication** — identifier les utilisateurs : inscription, connexion, récupération de mot de passe, JWT, gestion des sessions. Ne connaît rien de Claude.
- **User** — profil, préférences, informations personnelles, langue, fuseau horaire.
- **Subscription** — abonnements, plans, essais gratuits, expiration, paiements.
- **Billing** — communication avec Stripe, webhooks, paiements, facturation.
- **Claude Gateway** *(cœur du produit)* — recevoir les requêtes, sélectionner la clé API, appeler Anthropic, transmettre les réponses, journaliser les appels, contrôler les quotas. Aucune logique métier liée aux utilisateurs.
- **Conversations** — historique, organisation, archivage, renommage, suppression.
- **Files** — recevoir les fichiers, les transmettre au fournisseur, conserver uniquement les métadonnées nécessaires. La V1 ne traite jamais le contenu des documents.
- **Administration** — gestion des utilisateurs, abonnements, consommation, supervision.
- **Monitoring** — collecter les métriques (nombre de requêtes, temps moyen de réponse, consommation des modèles, erreurs).
- **Notifications** — informer l'utilisateur (paiement, expiration, quota, erreur, maintenance).

---

## 9. AI Development Rules
Destinées exclusivement aux IA développant le projet.

1. Toujours lire `PROJECT.md` avant toute modification.
2. Ne jamais développer une fonctionnalité absente de la roadmap.
3. Ne jamais modifier la philosophie du projet.
4. En cas de doute, préférer la solution la plus simple.
5. Avant chaque développement, vérifier que Claude ne fournit pas déjà cette fonctionnalité.
6. Ne jamais ajouter de dépendance inutile.
7. Le backend doit rester une Gateway. Toujours.

---

## 10. North Star

### 10.1 Purpose
Chaque décision architecturale, fonctionnelle et business doit rapprocher le produit de sa direction. Cette direction est la North Star : la référence pour évaluer chaque évolution future. Si une décision n'en rapproche pas, elle ne devrait pas être implémentée.

### 10.2 North Star Statement
**Claude Gateway is the best professional way to access and use Claude.** Claude Gateway ne concurrence pas Claude ; il enrichit l'expérience professionnelle autour de Claude. Le produit existe pour supprimer les frictions opérationnelles, de sécurité et d'organisation rencontrées par les consultants tout en préservant l'expérience Claude native.

### 10.3 Product Identity
Claude Gateway **est** : une Gateway professionnelle, une plateforme d'orchestration sécurisée, une plateforme SaaS, une couche de gouvernance, une plateforme de facturation, un espace de travail professionnel construit autour de Claude.

Claude Gateway **n'est pas** : un LLM, un concurrent de Claude, un chatbot from scratch, un moteur d'IA custom, une plateforme RAG, un moteur OCR, une plateforme de traitement documentaire.

Cette identité ne doit jamais changer.

### 10.4 Primary Objective
Permettre aux utilisateurs de travailler avec Claude exactement comme sur Claude, tout en bénéficiant des fonctionnalités professionnelles de la plateforme. La Gateway doit devenir invisible. Claude reste l'intelligence ; Claude Gateway gère tout ce qui l'entoure.

### 10.5 Secondary Objectives
Après l'expérience utilisateur, chaque évolution devrait améliorer : sécurité, fiabilité, simplicité, performance, scalabilité, maintenabilité, efficience des coûts, observabilité. Aucune fonctionnalité ne doit réduire ces qualités.

### 10.6 Long-Term Vision
Claude Gateway démarre comme une Gateway dédiée à Claude et ambitionne de devenir une plateforme AI Gateway professionnelle capable d'orchestrer plusieurs fournisseurs tout en préservant une expérience utilisateur unique et cohérente. L'expérience frontend reste inchangée quel que soit le fournisseur sous-jacent.

### 10.7 Anti-Goals
Claude Gateway ne doit jamais devenir : un remplaçant de Claude, un LLM custom, un moteur de recherche documentaire, une plateforme RAG générique, une plateforme OCR, un IDE, une plateforme low-code, un moteur d'automatisation de workflow.

### 10.8 Definition of Success
Claude Gateway est un succès lorsque : un consultant préfère l'utiliser plutôt que d'accéder directement à Claude ; les capacités plateforme apportent une valeur mesurable sans altérer l'expérience Claude native ; la Gateway reste légère, modulaire et facile à maintenir ; de nouveaux fournisseurs IA s'intègrent sans re-conception ; chaque nouvelle fonctionnalité renforce la plateforme plutôt que de dupliquer Claude.

> **Principe directeur : Claude est responsable de l'intelligence. Claude Gateway est responsable de tout ce qui l'entoure.**

---

## 11. Functional Specification

### 11.1 Overview
La V1 reproduit fidèlement l'expérience Claude en ajoutant les capacités plateforme requises. Chaque fonctionnalité V1 satisfait l'une des conditions : (a) elle existe dans Claude et doit être proxifiée de façon transparente ; (b) elle n'existe pas dans Claude mais est requise pour opérer la plateforme (auth, billing, administration, monitoring…). Aucune autre fonctionnalité ne doit être développée en V1.

### 11.2 User Authentication
La plateforme permet de : créer un compte ; se connecter de façon sécurisée ; réinitialiser son mot de passe ; vérifier son adresse email ; gérer son profil ; se déconnecter de toutes les sessions actives. L'authentification est entièrement gérée par Claude Gateway. Les comptes Anthropic ne sont jamais exposés aux utilisateurs finaux.

### 11.3 User Profile
Chaque utilisateur possède un profil : informations personnelles, détails d'abonnement, usage courant, langue préférée, fuseau horaire, mode fournisseur (Hosted ou BYOK). Le profil est indépendant de tout fournisseur IA.

### 11.4 Conversations
Nombre illimité de conversations selon l'abonnement. Chaque conversation contient : titre, date de création, dernière activité, modèle sélectionné, historique complet des messages. L'utilisateur peut créer, renommer, archiver, supprimer, rechercher et reprendre ses conversations. La gestion est entièrement assurée par Claude Gateway.

### 11.5 Chat Experience
L'interface de chat doit se rapprocher au maximum de Claude. La V1 supporte : réponses en streaming, rendu Markdown, blocs de code, tableaux, images (si supportées par le fournisseur), contexte de conversation, continuation, sélection du modèle lorsque disponible. Le frontend ne doit pas exposer de détails d'implémentation spécifiques au fournisseur.

### 11.6 File Upload
L'utilisateur peut téléverser tout type de fichier officiellement supporté par Claude (PDF, Word, texte, images). La Gateway est responsable uniquement de : recevoir l'upload, valider la requête, appliquer les limites d'abonnement, transmettre le fichier au fournisseur, retourner la réponse du fournisseur.

> La V1 n'analyse **jamais** le contenu des documents. Jamais d'OCR, jamais d'embeddings, jamais d'indexation.

### 11.7 Hosted Mode
Le mode Hosted permet de consommer Claude via les identifiants API de la plateforme. La Gateway sélectionne la clé plateforme, applique les quotas, mesure la consommation, applique les limites d'abonnement, enregistre les statistiques d'usage. Les utilisateurs n'ont jamais accès aux identifiants plateforme.

### 11.8 BYOK Mode
Le mode BYOK permet de connecter sa clé API Anthropic personnelle, stockée de façon sécurisée. À l'exécution : la Gateway récupère la clé chiffrée, l'utilise pour la requête, ne l'expose jamais au navigateur, ne journalise jamais la clé en clair. La facturation de la consommation IA reste sur le compte Anthropic de l'utilisateur ; Claude Gateway ne facture que l'abonnement plateforme.

### 11.9 Subscription Management
Plusieurs plans d'abonnement. Chaque abonnement définit : fonctionnalités disponibles, usage mensuel maximal, limites de stockage, fournisseurs disponibles, capacités premium. Les changements deviennent effectifs immédiatement après confirmation de paiement.

### 11.10 Billing
Facturation via Stripe : abonnements mensuels, daily passes, essais gratuits, upgrades, downgrades, annulation, historique de paiement. Claude Gateway ne stocke jamais d'informations de carte bancaire.

### 11.11 Administration
Les administrateurs peuvent : voir les utilisateurs, les abonnements, les statistiques ; suspendre / réactiver des comptes ; consulter la santé de la plateforme ; monitorer l'usage API ; voir les événements de facturation. Les capacités d'administration restent isolées des fonctionnalités clientes.

### 11.12 Monitoring
La plateforme enregistre en continu des métriques opérationnelles : total des requêtes, utilisateurs actifs, latence fournisseur, taux d'erreur, consommation de tokens, coûts quotidiens, distribution des abonnements, santé de l'infrastructure. Ces données servent uniquement à l'exploitation et ne doivent jamais interférer avec les requêtes clientes.

### 11.13 Error Handling
La Gateway présente des erreurs claires et cohérentes quel que soit le fournisseur. Les utilisateurs ne reçoivent jamais d'exceptions brutes du fournisseur. Les erreurs sont traduites en messages métier utiles, avec suggestions de récupération quand c'est possible.

### 11.14 Logging
La plateforme enregistre les événements nécessaires au dépannage, à l'audit, à la vérification de facturation et aux investigations de sécurité. Ne doivent **jamais** apparaître dans les logs : clés API, mots de passe, tokens, contenu de documents, informations personnelles au-delà du strict nécessaire.

### 11.15 Functional Boundaries
La V1 exclut intentionnellement toute fonctionnalité nécessitant du traitement documentaire : OCR, Textract, embeddings, chunking, recherche vectorielle, recherche sémantique, bases de connaissances, mémoire permanente, RAG, bibliothèques documentaires. Maintenir cette frontière est une décision architecturale fondamentale.

---

## 12. Non-Functional Requirements
Les fonctionnalités définissent *ce que* fait Claude Gateway ; les NFR définissent *à quel point* la plateforme doit bien fonctionner. Obligatoires pour chaque module.

- **12.1 Performance** — Expérience réactive. Auth en quelques secondes sous charge normale ; le chat commence à streamer dès que le fournisseur renvoie des données ; les uploads démarrent immédiatement après validation ; l'UI reste réactive. La Gateway ajoute le moins de latence possible : c'est une couche d'orchestration, pas un moteur de traitement.
- **12.2 Availability** — SaaS en ligne, haute disponibilité : redémarrage auto des pods, health probes, déploiements gracieux, rolling updates, zéro intervention manuelle si possible. Aucune requête ne dépend d'une instance unique.
- **12.3 Scalability** — Croissance sans re-conception, scaling principalement horizontal, application stateless autant que possible. L'information persistante vit dans des services managés (PostgreSQL, object storage, fournisseurs externes). Instances interchangeables.
- **12.4 Maintainability** — Chaque module reste compréhensible, testable et modifiable indépendamment. Complexité minimisée. Code lisible préféré au code astucieux.
- **12.5 Modularity** — Chaque module possède une responsabilité métier unique, communique via des interfaces claires. Aucune duplication de logique. Auth ne facture jamais ; billing n'authentifie jamais ; la gestion des conversations ne communique jamais directement avec l'infrastructure.
- **12.6 Security** — Fonctionnalité cœur : moindre privilège, secure by default, chiffrement par défaut, autorisation explicite, gestion sécurisée des secrets, auditabilité complète. Jamais optionnelle.
- **12.7 Privacy** — Les données appartiennent à l'utilisateur. Support de l'export, de la suppression de compte, des politiques de rétention, du consentement. Minimisation des données personnelles.
- **12.8 Reliability** — Gestion gracieuse des échecs (timeout fournisseur, fournisseur indisponible, clé API invalide, abonnement expiré, coupure réseau). Récupération sans intervention utilisateur quand c'est possible.
- **12.9 Observability** — Chaque composant de production expose métriques, logs et santé permettant un diagnostic rapide. Obligatoire pour la production readiness.
- **12.10 Cost Efficiency** — Chaque décision considère le coût opérationnel. La solution préférée offre le meilleur équilibre simplicité / maintenabilité / scalabilité / coût. Infrastructure proportionnelle à l'usage réel.
- **12.11 Extensibility** — La V1 évite les décisions empêchant l'évolution future (multi-fournisseurs, auth entreprise, bibliothèques documentaires, bases de connaissances, gestion d'organisation). L'architecture évolue par extension plutôt que par remplacement.
- **12.12 Production Readiness** — Une fonctionnalité est complète seulement si : implémentation faite, tests automatisés, logging, gestion d'erreurs, exigences de sécurité respectées, documentation à jour, monitoring disponible, prête au déploiement production. Le code qui ne marche qu'en dev n'est pas complet.
- **12.13 Simplicity Requirement** — La simplicité est une exigence de premier ordre : préférer l'implémentation qui introduit moins de dépendances, requiert moins de composants d'infrastructure, est plus facile à comprendre et maintenir, et réduit le risque opérationnel. La sophistication technique n'est jamais un objectif en soi.

---

## 13. Technical Architecture

### 13.1 Architectural Philosophy
Architecture modulaire, cloud-native. Chaque composant a une responsabilité unique et existe parce qu'il apporte une valeur métier mesurable. La plateforme doit rester compréhensible par un seul développeur tout en supportant des charges de production. La complexité n'est introduite que si justifiée par un besoin produit clair.

### 13.2 High-Level Architecture
Cinq couches logiques :

```
+------------------------------------------------------+
|                     Web Browser                      |
+------------------------------------------------------+
                      │
                      ▼
+------------------------------------------------------+
|                Angular Frontend                      |
+------------------------------------------------------+
                      │
                      ▼
+------------------------------------------------------+
|             Spring Boot API Gateway                  |
+------------------------------------------------------+
                      │
      ┌───────────────┼────────────────┐
      ▼               ▼                ▼
 Anthropic API   PostgreSQL      Stripe API
                      │
                      ▼
             Monitoring & Logging
```

Le frontend ne communique jamais directement avec Anthropic et ne stocke jamais d'identifiants fournisseur. Toute communication passe par Claude Gateway.

### 13.3 Frontend
Responsable uniquement de la présentation : UI, écrans d'authentification, interface de chat, gestion des conversations, uploads, pages d'abonnement, profil, interface d'administration. Jamais de logique métier ; jamais de communication directe avec un fournisseur IA externe.

### 13.4 Backend
Cœur de la plateforme : authentification, autorisation, orchestration API, billing, validation d'abonnement, persistance des conversations, gestion des utilisateurs, monitoring, audit logging. Le backend n'est **pas** responsable de générer les réponses IA (elles appartiennent au fournisseur).

### 13.5 AI Provider Layer
La couche fournisseur abstrait la communication avec les services IA externes. La V1 supporte Anthropic Claude. Les versions futures pourront supporter d'autres fournisseurs sans changer le frontend. Chaque implémentation expose la même interface interne :

```
AIProvider
├── AnthropicProvider
├── OpenAIProvider
├── GeminiProvider
└── FutureProvider
```

Les services métier dépendent uniquement de l'interface abstraite, jamais d'une implémentation spécifique à Anthropic.

### 13.6 Database
PostgreSQL stocke uniquement des informations plateforme : utilisateurs, conversations, messages, abonnements, clés API, audit logs, statistiques d'usage. La V1 évite délibérément les bases vectorielles ; **elle ne requiert pas pgvector** et ne persiste pas d'embeddings.

### 13.7 Storage
La V1 minimise le stockage persistant. Les fichiers peuvent être stockés temporairement lorsque nécessaire pour relayer les requêtes au fournisseur. La gestion documentaire persistante est hors périmètre V1 (bibliothèques documentaires → V2).

### 13.8 External Services
La V1 intègre uniquement les services requis : Anthropic, Stripe, PostgreSQL, Kubernetes, stack de monitoring. Aucune infrastructure supplémentaire sans justification métier claire.

### 13.9 Stateless Design
Instances stateless ; l'état métier appartient aux services persistants. Permet scaling horizontal, rolling deployments, haute disponibilité, récupération simplifiée. Le stockage local applicatif ne doit jamais être une dépendance.

### 13.10 Infrastructure
Déploiement sur Kubernetes, conteneurs immuables, configuration externalisée, secrets gérés de façon sécurisée, infrastructure reproductible. Configuration manuelle de production à éviter.

### 13.11 Deployment Philosophy
Déploiements répétables, automatisés, réversibles, observables. Exécutables via CI/CD. Les déploiements manuels sont exceptionnels.

### 13.12 Logging Architecture
Chaque requête traversant la Gateway doit être traçable. Les logs permettent de diagnostiquer les échecs, comprendre les flux, investiguer les incidents, supporter les clients. Aucune information sensible dans les logs.

### 13.13 Monitoring Architecture
Le monitoring fait partie du produit : santé, métriques de performance, usage des ressources, taux d'erreur, disponibilité fournisseur, métriques métier. La visibilité opérationnelle est obligatoire.

### 13.14 Architectural Constraints
Le backend ne doit **jamais** : devenir un moteur d'IA ; implémenter son propre LLM ; dupliquer les capacités du fournisseur ; réaliser d'indexation documentaire sémantique en V1 ; introduire de l'infrastructure inutile. Chaque décision renforce la philosophie Gateway.

### 13.15 Future Evolution
L'architecture est conçue pour une évolution progressive. Les versions futures pourront introduire : multi-fournisseurs, auth entreprise, gestion d'organisation, bibliothèques documentaires, bases de connaissances, RAG, connecteurs, gouvernance avancée — en **étendant** les modules existants plutôt qu'en re-concevant la plateforme. La stabilité architecturale est un objectif long terme.

---

## 14. Engineering Principles

- **14.2 Simplicity First** — Objectif d'ingénierie primaire. À implémentations valides égales, choisir la plus simple. Toute complexité justifiée par une valeur métier mesurable. La maintenabilité future prime sur la sophistication technique.
- **14.3 Single Responsibility** — Chaque composant a une responsabilité unique (Auth authentifie ; Billing gère les abonnements ; Conversations gèrent les conversations ; Gateway communique avec les fournisseurs ; Monitoring collecte les métriques). Jamais de chevauchement.
- **14.4 Separation of Concerns** — La logique métier reste indépendante de l'infrastructure. Les controllers ne contiennent pas de règles métier ; les repositories pas de logique applicative ; le code d'infrastructure ne prend pas de décisions métier.
- **14.5 Dependency Direction** — Les modules métier de haut niveau ne dépendent jamais directement de l'infrastructure. Les services dépendent d'interfaces ; l'infrastructure fournit les implémentations.
- **14.6 Provider Independence** — Le code métier ne dépend jamais directement d'Anthropic mais d'une interface AIProvider abstraite. Remplacer un fournisseur ne doit exiger qu'un minimum de changements.
- **14.7 Cloud Native** — Conçu pour Kubernetes : conteneurs jetables, instances redémarrables à tout moment, scaling horizontal, configuration externe. Aucune dépendance à l'état local de la machine.
- **14.8 Stateless Services** — Services applicatifs stateless ; l'information persistante uniquement dans des systèmes de stockage dédiés.
- **14.9 API First** — Chaque capacité exposée existe d'abord comme une API bien conçue. Le frontend consomme les mêmes API que de futurs clients. La logique métier n'existe jamais uniquement dans l'UI.
- **14.10 Security First** — La sécurité fait partie de l'implémentation : auth, autorisation, validation, gestion d'erreurs sécurisée, audit logging. Une fonctionnalité qui contourne la sécurité est incomplète.
- **14.11 Testability** — Chaque composant métier testable en isolation. Le test valide le comportement métier plutôt que les détails d'implémentation.
- **14.12 Observability** — Chaque fonctionnalité de production expose assez d'information pour comprendre son comportement. Logging et métriques sont obligatoires.
- **14.13 Fail Gracefully** — Les échecs sont attendus. Détection rapide, messages d'erreur utiles, préservation du travail utilisateur, retry des échecs transitoires, pas d'exposition de détails internes.
- **14.14 Backward Compatibility** — Préserver la compatibilité quand c'est possible. Les breaking changes restent exceptionnels ; les migrations de données sont gérées avec soin ; les API publiques évoluent de façon prévisible.
- **14.15 Documentation Driven Development** — La documentation fait partie du logiciel. Chaque décision architecturale significative est reflétée dans la documentation. `PROJECT.md` reste la source de vérité primaire.
- **14.16 Engineering Philosophy** — Optimiser pour clarté, maintenabilité, extensibilité, fiabilité, simplicité opérationnelle. La vitesse de développement ne doit jamais compromettre la qualité long terme. Chaque implémentation doit faciliter la suivante.

---

## 15. AI Development Guidelines

- **15.2 PROJECT.md Is The Source of Truth** — En cas de conflit apparent, `PROJECT.md` prévaut toujours.
- **15.3 Think Before Coding** — Avant de coder, répondre : quel problème métier ? déjà fourni par le fournisseur ? appartient à la version courante ? quel module en est responsable ? peut-on faire plus simple ?
- **15.4 Prefer Existing Capabilities** — Intégrer les capacités du fournisseur plutôt que les reconstruire. Éviter implémentations dupliquées, abstractions inutiles, reconstruction de fonctionnalités fournisseur.
- **15.5 Respect Module Boundaries** — Chaque implémentation appartient à un module. Si du code semble appartenir à plusieurs modules, reconsidérer l'architecture avant d'implémenter.
- **15.6 Minimize Dependencies** — Avant d'ajouter une librairie : réellement requise ? non implémentable avec l'existant ? activement maintenue ? alignée avec l'architecture ?
- **15.7 Production-Oriented Development** — Le code atteint la production. Raccourcis découragés ; implémentations temporaires clairement identifiées.
- **15.8 Favor Readability** — Code lisible préféré au code astucieux.
- **15.9 Avoid Premature Optimization** — Optimiser seulement après identification d'une limite réelle.
- **15.10 Continuous Consistency** — Vérifier en continu : cohérence des noms, cohésion des modules, exactitude de la documentation, alignement avec `PROJECT.md`.
- **15.11 Future Compatibility** — Rester conscient des évolutions futures sans complexifier inutilement la V1.
- **15.12 AI Decision Process** — Séquence mentale : Business Need → Product Scope → Architecture → Module Responsibility → Security → Testing → Implementation → Documentation → Validation. Sauter une étape augmente le risque de dérive architecturale.
- **15.13 Definition of Good AI Collaboration** — Réussie quand l'IA respecte la vision, évite la complexité inutile, propose des améliorations sans violer la philosophie, explique les décisions architecturales importantes, et produit du code production-ready. L'objectif est la qualité produit long terme, pas la quantité de code généré.
