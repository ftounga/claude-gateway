# Coding Rules — claude-gateway

## Principes généraux

- Lisibilité > concision
- Explicite > implicite
- Simple > générique
- Ne pas anticiper des besoins non encore spécifiés
- Aucune abstraction prématurée

---

## Backend — Spring Boot

### Structure des packages

```
fr.claudegateway.
  ├── {{MODULE_1}}/       → [domaine métier principal]
  ├── {{MODULE_2}}/       → [second domaine métier]
  ├── auth/               → sécurité, OAuth2, identité
  ├── billing/            → abonnements, usage (si applicable)
  └── shared/             → utilitaires partagés (pas de logique métier)
```

### Nommage

| Élément | Convention | Exemple |
|---------|-----------|---------|
| Package | `snake_case` minuscule | `fr.claudegateway.{{MODULE_1}}` |
| Classe | `PascalCase` | `{{Entity}}Service` |
| Méthode | `camelCase` | `findAllBy{{Tenant}}` |
| Constante | `UPPER_SNAKE_CASE` | `MAX_FILE_SIZE` |
| Table SQL | `snake_case` pluriel | `{{entities}}` |
| Colonne SQL | `snake_case` | `user_id` |

### Layering obligatoire

```
Controller → Service → Repository
```

- Le controller ne contient aucune logique métier
- Le service contient toute la logique métier
- Le repository ne contient que des requêtes de données
- Pas de logique métier dans les entités JPA

### Multi-tenant — Règle absolue

Toute requête accédant à des données doit filtrer par `user_id`.
Un utilisateur ne peut accéder qu'aux données de son utilisateur.
Ce filtre est appliqué au niveau service, pas uniquement au niveau SQL.

```java
// Correct
{{entity}}Repository.findByIdAnd{{TenantIdField}}(id, {{tenantId}});

// Interdit
{{entity}}Repository.findById(id); // sans vérification user_id
```

### Endpoints REST

- Nommage pluriel et en kebab-case : `/api/{{resources}}`, `/api/utilisateurs`
- Versioning : `/api/v1/...`
- Réponses cohérentes : 200, 201, 400, 403, 404, 409, 500
- Pas de logique dans les DTOs
- Séparer DTO de requête et DTO de réponse

### Gestion des erreurs

- Utiliser un `@ControllerAdvice` global
- Ne jamais exposer de stacktrace en réponse
- Messages d'erreur en anglais côté API, traduits côté frontend
- Toujours logger l'erreur technique, renvoyer un message générique au client

### Jobs asynchrones

- Tout traitement asynchrone lourd crée un enregistrement de job avant de démarrer
- Mettre à jour le statut du job à chaque étape (PENDING → RUNNING → DONE / FAILED)
- Gérer les cas d'échec avec `error_message` et retry si applicable

---

## Frontend — Angular

### Structure des modules

```
src/app/
  ├── core/               → services globaux, guards, interceptors
  ├── shared/             → composants, pipes, directives réutilisables
  ├── features/
  │   ├── auth/
  │   ├── {{feature_1}}/
  │   ├── {{feature_2}}/
  │   └── {{feature_3}}/
  └── layout/             → shell, navigation, header
```

### Nommage

| Élément | Convention | Exemple |
|---------|-----------|---------|
| Composant | `kebab-case` | `{{entity}}-detail` |
| Classe | `PascalCase` | `{{Entity}}DetailComponent` |
| Service | `PascalCase` + `Service` | `{{Entity}}Service` |
| Observable | `camelCase` + `$` | `{{entities}}$` |
| Interface | `PascalCase` | `{{Entity}}` |

### Règles Angular

- Un composant = une responsabilité
- Les composants ne font pas d'appels HTTP directs → passer par un service
- Les services ne manipulent pas le DOM
- Utiliser `AsyncPipe` pour les observables dans les templates
- Pas de logique dans les templates au-delà des conditions simples

### Gestion des erreurs HTTP

- Interceptor global pour les 401 (redirect login) et 403 (message utilisateur)
- Afficher un message explicite à l'utilisateur, ne jamais afficher de détail technique

---

## Base de données — PostgreSQL

### Migrations

- Toutes les migrations via Liquibase
- Nommage : `{NNN}-{description}.xml` (ex: `001-init-schema.xml`, `002-add-{{entity}}.xml`)
- Emplacement : `src/main/resources/db/changelog/migrations/`
- Inclus via `db.changelog-master.xml` avec `<includeAll>`
- Une migration = un changement cohérent
- Jamais de migration destructive sans migration de sauvegarde préalable
- Pas de modification d'une migration déjà appliquée en production

### Contraintes obligatoires

- Toutes les FK ont une contrainte `REFERENCES` explicite
- Toutes les tables ont `created_at TIMESTAMP NOT NULL DEFAULT NOW()`
- Les colonnes `user_id` ont toujours une FK vers `utilisateurs(id)`
- Index obligatoires sur toutes les colonnes `user_id`

### Conventions SQL

```sql
-- Correct
SELECT e.id, e.name
FROM {{entities}} e
WHERE e.user_id = :{{tenantId}}
  AND e.status = 'ACTIVE';

-- Interdit
SELECT * FROM {{entities}} WHERE id = 1;
```

---

## Git

### Nommage des branches

| Type | Format | Exemple |
|------|--------|---------|
| Feature / subfeature | `feat/SF-XX-nom-court` | `feat/SF-01-create-{{entity}}` |
| Bugfix | `fix/description-courte` | `fix/user_id-isolation-missing` |
| Refactor | `refactor/description` | `refactor/{{entity}}-service` |

### Commits

- Format : `type(scope): description courte`
- Types : `feat`, `fix`, `refactor`, `test`, `docs`, `chore`
- Exemples :
  - `feat({{module}}): add {{entity}} creation endpoint`
  - `test(auth): add utilisateur member isolation tests`
  - `fix({{module}}): handle job failure status update`
- Un commit = une modification cohérente
- Pas de commits "WIP" ou "fix fix fix"

---

## Ce qui est interdit

- Logique métier dans les controllers ou les entités
- Accès aux données sans filtre `user_id`
- Commit direct sur `main`
- Migration SQL modifiant une colonne existante sans étape de compatibilité
- Stacktrace exposée dans une réponse API
- Lancer un traitement lourd de façon synchrone
