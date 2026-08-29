# Mini-spec — [F-31 / SF-31-06] Les formulaires de l'écran Réglages ne soumettaient pas

---

## Identifiant

`F-31 / SF-31-06`

## Feature parente

`F-31` — Atelier sur dépôt Git.
Correctif d'un défaut de l'écran **Réglages**, introduit avec F-03 (BYOK) et révélé par F-31 lors du
premier test réel d'enregistrement d'un jeton GitHub. **Aucune feature nouvelle** : F-31 et F-03 sont
déjà référencées dans `docs/PRODUCT_SPEC.md`.

## Statut

`ready`

## Date de création

2026-08-29

## Branche Git

`fix/SF-31-06-soumission-formulaires-reglages`

---

## Objectif

> En une phrase : que fait cette subfeature ?

Rendre les deux formulaires de l'écran Réglages réellement soumettables — l'enregistrement du **jeton
GitHub** (F-31) et celui de la **clé API personnelle BYOK** (F-03) rechargent aujourd'hui la page sans
émettre aucune requête.

---

## Contexte

### Constat de production (2026-08-29, mesuré)

Trois tentatives de création d'un projet Git sur `portal.ng-itconsulting.com` échouent en
`400 git_token_missing` (corps de 130 octets, identifié par comparaison avec les autres messages
d'erreur possibles : `invalid_git_repository` en ferait 133 ou 129, `invalid_git_token` 101).

Cause : **aucun jeton n'est enregistré**. `user_git_credentials` contient **0 ligne**, et les journaux
de l'ingress ne montrent **aucun `POST /api/user/git-token`** sur 48 heures — uniquement des `GET`.
La table `user_api_keys` est également vide.

Signature du défaut dans les journaux, à la seconde du clic sur « Enregistrer le jeton » :

```
14:37:44  GET /api/me, /api/user/api-key, /api/user/git-token   ← écran Réglages chargé
14:37:52  GET /settings?              200 30748                 ← soumission NATIVE du formulaire
14:37:52  GET /api/me, /api/user/api-key, /api/user/git-token   ← page rechargée (ngOnInit)
```

Le `GET /settings?` — querystring **vide**, car aucun `<input>` de l'écran ne porte d'attribut `name`
— est une navigation déclenchée par le navigateur, pas par Angular.

### Cause racine

`frontend/src/app/settings/settings.component.ts` déclare `imports: [ RouterLink,
ReactiveFormsModule, DatePipe, … ]` — **`FormsModule` est absent**.

Or la directive `NgForm`, celle qui intercepte l'événement `submit`, appelle `preventDefault()` et
émet `(ngSubmit)`, a pour sélecteur `form:not([ngNoForm]):not([formGroup])` et est déclarée dans
**`FormsModule`**, non dans `ReactiveFormsModule`. Les deux `<form>` de l'écran (lignes 94 et 163 du
template) n'ont pas de `[formGroup]` : **aucune directive ne s'y applique**. `(ngSubmit)` est alors
compilé comme un binding vers un événement DOM du même nom, qui n'est jamais émis — donc
`saveApiKey()` et `saveGitToken()` ne sont **jamais appelés**, et le `<button type="submit">`
déclenche la soumission native.

Le défaut ne pouvait pas apparaître plus tôt : `[formControl]` (fourni par `ReactiveFormsModule`)
fonctionne, le champ se saisit normalement, et le build passe — rien ne signale l'anomalie avant le
clic.

### Pourquoi les tests ne l'ont pas vu

`settings.component.spec.ts` appelle `component.saveGitToken()` et `component.saveApiKey()`
**directement en TypeScript** (lignes 255, 281, 294). Le template n'est jamais exercé : le chemin
DOM → directive → handler n'est couvert par aucun test.

### Périmètre du défaut — audit des 10 écrans utilisant `(ngSubmit)`

