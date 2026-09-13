# Mini-spec — F-103 / SF-103-02 — Qui, et à qui demander

## Identifiant

`F-103 / SF-103-02`

## Feature parente

`F-103` — Le Radar, la page sujet (cadrage : `docs/features/F-99/CADRAGE-le-radar.md` §4.3, §4.4,
§8 ; maquette 2, colonne de droite)

## Statut

`in-progress`

## Date de création

2026-09-13

## Branche Git

`feat/SF-103-02-qui-a-qui-demander`

---

## Objectif

Dire, sur la page d'un sujet, **qui est dans ce sujet** (personnes et rôles), **ce que le Radar ne
sait pas**, et pour chaque manque **la personne à qui poser la question**.

---

## Comportement attendu

### Cas nominal

1. **Qui est dans ce sujet** (colonne de droite de la page) : les personnes du sujet, rangées par
   rôle — *décide*, *pilote*, *expert*, *informé* — puis par nom ; nom, fonction si la source la
   fournit, **rôle écrit**, et les renvois de preuve du rôle (numéros de SF-103-01).
2. **Ce que le Radar ne sait pas** : `GET /api/radar/hosts/{hostId}/subjects/{subjectId}/unknowns`
   rend une liste ordonnée de manques, **calculée depuis le registre** (aucun appel au modèle) :

   | Ordre | Manque (`kind`) | Quand | Question rendue | À qui demander (dans l'ordre) |
   |---|---|---|---|---|
   | 1 | `COVERAGE` | la dernière synchro du poste est `PARTIAL` ou `FAILED` | « La dernière synchro n'a pas tout lu : ce qui précède peut être incomplet. » | personne |
   | 2 | `NEXT_STEP` | pas de prochaine étape | « La prochaine étape n'est pas connue. » | pilote → décideur → dernier auteur |
   | 3 | `DUE_DATE` | pas d'échéance (sujet ouvert ou en sommeil) | « Aucune échéance n'est connue. » | pilote → décideur → dernier auteur |
   | 4 | `DECIDER` | aucune personne n'a le rôle *décide* | « On ne sait pas qui décide. » | pilote → dernier auteur |
   | 5 | `SILENCE` | sujet `en sommeil` | « Rien n'a bougé depuis le 20 août : où en est le sujet ? » | pilote → décideur → dernier auteur |
   | 6 | `OWNER` | engagement en cours, non désavoué, `probable` | « « Rédiger la note DSI » : évoqué, sans porteur certain. » | partie de l'engagement → auteur de sa preuve la plus récente → pilote |
   | 7 | `DEDUCED_DUE` | engagement en cours, `certain`, échéance **déduite** | « « Envoyer le plan » : l'échéance du 2 octobre est déduite, pas écrite. » | partie → auteur → pilote |

   Au plus **8** manques (les engagements au-delà sont omis). Un sujet **clos** ou **fusionné**
   n'a aucun manque.
3. **À qui demander** : chaque manque porte (ou non) une personne — identifiant, nom, fonction, rôle
   sur le sujet s'il en a un — et **la raison** en mots : « pilote le sujet », « décide sur ce
   sujet », « est attendu sur cet engagement », « attend cet engagement », « a écrit en dernier sur le
   sujet, le 12 septembre », « a écrit la preuve de cet engagement ». Sans personne connue : « Personne
   n'est identifié sur ce sujet. » (sauf `COVERAGE`, qui n'a pas de destinataire). Un manque porte
   aussi les preuves qui le fondent (engagement) pour les renvois.
4. **Téléphone** : la colonne de droite passe sous la colonne principale.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Sujet inconnu, d'un autre poste ou d'un autre compte | introuvable, sans rien dire | 404 |
| Poste d'autrui | introuvable | 404 |
| Poste hors Vigie | `host_not_in_space` | 409 |
| Sans droit Vigie (Teams) | refus du droit, avant toute lecture de poste | 403 |
| `subjectId` malformé | refus | 400 |
| Les manques ne se lisent pas (écran) | la page s'affiche sans l'encart, avec « Ce que le Radar ne sait pas n'a pas pu être lu. » | — |

---

## Critères d'acceptation

- [ ] Un sujet sans prochaine étape, sans échéance et sans décideur, avec Sophie *pilote*, rend
      `NEXT_STEP`, `DUE_DATE`, `DECIDER`, chacun adressé à Sophie avec la raison « pilote le sujet ».
- [ ] Sans rôle *pilote* ni *décide*, le destinataire est l'auteur de la preuve la plus récente de la
      chronologie, avec sa date.
- [ ] Un engagement `probable` d'une autre personne vers moi est adressé à cette personne ; un
      engagement `certain` à échéance déduite rend `DEDUCED_DUE`.
- [ ] Dernière synchro `PARTIAL` → `COVERAGE` en tête, sans destinataire.
- [ ] Sujet clos ou fusionné → liste vide ; sujet en sommeil → `SILENCE`.
- [ ] Au plus 8 manques.
- [ ] Isolation : le sujet d'Alice sur le poste A n'est lisible ni sous son poste B, ni par Bob
      (404) ; poste hors Vigie → 409 ; aucune personne d'un autre poste n'est proposée.
