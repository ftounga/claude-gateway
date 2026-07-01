# Workflow de développement — Feature → Subfeature

Ce document décrit le cycle standard de développement d'une feature dans le projet.

---

## Vue globale

Le workflow complet est le suivant :

1. Feature kickoff
2. Découpage en subfeatures
3. Sélection d’une subfeature
4. Mini-spec + critères d’acceptation
5. Plan de test
6. Implémentation
7. Review
8. Vérification Definition of Done
9. Passage à la subfeature suivante

---

## Correspondance avec les prompts

| Étape | Prompt |
|------|--------|
| Démarrage feature | feature-kickoff-prompt-template.md |
| Implémentation | subfeature-implementation-prompt-template.md |
| Review | review-prompt-template.md |
| DoD | dod-check-prompt-template.md |
| Subfeature suivante | next-subfeature-prompt-template.md |
| Reprise feature | feature-progress-orchestrator-prompt-template.md |

---

## Cycle réel d’utilisation

Exemple d’interaction :

1. Démarrage
   "Exécute le workflow projet pour la Feature X"

2. Sélection
   "On commence par SF-XX-01"

3. Implémentation
   "Implémente SF-XX-01"

4. Review
   "Review cette implémentation selon la gouvernance"

5. DoD
   "Vérifie si la sous-feature est Done"

6. Suite
   "Passons à la subfeature suivante"

---

## Règles importantes

- Ne jamais implémenter toute une feature d’un coup
- Toujours travailler subfeature par subfeature
- Toujours passer par mini-spec + tests avant dev
- Toujours faire review + DoD
- Toujours valider avant de passer à la suivante

---

## Objectif

Garantir :
- qualité
- traçabilité
- testabilité
- itération maîtrisée