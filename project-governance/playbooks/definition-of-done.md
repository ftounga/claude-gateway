# Definition of Done — claude-gateway

## Principe

Une subfeature est **Done** quand tous les critères ci-dessous sont verts.
Aucun critère n'est optionnel.
Un seul critère rouge = la subfeature n'est pas Done.

---

## Critères — Code

- [ ] Le code implémente exactement ce qui est décrit dans la mini-spec, ni plus ni moins
- [ ] Aucune logique métier dans les controllers ou entités JPA
- [ ] Toute requête sur des données filtre par `user_id`
- [ ] Les cas d'erreur sont gérés (validation, 403, 404, 500)
- [ ] Aucune stacktrace exposée dans les réponses API
- [ ] Les DTOs de requête et de réponse sont distincts
- [ ] Les traitements lourds/asynchrones sont exécutés hors du thread HTTP (N/A si non applicable)

---

## Critères — Tests backend

- [ ] Tests unitaires présents sur la logique métier du service
- [ ] Tests d'intégration présents sur les endpoints exposés
- [ ] Les cas d'erreur sont testés (données invalides, accès interdit, ressource absente)
- [ ] L'isolation tenant est testée (un utilisateur ne peut pas voir les données d'un autre)
- [ ] Tous les tests passent (aucun test ignoré ou commenté)
- [ ] Le plan de test de la mini-spec est couvert

## Critères — Tests frontend

> S'applique à toute subfeature avec composant Angular. Cocher N/A si purement backend.

- [ ] Un fichier `.spec.ts` existe pour chaque composant et service créé ou modifié
- [ ] Le composant se crée sans erreur (`should create` passant)
- [ ] Les interactions utilisateur clés sont testées (clic bouton, soumission formulaire)
- [ ] Les cas d'erreur API sont testés (service renvoie une erreur → comportement attendu)
- [ ] Les services Angular sont testés avec `HttpClientTestingModule` (requêtes émises, réponses mappées)
- [ ] Tous les tests Karma passent (`npm test -- --watch=false`)

---

## Critères — Base de données

- [ ] La migration Liquibase est présente si un changement de schéma est requis
- [ ] La migration suit le format de nommage `{NNN}-{description}.xml`
- [ ] Les FK et index sont en place sur les colonnes critiques
- [ ] La migration est réversible ou une stratégie de rollback est documentée

---

## Critères — Review

- [ ] La PR respecte le template `project-governance/templates/pr-template.md`
- [ ] La review a été faite selon `project-governance/playbooks/review-rules.md`
- [ ] Aucun critère bloquant de la review-checklist n'est ouvert
- [ ] La review est approuvée

---

## Critères — CI / Build

- [ ] Le build passe sans erreur
- [ ] Tous les tests de la CI sont verts
- [ ] Aucun warning de compilation non traité (sauf exception documentée)

---

## Critères — Documentation

- [ ] Si un endpoint est ajouté ou modifié : le contrat API est mis à jour
- [ ] Si une décision d'architecture est prise : un ADR est créé (`project-governance/templates/adr-template.md`)
- [ ] Si une question ouverte est tranchée : `docs/OPEN_QUESTIONS.md` est mis à jour
- [ ] Si une nouvelle table est ajoutée : `docs/ARCHITECTURE_CANONIQUE.md` est mis à jour

---

## Critères — Frontend

> S'applique à toute subfeature ou feature comportant un écran utilisateur visible.
> Si la subfeature est purement backend (pas d'écran), cocher N/A explicitement.

- [ ] Un composant Angular est créé pour chaque écran décrit dans la mini-spec
- [ ] Le composant respecte le layout défini dans `docs/DESIGN_SYSTEM.md` (header, sidenav, padding)
- [ ] Les couleurs, polices et espacements respectent le Design System (palette, typographie, multiples de 8px)
- [ ] Les appels API passent par un service Angular dédié (jamais d'HttpClient dans un composant)
- [ ] Les erreurs API sont affichées via `MatSnackBar` (jamais `alert()`)
- [ ] Les formulaires utilisent `mat-form-field appearance="outline"` avec `mat-error`
- [ ] Les listes utilisent `mat-table` avec `mat-paginator`
- [ ] L'écran est protégé par un `AuthGuard` si l'accès requiert une authentification
- [ ] Le routage est déclaré dans `app.routes.ts`

---

## Critères — Sécurité

- [ ] Aucune donnée sensible (token, mot de passe, clé API) dans le code ou les logs
- [ ] Les endpoints sont protégés par les bons rôles (ex: `OWNER`, `ADMIN`, `MEMBER` — selon le projet)
- [ ] L'isolation tenant est vérifiée pour tout accès aux ressources (`user_id`)

---

## Feature parente — Done

Une feature parente est Done quand :
- Toutes ses subfeatures sont Done
- Le comportement de bout en bout est validé manuellement ou via un test E2E
- Le résultat est conforme à l'objectif fonctionnel décrit dans le `feature-template.md`
