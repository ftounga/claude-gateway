# ARCHITECTURE_CANONIQUE.md
claude-gateway — Architecture produit et technique de référence

> ⚠️ **DOCUMENT SUBORDONNÉ À `docs/PROJECT.md`** (source de vérité produit).
> **Séquence de livraison** : la **passerelle** (F-01→F-12) d'abord, **puis** le **traitement documentaire**
> (OCR/Textract, RAG, chunking, embeddings, pgvector, recherche vectorielle, indexation) — désormais
> **dans le périmètre** (amendement `PROJECT.md` du 2026-07-01, **ADR-011** superséde ADR-004,
> `PRODUCT_SPEC.md` F-05→08 + F-13/14/15/16). Les sections ci-dessous décrivant le pipeline documentaire
> sont donc **valides** (à construire). Auth = OAuth2/OIDC **+** email/mot de passe (JWT). Restent hors
> périmètre : V3 (F-17 équipes, F-18 on-prem). En cas de conflit, `PROJECT.md` prévaut.

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
- **uploaded_files** — métadonnées d'un fichier téléversé puis transmis au fournisseur (F-04, migration `007`). **Aucun contenu binaire stocké** (relais pur, PROJECT.md §11.6). Dossier de fichiers par conversation (F-23, migration `033-uploaded-files-conversation`) : colonne `conversation_id` rattachant le fichier à la conversation où il a été joint.
  - `uploaded_files` : `id (uuid)`, `user_id (uuid)`, `conversation_id (uuid, nullable, FK → conversations(id) ON DELETE SET NULL)`, `provider_file_id (interne, jamais exposé)`, `filename`, `media_type`, `size_bytes`, `created_at`. Index `user_id`, index `conversation_id`. Stampé au premier rattachement à un tour de chat (F-23) ; `GET /api/conversations/{id}/files` liste les fichiers d'une conversation (isolation `user_id`).
- **documents** — document soumis au pipeline OCR (F-05, migration `010`). Isolé par `user_id`.
  - `documents` : `id (uuid)`, `user_id (uuid)`, `filename`, `media_type`, `size_bytes`,
    `status (UPLOADED|PROCESSING|EXTRACTED|INDEXING|INDEXED|FAILED)`,
    `ocr_mode (SYNC|ASYNC|LOCAL)` — LOCAL = extraction Word sur la machine, sans fournisseur OCR (F-86), `provider_job_id (interne, nullable, jamais exposé)`,
    `extracted_text (nullable)`, `textract_raw (brut fournisseur, nullable, jamais exposé)`,
    `error_message (neutre, nullable)`, `chunk_count (int, F-06, migration 011)`,
    `created_at`, `updated_at`. Index `user_id`.
  - OCR via l'interface abstraite **`OcrProvider`** (Provider Independence) : impl AWS Textract
    (`TextractOcrProvider`, SDK confiné au package provider) ou `StubOcrProvider` (dev/tests).
    Images → sync `DetectDocumentText` ; PDF/TIFF → async `StartDocumentTextDetection` +
    worker de polling intra-backend (`OcrPollingWorker`, `@Scheduled` ; OQ-10). Secrets AWS via
    IRSA, jamais loggés.
  - **Note** : la table `documents` du schéma initial `001-init-schema` (placeholder legacy `spec.md`,
    `uploaded_by text`, sans `user_id`) a été **remplacée** en `010` par la table V1 conforme
    ci-dessus (même stratégie que `006-messages`/`008-subscriptions`).
- **chunks** — (N) fragments d'un **documents** issus de l'ingestion RAG (F-06, migration `011`).
  Isolé par `user_id` (filtre direct pour la recherche vectorielle F-07). Reconstruite en `011` :
  `id (uuid)`, `document_id (uuid, FK→documents CASCADE)`, `user_id (uuid)`, `chunk_index (int)`,
  `text`, `char_start`/`char_end (offsets, nullable)`, `page_number (nullable, non dérivé en F-06)`,
  `created_at`. Index `document_id`, `user_id`.
  - Colonne pgvector **`embedding vector(1536)`** (OQ-01) + index **ivfflat `lists=100`** (OQ-03) :
    **Postgres uniquement** (DDL isolé `dbms=postgresql`). L'entité JPA `Chunk` ne mappe pas `embedding` ;
    sa persistance passe par l'abstraction **`EmbeddingStore`** (`PgVectorEmbeddingStore` SQL natif /
    `NoopEmbeddingStore` par défaut → tests H2 verts sans type vectoriel).
  - Embeddings via l'interface abstraite **`EmbeddingProvider`** (Provider Independence) : impl
    `StubEmbeddingProvider` (défaut, déterministe) ou `ApiEmbeddingProvider` (HTTP OpenAI-compatible,
    clé env jamais loggée). Ingestion asynchrone via `IngestionWorker` (`@Scheduled`, intra-backend ;
    OQ-10) — hors thread HTTP. Idempotente (suppression + recréation des chunks, isolée `user_id`).
  - **Recherche vectorielle (F-07, Q&A `/ask`)** : `EmbeddingStore.search(userId, queryVector, topK)`
    (extension de l'abstraction) — impl `PgVectorEmbeddingStore` (SQL natif plus-proches-voisins `<->`
    L2, **filtre `user_id`**, index ivfflat de `011`) / `NoopEmbeddingStore` (vide → repli en H2/tests).
    `AskService` : quota (F-10) → embedding question → recherche isolée → rechargement chunks/documents
    **filtré `user_id`** → prompt cité `[filename:page:chunkIndex]` → relais Claude (`AIProvider`).
    Endpoint **`POST /ask`** (authentifié). Aucune nouvelle table (réutilise `chunks.embedding`).
- **subscriptions** — abonnement d'un utilisateur (F-09, migration `008`). **Un seul par `user_id`** (unique).
  - `subscriptions` : `id (uuid)`, `user_id (uuid, unique)`, `status (TRIALING|ACTIVE|PAST_DUE|CANCELED|INCOMPLETE)`,
    `plan_code (nullable ; SOLO|PRO|DAILY|GOLD)`, `trial_ends_at (nullable)`, `current_period_end (nullable)`,
    `stripe_customer_id (interne, nullable, jamais exposé)`, `stripe_subscription_id (interne, nullable, jamais exposé)`,
    `atelier_option_status (nullable ; même énumération que status — F-40, migration 054)`,
    `atelier_option_stripe_subscription_id (interne, nullable, unique, jamais exposé — F-40, migration 054)`,
    `atelier_option_cancel_at (nullable ; terme d'une résiliation programmée — F-40, migration 055)`,
    `teams_option_status (nullable ; même énumération que status — F-89 / SF-89-01, migration 078 ; état de l'option Vigie depuis F-107 / SF-107-03)`,
    `vigie_option_stripe_subscription_id (interne, nullable, unique, jamais exposé — F-107 / SF-107-03, migration 093)`,
    `vigie_option_cancel_at (nullable ; terme d'une résiliation d'option Vigie programmée — migration 093)`,
    `created_at`, `updated_at`. Index `user_id`, `stripe_subscription_id`, `atelier_option_stripe_subscription_id` (unique), `vigie_option_stripe_subscription_id` (unique).
  - **Droit d'Atelier (F-40)** : l'accès à l'Atelier n'est plus un test de **plan** (`plan_code = GOLD`)
    mais un test de **droit**, porté par le plan Gold actif **ou** par l'option Atelier active sur un
    plan Solo/Pro actif. La règle vit dans `SpaceEntitlementService` (paquet `billing`, espace `FORGE`,
    F-107 / SF-107-02 — anciennement `AtelierEntitlementService`). L'option
    est un abonnement fournisseur **distinct** de celui du plan, d'où la seconde colonne
    d'identifiant : les confondre ferait qu'une résiliation d'option annulerait le plan.
    L'option ouvre un droit et **jamais** un jeton : les quotas de `app.quota.plans` sont inchangés.
    Endpoints d'option : **`GET /billing/atelier-option`**, **`POST /billing/atelier-option/checkout`**,
    **`POST /billing/atelier-option/cancel`** (authentifiés) ; activation et fermeture appliquées par le
    webhook signé **`POST /webhook/stripe`**, qui route les événements d'option **avant** sa résolution
    générique — sans quoi le repli « par client » écraserait le statut du plan. Résiliation **en fin de
    période** : le statut reste `ACTIVE` jusqu'au terme déjà payé. Prix d'affichage et price ID en
    configuration (`APP_BILLING_ATELIER_OPTION_PRICE`, défaut 40 € ; `STRIPE_PRICE_ATELIER_OPTION`).
  - **Note** : la table `subscriptions` du schéma initial `001-init-schema` (placeholder legacy `spec.md`,
    `user_id text`, `plan`, sans statut typé ni unicité) a été **remplacée** en `008` par la table V1 conforme
    ci-dessus (même stratégie que `006-messages`).
- **usage_counters** — compteur de consommation de tokens (F-10, migration `009`). **Une ligne par
  (`user_id`, période)** (unique).
  - `usage_counters` : `id (uuid)`, `user_id (uuid)`, `period_start (date ; 1er du mois calendaire UTC)`,
    `input_tokens (bigint)`, `output_tokens (bigint)`, `bonus_tokens (bigint, défaut 0 ; tokens rachetés
    top-up F-21, migration `032`)`, `sandbox_seconds (bigint, défaut 0 ; F-28)`,
    `quota_alert_raised_at (timestamptz, nullable ; F-42, migration `058`)`,
    `quota_alert_dismissed_at (timestamptz, nullable ; F-42, migration `058`)`,
    `billed_tokens (bigint, défaut 0 ; F-63, migration `069`)`,
    `created_at`, `updated_at`. Unique `(user_id, period_start)`, index `user_id`.
  - **Décompte au coût réel (F-63)** : `input_tokens` / `output_tokens` restent des **volumes** —
    ce que le fournisseur a traité, cache compris —, et c'est d'eux que vivent le rapport d'usage
    (F-16) et la consommation par client (F-61), qui en déduisent un coût estimé. Ce que le quota
    oppose est `billed_tokens`, où **chaque nature de token pèse son coût** : une sortie coûte cinq
    fois une entrée chez le fournisseur, une lecture de cache un dixième. Écrire le décompte pondéré
    dans les colonnes de volume aurait appliqué les mêmes tarifs deux fois — un coût au carré —,
    d'où une colonne distincte. Les tarifs, la valeur d'un token de quota et le markup sont en
    **configuration** (`app.atelier.agent.cost.*`), jamais en dur. Reprise de la migration :
    `billed_tokens = input_tokens + output_tokens` — le changement de comptage ne vaut que pour les
    tours à venir.
  - Alimente la vérification de quota **avant** l'appel fournisseur (`ChatService` → `402 quota_exceeded`
    à la limite) et `GET /usage`. Le quota **effectif** = quota mensuel (dérivé de `subscriptions` via la
    configuration `app.quota`, jamais en dur, réversible) **+ `bonus_tokens`** de la période (rachats top-up,
    F-21). V1 = **blocage à la limite** (overage non monétisé, OQ-08 ; variante payante ouverte).
  - **Alerte de consommation (F-42)** : les deux colonnes `quota_alert_*` portent la marque « déjà
    prévenu ». Elles vivent ici, et pas dans une table dédiée ni en mémoire, parce que la ligne
    `(user_id, period_start)` **est** déjà l'unité « un utilisateur, une période » : la marque coûte zéro
    lecture (le compteur est déjà chargé par `recordUsage`), elle survit au redéploiement et reste
    cohérente entre les deux replicas (application stateless), et le mois suivant crée une nouvelle ligne
    qui **ré-arme l'alerte sans code de remise à zéro**. `raised_at` est posé **une seule fois** (unicité
    de l'émission) ; `dismissed_at` retient que l'utilisateur l'a écartée. L'évaluation est faite après
    l'incrément et **avant** la sauvegarde — même écriture — et encadrée : elle n'échoue jamais l'appel.
- **usage_turns** — journal de consommation **par tour** (F-61 / SF-61-01, migration `068`).
  **Append-only** : une ligne par tour facturé, jamais modifiée, effacée seulement avec le compte.
  - `usage_turns` : `id (uuid)`, `user_id (uuid, NOT NULL)`, `workspace_id (uuid, nullable)`,
    `host_id (uuid, nullable)`, `input_tokens (bigint, défaut 0)`, `output_tokens (bigint, défaut 0)`,
    `occurred_at (timestamptz)`. Index `(user_id, occurred_at)`.
  - **Pourquoi elle existe** : `workspaces.agent_input_tokens` (migration `040`) est un **repère de
    delta remis à zéro à chaque ouverture de session** (`markSessionOpened`) — l'agréger ferait
    **rétrécir** les totaux, et un consultant verrait la consommation d'un client baisser toute
    seule. `usage_counters` est monotone mais son grain est (utilisateur × mois) : aucune dimension
    projet ni poste. Le relevé de tour existait déjà dans `atelier_messages.terminal_json`, mais en
    **JSON d'affichage**, aux côtés de la transcription — facturer en traversant des contenus est
    exclu.
  - **Aucune colonne de texte**, volontairement : des identifiants, deux volumes, un horodatage.
    Les écrans de F-61 montrent des volumes et des coûts, **jamais** des contenus, et cette garantie
    est tenue par la **structure** de la table plutôt que par la prudence des requêtes.
  - **Aucune clé étrangère** vers `workspaces` ni `runner_hosts` : une pièce de refacturation ne
    disparaît pas parce qu'on a rangé un projet. `host_id` est un **instantané** du poste au moment
    du tour — déplacer un projet demain ne déplace pas une dépense déjà refacturée.
  - Alimente `GET /usage/by-client` (F-61 / SF-61-02). La console d'administration
    (`GET /admin/usage`, SF-61-03) ne la lit **pas** : elle agrège `usage_counters`, qui ignore les
    projets — une vue admin bâtie sur ce journal donnerait à l'administrateur la carte des missions
    de ses utilisateurs.
