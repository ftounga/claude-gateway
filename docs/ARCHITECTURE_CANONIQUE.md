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
    `ocr_mode (SYNC|ASYNC)`, `provider_job_id (interne, nullable, jamais exposé)`,
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
    `plan_code (nullable ; SOLO|PRO|DAILY)`, `trial_ends_at (nullable)`, `current_period_end (nullable)`,
    `stripe_customer_id (interne, nullable, jamais exposé)`, `stripe_subscription_id (interne, nullable, jamais exposé)`,
    `created_at`, `updated_at`. Index `user_id`, `stripe_subscription_id`.
  - **Note** : la table `subscriptions` du schéma initial `001-init-schema` (placeholder legacy `spec.md`,
    `user_id text`, `plan`, sans statut typé ni unicité) a été **remplacée** en `008` par la table V1 conforme
    ci-dessus (même stratégie que `006-messages`).
- **usage_counters** — compteur de consommation de tokens (F-10, migration `009`). **Une ligne par
  (`user_id`, période)** (unique).
  - `usage_counters` : `id (uuid)`, `user_id (uuid)`, `period_start (date ; 1er du mois calendaire UTC)`,
    `input_tokens (bigint)`, `output_tokens (bigint)`, `bonus_tokens (bigint, défaut 0 ; tokens rachetés
    top-up F-21, migration `032`)`, `created_at`, `updated_at`. Unique `(user_id, period_start)`, index `user_id`.
  - Alimente la vérification de quota **avant** l'appel fournisseur (`ChatService` → `402 quota_exceeded`
    à la limite) et `GET /usage`. Le quota **effectif** = quota mensuel (dérivé de `subscriptions` via la
    configuration `app.quota`, jamais en dur, réversible) **+ `bonus_tokens`** de la période (rachats top-up,
    F-21). V1 = **blocage à la limite** (overage non monétisé, OQ-08 ; variante payante ouverte).
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
- **workspaces — validation avant exécution** (F-33 / SF-33-01, migration `044`). Colonne
  `agent_ask_before_bash (boolean, non nul, défaut false)` : quand elle est posée, la session d'agent
  est ouverte avec `permission_policy: always_ask` sur le **seul outil `bash`** (surcharge d'outils
  session-locale, `agent_with_overrides.tools` — l'agent plateforme n'est jamais modifié).
  **Aucune table nouvelle**, aucune donnée existante changée : à `false`, le corps de création de
  session est strictement celui d'avant F-33.
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

- **runner_pairing_codes / runner_tokens** — identité du runner (F-38 / SF-38-01, migration `047`).
  Deux tables neuves. Le **runner** est un second type de porteur d'identité, authentifié par jeton
  et non par JWT utilisateur ; il ouvre (SF-38-02) une connexion sortante pour exécuter les outils de
  l'agent sur une machine connectée. **Aucun secret en clair** : seul le `SHA-256 (hex)` du code
  d'appairage et du jeton est stocké.
  - `runner_pairing_codes` : `id (uuid)`, `user_id (uuid)`, `workspace_id (uuid)`, `code_hash (varchar 64)`,
    `expires_at`, `consumed_at`, `created_at`. Index `code_hash`. Code court, TTL 5 min, usage unique.
  - `runner_tokens` : `id (uuid)`, `user_id (uuid)`, `workspace_id (uuid)`, `token_hash (varchar 64, unique)`,
    `label (varchar 100)`, `expires_at`, `revoked_at`, `last_seen_at`, `created_at`. Index `(user_id, workspace_id)`.
    TTL 30 j, révocable. Isolation `user_id` sur toutes les lectures/gestions.
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
  - **Effet de bord sur `workspaces.agent_ask_before_bash`** (migration `044`, F-33) : le passage en cible
    `RUNNER` **force la colonne à `true`** et sa désactivation est refusée (`409 execution_target_runner`) —
    `always_allow` est acceptable dans un conteneur jetable, pas sur une vraie machine (D7). Le
    coupe-circuit `POST /workspaces/{id}/runner/kill` **ramène la colonne à `SANDBOX`**.
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
  - `runner_audit` : `id (uuid)`, `user_id (uuid)`, `workspace_id (uuid)`, `token_id (uuid, nullable)`,
    `call_id (varchar 64)`, `tool (varchar 32)`, `target (varchar 1000)`, `outcome (varchar 16)`,
    `error_code (varchar 32)`, `exit_code (int)`, `duration_ms (bigint)`, `bytes (bigint)`,
    `created_at`. Index `(user_id, workspace_id, created_at)`.
  - **Ce que la table ne contient jamais** : aucun contenu de fichier, aucune sortie de commande,
    aucun message d'erreur du runner (un message peut porter un fragment de chemin de la machine ;
    un code d'erreur, jamais). Les lectures d'amorçage de la consigne système sont **agrégées en une
    seule ligne** (`tool = bootstrap`) plutôt qu'une par fichier.
  - Endpoints **`GET /workspaces/{id}/runner/audit`** (journal, `limit` borné à `[1..200]`),
    **`POST /workspaces/{id}/runner/kill`** (coupe-circuit : révocation de tous les jetons, coupure
    de la liaison, retour en cible `SANDBOX`) et **`POST /workspaces/{id}/chat/confirm`** (réponse à
    une demande d'autorisation de la boucle Assistant) — JWT, gardés par l'accès Atelier.
    L'écriture d'audit est **hors transaction et non bloquante** pour la boucle tool-use.

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

- **Projet qui vit déjà sur la machine — deux colonnes sur `workspaces`, aucune table neuve**
  (F-38 / SF-38-15 migration `052`, SF-38-18 migration `053`).
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
  - Les deux colonnes suivent l'isolation générale : elles vivent sur `workspaces`, lues et écrites
    sous le `user_id` propriétaire du projet.

Voir `docs/spec.md` §4 pour le DDL historique (scaffolding). Le schéma V1 réel est porté par les migrations Liquibase (`db/changelog/migrations/`).

Règle d'isolation des données :
Tout accès aux données filtre obligatoirement sur **`user_id`**
(documents/messages/subscriptions/uploaded_files/usage_counters/user_api_keys/user_git_credentials/prompt_templates/runner_tokens/runner_pairing_codes/runner_audit via `user_id`). Aucun endpoint ne renvoie des données d'un autre utilisateur. (Exception documentée : `processed_billing_events` est un registre technique d'idempotence sans donnée utilisateur, clé globale au fournisseur.)

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
