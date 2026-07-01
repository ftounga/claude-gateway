# Next Subfeature Prompt — claude-gateway

Template pour passer explicitement à la subfeature suivante d'une feature en cours.

Utilise ce prompt une fois que la subfeature précédente est terminée (DoD vérifié, merge effectué) et que tu veux démarrer le cycle complet sur la subfeature suivante.

---

## Variables à remplacer

| Variable | Description | Exemple |
|----------|-------------|---------|
| `{{FEATURE_NAME}}` | Nom court de la feature parente | Création de dossier juridique |
| `{{COMPLETED_SUBFEATURE_ID}}` | Identifiant de la subfeature terminée | SF-01 |
| `{{COMPLETED_SUBFEATURE_NAME}}` | Nom de la subfeature terminée | Formulaire de création de dossier |
| `{{NEXT_SUBFEATURE_ID}}` | Identifiant de la subfeature à démarrer | SF-02 |
| `{{NEXT_SUBFEATURE_NAME}}` | Nom attendu de la subfeature suivante | Liste des dossiers avec pagination |
| `{{REMAINING_SUBFEATURES}}` | Liste brève des subfeatures restantes après celle-ci | SF-02, SF-03, SF-04 |

---

## Prompt complet prêt à copier-coller

```
Tu es un assistant technique travaillant sur le projet claude-gateway.

LECTURE OBLIGATOIRE avant toute réponse :
Lis les fichiers suivants dans le repo courant dans cet ordre :
1. CLAUDE.md
2. docs/ARCHITECTURE_CANONIQUE.md
3. docs/PRODUCT_SPEC.md
4. docs/OPEN_QUESTIONS.md
5. project-governance/playbooks/feature-lifecycle.md
6. project-governance/playbooks/definition-of-done.md
7. project-governance/playbooks/coding-rules.md
8. project-governance/playbooks/testing-strategy.md
9. project-governance/checklists/readiness-checklist.md
10. project-governance/templates/subfeature-template.md
11. project-governance/templates/test-plan-template.md
12. ai-skills/story-writer.md
13. ai-skills/test-case-generator.md

Ne commence à travailler qu'une fois ces documents lus.

---

CONTEXTE :

Feature parente : {{FEATURE_NAME}}

Subfeature terminée : {{COMPLETED_SUBFEATURE_ID}} — {{COMPLETED_SUBFEATURE_NAME}}
Statut confirmé : DoD vérifié, merge effectué.

Subfeature suivante à traiter : {{NEXT_SUBFEATURE_ID}} — {{NEXT_SUBFEATURE_NAME}}

Subfeatures restantes après celle-ci : {{REMAINING_SUBFEATURES}}

---

VÉRIFICATION PRÉALABLE :

Avant de démarrer le cycle de la subfeature suivante, vérifier que la subfeature précédente est bien terminée :

Checklist de clôture de {{COMPLETED_SUBFEATURE_ID}} :
- [ ] DoD vérifié et validé
- [ ] PR mergée sur la branche principale
- [ ] Aucune régression signalée
- [ ] Les questions ouvertes soulevées pendant le dev ont été documentées dans docs/OPEN_QUESTIONS.md si non résolues

Si l'une de ces conditions n'est pas remplie → STOP.
Ne pas démarrer {{NEXT_SUBFEATURE_ID}} tant que {{COMPLETED_SUBFEATURE_ID}} n'est pas entièrement close.
Indiquer ce qui bloque et le prompt à utiliser pour le résoudre.

---

MISSION :

Si la subfeature précédente est bien clôturée, démarrer le cycle complet de {{NEXT_SUBFEATURE_ID}} :

Étape 1 — Rappel du contexte de la subfeature suivante
Résumer ce que l'on sait de {{NEXT_SUBFEATURE_ID}} :
- Objectif attendu (tel que défini lors du découpage initial)
- Dépendances avec {{COMPLETED_SUBFEATURE_ID}}
- Contraintes connues ou questions ouvertes déjà identifiées

Étape 2 — Mini-spec (skill story-writer)
Produire la mini-spec complète de {{NEXT_SUBFEATURE_ID}} selon le template subfeature-template.md.
Inclure obligatoirement :
- Objectif
- Comportement nominal (flux principal)
- Cas d'erreur et comportements aux limites
- Critères d'acceptation numérotés et testables
- Valeurs initiales et données nécessaires
- Contraintes de validation (format, taille, règles métier)
- Périmètre explicitement hors-scope
- Éléments techniques (endpoint, composant Angular, entité JPA si applicable)
- Dépendances avec les subfeatures précédentes

Étape 3 — Plan de test (skill test-case-generator)
Produire le plan de test complet selon test-plan-template.md.
Couvrir obligatoirement :
- Tests unitaires (service, validator)
- Tests d'intégration (controller → repository)
- Tests d'isolation workspace_id
- Cas limites et cas d'erreur
Tracer chaque test vers son critère d'acceptation.

Étape 4 — Vérification Ready for Dev
Appliquer la readiness-checklist.md point par point.
Produire le verdict :
- ✅ READY FOR DEV — tout est en place, transmettre à subfeature-implementation-prompt-template.md
- ⚠️ READY avec réserves — indiquer les réserves et comment les lever
- ❌ NOT READY — indiquer précisément ce qui manque

---

CONTRAINTES :
- Ne produis pas de code dans cette conversation
- Ne démarrer {{NEXT_SUBFEATURE_ID}} qu'après confirmation de clôture de {{COMPLETED_SUBFEATURE_ID}}
- Signaler toute question ouverte impactée (docs/OPEN_QUESTIONS.md)
- Signaler toute incohérence avec docs/ARCHITECTURE_CANONIQUE.md
- Ne pas implémenter silencieusement une décision sur une question ouverte
- Si une contrainte de validation est absente → la documenter comme question ouverte plutôt que de l'inventer
```

---

## Résultat attendu

À l'issue de cette conversation :
- La clôture de la subfeature précédente est confirmée
- La mini-spec de {{NEXT_SUBFEATURE_ID}} est complète et validée
- Le plan de test est produit et tracé vers les critères d'acceptation
- Le verdict Ready for Dev est émis
- Les questions ouvertes soulevées sont documentées

La subfeature est prête à être transmise à `subfeature-implementation-prompt-template.md` pour le dev.