- **host_seat_months** — le **mois-poste** (F-65 / SF-65-01, migration `070`). Une ligne = « ce
  poste a été facturable pendant cette période, à partir de `billable_from` ».
  - `host_seat_months` : `id (uuid)`, `user_id (uuid, NOT NULL)`, `host_id (uuid, NOT NULL)`,
    `space (varchar 16, NOT NULL, défaut FORGE — F-107 / SF-107-05, migration 095)`,
    `period_start (date, NOT NULL)`, `billable_from (date, NOT NULL)`, `created_at (timestamptz)`.
    **Unicité `(host_id, space, period_start)`** (le supplément est par espace ; `(host_id, period_start)`
    avant la migration 095), index `(user_id, period_start)`.
  - **Pourquoi elle existe** : l'état de mission de F-60 (`runner_hosts.mission_status`) dit quels
    postes sont facturables **maintenant** — tous sauf les clôturés. Il ne dit pas ce qui a été vrai
    **pendant le mois**, et c'est exactement ce dont la facturation au poste a besoin : un poste
    clôturé le 12 a engagé le mois, un poste rouvert le 20 après clôture ne doit pas être compté
    deux fois. L'unicité `(host_id, period_start)` **est** la règle « un mois-poste se paie une
    fois » : la réouverture retrouve la ligne et la laisse intacte — ni piège à utilisateur (pas de
    double facturation), ni faille (pas de compteur remis à zéro par un aller-retour).
  - **L'absence de ligne est signifiante** : un poste facturable aujourd'hui sans ligne sur la
    période l'est depuis le premier jour du mois — ou depuis sa création, si elle est plus tardive.
    C'est ce qui dispense F-65 de toute écriture périodique : **aucun job mensuel ne peut manquer,
    puisqu'aucun n'existe**. Deux écritures seulement, aux frontières de facturabilité (clôture,
    réouverture).
  - **Aucune clé étrangère** vers `runner_hosts` : même choix que `usage_turns`. La purge est
    explicite — suppression du poste (`deleteByHostId`), suppression du compte (`deleteByUserId`).
  - Alimente `GET /billing/seats` et la part de quota apportée par les postes supplémentaires
    (`EntitlementService.resolveEffectiveMonthlyTokenQuota`). **Aucun montant n'est stocké ici** :
    les jetons se recalculent à partir de la configuration `app.seat` (défauts inertes).
- **host_spaces** — les **espaces d'un client** (F-106 / SF-106-01, migration `091`). Une ligne =
  « ce poste est activé dans cet espace depuis `activated_at` », `space ∈ {FORGE, VIGIE}`.
  - `host_spaces` : `id (uuid)`, `user_id (uuid, NOT NULL)`, `host_id (uuid, NOT NULL)`,
    `space (varchar 16, NOT NULL)`, `activated_at (timestamptz, NOT NULL)`. **Unicité
    `(host_id, space)`**, index `(user_id, space)`.
  - **Un poste, deux regards** : le poste reste une seule entité (machine, runner, appairage,
    mission). Activer dans un espace n'appaire rien ; retirer d'un espace ne supprime rien ailleurs ;
    le dernier espace ne se retire pas. La migration active **tous les postes existants dans la
    Forge**, aucun dans la Vigie. Un poste **sans ligne** est lu comme activé dans la Forge
    (déploiement progressif).
  - Filtre `GET /runner-hosts/overview?space=` ; les API de la Vigie (Radar) exigent le poste activé
    dans la Vigie (409 `host_not_in_space`), sauf export et purge.
  - **Le runner est commun aux deux espaces** (F-107 / SF-107-07) : postes, appairage, statut,
    coupe-circuit, `GET /teams/access` et terminal Teams s'ouvrent avec le droit Forge **ou** Vigie
    (`AtelierAccessService.requireRunnerAccess` / `requireTerminalAccess`) ; l'espace visé
    (`?space=`, création, activation) exige son propre droit ; projets, terminaux de projet, terminal
    du poste, carte et gouvernance restent Forge. `GET /workspaces?space=VIGIE` rend les seuls
    terminaux Teams.
  - **Aucune clé étrangère** : purge explicite à la suppression du poste (événement `DELETED`) et du
    compte.
- **host_mail_addresses** — l'**adresse de réception d'un client** (F-110 / SF-110-01, migration `100`).
  Une ligne au plus par poste : là où l'utilisateur reçoit, pour ce client, les courriels qu'il s'envoie.
  - `host_mail_addresses` : `id (uuid)`, `user_id (uuid, NOT NULL, FK users ON DELETE CASCADE)`,
    `host_id (uuid, NOT NULL, FK runner_hosts ON DELETE CASCADE)`, `address (varchar 254, NOT NULL)`,
    `verified_at (timestamptz)`, `code_hash (varchar 64)`, `code_expires_at`, `code_sent_at`,
    `code_attempts (int, NOT NULL, 0)`, `created_at`, `updated_at`. **Unicité `(host_id)`**, index
    `(user_id, host_id)`.
  - **Vérifiée ou inutilisée** : un code à 6 chiffres (empreinte SHA-256 seule, 15 min, 5 essais, une minute
    entre deux envois) prouve l'adresse ; changer d'adresse la repasse non vérifiée.
    `HostMailAddressService.resolveRecipient` rend l'adresse vérifiée du poste, sinon l'adresse du compte
    (`verifiedForClient=false`, à dire) — jamais une adresse en attente, jamais une adresse fournie par un
    appelant. API `/runner-hosts/{hostId}/mail-address` (garde runner : Forge ou Vigie).
  - **Clés étrangères en cascade** (choix assumé, contrairement à `host_spaces`) : l'adresse tombe avec le
    poste et avec le compte sans purge à écrire ailleurs.
- **client_emails** — les **courriels que l'utilisateur s'envoie** : file d'envoi **et** journal (F-110 /
  SF-110-02, migration `102`).
  - `client_emails` : `id`, `user_id (FK users ON DELETE CASCADE)`, `host_id (FK runner_hosts ON DELETE
    CASCADE)`, `workspace_id` (terminal d'origine, nullable), `kind (AGENT | MORNING_SUMMARY)`, `client_name`,
    `recipient`, `recipient_verified`, `subject (200)`, `size_bytes`, `attachment_count`, `body_text`,
    `body_html` (`text` ; `varchar(1000000)` en H2), `status (PENDING | SENDING | SENT | FAILED)`, `attempts`,
    `next_attempt_at`, `leased_until`, `failure_reason`, `sent_at`, `created_at`, `updated_at`. Index
    `(user_id, kind, created_at)` (limite quotidienne), `(status, next_attempt_at)` (travailleur),
    `(user_id, host_id)`.
  - **Destinataire jamais fourni par le modèle** : l'outil `email_me` (`ClientMailTool`, donné dans
    `AtelierChatService.buildTools` à tout terminal de poste avec Forge ou Vigie) n'a aucun champ destinataire ;
    `resolveRecipient` (SF-110-01) le fixe à la mise en file. Refus des secrets manifestes, 50 courriels `AGENT`
    par compte sur 24 h glissantes, Markdown rendu par commonmark (HTML brut échappé, liens assainis).
  - **Envoi asynchrone** : `ClientMailWorker` → `ClientMailOutbox.runOnce()` prend chaque ligne sous bail (mise
    à jour conditionnelle, 2 min), envoie par `EmailService.sendClientMail` (`multipart/alternative`, nom
    affiché « claude-gateway pour <client> », délais SMTP bornés F-77). Refus définitif → `FAILED` ; échec
    passager → reprise 1/5/15/60 min, `FAILED` au 5ᵉ. **Corps effacés à l'état final** : la ligne reste le
    journal. `GET /client-emails/{id}` rend l'état (jamais le corps) ; le terminal le relit (bloc « Courriel
    envoyé », champ `email` du bloc de transcription, événement SSE `email`).
  - **Pièces jointes** (SF-110-03, sans migration) : `attachments` de `email_me` — fichier du poste lu par le
    runner en binaire par tranches (outil runner `read_file_bytes`, 10 Mo au plus, tracé dans `runner_audit`),
    page F-109 du même poste (HTML joint + lien privé `/pages/{id}` dans le corps, ou `link_only`), export
    Markdown du Radar. **10 Mo au total** (pièces + corps), au-delà refus et consigne de proposer un lien ;
    conteneurs de secrets refusés par leur nom, pièces texte passées à `ClientMailSecrets`. Les octets vivent
    dans le stockage objet (`WorkspaceStorage`) sous `client-emails/{userId}/{emailId}/{nn}/{nom encodé}`,
    écrits dans la transaction de la mise en file, relus par le travailleur (`multipart/mixed`), **effacés à
    l'état final** (pièce manquante → `FAILED` sans reprise) et à la suppression du compte.
- **user_api_keys** — clé API personnelle BYOK chiffrée au repos (F-03, migration `030`, OQ-06 : AWS KMS
  envelope encryption). **Une seule clé par utilisateur** (`user_id` unique). **Aucune clé en clair** : seuls
  le blob chiffré et les 4 derniers caractères sont persistés.
  - `user_api_keys` : `id (uuid)`, `user_id (uuid, unique)`, `provider (ANTHROPIC)`, `encrypted_data_key`,
    `cipher_iv`, `ciphertext` (base64 chiffré), `key_last4`, `active (bascule Hosted/BYOK)`, `validated_at`,
    `created_at`, `updated_at`. Index `user_id`.
  - Le chiffrement est confiné à `fr.claudegateway.byok` (`ByokKeyCipher` : `KmsEnvelopeCipher` en cluster,
    impl locale dev/tests, impl dormante si non configuré → 503). `ChatService` déchiffre la clé active à la
    volée pour l'appel fournisseur (jamais persistée ni journalisée), sinon utilise la clé plateforme (Hosted).
    La clé est passée à `AIProvider` en paramètre neutre (Provider Independence).
- **user_git_credentials** — jeton d'accès GitHub de l'utilisateur, chiffré au repos (F-31 / SF-31-01,
  migration `042`). **Table dédiée**, et non extension de `user_api_keys` : le **chiffrement** de F-03 est
  réutilisé (`ByokKeyCipher`), pas le stockage — toucher à l'unicité vivante de la table qui porte la clé
  Claude serait un risque sans contrepartie. **Un seul jeton par utilisateur** (`user_id` unique).
  **Aucun jeton en clair** : seuls le blob chiffré, les 4 derniers caractères et le compte GitHub sont persistés.
  - `user_git_credentials` : `id (uuid)`, `user_id (uuid, unique)`, `github_login (varchar 100 ; public)`,
    `encrypted_data_key`, `cipher_iv`, `ciphertext` (base64 chiffré), `token_last4`, `created_at`,
    `updated_at`. Index `user_id`.
  - Endpoints **`GET/POST/DELETE /user/git-token`** (authentifiés, `user_id` du `SecurityContext` uniquement).
    Le jeton est **vérifié auprès de GitHub avant toute écriture** (`GitHubClient` → `GET /user` ; seul point
    du code couplé à GitHub) : 401/403 → `400 invalid_git_token`, 5xx/réseau → `503 github_unavailable` —
    une panne n'efface jamais un jeton valide. Jamais journalisé, jamais renvoyé. Inclus dans la
    suppression RGPD du compte (F-11). Il ne sera **jamais** injecté dans le sandbox : le proxy git du
    fournisseur l'ajoute après la sortie du conteneur (ADR-015).
- **workspaces — source du projet** (F-31 / SF-31-02, migration `043`). Le workspace d'Atelier gagne
  une **source** : `ARCHIVE` (archive `.zip` téléversée, comportement historique) ou `GIT` (dépôt monté
  par le fournisseur). **Aucune table nouvelle** : colonnes ajoutées à `workspaces`, toutes nullables ou
  à valeur par défaut — aucune donnée existante cassée.
  - Colonnes : `source (varchar 16, défaut ARCHIVE, non nul)`, `git_repo_url (varchar 500 ; URL publique)`,
    `git_owner (varchar 100)`, `git_repo (varchar 100)`, `git_branch (varchar 255 ; branche montée, et
    branche de base interdite au push)`. **Aucun secret** : le jeton vit chiffré dans
    `user_git_credentials`, déchiffré à la volée au seul moment du montage de session.
  - Un projet `GIT` **ne copie aucun fichier** dans le stockage objet : le dépôt est cloné dans la
    sandbox (`resources: [{type: "github_repository", …}]`), ce qui supprime le plafond
    `maxSessionFiles` (300) sur ces projets. Le stockage objet ne reçoit que les fichiers **réécrits**
    par la session.
  - **Explorateur** (SF-31-03) : arborescence = union de la branche (API GitHub, sans coût de sandbox)
    et des fichiers réécrits ; la version locale prime à la lecture. **Lecture seule** sur un projet
    `GIT` (`409 git_workspace_read_only`) et mode « Assistant » écarté (`409 git_workspace_terminal_only`) :
    écrire dans le stockage pendant que l'agent travaille sur le clone créerait deux vérités divergentes.
  - **Publication** (SF-31-04) : `POST /workspaces/{id}/git/push` fait pousser une **branche dédiée**
    par l'agent via le proxy git, puis **constate** l'existence de la branche auprès de GitHub avant
    d'annoncer un succès. Jamais sur la branche de base ; jamais de session ouverte pour l'occasion
    (`409 no_active_session`).
  - **Pull request** (SF-31-05) : `POST /workspaces/{id}/git/pull-request` fait appeler l'outil
    `create_pull_request` du **serveur MCP GitHub** par l'agent, puis **constate** l'existence de la
    pull request ouverte auprès de GitHub (`GET /repos/{owner}/{repo}/pulls?head={owner}:{branche}`)
    avant d'annoncer une URL. Même règles qu'au push : branche dédiée obligatoire, session existante
    obligatoire, échec = `200 created:false` + compte rendu.
- **user_git_credentials — vault de credentials MCP** (F-31 / SF-31-05, migration `045`). Deux
  colonnes ajoutées, **nullables** et **sans aucun secret** : `mcp_vault_id (varchar 64)` et
  `mcp_credential_id (varchar 64)` — des identifiants opaques rendus par le fournisseur
  (`vlt_…`, `vcrd_…`). Le PAT reste chiffré dans les colonnes existantes ; sa copie déposée dans le
  vault est **write-only** côté fournisseur (jamais relue, jamais renvoyée) et **n'entre jamais dans
  le conteneur** (proxy MCP, même garantie que le proxy git).
  - **Un vault par utilisateur** : le fournisseur n'accepte qu'une credential par `mcp_server_url` et
    par vault — un vault partagé ne pourrait porter qu'un seul PAT — et mélanger les jetons violerait
    l'isolation `user_id`.
  - **Créé paresseusement**, à la première session sur un dépôt Git ; **détruit à la révocation** du
    jeton (remplacement ou retrait), par événement applicatif consommé après commit. Un jeton révoqué
    chez nous mais toujours utilisable chez le fournisseur serait une révocation de façade.
  - Le vault s'attache **à la création de session** (`vault_ids`) : le fournisseur refuse de l'ajouter
    ensuite. Une session ouverte avant SF-31-05 n'a donc pas l'outil ; « Réinitialiser la sandbox »
    en rouvre une équipée.
