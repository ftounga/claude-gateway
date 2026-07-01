# ARCHITECTURE_CANONIQUE.md
claude-gateway — Architecture produit et technique de référence

> ⚠️ **DOCUMENT SUBORDONNÉ À `docs/PROJECT.md`** (source de vérité produit, 2026-07-01).
> Ce fichier est **antérieur** à `PROJECT.md`. Là où il décrit le **périmètre V1**, il est **obsolète** :
> **OCR (Textract), RAG, chunking, embeddings, pgvector, recherche vectorielle, indexation
> documentaire NE FONT PAS PARTIE DE LA V1** — ils sont repoussés en **V2** (voir `PROJECT.md` §1.6/§11.15,
> ADR-004, `PRODUCT_SPEC.md` F-05→F-08). **La V1 est une passerelle pure vers Claude** ; les fichiers
> sont uniquement transmis au fournisseur, sans traitement de contenu. Auth V1 = OAuth2/OIDC **+**
> email/mot de passe (JWT). En cas de conflit, `PROJECT.md` prévaut. Les sections ci-dessous décrivant
> le pipeline documentaire décrivent la **cible V2**, pas la V1.

Ce document constitue la référence architecturale technique du projet claude-gateway.
Toute implémentation technique, toute proposition d'évolution ou toute génération
de code doit rester cohérente avec `PROJECT.md` puis ce document.

Toute divergence doit être explicitement signalée.

> Voir aussi `docs/spec.md` (spécification technique détaillée) et `docs/marketing.md`.

---

# 1 — Vision du produit

claude-gateway est une application de chat LLM hébergée (proxy Claude), accessible par navigateur,
destinée principalement aux **consultants en mission** dont l'accès direct aux LLM est bloqué par
les proxys/DSI.

Objectif principal :

