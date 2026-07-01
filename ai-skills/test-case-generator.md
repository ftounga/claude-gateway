# Skill : test-case-generator

---

## 1. Nom

`test-case-generator`

---

## 2. Mission

Générer un plan de test minimal mais sérieux pour une subfeature, relié aux critères d'acceptation, couvrant les cas nominaux, les cas d'erreur, les limites et les régressions évidentes.

---

## 3. Quand utiliser ce skill

- Quand une mini-spec est rédigée et validée et qu'il faut produire le plan de test avant le dev
- Quand le plan de test d'une mini-spec est absent ou insuffisant
- Quand le qa-agent demande un plan de test structuré à partir des critères d'acceptation
- Quand le delivery-orchestrator vérifie qu'une mini-spec est prête (`readiness-checklist.md`)

---

## 4. Quand ne pas utiliser ce skill

- Quand la mini-spec n'est pas encore rédigée — utiliser `story-writer` d'abord
- Quand le plan de test existe déjà et est complet — passer directement au dev
- Quand il s'agit de valider une implémentation existante — utiliser `review-checklist-runner` ou `definition-of-done-checker`

---

## 5. Inputs attendus

```
- Mini-spec complète de la subfeature (ou section "critères d'acceptation" + "cas d'erreur")
- Stack concernée : backend / frontend / les deux
- Endpoints exposés (méthode, URL, rôles)
- Tables impactées
- Indication sur l'isolation workspace (applicable ou non)
- Indication sur le pipeline IA (applicable ou non)
```

---

## 6. Préconditions

- [ ] La mini-spec est rédigée et contient des critères d'acceptation
- [ ] Les cas d'erreur sont listés dans la mini-spec
- [ ] L'isolation workspace est explicitement indiquée (applicable / non applicable)
- [ ] La stack est connue (backend / frontend / les deux)

Si une précondition est manquante → signaler et demander avant de générer.

---

## 7. Processus d'exécution

**Étape 1 — Lire les critères d'acceptation**
Chaque critère d'acceptation doit avoir au moins un test associé. C'est le point de départ obligatoire.

**Étape 2 — Identifier les niveaux de test nécessaires**
- **Unitaire** : logique métier dans le service (calculs, règles, transformations)
- **Intégration** : endpoints REST, jobs, requêtes SQL
- **E2E** : flux complet critique uniquement (pas systématique)

**Étape 3 — Générer les cas nominaux**
Pour chaque endpoint ou comportement : cas d'entrée valide → résultat attendu → code HTTP.

**Étape 4 — Générer les cas d'erreur**
Couvrir systématiquement :
- Donnée invalide ou manquante (400)
- Non authentifié (401) si applicable
- Accès interdit / mauvais workspace (403)
- Ressource absente (404)
- Conflit si applicable (409)
- Erreur interne simulée (500) via mock

**Étape 5 — Identifier les cas limites**
- Valeurs limites : champ vide, chaîne trop longue, liste vide, pagination page 0 / page max
- Comportements aux frontières : premier élément, dernier élément, collection vide

**Étape 6 — Identifier les régressions évidentes**
Ce qui pourrait casser dans le code existant si cette subfeature est mal implémentée. À tester explicitement.

**Étape 7 — Vérifier l'isolation workspace**
Si la subfeature accède à des données : ajouter un test obligatoire "un utilisateur du workspace A ne voit pas les données du workspace B".

**Étape 8 — Vérifier le pipeline IA (si applicable)**
- Tester l'état du job (PENDING → RUNNING → DONE / FAILED)
- Utiliser un mock du LLM — ne jamais appeler le vrai LLM dans les tests

---

## 8. Output attendu

Un plan de test structuré, relié aux critères d'acceptation, prêt à être intégré dans la mini-spec ou transmis au qa-agent.

Utiliser le template `project-governance/templates/test-plan-template.md`.

---

## 9. Règles strictes

