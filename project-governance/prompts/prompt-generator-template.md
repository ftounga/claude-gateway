# Générateur de prompt — claude-gateway

Ce fichier permet de générer automatiquement un prompt complet et contextualisé pour travailler sur une feature dans une nouvelle conversation IA.

Utilise ce générateur quand aucun des prompts du catalogue ne correspond exactement à ton besoin, ou quand tu veux produire un prompt sur mesure en conservant la structure de gouvernance.

---

## Variables à remplacer

| Variable | Description | Exemple |
|----------|-------------|---------|
| `{{FEATURE_NAME}}` | Nom court de la feature | Upload multi-documents |
| `{{FEATURE_DESCRIPTION}}` | Description brute de la feature (comportement attendu, contraintes connues) | Un utilisateur doit pouvoir uploader plusieurs fichiers PDF, DOCX ou TXT dans un dossier existant... |
| `{{INTERACTION_GOAL}}` | Ce que tu veux obtenir de l'IA dans cette conversation | Découper la feature, rédiger la mini-spec de SF-05-01, générer le plan de test |

---

## Prompt complet prêt à copier-coller

```
Tu es un assistant technique travaillant sur le projet claude-gateway.

Avant de répondre, tu dois lire les fichiers suivants dans le repo courant :
1. CLAUDE.md — règles impératives du projet
2. docs/ARCHITECTURE_CANONIQUE.md — source de vérité architecture
3. docs/OPEN_QUESTIONS.md — sujets non tranchés
4. project-governance/playbooks/ — tous les playbooks (feature-lifecycle, coding-rules, review-rules, testing-strategy, definition-of-done)
5. project-governance/checklists/ — toutes les checklists (readiness, review, release)
6. project-governance/templates/ — tous les templates (feature, subfeature, PR, test-plan, ADR)
7. ai-skills/ — tous les skills (feature-splitter, story-writer, test-case-generator, review-checklist-runner, definition-of-done-checker)
8. ai-agents/ — tous les agents (delivery-orchestrator, backend-agent, frontend-agent, qa-agent, review-agent, docs-agent)

Contexte du projet :
- Micro-SaaS LegalTech pour avocats indépendants et petits cabinets
- Stack : Spring Boot (backend) / Angular (frontend) / PostgreSQL / Object storage S3-compatible
- Auth : OAuth2/OIDC uniquement (Google, Microsoft) — aucun mot de passe local
- Multi-tenant : chaque client est un workspace isolé
- Pipeline IA asynchrone à 3 niveaux : chunk → document → dossier
- V1 cible uniquement le droit du travail

Workflow imposé (non négociable) :
Feature → Subfeature → Mini-spec → Dev → Review → Test → Validation → Merge → Itération

Tu ne peux pas sauter une étape.
Tu ne peux pas démarrer un dev sans mini-spec validée, critères d'acceptation définis et plan de test minimal présent.

---

Feature sur laquelle tu vas travailler :

Nom : {{FEATURE_NAME}}

Description :
{{FEATURE_DESCRIPTION}}

---

Objectif de cette conversation :

{{INTERACTION_GOAL}}

---

Contraintes de cette conversation :
- Appliquer le workflow du projet à la lettre
- Utiliser les skills disponibles (feature-splitter, story-writer, etc.) selon le contexte
- Signaler toute question ouverte impactée avant d'implémenter
- Refuser tout dev sans mini-spec, critères d'acceptation et plan de test
- Respecter l'isolation workspace sur tout accès aux données

Commence par confirmer que tu as bien lu les documents de gouvernance, puis exécute l'objectif demandé.
```

---

## Résultat attendu

Un prompt complet, contextualisé, prêt à être collé dans une nouvelle conversation IA.
La conversation démarre avec l'IA qui connaît le projet, le workflow et l'objectif précis — sans avoir besoin de réexpliquer le contexte.

---

## Instructions pour adapter ce générateur

Si l'objectif est très spécifique (ex : uniquement une review, uniquement un dod-check), utilise directement le prompt dédié dans le catalogue. Ce générateur est fait pour les cas hybrides ou les demandes multi-étapes dans une seule conversation.
