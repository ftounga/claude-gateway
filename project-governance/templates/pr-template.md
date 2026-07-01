# Pull Request — [FEAT-XX / SF-YY] Titre

> Supprimer les sections non applicables. Ne pas laisser de section vide.

---

## Identifiant

`FEAT-XX / SF-YY`

## Type

- [ ] feat — nouvelle fonctionnalité
- [ ] fix — correction de bug
- [ ] refactor — refactoring sans changement fonctionnel
- [ ] test — ajout ou correction de tests
- [ ] docs — documentation uniquement
- [ ] chore — tâche technique (migration, config, dépendance)

---

## Résumé

> Une phrase décrivant ce que cette PR fait.

[À compléter]

---

## Lien vers la mini-spec

`docs/features/FEAT-XX/SF-XX-YY-nom.md`

---

## Changements effectués

> Liste des modifications significatives. Par composant si applicable.

### Backend
- [Description du changement 1]
- [Description du changement 2]

### Frontend
- [Description du changement]

### Base de données
- [Migration Liquibase : {NNN}-{description}.xml]
- [Nouvelle table / colonne / index]

---

## Critères d'acceptation vérifiés

> Copier les critères depuis la mini-spec et les cocher.

- [ ] [Critère 1]
- [ ] [Critère 2]
- [ ] [Critère 3 — isolation workspace]
- [ ] [...]

---

## Tests ajoutés

### Unitaires

- [NomDeLaClasseTest] — [ce qui est testé]
- [...]

### Intégration

- [NomDeLaClasseTest] — [endpoints testés]
- [...]

### Cas d'erreur couverts

- [ ] 400 — [situation]
- [ ] 403 — isolation workspace
- [ ] 404 — [situation]

---

## Décisions techniques prises en cours de dev

> Si une décision non prévue dans la mini-spec a été prise, la documenter ici.
> Si la décision est structurante → créer un ADR.

[Aucune / ou description]

---

## Checklist avant review

- [ ] Auto-review effectuée (j'ai relu ma propre PR)
- [ ] La mini-spec est respectée
- [ ] Les tests passent en local
- [ ] Aucun secret ou donnée sensible dans le diff
- [ ] La branche est à jour avec `main`
- [ ] La review checklist a été consultée : `project-governance/checklists/review-checklist.md`

---

## Impact sur des sujets ouverts

> Cette PR tranche-t-elle une question de `docs/OPEN_QUESTIONS.md` ?

- [ ] Oui — question tranchée : [description] → `docs/OPEN_QUESTIONS.md` mis à jour
- [ ] Non
