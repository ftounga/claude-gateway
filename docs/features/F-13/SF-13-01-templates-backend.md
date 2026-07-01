# Mini-spec — [F-13 / SF-13-01] Templates métier — backend CRUD

## Identifiant

`F-13 / SF-13-01`

## Feature parente

`F-13` — Templates métier (modèles de prompts réutilisables : audit, rapport…)

## Statut

`done`

## Date de création

2026-07-01

## Branche Git

`feat/SF-13-01-templates-backend`

---

## Objectif

> Permettre à un utilisateur de gérer (créer, lister, consulter, modifier, supprimer) ses propres
> modèles de prompts réutilisables, isolés par `user_id`, via une API REST `/api/templates`.

---

## Comportement attendu

### Cas nominal

1. `POST /api/templates` avec `{ name, category, content }` → crée un modèle appartenant à
   l'utilisateur courant (`user_id` du JWT) → `201` + `TemplateResponse`.
2. `GET /api/templates` → liste des modèles de l'utilisateur, du plus récemment modifié au plus
   ancien → `200` + `TemplateResponse[]` (contenu inclus).
3. `GET /api/templates/{id}` → détail d'un modèle possédé → `200` + `TemplateResponse`.
4. `PUT /api/templates/{id}` avec `{ name, category, content }` → met à jour un modèle possédé →
   `200` + `TemplateResponse`.
5. `DELETE /api/templates/{id}` → supprime définitivement un modèle possédé → `204`.

Le backend est une **Gateway** : aucun appel IA, aucun traitement documentaire. Simple CRUD
relationnel. Aucune capacité fournie par Claude n'est réimplémentée (Provider-First respecté :
sans objet ici).

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| `name` absent / vide après trim | `validation_error` | 400 |
| `content` absent / vide après trim | `validation_error` | 400 |
| `name` > 120 caractères | `validation_error` | 400 |
| `content` > 10000 caractères | `validation_error` | 400 |
| `category` non conforme à l'énumération | `validation_error` | 400 |
| `id` inexistant **ou** appartenant à un autre utilisateur | `not_found` (indistinct) | 404 |
| Requête sans JWT valide | rejet filtre sécurité | 401 |

---

## Contrat API (FIGÉ — importé tel quel par SF-13-02)

Toutes les routes sont authentifiées (JWT `Authorization: Bearer`), servies sous le context-path
`/api`. Isolation `user_id` systématique.

### `POST /api/templates`
Requête :
```json
{ "name": "Audit sécurité", "category": "AUDIT", "content": "Réalise un audit de sécurité de..." }
```
Réponse `201` :
```json
{
  "id": "uuid",
  "name": "Audit sécurité",
  "category": "AUDIT",
  "content": "Réalise un audit de sécurité de...",
  "createdAt": "2026-07-01T10:00:00Z",
  "updatedAt": "2026-07-01T10:00:00Z"
}
```

### `GET /api/templates` → `200`
`TemplateResponse[]` triés par `updatedAt` décroissant (contenu inclus).

### `GET /api/templates/{id}` → `200` `TemplateResponse` | `404`

### `PUT /api/templates/{id}`
Requête : identique à `POST`. Réponse `200` `TemplateResponse` | `404`.

### `DELETE /api/templates/{id}` → `204` | `404`

### Modèle `TemplateResponse`
| Champ | Type | Notes |
|-------|------|-------|
| id | string (uuid) | |
| name | string | ≤ 120 |
| category | string | `AUDIT` \| `REPORT` \| `OTHER` |
| content | string | ≤ 10000 |
| createdAt | string (ISO-8601) | |
| updatedAt | string (ISO-8601) | |

### Corps d'erreur (homogène projet)
```json
{ "error": "validation_error", "message": "…" }
```

---

## Critères d'acceptation

- [ ] `POST /api/templates` valide → `201` + modèle rattaché au `user_id` du JWT.
- [ ] `GET /api/templates` → uniquement les modèles de l'utilisateur, triés `updatedAt` desc.
- [ ] `GET /api/templates/{id}` d'un autre utilisateur → `404` (indistinct d'un id inexistant).
- [ ] `PUT /api/templates/{id}` met à jour name/category/content d'un modèle possédé ; sur modèle
      d'autrui → `404`, aucune écriture.
- [ ] `DELETE /api/templates/{id}` d'un modèle possédé → `204` ; d'autrui → `404`, aucune suppression.
- [ ] `name`/`content` vides ou trop longs → `400 validation_error`.
- [ ] `category` invalide → `400 validation_error` (jamais `500`).
- [ ] Sécurité : un utilisateur A ne peut ni lire, ni modifier, ni supprimer un modèle de B.
- [ ] Suppression RGPD du compte (F-11) : les modèles de l'utilisateur sont supprimés.
- [ ] Export RGPD (F-11) : les modèles de l'utilisateur figurent dans l'export.

---

## Périmètre