| Écran | `[formGroup]` | `FormsModule` | Verdict |
|---|---|---|---|
| `chat`, `templates`, `login`, `register`, `forgot-password`, `reset-password`, `profile` | oui | — | sain (`FormGroupDirective` s'applique) |
| `atelier`, `atelier/terminal` | non | **importé** | sain (`NgForm` s'applique) |
| **`settings`** | **non** | **absent** | **cassé** |

`settings` est le seul écran touché, et il porte **les deux** formulaires.

---

## Comportement attendu

### Cas nominal

1. L'utilisateur colle un PAT GitHub dans « Jeton GitHub » et clique « Enregistrer le jeton ».
2. `(ngSubmit)` est émis, la soumission native est bloquée : **la page ne se recharge pas**.
3. `POST /api/user/git-token` part avec le jeton ; à la réponse, le champ est vidé, la carte affiche
   le compte GitHub et le jeton masqué, et le bandeau « Jeton GitHub enregistré. » s'affiche.
4. Le même parcours vaut pour la clé BYOK (`POST /api/user/api-key`) — bouton « Enregistrer la clé ».
5. La touche **Entrée** dans le champ produit exactement le même effet que le clic.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Champ vide au moment du clic | Aucune requête, aucun rechargement ; `mat-error` « Jeton requis. » sous le champ (comportement déjà écrit, jusqu'ici inatteignable) |
| Jeton refusé par GitHub (`400`) | Bandeau « Jeton refusé par GitHub (invalide, révoqué ou expiré). », champ conservé, page non rechargée |
| GitHub indisponible (`503`) | Bandeau d'indisponibilité, jeton précédent inchangé |
| Double clic rapide | La garde `savingGitToken()` / `savingKey()` empêche la seconde soumission (déjà écrite) |

---

## Critères d'acceptation

- [ ] Un clic sur « Enregistrer le jeton » émet un `POST /api/user/git-token` et **ne provoque aucune
      navigation**
- [ ] Un clic sur « Enregistrer la clé » émet un `POST /api/user/api-key` et ne provoque aucune
      navigation
- [ ] Le champ vide ne déclenche ni requête ni rechargement, et affiche l'erreur de validation
- [ ] Un test exerce le **DOM** (`querySelector('form')` + `dispatchEvent(new Event('submit'))`), pas
      seulement la méthode du composant — sans quoi le défaut resterait invisible
- [ ] `npm run build` vert, suite frontend verte
- [ ] Aucun autre écran modifié

---

## Périmètre

### Hors scope (explicite)

- Toute modification du **backend** : `GitTokenController` et `ApiKeyController` sont corrects, ils
  n'ont jamais été appelés.
- Toute modification des autres écrans (audit ci-dessus : aucun n'est touché).
- La refonte des formulaires en `FormGroup` — corrigerait aussi le défaut, mais réécrit deux
  formulaires vivants là où l'import manquant est la cause réelle et sa correction tient en un mot.
- L'ajout d'un garde-fou de lint interdisant `(ngSubmit)` sans directive de formulaire : utile,
  mais c'est un chantier d'outillage distinct.

---

## Valeurs initiales

Sans objet — aucune donnée créée, aucune valeur par défaut introduite.

---

## Contraintes de validation

Inchangées. Les validateurs existants (`Validators.required` sur `gitTokenControl` et
`apiKeyControl`) deviennent seulement **atteignables** : jusqu'ici la soumission native court-circuitait
`saveGitToken()`, donc le test `if (this.gitTokenControl.invalid)` n'était jamais évalué.

Côté backend, les contraintes restent celles de SF-31-01 : `@NotBlank`, `@Size(max = 255)` sur le
jeton, vérification auprès de GitHub **avant** écriture.

---

## Technique

### Endpoint(s)

Aucun endpoint créé ni modifié. Deux endpoints existants redeviennent atteignables :
`POST /api/user/git-token` (SF-31-01) et `POST /api/user/api-key` (F-03).

### Tables impactées

Aucune.

### Migration Liquibase

Aucune.

### Composants Angular (si applicable)

- `frontend/src/app/settings/settings.component.ts` — ajout de `FormsModule` à `imports`, afin que
  `NgForm` s'applique aux deux `<form>` sans `[formGroup]`. Les `[formControl]` isolés ne
  s'enregistrent pas auprès de `NgForm` (`FormControlDirective` n'est pas un `NgModel`) : aucun effet
  de bord sur la validation ni sur l'état des contrôles.
- `frontend/src/app/settings/settings.component.spec.ts` — tests de non-régression par le DOM.

---

## Plan de test

### Tests unitaires

- Soumission du `<form>` du jeton GitHub **via le DOM** → `GitTokenService.saveToken` appelé avec la
  valeur du champ.
- Soumission du `<form>` de la clé BYOK via le DOM → `ApiKeyService.saveKey` appelé.
- Soumission avec champ vide → aucun appel de service, contrôle marqué `touched`.
- Le bouton « Enregistrer le jeton » est bien de type `submit` et rattaché au formulaire attendu.

### Tests d'intégration

Sans objet côté backend (aucun changement serveur). La vérification d'intégration réelle est le test
manuel de bout en bout après déploiement : enregistrement du jeton, puis création du projet Git.

### Isolation workspace

Sans objet — aucun accès aux données n'est ajouté ni modifié. L'isolation `user_id` reste entière
côté backend : `GitTokenController` résout l'utilisateur depuis le `SecurityContext` et n'accepte
aucun identifiant du client (SF-31-01, inchangé).

---

## Dépendances

### Subfeatures bloquantes

Aucune. SF-31-01 (jeton) et F-03 (BYOK) sont livrées ; c'est leur écran commun qui est réparé.

### Questions ouvertes impactées

Aucune.

---

## Notes et décisions

**Décision — `FormsModule` plutôt qu'un `(submit)` avec `preventDefault()` manuel.** Ajouter
`FormsModule` rétablit le comportement standard d'Angular pour tout formulaire de l'écran, présent et
futur ; intercepter `(submit)` à la main dans le template réparerait les deux formulaires du jour en
laissant le piège intact pour le troisième.

**Décision — un test qui passe par le DOM.** Le défaut a survécu à 389 tests frontend parce que tous
appelaient les méthodes du composant directement. La non-régression n'a de valeur ici que si elle
emprunte le chemin qui était cassé.

**Constat conservé — impact sur F-03.** La clé BYOK n'était pas davantage enregistrable
(`user_api_keys` vide en production). Le correctif la débloque par le même geste ; c'est une
conséquence constatée, pas un élargissement de périmètre.
