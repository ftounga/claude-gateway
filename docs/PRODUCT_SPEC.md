# PRODUCT_SPEC.md — claude-gateway

Source de vérité des fonctionnalités du produit.

Toute nouvelle feature doit être ajoutée ici avant toute implémentation.
Toute évolution d'une feature existante doit être validée et mise à jour ici.
Aucune feature ne peut être implémentée si elle n'est pas référencée dans ce fichier.

---

## Règles de gestion

- Toute feature ajoutée doit avoir un identifiant unique (`F-XX`)
- Les identifiants ne sont jamais réutilisés, même si une feature est supprimée
- Le statut est mis à jour à chaque étape du cycle de développement
- Toute modification de ce fichier doit être explicitement validée par le product owner
- Les features hors V1 sont listées mais ne peuvent pas être implémentées avant décision explicite

---

## Statuts possibles

| Statut | Signification |
|--------|--------------|
| `À spécifier` | Feature identifiée, pas encore découpée en subfeatures |
| `En cours` | Au moins une subfeature en cours d'implémentation |
| `Partielle` | Certaines subfeatures terminées, d'autres non |
| `Terminée` | Toutes les subfeatures DoD vérifiées et mergées |
| `Suspendue` | Mise en attente — décision explicite requise pour reprendre |

---

## Features V1

> **Périmètre V1 = passerelle pure vers Claude** (`PROJECT.md`, source de vérité). Les capacités de traitement documentaire (OCR, RAG, embeddings, pgvector, recherche vectorielle) sont **repoussées en V2** — voir F-05→F-08 dans le backlog. Décision du 2026-07-01.

| ID | Feature | Description | Statut |
|----|---------|-------------|--------|
| F-01 | Authentification | OAuth2/OIDC (Google) **+** email/mot de passe (inscription, reset, vérif email) via JWT ; gestion de session, 401 → /login, profil utilisateur | **Terminée** (SF-01-01→07) |
| F-02 | Chat proxy Claude | Interface de chat ; `POST /chat` relaie vers Claude (Hosted), stockage optionnel des messages/conversations, sélection du modèle | **Terminée** (SF-02-01→02) |
| F-03 | BYOK (clé utilisateur) | Ajout/suppression d'une clé API Claude chiffrée (`POST /user/api-key`), validation par appel test, bascule Hosted/BYOK | À spécifier |
| F-04 | Upload & transmission fichiers | `POST /upload` multipart (types supportés par Claude) → **transmission au fournisseur**. Stockage temporaire si nécessaire au relais. **Aucun OCR, aucune indexation, aucune persistance documentaire** (PROJECT.md §11.6) | **Terminée** (SF-04-01→03) |
| F-09 | Abonnements & billing | Plans Hosted/BYOK (Solo/Pro/Daily) + trial 14 j, checkout Stripe, webhook `/webhook/stripe` → `subscriptions` + entitlements | **Terminée** (SF-09-01→03) |
| F-10 | Quotas & entitlements | Compteurs de consommation (tokens), vérification quota avant appel, blocage à la limite (V1, overage non monétisé), `GET /usage` + jauge | **Terminée** (SF-10-01→02) |
| F-11 | Settings & compte | Réglages compte, export/suppression des données (RGPD) ; gestion clé BYOK reportée à F-03 | **Terminée** (SF-11-01→02) |
| F-12 | Landing / onboarding | Page produit consultants (CTA trial), onboarding 2 étapes (sign-up + Hosted/BYOK) | **Terminée** (SF-12-01→02) |

---

## Ordre d'implémentation recommandé

```
F-01 Authentification
   └─> F-02 Chat proxy (Hosted)
          ├─> F-03 BYOK
          └─> F-04 Upload & transmission fichiers
F-09 Billing ──> F-10 Quotas/entitlements ──> F-11 Settings/compte
F-12 Landing/onboarding (en parallèle, après F-01/F-02)
```

Rationale : l'auth conditionne tout ; le chat proxy Hosted est le cœur de valeur (démontrable vite) ;
BYOK et l'upload (transmission simple au fournisseur) s'empilent ensuite ; billing/quotas
sécurisent la monétisation ; settings et landing finalisent l'expérience. Le pipeline documentaire
(OCR → RAG → ask) est **hors V1** (→ V2).

---

## Features hors V1 (backlog)

| ID | Feature | Description | Cible |
|----|---------|-------------|-------|
| F-05 | OCR (Textract) | Extraction texte : images (`DetectDocumentText` sync), PDF (`StartDocumentTextDetection` async + worker de polling), stockage `textract_raw` | V2 |
| F-06 | Ingestion RAG | Chunking (400 tokens / overlap 50), embeddings via API fournisseur, stockage `chunks.embedding` (pgvector), auto-index | V2 |
| F-07 | Q&A documentaire (ask) | `POST /ask` : embedding question → recherche top-K pgvector → prompt cité `[filename:page:chunk]` → Claude ; fallback si non indexé | V2 |
| F-08 | Statut des documents | `GET /documents/{id}/status`, liste des documents, états UPLOADED/PROCESSING/INDEXED/FAILED, suppression (RGPD) | V2 |
| F-13 | Templates métier | Modèles de prompts (audit, rapport) réutilisables | V2 |
| F-14 | Export conversations/réponses | Export PDF/Markdown des échanges et réponses citées | V2 |
| F-15 | Embeddings locaux | Migration vers modèle local (all-MiniLM), suppression dépendance provider | V2 |
| F-16 | Rapports d'usage & coût in-app | Tableaux de bord consommation/coût par utilisateur | V2 |
| F-17 | Espaces d'équipe (cabinets) | Partage/collaboration multi-utilisateurs (introduit une notion d'organisation) | V3 |
| F-18 | On-prem / allowlist | Déploiement privé + procédure d'allowlist DSI | V3 |

