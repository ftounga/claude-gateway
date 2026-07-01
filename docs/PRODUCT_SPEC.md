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

| ID | Feature | Description | Statut |
|----|---------|-------------|--------|
| F-01 | Authentification | Connexion OAuth2/OIDC (Google), gestion de session, 401 → /login, profil utilisateur | À spécifier |
| F-02 | Chat proxy Claude | Interface de chat ; `POST /chat` relaie vers Claude (Hosted), stockage optionnel des messages/conversations, sélection du modèle | À spécifier |
| F-03 | BYOK (clé utilisateur) | Ajout/suppression d'une clé API Claude chiffrée (`POST /user/api-key`), validation par appel test, bascule Hosted/BYOK | À spécifier |
| F-04 | Upload de documents | `POST /upload` multipart (pdf, docx, txt, png, jpg ≤ 20 Mo) → S3 + ligne `documents` (UPLOADED), options `indexNow`/`noOCR` | À spécifier |
| F-05 | OCR (Textract) | Extraction texte : images (`DetectDocumentText` sync), PDF (`StartDocumentTextDetection` async + worker de polling), stockage `textract_raw` | À spécifier |
| F-06 | Ingestion RAG | Chunking (400 tokens / overlap 50), embeddings via API fournisseur, stockage `chunks.embedding` (pgvector), auto-index (2 follow-ups / 10 min) | À spécifier |
| F-07 | Q&A documentaire (ask) | `POST /ask` : embedding de la question → recherche top-K pgvector → prompt cité `[filename:page:chunk]` → Claude ; fallback si non indexé | À spécifier |
| F-08 | Statut des documents | `GET /documents/{id}/status`, liste des documents, états UPLOADED/PROCESSING/INDEXED/FAILED, suppression (RGPD) | À spécifier |
| F-09 | Abonnements & billing | Plans Hosted/BYOK (Solo/Pro/Daily) + trial 14 j, checkout Stripe, webhook `/webhook/stripe` → `subscriptions` + entitlements | À spécifier |
| F-10 | Quotas & entitlements | Compteurs de consommation (tokens, pages Textract), vérification quota avant appel, gestion overage | À spécifier |
| F-11 | Settings & compte | Réglages compte, gestion clé BYOK, export/suppression des données (RGPD, rétention 90 j) | À spécifier |
| F-12 | Landing / onboarding | Page produit consultants (CTA trial), onboarding 2 étapes (sign-up + Hosted/BYOK) | À spécifier |

---

## Ordre d'implémentation recommandé

```
F-01 Authentification
   └─> F-02 Chat proxy (Hosted)
          ├─> F-03 BYOK
          └─> F-04 Upload
                 └─> F-05 OCR
                        └─> F-06 Ingestion RAG
                               └─> F-07 Q&A (ask)  ──> F-08 Statut documents
F-09 Billing ──> F-10 Quotas/entitlements ──> F-11 Settings/compte
F-12 Landing/onboarding (en parallèle, après F-01/F-02)
```

Rationale : l'auth conditionne tout ; le chat proxy Hosted est le cœur de valeur (démontrable vite) ;
BYOK et le pipeline documentaire (upload → OCR → RAG → ask) s'empilent ensuite ; billing/quotas
sécurisent la monétisation ; settings et landing finalisent l'expérience.

---

## Features hors V1 (backlog)

| ID | Feature | Description | Cible |
|----|---------|-------------|-------|
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
