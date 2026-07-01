# Subfeature Implementation Prompt — claude-gateway

Template pour implémenter une subfeature déjà validée Ready for Dev.

Utilise ce prompt uniquement quand la mini-spec est complète et validée, les critères d'acceptation sont définis, et le plan de test est présent.

---

## Variables à remplacer

| Variable | Description | Exemple |
|----------|-------------|---------|
| `{{SUBFEATURE_ID}}` | Identifiant de la subfeature | FEAT-05 / SF-05-01 |
| `{{SUBFEATURE_TITLE}}` | Titre court | Endpoint POST upload multi-fichiers + persistance |
| `{{MINI_SPEC}}` | Contenu complet de la mini-spec (copier-coller depuis le fichier SF-XX-YY.md) | [contenu complet] |
| `{{ACCEPTANCE_CRITERIA}}` | Liste des critères d'acceptation numérotés | CA-01 : ... / CA-02 : ... |
| `{{TEST_PLAN}}` | Plan de test (unitaires + intégration + isolation workspace) | U-01, I-01... |
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
5. project-governance/playbooks/testing-strategy.md
6. project-governance/playbooks/definition-of-done.md
7. project-governance/checklists/readiness-checklist.md

---

SUBFEATURE À IMPLÉMENTER :

Identifiant : {{SUBFEATURE_ID}}
Titre : {{SUBFEATURE_TITLE}}
Stack : {{STACK}}

Mini-spec complète :
{{MINI_SPEC}}

Critères d'acceptation :
{{ACCEPTANCE_CRITERIA}}

Plan de test :
{{TEST_PLAN}}

---

INSTRUCTIONS D'IMPLÉMENTATION :

Tu dois produire uniquement le code correspondant à cette subfeature.
Ne pas implémenter d'autres subfeatures, même si elles semblent naturellement liées.

Pour chaque fichier produit, tu dois :
1. Respecter le layering Controller → Service → Repository
2. Appliquer le filtre workspace_id sur tout accès aux données
3. Séparer les DTOs de requête et de réponse
4. Gérer tous les cas d'erreur définis dans la mini-spec
5. Ne jamais exposer de stacktrace dans les réponses API
6. Respecter les conventions de nommage de coding-rules.md

---

PRODUCTION ATTENDUE :

Backend (si applicable) :
- Controller (endpoint, validation, rôle minimum)
- Service (logique métier, filtre workspace_id)
- Repository (requêtes JPA)
- DTOs (request et response séparés)
- Migration Liquibase si changement de schéma
- Tests unitaires (NomServiceTest.java) — couvrant tous les cas du plan de test
- Tests d'intégration (NomControllerIT.java) — couvrant tous les endpoints + isolation workspace

Frontend (si applicable) :
- Composant Angular (HTML + TypeScript)
- Service Angular (appels HTTP uniquement, pas dans le composant)
- Tests unitaires du composant et du service
- Gestion des erreurs HTTP (403, 404, 500)

Pour chaque fichier produit :
- Indiquer le chemin complet
- Expliquer brièvement les décisions techniques
- Signaler toute décision non prévue dans la mini-spec

---

INTERDICTIONS :
- Ne pas implémenter d'autres subfeatures dans cette conversation
- Ne pas ajouter de fonctionnalités hors périmètre de la mini-spec
- Ne pas modifier l'architecture sans signaler la divergence
- Ne pas produire de code sans tests correspondants

---

VÉRIFICATION FINALE :
Avant de répondre, vérifie que :
- Chaque critère d'acceptation est couvert par le code ou un test
- L'isolation workspace est testée
- La migration Liquibase est présente si nécessaire
- Aucune stacktrace ne peut être exposée
```

---

## Résultat attendu

À l'issue de cette conversation :
- Code complet de la subfeature (backend et/ou frontend selon la stack)
- Tests unitaires et d'intégration couvrant le plan de test
- Migration Liquibase si applicable
- Explication des décisions techniques prises
- La subfeature est prête pour `review-prompt-template.md`