Fournir un accès simple, sécurisé et traçable aux meilleurs LLM (Claude) depuis le navigateur,
avec en option l'analyse documentaire (OCR + RAG), en modèle **Hosted** (clé et facturation gérées
par la plateforme) ou **BYOK** (l'utilisateur fournit sa propre clé), le tout monétisé par
abonnement (Stripe).

Le système :

1. Authentifie l'utilisateur et vérifie son entitlement/quota.
2. Reçoit un message et le relaie (proxy) vers l'API Claude, en Hosted ou BYOK.
3. Permet d'uploader des documents (pdf, docx, txt, png, jpg — max 20 Mo) stockés sur S3.
4. Extrait le texte (OCR AWS Textract pour images/PDF scannés), le découpe (chunking) et l'indexe (embeddings + pgvector) — indexation opt-in.
5. Répond à des questions ancrées sur les documents indexés (RAG : recherche sémantique top-K → prompt cité → Claude).
6. Gère les abonnements et la facturation via Stripe (webhooks → entitlements).

---

# 2 — Positionnement produit

## Domaine initial (V1)

Chat proxy vers Claude + analyse documentaire optionnelle, pour consultants (freelances, cabinets
boutique, développeurs freelance). L'accent V1 : proxy fiable, upload/OCR/RAG, BYOK/Hosted, billing.

Cas d'usage principaux :

- Poser des questions à Claude depuis le navigateur en mission, sans accès direct.
- Uploader un document client et interroger son contenu (RAG cité).
- Utiliser sa propre clé API (BYOK) pour maîtriser sa facturation.
- Souscrire un abonnement (Solo/Pro/Daily) ou un daily pass.

Documents ou entités typiques manipulés :

- Documents (pdf, docx, txt, images), chunks de texte + embeddings, messages/conversations, abonnements.

## Extension progressive

- **V1** : chat proxy, upload + OCR + RAG, BYOK/Hosted, billing Stripe, quotas.
- **V2** : templates métier (audit, rapport), export, embeddings locaux (all-MiniLM), rapports d'usage/coût in-app.
- **V3** : espaces d'équipe (cabinets), on-prem/allowlist, connecteurs, multimodal étendu.

---

# 3 — Modèle SaaS

claude-gateway est un SaaS **multi-tenant par utilisateur** (B2C/B2B individuel) : chaque utilisateur
est son propre périmètre d'isolation. Il n'y a pas (en V1) de notion d'organisation/workspace
partagé ; la colonne d'isolation est **`user_id`**.

## Concepts fondamentaux

### Conversation
Fil d'échange entre un utilisateur et Claude (`messages.conversation_id`). Les messages appartiennent
à un `user_id`. Sert d'historique et de contexte.

### Document indexé
Fichier uploadé (`documents`) → texte extrait (OCR si besoin) → chunks (`chunks`) → embeddings
(pgvector) permettant la recherche sémantique et le RAG cité. Indexation opt-in.

### Utilisateur
Personne physique (consultant) accédant à la plateforme. Porte son historique, ses documents, sa
clé BYOK (chiffrée) et son abonnement. Toute donnée est isolée par `user_id`.

---

# 4 — Stack technique

La stack est volontairement simple et maîtrisée.

Frontend
Angular 19 (Angular Material)

Backend
Spring Boot 3.5 / Java 21

Base de données
PostgreSQL (production, extension **pgvector**) — H2 en mémoire (dev/test)

Migrations de schéma
Liquibase (XML, versionné dans `db/changelog/migrations/`). Colonnes JSON/vector spécifiques Postgres
isolées en changesets `dbms="postgresql"`.

Authentification
Spring Security **stateless** + JWT (HS256, secret plateforme `APP_JWT_SECRET`). Deux modes (OQ-05
tranchée le 2026-07-01, F-01 livrée) : **email/mot de passe** (BCrypt) et **OAuth2/OIDC Google**
(fédération par e-mail → même JWT plateforme). Le frontend gère 401 → /login.
Tables du domaine auth (migrations `001`–`004`) :
- `users` — compte (`id`, `email`, `password_hash` nullable, `email_verified`, `provider` LOCAL/GOOGLE,
  `role`, `token_version`, timestamps). Racine de l'isolation `user_id`.
- `email_verification_tokens` — tokens de vérification d'e-mail (usage unique, expiration).
- `password_reset_tokens` — tokens de réinitialisation de mot de passe (usage unique, expiration).
Déconnexion « toutes sessions » via incrément de `users.token_version` (claim `tv` du JWT vérifié
par le filtre). Pas de session serveur (hors handshake OAuth transitoire).

Stockage fichiers
Object storage S3 (AWS), SSE-KMS. En local : conteneur compatible (MinIO/localstack) ou S3 de dev.

Intégration IA
- Proxy vers l'API Claude (Anthropic) — Hosted (clé plateforme) ou BYOK (clé utilisateur chiffrée).
- OCR : AWS Textract (sync images, async PDF avec polling).
- Embeddings : via API fournisseur (Anthropic/OpenAI) en V1, migration possible vers local.
Tous les traitements longs (Textract PDF, ingestion embeddings) sont **asynchrones** (workers).

---

# 5 — Architecture système

Architecture logique :

Frontend Angular
→ interface utilisateur (Chat, Upload, Documents, Settings, Billing)

Backend Spring Boot
→ API métier, orchestration, proxy Claude, contrôle du pipeline d'ingestion

PostgreSQL (pgvector)
→ persistance (documents, chunks+embeddings, messages, subscriptions)

Composants supplémentaires :
- **Claude API** (Anthropic) — proxy des messages, Hosted/BYOK.
- **AWS S3** — stockage des uploads (SSE-KMS).
- **AWS Textract** — OCR (accès via IRSA).
- **Embedding API** (provider) — génération des vecteurs.
- **Worker(s) Kubernetes** — ingestion/Textract polling/embeddings (asynchrone).
- **Stripe** — abonnements + webhooks.
- **Secrets** : Kubernetes Secrets ; clés BYOK chiffrées (KMS). Accès AWS via IRSA.

Déploiement : cluster EKS partagé `legalcase-shared` (eu-west-3), workspace dédié
(namespace `claude-gateway-staging`), exposé sur `portal.ng-itconsulting.com` (nginx-ingress +
cert-manager). RDS PostgreSQL partagé avec legalcase, base dédiée `claudegatewaydb`.

---

# 6 — Modèle de données (entités principales)

- **users** — compte (F-01). Racine de l'isolation multi-tenant (`user_id = users.id`).
- **conversations** (1) → (N) **messages** — fil d'échange (F-02, migrations `005`/`006`).
  - `conversations` : `id (uuid)`, `user_id (uuid)`, `title`, `model`, `created_at`, `updated_at`. Index `user_id`.
  - `messages` : `id (uuid)`, `conversation_id (uuid, FK cascade)`, `user_id (uuid)`, `role (USER|ASSISTANT)`, `content`, `model (nullable)`, `created_at`. Index `conversation_id`, `user_id`.
  - **Note** : la table `messages` du schéma initial `001-init-schema` (issue de l'ancien `spec.md`, jamais câblée à une entité, `user_id text`, sans `model`/FK) a été **remplacée** en `006` par la table V1 conforme ci-dessus (typage `uuid`, FK cascade, colonne `model`).
- **documents** (1) → (N) **chunks** — scaffolding V2 dormant (OCR/RAG), non câblé en V1.
- **subscriptions** — abonnement Stripe par `user_id` (`plan`, `status`, `stripe_*`) — cible F-09.

Voir `docs/spec.md` §4 pour le DDL historique (scaffolding). Le schéma V1 réel est porté par les migrations Liquibase (`db/changelog/migrations/`).

Règle d'isolation des données :
Tout accès aux données filtre obligatoirement sur **`user_id`** (documents via `uploaded_by`,
messages/subscriptions via `user_id`). Aucun endpoint ne renvoie des données d'un autre utilisateur.

---

# 7 — Règles d'architecture non négociables

- **Layering strict** : Controller → Service → Repository. Pas de logique métier dans les controllers, pas d'accès repository depuis un controller.
- **Isolation des données** : tout accès filtre sur `user_id`. Jamais de requête sans filtre tenant.
- **Traitements longs asynchrones** : OCR PDF (Textract polling) et ingestion embeddings passent par des workers, jamais dans le thread HTTP.
- **Proxy LLM sécurisé** : la clé (plateforme ou BYOK) n'est jamais exposée au client. Clés BYOK stockées chiffrées.
- **Migrations via Liquibase uniquement** : jamais de DDL manuel hors changelog. `ddl-auto: validate`.
- **Auth obligatoire** : tous les endpoints métier sont authentifiés ; gestion 401 → /login côté frontend.
- **Secrets hors du code** : via K8s Secrets / variables d'environnement, jamais commités.

---

# 8 — Questions ouvertes

Les sujets non encore tranchés sont listés dans `docs/OPEN_QUESTIONS.md`.

Décisions impactant l'architecture actuelle :

- OQ-01 : Dimension d'embedding définitive (provider 1536 vs local 384) — impacte le schéma `chunks.embedding`.
- OQ-02 : Version Postgres RDS et modalités d'activation de pgvector sur l'instance partagée.
- OQ-05 : Fournisseur(s) OAuth et modèle de session/token.
