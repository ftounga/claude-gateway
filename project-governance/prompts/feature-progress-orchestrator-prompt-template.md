# Feature Progress Orchestrator Prompt — claude-gateway

Template pour reprendre une feature en cours et déterminer la prochaine étape logique.

Utilise ce prompt quand tu reviens sur une feature après une interruption, ou quand tu ne sais plus où tu en es dans le cycle de dev.

---

## Variables à remplacer

| Variable | Description | Exemple |
|----------|-------------|---------|
| `{{FEATURE_NAME}}` | Nom court de la feature | Création de dossier juridique |
| `{{FEATURE_DESCRIPTION}}` | Description originale de la feature | Un utilisateur authentifié doit pouvoir créer un dossier... |
| `{{CURRENT_STATE}}` | Ce que tu sais de l'état actuel — subfeatures connues, mini-specs produites, code écrit, PR ouvertes, DoD vérifiés | SF-01 mini-spec validée, SF-01 implémentée, PR ouverte, review non encore faite. SF-02 non démarrée. |

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
7. project-governance/checklists/readiness-checklist.md
8. project-governance/checklists/review-checklist.md
9. project-governance/checklists/release-checklist.md
10. project-governance/templates/subfeature-template.md

Ne commence à travailler qu'une fois ces documents lus.

---

FEATURE EN COURS :

Nom : {{FEATURE_NAME}}

Description originale :
{{FEATURE_DESCRIPTION}}

État connu :
{{CURRENT_STATE}}

---

MISSION :

Tu es l'orchestrateur de cette feature. Ta mission est de :
1. Analyser l'état actuel en croisant les informations fournies avec les règles du framework
2. Identifier l'étape exacte où se trouve le travail
3. Déterminer la prochaine action concrète à exécuter
4. Produire un rapport d'état clair et un plan d'action précis

---

ANALYSE D'ÉTAT — appliquer dans l'ordre :

Étape A — Vérification du découpage
- Les subfeatures sont-elles définies ?
- Si non → la prochaine action est d'appliquer feature-splitter avant tout
- Si oui → lister les subfeatures identifiées avec leur statut connu

Étape B — Pour chaque subfeature, déterminer son statut :
| Statut | Condition |
|--------|-----------|
| Non démarrée | Pas de mini-spec |
| Mini-spec manquante | Subfeature identifiée mais pas de mini-spec produite |
| Plan de test manquant | Mini-spec présente mais pas de plan de test |
| Ready for Dev | Mini-spec + plan de test + critères d'acceptation validés |
| En cours de dev | Implémentation démarrée, pas de PR |
| PR ouverte | Code produit, PR ouverte, review non faite |
| Review en attente | PR reviewée, changements demandés |
| Review validée | PR approuvée, DoD non vérifié |
| DoD à vérifier | Review validée, DoD non coché |
| Terminée | DoD vérifié, merge effectué |

Étape C — Identifier la subfeature active
- Quelle est la subfeature sur laquelle le travail doit reprendre ou avancer ?
- S'il y a une subfeature bloquée → identifier le blocage exact

Étape D — Déterminer la prochaine action

Selon le statut de la subfeature active, la prochaine action est :

| Statut détecté | Prochaine action |
|----------------|-----------------|
| Pas de subfeatures | Appliquer feature-splitter — utiliser feature-kickoff-prompt-template.md |
| Mini-spec manquante | Produire la mini-spec via story-writer — utiliser feature-kickoff-prompt-template.md |
| Plan de test manquant | Produire le plan de test via test-case-generator |
| Not Ready for Dev | Compléter ce qui manque selon readiness-checklist.md |
| Ready for Dev | Lancer l'implémentation — utiliser subfeature-implementation-prompt-template.md |
| En cours de dev | Reprendre l'implémentation — utiliser subfeature-implementation-prompt-template.md |
| PR ouverte, review manquante | Faire la review — utiliser review-prompt-template.md |
| Review avec changements demandés | Appliquer les corrections puis re-review |
| Review validée, DoD manquant | Vérifier la DoD — utiliser dod-check-prompt-template.md |
| DoD ok, merge non fait | Merger la PR puis passer à la subfeature suivante — utiliser next-subfeature-prompt-template.md |
| Toutes les subfeatures terminées | Feature complète — documenter la clôture |

---

FORMAT DE RÉPONSE ATTENDU :

### État de la feature "{{FEATURE_NAME}}"

**Subfeatures identifiées :**
| ID | Nom | Statut |
|----|-----|--------|
| SF-01 | ... | ... |
| SF-02 | ... | ... |

**Subfeature active :** SF-XX — [Nom]
**Statut détecté :** [Statut]
**Blocage éventuel :** [Description ou "Aucun"]

**Prochaine action :**
[Description précise de la prochaine action]

**Prompt à utiliser :**
[Nom du prompt template à utiliser pour cette étape]

**Ce qui ne doit pas être fait maintenant :**
[Ce qu'il ne faut pas faire pour ne pas brûler une étape]

---

CONTRAINTES :
- Ne produis pas de code dans cette conversation
- Ne saute aucune étape du cycle Feature → Subfeature → Mini-spec → Dev → Review → DoD → Merge
- Signale toute question ouverte impactée (docs/OPEN_QUESTIONS.md)
- Si l'état fourni est insuffisant pour déterminer le statut → poser les questions nécessaires une par une
- Ne pas produire de mini-spec ni de plan de test dans cette conversation — uniquement l'analyse d'état et l'orientation
```

---

## Résultat attendu

À l'issue de cette conversation :
- L'état de la feature est cartographié avec précision
- La subfeature active est identifiée
- La prochaine action est clairement définie
- Le prompt à utiliser pour l'étape suivante est indiqué

L'orchestrateur ne fait pas le travail — il oriente vers le bon prompt pour la bonne étape.
