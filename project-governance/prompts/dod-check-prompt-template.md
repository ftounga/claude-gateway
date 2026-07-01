# DoD Check Prompt — claude-gateway

Template pour vérifier la Definition of Done avant tout merge.

Utilise ce prompt en dernière étape de validation, après la review, avant le merge.

---

## Variables à remplacer

| Variable | Description | Exemple |
|----------|-------------|---------|
| `{{SUBFEATURE_ID}}` | Identifiant de la subfeature | SF-05-01 |
| `{{SUBFEATURE_TITLE}}` | Titre court | Endpoint POST upload multi-fichiers + persistance |
| `{{MINI_SPEC}}` | Contenu complet de la mini-spec validée | [contenu complet] |
| `{{ACCEPTANCE_CRITERIA}}` | Liste des critères d'acceptation numérotés | CA-01 : ... / CA-02 : ... |
| `{{TEST_PLAN}}` | Plan de test (unitaires + intégration + isolation workspace) | U-01, I-01... |
| `{{FILES_PRODUCED}}` | Liste des fichiers produits (chemins complets) | [liste] |
| `{{CI_STATUS}}` | Statut CI (pass / fail / non exécuté) | pass |
| `{{REVIEW_VERDICT}}` | Verdict de la review précédente | OK POUR MERGE / OK AVEC RÉSERVES |

---

## Prompt complet prêt à copier-coller

```
Tu es un assistant technique travaillant sur le projet claude-gateway.

LECTURE OBLIGATOIRE avant toute réponse :
Lis les fichiers suivants dans le repo courant :
1. CLAUDE.md
2. docs/ARCHITECTURE_CANONIQUE.md
3. docs/PRODUCT_SPEC.md
4. project-governance/playbooks/definition-of-done.md
5. project-governance/checklists/release-checklist.md
6. ai-skills/definition-of-done-checker.md

---

SUBFEATURE EN VÉRIFICATION DOD :

Identifiant : {{SUBFEATURE_ID}}
Titre : {{SUBFEATURE_TITLE}}

Mini-spec de référence :
{{MINI_SPEC}}

Critères d'acceptation :
{{ACCEPTANCE_CRITERIA}}

Plan de test :
{{TEST_PLAN}}

Fichiers produits :
{{FILES_PRODUCED}}

Statut CI : {{CI_STATUS}}

Verdict review précédente : {{REVIEW_VERDICT}}

---

VÉRIFICATION DOD À EXÉCUTER :

Applique le skill definition-of-done-checker.md dans l'ordre exact de ses catégories.

Pour chaque catégorie, produis un tableau :
| Critère | Statut (OK / KO / N/A) | Justification |

Catégories à vérifier :

1. Code
   - Respect du layering Controller → Service → Repository
   - Logique métier dans le Service uniquement
   - DTOs de requête et réponse séparés
   - Aucune stacktrace exposable dans les réponses API
   - Conventions de nommage respectées (coding-rules.md)
   - Aucun code hors périmètre mini-spec

2. Tests
   - Tests unitaires présents pour tous les cas du plan de test
   - Tests d'intégration présents pour tous les endpoints
   - Isolation workspace testée (test multi-tenant explicite)
   - Couverture de tous les critères d'acceptation

3. Base de données
   - Migration Liquibase présente si changement de schéma
   - Nommage Liquibase respecté ({NNN}-{description}.xml)
   - Aucune modification manuelle de schéma hors migration

4. Review
   - Review effectuée
   - Verdict review : OK POUR MERGE ou OK AVEC RÉSERVES (pas BLOQUÉ)
   - Tous les points bloquants de la review levés

5. CI / Build
   - CI en succès (build + tests)
   - Aucun test ignoré ou désactivé
   - Statut : {{CI_STATUS}}

6. Documentation
   - Mini-spec mise à jour si comportement modifié en cours d'implémentation
   - Questions ouvertes impactées signalées dans docs/OPEN_QUESTIONS.md si nécessaire
   - ADR créé si décision architecturale prise

7. Sécurité
   - Filtre workspace_id présent sur tous les accès aux données
   - Aucune donnée sensible exposée dans les réponses
   - Endpoint protégé par rôle minimum
   - Aucune stacktrace ne peut être retournée à l'appelant

---

FORMAT DU VERDICT FINAL :

Verdict : [DONE / PARTIELLEMENT DONE / NOT DONE]

Si DONE :
- Confirmer explicitement que toutes les catégories sont vertes
- La subfeature est prête pour le merge

Si PARTIELLEMENT DONE :
- Lister les critères KO avec justification
- Indiquer si les KO bloquent le merge ou peuvent être suivis en post-merge
- Ne pas valider un merge si un KO touche à : sécurité, isolation workspace, CI, review bloquée

Si NOT DONE :
- Lister tous les critères KO
- Préciser les actions correctives requises avant que le merge soit possible

---

INTERDICTIONS :
- Ne pas valider DONE si un seul critère de sécurité ou d'isolation workspace est KO
- Ne pas valider DONE si la CI est en échec ou non exécutée
- Ne pas valider DONE si la review est encore BLOQUÉE
- Ne pas modifier les fichiers du repo pendant ce check
```

---

## Résultat attendu

À l'issue de cette conversation :
- Tableau de vérification complet par catégorie DoD
- Liste des critères KO avec justification
- Verdict final : DONE / PARTIELLEMENT DONE / NOT DONE
- Si DONE : la subfeature est prête pour le merge
