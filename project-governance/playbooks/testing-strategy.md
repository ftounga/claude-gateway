# Testing Strategy — claude-gateway

## Principe

Chaque subfeature doit être testée avant d'être considérée Done.
Les tests ne sont pas optionnels.
Un plan de test minimal est défini dans la mini-spec avant le début du dev.

---

## Pyramide de tests

```
          [ E2E ]           ← peu nombreux, flux critiques uniquement
        [ Intégration ]     ← endpoints, jobs, pipeline IA
      [ Unitaires ]         ← logique métier, transformations, règles
```

---

## 1 — Tests unitaires

**Périmètre :** Logique métier dans les services, transformations de données, règles de validation.

**Ce qui doit être testé :**
- Toutes les méthodes de service contenant une logique métier
- Les cas nominaux ET les cas d'erreur
- Les règles de validation (format, présence, cohérence)
- Les transformations entité ↔ DTO

**Ce qui n'est pas testé en unitaire :**
- Les controllers (testés en intégration)
- Les repositories JPA (testés en intégration)
- La configuration Spring

**Framework :** JUnit 5 + Mockito (backend) | Jasmine + Jest (frontend Angular)

**Exemple de structure :**
```java
@Test
void createCaseFile_shouldThrowException_whenWorkspaceNotFound() { ... }

@Test
void createCaseFile_shouldPersistAndReturnDto_whenValid() { ... }
```

---

## 2 — Tests d'intégration

**Périmètre :** Endpoints REST, pipeline IA, jobs asynchrones, requêtes SQL.

**Ce qui doit être testé :**
- Chaque endpoint exposé : nominal + cas d'erreur
- Les codes HTTP retournés (200, 201, 400, 403, 404, 409, 500)
- Le contenu des réponses (structure du JSON)
- **L'isolation workspace** : un utilisateur du workspace A ne doit pas voir les données du workspace B
- Les migrations SQL : la base de test doit être à jour

**Framework :** Spring Boot Test + `@SpringBootTest` + TestContainers (PostgreSQL)

**Règle isolation workspace — test obligatoire :**
```java
@Test
void getCaseFile_shouldReturn403_whenCaseFileBelongsToAnotherWorkspace() { ... }
```

**Jobs asynchrones :**
- Tester le changement d'état du job (PENDING → RUNNING → DONE)
- Tester le cas d'échec (FAILED + `error_message` renseigné)
- Ne pas tester le LLM réel en intégration → utiliser un mock du provider

---

## 3 — Tests E2E

**Périmètre :** Flux complets critiques, de l'UI jusqu'à la base de données.

**Flux à couvrir en priorité (V1) :**
1. Login OAuth → création workspace → redirection dashboard
2. Création dossier → upload document → lancement analyse → consultation résultat
3. Réponse à une question IA → déclenchement nouvelle synthèse

**Framework :** Cypress (Angular) ou Playwright

**Règle :** Les E2E ne remplacent pas les tests d'intégration. Ils valident les flux utilisateur critiques uniquement.

---

## 4 — Couverture des cas d'erreur

Pour chaque subfeature, les cas d'erreur suivants doivent être testés si applicables :

| Cas | Code attendu | Exemple |
|-----|-------------|---------|
| Données invalides | 400 | champ obligatoire absent |
| Non authentifié | 401 | token absent ou expiré |
| Accès interdit | 403 | mauvais workspace |
| Ressource absente | 404 | dossier inexistant |
| Conflit | 409 | slug workspace déjà pris |
| Résultat partiel | 207 | upload multi-fichiers avec certains fichiers rejetés |
| Erreur serveur | 500 | exception non gérée (testée via mock) |

**207 Multi-Status — règles de test :**

Utiliser 207 quand une opération porte sur plusieurs éléments et que le résultat varie par élément (succès partiel).

- La réponse doit contenir un tableau avec le statut de chaque élément traité
- Tester le cas nominal : tous les éléments réussis → préférer 200/201 au 207
- Tester le cas mixte : certains éléments réussis, d'autres rejetés → 207 avec détail par élément
- Tester le cas d'échec total : tous les éléments rejetés → 207 ou 400 selon la sémantique métier (à préciser dans la mini-spec)
- Vérifier que les éléments rejetés incluent un motif explicite dans le corps de la réponse

```java
// Exemple de structure de réponse 207
[
  { "filename": "contrat.pdf",  "status": "STORED",  "documentId": "uuid-1" },
  { "filename": "image.png",    "status": "REJECTED", "error": "MIME type non autorisé" }
]
```

---

## 5 — Tests spécifiques au pipeline IA

Le pipeline IA est asynchrone. Les tests ne doivent pas appeler le LLM réel.

**Stratégie :**
- Mocker le provider LLM dans les tests d'intégration
- Tester le découpage en chunks (logique déterministe)
- Tester la persistance des résultats d'analyse (structure, champs attendus)
- Tester la progression du job (`analysis_jobs`)
- Tester le déclenchement de la synthèse enrichie après réponse avocat

---

## 6 — Données de test

**Règles :**
- Toujours utiliser des données de test isolées (pas de données partagées entre tests)
- Nettoyer les données après chaque test (ou utiliser des transactions rollback)
- Ne jamais utiliser de données de production pour les tests

**Fixtures recommandées :**
- Workspace de test dédié
- Utilisateur de test avec rôle `LAWYER`
- Dossier de test vide + dossier de test avec documents

---

## 7 — Plan de test minimal (mini-spec)

Avant de démarrer le dev, le plan de test de la mini-spec doit spécifier :

```
Tests unitaires :
- [ ] Cas nominal : [description]
- [ ] Cas d'erreur : [description]

Tests d'intégration :
- [ ] Endpoint X → 201 avec payload valide
- [ ] Endpoint X → 400 avec champ manquant
- [ ] Endpoint X → 403 si workspace différent

Isolation workspace :
- [ ] Oui / Non applicable
```

Ce plan est validé avant le démarrage du dev et reviewé dans la PR.