### Hors scope (explicite)

- Aucune intégration IA / envoi automatique du modèle à Claude (le backend reste Gateway).
- Aucune application côté chat (l'insertion dans le composer de chat relève de SF-13-02, côté client).
- Pas de variables/placeholders dynamiques dans le contenu (texte libre en V1 — arbitrage réversible).
- Pas de modèles « système » pré-remplis fournis par la plateforme (CRUD utilisateur seul — réversible).
- Pas de partage entre utilisateurs (espaces d'équipe = F-17/V3, hors périmètre).

---

## Valeurs initiales

| Champ | Valeur initiale | Règle |
|-------|----------------|-------|
| id | uuid généré | Hibernate `@UuidGenerator` |
| user_id | utilisateur courant | `CurrentUser.requireId()` — jamais un paramètre client |
| category | `OTHER` | si non fournie dans la requête |
| created_at / updated_at | now() | `@CreationTimestamp` / `@UpdateTimestamp` |

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format / Valeurs autorisées | Unicité | Normalisation |
|-------|-------------|-------------|----------------------------|---------|---------------|
| name | Oui | 120 | non vide après trim | Non | `trim()` |
| category | Non (défaut OTHER) | — | `AUDIT` \| `REPORT` \| `OTHER` | Non | — |
| content | Oui | 10000 | non vide après trim | Non | `trim()` |

Notes :
- `category` par défaut = `OTHER` si absente. Valeur hors énumération → `400` (handler
  `HttpMessageNotReadableException` ajouté, défensif, bénéficie à toute l'API).
- Longueurs (120 / 10000) tranchées par défaut (arbitrage réversible, cohérent avec `title` 200 /
  contenus texte existants). Aucune OPEN_QUESTION structurante impactée.

---

## Technique

### Endpoint(s)

| Méthode | URL | Auth | Rôle minimum |
|---------|-----|------|-------------|
| POST | `/api/templates` | Oui | USER |
| GET | `/api/templates` | Oui | USER |
| GET | `/api/templates/{id}` | Oui | USER |
| PUT | `/api/templates/{id}` | Oui | USER |
| DELETE | `/api/templates/{id}` | Oui | USER |

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| `prompt_templates` (nouvelle) | INSERT / SELECT / UPDATE / DELETE | isolation `user_id`, index `user_id` |

### Migration Liquibase

- [x] Oui — `031-prompt-templates.xml` (changesets `dbms=postgresql` + `dbms=h2` séparés,
      numéro au-dessus du dernier mergé `030`). Table relationnelle simple, **aucune colonne
      vectorielle** (pas de pgvector : F-13 n'est pas du RAG).

### Composants Angular

- Aucun (SF-13-02).

---

## Plan de test

### Tests unitaires (`TemplateServiceTest`)

- [ ] `getOwned` renvoie le modèle de l'utilisateur.
- [ ] `getOwned` sur modèle d'autrui → `TemplateNotFoundException`.
- [ ] `create` trim name/content, category par défaut OTHER si null, rattache `user_id`.
- [ ] `update` sur modèle d'autrui → `TemplateNotFoundException`, aucune sauvegarde.
- [ ] `delete` sur modèle d'autrui → `TemplateNotFoundException`, aucune suppression.

### Tests d'intégration (`TemplateApiIntegrationTest`)

- [ ] `POST` valide → `201` + champs.
- [ ] `POST` name vide → `400` ; content vide → `400` ; category invalide → `400`.
- [ ] `GET` liste → seulement les modèles du propriétaire.
- [ ] `GET /{id}` d'autrui → `404`.
- [ ] `PUT /{id}` possédé → `200` valeurs à jour ; d'autrui → `404`.
- [ ] `DELETE /{id}` possédé → `204` ; d'autrui → `404`.
- [ ] Sans token → `401`.

### Isolation utilisateur

- [x] Applicable — Alice ne voit/modifie/supprime aucun modèle de Bob (`404` indistinct).

### RGPD (F-11)

- [ ] `AccountServiceTest` : `deleteAccount` supprime les modèles (`templateRepository.deleteByUserId`).

---

## Dépendances

### Subfeatures bloquantes

- Aucune (socle auth/`CurrentUser` déjà en place).

### Questions ouvertes impactées

- Aucune. F-13 ne touche ni embeddings, ni pgvector, ni OCR (OQ-01/02/03 sans objet ici).

---

## Notes et décisions

- **Gateway-First / Provider Independence** : CRUD pur, aucun SDK fournisseur, aucun appel IA.
- **Arbitrage réversible** : contenu = texte libre (pas de placeholders), pas de modèles système,
  longueurs 120/10000. Tracés ici, ré-ouvrables sans migration destructive.
- **Livraison** : back puis front (contrat figé ci-dessus). Parallélisation multi-worktree non
  employée (agent unique) → séquentiel backend-avant-frontend, équivalent fonctionnel.
</content>