- **workspaces — validation avant exécution** (F-33 / SF-33-01, migration `044` ; **défaut inversé
  par F-73 / SF-73-02, migration `073`**). Colonne
  `agent_ask_before_bash (boolean, non nul, défaut **true**)` : quand elle est posée, la session
  d'agent est ouverte avec `permission_policy: always_ask` sur le **seul outil `bash`** (surcharge
  d'outils session-locale, `agent_with_overrides.tools` — l'agent plateforme n'est jamais modifié).
  **Aucune table nouvelle**, et la migration `073` ne fait **aucun `UPDATE`** : seul le défaut de
  colonne change, les projets existants gardent le réglage qu'ils portent. Le défaut est posé aussi
  sur l'**entité** (`@Builder.Default`), pour que tout chemin de création l'hérite (ADR-019).
  - La politique est fixée à l'**ouverture** de session : `PUT /workspaces/{id}/agent/confirmation`
    répond `appliesToCurrentSession: false` quand une sandbox tourne déjà, plutôt que d'annoncer une
    protection qui n'est pas en vigueur. La réinitialisation (F-30 SF-30-06) l'applique.
  - **La demande en attente n'est jamais persistée** (SF-33-02) : elle ne vaut que le temps du run.
    Elle est relayée dans le flux SSE (`confirm_request`), tranchée par
    `POST /workspaces/{id}/agent/confirm` (`allow` / `deny` + motif relayé à l'agent), et le
    rendez-vous passe par la session chez le fournisseur — donc sans état partagé entre répliques.
    Sans réponse dans `app.atelier.agent.confirm-timeout` (défaut `PT2M`), la commande est **refusée**.
  - ⚠️ Une session en attente de confirmation émet `session.status_idle` : seul un `idle`
    **non `requires_action`** termine un run, sous peine de clore le tour sans exécuter la commande.
- **atelier_messages — mémoire de la trajectoire d'outils** (F-39 / SF-39-03, migration `050`).
  Colonne `tool_trace` (texte, **nullable**) : pour chaque itération du tour, le commentaire de
  l'agent, ses appels d'outils avec leurs arguments et leurs résultats. **Aucune table nouvelle** —
  donnée de **REJEU**, lue en bloc avec l'historique et jamais requêtée, exactement comme
  `terminal_json` (`041`) est la donnée d'**AFFICHAGE**. Elle hérite ainsi de l'isolation `user_id`
  de la table.
  - **Bornée en trois endroits** : résultat d'outil conservé à 4 000 caractères (**la fin**, où se
    trouve le code de sortie), trajectoire d'un tour à 40 000 caractères (étapes les plus anciennes
    abandonnées), rejeu limité aux **5 derniers tours** — au-delà, les tours sont rejoués en texte
    seul, comme avant F-39.
  - **`tool_use` et `tool_result` sont rejoués appariés** : le fournisseur refuse un appel orphelin.
    Une itération sans résultat exploitable est simplement omise.
  - `null` (tour sans outil, ou message antérieur à SF-39-03) ⇒ rejeu en texte seul : non-régression
    complète. Une valeur illisible retombe sur le même comportement, jamais une exception.
- **workspaces — frontière de rejeu du fil** (F-39 / SF-39-04, migration `051`). Colonne
  `chat_thread_started_at` (horodatage, **nullable**) : « repartir à neuf » **ne supprime aucun
  message**, il déplace une frontière — `GET /workspaces/{id}/chat` continue de renvoyer toute la
  conversation, seul l'historique **rejoué au fournisseur** démarre après la frontière. C'est ce qui
  rend le geste réversible. `null` = tout l'historique est rejoué, soit le comportement d'avant F-39
  pour tous les projets existants. Deux routes s'y adossent, toutes deux passant par `requireOwned` :
  `GET .../chat/resume` (état de reprise, `prompt = NONE|IDLE`, seuil d'inactivité **14 jours**,
  constante) et `POST .../chat/restart`.
