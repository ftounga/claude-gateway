# Mini-spec — [F-99 / SF-99-03] Fusionner, séparer, les alias

---

## Identifiant

`F-99 / SF-99-03`

## Feature parente

`F-99` — Le Radar : le registre de l'organisation
(cadrage validé : `CADRAGE-le-radar.md`, §4.2, §7, §12 bis)

## Statut

`done` — livrée le 2026-09-13 (PR #496)

## Date de création

2026-09-13

## Branche Git

`feat/SF-99-03-radar-fusion-separation`

---

## Objectif

L'utilisateur **fusionne** deux sujets qui sont le même, ou **sépare** d'un sujet ce qui n'en est
pas, avec leurs preuves et leurs engagements ; ces gestes **deviennent des alias et des consignes de
rattachement** que l'analyse (F-101) lira pour ne pas refaire l'erreur, et restent annulables.

---

## Comportement attendu

### Cas nominal

1. **Fusionner** — `POST /api/radar/hosts/{hostId}/subjects/{subjectId}/merge`, corps
   `{ "intoSubjectId": ... }` : le sujet source S est absorbé par la cible T.
   - la **chronologie** de S passe à T (liens `CHRONOLOGY`) ;
   - les **engagements** de S passent à T, avec leurs preuves ;
   - les **phrases du résumé** de S s'ajoutent à la fin du résumé de T, avec leurs renvois ;
   - les **rôles** de S passent à T, sauf pour une personne qui a déjà un rôle sur T (celui de T reste) ;
   - les **alias** de S passent à T, et **le nom de S devient un alias de T** (origine `MERGE`) ;
   - l'état, la prochaine étape et l'échéance de S **ne s'imposent pas** à T (leurs preuves restent
     sur S) ;
   - S est conservé comme **trace** (`merged_into_id = T`) : il sort des listes, sa page indique
     `mergedIntoId`, et **toute écriture de synchro sur S est redirigée vers T** ;
   - une ligne `MERGE` est journalisée (ce qui a bougé) ; dernière activité de T recalculée.
2. **Séparer** — `POST /api/radar/hosts/{hostId}/subjects/{subjectId}/split`, corps
   `{ "name": ..., "evidenceIds": [...], "commitmentIds": [...] }` (« ce n'est pas le même sujet ») :
   - un nouveau sujet N est créé (`NEW`, nom souverain) ;
   - les preuves désignées quittent la chronologie de S pour celle de N ; les engagements désignés
     passent à N avec leurs preuves ; une phrase du résumé de S dont **toutes** les preuves partent
     passe à N ;
   - **consignes de rattachement** : sur S, un alias **refusé** portant le nom de N (« N n'est pas
     S ») ; sur N, un alias refusé portant le nom de S (origine `SPLIT`) ;
   - une ligne `SPLIT` est journalisée ; dernières activités recalculées.
3. **Annuler** une fusion ou une séparation — `POST .../corrections/{id}/undo` (route de SF-99-02) :
   remet en place ce qui avait bougé, retire les alias et consignes créés ; une séparation annulée
   supprime N. Refusé (409) si une fusion ou séparation plus récente et active implique l'un des
   sujets, ou si N a reçu autre chose depuis la séparation.
4. **Les alias à la main** — `POST /api/radar/hosts/{hostId}/subjects/{subjectId}/aliases`
   `{ "alias": ... }` (origine `USER`) ; `DELETE .../subjects/{subjectId}/aliases/{aliasId}`.
5. **Registre** :
   - `addAlias(scope, subjectId, alias)` (origine `SYNC`) : **ignoré** si l'utilisateur a refusé ce
     nom pour ce sujet — une consigne est souveraine ;
   - `attachmentContext(scope, includeClosed)` : pour chaque sujet non fusionné, nom, état, alias,
     **alias refusés** et phrases du résumé — la matière de l'invite de rattachement (F-101).
6. **Lecture** : `AliasView` expose `origin` et `rejected` ; `SubjectDetail` expose `mergedIntoId`.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Fusion d'un sujet avec lui-même, `intoSubjectId` absent | `radar_invalid` | 400 |
| Fusion ou séparation d'un sujet déjà fusionné (source ou cible) | `radar_subject_merged` | 409 |
| Correction de SF-99-02 sur un sujet fusionné | `radar_subject_merged` | 409 |
| Séparation sans nom, sans preuve, ou avec **toutes** les preuves de S | `radar_invalid` | 400 |
| Preuve désignée absente de la chronologie de S, engagement d'un autre sujet | `radar_invalid` | 400 |
| Sujet cible / source d'un autre poste | `not_found` | 404 |
| Annulation recouverte par une fusion / séparation plus récente, ou N modifié depuis | `radar_correction_conflict` | 409 |
| Alias vide ou > 200, alias déjà présent | `radar_invalid` | 400 |
| Sans droit Teams | `teams_forbidden` | 403 |

---

## Critères d'acceptation

- [ ] Après fusion : la page de T montre la chronologie, les engagements et les phrases de S, et
      l'alias du nom de S ; `GET /subjects` ne liste plus S ; la page de S porte `mergedIntoId`.
- [ ] Après fusion, `registry.attachEvidence(S)` range la preuve dans la chronologie de T.
- [ ] Un rôle de S sur une personne déjà présente sur T ne remplace pas celui de T.
- [ ] Après séparation : N porte les preuves et engagements désignés ; S ne les a plus ; les consignes
      refusées existent des deux côtés ; une phrase entièrement sourcée par les preuves parties suit.
- [ ] `registry.addAlias` d'un nom refusé est ignoré.
- [ ] `attachmentContext` rend alias et refus, exclut les sujets fusionnés.
- [ ] Annuler une fusion rétablit S (liste, chronologie, engagements, phrases, alias) ; annuler une
      séparation supprime N et rend tout à S.
- [ ] Isolation : fusion vers un sujet d'un autre poste → 404, rien ne bouge.

---

## Périmètre

### Hors scope (explicite)

- Déplacement des rôles lors d'une séparation (les rôles restent sur S ; l'utilisateur les redira).
- Fusion de personnes de l'annuaire.
- Rattachement automatique d'un échange à un sujet (F-101) ; « ignorer ce fil » (F-100).
- Écrans (F-102, F-103) et outil `radar_merge_subjects` (F-104).

