# Mini-spec — [F-106 / SF-106-06] Le lien entre un sujet et un projet

---

## Identifiant

`F-106 / SF-106-06`

## Feature parente

`F-106` — La Vigie, l'espace du pilotage (cadrage : `CADRAGE-F-106-la-vigie.md` §3, §5)

## Statut

`done` — livrée le 2026-09-13 (PR #552)

## Date de création

2026-09-13

## Branche Git

`feat/SF-106-06-lien-sujet-projet`

---

## Objectif

Relier un sujet de la Vigie à un projet de la Forge du même poste — déclaré par l'utilisateur ou proposé
par l'analyse et confirmé d'un clic — et en tirer les deux passerelles « Voir le projet dans la Forge » et
« N sujets dans la Vigie ».

---

## Contexte

SF-106-04 n'a livré que les passerelles au niveau client et a laissé la question « déclaré ou déduit ? »
au PO. **Décision du PO (2026-09-13) : les deux voies.** (a) Lien déclaré sur la page sujet (F-103),
souverain ; (b) lien **proposé** par l'analyse (F-101) quand un échange nomme un projet du poste,
affiché comme une question, confirmé ou refusé d'un clic ; un refus est retenu et jamais reproposé.

---

## Comportement attendu

### Cas nominal

1. **Table `radar_subject_projects`** (migration `097`) : une ligne par paire (sujet, projet) —
   `user_id`, `host_id`, `subject_id`, `workspace_id`, `origin` (`USER` | `PROPOSED`), `state`
   (`CONFIRMED` | `PROPOSED` | `REFUSED`), `created_at`. Unicité `(subject_id, workspace_id)` : c'est elle
   qui retient le refus.
2. **Les projets du poste** = `WorkspaceService.listByHost(userId, hostId)` (terminaux exclus). Un projet
   d'un autre poste, d'un autre compte ou supprimé n'est **jamais** lié ni affiché.
3. **Page sujet — lecture** : `GET /radar/hosts/{hostId}/subjects/{subjectId}/projects` →
   `{ inForge, links: [{ workspaceId, name, projectPath, origin, state }], candidates: [{ workspaceId, name,
   projectPath }] }`. `links` = lignes `CONFIRMED` et `PROPOSED` ; `candidates` = projets du poste sans
   lien confirmé ni proposé (un projet refusé redevient un candidat pour un lien manuel).
4. **Lier** : `PUT .../projects/{workspaceId}` → ligne absente : `USER`/`CONFIRMED` ; `PROPOSED` →
   `CONFIRMED` (origine conservée : on sait qu'il venait de l'analyse) ; `REFUSED` → `USER`/`CONFIRMED` ;
   déjà `CONFIRMED` : sans effet. Réponse : la vue du point 3.
5. **Délier / refuser** : `DELETE .../projects/{workspaceId}` → ligne `CONFIRMED` ou `PROPOSED` →
   `REFUSED` (l'analyse ne la reproposera jamais) ; absente ou déjà refusée : sans effet. Réponse : la vue.
6. **Proposition par l'analyse** : après l'écriture d'un sujet par `RadarExtractionWriter`, le
   `RadarProjectProposer` lit le texte des messages qui prouvent ce sujet et le titre de leurs échanges ;
   si un **nom de projet** ou un **nom de dossier** (dernier segment du chemin) du poste y figure en mot
   entier (casse et accents ignorés, 3 caractères au moins, jamais le nom du poste lui-même), une ligne
   `PROPOSED`/`PROPOSED` est créée **seulement si aucune ligne n'existe** pour la paire. Rien n'est lié
   sans l'utilisateur. Déterministe : aucun appel au fournisseur de plus.
7. **Page sujet — écran** (colonne de droite, section « Projet dans la Forge ») :
   - une proposition se lit comme une question : « Ce sujet concerne-t-il le projet **X** ? » avec
     **Oui, lier** et **Non** ;
   - un lien confirmé : nom et dossier du projet, **Voir le projet dans la Forge** (`/forge/<hostId>`,
     seulement si le client est activé dans la Forge) et **Délier** ;
   - **Lier à un projet de la Forge** : menu des candidats ; absent s'il n'y en a aucun ;
   - aucun projet sur le poste et aucun lien : « Aucun projet de la Forge sur ce poste. »
8. **Forge — passerelle inverse** : `GET /radar/hosts/{hostId}/project-subjects` →
   `[{ workspaceId, subjects: [{ id, name }] }]` (liens `CONFIRMED`, sujets non fusionnés). La Forge ne
   l'appelle que pour un client **activé dans la Vigie** ; sur la tuile d'un projet, si N > 0 :
   **« N sujet(s) dans la Vigie »** — un lien vers la page du sujet si N = 1, un menu des sujets sinon.
   Échec de lecture (droit Vigie absent, réseau) : rien n'est affiché.
9. **Purge** : la purge du Radar d'un poste et la suppression du compte effacent les liens.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Sans droit Vigie | Refus (garde Vigie existante) | 403 |
| Poste inconnu ou d'autrui | Introuvable | 404 |
| Poste non activé dans la Vigie | `host_not_in_space` | 409 |
| Sujet inconnu, d'un autre poste ou d'autrui | Introuvable | 404 |
| Sujet fusionné (lier) | `radar_subject_merged` (existant) | 409 |
| Projet d'un autre poste, d'autrui, inconnu ou terminal | Introuvable, rien n'est écrit | 404 |
| Texte d'échange sans nom de projet, ou paire déjà refusée | Aucune proposition | — |

---

## Critères d'acceptation

- [x] CA1 — `PUT` lie un projet du poste (`USER`/`CONFIRMED`) ; `GET` le rend dans `links`, plus dans
      `candidates`.
- [x] CA2 — `DELETE` passe le lien à `REFUSED` ; il disparaît de `links` et redevient candidat.
- [x] CA3 — Un projet d'un autre poste du même compte, ou d'un autre compte : `PUT` → 404, aucune ligne.
- [x] CA4 — Un échange qui nomme « billing-api » (dossier) propose le lien `PROPOSED` ; relu avec la paire
      refusée, aucune ligne nouvelle ; un texte sans nom de projet ne propose rien ; le nom du poste ne
      propose rien.
- [x] CA5 — `PUT` sur une proposition la confirme (origine `PROPOSED` conservée).
- [x] CA6 — `GET /project-subjects` rend les seuls liens confirmés, par projet.
- [x] CA7 — Purge du Radar d'un poste : les liens de ce poste disparaissent, pas ceux d'un autre poste.
- [x] CA8 — Écran sujet : proposition avec « Oui, lier » / « Non », lien confirmé avec « Voir le projet
      dans la Forge » (si activé dans la Forge) et « Délier », menu « Lier à un projet de la Forge ».
- [x] CA9 — Tuile Forge : « 2 sujets dans la Vigie » pour un client activé dans la Vigie, rien si N = 0 ou
      si le client n'est pas dans la Vigie.

---

## Périmètre

### Hors scope (explicite)

- Lecture des liens par l'agent (outils Radar, F-104) et proposition par le terminal Teams.
- Report des liens d'un sujet fusionné sur sa cible, et partage des liens à la séparation : la page d'un
  sujet fusionné montre ses liens mais n'en accepte pas de nouveaux ; la Forge n'affiche pas les sujets
  fusionnés.
- Filtre du Radar par projet ; mise en évidence du projet dans `/forge/<hostId>`.
- Proposition à partir de résumés ou d'alias du sujet (seuls les échanges qui le prouvent sont lus).

---

## Valeurs initiales

Aucune ligne à la migration.

## Contraintes de validation

| Champ | Obligatoire | Format / Valeurs autorisées |
|-------|-------------|----------------------------|
| `workspaceId` (chemin) | Oui | UUID d'un projet de `listByHost(user, host)` |
| `origin` | Oui | `USER`, `PROPOSED` |
| `state` | Oui | `CONFIRMED`, `PROPOSED`, `REFUSED` |
| nom proposé | — | 3 caractères au moins, mot entier, différent du nom du poste |

---

## Technique

### Endpoint(s)

| Méthode | URL | Auth | Rôle |
|---------|-----|------|------|
| GET | `/api/radar/hosts/{hostId}/subjects/{subjectId}/projects` | Oui | droit Vigie |
| PUT | `/api/radar/hosts/{hostId}/subjects/{subjectId}/projects/{workspaceId}` | Oui | droit Vigie |
| DELETE | `/api/radar/hosts/{hostId}/subjects/{subjectId}/projects/{workspaceId}` | Oui | droit Vigie |
| GET | `/api/radar/hosts/{hostId}/project-subjects` | Oui | droit Vigie |

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| `radar_subject_projects` | CREATE + CRUD | nouvelle |
| `workspaces` | SELECT | projets du poste |
| `radar_subjects` | SELECT | périmètre, fusion |
| `host_spaces` | SELECT | `inForge` |

### Migration Liquibase

- [x] `097-radar-subject-projects.xml` — création de table, unicité `(subject_id, workspace_id)`, index
      `(user_id, host_id, workspace_id)` et `(user_id, host_id, subject_id)` ; rollback : drop. Numéro
      revérifié après rebase.

### Composants

- Backend : `RadarSubjectProject`, `RadarSubjectProjectOrigin`, `RadarSubjectProjectState`,
  `RadarSubjectProjectRepository`, `RadarSubjectProjectService`, `RadarSubjectProjectController`,
  `analysis/RadarProjectProposer`, `RadarExtractionWriter` (appel du proposeur), `RadarPurgeService`.
- Frontend : `radar-subject.models`, `radar-subject.service` (lecture, lier, délier),
  `vigie.service` (`projectSubjects`), `radar-subject-page` (section), `postes.component` (lecture par
  client activé dans la Vigie), `forge-project-tile` (lien ou menu).

### Préoccupations transversales

- [x] **Navigation / routing** — nouveaux liens vers des routes existantes : `/forge/<hostId>` (page
  sujet) et `/vigie/<hostId>/sujets/<id>` (tuile Forge). Aucune route ni garde modifiée ; vérifiés :
  `vigieMatcher`, `forgeMatcher`, `vigie/:hostRef/sujets/:subjectId`.
- [ ] Auth / Principal — non.
- [ ] Contexte tenant — non (isolation `user_id` + `host_id` appliquée à chaque lecture).
- [ ] Plans / limites — non (garde Vigie existante réemployée, aucun gate modifié).

---

## Plan de test

### Tests unitaires

- [ ] `RadarProjectProposerTest` — nom de dossier et nom de projet reconnus en mot entier, casse et
      accents ignorés ; trop court, nom du poste, sous-mot : rien ; paire existante (refusée) : rien.
- [ ] `RadarSubjectProjectServiceTest` — transitions de `link` / `unlink` ; projet hors poste → 404.

### Tests d'intégration

- [ ] `RadarSubjectProjectApiIntegrationTest` — CA1, CA2, CA3 (autre poste, autre compte), CA5, CA6,
      CA7, 403 sans droit Vigie, 409 client hors Vigie, 404 sujet d'un autre poste.
- [ ] Analyse de bout en bout existante (`RadarExtractionWriter`) : non-régression + proposition (CA4).

### Frontend

- [ ] `radar-subject-page.component.spec` — proposition, confirmation, refus, lien confirmé, menu.
- [ ] `forge-project-tile.component.spec` — 0, 1, 2 sujets.
- [ ] `postes.component.spec` — lecture seulement pour un client activé dans la Vigie.

### Isolation

- [x] Applicable — chaque requête filtre `user_id` + `host_id` ; projet vérifié dans `listByHost` ; tests
      autre poste / autre compte.

---

## Dépendances

### Subfeatures bloquantes

SF-106-01→05, F-99, F-101, F-103 (livrées).

### Questions ouvertes impactées

Aucune (question PO de SF-106-04 tranchée le 2026-09-13).

---

## Notes et décisions

- **Délier = refuser** : l'utilisateur qui défait un lien dit « ce n'est pas ce projet » ; l'analyse ne doit
  pas le remettre le lendemain. Il peut toujours relier à la main.
- **Proposition déterministe** (correspondance de noms) plutôt qu'une question de plus au modèle : le
  cadrage demande une question à l'utilisateur, pas une certitude ; aucun coût de plus, et le refus retenu
  borne le bruit.