- **workspaces — résumé de compaction du fil** (F-117 / SF-117-01, migration `107`). Colonne
  `chat_thread_summary` (texte, **nullable**) : quand le **texte rejoué** dépasse un seuil de sécurité
  **sous** la fenêtre du modèle (`app.atelier.compaction.trigger-tokens`, défaut 120 000, heuristique
  caractères/token), `AtelierCompactionService` **résume les tours anciens** (un appel modèle dédié,
  borné, sans outils, via `AiAgentProvider`, isolé `user_id`+`host_id`), garde les tours récents
  entiers, et **injecte ce résumé en tête** de ce qui repart au fournisseur — préfixe stable, donc
  cache de prompt préservé. La compaction **réutilise la frontière `chat_thread_started_at`** (posée
  automatiquement au premier tour récent gardé) : l'affichage garde tout, seul le rejeu est réduit.
  `null` = aucun résumé (comportement d'avant F-117) ; un « nouveau départ » l'efface. Le repli
  réactif (F-117 / SF-117-02) force une compaction et relance une fois quand le fournisseur refuse
  le contexte (400 « prompt too long », traduit en `AgentPromptTooLongException`), au lieu de tuer le
  tour. Consommation agrégée aux compteurs du tour (décompte d'usage existant).
- **Outillage de la boucle maison — aucune persistance** (F-39 / SF-39-05 et SF-39-06). La panoplie
  déclarée au modèle suit la **capacité de la cible** : en `RUNNER`, `read_file` / `write_file` /
  `edit_file` / `bash` (`list_files` et `search_files` retirés — `ls`, `find` et `grep -n` font
  mieux, et 95 % de l'usage réel mesuré est déjà du `bash`) ; en `SANDBOX`, la panoplie historique
  plus `edit_file`, car il n'y a pas de `bash` là-bas. La **déclaration** est retirée, pas la
  capacité : un `list_files` reçu malgré tout reste relayé. `read_file` rend des lignes **numérotées
  et paginées** (`offset`, `limit` ≤ 2 000) et `edit_file` remplace un passage **exact** — les deux
  calculés **côté gateway** à partir des primitives runner existantes, donc **sans évolution du
  protocole**. Une lecture **tronquée** fait refuser l'édition : réécrire un fragment détruirait la
  fin du fichier, en silence.
- **Transcription d'un tour de la boucle maison — aucune migration** (F-39 / SF-39-17). Le document
  d'affichage `terminal_json` (F-30 / SF-30-09) est désormais écrit **par les deux moteurs** : jusque-là
  seul le chemin **Managed Agents** le persistait, c'est-à-dire **pas celui qui exécute réellement**
  depuis F-38 — un rechargement ne rendait alors que la dernière ligne visible. **Aucune table, aucune
  colonne** : le format existe et l'isolation `user_id` vient de `atelier_messages`.
  - **Bornée à l'écriture** : 200 blocs par tour, 4 000 caractères par sortie, **la fin conservée** —
    c'est là que se trouvent le code de sortie et le message d'erreur ; garder le début mémoriserait la
    question sans la réponse. Même règle que la troncature de `tool_trace` (SF-39-03), et pour la même
    raison. Les blocs écartés sont **comptés et dits** à l'écran, jamais silencieusement perdus.
  - ⚠️ **Chaîne de délais, invariant d'exploitation** : `budget de tour (600 s) < flux SSE (900 s) ≤
    ingress (900 s)`. Un flux SSE reste **silencieux** entre deux événements (pendant un `npm install`,
    pendant que le modèle réfléchit) : un `proxy-read-timeout` plus court que le tour coupe la
    connexion alors que le travail continue côté serveur, et l'écran se fige **sans erreur**. Les
    annotations `nginx.ingress.kubernetes.io/proxy-read-timeout` et `proxy-send-timeout`
    (`k8s/base/ingress/ingress.yaml`) suivent `STREAM_TIMEOUT_MS` d'`AtelierChatController` : les
    changer **ensemble, ou pas du tout**. C'est la boucle qui doit rendre la main la première, en
    disant pourquoi.
  - **Journal serveur, sans contenu** : deux lignes `info` par tour (ouverture, fermeture avec la cause
    d'arrêt) — ni commande, ni sortie, ni chemin de fichier. Une ligne par itération noierait le journal.
- **Explorateur en panneau — aucune persistance** (F-39 / SF-39-18). L'explorateur de fichiers s'ouvre
  **dans** la vue Atelier via un **paramètre de requête**, jamais un segment de route : Angular détruit
  un composant quand la **route** change, et détruire `AtelierComponent` emportait le **flux SSE du tour
  en cours** — l'autorisation d'exécuter (F-33) partait alors dans un flux inexistant et la porte
  tranchait seule en **refus** au bout de `confirm-timeout`. La route dédiée `/atelier/:id/fichiers`
  **survit** (favoris, liens partagés), aucun terminal n'y étant monté. **Aucune table, aucune colonne,
  aucun changement serveur.**
- **atelier_messages — tour interrompu** (F-32 / SF-32-01). **Aucune migration** : la marque d'un tour
  arrêté par l'utilisateur vit dans le document d'affichage `terminal_json` déjà existant (F-30 /
  SF-30-09), sous un champ booléen **additif** `interrupted` — un tour antérieur, qui ne le porte pas,
  se relit exactement comme avant. Le tour interrompu **est persisté** (transcription partielle,
  sérialisée même si aucune commande n'a été lancée) et sa consommation **est décomptée** : il a
  réellement consommé du bac à sable. Écart assumé avec SF-30-09, qui ne persiste que les runs aboutis.
  L'interruption elle-même n'est **aucunement persistée** : `POST /workspaces/{id}/agent/interrupt`
  relaie `user.interrupt` à la session chez le fournisseur, qui s'arrête à une frontière sûre.
- **workspaces — coût facturé de la session** (F-36 / SF-36-02, migration `046`). Colonne
  `agent_list_cost (bigint, non nul, défaut 0)` : coût cumulé de la session en cours, en **unités
  mineures**, tel que le fournisseur le facture (tokens au tarif du modèle servi, recherches web,
  temps de bac à sable). Même rôle que `agent_input_tokens` / `agent_active_seconds` — le fournisseur
  rapporte un **cumul**, seul le **delta** est décompté, sinon la même dépense serait facturée à
  chaque tour. Remise à zéro à l'ouverture d'une session. **Aucune table nouvelle** ; à 0, le
  décompte retombe exactement sur celui d'avant F-36.
  - Le quota reste **libellé en tokens** : le coût est converti en équivalent tokens au tarif de
    référence (`app.atelier.agent.cost.cost-per-million-tokens`), multiplié par un **markup**
    configurable (`markup`, défaut **1,0 = neutre** — les allocations par plan portent déjà la marge).
  - Sans `list_cost` rapporté (ou illisible), **repli** sur le décompte des tokens bruts.
- **Plafond de dépense d'une session — aucune persistance** (F-36 / SF-36-01). Le budget
  (`budget.max_list_cost`) est calculé à l'**ouverture** — `min(quota restant converti, plafond par
  run)`, plancher configurable — et posé chez le fournisseur, qui l'applique en **verrou pré-requête**.
  Il n'est **ni stocké ni modifiable** : le fournisseur refuse d'ajouter un budget à une session déjà
  ouverte, donc une session ouverte avant F-36 n'en a pas (le quota post-run continue de s'y
  appliquer, et « Réinitialiser la sandbox » en rouvre une bornée). Un tour arrêté par ce plafond est
  marqué par un champ **additif** `budgetReached` dans le document `terminal_json` — même patron que
  `interrupted` (F-32) : le tour a eu lieu, il est décompté, et l'écran le dit.
- **Délégation à des sous-agents — aucune persistance** (F-35 / SF-35-01→03). **Aucune migration** :
  la délégation est un **réglage global** (`app.atelier.agent.subagents-enabled`, défaut **true**, et
  `max-subagents`, défaut **3**), pas une propriété de projet — rien à stocker par workspace. La
  capacité est celle du fournisseur (`agent_with_overrides.multiagent: {type: "coordinator", agents:
  [{type: "self"} …]}`) : la Gateway la **relaie** et n'ordonnance rien (Gateway-First).
  - **Bornée par le budget de session** (F-36) et non par un compteur propre : quand la session
    délègue, le plafond passe de `cost.max-run-cost` à `cost.max-run-cost-delegated`, **toujours**
    borné par le quota restant. Les sous-agents étant des **threads d'une même session**, un seul
    conteneur est facturé et le verrou pré-requête les borne tous à la fois.
  - **Relevé d'usage pris au niveau session** (`usage`, `stats.active_seconds`, `list_cost`) : il
    couvre déjà tous les fils. Ne jamais le passer au niveau d'un fil — ce serait sous-compter — ni
    additionner racine et fils — ce serait compter deux fois. Un test fige ce point.
  - **Provenance** : `thread_id` relayé dans le flux SSE (`action` / `action_result`) et persisté
    comme champ **additif** `threadId` du document `terminal_json` — même patron que `interrupted`
    (F-32) et `budgetReached` (F-36). `null` sur un run séquentiel : l'historique antérieur se relit
    exactement comme avant.
  - **Coupe-circuit** : `APP_ATELIER_AGENT_SUBAGENTS_ENABLED=false` — une variable d'environnement,
    sans redéploiement. À `false`, le corps de création de session est strictement celui d'avant F-35.
- **Diff des modifications d'un tour — aucune persistance dédiée** (F-37 / SF-37-01). Le diff unifié
  est calculé **à la resynchronisation**, seul instant où l'ancienne version (encore dans le stockage
  objet) et la nouvelle (téléchargée de la session) coexistent, puis relayé sur l'événement SSE
  `done` (champ **additif** `diffs`) et persisté comme clé **additive** `diffs` du document
  `terminal_json` — même patron que `interrupted` (F-32), `budgetReached` (F-36) et `threadId`
  (F-35). **Aucune table, aucune colonne, aucune migration.** Clé **absente** d'un tour sans
  modification : les tours antérieurs à F-37 se relisent exactement comme avant. Le calcul est écrit
  à la main (plus longue sous-séquence commune sur les lignes) et **borné avant de comparer** —
  préfixe et suffixe communs élagués, puis repli sans comparaison fine au-delà de 2 000 000 de
  cellules — pour qu'un fichier volumineux ne produise jamais de pic mémoire quadratique. Un fichier
  réécrit **à l'identique** est écarté du resync : une session persistante réexpose ses sorties, et
  l'annoncer comme modifié serait faux.
- **Instructions de projet — aucune persistance** (F-34 / SF-34-01). Le `CLAUDE.md` du workspace (repli
  `.atelier/instructions.md`) est lu **à l'ouverture de session** dans la source du projet — stockage
  objet pour un projet `ARCHIVE`, branche montée via l'API GitHub pour un projet `GIT` — et composé au
  prompt plateforme (`agent_with_overrides.system`, plateforme **en tête**). **Aucune table, aucune
  colonne, aucune migration** : les instructions vivent dans les fichiers du projet, et `instructionsPath`
  exposé par `GET /workspaces/{id}` est **dérivé de l'arborescence** déjà chargée, jamais stocké.

- **prompt_templates** — modèle de prompt réutilisable (F-13, migration `031`). Isolé par `user_id`.
  Donnée purement relationnelle, **sans colonne vectorielle** (F-13 n'est pas du RAG) — le backend
  reste une Gateway (aucun appel IA attaché à cette entité).
  - `prompt_templates` : `id (uuid)`, `user_id (uuid)`, `name (varchar 120)`,
    `category (AUDIT|REPORT|OTHER)`, `content (varchar 10000)`, `created_at`, `updated_at`. Index `user_id`.
  - Endpoints **`GET/POST /templates`**, **`GET/PUT/DELETE /templates/{id}`** (authentifiés, isolation
    `user_id` : un modèle d'autrui est indistinct d'un modèle inexistant → 404). Inclus dans l'export et
    la suppression RGPD (F-11).
- **processed_billing_events** — registre d'idempotence des événements de facturation traités (F-21 / SF-21-02,
  migration `033`). Table **purement technique** (aucune donnée utilisateur, aucun secret) : elle n'est **pas**
  filtrée par `user_id`, sa clé est globale au fournisseur.
  - `processed_billing_events` : `event_id (varchar, PK ; id d'événement fournisseur, ex. evt_...)`,
    `processed_at (timestamptz, défaut now())`.
  - Garantit qu'un rachat de tokens (top-up) n'est crédité **qu'une seule fois** même si le webhook Stripe est
    rejoué : `WebhookService` insère le marqueur (gate de contrainte PK) puis appelle
    `QuotaService.creditBonusTokens(userId, tokens)` dans la **même transaction** (montant de tokens autoritatif
    côté serveur via le catalogue `TopUpCatalog`, jamais depuis le payload). Endpoints top-up : **`GET /billing/topups`**,
    **`POST /billing/topup/checkout`** (authentifiés) ; crédit appliqué via le webhook signé **`POST /webhook/stripe`**.

- **governance_packages / governance_package_files** — le **catalogue publié** (F-51 / SF-51-01,
  migration `065`). Un **paquet de gouvernance** apporte quatre choses, et rien d'autre : des
  **règles** (texte ajouté à la consigne système du projet), des **contrôles** (identifiants de
  composants du serveur, branchés sur les crochets de F-50), des **gabarits**, des **skills** et,
  depuis F-92 / SF-92-01, des fichiers de **carte** — les trois derniers étant des fichiers déposés
  sur la machine. **Le genre décide du point de chute** : `SKILL` et `TEMPLATE` dans **chaque
  projet** du poste, `MAP` **une seule fois, à la racine du poste** — là où vit la carte, à côté des
  dossiers de projets.
  - `governance_packages` : `id (uuid)`, `slug (varchar 64, unique, immuable)`, `name (varchar 120)`,
    `summary (varchar 500)`, `rules (text)`, `control_ids (varchar 1000, liste à plat)`,
    `version (int)`, `published (boolean)`, `published_at`, `created_at`, `updated_at`. Index
    `(published)`.
  - `governance_package_files` : `id (uuid)`, `package_id (uuid)`, `sort_order (int)`,
    `path (varchar 255)`, `kind (varchar 16 : SKILL | TEMPLATE | MAP)`, `content (text)`,
    `generated (boolean, défaut true — F-96 / SF-96-01, migration 079)`,
    `known_digests (varchar 1300, nullable — F-96 / SF-96-02, migration 080)`, `created_at`.
    **`MAP` est arrivé sans migration** (F-92 / SF-92-01) : la colonne est un
    `varchar(16)` **sans contrainte de valeur**, et une valeur de plus n'est donc pas un changement
    de schéma.
    **`generated` est la déclaration d'ARTEFACT GÉNÉRÉ** : le produit a écrit ce fichier, il peut
    donc le **mettre à jour** — et **seulement là où il est resté exactement celui qui a été
    déposé** (voir `governance_deposited_files`). À `false`, le paquet pose le fichier une fois et
    n'y revient jamais. Le défaut est `true` sans danger : un fichier que l'utilisateur a touché
    **redevient du contenu utilisateur**, quelle que soit la déclaration.
    **`known_digests` est le registre des empreintes déjà publiées à ce chemin** — une par ligne,
    les plus récentes d'abord, bornées à 20. C'est la **deuxième** façon de reconnaître un artefact
    que personne n'a touché : son contenu est *mot pour mot* l'un de ceux que le produit a publiés
    ici. Sans lui, la mise à jour ne toucherait que les postes activés **après** F-96 — c'est-à-dire
    **pas** ceux qui portent la dette (F-95 a modifié le gabarit `STATE.md`, et les postes déjà
    activés gardent l'ancien). Le registre est **reporté** à travers le « efface puis réécrit » des
    deux chemins d'écriture (semeur et rédaction d'admin), et le contenu remplacé y entre
    automatiquement. Le produit livre en outre les empreintes de ce qu'il a publié **avant** F-96
    dans la ressource `governance/savoir-durable/empreintes-anterieures.txt`. Ce sont des
    **empreintes, pas des contenus** : le registre sert à reconnaître, jamais à restaurer, et un
    contenu non reconnu est **conservé**.
    Index `(package_id, sort_order)`. La colonne s'appelle `sort_order` et non `position` :
    `POSITION` est une fonction SQL standard, donc réservée pour H2.
  - **Pas de `user_id`, et c'est délibéré** : un paquet est un **contenu produit**, comme un plan
    tarifaire — il n'appartient au dossier de personne. L'écriture est réservée à l'admin
    (`AdminService.assertAdmin()`, F-20) ; la lecture publique est bornée aux paquets **publiés**.
  - **Un paquet ne porte pas de code.** Il cite des identifiants de contrôles, et la publication
    **refuse** un identifiant que le registre serveur ne connaît pas. Rien ne charge de script, ne
    lance de processus, ni n'évalue une source reçue : c'est la limite héritée de F-50, et elle écarte
    d'emblée la classe de failles qu'un catalogue de crochets ouvert introduirait.
  - Endpoints **`GET/POST /admin/governance/packages`**, **`PUT/DELETE /admin/governance/packages/{id}`**,
    **`POST /admin/governance/packages/{id}/publish|unpublish`**, **`GET /admin/governance/controls`**
    (ADMIN) et **`GET /governance/packages`** (JWT — catalogue publié, **sans** le contenu des
    fichiers).

- **governance_selections / governance_activations** — le **catalogue personnel** et son application
  (F-51 / SF-51-02, migration `066`). Deuxième étage du catalogue : l'admin publie, **chacun
  compose**.
  - `governance_selections` : `id (uuid)`, `user_id (uuid)`, `package_id (uuid)`,
    `default_applied (boolean)`, `created_at`, `updated_at`. Unicité `(user_id, package_id)`.
    Retenir un paquet **n'active rien** : c'est un geste de bibliothèque. Le seul automatisme est
    `default_applied`, qui fait embarquer le paquet par les projets **à venir** de cet utilisateur.
  - `governance_host_activations` — **l'activation, au grain du POSTE** (F-75 / SF-75-01, migration
    `072`) : `id (uuid)`, `user_id (uuid)`, `host_id (uuid)`, `package_id (uuid)`,
    `applied_version (int)`, `status (varchar 16 : PENDING | APPLIED)`, `applied_at`, `created_at`,
    `updated_at`. Unicité `(user_id, host_id, package_id)`.
    Tant que la ligne existe, les règles du paquet rejoignent la consigne système de **tous les
    projets du poste** et ses contrôles se branchent sur les crochets de F-50 (SF-51-04).
    **Pourquoi le poste et non le projet** : la gouvernance se pose **une fois au niveau de la
    machine** ; ce sont les *artefacts* qui sont par sujet (le `STATE.md` de chaque dossier, la dette
    de promotion). On active une fois sur un client, et **tout dossier ajouté demain sous sa racine
    en hérite**. **Aucune dérogation par dossier** (tranché par le PO le 2026-09-12) : une
    gouvernance qui se contourne au cas par cas cesse d'en être une.
    Le poste **« Hébergé »** (F-71) — les projets sans machine — est gouvernable par le mot réservé
    `hosted` ; en base, ses lignes portent la clé technique `00000000-0000-0000-0000-000000000000`,
    qui **ne sort jamais par l'API** (F-71 : ce poste est une vue, il n'a pas d'identifiant public).
  - `governance_activations` (migration `066`) — **vestige** de l'activation par projet. La table est
    **laissée en place, inchangée**, et la migration `072` en **reporte** le contenu sur le poste de
    chaque projet, dédoublonné, statut repris à `PENDING` (le grain du dépôt a changé : « appliqué »
    signifie désormais « en place dans **tous** les dossiers du poste »). Sa suppression appartient à
    un nettoyage ultérieur — garder l'original est ce qui rend la reprise réversible.
  - `applied_version` **fige** la version appliquée : un poste peut rester en v2 pendant que le
    paquet passe en v3. Rien ne met à jour un poste dans le dos de son propriétaire.
  - `governance_deposited_files` — **l'empreinte de ce qu'on a déposé** (F-96 / SF-96-01, migration
    `079`) : `id (uuid)`, `user_id (uuid)`, `host_id (uuid)`, `workspace_id (uuid)`,
    `package_id (uuid)`, `path (varchar 255)`, `digest (varchar 64)`, `package_version (int)`,
    `created_at`, `updated_at`. Unicité `(user_id, host_id, workspace_id, package_id, path)`.
    **Pourquoi elle existe** : le dépôt n'avait que deux issues — `CREATE` et `KEEP` — et ne gardait
    donc aucune trace ; un skill corrigé n'atteignait **jamais** un poste qui avait déjà l'ancienne
    version. Pour mettre à jour **sans jamais écraser du contenu utilisateur**, il faut répondre à
    une question que rien ne portait : *le fichier présent est-il celui que nous y avions mis ?*
    `governance_host_activations` retient une **version de paquet**, pas un contenu.
    **Une ligne par destination** — le même fichier n'a pas le même sort dans deux dossiers — et la
    **racine du poste** (la carte, F-92) porte la clé réservée
    `00000000-0000-0000-0000-000000000000` dans `workspace_id` : une colonne nulle ne dédoublonnerait
    rien sous PostgreSQL.
    **Une empreinte, pas une copie** : 64 caractères hexadécimaux (sha-256, **fins de ligne
    normalisées** — un runner Windows peut réécrire `\n` en `\r\n` sans que personne n'ait touché au
    fichier), dont on ne peut rien reconstituer. Le contenu de l'utilisateur ne quitte jamais sa
    machine.
    **L'absence d'empreinte n'autorise rien** : un fichier présent sans ligne ici est traité en
    contenu utilisateur — **conservé** —, et l'annonce le dit (`KEEP_LOCAL`, distinct de `KEEP` :
    *un fichier conservé parce qu'il a été modifié n'est pas la même chose qu'un fichier conservé
    parce qu'il était déjà bon*). Les lignes partent avec le poste supprimé.
  - `governance_map_growth` — **ce que la carte a gagné** (F-93 / SF-93-02, migration `078`) :
    `id (uuid)`, `user_id (uuid)`, `host_id (uuid)`, `path (varchar 512)`, `facts (int)`,
    `observed_at`, `first_facts (int)`, `first_seen_at`, `last_gain (int, nullable)`,
    `last_gain_at (nullable)`. Unicité `(user_id, host_id, path)`.
    **Une ligne par fichier de carte, jamais un journal** : la question tient en une phrase —
    « qu'est-ce que la carte a gagné, et quand ? » — et un journal d'observations grossirait sans fin
    pour la même réponse. `first_*` donne le point de départ, `facts`/`observed_at` l'état courant,
    `last_gain*` le dernier gain. **On constate, on ne croit pas sur parole** : ce qui est retenu est
    le **delta observé** du nombre de faits, jamais ce qu'un modèle a déclaré avoir promu — le delta
    vaut quelle que soit la main qui a écrit. Une **première** observation pose une référence et ne
    rend **aucun gain** (une carte déjà pleine le premier jour n'a rien gagné) ; une **diminution**
    met simplement le compte à jour (une carte qu'on élague a été rangée, pas appauvrie) ; une
    lecture **tronquée** n'écrit rien (un compte incomplet n'est pas un compte). L'écriture vit dans
    sa propre transaction et toute panne est absorbée : **un journal de croissance n'empêche jamais
    de lire une carte**.
  - **Isolation** : `user_id` est **en tête** de chaque index d'unicité et aucune méthode de
    repository n'existe sans lui — c'est une propriété des interfaces, pas une précaution des
    appelants. Le **poste** est en outre vérifié comme **possédé** avant toute écriture, et ses
    activations sont effacées avec lui. Supprimer un **dossier**, en revanche, n'éteint plus rien :
    la gouvernance appartient à la machine.
  - **Le dépôt ne détruit jamais rien** (SF-51-03) : il crée ce qui manque et **laisse tel quel** tout
    fichier déjà présent, contenu différent compris. La désactivation retire les règles et les
    contrôles, mais **laisse les fichiers** : ils appartiennent au projet dès qu'ils y sont.
  - Endpoints **`GET /governance/selection`**, **`PUT/DELETE /governance/selection/{packageId}`**,
    **`GET /governance/hosts`** (mes postes gouvernables), **`GET /governance/hosts/{hostRef}`**,
    **`GET /governance/hosts/{hostRef}/{packageId}/preview`** (l'annonce : ce qui sera écrit et où,
    **dossier par dossier**, **sans rien écrire**),
    **`GET /governance/hosts/{hostRef}/{packageId}/file?path=…`** (**lire avant d'accepter**,
    F-75 / SF-75-02 : le contenu apporté par le paquet, et ce que chaque dossier porte **déjà** sous
    ce chemin — puisque le dépôt n'écrase jamais, c'est l'existant qui restera. Seuls les chemins
    **apportés par le paquet** sont lisibles : une lecture de gouvernance, pas un explorateur de
    fichiers. Contenus bornés à 200 000 caractères et dossiers inspectés bornés à 20, coupe et
    omissions **annoncées**), **`POST /governance/hosts/{hostRef}/{packageId}`**,
    **`POST .../{packageId}/apply`**, **`DELETE .../{packageId}`** (JWT, accès Atelier).
    `hostRef` = identifiant d'un poste possédé, ou le mot réservé `hosted`. **Il n'existe plus aucune
    route d'activation par projet** (`/workspaces/{id}/governance**` retirée par F-75).

- **access_codes** — les **codes d'accès à durée limitée** (F-62 / SF-62-01, migration `067`). Un
  code émis par l'ADMIN ouvre à qui le saisit le **droit** d'accès à la Forge pendant 24 h.
  - `access_codes` : `id (uuid)`, `code_hash (varchar 64, unique)`, `label (varchar 120)`,
    `assigned_email (varchar 255, nullable)`, `granted_plan_code (varchar 32)`,
    `granted_space (varchar 16, nullable — FORGE / VIGIE ; nul = tous les espaces, F-107 / SF-107-04, migration 094)`,
    `duration_hours (int)`, `valid_until`, `created_by_user_id (uuid)`,
    `redeemed_by_user_id (uuid, nullable)`, `redeemed_at`, `granted_until`,
    `previous_plan_code (varchar 32)`, `previous_status (varchar 16)`, `created_at`, `updated_at`.
    Index `(redeemed_by_user_id, granted_until)`.
  - **Rien n'est écrit dans `subscriptions`.** Le code ouvre un droit **en surcouche**, à côté du
    plan, sans jamais l'écraser : `plan_code` appartient au webhook Stripe, et un code qui y
    écrirait finirait par changer ce qu'un client paie. C'est aussi ce qui fait qu'il n'y a **rien à
    restaurer** au terme — le plan précédent n'a jamais été quitté. Il est tout de même recopié dans
    `previous_plan_code` / `previous_status` comme **trace**, pour que l'administration lise « ce
    compte reviendra à SOLO » sans recouper deux tables.
  - **L'expiration est une comparaison, pas un événement** : passé `granted_until`, le droit se
    ferme. Aucun job planifié n'existe, délibérément — l'exigence est que le retour survienne « même
    si personne ne se connecte », et un cron peut ne pas tourner là où une comparaison ne le peut
    pas. L'état d'un code (`ISSUED` / `ACTIVE` / `ENDED` / `EXPIRED`) est **dérivé** de ses dates,
    jamais stocké, pour la même raison.
  - **Grâce de tour** (`app.access-code.grace-minutes`, défaut 15) : le contrôle d'accès à la Forge
    tolère le terme + grâce, parce qu'il est rejoué à chaque requête d'un tour (relances du runner,
    flux d'événements) et que fermer à la seconde exacte couperait un tour engagé. La grâce n'ajoute
    aucun jeton.
  - **Aucun quota n'est modifié** : comme l'option Forge de F-40, un code ouvre l'accès, il n'ajoute
    pas de tokens.
  - `code_hash` seul est stocké : le code en clair est renvoyé **une fois** à l'émission, jamais
    persisté ni journalisé.
  - **Isolation** : toute lecture d'un droit filtre sur `redeemed_by_user_id`, en tête de l'index ;
    aucune méthode de repository ne lit un droit sans le nommer. La liste complète est réservée à
    l'ADMIN (`AdminService.assertAdmin()`).
  - Endpoints **`POST/GET /admin/access-codes`** (ADMIN) et **`POST /access-code/redeem`**,
    **`GET /access-code/grant`** (JWT).

- **runner_update_journal** — les **mises à jour du runner** d'un poste : journal **et** état courant (F-111 /
  SF-111-04, migration `104`).
  - `runner_update_journal` : `id (uuid)`, `user_id (uuid, NOT NULL)` — le **propriétaire** du poste, jamais
    l'ADMIN qui a cliqué —, `host_id (uuid, NOT NULL, FK runner_hosts ON DELETE CASCADE)`, `requested_by (uuid,
    NOT NULL)`, `from_version (varchar 64)`, `to_version (varchar 64, NOT NULL)`, `forced (boolean, NOT NULL)`,
    `state (varchar 16 : REQUESTED | DOWNLOADING | WAITING | RESTARTING | SUCCEEDED | FAILED | ROLLED_BACK)`,
    `detail (varchar 500)`, `requested_at`, `updated_at`, `finished_at`. Index `(host_id, requested_at)`.
  - La dernière ligne d'un poste est son état de mise à jour (`active` = non terminale, nouvelles < 10 min).
    Écrite par `RunnerUpdateService` : à la demande (`POST /runner-hosts/{hostId}/runner-update`, propriétaire
    ou ADMIN), sur les trames `update_status` et `ready` du **poste de la session** runner.
  - Colonnes associées sur `runner_hosts` (migration `101`, SF-111-01) : `runner_contract`, `runner_java`,
    `runner_launcher`, `runner_capabilities` — ce que le runner déclare de lui-même dans `ready`.
- **mcp_personal_tokens / mcp_token_hosts / mcp_journal** — les **jetons personnels** MCP, l'**accès
  poste par poste** et le **journal MCP** (F-112 / SF-112-03, migration `105`). Tout est cloisonné par
  `user_id`.
  - `mcp_personal_tokens` : `id (uuid)`, `user_id (uuid, NOT NULL)`, `name (varchar 120, NOT NULL)`,
    `token_hash (varchar 64, NOT NULL, UNIQUE)` — **SHA-256** du secret, jamais le clair —,
    `token_prefix (varchar 24, NOT NULL)` — préfixe affichable `cgmcp_…` —, `scopes (varchar 500, NOT
    NULL)` — périmètres séparés par des espaces —, `created_at (NOT NULL)`, `expires_at (NOT NULL)` —
    **expiration obligatoire**, ≤ 90 j (imposé par le service) —, `last_used_at`, `revoked_at`. Index
    `(user_id, created_at)`. Écrites par `McpPersonalTokenService` ; le secret n'existe qu'à la
    création (rendu **une seule fois**).
  - `mcp_token_hosts` : `token_id (uuid, FK mcp_personal_tokens ON DELETE CASCADE)`, `host_id (uuid,
    FK runner_hosts ON DELETE CASCADE)`, PK `(token_id, host_id)` — les **postes accessibles** par un
    jeton (accès poste par poste ; un poste non listé n'est pas accessible).
  - `mcp_journal` : `id (uuid)`, `user_id (uuid, NOT NULL)`, `client (varchar 200)`, `token_id (uuid)`,
    `auth_kind (varchar 16 : OAUTH | PERSONAL, NOT NULL)`, `tool (varchar 120)`, `host_id (uuid)`,
    `params_summary (varchar 500)` — **les clés des paramètres, jamais leurs valeurs** (cadrage §6.6) —,
    `result (varchar 16 : OK | ERROR | DENIED, NOT NULL)`, `duration_ms (bigint)`, `created_at (NOT
    NULL)`. Index `(user_id, created_at)`. Écrit par un **décorateur d'outils** (`McpServerConfig`) via
    `McpJournalService`, best-effort ; lu par l'utilisateur sur l'écran « IA connectées ».
- **promotion_reportee** — la **promotion reportée faute de poste**, persistée (F-93 / SF-93-05,
  migration `106`). Quand la machine est hors ligne pendant un tour, un contrôle de fin de tour
  reporte la promotion au lieu de refuser trois fois une écriture impossible (SF-93-04) ; cette table
  garde ce report pour qu'il soit **réclamé une fois** au premier tour où le runner répond, **quel que
  soit le pod** et après un redémarrage — là où SF-93-04 le gardait en mémoire du processus.
  - `promotion_reportee` : `id (uuid)`, `user_id (uuid, NOT NULL)` — le **propriétaire** du projet —,
    `host_id (uuid, NOT NULL)` — le **poste** hors ligne —, `workspace_id (uuid, NOT NULL, FK
    workspaces ON DELETE CASCADE)`, `elements (text — PostgreSQL / varchar(1000000) — H2 : les
    éléments non rangés, à plat, un par ligne)`, `dette (int, NOT NULL, défaut 0)`, `reported_at (NOT
    NULL)`. Clé **unique `(user_id, host_id, workspace_id)`**, index `(reported_at)`.
  - Écrite/lue par `JpaPromotionReporteeStore` (bean `@Primary` de `PromotionReporteeStore` ; l'autre
    implémentation est en mémoire, pour les tests). Cumul (éléments sans doublon, dette maximale, date
    du premier report), **durée de vie 7 jours**, borne 500 entrées. **Réclamation atomique** : lecture
    sous verrou pessimiste puis suppression dans la même transaction (claim-once multi-pods).
- **runner_hosts** — le **poste** (F-48 / SF-48-01, migration `064`). Une machine connectée, avec
  **une racine**, **un runner** et **un seul appairage** ; les projets deviennent des dossiers sous
  cette racine. C'est le déplacement d'unité de F-48 : jusque-là, chaque dossier exigeait son code
  d'appairage, son runner et sa connexion — pour la même machine et le même utilisateur.
  - `runner_hosts` : `id (uuid)`, `user_id (uuid)`, `name (varchar 100)`, `root_name (varchar 255)`,
    `os (varchar 64)`, `shell (varchar 16)`, `elevated (boolean)`,
    `runner_version (varchar 64)`, `mission_status (varchar 16, NOT NULL, défaut ACTIVE)`,
    `last_seen_at`, `created_at`, `updated_at`. Index `(user_id)`.
  - `runner_version` (F-81 / SF-81-03, migration `076`) est la version du binaire que le runner
    **déclare** dans sa trame `ready`. Elle existe pour que « son runner est-il à jour ? » ait une
    **réponse** — rendue dans la vue d'ensemble du poste, et comparée à la version que la gateway
    distribue elle-même pour écrire une ligne de journal quand le poste est en retard. Elle ne
    **décide** de rien : aucun runner n'est refusé, dégradé ou arrêté sur sa valeur (hors périmètre
    absolu de F-81). Nulle tant qu'aucun runner ne s'est connecté.
  - `mission_status` (F-60 / SF-60-01, migration `067`) est l'état **métier** de la mission —
    `ACTIVE`, `PENDING`, `CLOSED` —, **déclaré par le propriétaire** et jamais déduit. Il est
    indépendant de l'état **technique** (« connecté »), qui se calcule : un poste éteint peut
    porter une mission active en pause, un poste connecté une mission close qu'on n'a pas rangée.
    Le déclarer **ne coupe rien** : ni jeton, ni liaison, ni rattachement de projet, ni journal —
    le coupe-circuit reste `POST /runner-hosts/{id}/kill`. `GET /runner-hosts/overview` rend
    **tous** les postes, clôturés compris : le rangement est une affaire d'écran.
  - Tout ce que la gateway sait de la machine est **déclaré par le runner**, jamais deviné :
    `root_name` n'est que le **dernier segment** de la racine, jamais le chemin absolu. Ces trois
    colonnes viennent de `workspaces` (migrations `052`, `053`, `063`) : elles décrivaient une
    machine, pas un projet.
  - `workspaces` gagne `host_id (uuid, nullable)` et `project_path (varchar 512)` — le chemin du
    projet **relatif à la racine du poste**, chaîne vide pour la racine elle-même.
  - `workspaces` gagne aussi `host_terminal (boolean, non nul, défaut false)` — **F-74 / SF-74-01,
    migration `074`** : vrai pour le **terminal du poste**, la ligne qui n'est pas un projet mais
    le terminal de la machine, posé à sa racine. Un terminal comme les autres **parce que c'est le
    même objet** : la conversation, le fil, la session, la porte de confirmation, le journal, le
    registre des terminaux vivants (F-70), l'usage par tour (F-61) et l'héritage de gouvernance
    (F-75) pendent tous à `workspace_id` et s'appliquent sans une ligne de code de plus. Une table
    dédiée aurait exigé un second chemin pour chacun. Le booléen à `false` par défaut laisse toute
    ligne antérieure et toute lecture existante justes par construction. `listByHost` — donc la
    carte du poste, le contrôle de doublon de F-72 et la garde de suppression de F-69 — rend **les
    projets**, terminal exclu ; et supprimer le poste emporte son terminal.
  - `workspaces` gagne enfin `teams_terminal (boolean, non nul, défaut false)` — **F-89 / SF-89-01,
    migration `078`** : vrai pour le **terminal Teams** du poste, celui où l'on parle de réunions et
    de conversations. **Un second booléen, et non une énumération `kind`** : un `kind` obligerait à
    relire toutes les requêtes existantes pour y ajouter `kind = 'PROJECT'`. **Et non `host_terminal`
    réutilisé** : le terminal du poste ouvre un shell à la racine, celui-ci n'en parle jamais et
    affiche des blocs qu'un terminal de projet n'affichera **jamais** — les confondre ferait
    apparaître des cartes de réunion dans un terminal qui doit rester textuel. Mêmes exclusions que
    le terminal du poste (`listByHost`, suppression avec la machine). C'est **ce drapeau** qui décide
    si l'agent reçoit les outils `teams_*` — avec le droit Teams — et si le fil sait afficher autre
    chose que du texte.
  - **Droit du volet Teams (F-89 / SF-89-01, décision D5 du cadrage)** : `SpaceEntitlementService`,
    espace `VIGIE` (F-107 / SF-107-02 — anciennement `TeamsEntitlementService`), l'ouvre dans exactement deux cas — **option Teams** en cours sur un plan mensuel lui-même en
    cours (`SOLO`, `PRO`, `GOLD`, `BYOK` ; `DAILY` exclu), ou **accès offert** (F-62) en cours.
    **Aucun plan n'inclut Teams** — c'est une option, et rien d'autre —, et **l'option ouvre l'accès
    sans ajouter un jeton** : aucun quota n'est lu ni modifié. Le **montant et le parcours d'achat
    restent à confirmer par le PO** ; aucune colonne de tarif n'est créée.
  - **Images des moments (F-89 / SF-89-02)** : hors base, dans l'abstraction `WorkspaceStorage`,
    sous `teams-moments/{userId}/{workspaceId}/{imageId}.{png|jpg|webp}`. **L'isolation est dans la
    clé** — reconstruite depuis l'utilisateur authentifié, l'identifiant du client n'étant qu'un
    dernier segment — et elles **s'effacent avec le terminal** (décision D2 : les textes et images
    vivent avec le compte rendu). Les **blocs riches** eux-mêmes ne créent aucune table : ils voyagent
    dans `atelier_messages.terminal_json`, qui porte déjà le relevé de tour.
  - **Un poste appartient à un seul utilisateur** : ce n'est pas F-17 (espaces d'équipe, V3), rien
    n'est partagé entre comptes, l'isolation `user_id` reste la règle.
  - Endpoints **`POST/GET /runner-hosts`**, **`GET/PUT/DELETE /runner-hosts/{id}`**,
    **`POST /runner-hosts/{id}/pairing-code|kill`**, **`GET /runner-hosts/{id}/tokens|status`**,
    **`DELETE /runner-hosts/{id}/tokens/{tokenId}`**, **`POST /runner-hosts/{id}/terminal`**
    (F-74 : retrouve ou crée le terminal du poste, **idempotent**, `200`) (JWT, accès Atelier), et
    **`PUT /workspaces/{id}/host`** pour rattacher un projet. Volontairement **hors** du préfixe
    `/runner/**`, qui est la chaîne du protocole runner et refuse tout ce qui n'y est pas listé.
  - **Table rase** (décision du PO, 2026-09-10) : la migration `064` **vide** les trois tables runner
    avant de changer de clef — aucune reprise de données, aucune colonne de transition, aucune double
    lecture. Le seul coût est un ré-appairage.

- **live_terminals** — la **place de terminal vivant** (F-70 / SF-70-01, migration `071`). Une
  ligne = **un onglet de terminal ouvert**, qui tient sa place en la renouvelant (~30 s). Le PO a
  tranché **quatre flux réellement vivants au maximum**, refus explicite au cinquième : quatre flux
  vivants, ce sont **quatre consommations simultanées** — le plafond est un garde-fou de dépense,
  pas une contrainte technique.
  - `live_terminals` : `id (uuid)`, `user_id (uuid, NOT NULL)`, `workspace_id (uuid, NOT NULL)`,
    `session_id (varchar 64, NOT NULL)`, `opened_at`, `last_seen_at`, et — depuis F-76 / SF-76-01,
    migration `075` — l'**aperçu vivant** : `activity (varchar 24)`, `activity_detail (varchar 120)`,
    `preview_lines (varchar 1024)`, `activity_at`. Index **unique** `(user_id, session_id)`, index
    `(user_id, last_seen_at)`.
  - **L'aperçu (F-76)** dit **ce que le terminal fait** — `IDLE` / `THINKING` / `RUNNING` /
    `AWAITING_APPROVAL`, le détail (« npm test ») et ses **dernières lignes**. Il voyage avec le
    **battement de cœur** qui existe déjà : pas d'endpoint de plus, pas de canal de plus, une
    écriture sur une ligne qui est déjà là. Bornes tenues **au serveur** (6 lignes, 160 caractères,
    détail 120) et séquences ANSI retirées — une borne tenue par l'appelant n'est pas une borne.
    **Aucun index** : ces colonnes ne sont jamais un critère de lecture. Ce n'est **pas** un
    historique : la fiche porte le **dernier** aperçu, ce qu'un tour a produit vit dans
    `atelier_messages`. Il meurt avec la place, donc avec l'onglet.
  - **Pourquoi une table et pas un registre en mémoire** : sous HPA, un compteur en mémoire ne
    verrait que les terminaux du pod qui répond, et un garde-fou de dépense qui ne compte qu'un
    replica n'en est pas un. La table est lue par tous les pods et ne passe **pas** par le relais
    inter-pods (SF-38-12/13).
  - **`last_seen_at` est ce qui rend le plafond sûr** : un onglet fermé brutalement n'envoie aucune
    libération ; sa place expire seule au bout du délai de grâce (`PT90S` par défaut, borné à
    [30 s, 10 min]). Sans elle, quatre fermetures brutales condamneraient le compte.
  - **Aucune clé étrangère** vers `workspaces` ni `users` : cohérent avec le reste du domaine
    runner. La purge est explicite (`deleteByUserId` dans `AccountService`), et une place qui
    désignerait un projet disparu n'est jamais rendue nommée (le nom vient d'une lecture
    **par propriétaire**, comme en SF-49-03).
  - Endpoints **`POST/DELETE /workspaces/{id}/terminal/live`** (prendre-ou-tenir / libérer) et
    **`GET /terminals/live`** (JWT). Refus : **409 `terminal_limit_reached`**.
  - Chaque entrée de `GET /terminals/live` porte `teamsTerminal` (F-89 / SF-89-07, **additif**) :
    lu sur les projets **du propriétaire** (`workspaces.teams_terminal`), `false` pour un projet non
    résolu. La mosaïque en peint la tuile de la surface Teams.
  - `GET /runner-hosts/overview` gagne `liveTerminals` (par poste) et `liveTerminal` (par projet),
    champs **additifs** : la vue d'ensemble lit le registre **une fois** par appel.

- **runner_pairing_codes / runner_tokens** — identité du runner (F-38 / SF-38-01, migration `047` ;
  clef passée de `workspace_id` à `host_id` par F-48 / SF-48-01, migration `064`).
  Deux tables neuves. Le **runner** est un second type de porteur d'identité, authentifié par jeton
  et non par JWT utilisateur ; il ouvre (SF-38-02) une connexion sortante pour exécuter les outils de
  l'agent sur une machine connectée. **Aucun secret en clair** : seul le `SHA-256 (hex)` du code
  d'appairage et du jeton est stocké.
  - `runner_pairing_codes` : `id (uuid)`, `user_id (uuid)`, `host_id (uuid)`, `code_hash (varchar 64)`,
    `expires_at`, `consumed_at`, `created_at`. Index `code_hash`. Code court, TTL 5 min, usage unique,
    **un seul par machine**.
  - `runner_tokens` : `id (uuid)`, `user_id (uuid)`, `host_id (uuid)`, `token_hash (varchar 64, unique)`,
    `label (varchar 100)`, `expires_at`, `revoked_at`, `last_seen_at`, `created_at`. Index `(user_id, host_id)`.
    TTL 30 j, révocable. Isolation `user_id` sur toutes les lectures/gestions.
  - `RunnerIdentity` vaut `(tokenId, userId, hostId)` : le **projet** ne fait plus partie de
    l'identité, il voyage **par appel** dans le champ `project` de la trame `tool_call`, et donne au
    runner le **dossier de départ** du tour (F-48 / SF-48-02). **Ce n'est plus une borne depuis
    F-73 / SF-73-01** : le confinement a été retiré partout, parce qu'il n'existait déjà pas pour
    `bash` (seul le `cwd` passait par la garde, jamais la commande). Ce qui s'interpose est la porte
    de confirmation, le journal d'audit, le coupe-circuit — et ce que l'application **dit** (ADR-019).
  - Endpoints **`POST /workspaces/{id}/runner/pairing-code`**, **`GET/DELETE /workspaces/{id}/runner/tokens`**
    (JWT, gardés par l'accès Atelier Gold/ADMIN) et **`POST /runner/pair`** (sans JWT : le code d'appairage
    est la credential), ce dernier servi par une **chaîne de sécurité Spring dédiée** `@Order(1)`
    `securityMatcher("/runner/**")` — la chaîne principale reste inchangée (ADR-016).

- **workspaces — cible d'exécution** (F-38 / SF-38-05, décision D1, migration `048`). Le workspace
  d'Atelier gagne une **cible d'exécution** : `SANDBOX` (les outils s'exécutent dans le bac à sable du
  fournisseur ou sur le stockage objet — comportement historique) ou `RUNNER` (les outils s'exécutent
  sur la machine de l'utilisateur, via le canal de SF-38-02 — WebSocket, ou long-polling HTTP en repli
  depuis SF-38-09). Strictement **symétrique de la
  source `ARCHIVE`\|`GIT`** de la migration `043`. **Aucune table nouvelle** : une colonne de dimension
  ajoutée à `workspaces`.
  - Colonne : `execution_target (varchar 16, défaut `SANDBOX`, non nul)`. Le `defaultValue` explicite
    laisse **toutes les lignes existantes dans le comportement d'avant F-38**. Réversible (`dropColumn`).
    **Aucun secret** : le jeton runner vit haché dans `runner_tokens`.
  - En cible `RUNNER`, `AtelierChatService.runLoop` route ses **quatre outils fichiers** (`list_files`,
    `read_file`, `write_file`, `search_files`) vers le runner de l'utilisateur au lieu du stockage objet,
    et expose en plus l'outil **`bash`** (SF-38-07) — jamais en cible `SANDBOX`. Les **Managed Agents sont
    refusés** dans ce mode (D2 : ils exécutent chez Anthropic, impossible à rerouter) → `409
    execution_target_runner`. Le garde-fou « projet Git en lecture seule » ne vaut plus que pour
    `SANDBOX` : un projet `GIT` + `RUNNER` est légitime (le dépôt est cloné sur la machine).
  - **`workspaces.agent_ask_before_bash`** (migration `044`, F-33 ; défaut inversé par la migration
    `073`, F-73) : **`true` par défaut**, et le passage en cible `RUNNER` **n'y touche pas**. La
    décision D7 de SF-38-08 forçait la colonne à `true` à chaque bascule et en refusait la
    désactivation (`409 execution_target_runner`) ; SF-38-20 a rouvert la désactivation, **F-47 /
    SF-47-04** a retiré le forçage et mis le défaut à `false` (ADR-018), puis **F-73 / SF-73-02** a
    **réarmé le défaut** (ADR-019) : ce `false` avait été pris quand le confinement du runner
    paraissait exister — il n'existait pas pour `bash`, et il est retiré. Le forçage à la bascule,
    lui, **reste retiré** : réarmer dans le dos de qui a éteint resterait réarmer dans son dos. La
    porte reste **réglable par projet** ; le **journal d'audit** et le **coupe-circuit** restent non
    désactivables. Le coupe-circuit `POST /workspaces/{id}/runner/kill` **ramène la cible à
    `SANDBOX`**, sans modifier ce réglage.
  - **Limite connue, assumée par le PO (ADR-019)** : cette porte ne couvre que `bash`. Les quatre
    outils fichiers ne demandent rien — un `read_file` sur un `.env` ou `~/.ssh/id_rsa` part chez le
    fournisseur dans le contexte du tour, et **plus aucune exclusion de secrets** ne s'y oppose côté
    runner (`ExclusionRules.DEFAULT_DENY` supprimée ; le filtre restant n'élague que le listage).
  - Endpoint **`PUT /workspaces/{id}/execution-target`** (JWT, accès Atelier, `requireOwned` d'abord :
    **404** sur le workspace d'autrui, **400** sur valeur inconnue) ; `executionTarget` est exposé en champ
    **additif** dans le détail et la liste des workspaces.
  - **Limite de production tracée** : le routage n'utilise que `findLocal()` (la socket runner doit vivre
    sur le pod qui tient le tour) et la porte de confirmation est en mémoire — le mode `RUNNER` suppose
    un **replica unique ou une affinité d'ingress**. `NOTIFY` (plafonné à 8 000 octets) ne peut pas
    relayer du contenu de fichier entre pods.

- **runner_audit** — journal d'audit du runner (F-38 / SF-38-08, décision D11, migration `049`).
  Table neuve, **une ligne par appel d'outil terminé** sur la machine de l'utilisateur et par appel
  **refusé avant émission** (validation d'action). Clef de corrélation `call_id` = l'identifiant
  `tool_use` du fournisseur : la même clef relie la trame WebSocket, l'événement SSE de confirmation
  et la ligne d'audit.
  - `runner_audit` : `id (uuid)`, `user_id (uuid)`, `workspace_id (uuid, **nullable** depuis F-48)`,
    `host_id (uuid, nullable)`, `token_id (uuid, nullable)`,
    `call_id (varchar 64)`, `tool (varchar 32)`, `target (varchar 1000)`, `outcome (varchar 16)`,
    `error_code (varchar 32)`, `exit_code (int)`, `duration_ms (bigint)`, `bytes (bigint)`,
    `created_at`. Index `(user_id, workspace_id, created_at)` et `(user_id, host_id, created_at)`.
  - Le journal **conserve le projet** (F-48 / SF-48-01) : il doit continuer de dire *quel projet* a
    exécuté quoi, même si le runner appartient désormais à une machine. `workspace_id` devient
    nullable parce que certains gestes visent la machine entière — le coupe-circuit, par exemple ;
    les rattacher à un projet arbitraire serait un mensonge dans le seul document censé dire la
    vérité.
  - **Ce que la table ne contient jamais** : aucun contenu de fichier, aucune sortie de commande,
    aucun message d'erreur du runner (un message peut porter un fragment de chemin de la machine ;
    un code d'erreur, jamais). Les lectures d'amorçage de la consigne système sont **agrégées en une
    seule ligne** (`tool = bootstrap`) plutôt qu'une par fichier.
  - Endpoints **`GET /workspaces/{id}/runner/audit`** (journal, `limit` borné à `[1..200]`),
    **`POST /workspaces/{id}/runner/kill`** (coupe-circuit : révocation de tous les jetons, coupure
    de la liaison, retour en cible `SANDBOX`) et **`POST /workspaces/{id}/chat/confirm`** (réponse à
    une demande d'autorisation de la boucle Assistant) — JWT, gardés par l'accès Atelier.
    L'écriture d'audit est **hors transaction et non bloquante** pour la boucle tool-use.

- **runner_diag_events** — journal de **diagnostic** du runner (F-132 / SF-132-02, migration `114`).
  Table neuve, **un événement de diagnostic structuré et expurgé par ligne**, remonté par le runner
  dans la trame `runner_diag` (SF-132-01) sur le WebSocket existant. On y range des **formes et des
  états** — état du Chrome managé (`REACHABLE`/`LAUNCHED`/`UNREACHABLE`/`NO_BROWSER`), verdict de la
  sonde Teams, cycle de vie de la capture (tailles : octets audio, nb images), ticks de la Vigie,
  erreurs (type + message court). Distinct de `runner_audit` (qui trace les appels d'outils) : ici
  c'est la **plomberie** Vigie/Teams, invisible du serveur jusqu'ici.
  - `runner_diag_events` : `id (uuid)`, `user_id (uuid, NOT NULL)`, `host_id (uuid, NOT NULL)`,
    `level (varchar 8 — DEBUG/INFO/WARN/ERROR)`, `category (varchar 32)`, `code (varchar 64)`,
    `message (varchar 500, nullable)`, `fields (varchar 2000, nullable — JSON compact de scalaires)`,
    `observed_at (timestamptz, nullable — horloge runner)`, `created_at`. Index
    `(user_id, host_id, created_at)` (isolation + tri) et `(created_at)` (purge TTL).
  - **Ce que la table ne contient jamais** (invariant, poste **client/banque**) : aucun secret,
    aucune URL brute (seulement sa **classe**), aucun chemin sensible, aucun contenu Teams —
    l'**expurgation est faite à la source** (runner, SF-132-01). La gateway borne en plus (longueurs).
  - **Stockage borné** : **anneau par poste** (2000 lignes, les plus anciennes supprimées au-delà) +
    **TTL 7 jours** (purge planifiée nocturne, désactivable). Négligeable sur la RDS partagée.
  - **Isolation** : `user_id` et `host_id` viennent de la **session** runner (`RunnerIdentity`),
    jamais d'un champ du message. Toute lecture filtre sur les deux.
  - Endpoint **`GET /runner-hosts/{hostId}/diag`** (JWT, accès runner + poste possédé → **404**
    sinon ; filtres `level`/`since`/`until`, `limit` borné à `[1..500]`). Lisible par l'assistant
    **via la base** (comme `runner_audit`). L'ingestion est **hors du fil runner et non bloquante**.

- **atelier_permission_rules** — politique de permission des outils de la boucle maison (F-121 /
  SF-121-02, migration `075`). Table neuve, **une ligne par règle** allow/ask/deny persistée par
  workspace/utilisateur — c'est ce qui donne au modèle de permission une mémoire qui survit au tour et
  au redémarrage, là où la porte de confirmation d'avant SF-121-02 repartait de zéro à chaque message.
  - `atelier_permission_rules` : `id (uuid)`, `user_id (uuid)`, `workspace_id (uuid)`,
    `tool (varchar 32)`, `command_prefix (varchar 512, nullable)`, `effect (varchar 8 : ALLOW|ASK|DENY)`,
    `created_at`. Index `(user_id, workspace_id)`.
  - `command_prefix` n'a de sens que pour `bash` : c'est le préfixe de commande couvert par la règle
    (« toujours autoriser cette commande » écrit le **premier mot** de la commande). `null` = règle qui
    porte sur **tout l'outil** (par exemple « toujours autoriser edit_file »). Pour `bash`, une règle de
    préfixe l'emporte sur une règle d'outil, et le préfixe le plus long gagne.
  - **Résolution** : au défaut d'une règle, la boucle retombe sur son comportement d'avant — `bash`
    demandé selon `agent_ask_before_bash`, une **édition** selon `app.atelier.ask-before-edit`
    (défaut `false`), le reste exécuté. Isolation `(user_id, workspace_id)` sur toute lecture/écriture.
  - Purge : à la suppression du **compte** (`AccountService`) et du **projet** (`WorkspaceService`).

- **atelier_deposited_files** — fichiers déposés dans un terminal (F-115 / SF-115-01, migration `108`).
  Table neuve, **une ligne par fichier déposé** (glisser / coller / trombone), en attente d'un tour :
  c'est ce qui donne au dépôt une mémoire entre le geste de l'utilisateur et le tour suivant, dont la
  consigne portera le **chemin** (SF-115-03) — jamais le binaire.
  - `atelier_deposited_files` : `id (uuid)`, `user_id (uuid)`, `workspace_id (uuid)`,
    `path (varchar 1024)`, `size_bytes (bigint)`, `created_at`, `consumed_at (nullable)`. Index
    `(user_id, workspace_id)`.
  - `path` est le chemin **relatif** où l'agent lira le fichier : `entrees/<nom>` (workspace hébergé,
    octets bruts en S3) ou `.atelier/entrees/<nom>` (poste, écrit par le runner en transfert découpé
    via le nouvel outil `write_file_bytes`). Le « jamais hors de `entrees/` » est garanti **côté
    gateway** (nom assaini + préfixe fixe + `normalizePath` refusant `..`) ; `PathResolver` du runner
    ne confine pas (décision PO 2026-09-12). `consumed_at` vaut `null` tant qu'aucun tour n'a porté le
    chemin dans sa consigne.
  - Bornes du dépôt (réglables `app.atelier.deposit.*`) : 8 Mio hébergé, 100 Mio poste, 20 fichiers ;
    coupe-circuit `RunnerLiveness` (poste hors ligne → refus nommé). Isolation `(user_id, workspace_id)`
    sur toute lecture ; purge à la suppression du **projet** (`WorkspaceService`).

- **poste_billing** — le **TJM par poste** (F-124 / SF-124-01, migration `109`). Table neuve, **au plus
  une ligne par poste** `(user_id, host_id)` : le taux journalier (€ HT/jour) que l'utilisateur facture
  au client installé sur cette machine. C'est la brique de configuration du suivi de revenu ; le
  **cumul** (jours × TJM) est calculé à la volée (SF-124-02), il n'est pas stocké.
  - `poste_billing` : `id (uuid)`, `user_id (uuid, FK users ON DELETE CASCADE)`, `host_id (uuid, FK
    runner_hosts ON DELETE CASCADE)`, `daily_rate_cents (bigint)`, `created_at`, `updated_at`. Index
    **unique** `(host_id)` et index `(user_id, host_id)`.
  - Le montant est tenu en **centimes** — jamais un flottant en base ; borné à 1 000 000 € HT/jour
    (`PosteBilling.MAX_DAILY_RATE_CENTS`), jamais négatif. Isolation par `requireOwned` avant toute
    écriture ; purge garantie par les FK `ON DELETE CASCADE` (ni le poste ni le compte ne laissent de
    TJM orphelin), comme `host_mail_addresses`.

- **activity_settings** — le **réglage de suivi d'activité** d'un utilisateur (F-124 / SF-124-01,
  migration `109`). Table neuve, **au plus une ligne par `user_id`**. Aujourd'hui un seul champ : le
  **mois de départ** du cumul. Absente, le défaut applicatif vaut `2025-09` — le cumul se lit toujours.
  - `activity_settings` : `id (uuid)`, `user_id (uuid, FK users ON DELETE CASCADE)`,
    `start_month (varchar 7, 'YYYY-MM')`, `created_at`, `updated_at`. Index **unique** `(user_id)`.

- **cra_entries** — le **CRA déclaré** (F-124 / SF-124-02, migration `110`). Table neuve, **au plus une
  ligne par `(user_id, host_id, year_month)`** : les jours travaillés déclarés sur un poste pour un
  mois. **Seuls les CRA déclarés sont stockés** ; le « supposé » (mois complet automatique) est calculé
  à la volée par `RevenueService` (aucune ligne — l'absence est signifiante). La table est **l'entrée du
  calcul du cumul** : créée et **lue** en SF-124-02 ; son chemin d'**écriture** (extraction IA du message
  NL par `CraService`/`CraController`, `POST /activity/cra`) est livré en **SF-124-03** — le modèle
  extrait via l'interface `AIProvider` (Provider-First), la Gateway rapproche le nom à un poste possédé
  (inconnu → demandé, jamais deviné), valide (jours ≤ jours ouvrés, 0,5, mois courant par défaut) et
  persiste. Renvoyer un CRA pour un mois **écrase** l'ancien (unicité sur la clé).
  - `cra_entries` : `id (uuid)`, `user_id (uuid, FK users ON DELETE CASCADE)`, `host_id (uuid, FK
    runner_hosts ON DELETE CASCADE)`, `year_month (varchar 7, 'YYYY-MM')`, `days (numeric(4,1)` —
    demi-journées admises), `created_at`, `updated_at`. Index **unique** `(user_id, host_id, year_month)`
    et index `(user_id, host_id)`.
  - **Le calcul du cumul** (`RevenueService`, SF-124-02), par (poste avec TJM, mois) du mois de départ
    au mois courant : CRA déclaré → jours déclarés (**déclaré**) ; sinon mois passé → jours ouvrés du
    mois (lun–ven hors fériés France, calculés — `WorkdayCalendar`/`FrenchHolidays`, **supposé**) ; sinon
    mois courant non déclaré → **0** (un mois inachevé ne gonfle pas le total). Montants en centimes.
    `GET /activity/revenue` rend par poste `{tjmCents, cumulCents, declaredCents, supposedCents}` et les
    totaux tous clients. Aucun appel fournisseur, aucun quota consommé.

- **meetings** — l'**artefact « réunion »** (F-128 / SF-128-01, migration `111`). Table neuve : une
  réunion Teams rejointe et capturée depuis un poste, isolée `(user_id, host_id)`. Le bouton
  **« Rejoindre & capturer »** de la Vigie crée la ligne et ordonne au runner d'ouvrir l'URL **dans le
  Chrome managé** (F-122, tool `teams_meeting_join` — hors catalogue agent, appelé directement par
  `TeamsMeetingService` via `RunnerToolGateway.teamsRead`). **Gateway-First** : le backend orchestre, il
  ne capture ni ne transcrit. **DRAPEAU SF-128-01** : les octets média (audio onglet + micro) sont
  capturés en SF-128-02 ; ici `state=RECORDING` signifie « session ouverte / onglet rejoint ». La purge
  active de la rétention est SF-128-07 (ici la durée est seulement **stockée**).
  - `meetings` : `id (uuid)`, `user_id (uuid, FK users ON DELETE CASCADE)`, `host_id (uuid, FK
    runner_hosts ON DELETE CASCADE)`, `subject_id (uuid, nullable — pointeur vers radar_subjects, sans
    FK, même choix que le registre du Radar)`, `title (varchar 300, nullable)`, `meeting_url (varchar
    2048)`, `state (varchar 20 — RECORDING/PAUSED/STOPPED/FAILED)`, `consent_acknowledged (boolean)`,
    `retention_days (int, défaut 30, borne applicative [1;365])`, `capture_ref (varchar 200, nullable)`,
    `audio_key (varchar 300, nullable — SF-128-02, migration 112)`, `audio_bytes (bigint, nullable)`,
    `image_count (int, nullable — SF-128-03, migration 113)`,
    `started_at`, `ended_at (nullable)`, `created_at`, `updated_at`. Index `(user_id, host_id,
    started_at)`. Endpoints `/api/vigie/hosts/{hostId}/meetings` (create/stop/pause/resume/list/get),
    gardés par le droit Teams + possession du poste + activation Vigie.
  - **Capture par onglet (Option A, §2bis)** : « Rejoindre & capturer » ordonne au runner
    `teams_meeting_join` (navigue l'onglet Teams du Chrome managé vers l'URL), puis
    `teams_meeting_capture_start` (SF-128-02 : audio onglet + micro mixés, script injecté piloté CDP) ;
    l'arrêt ordonne `teams_meeting_capture_stop` qui remonte l'**audio** (`POST /runner/teams/meetings/{id}/audio`)
    puis les **images clés** du partage (`POST /runner/teams/meetings/{id}/images`) — jeton runner
    (`X-Runner-Token`), isolation re-vérifiée par le contrôleur (`user_id` du jeton + `host_id` du
    terminal Teams possédé + réunion résolue par le triplet). Le média est stocké en objet
    (`teams-meetings/{userId}/{hostId}/{meetingId}/…`), jamais la vidéo pleine. Ces tools runner sont
    **hors du catalogue agent** (commandes d'orchestration de la Vigie, pas des outils du modèle).
    **DRAPEAU** : la capture navigateur est validée sur call réel ; STT (SF-128-04) et exploitation
    (SF-128-05) restent à venir.

- **Repli de transport du runner — aucune table** (F-38 / SF-38-09). Le canal runner peut être porté
  par le WebSocket de SF-38-02 **ou** par un long-polling HTTP quand un proxy refuse (ou coupe)
  l'`Upgrade`. **Aucune migration, aucune colonne, aucun type de message nouveau** : les deux
  transports portent les mêmes enveloppes et s'enregistrent avec le **même** record `RunnerConnection`
  (nodeId du pod), de sorte que `GET /workspaces/{id}/runner/status`, `findLocal()` et le routage des
  appels d'outils sont identiques quel que soit le tuyau.
  - Trois endpoints supplémentaires sur la **chaîne dédiée** `/runner/**` (D9, `@Order(1)`) :
    **`POST /runner/poll`** (long-poll ≤ 25 s), **`POST /runner/send`** (une trame ou un lot),
    **`POST /runner/disconnect`**. Ils sont **`permitAll` dans la chaîne runner uniquement** (elle se
    termine par `anyRequest().denyAll()`) et le contrôleur **authentifie lui-même** l'en-tête
    `X-Runner-Token` : refus en **401 générique**, et **jamais** d'`AuthenticatedUser` posé dans le
    `SecurityContext` — un jeton runner n'ouvre aucun endpoint utilisateur. Le jeton ne voyage jamais
    en query (journaux d'accès du proxy et de l'ingress).
  - Le **poll fait office de heartbeat** (`runner_tokens.last_seen_at` rafraîchi à chaque poll et à
    chaque dépôt) ; un canal inactif au-delà de `app.runner.poll.idle-timeout-ms` (90 s) est fermé
    comme une socket coupée (appels en vol terminés en `runner_unavailable`, présence retirée,
    aucun rejeu).
  - **Limite de production inchangée** : le long-polling n'ajoute **aucun** relais inter-pods — un
    canal s'enregistre sur **son** pod, le mode `RUNNER` suppose toujours un replica unique ou une
    affinité d'ingress.

- **Projet qui vit déjà sur la machine — trois colonnes sur `workspaces`, aucune table neuve**
  (F-38 / SF-38-15 migration `052`, SF-38-18 migration `053`, SF-38-27 migration `063`).
  - `workspaces.runner_root_name` (`varchar(255)`, **nullable**) — le **nom** de la racine déclarée
    par le runner à l'appairage, jamais le chemin absolu : la gateway n'apprend pas où le projet vit
    sur la machine, elle sait seulement comment l'appeler à l'écran. C'est le pendant de la source
    `LOCAL` : un projet créé en ne donnant qu'un nom, dont la racine est déclarée par le runner.
  - `workspaces.runner_elevated` (`boolean`, **nullable**, sans défaut) — les **droits** sous
    lesquels le runner tourne, détectés par lui (uid réel, repli sur le nom du compte) et déclarés à
    l'appairage. La gateway ne peut pas les deviner. Nullable **et** sans défaut parce qu'un projet
    antérieur n'a rien déclaré et qu'un runner antérieur à SF-38-18 n'envoie pas le champ — l'absence
    d'information ne doit pas se lire comme « droits ordinaires ». L'écran s'en sert **là où l'on
    autorise une commande** : autoriser `rm -rf build` n'a pas le même poids selon les droits sous
    lesquels elle s'exécutera. **Informatif, jamais une garde** : le runner agit avec les droits du
    compte qui l'a lancé, et démarrer en root n'est pas interdit (usage conteneur).
  - `workspaces.runner_shell` (`varchar(16)`, **nullable**, sans défaut) — le **genre
    d'interpréteur** que le runner a élu au démarrage (`posix`, `powershell` ou `cmd`) et déclaré
    dans sa trame `ready`. La **consigne système** en cible `RUNNER` en dépend : elle dicte au
    modèle une syntaxe d'exploration, et `bash` y est son seul outil pour explorer (SF-39-05) —
    dicter `ls`/`find`/`grep -n` à un poste qui n'a que `cmd.exe` faisait échouer chaque
    exploration. Déclarée dans `ready` et **non** à l'appairage : l'appairage n'a lieu qu'une fois,
    la trame part à chaque connexion. Persistée plutôt que gardée en mémoire parce que la consigne
    est construite par le pod qui sert le message, pas par celui qui porte la socket (HPA
    `min 1 / max 4`). Écriture sous **liste blanche stricte** : une valeur hors des trois genres
    n'entre pas en base.
  - Les trois colonnes suivent l'isolation générale : elles vivent sur `workspaces`, lues et écrites
    sous le `user_id` propriétaire du projet — l'écriture depuis le canal runner se fait sur le
    `workspace_id` de la **session authentifiée**, jamais sur un identifiant lu dans une trame.

- **radar_*** — le **registre du Radar** (F-99 / SF-99-01, migration `081` ; cadrage
  `docs/features/F-99/CADRAGE-le-radar.md` §3-4). Neuf tables, **toutes à `user_id` ET `host_id`
  non nuls** : le Radar d'un poste ne voit jamais celui d'un autre poste, y compris du même
  utilisateur. Aucune clé étrangère (purge explicite, SF-99-05).
  - `radar_subjects` : `name (200)`, `state` (`NEW`, `ADVANCING`, `WAITING`, `BLOCKED`, `DORMANT`,
    `CLOSE_PROPOSED`, `CLOSED`), `next_step (500)`, `due_date`, `last_activity_at`.
  - `radar_subject_aliases` : `subject_id`, `alias`, `normalized` — unique `(user_id, host_id,
    subject_id, normalized)`.
  - `radar_subject_facts` : le **résumé phrase par phrase** (`subject_id`, `position`, `text (500)`).
  - `radar_people` : l'annuaire du poste, unique `(user_id, host_id, source_key)`.
  - `radar_subject_roles` : rôle d'une personne **sur un sujet** (`DECIDES`, `DRIVES`, `EXPERT`,
    `INFORMED`), unique `(user_id, host_id, subject_id, person_id)`.
  - `radar_commitments` : `direction` (`ME_TO_OTHER`, `OTHER_TO_ME`, `INTRODUCTION`), `description`,
    `from/to/other_person_id` (vide = « moi »), `due_date`, `due_deduced`, `status` (`OPEN`, `KEPT`,
    `POSTPONED`, `ABANDONED`), `certainty` (`CERTAIN`, `PROBABLE` — jamais un score),
    `extraction_key` unique par périmètre (idempotence de l'analyse).
  - `radar_evidence` : **la preuve** — `source` (`TEAMS_MESSAGE`, `TEAMS_MEETING`,
    `LOCAL_RECORDING`, `USER_NOTE`, `PASTED_MAIL`), `source_ref`, `occurred_at`, `quote (280)`,
    `deep_link` ; **unique `(user_id, host_id, source, source_ref)`** : une synchro reprise ne duplique
    rien. Des extraits, pas des archives.
  - `radar_evidence_links` : ce que chaque preuve justifie (`target_kind` : `CHRONOLOGY`, `STATE`,
    `NEXT_STEP`, `DUE_DATE`, `SUMMARY`, `COMMITMENT`, `ROLE` ; `target_id`). **Pas de fait sans
    preuve** : le registre (`RadarRegistry`, seule porte d'écriture) refuse toute valeur sans lien.
    Les liens, et non les preuves, portent le sujet : fusion et séparation déplacent des liens.
  - `radar_syncs` : `status`, `started_at`, `finished_at`, `coverage` (JSON, forme fixée par F-100),
    `consumed_tokens`, `reserve_exempt` (booléen ; première synchro d'un client hors réserve — F-107 /
    SF-107-04, migration 094).
  - **Corrections souveraines** (F-99 / SF-99-02, migration `082`) : marques `name_sovereign`,
    `state_sovereign`, `next_step_sovereign`, `due_date_sovereign` sur `radar_subjects` (par champ),
    `sovereign` et `disowned` (« pas moi ») sur `radar_commitments` (pour l'engagement entier). Une
    valeur souveraine n'est **jamais réécrite par une synchro** (`RadarRegistry`). Journal
    `radar_corrections` (`subject_id`, `target_kind`, `target_id`, `action`, `before_values` /
    `after_values` JSON des **seuls champs touchés**, `created_at`, `undone_at`) : toute correction est
    annulable, sauf recouverte par une correction plus récente et active des mêmes champs.
  - **Fusion, séparation, alias** (F-99 / SF-99-03, migration `083`) : `radar_subject_aliases.origin`
    (`SYNC`, `USER`, `MERGE`, `SPLIT`) et `rejected` — un alias refusé est une **consigne de
    rattachement** (« ce nom n'est pas ce sujet ») qu'une synchro ne peut plus proposer ;
    `radar_subjects.merged_into_id` — le sujet absorbé reste comme **trace** (hors listes, écritures de
    synchro redirigées vers la cible). Fusion et séparation **déplacent des liens** (jamais des preuves)
    et sont journalisées (`MERGE`, `SPLIT`) avec la liste exacte de ce qui a bougé, donc annulables.
  - **Clôture d'un sujet** (F-99 / SF-99-04, migration `084`) : sur `radar_subjects`,
    `previous_state`, `close_proposed_at`, `close_rejected_at`, `closed_at`, `dormant_since`,
    `woke_at`, `wake_dismissed_at` ; lien `CLOSE_SIGNAL`. Un signal explicite **propose** (`CLOSE_PROPOSED`),
    l'utilisateur **clôt** (souverain), le silence **met en sommeil** à 21 jours (`RadarDormancyWorker`,
    nuit, balayage par périmètre `(user_id, host_id)`) et **ne clôt jamais** ; une activité postérieure
    à la clôture **réveille** (`woke_at`) sans rouvrir. Gestes journalisés : `CLOSE`, `CONFIRM_CLOSE`,
    `REJECT_CLOSE`, `DISMISS_WAKE`.
  - **Nourrir le Radar** (F-104 / SF-104-01, migration `096`) : `radar_corrections.evidence_id`
    (nullable, index `(user_id, host_id, evidence_id)`) — la **preuve** d'une correction dite par
    l'utilisateur à un agent muni des **outils Radar** (`RadarToolCatalog` / `RadarToolExecutor` :
    `radar_find_subject`, `radar_update_subject`, `radar_close_subject`, `radar_add_engagement`,
    `radar_mark_engagement`, `radar_merge_subjects`). La preuve est la **parole de l'utilisateur**
    (`USER_NOTE`, ou `PASTED_MAIL` daté du courriel), jamais un paramètre du modèle ; chaque écriture est
    une correction souveraine journalisée, marquée de sa preuve, rangée dans la chronologie. Actions
    ajoutées : `CREATE_SUBJECT` et `ADD_COMMITMENT`, annulables tant que l'objet n'a rien reçu d'autre.
    Garde : terminal Teams d'un poste activé dans la Vigie, droit Vigie.
  - **Lien sujet ↔ projet** (F-106 / SF-106-06, migration `097`) : `radar_subject_projects`
    (`user_id`, `host_id`, `subject_id`, `workspace_id`, `origin` `USER`|`PROPOSED`, `state`
    `CONFIRMED`|`PROPOSED`|`REFUSED`, `created_at`) — **unicité `(subject_id, workspace_id)`**, index
    `(user_id, host_id, workspace_id)` et `(user_id, host_id, subject_id)`. Le projet doit appartenir au
    même poste (`WorkspaceService.listByHost`, terminaux exclus). Déclaré sur la page sujet (souverain) ou
    **proposé** par l'analyse (`RadarProjectProposer`, appelé par `RadarExtractionWriter`) quand un
    message qui prouve le sujet nomme un projet ou son dossier (mot entier, ≥ 3 caractères, jamais le nom
    du poste) ; une proposition n'est qu'une question. **Délier = refuser** : la ligne reste `REFUSED` et
    n'est jamais reproposée. API Vigie : `GET|PUT|DELETE /radar/hosts/{hostId}/subjects/{id}/projects[/{workspaceId}]`,
    `GET /radar/hosts/{hostId}/project-subjects` (passerelle « N sujets dans la Vigie » de la Forge).
    Purgé avec le Radar du poste et le compte.
  - **Purge et export** (F-99 / SF-99-05, migration `085`) : `radar_purges` (`reason` : `MISSION_CLOSED`,
    `VIGIE_REMOVED`, `USER_REQUEST`, `HOST_DELETED` ; `purged_at`, `subjects_count`, `evidence_count`) —
    **trace sans contenu**. La purge supprime en masse toutes les lignes `radar_*` du périmètre ; elle
    est déclenchée par l'utilisateur (confirmation explicite, export Markdown proposé avant), par la
    **suppression du poste** (`RadarHostLifecycleListener`, même transaction) et par la **suppression du
    compte** (`AccountService`). Export et purge ne demandent pas le droit d'option : récupérer et
    effacer ses données ne dépend pas d'un abonnement.
  - **File d'analyse** (F-101 / SF-101-01, migration `089`) : `radar_analysis_batches` — les lots
    d'échanges remontés par la synchro (contrat d'entrée : `docs/features/F-101/SF-101-01-la-file-d-analyse.md`),
    unique `(user_id, host_id, batch_key)`, `sync_id`, `status` (`PENDING`, `PROCESSING`, `DONE`,
    `DEFERRED`, `FAILED`, `EXPIRED`), tentatives, échéance, `payload` = **texte brut, mis à NULL dans la
    transaction qui écrit les faits** ou à `expires_at` (7 jours au plus), compteurs (échanges,
    messages, retenus, sujets rattachés / créés) et **six compteurs de jetons** (tri, extraction, cache).
    `radar_analysis_leases` : le **bail** d'analyse d'un poste (`owner`, `leased_until`), unique
    `(user_id, host_id)` — un seul traitement par poste, tous pods confondus. Les deux tables sont
    effacées par la purge du Radar.
  - **Relance due** (F-101 / SF-101-04, migration `090`) : sur `radar_commitments`, `last_evidence_at`
    (preuve la plus récente, tenue par le registre) et `follow_up_due_on` — **recalculé par rappel
    d'entité à chaque écriture** : « j'attends des autres » ouvert et non désavoué → premier jour ouvré
    après l'échéance, sinon 3 jours ouvrés après la dernière preuve ; `NULL` sinon. Index
    `(user_id, host_id, follow_up_due_on)`.
  - **Réglages Radar d'un poste** (F-100 / SF-100-01, migration `086`) : `radar_host_settings`, unique
    `(user_id, host_id)` — `verification` (JSON des quatre cases de la **vérification guidée** : session,
    conversations, réunions, transcriptions ; des compteurs et des états, jamais un titre, un nom ou une
    adresse) et `verified_at`. Effacée par la purge du Radar.
  - **Planification de la synchro du soir** (F-100 / SF-100-02, migration `087`) : sur
    `radar_host_settings`, `enabled`, `client_authorized_at` (autorisation du client confirmée, §14),
    `sync_time` (`HH:mm`, 22:00 par défaut), `time_zone` (IANA, `Europe/Paris` par défaut),
    `last_slot_date` (dernier créneau traité, date locale), `missed_slot_at` (créneau manqué en attente de
    rattrapage) et **`running_sync_id` — le verrou : une seule synchro par poste, pris par mise à jour
    conditionnelle**. Sur `radar_syncs`, `trigger_kind` (`SCHEDULED`, `MANUAL`, `CATCH_UP`),
    `scheduled_for`, `heartbeat_at` (abandon après 15 min sans battement) et `progress` (JSON borné).
  - **Résumé du matin par courriel** (F-110 / SF-110-04, migration `103`) : sur `radar_host_settings`,
    `morning_email` (option par client, `false` par défaut) et `morning_email_sync_id` (dernière synchro du soir
    traitée ; posée à l'activation, puis **prise par mise à jour conditionnelle** — un seul courriel par synchro).
    `RadarMorningMailWorker` → `RadarMorningMail.runOnce()` : droit Vigie + client dans la Vigie, dernière synchro
    `SCHEDULED`/`CATCH_UP` terminée `SUCCEEDED`/`PARTIAL`, analyse posée (ou > 2 h), écartée au-delà de 18 h ;
    courriel `MORNING_SUMMARY` mis en file dans `client_emails` (même transaction que le marqueur) vers
    `resolveRecipient` — phrases et compteurs de `RadarBriefService`, relances dues, lien `/vigie/{hostId}`.
  - **Collecte incrémentale** (F-100 / SF-100-03, migration `088`) : `radar_sync_cursors` — **où la
    collecte en est, par fil et par poste** (`source` `TEAMS` / `DEPOT`, `conversation_ref`, `kind`,
    `cursor_at`), unique `(user_id, host_id, source, conversation_ref)` ; le curseur n'avance qu'une fois le
    lot accepté par la file d'analyse, et ne recule jamais. `radar_thread_rules` — ce que l'utilisateur a
    dit d'un fil (`IGNORE` : ignorer ce fil ; `READ_CHANNEL` : lire ce canal en entier), unique
    `(user_id, host_id, conversation_ref, rule)`, correction souveraine. Les deux sont purgées avec le Radar.

- **pages / page_versions** — les **pages** (F-109 / SF-109-01, migration `098` ; cadrage
  `docs/features/F-109/CADRAGE-F-109-les-pages.md`). `pages` : un document HTML rendu par l'agent,
  rattaché à son **lieu** (`space` `FORGE`|`VIGIE`, `host_id` nullable, `workspace_id` nullable),
  `title` (120), `description` (300), `current_version`. `page_versions` : une ligne par version conservée
  (`version` unique par page, `size_bytes` HTML **et** pièces jointes, `attachment_count`). FK `users`
  et `pages` en cascade ; index `(user_id, host_id, space)`, `(user_id, workspace_id)`, `page_versions(user_id)`.
  **Contenu hors base**, dans le stockage objet de l'Atelier sous `pages/{userId}/{pageId}/v{N}/`
  (`index.html`, `files/{nom}`). Bornes : 8 Mo par version, 500 Mo par compte, 10 versions par page
  (les plus anciennes purgées). **Service en origine opaque** : `Content-Security-Policy: sandbox
  allow-scripts allow-popups` (jamais `allow-same-origin`), `connect-src 'none'`, `form-action 'none'`,
  CDN en liste close (`PageContentPolicy`). L'écran lit une page par **ticket signé** (HMAC, clé dérivée
  du secret JWT, 10 min, non-JWT) sur `GET /p/{jeton}/**` — **la seule route ouverte sans compte**.
  - **Partage et journal** (F-109 / SF-109-05, migration `099`) : `page_shares` (`page_id` FK cascade,
    `user_id`, **`token_hash`** SHA-256 unique — le jeton de 32 octets n'est jamais stocké —, `created_at`,
    `expires_at` 1 à 90 jours, `revoked_at`, `open_count`, `last_opened_at` ; rien du visiteur), servi sur la
    même route publique ; `page_events` (`page_id` FK cascade, `user_id`, `share_id`, `kind`
    `CREATED`|`VERSION`|`SHARED`|`OPENED`|`REVOKED`, `version`, `occurred_at`). Index `(user_id, page_id)`.
    API propriétaire : `POST|GET /pages/{id}/shares`, `DELETE /pages/{id}/shares/{shareId}`, `GET /pages/{id}/journal`.

Voir `docs/spec.md` §4 pour le DDL historique (scaffolding). Le schéma V1 réel est porté par les migrations Liquibase (`db/changelog/migrations/`).

Règle d'isolation des données :
Tout accès aux données filtre obligatoirement sur **`user_id`**
(documents/messages/subscriptions/uploaded_files/usage_counters/usage_turns/user_api_keys/user_git_credentials/prompt_templates/runner_hosts/host_seat_months/live_terminals/runner_tokens/runner_pairing_codes/runner_audit/atelier_permission_rules/atelier_deposited_files/poste_billing/activity_settings/cra_entries/governance_selections/governance_activations/governance_host_activations/governance_map_growth/governance_deposited_files/pages/page_versions/page_shares/page_events via `user_id` ; tables `radar_*` via `user_id` **et** `host_id` ; `meetings` via `user_id` **et** `host_id` ; `runner_diag_events` via `user_id` **et** `host_id` ; `promotion_reportee` via `user_id` + `host_id` + `workspace_id` ; `access_codes` via `redeemed_by_user_id`). Aucun endpoint ne renvoie des données d'un autre utilisateur. (Exceptions documentées : `processed_billing_events` est un registre technique d'idempotence sans donnée utilisateur, clé globale au fournisseur ; `governance_packages` / `governance_package_files` sont un **contenu produit** — comme un plan tarifaire —, écrits par l'admin seul et lus par tous une fois publiés.)

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

- OQ-01 : **Tranchée (F-06)** — dimension d'embedding **1536** (`chunks.embedding vector(1536)`), réversible via `app.rag.embedding.dimension`.
- OQ-02 : **Exploitée (F-06)** — pgvector activé (`002`) et utilisé (`011`), DDL vectoriel isolé `dbms=postgresql`.
- OQ-03 : **Tranchée (F-06/F-07)** — index **IVFFlat `lists=100`** (recherche `<->` L2 exploitée par
  F-07 `/ask` ; HNSW = évolution ultérieure, réversible via migration d'index).
- OQ-10 : **Tranchée (F-05/F-06)** — workers intra-backend `@Scheduled` (`OcrPollingWorker`, `IngestionWorker`), réversible vers workers dédiés + file en V2.
- OQ-05 : Fournisseur(s) OAuth et modèle de session/token.
