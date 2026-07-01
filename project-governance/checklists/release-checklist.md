# Release Checklist — Avant le merge sur main

À valider avant de merger une PR.
Tous les items doivent être verts.

---

## Validation technique

- [ ] La review est approuvée
- [ ] La CI est verte (build + tous les tests)
- [ ] Aucun conflit de merge avec `main`
- [ ] La branche est à jour avec `main` (rebase ou merge récent)

---

## Definition of Done

- [ ] Tous les critères de `project-governance/playbooks/definition-of-done.md` sont verts
- [ ] La mini-spec est respectée intégralement
- [ ] Tous les critères d'acceptation sont validés

---

## Base de données

- [ ] Si une migration Liquibase est présente : elle a été testée en local sur une base propre
- [ ] La migration ne casse pas les données existantes
- [ ] Les index et FK sont en place

---

## Sécurité

- [ ] Aucune donnée sensible dans le diff (clé API, token, secret)
- [ ] L'isolation workspace est garantie sur toutes les nouvelles routes

---

## Documentation mise à jour

- [ ] Si endpoint modifié ou créé : contrat API à jour
- [ ] Si table créée : `docs/ARCHITECTURE_CANONIQUE.md` mis à jour
- [ ] Si question ouverte tranchée : `docs/OPEN_QUESTIONS.md` mis à jour
- [ ] Si décision d'architecture prise : ADR créé dans `project-governance/templates/`

---

## Post-merge

- [ ] Vérifier que la CI post-merge est verte sur `main`
- [ ] Identifier si la subfeature suivante est débloquée
- [ ] Mettre à jour le statut de la feature parente si toutes ses subfeatures sont Done
- [ ] Communiquer le merge aux parties prenantes si la feature parente est complète
