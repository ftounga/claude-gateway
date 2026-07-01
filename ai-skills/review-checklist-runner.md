# Skill : review-checklist-runner

---

## 1. Nom

`review-checklist-runner`

---

## 2. Mission

Évaluer une implémentation ou une PR selon les règles de review du projet et produire un verdict clair, motivé et exploitable, avec liste précise des blocages.

---

## 3. Quand utiliser ce skill

- Quand une PR est ouverte et doit être reviewée avant merge
- Quand le qa-agent a produit son rapport PASS et transmet au review-agent
- Quand le delivery-orchestrator demande une évaluation de conformité avant merge
- Quand un développeur veut auto-évaluer sa PR avant de demander une review

---

## 4. Quand ne pas utiliser ce skill

- Quand la mini-spec n'est pas encore validée — la review ne peut pas s'effectuer sans mini-spec
- Quand le rapport QA est absent — exiger le rapport QA avant de lancer la review
- Quand il s'agit de valider la complétude fonctionnelle — utiliser `definition-of-done-checker`
- Quand il s'agit de générer des tests — utiliser `test-case-generator`

---

## 5. Inputs attendus

```
- Mini-spec validée de la subfeature (SF-XX-YY)
- Code diff de la PR (ou description des fichiers modifiés)
- Template PR rempli : project-governance/templates/pr-template.md
- Rapport QA fourni par qa-agent (statut PASS requis)
- Résultats de CI (build + tests)
```

---

## 6. Préconditions

- [ ] La mini-spec est disponible et validée
- [ ] Le rapport QA est présent avec statut PASS
- [ ] Le template PR est rempli
- [ ] Les résultats de CI sont disponibles (build + tests)

Si une précondition est manquante → signaler et bloquer jusqu'à résolution.

---

## 7. Processus d'exécution

**Étape 1 — Lire la mini-spec avant le code**
Comprendre l'intention avant d'évaluer l'implémentation. Identifier les critères d'acceptation.

