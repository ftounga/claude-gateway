# Chat personnel (claude-gateway) — Spécification technique détaillée

> Document de référence produit/technique. Sert de base à `ARCHITECTURE_CANONIQUE.md`,
> `PRODUCT_SPEC.md` et au dev. Les `[À REMPLIR]` sont des décisions à trancher à l'implémentation.

## 1. Objectif

Fournir une application de chat personnel hébergée sur le cluster EKS (service public) qui :

- fait office de proxy/intermédiaire pour accéder aux LLM (Claude) quand l'accès direct est bloqué depuis le navigateur client ;
- fournit en option RAG (indexation opt-in), OCR (AWS Textract) et recherche sémantique via embeddings ;
- propose **BYOK** (l'utilisateur fournit sa propre clé API) ou **Hosted mode** (clé et facturation assurées par la plateforme) ;
- inclut un système d'abonnement (Stripe) avec plans Hosted + BYOK et daily pass.

## 2. Décisions générales (confirmées)

- **Usage** : application personnelle et commerciale ciblant principalement les consultants.
- **Hébergement** : backend + services déployés dans le cluster EKS `legalcase-shared` (publiquement accessible), workspace dédié.
- **Domaine** : `portal.ng-itconsulting.com`.
- **Base de données** : Postgres RDS **existant (partagé avec legalcase)**, base de données dédiée `claudegatewaydb`. pgvector à activer au besoin.
- **Fichiers acceptés** : pdf, docx, txt, png, jpg — taille max **20 MB**.
- **OCR** : AWS Textract (`DetectDocumentText` pour images, `StartDocumentTextDetection` pour PDFs).
- **RAG** : off par défaut à l'upload (checkbox décochée), auto-index si 2 follow-ups dans 10 minutes.
- **Chunking** : 400 tokens / overlap 50.
- **Embeddings** : initialement via API fournisseur (Anthropic/OpenAI) ; migration possible vers local (all-MiniLM) ultérieurement.
- **BYOK** : option disponible (clé utilisateur stockée chiffrée).
- **Pricing** : Hosted (Solo 29 €/mois, Pro 119 €/mois, Daily 15 €/jour) ; BYOK (Solo 9 €/mois, Pro 49 €/mois, Daily 7 €/jour) ; trial 14 jours.

## 3. Architecture globale

Composants :

- **Frontend** : Angular — Chat UI, Upload, Documents, Settings, Billing.
- **Backend** : Java Spring Boot — API publique, orchestrateur, proxy Claude, contrôle du pipeline d'ingestion.
- **Embedding Service** : API (initialement appels à l'endpoint embeddings du fournisseur).
- **Postgres RDS** (extension pgvector) — tables `documents`, `chunks`, `messages`, `subscriptions`.
- **S3 bucket** (AWS) — stockage uploads (SSE-KMS recommandé).
- **OCR** : AWS Textract.
- **Worker(s)** : ingestion / textract / polling / embedding — pods Kubernetes.
- **Auth & Billing** : intégration Stripe, gestion des abonnements via webhooks.
- **Secrets** : Kubernetes Secrets / Vault ; AWS IRSA pour l'accès Textract/S3.

Flux :

```
Frontend  ↔  Backend (Spring Boot)  ↔  Claude API
Backend   ↔  S3 / Textract
Backend   ↔  RDS (pgvector)
Backend   ↔  Embedding API (provider) ou service local
Stripe webhooks  ↔  Backend
```

## 4. Schéma de données (Postgres)

Activer pgvector :

```sql
CREATE EXTENSION IF NOT EXISTS vector;
```

Tables principales :

```sql
CREATE TABLE documents (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  filename text NOT NULL,
  s3_key text,
  uploaded_by text,
  uploaded_at timestamptz DEFAULT now(),
  status text,            -- UPLOADED|PROCESSING|INDEXED|FAILED
  metadata jsonb,
  textract_raw jsonb
);

CREATE TABLE chunks (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  document_id uuid REFERENCES documents(id) ON DELETE CASCADE,
  chunk_index int,
  text text,
  char_start int,
  char_end int,
  page_number int,
  embedding vector(384),
  created_at timestamptz DEFAULT now()
);

CREATE TABLE messages (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  conversation_id uuid,
  user_id text,
  role text,
  content text,
  created_at timestamptz DEFAULT now()
);

CREATE TABLE subscriptions (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id text,
  plan text,
  stripe_customer_id text,
  stripe_subscription_id text,
  started_at timestamptz,
  status text
);
```

Indexes recommandés :

```sql
CREATE INDEX ON chunks (document_id);
CREATE INDEX IF NOT EXISTS chunks_embedding_idx
  ON chunks USING ivfflat (embedding vector_l2_ops) WITH (lists = 100);
```

> Note : tuning et choix HNSW/IVF selon la version pgvector et la charge.
> La dimension `vector(384)` correspond à all-MiniLM ; à ajuster selon le modèle d'embedding retenu (les embeddings provider sont souvent 1536). **[À REMPLIR]**

## 5. API Endpoints (spec)

**Endpoints publics :**

- `POST /chat` — `{ conversationId?, message, model? }` → sanitize, vérifie quota/entitlement, forward vers Claude, stocke messages (optionnel). Réponse `{ reply, meta }`.
- `POST /upload` — multipart `file, indexNow?: boolean, noOCR?: boolean` → stocke sur S3, crée document (UPLOADED), si image et `noOCR=false` → Textract `DetectDocumentText` (sync) ou `StartDocumentTextDetection` pour PDFs (async), enqueue ingestion.
- `GET /documents/{id}/status` — statut d'ingestion & d'indexation.
- `POST /ask` — `{ documentId?, question, model? }` → si document indexé : embedding de la question + recherche top-K pgvector, prompt avec passages → Claude ; sinon fallback texte brut ou suggestion d'indexation.
- `POST /user/api-key` — `{ apiKey }` → stocke la clé BYOK chiffrée, valide par un appel test.

**Endpoints internes :**

- `POST /internal/embedBatch`
- `POST /internal/textract/callback` (optionnel, pour SNS)

**Webhook :** `/webhook/stripe`

## 6. Pipeline Textract (backend + polling)

1. Upload → stockage S3 (SSE-KMS) → document `status=UPLOADED`.
2. Si **image** → `DetectDocumentText` (sync) → parse Blocks → assemble texte, stocke `textract_raw`, chunk + enqueue embedding.
3. Si **PDF** → `StartDocumentTextDetection` → sauvegarde `jobId`, `status=PROCESSING`. Le worker poll `GetDocumentTextDetection(jobId)` jusqu'à SUCCEEDED/FAILED, puis assemble, chunk, enqueue embedding.

**Polling :** intervalle initial 5–10 s, backoff x2 → max ~30 s, timeout 30 min. Stocker `next_poll_at` et `backoff` en DB.

**Sécurité :** IRSA pour l'accès Textract sur EKS. Politique IAM minimale.

## 7. Chunking & ingestion des embeddings

- **Chunking** : tokenizer du modèle d'embedding si possible. `chunk_size = 400 tokens`, `overlap = 50`. Pour l'OCR : normaliser le texte, puis chunker (réduire la taille si OCR bruité).
- **Ingestion** : worker batch (ex. 64 chunks) → POST embedding API → stocke dans `chunks.embedding`. Construit/maintient l'index vectoriel.
- **Recherche** :

```sql
SELECT id, text, chunk_index, document_id,
       embedding <-> :query_embedding AS distance
FROM chunks
WHERE document_id = :id
ORDER BY embedding <-> :query_embedding
LIMIT :k;
```

`K` par défaut = 8 ; concat des passages les mieux classés jusqu'au budget de prompt (~1500 tokens).

**Prompt template (FR) :**

> SYSTEM : « Tu es un assistant qui répond strictement en te basant sur les passages fournis. Cite la source sous la forme `[filename:page:chunk_index]`. Si l'information n'est pas présente, indique-le clairement. »
> USER : question + passages.

## 8. BYOK & Hosted mode

- Champ optionnel `user_api_key` stocké chiffré (AWS KMS / Vault).
- Si présent, le backend proxy les appels Claude avec la clé utilisateur (facturation sur son compte).
- Si absent ou mode Hosted, le backend utilise la clé plateforme (facturation gérée par la plateforme).
- UI : réglage compte pour ajouter/supprimer la clé. Wording clair : « En fournissant votre clé, vous gérez la facturation des appels Claude. »
- Sécurité : clé jamais exposée au client ; stockée chiffrée ; suppression sur demande.

## 9. Billing & Pricing

**Hosted** (quotas de tokens inclus) : Solo 29 €/mois, Pro 119 €/mois (RAG & export), Daily 15 €/jour.
**BYOK** (plateforme seule) : Solo 9 €/mois, Pro 49 €/mois (RAG, export), Daily 7 €/jour.
**Trial** : 14 jours.

Billing via Stripe, webhooks → mise à jour de `subscriptions` et des entitlements. Overage : compteurs de quota + tarif par dépassement (ex. configurable 0,002 €/token ou par tranche).

## 10. Sécurité & conformité

- Secrets : K8s Secrets ou Vault pour les clés plateforme ; clés BYOK chiffrées via KMS.
- Transport : TLS partout (Ingress + backend), cert-manager.
- Stockage : S3 SSE-KMS, RDS chiffré at rest.
- RGPD : endpoint de suppression, export, rétention par défaut 90 jours (configurable).
- Audit : logs des actions (uploads, asks, billing).

## 11. Monitoring & Ops

- Métriques : appels Claude/jour, tokens/jour, pages Textract/jour, appels embeddings, erreurs, backlog d'ingestion.
- Outils : Prometheus/Grafana, Loki/CloudWatch.
- Alertes : seuil de coût, taux d'erreur élevé, échecs de jobs Textract.

## 12. Déploiement (EKS)

- Images Docker : backend, frontend, ingestion/embedding worker.
- Manifests K8s (Kustomize, comme legalcase) : Deployments, Services, Ingress, Secrets, ConfigMaps, HPA optionnel.
- IRSA : service accounts avec permissions Textract/S3.
- CI/CD : GitHub Actions → build/push images (ECR) → `kubectl apply -k`.

## 13. Tests & acceptance

- Intégration : upload → textract → chunk → embed → search → ask renvoie des réponses citant les sources.
- E2E : upload de docs, follow-ups, auto-index après 2 follow-ups → réponses améliorées.
- Perf : réponses proxy-only < 3 s ; réponses RAG < 10 s typiques.

## 14. TODO / Placeholders

- Version Postgres RDS / activation pgvector : **[À REMPLIR]**
- Modèles Claude disponibles sur le compte : **[À REMPLIR]**
- Réglages Stripe (taxe, TVA) : **[À REMPLIR]**
- Dimension d'embedding définitive : **[À REMPLIR]**

## 15. Deliverables

- Scripts SQL (pgvector + tables) → migrations Liquibase.
- Backend Spring Boot (proxy) + Dockerfile + manifests k8s.
- Worker Textract (Java) + code de polling.
- Doc marketing + pricing (voir `marketing.md`).
- Frontend Angular (chat + upload + bouton index).