- Chaque critère d'acceptation doit avoir au moins un test associé — aucun critère sans test
- Les cas d'erreur de la mini-spec doivent tous avoir un test d'intégration associé
- L'isolation workspace est testée dès qu'un accès à des données est présent — aucune exception
- Ne pas générer de tests redondants sans valeur (ne pas tester la même chose deux fois sous des noms différents)
- Ne jamais appeler le LLM réel dans un test — toujours mocker le provider IA
- Les tests E2E sont réservés aux flux critiques — ne pas en générer systématiquement
- Chaque test est identifié (U-01, I-01, W-01, A-01, E-01) pour la traçabilité

---

## 10. Critères de qualité

- Chaque critère d'acceptation est couvert par au moins un test
- Les cas 400, 403, 404 sont couverts pour chaque endpoint exposé
- L'isolation workspace est testée si applicable
- Les tests sont nommés et identifiables
- Les tests sont distincts et non redondants
- Le plan est lisible et exploitable directement par un développeur

---

## 11. Cas de refus ou d'escalade

| Situation | Action |
|-----------|--------|
| Mini-spec absente | REFUS — utiliser `story-writer` d'abord |
| Critères d'acceptation absents | REFUS — impossible de générer des tests sans critères |
| Isolation workspace non indiquée | DEMANDER — ne pas supposer |
| Pipeline IA présent sans mock identifié | SIGNALEMENT — noter l'obligation de mocker le LLM |
| Demande de tests E2E systématiques | REFUS — les E2E sont réservés aux flux critiques |

---

## 12. Exemple d'utilisation

**Input :**
```
SF-01-02 — Lister les dossiers du workspace
Endpoint : GET /api/v1/case-files (rôle : MEMBER)
Table : case_files (filtre workspace_id)
Critères d'acceptation :
- Retourne uniquement les dossiers du workspace de l'utilisateur connecté
- Pagination : page + size
- 403 si workspace différent
- 400 si paramètre page invalide
Isolation workspace : applicable
```

**Output :**
```
Tests unitaires :
U-01 — CaseFileService.findAllByWorkspace — retourne la liste filtrée par workspace_id
U-02 — CaseFileService.findAllByWorkspace — lève une exception si workspace inconnu

Tests d'intégration :
I-01 — GET /api/v1/case-files → 200, liste de dossiers du workspace
I-02 — GET /api/v1/case-files?page=0&size=10 → 200, max 10 éléments
I-03 — GET /api/v1/case-files → 401 si token absent
I-04 — GET /api/v1/case-files → 400 si page=invalide
I-05 — GET /api/v1/case-files → 200, liste vide si aucun dossier

Isolation workspace :
W-01 — Utilisateur workspace A → ne reçoit pas les dossiers du workspace B → 403

Cas limite :
I-06 — GET /api/v1/case-files?page=0&size=0 → 400 (size invalide)
I-07 — GET /api/v1/case-files?page=999 → 200, liste vide (pas d'erreur)
```

---

## 13. Format de réponse attendu

```markdown
## Plan de test — [SF-XX-YY] [Titre]

### Tests unitaires
| ID | Classe | Description | Résultat attendu |
|----|--------|-------------|-----------------|
| U-01 | [NomServiceTest] | [description] | [résultat] |

### Tests d'intégration
| ID | Endpoint | Contexte | Code HTTP | Corps |
|----|----------|---------|-----------|-------|
| I-01 | GET /api/v1/[resource] | payload valide | 200 | [description] |
| I-02 | GET /api/v1/[resource] | token absent | 401 | — |
| I-03 | GET /api/v1/[resource] | workspace différent | 403 | — |

### Isolation workspace
| ID | Description | Résultat attendu |
|----|-------------|-----------------|
| W-01 | Workspace A ne voit pas workspace B | 403 |

### Cas limites
| ID | Description | Résultat attendu |
|----|-------------|-----------------|
| I-0X | [description] | [résultat] |

### Pipeline IA (si applicable)
| ID | Description | Mock LLM | Résultat attendu |
|----|-------------|----------|-----------------|

### Traçabilité critères d'acceptation
| Critère | Tests couvrants |
|---------|----------------|
| [Critère 1] | U-01, I-01 |
| [Critère 2] | I-03, W-01 |

---
Prochaine étape : → Intégrer dans la mini-spec SF-XX-YY et transmettre au delivery-orchestrator
```
