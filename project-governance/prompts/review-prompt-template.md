# Review Prompt — claude-gateway

Template pour effectuer une review de code selon les checklists du projet.

Utilise ce prompt quand une PR est ouverte et doit être reviewée avant merge.

---

## Variables à remplacer

| Variable | Description | Exemple |
|----------|-------------|---------|
| `{{SUBFEATURE_ID}}` | Identifiant de la subfeature reviewée | SF-05-01 |
| `{{SUBFEATURE_TITLE}}` | Titre court | Endpoint POST upload multi-fichiers + persistance |
| `{{MINI_SPEC}}` | Contenu complet de la mini-spec validée | [contenu complet] |
| `{{ACCEPTANCE_CRITERIA}}` | Liste des critères d'acceptation numérotés | CA-01 : ... / CA-02 : ... |
| `{{PR_DIFF}}` | Diff complet de la PR (ou liste des fichiers modifiés) | [diff ou liste de fichiers] |
| `{{STACK}}` | Stack concernée | Backend Spring Boot / Frontend Angular / Les deux |

---

## Prompt complet prêt à copier-coller

```
Tu es un assistant technique travaillant sur le projet claude-gateway.

LECTURE OBLIGATOIRE avant toute réponse :
Lis les fichiers suivants dans le repo courant :
1. CLAUDE.md
2. docs/ARCHITECTURE_CANONIQUE.md
3. docs/PRODUCT_SPEC.md
4. project-governance/playbooks/coding-rules.md
5. project-governance/playbooks/review-rules.md
6. project-governance/playbooks/testing-strategy.md
7. project-governance/checklists/review-checklist.md
8. ai-skills/review-checklist-runner.md

---

SUBFEATURE EN REVIEW :

Identifiant : {{SUBFEATURE_ID}}
Titre : {{SUBFEATURE_TITLE}}
Stack : {{STACK}}

Mini-spec de référence :
{{MINI_SPEC}}

Critères d'acceptation :
{{ACCEPTANCE_CRITERIA}}

Code produit (diff ou fichiers modifiés) :
{{PR_DIFF}}

---

ÉTAPES DE REVIEW À EXÉCUTER DANS L'ORDRE :

Étape 1 — Conformité mini-spec
Vérifier que le code produit correspond exactement à la mini-spec.
Signaler tout écart : code manquant, comportement ajouté hors périmètre, cas d'erreur non traités.

Étape 2 — Couverture des critères d'acceptation
Vérifier que chaque critère d'acceptation est couvert par le code ou un test.
Produire un tableau de traçabilité : CA-XX → fichier → verdict (COUVERT / MANQUANT / PARTIEL).

Étape 3 — Sécurité et isolation workspace
Vérifier sur chaque accès aux données :
- Présence du filtre workspace_id (BLOQUANT si absent)
- Aucune donnée sensible exposée dans les réponses
- Endpoints protégés par rôle minimum
- Aucune stacktrace exposée dans les réponses API

Étape 4 — Architecture et conventions
Vérifier le respect du layering Controller → Service → Repository.
Vérifier que la logique métier est dans le Service, pas dans le Controller.
Vérifier les conventions de nommage (coding-rules.md).
Vérifier la présence de la migration Liquibase si le schéma a changé.

Étape 5 — Tests
Vérifier que les tests unitaires couvrent les cas du plan de test.
Vérifier que les tests d'intégration couvrent les endpoints et l'isolation workspace.
Vérifier qu'aucun test ne peut passer sans que la logique correspondante soit présente.

Étape 6 — Application du skill review-checklist-runner
Applique le skill review-checklist-runner.md.
Produis le verdict structuré.

---

FORMAT DU VERDICT FINAL :

Verdict : [OK POUR MERGE / OK AVEC RÉSERVES / BLOQUÉ]

Si BLOQUÉ :
- Lister chaque point bloquant avec : ID, description, fichier concerné, ligne si applicable
- Aucun merge ne peut avoir lieu tant que les bloquants ne sont pas levés

Si OK AVEC RÉSERVES :
- Lister les réserves (non bloquantes mais attendues avant merge ou dans la prochaine itération)
- Préciser si chaque réserve est à corriger avant merge ou en suivi

Si OK POUR MERGE :
- Confirmer explicitement que tous les critères d'acceptation sont couverts
- Confirmer que l'isolation workspace est validée

---

INTERDICTIONS :
- Ne pas valider une PR si un seul BLOQUANT est présent
- Ne pas proposer de modifications hors périmètre de la mini-spec
- Ne pas modifier les fichiers du repo pendant la review
```

---

## Résultat attendu

À l'issue de cette conversation :
- Tableau de traçabilité critères d'acceptation → code
- Liste des points bloquants (si présents)
- Liste des réserves (si présentes)
- Verdict final : OK POUR MERGE / OK AVEC RÉSERVES / BLOQUÉ
