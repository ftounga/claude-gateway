# Feature Audit Prompt — claude-gateway

Template pour tester si le framework de gouvernance s'applique correctement à une feature.

Utilise ce prompt pour auditer le process, tester la robustesse du framework, ou valider qu'une feature est correctement gouvernée avant de démarrer le dev.

---

## Variables à remplacer

| Variable | Description | Exemple |
|----------|-------------|---------|
| `{{FEATURE_NAME}}` | Nom de la feature testée | Upload multi-documents |
| `{{FEATURE_DESCRIPTION}}` | Description brute complète de la feature | Un utilisateur doit pouvoir uploader plusieurs fichiers PDF, DOCX, TXT dans un dossier existant... |

---

## Prompt complet prêt à copier-coller

```
Tu es un assistant technique travaillant sur le projet claude-gateway.

LECTURE OBLIGATOIRE avant toute réponse :
Lis les fichiers suivants dans le repo courant :
1. CLAUDE.md
2. docs/ARCHITECTURE_CANONIQUE.md
3. docs/OPEN_QUESTIONS.md
4. project-governance/playbooks/feature-lifecycle.md
5. project-governance/playbooks/coding-rules.md
6. project-governance/playbooks/review-rules.md
7. project-governance/playbooks/testing-strategy.md
8. project-governance/playbooks/definition-of-done.md
9. project-governance/checklists/readiness-checklist.md
10. project-governance/checklists/review-checklist.md
11. project-governance/checklists/release-checklist.md
12. project-governance/templates/feature-template.md
13. project-governance/templates/subfeature-template.md
14. project-governance/templates/test-plan-template.md
15. project-governance/templates/pr-template.md
16. ai-skills/feature-splitter.md
17. ai-skills/story-writer.md
18. ai-skills/test-case-generator.md
19. ai-skills/review-checklist-runner.md
20. ai-skills/definition-of-done-checker.md

---

MODE AUDIT UNIQUEMENT.

Tu ne dois :
- modifier aucun fichier
- créer aucun fichier
- supprimer aucun fichier
- proposer aucun patch

Tu dois uniquement :
- lire les fichiers existants
- simuler le workflow complet
- produire un diagnostic

---

FEATURE À AUDITER :

Nom : {{FEATURE_NAME}}

Description :
{{FEATURE_DESCRIPTION}}

---

ÉTAPES D'AUDIT À EXÉCUTER DANS L'ORDRE :

1. Vérifier si la demande respecte les règles du projet (CLAUDE.md — Blocages automatiques)
2. Vérifier si la demande couvre une seule feature ou plusieurs (règle détection multi-features)
3. Appliquer le skill feature-splitter pour découper la feature
4. Évaluer la qualité du découpage selon les critères du skill
5. Proposer un ordre d'implémentation justifié
6. Sélectionner la première subfeature
7. Appliquer le skill story-writer pour produire la mini-spec
8. Évaluer la qualité des critères d'acceptation produits
9. Appliquer le skill test-case-generator pour produire le plan de test
10. Appliquer la readiness-checklist.md — verdict Ready for Dev
11. Simuler une implémentation hypothétique (liste des fichiers, classes, règles respectées)
12. Appliquer le skill review-checklist-runner — verdict review
13. Appliquer le skill definition-of-done-checker — verdict DoD

---

CONTRAINTES D'AUDIT :
- Mentionner explicitement chaque skill utilisé
- Citer les documents de gouvernance à chaque étape
- Signaler toute incohérence détectée dans le framework
- Signaler tout manque dans les templates ou checklists
- Ne modifier aucun fichier pendant l'audit

---

RÉSULTAT ATTENDU :

Partie 1 — Simulation complète du workflow (toutes les étapes)
Partie 2 — Tableau des anomalies détectées (ID, criticité, description, fichier concerné)
Partie 3 — Recommandations d'amélioration priorisées
Partie 4 — Verdict global : le framework gère-t-il correctement cette feature ?
```

---

## Résultat attendu

Un rapport d'audit complet avec :
- Simulation du workflow sur la feature testée
- Anomalies détectées dans le framework (lacunes, incohérences, manques)
- Verdict global sur la solidité du framework pour ce cas
- Recommandations priorisées pour améliorer le framework
