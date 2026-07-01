# Feature Kickoff Prompt — claude-gateway

Template pour démarrer une feature selon le framework de gouvernance complet.

Utilise ce prompt au début de toute nouvelle feature, avant toute subfeature, avant tout dev.

---

## Variables à remplacer

| Variable | Description | Exemple |
|----------|-------------|---------|
| `{{FEATURE_NAME}}` | Nom court de la feature | Création de dossier juridique |
| `{{FEATURE_DESCRIPTION}}` | Description brute de la feature avec comportements attendus et contraintes connues | Un utilisateur authentifié doit pouvoir créer un dossier avec titre, domaine juridique et description optionnelle... |
| `{{INTERACTION_GOAL}}` | Ce que tu veux obtenir à l'issue de cette conversation | Mini-spec complète et plan de test pour la première subfeature, prêts pour validation |

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
6. project-governance/playbooks/coding-rules.md
7. project-governance/playbooks/review-rules.md
8. project-governance/playbooks/testing-strategy.md
9. project-governance/playbooks/definition-of-done.md
10. project-governance/checklists/readiness-checklist.md
11. project-governance/checklists/review-checklist.md
12. project-governance/checklists/release-checklist.md
13. project-governance/templates/feature-template.md
14. project-governance/templates/subfeature-template.md
15. project-governance/templates/test-plan-template.md
16. ai-skills/feature-splitter.md
17. ai-skills/story-writer.md
18. ai-skills/test-case-generator.md

Ne commence à travailler qu'une fois ces documents lus.

---

MODE DE TRAVAIL :

Tu dois respecter strictement le workflow suivant :
Feature → Subfeature → Mini-spec → Dev → Review → Test → Validation → Merge

Règles non négociables :
- Aucun dev ne démarre sans mini-spec validée
- Aucun dev ne démarre sans critères d'acceptation définis
- Aucun dev ne démarre sans plan de test minimal présent
- Toute feature doit être découpée en subfeatures indépendantes avant toute mini-spec
- Toute demande couvrant plusieurs features distinctes doit être refusée et séparée
- Toute question ouverte impactée doit être signalée avant d'avancer

---

FEATURE À TRAITER :

Nom : {{FEATURE_NAME}}

Description :
{{FEATURE_DESCRIPTION}}

---

OBJECTIF DE CETTE CONVERSATION :

{{INTERACTION_GOAL}}

---

ÉTAPES À EXÉCUTER DANS L'ORDRE :

Étape 1 — Détection multi-features
Vérifie si la description couvre une seule feature ou plusieurs.
Si plusieurs features distinctes → les identifier et demander arbitrage avant de continuer.

Étape 2 — Découpage (skill feature-splitter)
Applique le skill feature-splitter pour découper la feature en subfeatures.
Produis le tableau de subfeatures ordonné avec dépendances et risques.

Étape 3 — Sélection de la première subfeature
Propose la première subfeature à traiter selon l'ordre d'implémentation.

Étape 4 — Mini-spec (skill story-writer)
Produis la mini-spec complète de la première subfeature selon le template subfeature-template.md.
Inclure obligatoirement : objectif, comportement nominal, cas d'erreur, critères d'acceptation,
valeurs initiales, contraintes de validation, périmètre hors-scope, éléments techniques.

Étape 5 — Plan de test (skill test-case-generator)
Produis le plan de test complet selon test-plan-template.md.
Couvrir : unitaires, intégration, isolation workspace, cas limites.
Tracer chaque test vers son critère d'acceptation.

Étape 6 — Vérification Ready for Dev
Applique la readiness-checklist.md.
Produis le verdict : READY / READY avec réserves / NOT READY.
Si NOT READY → identifier ce qui manque avant de continuer.

---

CONTRAINTES :
- Ne produis pas de code dans cette conversation
- Signale toute incohérence avec docs/ARCHITECTURE_CANONIQUE.md
- Signale toute question ouverte impactée (docs/OPEN_QUESTIONS.md)
- Si une contrainte de validation est absente → la documenter ou la marquer comme question ouverte
```

---

## Résultat attendu

À l'issue de cette conversation :
- La feature est découpée en subfeatures ordonnées
- La mini-spec de la première subfeature est complète
- Le plan de test est produit et tracé
- Le verdict Ready for Dev est émis
- Les questions ouvertes impactées sont identifiées

La subfeature est prête à être transmise à `subfeature-implementation-prompt-template.md` pour le dev.
