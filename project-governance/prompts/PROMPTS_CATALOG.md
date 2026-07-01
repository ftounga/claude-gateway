/context# Catalogue des prompts du projet — claude-gateway

Ce catalogue recense tous les prompts standardisés du projet.
Chaque prompt est un template prêt à copier-coller dans une nouvelle conversation IA (Claude Code, Claude.ai, ChatGPT).

Objectif : garantir que le framework de gouvernance s'applique de façon cohérente quelle que soit la conversation, quel que soit l'interlocuteur IA.

---

## Règle d'utilisation

1. Identifier le contexte de travail dans le tableau ci-dessous
2. Ouvrir le fichier correspondant
3. Remplacer les variables `{{VARIABLE}}` par les valeurs réelles
4. Copier-coller le prompt dans la conversation IA
5. Ne pas modifier la structure du prompt — elle est calibrée pour déclencher le bon comportement

---

## Liste des prompts disponibles

| Prompt | Objectif | Quand l'utiliser | Fichier |
|--------|----------|-----------------|---------|
| **prompt-generator** | Générer automatiquement un prompt contextualisé pour n'importe quelle feature | Quand tu veux créer un prompt sur mesure sans partir de zéro | `prompt-generator-template.md` |
| **feature-kickoff** | Démarrer une feature selon le framework complet | Début de travail sur une nouvelle feature — avant tout dev | `feature-kickoff-prompt-template.md` |
| **feature-audit** | Tester si le framework s'applique correctement à une feature | Audit qualité, validation du process, test de robustesse | `feature-audit-prompt-template.md` |
| **subfeature-implementation** | Implémenter une subfeature déjà Ready for Dev | Quand la mini-spec est validée et le dev peut démarrer | `subfeature-implementation-prompt-template.md` |
| **review** | Effectuer une review selon les checklists du projet | Quand une PR est ouverte et doit être reviewée | `review-prompt-template.md` |
| **dod-check** | Vérifier la Definition of Done | Avant tout merge — validation finale de complétude | `dod-check-prompt-template.md` |
| **feature-progress-orchestrator** | Reprendre une feature en cours et identifier la prochaine étape exacte | Quand tu reviens sur une feature après une interruption ou que tu ne sais plus où tu en es | `feature-progress-orchestrator-prompt-template.md` |
| **next-subfeature** | Passer à la subfeature suivante après clôture de la précédente | Une fois la subfeature courante mergée et son DoD vérifié — pour démarrer le cycle de la suivante | `next-subfeature-prompt-template.md` |

---

## Chaîne d'utilisation naturelle

```
feature-kickoff
  → subfeature-implementation   (quand mini-spec validée)
    → review                    (quand code produit)
      → dod-check               (avant merge)
        → next-subfeature       (subfeature suivante du même feature)
          → subfeature-implementation → review → dod-check → ...

feature-progress-orchestrator   (reprise après interruption — détecte où on en est)
feature-audit                   (à tout moment pour tester le framework)
prompt-generator                (pour créer un prompt sur mesure)
```

---

## Référence gouvernance

Ces prompts s'appuient sur :
- `CLAUDE.md` — règles impératives du projet
- `docs/ARCHITECTURE_CANONIQUE.md` — source de vérité architecture
- `project-governance/playbooks/` — cycles de vie, coding rules, review rules, testing strategy, DoD
- `project-governance/checklists/` — readiness, review, release
- `project-governance/templates/` — feature, subfeature, PR, test plan, ADR
- `ai-skills/` — feature-splitter, story-writer, test-case-generator, review-checklist-runner, definition-of-done-checker
- `ai-agents/` — delivery-orchestrator et agents spécialisés