**Étape 2 — Évaluer les critères bloquants**
Parcourir dans cet ordre :
1. Sécurité (isolation workspace, données sensibles, rôles, stacktrace)
2. Conformité mini-spec (périmètre, critères d'acceptation)
3. Tests (présents, passants, isolation workspace couverte)
4. Architecture (layering, migration, async IA)
5. CI (build vert, tests verts)

Un seul critère bloquant rouge → verdict `BLOQUÉ` immédiatement, inutile de continuer.

**Étape 3 — Évaluer les critères non bloquants**
Passer en revue : nommage, duplication, lisibilité, documentation. Formuler des suggestions sans bloquer.

**Étape 4 — Vérifier la release-checklist**
Appliquer `project-governance/checklists/release-checklist.md` avant d'approuver.

**Étape 5 — Produire le rapport**
Structurer le rapport selon le format standard. Chaque point est identifié, motivé, avec référence à la règle concernée.

**Étape 6 — Émettre le verdict**
- `OK POUR REVIEW` : tous les critères bloquants sont verts
- `OK AVEC RÉSERVES` : tous bloquants verts, points non bloquants à noter
- `BLOQUÉ` : au moins un critère bloquant rouge — liste des corrections requises

---

## 8. Output attendu

Un rapport de review structuré avec verdict clair.

Référence : `project-governance/playbooks/review-rules.md`
Référence : `project-governance/checklists/review-checklist.md`

---

## 9. Règles strictes

- Lire la mini-spec avant le code — toujours
- Évaluer les critères bloquants avant les non bloquants
- Un critère bloquant rouge = verdict `BLOQUÉ` — aucune exception
- Ne jamais émettre un verdict flou ("à voir", "probablement ok")
- Ne pas demander des changements sur des choix actés dans la mini-spec validée
- Ne pas proposer de refactoring hors périmètre de la subfeature
- Ne pas approuver si CI rouge
- Ne pas approuver sans rapport QA

Les critères bloquants sont définis dans `project-governance/playbooks/review-rules.md` :
- Isolation workspace non vérifiée
- Donnée sensible dans le code
- Endpoint sans protection de rôle
- Stacktrace dans réponse API
- Code hors mini-spec
- Critère d'acceptation non couvert
- Tests absents ou échoués
- Logique métier dans controller
- Migration absente si schéma modifié
- Traitement IA synchrone
- Build / CI rouge

---

## 10. Critères de qualité

- Chaque point bloquant est référencé à la règle qui le fonde
- Les corrections requises sont précises et actionnables
- Les suggestions non bloquantes sont clairement distinguées des blocages
- Le verdict est l'un des trois états exactement : `OK POUR REVIEW`, `OK AVEC RÉSERVES`, `BLOQUÉ`
- Le rapport est exploitable tel quel par le développeur pour corriger

---

## 11. Cas de refus ou d'escalade

| Situation | Action |
|-----------|--------|
| Mini-spec absente | REFUS — impossible de reviewer sans mini-spec |
| Rapport QA absent | REFUS — exiger le rapport QA avant review |
| CI rouge | BLOQUÉ automatiquement — ne pas continuer la review |
| Scope de la PR couvre plusieurs subfeatures | ESCALADE vers delivery-orchestrator — demander un redécoupage de PR |
| Divergence avec ARCHITECTURE_CANONIQUE.md non signalée | BLOQUÉ — signaler la divergence |

---

## 12. Exemple d'utilisation

**Input :**
```
SF-01-01 — Créer un dossier
Rapport QA : PASS
CI : verte
Critères d'acceptation :
- POST /api/v1/case-files → 201 avec dossier créé
- 403 si workspace différent
- 400 si titre manquant
Diff PR : CaseFileController, CaseFileService, CaseFileRepository, V2__create_case_files.sql,
CaseFileServiceTest, CaseFileControllerIT
```

**Output (extrait) :**
```
CRITÈRES BLOQUANTS
✅ Isolation workspace_id vérifiée dans CaseFileService.create()
✅ Aucune donnée sensible dans le diff
✅ Endpoint protégé : @PreAuthorize("hasRole('LAWYER')")
✅ Aucune stacktrace dans les réponses
✅ Code conforme à la mini-spec SF-01-01
✅ Critères d'acceptation couverts (201, 403, 400)
✅ Tests unitaires présents : CaseFileServiceTest
✅ Tests d'intégration présents : CaseFileControllerIT
✅ Isolation workspace testée dans CaseFileControllerIT
✅ Migration V2__create_case_files.sql présente
✅ CI verte

POINTS NON BLOQUANTS
- CaseFileController.java L42 : nommage de variable `cf` peu lisible → suggestion : `caseFile`

VERDICT : OK AVEC RÉSERVES
Suggestion : renommer `cf` en `caseFile` dans CaseFileController (non bloquant)
```

---

## 13. Format de réponse attendu

```markdown
## Rapport Review — [SF-XX-YY] [Titre]
Date : YYYY-MM-DD

### Critères bloquants
[✅ / ❌] Isolation workspace_id — [précision]
[✅ / ❌] Aucune donnée sensible — [précision]
[✅ / ❌] Endpoints protégés par rôle — [précision]
[✅ / ❌] Aucune stacktrace en réponse — [précision]
[✅ / ❌] Conformité mini-spec — [précision]
[✅ / ❌] Critères d'acceptation couverts — [précision]
[✅ / ❌] Tests unitaires présents et passants — [précision]
[✅ / ❌] Tests d'intégration présents et passants — [précision]
[✅ / ❌] Isolation workspace testée — [précision]
[✅ / ❌] Aucune logique dans controller — [précision]
[✅ / ❌] Migration présente si schéma modifié — [précision]
[✅ / ❌] CI verte — [précision]

### Points non bloquants
- [description + suggestion + référence fichier:ligne]

### Verdict
**[OK POUR REVIEW | OK AVEC RÉSERVES | BLOQUÉ]**

Corrections requises (si BLOQUÉ) :
- [Point bloquant 1 — action précise à effectuer]
- [Point bloquant 2 — action précise à effectuer]

---
Prochaine étape :
→ Si OK : transmettre au delivery-orchestrator pour merge
→ Si BLOQUÉ : retourner au dev avec la liste des corrections
```
