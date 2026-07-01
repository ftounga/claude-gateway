# Skill : definition-of-done-checker

---

## 1. Nom

`definition-of-done-checker`

---

## 2. Mission

Déterminer si une subfeature peut être considérée comme terminée selon la Definition of Done du projet, en produisant un verdict clair avec action de clôture pour chaque point manquant.

---

## 3. Quand utiliser ce skill

- Quand une subfeature est considérée "développée" et doit être validée avant de passer en review
- Quand le delivery-orchestrator vérifie si une subfeature peut être déclarée Done
- Quand le qa-agent finalise son rapport et doit confirmer la complétude
- Quand une PR est sur le point d'être mergée et qu'une validation finale de complétude est requise

---

## 4. Quand ne pas utiliser ce skill

- Quand la subfeature n'est pas encore développée — ce skill valide, il ne guide pas le dev
- Quand il s'agit de faire la review de code ligne par ligne — utiliser `review-checklist-runner`
- Quand le plan de test est à générer — utiliser `test-case-generator`

---

## 5. Inputs attendus

```
- Mini-spec de la subfeature (SF-XX-YY) avec critères d'acceptation
- Plan de test de la subfeature
- Code produit (liste des fichiers créés ou modifiés)
- Résultats des tests (passés / échoués)
- Résultats de CI
- Rapport QA (si disponible)
- Rapport de review (si disponible)
```

---

## 6. Préconditions

- [ ] La mini-spec est disponible avec les critères d'acceptation
- [ ] Le code est implémenté
- [ ] Les résultats des tests sont disponibles

Si les résultats de tests sont absents → signaler et demander avant de valider.

---

## 7. Processus d'exécution

**Étape 1 — Lire la DoD**
Charger `project-governance/playbooks/definition-of-done.md`. C'est la référence absolue. Chaque critère est évalué.

**Étape 2 — Évaluer les critères Code**
- Le code implémente exactement la mini-spec (ni plus, ni moins)
- Aucune logique dans controller / entité
- Filtre `workspace_id` sur tous les accès données
- Cas d'erreur gérés
- Aucune stacktrace en réponse
- DTOs de requête et réponse distincts
- Traitements IA asynchrones si applicable

**Étape 3 — Évaluer les critères Tests**
- Tests unitaires présents et passants
- Tests d'intégration présents et passants
- Cas d'erreur testés
- Isolation workspace testée (si applicable)
- Aucun test ignoré ou commenté
- Plan de test couvert

**Étape 4 — Évaluer les critères Base de données**
- Migration Flyway présente si changement de schéma
- Nommage conforme
- FK et index en place
- Stratégie de rollback documentée si applicable

**Étape 5 — Évaluer les critères Review**
- Template PR rempli
- Review effectuée selon les règles
- Aucun critère bloquant ouvert
- Review approuvée

**Étape 6 — Évaluer les critères CI / Build**
- Build vert
- Tous les tests CI verts
- Warnings non traités documentés si exception

**Étape 7 — Évaluer les critères Documentation**
- Contrat API mis à jour si endpoint modifié
- ADR créé si décision d'architecture prise
- `docs/OPEN_QUESTIONS.md` mis à jour si question tranchée
- `docs/ARCHITECTURE_CANONIQUE.md` mis à jour si nouvelle table

**Étape 8 — Évaluer les critères Sécurité**
- Aucune donnée sensible dans le code
- Endpoints protégés par les bons rôles
- Isolation workspace vérifiée

**Étape 9 — Émettre le verdict**
- `DONE` : tous les critères sont verts
- `PARTIELLEMENT DONE` : critères mineurs manquants, aucun bloquant
- `NOT DONE` : au moins un critère bloquant rouge

Pour chaque critère rouge → une action de clôture précise et actionnnable.

---

## 8. Output attendu

Un rapport DoD structuré avec verdict et actions de clôture.

Référence : `project-governance/playbooks/definition-of-done.md`

---

## 9. Règles strictes

- Tous les critères de la DoD sont évalués — aucun n'est ignoré ni supposé vert sans vérification
- Un critère rouge = action de clôture obligatoire, formulée précisément
- Le verdict `DONE` ne peut être émis qu'avec 100% de critères verts
- `PARTIELLEMENT DONE` uniquement si les critères manquants sont tous non bloquants (documentation, nommage mineur)
- `NOT DONE` si un critère de sécurité, tests ou CI est rouge
- Ne jamais approximer : "probablement couvert" n'est pas une validation
- Les actions de clôture sont des actions concrètes, pas des suggestions

