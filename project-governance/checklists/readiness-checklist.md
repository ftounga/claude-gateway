# Readiness Checklist — Avant de démarrer le dev

À valider avant d'écrire la première ligne de code.
Si un item est rouge → résoudre avant de commencer.

---

## Mini-spec

- [ ] Le fichier `subfeature-template.md` est rempli pour cette subfeature
- [ ] L'objectif fonctionnel est formulé en une phrase claire
- [ ] Le comportement nominal est décrit précisément
- [ ] Au moins 2 cas d'erreur sont identifiés
- [ ] Les critères d'acceptation sont listés, vérifiables et non ambigus
- [ ] Le plan de test minimal est défini (unitaires + intégration + isolation workspace)
- [ ] Le périmètre hors-scope est explicitement indiqué

## Contraintes de validation

- [ ] La section "Contraintes de validation" du `subfeature-template.md` est remplie pour tous les champs soumis à une règle
- [ ] Toute contrainte structurante non encore définie (longueur max, taille fichier, valeurs enum, quota) est soit **tranchée**, soit **enregistrée dans `docs/OPEN_QUESTIONS.md`** avec son impact documenté
- [ ] Aucun critère d'acceptation ne reste indéterminé à cause d'une contrainte manquante sans que ce manque soit explicitement tracé

> Si une contrainte structurante est absente et non tracée → la subfeature n'est pas `ready`. La marquer `blocked` jusqu'à résolution.

---

## Architecture & dépendances

- [ ] Les tables impactées sont identifiées et cohérentes avec `docs/ARCHITECTURE_CANONIQUE.md`
- [ ] Les endpoints à créer ou modifier sont listés
- [ ] Les dépendances sur d'autres subfeatures sont identifiées
- [ ] Les subfeatures bloquantes sont Done ou démarrables en parallèle sans conflit
- [ ] Si une question ouverte de `docs/OPEN_QUESTIONS.md` est impactée : elle est tranchée ou contournée explicitement

## Migration base de données

- [ ] Si un changement de schéma est nécessaire : la migration Liquibase est planifiée
- [ ] Le nommage de la migration est conforme : `{NNN}-{description}.xml`
- [ ] La migration est réversible ou un plan de rollback est documenté

## Branche Git

- [ ] La branche de travail est créée depuis `main` à jour
- [ ] Le nommage respecte la convention : `feat/SF-XX-nom-court`
- [ ] Aucune autre subfeature n'est mélangée dans cette branche

## Compréhension

- [ ] Les coding rules sont connues : `project-governance/playbooks/coding-rules.md`
- [ ] La definition of done est connue : `project-governance/playbooks/definition-of-done.md`
- [ ] Le développeur peut expliquer en une phrase ce que la subfeature fait et ne fait pas
