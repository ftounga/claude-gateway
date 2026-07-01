# Skill : story-writer

---

## 1. Nom

`story-writer`

---

## 2. Mission

Produire une mini-spec complète et exploitable pour une subfeature, à partir du template projet, prête à être validée par le delivery-orchestrator avant tout démarrage de dev.

---

## 3. Quand utiliser ce skill

- Quand une subfeature est identifiée (via `feature-splitter` ou directement) et qu'il faut rédiger sa mini-spec
- Quand le delivery-orchestrator demande une mini-spec avant de déléguer à un agent dev
- Quand une mini-spec existante est incomplète ou ambiguë et doit être reformulée

---

## 4. Quand ne pas utiliser ce skill

- Quand la feature n'a pas encore été découpée en subfeatures — utiliser `feature-splitter` d'abord
- Quand la mini-spec est déjà complète et validée — passer directement au dev
- Quand la demande concerne la validation d'une mini-spec — utiliser `review-checklist-runner`

---

## 5. Inputs attendus

```
- ID et titre de la subfeature (ex: SF-01-02 — Lister les dossiers du workspace)
- Feature parente et son objectif
- Périmètre inclus / exclus (issu du feature-splitter ou fourni directement)
- Stack concernée : backend / frontend / les deux
- Tables et endpoints pressentis (si connus)
- Contraintes métier ou techniques connues
- Questions ouvertes impactées (si connues)
```

---

## 6. Préconditions

- [ ] La subfeature est identifiée et a un ID (`SF-XX-YY`)
- [ ] Le périmètre inclus / exclus est délimité
- [ ] La stack cible est connue
- [ ] Les tables impactées sont cohérentes avec `docs/ARCHITECTURE_CANONIQUE.md`
- [ ] Les questions ouvertes bloquantes sont identifiées (`docs/OPEN_QUESTIONS.md`)

Si une précondition est manquante → signaler et demander avant de rédiger.

---

## 7. Processus d'exécution

**Étape 1 — Analyser le contexte**
Lire le périmètre, la stack et les contraintes. Identifier les zones de risque (isolation workspace, async, auth).

**Étape 2 — Formuler l'objectif**
Rédiger l'objectif en une phrase : "Permettre à [qui] de [quoi] dans le contexte de [quand/où]."

**Étape 3 — Décrire le comportement nominal**
Décrire le flux principal : entrée → traitement → sortie. Être précis sur ce qui est persisté, retourné, déclenché.

**Étape 4 — Lister les cas d'erreur**
Pour chaque cas d'erreur prévisible : situation → comportement attendu → code HTTP. Minimum 2 cas d'erreur.

**Étape 5 — Rédiger les critères d'acceptation**
Chaque critère est :
- Formulé comme un test ("Quand X, alors Y")
- Vérifiable sans ambiguïté
- Couvrant nominal ET cas d'erreur ET isolation workspace si applicable

**Étape 6 — Identifier les éléments techniques**
- Endpoints (méthode, URL, rôle minimum requis)
- Tables impactées (opération : INSERT / SELECT / UPDATE)
- Migration Flyway si nécessaire
- Composants Angular si applicable

**Étape 7 — Définir le plan de test minimal**
Lister les tests requis par type (unitaires, intégration, isolation workspace). Relier chaque test à un critère d'acceptation.

**Étape 8 — Signaler les questions ouvertes**
Si un arbitrage est nécessaire → référencer `docs/OPEN_QUESTIONS.md` et ne pas inventer de règle métier.

---

## 8. Output attendu

Un fichier mini-spec conforme au template `project-governance/templates/subfeature-template.md`, prêt à être transmis au delivery-orchestrator pour validation.

---

## 9. Règles strictes

- Ne jamais inventer une règle métier absente des inputs
- Si un arbitrage est nécessaire → le signaler et référencer `docs/OPEN_QUESTIONS.md`, ne pas trancher seul
- Les critères d'acceptation doivent tous être vérifiables — aucun critère flou ("l'interface est ergonomique")
- Minimum 2 cas d'erreur documentés pour toute subfeature avec un endpoint
- L'isolation workspace doit être couverte si la subfeature accède à des données
- Le périmètre hors-scope doit être explicite
- Le plan de test minimal est obligatoire — la mini-spec sans plan de test est incomplète
- Utiliser le template `project-governance/templates/subfeature-template.md`