---

## Historique des évolutions

| Date | Modification | Validé par |
|------|-------------|------------|
| 2026-07-01 | Création initiale (dérivée de docs/spec.md) | Product owner |
| 2026-07-01 | Recentrage V1 = passerelle pure (PROJECT.md source de vérité). F-05/06/07/08 (OCR/RAG/pgvector/ask) → V2. F-04 redéfini (upload+transmission, sans OCR/index). F-01 = OAuth + email/mot de passe (JWT). | Product owner |
| 2026-07-01 | **F-01 Authentification terminée** (SF-01-01 socle JWT/User → SF-01-07 écrans Angular). Backend : register/login BCrypt, vérif email, reset mot de passe, OAuth Google, profil + logout-all (`token_version`). Nouvelles tables : `users`, `email_verification_tokens`, `password_reset_tokens`. Front : écrans `auth/`. | Delivery agent |
| 2026-07-01 | **F-04 SF-04-01 — Upload & transmission au fournisseur** : `POST /upload` multipart (liste blanche MIME + plafond 32 Mo), transmission via `AIProvider.uploadFile` (Anthropic Files API), métadonnées `uploaded_files` (isolation `user_id`), aucun contenu stocké. Nouvelle table `uploaded_files` (migration `007`). | Delivery agent |
| 2026-07-01 | **F-04 SF-04-02 — Rattachement au chat** : `POST /chat` accepte `attachmentIds` ; fichiers résolus (isolation `user_id`, 404 sinon) et transmis au fournisseur comme blocs `document`/`image`. | Delivery agent |
| 2026-07-01 | **F-04 SF-04-03 — Pièce jointe (frontend)** : bouton trombone dans le chat, upload via `UploadService`, puces Material, `attachmentIds` à l'envoi. | Delivery agent |
| 2026-07-01 | **F-04 Upload & transmission fichiers terminée** (SF-04-01→03). Périmètre V1 respecté : upload + transmission au fournisseur uniquement, sans OCR/indexation/persistance documentaire. | Delivery agent |
| 2026-07-01 | **SF-02-01 — Backend proxy de chat Hosted** (PR #15). Couche fournisseur abstraite `AIProvider` + `AnthropicProvider` (Hosted, clé plateforme via env, jamais loggée) ; `ChatService`/`ConversationService`, endpoints `POST /chat`, `GET/PATCH/DELETE /conversations`, `GET /chat/models`. Nouvelles tables `conversations`, `messages` (la table `messages` placeholder du schéma legacy `001-init-schema` est remplacée par la table V1 conforme). Isolation `user_id`. | Delivery agent |
| 2026-07-01 | **SF-02-02 — Frontend interface de chat** (PR #16). Écran `/chat` (sidebar conversations + fil + sélecteur de modèle) consommant l'API F-02, route lazy protégée par `authGuard`, `ConfirmDialog` pour suppression, `MatSnackBar` pour erreurs. | Delivery agent |
| 2026-07-01 | **F-02 Chat proxy Claude (Hosted) terminée** (SF-02-01 backend → SF-02-02 frontend). Proxy non-streamé V1 vers Claude en mode Hosted, persistance conversations/messages, sélection de modèle (liste blanche configurable, OQ-04 contournée). Streaming et rendu Markdown = améliorations ultérieures. | Delivery agent |
| 2026-07-01 | **F-09 SF-09-01 — Domaine abonnement & essai** (PR #22) : table `subscriptions` (migration `008`, unique `user_id`, colonnes Stripe nullable ; remplace le placeholder legacy de `001`). Catalogue statique de plans Hosted Solo/Pro/Daily (prix/price IDs hors code). Essai `TRIALING` 14 j provisionné à la volée (idempotent). `GET /billing/plans`, `GET /billing/subscription` (isolation `user_id`, ids Stripe jamais exposés). | Delivery agent |
| 2026-07-01 | **F-09 SF-09-02 — Checkout Stripe & webhook** (PR #23) : interface `BillingProvider` + `StripeBillingProvider` (code métier découplé de Stripe). `POST /billing/checkout` → session Checkout (subscription mensuel / payment pass DAILY). `POST /webhook/stripe` public, **signature vérifiée**, événements traduits → activation/annulation d'abonnement (idempotent). Secrets/price IDs via env (OQ-07 contournée), fournisseur dormant → 503. | Delivery agent |
| 2026-07-01 | **F-09 SF-09-03 — Écran de facturation** (PR #24) : route `/billing` (lazy, `authGuard`), abonnement courant + catalogue de plans, `POST /billing/checkout` puis redirection Stripe, retour `?checkout=success|cancel`. `BillingService` sur `/api` uniquement. Conforme DESIGN_SYSTEM. | Delivery agent |
| 2026-07-01 | **F-09 Abonnements & billing Stripe terminée** (SF-09-01→03). Plans + essai 14 j, checkout Stripe et webhook signé alimentant `subscriptions`, écran Angular. Entitlements/quotas de consommation = F-10 (consommera `subscriptions`). Nouvelle table `subscriptions` (migration `008`). | Delivery agent |
| 2026-07-01 | **F-10 SF-10-01 — Compteurs & enforcement quota** (PR #26) : table `usage_counters` (migration `009`, compteur cumulatif de tokens par `user_id`/mois calendaire UTC, isolation `user_id`). `EntitlementService` (abonnement F-09 → quota mensuel de tokens ; fail-closed), `QuotaService` (pré-contrôle avant appel fournisseur, enregistrement après, `GET /usage`). `ChatService` bloque à la limite (402 `quota_exceeded`, aucun message persisté). Quotas externalisés (`app.quota`). **OQ-08 tranchée** : V1 = blocage à la limite (overage non monétisé). | Delivery agent |
| 2026-07-01 | **F-10 SF-10-02 — Jauge de consommation (frontend)** (PR #27) : `UsageService` + carte « Consommation » dans l'écran `/billing` (utilisé/quota/restant, `mat-progress-bar`, badge « Quota atteint », dégradation gracieuse). Conforme DESIGN_SYSTEM. | Delivery agent |
| 2026-07-01 | **F-10 Quotas & entitlements terminée** (SF-10-01→02). Compteurs de tokens par période, vérification quota avant appel (402 à la limite), `GET /usage` + jauge. Nouvelle table `usage_counters` (migration `009`). Overage monétisé = évolution ultérieure (OQ-08 : variante payante ouverte). | Delivery agent |
| 2026-07-01 | **F-11 SF-11-01 — Compte & RGPD (backend)** (PR #29) : `GET /api/account/export` (portabilité, art. 20) agrège compte + abonnement + usage + conversations/messages + fichiers du seul `user_id` ; `DELETE /api/account` (effacement, art. 17) supprime en transaction toutes les données rattachées puis le compte (cascade FK vers les jetons). Aucun champ sensible exposé (`passwordHash`, ids Stripe, `providerFileId`). Isolation `user_id`. **Aucune nouvelle table.** | Delivery agent |
| 2026-07-01 | **F-11 SF-11-02 — Écran Paramètres & RGPD (frontend)** (PR #30) : route `/settings` (lazy, `authGuard`), récapitulatif compte, export téléchargé en JSON, suppression via `MatDialog` exigeant la ressaisie de l'e-mail puis purge du token + redirection `/login`. Lien depuis `/profile`. Conforme DESIGN_SYSTEM. | Delivery agent |
| 2026-07-01 | **F-11 Settings & compte (RGPD) terminée** (SF-11-01→02). Export et suppression RGPD des données (suppression immédiate et définitive ; « rétention 90 j » tranchée par défaut sans planificateur async — arbitrage réversible). **Gestion de la clé BYOK reportée à F-03** (hors périmètre F-11 pour éviter la dérive multi-features). Aucune nouvelle table. | Delivery agent |
| 2026-07-01 | **F-12 SF-12-01 — Landing consultants** (PR #32) : route `/` → `LandingComponent` (hero de valeur, 3 bénéfices consultants, parcours « comment ça marche », CTA « Démarrer l'essai gratuit » → `/register`, CTA contextuel `/chat` si authentifié). Page 100 % statique (aucun appel réseau). Remplace et supprime l'ancien placeholder `HomeComponent`/`HomeService` (carte de statut backend, artefact de dev). Conforme DESIGN_SYSTEM. Feature 100 % frontend, aucune table. | Delivery agent |
| 2026-07-01 | **F-12 SF-12-02 — Onboarding 2 étapes** (PR #33) : route `/onboarding` (lazy, `authGuard`), `mat-stepper` (compte via `GET /api/me` + rappel de vérification ; choix Hosted → `/chat`, BYOK → `/billing` avec note « clé dans les Réglages » (F-03), « passer » → HOSTED + `/chat`). `OnboardingService` mémorise l'état en `localStorage` (**mode fournisseur non persisté côté serveur** : aucun endpoint V1 ne l'expose ; arbitrage réversible). **Transversal navigation** : `login` + `oauth-callback` redirigent via `postLoginPath()` (`/onboarding` si non terminé, sinon `/profile`), non-régression testée. Aucune table. | Delivery agent |
| 2026-07-01 | **F-12 Landing & onboarding terminée** (SF-12-01→02). Page produit consultants + onboarding 2 étapes (inscription puis choix Hosted/BYOK). Feature 100 % frontend consommant F-01/F-02/F-09 existants ; aucune capacité IA réimplémentée (Provider-First) ; aucune nouvelle table ni migration. | Delivery agent |