---

## Valeurs initiales

| Champ | Valeur initiale | Règle |
|-------|----------------|-------|
| `radar_subject_aliases.origin` | `USER` pour les lignes existantes | seuls les `RENAME` en ont créé |
| `radar_subject_aliases.rejected` | `false` | vrai pour une consigne « n'est pas ce sujet » |
| `radar_subjects.merged_into_id` | `null` | posé par la fusion |
| Sujet N (séparation) | `state = NEW`, `name_sovereign = true` | le nom vient de l'utilisateur |

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format / Valeurs autorisées | Unicité | Normalisation |
|-------|-------------|-------------|----------------------------|---------|---------------|
| intoSubjectId | Oui | — | UUID d'un sujet non fusionné du poste, ≠ source | — | — |
| split.name | Oui | 200 | non vide | — | trim |
| split.evidenceIds | Oui | — | ≥ 1, toutes dans la chronologie de S, pas toutes | — | dédoublonnées |
| split.commitmentIds | Non | — | engagements de S | — | dédoublonnés |
| alias | Oui | 200 | non vide | (user, host, subject, normalisé) | trim ; clé minuscule |
| origin | — | — | `SYNC`, `USER`, `MERGE`, `SPLIT` | — | — |

---

## Technique

### Endpoint(s)

| Méthode | URL | Auth | Rôle minimum |
|---------|-----|------|-------------|
| POST | `/api/radar/hosts/{hostId}/subjects/{subjectId}/merge` | Oui | droit Teams |
| POST | `/api/radar/hosts/{hostId}/subjects/{subjectId}/split` | Oui | droit Teams |
| POST | `/api/radar/hosts/{hostId}/subjects/{subjectId}/aliases` | Oui | droit Teams |
| DELETE | `/api/radar/hosts/{hostId}/subjects/{subjectId}/aliases/{aliasId}` | Oui | droit Teams |
| POST | `/api/radar/hosts/{hostId}/corrections/{id}/undo` | Oui | (SF-99-02, étendue) |

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| `radar_subjects` | INSERT / UPDATE / DELETE | + `merged_into_id` ; N créé / supprimé |
| `radar_subject_aliases` | INSERT / UPDATE / DELETE | + `origin`, `rejected` |
| `radar_evidence_links` | UPDATE | `subject_id` déplacé |
| `radar_commitments` / `radar_subject_facts` / `radar_subject_roles` | UPDATE | `subject_id` (et `position`) déplacés |
| `radar_corrections` | INSERT / UPDATE | actions `MERGE`, `SPLIT` |

### Migration Liquibase

- [x] Oui — `083-radar-merge-split.xml`

### Composants Angular (si applicable)

- Aucun (F-103, planifiée).

---

## Plan de test

### Tests service

- [ ] `RadarStructureServiceTest` — fusion (tout ce qui bouge, rôle en conflit, redirection des
      écritures, 409 sur sujet fusionné) ; séparation (preuves, engagements, phrase, consignes, 400) ;
      annulations et conflits ; `addAlias` refusé ; `attachmentContext`.

### Tests d'intégration

- [ ] `RadarStructureApiIntegrationTest` — fusion puis lecture (liste, page T, page S), séparation,
      alias ajout / suppression, annulation par la route du journal, 404 / 409 / 400.

### Isolation

- [x] Applicable — fusion vers un sujet d'un autre poste (même utilisateur) → 404 ; séparation avec une
      preuve d'un autre poste → 400 ; rien ne bouge.

---

## Dépendances

- SF-99-01, SF-99-02 — `done`.
- Questions ouvertes : aucune.

---

## Préoccupations transversales

- **Contexte tenant : oui.** Composants : `RadarStructureService` (nouveau), `RadarRegistry`
  (redirection des écritures, `addAlias`, `attachmentContext`), `RadarCorrectionService` (annulation
  déléguée) — tous à `RadarScope`.
- **Plans / limites : oui (lecture seule).** `TeamsAccessService.requireAccess()` sur les nouvelles routes.
- **Auth / Principal : non.** **Navigation : non.**

---

## Notes et décisions

- **Le sujet absorbé est gardé comme trace** plutôt que supprimé : c'est ce qui rend la fusion
  annulable et ce qui permet de rediriger une écriture tardive de la synchro au lieu de la perdre.
- **Les valeurs de la cible gagnent** (état, prochaine étape, échéance) : la fusion ne doit rien
  affirmer que personne n'a dit sur T.
- **Consigne = alias refusé** dans la même table : l'invite de F-101 lit en un seul endroit « ce sujet
  s'appelle aussi » et « ce sujet n'est pas ».
