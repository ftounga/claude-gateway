# Plan de test — [FEAT-XX / SF-YY] Titre

> Ce document est rempli avant le démarrage du dev.
> Il est attaché à la mini-spec et reviewé dans la PR.
> Référence stratégie : `project-governance/playbooks/testing-strategy.md`

---

## Identifiant

`FEAT-XX / SF-YY`

## Date

YYYY-MM-DD

---

## Périmètre des tests

> Quelles parties du système sont testées par cette subfeature ?

- [ ] Service métier : [NomDuService]
- [ ] Endpoint : [METHODE /api/v1/resource]
- [ ] Migration SQL : [{NNN}-{description}.xml]
- [ ] Composant Angular : [NomDuComposant]
- [ ] Job asynchrone : [type de job]
- [ ] Pipeline IA (niveau) : chunk / document / dossier

---

## 1 — Tests unitaires

### Classe testée : [NomDuServiceTest]

| # | Description | Type | Résultat attendu |
|---|-------------|------|-----------------|
| U-01 | Cas nominal : [description] | nominal | [résultat] |
| U-02 | Cas d'erreur : [champ manquant] | erreur | Exception levée / message |
| U-03 | Isolation : [workspace invalide] | sécurité | Exception levée |
| U-04 | [...] | [...] | [...] |

---

## 2 — Tests d'intégration

### Endpoint(s) testés

| # | Méthode + URL | Payload / Params | Code HTTP attendu | Corps réponse |
|---|--------------|-----------------|------------------|---------------|
| I-01 | POST /api/v1/[resource] | payload valide | 201 | DTO créé |
| I-02 | POST /api/v1/[resource] | champ X manquant | 400 | message erreur |
| I-03 | GET /api/v1/[resource]/{id} | id inconnu | 404 | — |
| I-04 | GET /api/v1/[resource]/{id} | workspace différent | 403 | — |
| I-05 | [...] | [...] | [...] | [...] |

---

## 3 — Isolation workspace

- [ ] Applicable

| # | Description | Résultat attendu |
|---|-------------|-----------------|
| W-01 | Utilisateur workspace A tente d'accéder à une ressource workspace B | 403 Forbidden |
| W-02 | [Autre cas d'isolation si applicable] | [résultat] |

- [ ] Non applicable — raison : [...]

---

## 4 — Cas d'erreur à couvrir

| Code | Situation | Test couvrant |
|------|-----------|--------------|
| 400 | [description] | I-02 |
| 403 | Workspace différent | I-04, W-01 |
| 404 | [description] | I-03 |
| 409 | [description si applicable] | [référence] |
| 500 | Exception non gérée (mock) | [référence] |

---

## 5 — Tests pipeline IA (si applicable)

| # | Description | Mock LLM | Résultat attendu |
|---|-------------|----------|-----------------|
| A-01 | Lancement job → statut PENDING créé | Non | `analysis_jobs.status = PENDING` |
| A-02 | Exécution job → statut RUNNING puis DONE | Oui | `analysis_jobs.status = DONE` |
| A-03 | Échec LLM → statut FAILED + error_message | Oui (erreur simulée) | `analysis_jobs.status = FAILED` |
| A-04 | [...] | [...] | [...] |

---

## 6 — Tests E2E (si applicable)

> Uniquement si cette subfeature fait partie d'un flux critique de bout en bout.

| # | Flux testé | Outil |
|---|-----------|-------|
| E-01 | [description du flux complet] | Cypress / Playwright |

---

## Données de test nécessaires

- Workspace de test : `workspace_test_XX`
- Utilisateur de test : rôle [OWNER / LAWYER / MEMBER]
- Fixtures : [description des données nécessaires]

---

## Critères de succès du plan de test

- [ ] Tous les tests unitaires passent
- [ ] Tous les tests d'intégration passent
- [ ] L'isolation workspace est vérifiée
- [ ] Les cas d'erreur listés ci-dessus sont couverts
- [ ] Aucun test ignoré ou commenté