- [ ] La page affiche les personnes rangées par rôle (rôle écrit), et l'encart des manques avec le
      destinataire et la raison.

---

## Périmètre

### Hors scope (explicite)

- Deviner un manque par le modèle (« la date exacte du pilote ») : les manques sont ceux que le
  registre **prouve** ; la formulation fine appartient à la réponse au manager (SF-103-03).
- Contacter la personne (relance préparée : F-104 / SF-104-05).
- Modifier un rôle depuis la page.
- L'annuaire complet (SF-103-04).

---

## Valeurs initiales

Aucune : lecture seule.

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format / Valeurs autorisées | Unicité | Normalisation |
|-------|-------------|-------------|----------------------------|---------|---------------|
| `hostId` | Oui | — | UUID | — | — |
| `subjectId` | Oui | — | UUID | — | — |
| `kind` (sortie) | — | — | `COVERAGE`, `NEXT_STEP`, `DUE_DATE`, `DECIDER`, `SILENCE`, `OWNER`, `DEDUCED_DUE` | — | — |
| manques (sortie) | — | 8 | — | — | — |

---

## Technique

### Endpoint(s)

| Méthode | URL | Auth | Rôle minimum |
|---------|-----|------|-------------|
| GET | `/api/radar/hosts/{hostId}/subjects/{subjectId}/unknowns` | JWT | propriétaire du poste, droit Teams/Vigie, poste activé dans la Vigie |

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| `radar_subjects`, `radar_subject_roles`, `radar_people`, `radar_commitments`, `radar_evidence`, `radar_evidence_links`, `radar_subject_facts`, `radar_subject_aliases` | SELECT | via `RadarReadService.subject`, filtré `user_id` + `host_id` |
| `radar_syncs` | SELECT | dernière synchro du périmètre |

### Migration Liquibase

- [x] Non applicable

### Composants

- Backend : `RadarUnknowns` (fonction pure), `RadarUnknownsService`, `RadarSubjectPageController`
  (nouveau contrôleur : `RadarController` et `RadarBoardController` ne sont pas touchés),
  `dto/RadarSubjectPageViews`.
- Frontend : `RadarSubjectService.unknowns`, modèles, `radar-subject-view.ts` (rôles en mots, ordre),
  `RadarSubjectPageComponent` (colonne de droite).

### Préoccupations transversales

- **Contexte tenant : oui (lecture).** Composants vérifiés : `RadarScopeResolver.requireInVigie`
  (possession puis Vigie), `RadarReadService.subject` (dépôts filtrés `user_id` + `host_id`),
  `RadarSyncRepository.findByUserIdAndHostIdOrderByStartedAtDesc`. Aucun nouveau moyen de résoudre
  le poste.
- Navigation : non (même page). Auth / Principal : non (même garde que `RadarController`). Plans /
  limites : non (aucun appel modèle).

---

## Plan de test

### Tests unitaires

- [ ] `RadarUnknownsTest` — chaque manque et son ordre ; destinataire pilote → décideur → dernier
      auteur ; partie de l'engagement et auteur de sa preuve ; `COVERAGE` sans destinataire ; clos /
      fusionné vide ; engagement désavoué ou tenu ignoré ; plafond 8 ; personne inconnue → « Personne
      n'est identifié ».
- [ ] `radar-subject-view.spec.ts` — rôles en mots et ordre.
- [ ] `radar-subject-page.component.spec.ts` — personnes rangées ; manques avec destinataire et
      raison ; manques illisibles.
- [ ] `radar-subject.service.spec.ts` — adresse des manques.

### Tests d'intégration

- [ ] `RadarUnknownsApiIntegrationTest` — nominal (pilote désigné) ; synchro partielle ;
      isolation : autre poste d'Alice 404, Bob 404 ; poste hors Vigie 409 ; identifiant malformé 400.

### Isolation workspace

- [x] Applicable — deux postes du même compte et deux comptes (`RadarIntegrationTestBase`).

---

## Dépendances

### Subfeatures bloquantes

- SF-103-01 — `done` (PR #539).

### Questions ouvertes impactées

- Aucune.

---

## Notes et décisions

- **Calculé depuis le registre, pas par le modèle** : la règle §4.1 « pas de fait sans preuve » vaut
  aussi pour les manques — un manque dit ce que le registre ne contient pas, rien de plus. Pas de
  consommation, pas de latence.
- **Alignement sur `DESIGN_SYSTEM.md` §17** (livré par F-102 / SF-102-03 pendant cette sous-feature) : la
  page sujet prend les pastilles d'état de l'onglet Radar (`SUBJECT_STATE_CHIPS`, `.radar-state--*`, bleu
  §9 index 0 pour « nouveau / clos ? / se réveille »), l'icône `forum` d'un message Teams, le lien
  *Ouvrir la source* (avec la seconde pour une réunion), aucune couleur sur une source ni sur une
  personne (rôle en pastille neutre), liens et renvois à l'encre `--cg-primary`.
- **Nouveau contrôleur** plutôt qu'un ajout à `RadarController` / `RadarBoardController` : F-102 les
  modifie en parallèle.
