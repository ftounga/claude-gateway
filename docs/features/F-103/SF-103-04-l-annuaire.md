# Mini-spec — F-103 / SF-103-04 — L'annuaire

## Identifiant

`F-103 / SF-103-04`

## Feature parente

`F-103` — Le Radar, la page sujet (cadrage : `docs/features/F-99/CADRAGE-le-radar.md` §8 « L'annuaire »,
§12 bis ; `DESIGN_SYSTEM.md` §16 (Vigie) et §17)

## Statut

`in-progress`

## Date de création

2026-09-13

## Branche Git

`feat/SF-103-04-annuaire`

---

## Objectif

Faire de l'onglet **Personnes** d'un client de la Vigie **l'annuaire du Radar** : les personnes
rencontrées, leurs sujets et leur rôle sur chacun, la dernière interaction — cherchable, et chaque
sujet menant à sa page.

---

## Comportement attendu

### Cas nominal

1. L'onglet *Personnes* lit l'annuaire du client (`GET /api/radar/hosts/{hostId}/people`, F-99,
   inchangé ; lecture une fois par client et par page, comme aujourd'hui) et le rend par le composant
   **`app-radar-directory`**.
2. **Ordre** : dernière interaction la plus récente d'abord ; les personnes sans interaction connue
   ensuite ; à égalité, par nom (ordre alphabétique français, sans tenir compte de la casse ni des
   accents).
3. **Chaque personne** : nom ; fonction si la source la fournit ; « *n* sujet(s) » ; « dernier échange
   le 12 septembre » (l'année écrite si elle diffère de l'année courante) ; puis **ses sujets** : nom du
   sujet en **lien vers sa page** (`/vigie/:hostRef/sujets/:id`), **son rôle écrit** (*décide*,
   *pilote*, *expert*, *informé*) et la **pastille d'état** du sujet (§17). Sujets en cours d'abord,
   sujets clos ensuite ; puis par nom.
4. **Chercher** : un champ « Chercher une personne ou un sujet » filtre sur le nom, la fonction et le
   nom des sujets (insensible à la casse et aux accents). Aucun résultat : « Personne ne correspond. »
5. **Longue liste** : les 50 premières personnes, puis « Afficher les *k* autres ».
6. **Depuis la page sujet** : le titre « Qui est dans ce sujet » porte un lien « L'annuaire » vers
   `/vigie/:hostRef?onglet=personnes`.
7. **Téléphone** : chaque personne sur une colonne, les sujets passent à la ligne.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Annuaire illisible | « L'annuaire n'a pas pu être lu. » (inchangé) | 4xx / 5xx |
| Annuaire vide | « Personne encore : l'annuaire se remplit à chaque synchro du Radar. » (inchangé) | 200 |
| Filtre sans résultat | « Personne ne correspond. » | — |
| Personne sans sujet | « 0 sujet », aucune liste | — |
| Poste d'autrui / hors Vigie | lecture refusée par la gateway (404 / 409) → annuaire illisible | 404 / 409 |

---

## Critères d'acceptation

- [ ] L'onglet *Personnes* rend `app-radar-directory` avec l'annuaire du client ouvert.
- [ ] Ordre : interaction la plus récente d'abord, sans interaction à la fin, puis par nom.
- [ ] Chaque sujet d'une personne est un lien vers `/vigie/<client>/sujets/<sujet>`, avec le rôle écrit
      et la pastille d'état §17 ; sujets clos après les sujets en cours.
- [ ] Le filtre retient par nom, fonction ou nom de sujet, sans casse ni accents ; sinon « Personne ne
      correspond. »
- [ ] Au-delà de 50 personnes, « Afficher les *k* autres » révèle la suite.
- [ ] La page sujet mène à l'annuaire du client.
- [ ] Annuaire vide et illisible : messages inchangés.
- [ ] Isolation : aucune lecture nouvelle ; la lecture existante reste filtrée `user_id` + `host_id`
      (tests F-99 rejoués).

---

## Périmètre

### Hors scope (explicite)

- Fiche détaillée d'une personne, modification d'une personne, fusion de doublons.
- Les sujets où une personne n'a **aucun rôle** (auteur d'une preuve seulement) : le registre ne rend
  que les rôles ; l'annuaire ne devine rien.
- Contacter une personne.
- Nouvel endpoint ou nouvelle donnée côté gateway.

---

## Valeurs initiales

Aucune.

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format / Valeurs autorisées | Unicité | Normalisation |
|-------|-------------|-------------|----------------------------|---------|---------------|
| filtre | Non | 100 | texte libre | — | `trim()`, minuscules, sans accents |
| page d'affichage | — | 50 personnes | — | — | — |

---

## Technique

### Endpoint(s)

Aucun nouveau. Lu : `GET /api/radar/hosts/{hostId}/people` (F-99).

### Tables impactées

Aucune écriture ; lecture F-99 existante (`radar_people`, `radar_subject_roles`, `radar_subjects`).

### Migration Liquibase

- [x] Non applicable

### Composants Angular

- `vigie/radar-directory/radar-directory.ts` (nouveau) — fonctions pures : ordre, filtre, sujets
  d'une personne, libellés.
- `vigie/radar-directory/radar-directory.component` (nouveau) — l'annuaire.
- `VigieComponent` — l'onglet *Personnes* délègue la liste au composant ; `lastInteraction` et
  `subjectsLabel` y déménagent ; styles `vigie__people` / `vigie__person*` retirés.
- `RadarSubjectPageComponent` — lien « L'annuaire ».

### Préoccupations transversales

- **Navigation : oui.** Composants vérifiés : `VigieComponent` (`?onglet=personnes` inchangé, lecture
  de l'annuaire à l'ouverture de l'onglet et au rafraîchissement inchangée), route
  `vigie/:hostRef/sujets/:subjectId` (SF-103-01, cible des liens), `vigie-fleet.ts`
  (`effectiveVigieTab` accepte `personnes`).
- Contexte tenant, Auth, Plans / limites : non.

---

## Plan de test

### Tests unitaires

- [ ] `radar-directory.spec.ts` — ordre ; filtre (nom, fonction, sujet, accents, casse, vide) ;
      sujets clos après ; libellés « 1 sujet » / « 2 sujets » ; date d'interaction (année écrite si
      différente, invalide → rien).
- [ ] `radar-directory.component.spec.ts` — liens de sujet, rôle, pastille ; filtre et « Personne ne
      correspond » ; « Afficher les *k* autres ».
- [ ] `vigie.component.spec.ts` — l'onglet rend l'annuaire ; vide et illisible inchangés.
- [ ] `radar-subject-page.component.spec.ts` — lien vers l'annuaire.

### Tests d'intégration

- [ ] Backend : `RadarReadApiIntegrationTest` et `RadarIsolationIntegrationTest` rejoués (contrat de
      l'annuaire inchangé).

### Isolation workspace

- [x] Applicable — portée par la lecture F-99 existante, tests rejoués ; aucun identifiant de compte
      côté écran.

---

## Dépendances

### Subfeatures bloquantes

- SF-103-01 (page sujet, cible des liens) — `done`.

### Questions ouvertes impactées

- Aucune.

---

## Notes et décisions

- **Composant dédié** plutôt qu'un gabarit dans `VigieComponent` : l'écran de la Vigie reste un
  maître–détail, et l'annuaire se teste seul.
- **« Afficher les *k* autres »** plutôt qu'un `mat-paginator` : ce n'est pas une table, et une liste
  cherchable qui s'allonge garde le filtre utile sur toutes les pages.
