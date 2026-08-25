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

Voir `docs/spec.md` §4 pour le DDL historique (scaffolding). Le schéma V1 réel est porté par les migrations Liquibase (`db/changelog/migrations/`).

Règle d'isolation des données :
Tout accès aux données filtre obligatoirement sur **`user_id`**
(documents/messages/subscriptions/uploaded_files/usage_counters/user_api_keys/user_git_credentials/prompt_templates via `user_id`). Aucun endpoint ne renvoie des données d'un autre utilisateur. (Exception documentée : `processed_billing_events` est un registre technique d'idempotence sans donnée utilisateur, clé globale au fournisseur.)

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
