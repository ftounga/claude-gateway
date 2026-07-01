# Review Rules — claude-gateway

## Principe

La review est une étape de validation, pas une formalité.
Un reviewer qui approuve est co-responsable du code mergé.
Utiliser la checklist : `project-governance/checklists/review-checklist.md`

---

## Critères bloquants

Ces critères empêchent le merge. Aucune exception.

### Sécurité
- [ ] **BLOQUANT** — Un endpoint accessible sans vérification du `workspace_id`
- [ ] **BLOQUANT** — Une donnée sensible (token, secret, clé API) présente dans le code ou un log
- [ ] **BLOQUANT** — Un endpoint non protégé par un rôle explicite
- [ ] **BLOQUANT** — Une stacktrace exposée dans une réponse API

### Cohérence avec la mini-spec
- [ ] **BLOQUANT** — Le code implémente quelque chose qui n'est pas dans la mini-spec
- [ ] **BLOQUANT** — Un critère d'acceptation de la mini-spec n'est pas couvert
- [ ] **BLOQUANT** — Le comportement implémenté contredit la mini-spec

### Tests
- [ ] **BLOQUANT** — Aucun test unitaire sur la logique métier du service
- [ ] **BLOQUANT** — Aucun test d'intégration sur les endpoints exposés
- [ ] **BLOQUANT** — L'isolation workspace n'est pas testée si la subfeature accède à des données
- [ ] **BLOQUANT** — Des tests existants sont en échec

### Architecture
- [ ] **BLOQUANT** — De la logique métier dans un controller ou une entité JPA
- [ ] **BLOQUANT** — Une migration Liquibase absente alors qu'un changement de schéma est fait
- [ ] **BLOQUANT** — Un traitement IA lancé de façon synchrone

### Build
- [ ] **BLOQUANT** — Le build échoue
- [ ] **BLOQUANT** — La CI est rouge

---

## Critères non bloquants (commentaire requis)

Ces critères génèrent un commentaire de review mais ne bloquent pas le merge si le reviewer juge l'impact acceptable.

- Nommage non conforme aux coding rules (si mineur et isolé)
- Duplication de code évitable mais sans impact fonctionnel
- Absence de commentaire sur une logique complexe
- Warning de compilation non traité

Dans ce cas, le reviewer doit :
1. Laisser un commentaire explicite sur le point
2. Créer une issue ou une note dans la PR pour le suivi
3. Approuver avec commentaire (pas de blocage)

---

## Processus de review

### Pour le développeur

1. Remplir le template PR : `project-governance/templates/pr-template.md`
2. Auto-review avant de demander une review (relire sa propre PR)
3. Vérifier la checklist : `project-governance/checklists/review-checklist.md`
4. Répondre à chaque commentaire (résoudre ou argumenter)

### Pour le reviewer

1. Lire la mini-spec associée avant de lire le code
2. Vérifier les critères bloquants en premier
3. Lire le code dans cet ordre :
   - Migration SQL (si présente)
   - Tests (valide la compréhension du comportement attendu)
   - Service (logique métier)
   - Controller (contrat API)
   - Frontend (si applicable)
4. Laisser des commentaires constructifs et précis
5. Approuver, demander des changements, ou bloquer selon les critères

---

## Règles de délai

- Une PR ne doit pas rester sans review plus de 1 jour ouvré
- Si le reviewer est bloqué sur un point, il le signale immédiatement (pas en silence)
- Une PR ouverte depuis plus de 3 jours sans activité doit être relancée ou fermée

---

## Ce qui n'est pas du ressort de la review

- Débattre de choix déjà actés dans la mini-spec validée
- Proposer des refactorings hors périmètre de la subfeature
- Commenter sur le style si les coding rules ne sont pas enfreintes

Ces points peuvent être mentionnés comme suggestions facultatives, clairement libellées comme telles.