---

## 10. Critères de qualité

- L'objectif est formulé en une phrase, sans ambiguïté
- Le comportement nominal est décrit pas à pas (entrée → traitement → sortie)
- Chaque cas d'erreur a un code HTTP associé
- Chaque critère d'acceptation est vérifiable et non ambigu
- Le périmètre hors-scope est explicite
- Le plan de test couvre nominal, cas d'erreur et isolation workspace
- Les questions ouvertes sont référencées, pas ignorées

---

## 11. Cas de refus ou d'escalade

| Situation | Action |
|-----------|--------|
| Subfeature non identifiée (pas d'ID) | REFUS — utiliser `feature-splitter` d'abord |
| Règle métier manquante et non dérivable | SIGNALEMENT — noter dans "Points ouverts" de la mini-spec, référencer OPEN_QUESTIONS.md |
| Incohérence avec ARCHITECTURE_CANONIQUE.md | BLOCAGE — signaler la divergence avant de rédiger |
| Périmètre non délimité | REFUS — demander inclus / exclus |
| Question ouverte bloquante non tranchée | SIGNALEMENT + marquer la subfeature `blocked` dans la mini-spec |

---

## 12. Exemple d'utilisation

**Input :**
```
SF-01-02 — Lister les dossiers du workspace
Feature parente : FEAT-01 — Gestion des dossiers
Objectif : Retourner la liste paginée des dossiers actifs du workspace de l'utilisateur connecté
Stack : Backend
Tables : case_files, workspaces
Rôle minimum : MEMBER
```

**Output (extrait) :**
```markdown
## Objectif
Permettre à un membre du workspace de récupérer la liste paginée de ses dossiers actifs.

## Comportement nominal
GET /api/v1/case-files → retourne la liste des case_files filtrés par workspace_id de l'utilisateur,
triés par date de création décroissante, paginés (page, size).

## Cas d'erreur
| Situation | Comportement | Code |
|-----------|-------------|------|
| Token absent | Non autorisé | 401 |
| Workspace différent | Accès refusé | 403 |
| Paramètre page invalide | Erreur validation | 400 |

## Critères d'acceptation
- [ ] Quand l'utilisateur appelle GET /api/v1/case-files, il reçoit uniquement les dossiers de son workspace
- [ ] Quand page=0 et size=10, il reçoit au maximum 10 dossiers
- [ ] Quand un utilisateur tente d'accéder aux dossiers d'un autre workspace, il reçoit 403

## Plan de test minimal
Unitaires :
- [ ] CaseFileService.findAllByWorkspace — nominal + workspace invalide

Intégration :
- [ ] GET /api/v1/case-files → 200 avec payload valide
- [ ] GET /api/v1/case-files → 403 si workspace différent
- [ ] GET /api/v1/case-files?page=invalid → 400
```

---

## 13. Format de réponse attendu

```markdown
# Mini-spec — [SF-XX-YY] [Titre]

## Identifiant
[SF-XX-YY]

## Objectif
[Une phrase]

## Comportement nominal
[Description pas à pas]

## Cas d'erreur
[Tableau : situation / comportement / code HTTP]

## Critères d'acceptation
- [ ] [Critère 1 — nominal]
- [ ] [Critère 2 — cas d'erreur]
- [ ] [Critère 3 — isolation workspace]

## Périmètre hors-scope
- [Ce qui n'est pas fait]

## Éléments techniques
[Endpoints, tables, migration, composants]

## Plan de test minimal
[Unitaires / Intégration / Isolation workspace]

## Points ouverts
[Références à docs/OPEN_QUESTIONS.md si applicable]

---
Statut : draft
Prochaine étape : → Validation par delivery-orchestrator avant tout dev
```
