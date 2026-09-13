# Mini-spec — [F-99 / SF-99-06] Séparer un sujet et gérer ses alias

---

## Identifiant

`F-99 / SF-99-06`

## Feature parente

`F-99` — Le Radar : le registre de l'organisation
(cadrage validé : `CADRAGE-le-radar.md`, §4.2, §7 ; API livrée en SF-99-03)

## Statut

`done` — livrée le 2026-09-13 (PR #555)

## Date de création

2026-09-13

## Branche Git

`feat/SF-99-06-separer-alias`

---

## Objectif

Depuis la page sujet de la Vigie, l'utilisateur **sépare** un sujet (il choisit les preuves et les
engagements qui partent dans un nouveau sujet) et **ajoute ou retire un alias** ; chacun de ces gestes
est une correction souveraine, **annulable depuis la chronologie** de la page.

---

## Contexte

Constat de clôture F-104 (2026-09-13, `PRODUCT_SPEC.md` ligne F-99) : l'API de séparation et des alias
existe (SF-99-03) mais aucun écran ne l'appelle. Défaut révélé par l'écran : l'ajout et le retrait
d'un alias **ne sont pas journalisés** — ils ne peuvent donc pas être annulés, contrairement à la
séparation. Ils le deviennent ici (actions `ADD_ALIAS` / `REMOVE_ALIAS`, sans migration :
`radar_corrections.action` est un `varchar(24)` sans contrainte de valeurs).

---

## Comportement attendu

### Cas nominal

1. **Les autres noms** — sous le titre de la page sujet (`app-radar-subject-aliases`) :
   - « Aussi appelé » : les alias acceptés, chacun avec un bouton *Retirer* (icône `close`, libellé
     accessible « Retirer l'alias « X » ») ;
   - « N'est pas » : les consignes (alias refusés, nés d'une séparation), elles aussi retirables ;
   - *Ajouter un alias* : un champ `mat-form-field` outline (200 caractères) et un bouton *Ajouter* ;
     `POST …/subjects/{id}/aliases` ;
   - aucun geste sur un sujet fusionné (bandeau existant) ;
   - après chaque geste : snackbar « Alias ajouté. » / « Alias retiré. » avec **Annuler**, la page relit
     le sujet et son journal.
2. **Séparer** — bouton *Séparer…* dans l'en-tête (absent sur un sujet fusionné, désactivé avec une
   infobulle quand la chronologie compte moins de deux preuves) ouvre `SplitSubjectDialogComponent` :
   - le nom du nouveau sujet (requis, 200 caractères) ;
   - la chronologie en cases à cocher (icône de source, moment, citation) ;
   - les engagements du sujet en cases à cocher (facultatif) ;
   - *Séparer* reste désactivé tant que le nom est vide, qu'aucune preuve n'est cochée **ou que toutes
     le sont** (la phrase le dit : « Séparer toutes les preuves revient à renommer ») ;
   - `POST …/subjects/{id}/split` `{ name, evidenceIds, commitmentIds }` ; succès → snackbar
     « « N » est un nouveau sujet. » avec **Annuler** ; la page relit.
3. **Le journal dans la chronologie** — la section *Chronologie* gagne « Vos corrections sur ce sujet »
   (`app-radar-subject-journal`, `GET …/corrections?subjectId=`) : chaque geste écrit en mots (renommé,
   état dit, prochaine étape, échéance, clos, fusion, **séparation** avec le lien vers le nouveau sujet,
   **alias ajouté / retiré** avec le nom, gestes sur un engagement avec sa description), son moment, et
   *Annuler* tant qu'il n'est pas annulé (« annulé » sinon). `POST …/corrections/{id}/undo` ; la page
   relit ; un sujet né de la séparation qu'on annule n'existe plus (le lien disparaît).
