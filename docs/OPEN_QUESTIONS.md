# OPEN_QUESTIONS.md — claude-gateway

Questions non tranchées ayant un impact produit ou technique. À mettre à jour au fil des décisions.

> **MàJ 2026-07-01 (amendement)** — Le traitement documentaire est **entré dans le périmètre** (amendement
> `PROJECT.md`, ADR-011). Les questions RAG/pgvector **OQ-01, OQ-02, OQ-03, OQ-10** sont désormais
> **tranchées** (F-06 livrée : 1536 / pgvector exploité / IVFFlat lists=100 / workers intra-backend,
> toutes réversibles). **OQ-05 (auth)** tranchée (OAuth + email/mot de passe JWT). **OQ-06 (chiffrement
> clés BYOK)** tranchée (AWS KMS, débloque F-03).

---

## OQ-01 — Dimension d'embedding
**Statut** : **Tranchée (2026-07-01, F-06 / SF-06-01)**
**Impact** : Définit le type de `chunks.embedding` (`vector(N)`) et l'index pgvector. Un changement après ingestion impose une ré-indexation complète.
**Options** : 1536 (embeddings via API fournisseur OpenAI/Anthropic, défaut actuel du schéma) ; 384 (modèle local all-MiniLM, cible V2) ; autre selon modèle.
**Décision** : **1536** (`chunks.embedding vector(1536)`, migrations `002`/`011`). Réversible via `app.rag.embedding.dimension` (une nouvelle dimension imposerait une ré-indexation + une migration du type de colonne). Modèle local 384 → F-15 (embeddings locaux).

## OQ-02 — Version Postgres RDS & activation pgvector
**Statut** : **Exploitée (2026-07-01, F-06)** — pgvector activé (`002-pgvector`) et utilisé (`011`)
**Impact** : L'instance RDS est partagée avec legalcase. Il faut confirmer la version PG et que l'extension `vector` est disponible/activable sur cette instance.
**Options** : Activer pgvector sur la base `claudegatewaydb` (extension par base) ; vérifier la version PG (≥ 15 recommandé pour HNSW).
**Décision** : Extension `vector` activée par base via `002-pgvector.xml` ; colonne + index créés en `011` (DDL isolé `dbms=postgresql`). Les tests H2 restent verts via l'abstraction `EmbeddingStore` (store no-op par défaut, colonne vectorielle non mappée par l'entité).

## OQ-03 — Index vectoriel : IVFFlat vs HNSW
**Statut** : **Tranchée (2026-07-01, F-06 / SF-06-01)**
**Impact** : Qualité/latence de la recherche sémantique.
**Options** : IVFFlat (défaut actuel, `lists=100`) ; HNSW (meilleur rappel, plus coûteux en écriture, requiert pgvector récent).
**Décision** : **IVFFlat `lists=100`** (déjà provisionné `002`, recréé en `011`). HNSW reste une **évolution ultérieure** (à réévaluer selon le rappel/charge en F-07), réversible via migration d'index.

## OQ-04 — Modèles Claude disponibles sur le compte
**Statut** : Ouvert
**Impact** : Valeurs par défaut du proxy (`model`) et affichage des modèles sélectionnables côté UI.
**Options** : À lister depuis le compte Anthropic (ex. Sonnet/Haiku/Opus courants).
**Décision** : À définir.

## OQ-05 — Fournisseurs OAuth & modèle de session
**Statut** : Tranchée (2026-07-01)
**Impact** : F-01 (auth), configuration Spring Security, redirections, JWT.
**Décision** : **Les deux modes** — OAuth2/OIDC (Google) **et** compte email/mot de passe (inscription, reset, vérification email), authentification par **JWT**. Microsoft/autres providers → V2.

## OQ-06 — Stockage & chiffrement des clés BYOK
**Statut** : Tranchée (2026-07-01) — **implémentée et livrée** en F-03 (SF-03-01→04, PR #46/#48/#49/#50)
**Impact** : F-03, conformité. Où et comment chiffrer la clé utilisateur.
**Options** : Chiffrement applicatif via AWS KMS ; Vault. Rotation, suppression sur demande.
**Décision (2026-07-01)** : **AWS KMS envelope encryption**. Clé customer-managed dédiée (rotation activée, alias `alias/claude-gateway-staging-byok`), rôle IRSA backend autorisé `GenerateDataKey/Encrypt/Decrypt` sur cette seule clé (moindre privilège). La clé API BYOK est chiffrée côté application via une data key KMS ; jamais stockée ni loggée en clair, jamais exposée au frontend. Alias injecté par `APP_BYOK_KMS_KEY_ID`. Débloque F-03.

## OQ-07 — Réglages Stripe (TVA/taxes, produits, price IDs)
**Statut** : Contournée en V1 (F-09 livrée) — TVA/Stripe Tax reste à trancher
**Impact** : F-09, facturation conforme (TVA UE), mapping plans → price IDs.
**Options** : Stripe Tax activé ; price IDs par plan (Hosted/BYOK × Solo/Pro/Daily) staging + prod.
**Décision (2026-07-01, F-09)** : Les **price IDs** sont **externalisés en configuration d'environnement**
(`app.billing.stripe.prices.{SOLO,PRO,DAILY}`, `STRIPE_PRICE_*`), jamais en dur — le catalogue de code
ne porte aucun montant. Les montants réels vivent dans Stripe (réversibles sans redéploiement).
**Stripe Tax reste désactivé en V1** (option de configuration à activer ultérieurement) : point encore ouvert.

## OQ-08 — Facturation de l'overage
**Statut** : Partiellement tranchée (2026-07-01) — **V1 = blocage à la limite** ; variante monétisée reste ouverte
**Impact** : F-10, monétisation au-delà du quota.
**Options** : Prix par token (ex. 0,002 €/token) ; par tranche ; blocage à la limite.
**Décision** : **V1 = blocage à la limite** (option non monétaire, réversible) — F-10/SF-10-01 : à quota atteint, `POST /chat` renvoie `402 quota_exceeded` sans appeler le fournisseur. La **variante monétisée** (facturation au token / à la tranche au-delà du quota) reste **ouverte** et relève d'une évolution ultérieure (touche à la facturation → décision explicite requise avant implémentation).

## OQ-09 — Domaine staging vs production
**Statut** : Ouvert
**Impact** : DNS, ingress, certificats. Le déploiement de validation utilise `portal.ng-itconsulting.com`.
**Options** : Garder `portal.ng-itconsulting.com` en prod et introduire `staging.portal.ng-itconsulting.com` pour le staging ; ou domaine `.fr` dédié comme legalcase.
**Décision** : À définir (staging actuel exposé directement sur `portal.ng-itconsulting.com`).

## OQ-10 — Worker(s) : intégré vs séparé
**Statut** : **Tranchée (2026-07-01, F-05 + F-06, réversible)**
**Impact** : Architecture de déploiement (pods), scaling de l'ingestion.
**Options** : Traitement asynchrone intra-backend (scheduler/threadpool) en V1 ; workers dédiés (pods séparés + file) en V2.
**Décision** : **Workers intra-backend `@Scheduled`** retenus — `OcrPollingWorker` (F-05) et `IngestionWorker` (F-06 / SF-06-02), désactivables par config. Choix **réversible** : les abstractions (`OcrProvider`, `EmbeddingProvider`/`EmbeddingStore`) + le pilotage par état en base (`documents.status`) permettent d'extraire des workers dédiés + file (SQS/…) en V2 sans réécrire le domaine. À réévaluer selon la charge d'ingestion réelle.