---

## 10. Critères de qualité

- Chaque critère est évalué explicitement (✅ / ❌ / N/A)
- Chaque ❌ est associé à une action de clôture précise
- Le verdict est l'un des trois états : `DONE`, `PARTIELLEMENT DONE`, `NOT DONE`
- Les actions de clôture sont ordonnées par priorité (sécurité > tests > CI > documentation)
- Le rapport est lisible et exploitable directement

---

## 11. Cas de refus ou d'escalade

| Situation | Action |
|-----------|--------|
| Mini-spec absente | REFUS — impossible de valider sans mini-spec |
| Résultats de tests absents | REFUS — demander les résultats avant de valider |
| CI non exécutée | `NOT DONE` automatique sur le critère CI |
| Critère de sécurité rouge | `NOT DONE` — escalade vers review-agent |
| Décision d'architecture non documentée détectée | SIGNALEMENT vers docs-agent |
| Question ouverte tranchée mais OPEN_QUESTIONS.md non mis à jour | Action de clôture : mise à jour obligatoire |

---

## 12. Exemple d'utilisation

**Input :**
```
SF-01-01 — Créer un dossier
Tests unitaires : CaseFileServiceTest → 3 tests, tous verts
Tests d'intégration : CaseFileControllerIT → 4 tests, tous verts
Migration : V2__create_case_files.sql → appliquée
CI : verte
Review : approuvée
Template PR : rempli
Rapport QA : PASS
Endpoint créé : POST /api/v1/case-files → pas encore documenté dans ARCHITECTURE_CANONIQUE.md
```

**Output :**
```
CRITÈRES CODE
✅ Code conforme à la mini-spec
✅ Aucune logique dans controller
✅ Filtre workspace_id présent
✅ Cas d'erreur gérés (400, 403)
✅ Aucune stacktrace en réponse
✅ DTOs distincts

CRITÈRES TESTS
✅ Tests unitaires présents et verts (3/3)
✅ Tests intégration présents et verts (4/4)
✅ Isolation workspace testée
✅ Aucun test ignoré

CRITÈRES BASE DE DONNÉES
✅ Migration V2__create_case_files.sql présente
✅ FK et index en place

CRITÈRES REVIEW
✅ Template PR rempli
✅ Review approuvée

CRITÈRES CI
✅ Build vert, tous tests verts

CRITÈRES DOCUMENTATION
❌ Endpoint POST /api/v1/case-files non ajouté dans docs/ARCHITECTURE_CANONIQUE.md

CRITÈRES SÉCURITÉ
✅ Aucune donnée sensible
✅ Endpoint protégé (LAWYER)
✅ Isolation workspace vérifiée

VERDICT : PARTIELLEMENT DONE

ACTIONS DE CLÔTURE
1. [docs-agent] Ajouter POST /api/v1/case-files dans docs/ARCHITECTURE_CANONIQUE.md section 6
```

---

## 13. Format de réponse attendu

```markdown
## Rapport DoD — [SF-XX-YY] [Titre]
Date : YYYY-MM-DD

### Critères Code
[✅ / ❌ / N/A] [Critère] — [précision]
...

### Critères Tests
[✅ / ❌ / N/A] [Critère] — [précision]
...

### Critères Base de données
[✅ / ❌ / N/A] [Critère] — [précision]
...

### Critères Review
[✅ / ❌ / N/A] [Critère] — [précision]
...

### Critères CI / Build
[✅ / ❌ / N/A] [Critère] — [précision]
...

### Critères Documentation
[✅ / ❌ / N/A] [Critère] — [précision]
...

### Critères Sécurité
[✅ / ❌ / N/A] [Critère] — [précision]
...

---

### Verdict
**[DONE | PARTIELLEMENT DONE | NOT DONE]**

### Actions de clôture (si applicable)
Ordonnées par priorité :
1. [Catégorie] [Action précise et actionnable]
2. [Catégorie] [Action précise et actionnable]

---
Prochaine étape :
→ Si DONE : confirmer au delivery-orchestrator — subfeature prête pour merge
→ Si PARTIELLEMENT DONE : exécuter les actions de clôture, puis re-checker
→ Si NOT DONE : retourner au dev / qa-agent avec la liste des points bloquants
```