4. **Backend — alias journalisés** (défaut révélé par l'écran) :
   - `addUserAlias` écrit une correction `ADD_ALIAS` (`after` : `aliasId`, `alias`) ;
   - `removeAlias` écrit une correction `REMOVE_ALIAS` (`before` : `aliasId`, `alias`, `origin`,
     `rejected`) ;
   - annuler `ADD_ALIAS` supprime l'alias ; annuler `REMOVE_ALIAS` le recrée (même nom, origine, refus) ;
   - réponses HTTP inchangées (`AliasView` à l'ajout, 204 au retrait).

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Alias vide, > 200, déjà connu du sujet | message de la gateway dans la snackbar, rien n'est écrit | 400 |
| Séparation sans preuve, avec toutes les preuves, nom vide | bouton désactivé ; si la gateway refuse quand même, son message dans le dialogue | 400 |
| Sujet fusionné entre-temps | message « fusionné », la page relit | 409 |
| Annuler `ADD_ALIAS` dont l'alias a déjà été retiré | `radar_correction_conflict` « retiré depuis » | 409 |
| Annuler `REMOVE_ALIAS` alors que le nom est de nouveau connu du sujet | `radar_correction_conflict` | 409 |
| Annuler une séparation dont le nouveau sujet a vécu | message de la gateway (SF-99-03) dans la snackbar | 409 |
| Journal illisible | « Vos corrections n'ont pas pu être lues. », la page reste | — |
| Sujet ou alias d'un autre poste / compte | `not_found` | 404 |

---

## Critères d'acceptation

- [ ] La page sujet rend les alias acceptés et les consignes ; *Ajouter* appelle `addAlias(hostId, subjectId, alias)` ; *Retirer* appelle `removeAlias` ; aucun geste sur un sujet fusionné.
- [ ] Après un geste d'alias, la snackbar propose **Annuler**, qui annule la correction correspondante du journal.
- [ ] Le dialogue de séparation n'autorise ni nom vide, ni zéro preuve, ni toutes les preuves ; il envoie exactement les preuves et engagements cochés.
- [ ] Après séparation, snackbar avec **Annuler** ; la page relit ; le journal montre la séparation et le lien vers le nouveau sujet.
- [ ] Le journal écrit chaque geste en mots, et *Annuler* appelle `undo(hostId, correctionId)` puis relit la page ; une erreur 409 est dite.
- [ ] Backend : ajout et retrait d'un alias journalisés (`ADD_ALIAS`, `REMOVE_ALIAS`) et annulables ; conflits 409 nommés ; réponses HTTP inchangées.
- [ ] Isolation : annuler une correction d'alias d'un autre poste → 404, rien ne bouge.
- [ ] DESIGN_SYSTEM §17 : aucune couleur nouvelle ; boutons `mat-stroked-button` / `mat-button` ; formulaire outline ; confirmation par `MatDialog`.

---

## Périmètre

### Hors scope (explicite)

- Fusionner depuis la page sujet (fusion déjà servie par *Donner la nouvelle*, F-104).
- Déplacer les rôles lors d'une séparation (SF-99-03 : ils restent sur la source).
- Renommer, état, prochaine étape depuis la page (gestes de l'onglet Radar, F-102).
- Lien sujet ↔ projet (SF-106-06, livrée en parallèle).
- Export, purge (SF-99-07).

---

## Valeurs initiales

| Champ | Valeur initiale | Règle |
|-------|----------------|-------|
| Cases du dialogue de séparation | décochées | rien ne part par défaut |
| Champ alias | vide | — |

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format / Valeurs autorisées | Unicité | Normalisation |
|-------|-------------|-------------|----------------------------|---------|---------------|
| alias | Oui | 200 | non vide | par sujet (clé minuscule, côté gateway) | trim |
| split.name | Oui | 200 | non vide | — | trim |
| split.evidenceIds | Oui | — | ≥ 1, pas toutes | — | — |
| split.commitmentIds | Non | — | engagements du sujet | — | — |
| action (journal) | — | 24 | + `ADD_ALIAS`, `REMOVE_ALIAS` | — | — |

---

## Technique

### Endpoint(s)

| Méthode | URL | Auth | Rôle minimum |
|---------|-----|------|-------------|
| POST | `/api/radar/hosts/{hostId}/subjects/{subjectId}/split` | JWT | droit Radar (inchangé) |
| POST | `/api/radar/hosts/{hostId}/subjects/{subjectId}/aliases` | JWT | inchangé ; journalise désormais |
| DELETE | `/api/radar/hosts/{hostId}/subjects/{subjectId}/aliases/{aliasId}` | JWT | inchangé ; journalise désormais |
| GET | `/api/radar/hosts/{hostId}/corrections?subjectId=` | JWT | inchangé |
| POST | `/api/radar/hosts/{hostId}/corrections/{id}/undo` | JWT | inchangé ; + `ADD_ALIAS` / `REMOVE_ALIAS` |

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| `radar_corrections` | INSERT / UPDATE | nouvelles valeurs d'`action` |
| `radar_subject_aliases` | INSERT / DELETE | recréation à l'annulation d'un retrait |

### Migration Liquibase

- [x] Non — `action` est un `varchar(24)` sans contrainte.

### Composants

| Composant | Rôle |
|-----------|------|
| backend `RadarCorrectionAction` | + `ADD_ALIAS`, `REMOVE_ALIAS` |
| backend `RadarStructureService` | journalise les alias ; `undoAlias` |
| backend `RadarCorrectionService.undo` | délègue les alias à la structure |
| `RadarSubjectService` | `split`, `addAlias`, `removeAlias`, `corrections`, `undo` |
| `radar-subject.models.ts` | `RadarCorrectionView`, `RadarSplitRequest` |
| `radar-subject-journal.ts` (pur) | les mots d'une correction |
| `RadarSubjectAliasesComponent` | autres noms et consignes, ajout / retrait |
| `RadarSubjectJournalComponent` | vos corrections, *Annuler* |
| `SplitSubjectDialogComponent` | choix des preuves et engagements |
| `RadarSubjectPageComponent` | branche les trois, relit après geste |

---

## Plan de test

### Tests unitaires

- [ ] `radar-subject-journal.spec.ts` — libellés de chaque action, séparation avec sujet créé, engagement nommé, action inconnue.
- [ ] `split-subject-dialog.component.spec.ts` — bouton désactivé (nom vide, zéro, toutes) ; corps envoyé = cochés ; erreur 400 dite.
- [ ] `radar-subject-aliases.component.spec.ts` — rendu accepté / consignes ; ajout, retrait émis ; rien sur un sujet fusionné.
- [ ] `radar-subject-page.component.spec.ts` (étendu) — journal rendu (*Annuler*, « annulé », erreur), séparation, alias avec snackbar *Annuler*, relecture.
- [ ] `radar-subject.service.spec.ts` (étendu) — URLs et corps.
- [ ] backend `RadarStructureServiceTest` (étendu) — alias journalisés, annulation de l'ajout et du retrait, conflits.

### Tests d'intégration

- [ ] `RadarStructureApiIntegrationTest` (étendu) — ajout puis journal `ADD_ALIAS`, annulation par la route du journal ; retrait puis `REMOVE_ALIAS` et recréation ; 409 ; contexte Spring.

### Isolation

- [x] Applicable — Bob / poste B d'Alice : annulation d'une correction d'alias du poste A → 404, alias intact.

---

## Dépendances

### Subfeatures bloquantes

- SF-99-03 (API) — `done` ; F-103 (page sujet) — `done`.

### Questions ouvertes impactées

- Aucune.

---

## Préoccupations transversales

- **Contexte tenant : oui.** Composants : `RadarStructureService` (toute écriture et annulation à
  `RadarScope`, alias relu dans le périmètre), `RadarCorrectionService.undo` (correction relue par
  `user_id` + `host_id`, inchangé), `RadarSubjectService` (aucun identifiant de compte envoyé).
- **Plans / limites : non** (routes et droits inchangés).
- **Auth / Principal : non.** **Navigation / routing : non** (aucune route ; lien vers une page sujet existante).

---

## Notes et décisions

- **Alias journalisés** plutôt qu'une annulation « côté écran » (supprimer / recréer) : l'annulation
  doit passer par le journal comme tous les gestes souverains, et une consigne recréée doit garder son
  origine et son refus. Réversible, aucune migration.
- **L'annulation d'un geste d'alias dans la snackbar** retrouve sa correction dans le journal relu :
  les réponses HTTP des routes d'alias restent inchangées.
- **Composants enfants** plutôt qu'un gabarit de page grossi : la page sujet est modifiée en parallèle
  (SF-106-06) ; trois balises suffisent à la brancher.
- Limite connue : un alias créé par une fusion ou séparation, retiré puis recréé par annulation, change
  d'identifiant ; annuler ensuite cette fusion ou séparation ne le supprime pas (il reste un alias
  exact, retirable).
