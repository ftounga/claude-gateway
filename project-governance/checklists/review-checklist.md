# Review Checklist — Avant d'approuver une PR

À valider par le reviewer avant toute approbation.
Les items marqués **BLOQUANT** empêchent le merge.

---

## Prérequis

- [ ] La mini-spec de la subfeature a été lue avant de lire le code
- [ ] Le template PR est rempli : `project-governance/templates/pr-template.md`
- [ ] La branche est à jour avec `main`

---

## Sécurité — BLOQUANT

- [ ] Chaque accès aux données vérifie le `workspace_id` (**BLOQUANT** si absent)
- [ ] Aucune donnée sensible dans le code ou les logs (**BLOQUANT**)
- [ ] Les endpoints sont protégés par les bons rôles (**BLOQUANT** si absent)
- [ ] Aucune stacktrace dans les réponses API (**BLOQUANT**)

---

## Cohérence mini-spec — BLOQUANT

- [ ] Le code correspond à ce qui est décrit dans la mini-spec (**BLOQUANT** si dérive)
- [ ] Tous les critères d'acceptation sont couverts (**BLOQUANT** si manquant)
- [ ] Rien n'est implémenté hors périmètre sans justification (**BLOQUANT**)

---

## Tests — BLOQUANT

- [ ] Tests unitaires présents sur la logique métier (**BLOQUANT** si absents)
- [ ] Tests d'intégration présents sur les endpoints (**BLOQUANT** si absents)
- [ ] Isolation workspace testée si la subfeature accède à des données (**BLOQUANT** si absente)
- [ ] Tous les tests passent (**BLOQUANT** si échec)
- [ ] Les cas d'erreur du plan de test sont couverts (**BLOQUANT** si manquants)

---

## Architecture — BLOQUANT

- [ ] Aucune logique métier dans les controllers ou entités (**BLOQUANT**)
- [ ] Migration Liquibase présente si changement de schéma (**BLOQUANT** si absente)
- [ ] Aucun traitement IA synchrone (**BLOQUANT**)
- [ ] Le build et la CI sont verts (**BLOQUANT**)
- [ ] Si la PR ajoute un outil à l'agent (ou un nouveau `type` d'étape) : sa scène Jarvis est déclarée dans `frontend/src/app/jarvis/scenes/jarvis-scene-registry.json` — scène dédiée, ou `"scene": "GENERIQUE"` acceptée explicitement avec un `libelle` lisible (F-234, D17) (**BLOQUANT** si absente)
- [ ] Si la PR ajoute une scène Jarvis (nouvel `id` au registre ou posé par le lecteur) : son rendu 3D est déclaré — fonction dans `SCENES_3D` (`frontend/src/app/jarvis/stage3d/scenes3d/index.ts`) ou repli explicite sur S-GEN 3D (`SGEN_3D_IDS`) ; la garde `jarvis-scenes3d.spec.ts` échoue sinon (F-236, SF-236-08) (**BLOQUANT** si absente)
- [ ] Si la PR touche le rendu des schémas d'architecture (`diagram-renderer/cadre*.js`, `gardes.js`, `cloud.py --prepare`) : les gardes G1–G6 passent sur les schémas types (`cd diagram-renderer && npm test`) et les tests Python `test_cloud*.py` restent verts (F-238, cadrage §4.6) (**BLOQUANT** si rouge)

---

## Design System — BLOQUANT si composant frontend présent

- [ ] Couleurs utilisées conformes à `docs/DESIGN_SYSTEM.md` (**BLOQUANT** si hors palette)
- [ ] Polices conformes : Inter, Merriweather, JetBrains Mono uniquement (**BLOQUANT**)
- [ ] Composants Angular Material utilisés selon les règles du design system (**BLOQUANT**)
- [ ] Espacements multiples de 4px (**BLOQUANT** si non respecté)
- [ ] Pas de `window.alert()`, `window.confirm()` ou `window.prompt()` (**BLOQUANT**)
- [ ] Notifications via `MatSnackBar`, confirmations destructives via `MatDialog`
- [ ] Tables avec `mat-paginator` si liste de données
- [ ] Formulaires avec `mat-form-field` appearance `outline` et `mat-error` pour les erreurs

---

## Qualité — Non bloquant

- [ ] Le nommage respecte les coding rules
- [ ] Pas de duplication évitable
- [ ] La logique complexe est commentée si non évidente
- [ ] Les DTOs de requête et réponse sont distincts
- [ ] Pas de warnings de compilation non traités

---

## Documentation — Non bloquant

- [ ] Si un endpoint est modifié : le contrat est mis à jour
- [ ] Si une décision d'archi est prise : un ADR est créé
- [ ] Si une question ouverte est tranchée : `docs/OPEN_QUESTIONS.md` est mis à jour
- [ ] Si une table est ajoutée : `docs/ARCHITECTURE_CANONIQUE.md` est mis à jour

---

## Résultat

- Tous les BLOQUANTS sont verts → approbation possible
- Un BLOQUANT est rouge → demander les changements, ne pas approuver
- Items non bloquants → commenter sans bloquer, noter pour suivi
