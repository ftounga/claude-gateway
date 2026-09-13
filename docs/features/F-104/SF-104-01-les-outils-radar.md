# Mini-spec — F-104 / SF-104-01 — Les outils Radar

> Base : `docs/features/F-99/CADRAGE-le-radar.md` §4 (règles qui empêchent le Radar de mentir), §6
> (« l'utilisateur le dit : clos immédiatement »), §9 (nourrir le Radar), §12 bis F-104. Cadrage validé
> par le PO.

## Identifiant

`F-104 / SF-104-01`

## Feature parente

`F-104` — Le Radar : le nourrir

## Statut

`done` — PR #544

## Date de création

2026-09-13

## Branche Git

`feat/SF-104-01-outils-radar`

---

## Objectif

Donner à un agent les **six outils Radar** (`radar_find_subject`, `radar_update_subject`,
`radar_close_subject`, `radar_add_engagement`, `radar_mark_engagement`, `radar_merge_subjects`), qui
lisent et écrivent le registre **d'un seul poste** à partir de **la parole de l'utilisateur**, gardés par
le **droit Vigie** dans `buildTools`, chaque écriture étant une correction souveraine journalisée,
rattachée à sa preuve `user_note` et annulable.

---

## Comportement attendu

### Cas nominal

1. **Le catalogue** (`RadarToolCatalog`) déclare les six outils, leurs schémas et leurs descriptions (qui
   portent la doctrine : la parole de l'utilisateur est la preuve, rien n'est inventé, un `probable` n'est
   pas écrit comme certain, dire ce qu'on a compris avant d'écrire). Un seul endroit, préfixe `radar_`.
2. **La garde** : les outils ne sont donnés que si **toutes** les conditions tiennent — le workspace est le
   **terminal Teams** d'un poste (`hostId` non nul), l'utilisateur a le **droit Vigie**
   (`TeamsAccessService.hasAccess(userId)`, bypass administrateur compris) et le poste est **activé dans la
   Vigie** (`HostSpaceService.isActive`). Sinon : liste vide, en silence (l'agent n'a pas la capacité).
   `buildTools` ajoute ce catalogue après les outils Teams.
3. **Le second verrou** : un appel `radar_*` non déclaré (le modèle nomme l'outil de lui-même) est refusé
   par la boucle avec un motif, sans rien lire ni écrire.
4. **La preuve** : dans le terminal, la preuve d'une écriture est **le message de l'utilisateur du tour** —
   `USER_NOTE`, `source_ref = atelier-message:<id du message>`, datée de l'ouverture du tour, citation courte
   (≤ 280 caractères, règle §4.6). Elle est créée à la **première écriture** du tour, idempotente par
   `source_ref` (plusieurs écritures, une seule preuve). Une lecture ne crée rien.
5. **Les outils** (périmètre `RadarScope(userId, hostId du terminal)`, jamais un identifiant de poste venu
   du modèle) :
   - `radar_find_subject { query?, include_closed? }` — lecture : sujets du poste dont le nom ou un alias
     contient la requête ou l'un de ses mots significatifs (classés par nombre de mots trouvés, puis
     activité) ; sans requête, les sujets ouverts. Pour chacun (15 au plus) : id, nom, état, prochaine
     étape, échéance, dernière activité, alias, résumé (5 phrases au plus), engagements en cours non
     désavoués (10 au plus : id, sens, description, personnes, échéance, certitude, relance due).
     Résultat JSON borné à 12 000 caractères (`truncated` dit la coupe).
   - `radar_update_subject { subject_id | new_subject_name, name?, state?, next_step?, due_date? }` — dit
     l'état (`NEW`, `ADVANCING`, `WAITING`, `BLOCKED`), la prochaine étape, l'échéance (`AAAA-MM-JJ`, vide
     = l'effacer), le nom ; chaque champ **différent** de la valeur courante devient une correction
     souveraine (`SET_STATE`, `SET_NEXT_STEP`, `SET_DUE_DATE`, `RENAME`). Sans `subject_id` et avec
     `new_subject_name` : **crée** le sujet à partir de la preuve (correction `CREATE_SUBJECT`). Un sujet
     fusionné est redirigé vers le sujet qui l'a absorbé (le résultat le dit).
   - `radar_close_subject { subject_id }` — clôt immédiatement (correction `CLOSE`, parole souveraine) ; le
     résultat liste les engagements encore ouverts et demande de poser la question « le fermer aussi ? ».
   - `radar_add_engagement { subject_id, direction, description, from_person?, to_person?, other_person?,
     due_date? }` — engagement `CERTAIN`, souverain, personnes désignées **par leur nom** (retrouvées dans
     l'annuaire du poste sans casse, sinon créées avec la clé `note:<nom>`) ; correction `ADD_COMMITMENT`.
   - `radar_mark_engagement { commitment_id, status, due_date? }` — `DONE`, `ABANDON`, `POSTPONE` (échéance
     requise), `REOPEN`, `NOT_MINE` : la correction d'engagement existante.
   - `radar_merge_subjects { source_subject_id, into_subject_id }` — la fusion existante (SF-99-03).
6. **Chaque écriture**, dans **une seule transaction** : preuve enregistrée, correction(s) écrite(s) et
   **marquée(s) de la preuve** (`radar_corrections.evidence_id`), preuve rangée dans la chronologie du
   sujet touché. Le résultat rendu au modèle dit **ce qui a été écrit**, en clair, et que c'est annulable.
7. **Annulation** (journal existant, étendu) : `CREATE_SUBJECT` supprime le sujet s'il n'a rien reçu
   d'autre que la preuve qui l'a créé ; `ADD_COMMITMENT` supprime l'engagement et ses liens s'il n'a pas été
   corrigé depuis. Les autres actions s'annulent comme avant.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Terminal de projet, poste hors Vigie, sans droit, workspace sans poste | outils non déclarés ; appel forcé refusé (« n'existent que dans le terminal Teams d'un client suivi par la Vigie ») | — (résultat d'outil en erreur) |
| Sujet / engagement inconnu ou d'un autre poste | résultat en erreur « introuvable sur ce poste », rien n'est écrit | — |
| Paramètre manquant ou invalide (état de clôture, date illisible, sens inconnu, partie manquante) | résultat en erreur avec la phrase qui dit quoi corriger, rien n'est écrit | — |
| Aucune modification demandée (valeurs identiques) | résultat « rien à changer », rien n'est écrit, aucune preuve créée | — |
| Sujet déjà clos (`radar_close_subject`) | erreur « déjà clos », rien n'est écrit | — |
| Échec au milieu d'une écriture | transaction annulée : ni preuve, ni correction, ni lien | — |
| Annuler `CREATE_SUBJECT` d'un sujet qui a reçu d'autres preuves ou engagements | `radar_correction_conflict` | 409 |
| Annuler `ADD_COMMITMENT` d'un engagement corrigé depuis | `radar_correction_conflict` | 409 |

---

## Critères d'acceptation

- [ ] Terminal Teams d'un poste activé dans la Vigie + droit : les six outils `radar_*` sont dans la panoplie.
- [ ] Terminal de projet, sans droit, poste hors Vigie, workspace sans poste : aucun outil `radar_*`, et un
      appel forcé est refusé sans effet.
- [ ] `radar_find_subject` retrouve un sujet par un mot de son nom ou d'un alias, avec résumé et engagements
      en cours, et **jamais** un sujet d'un autre poste du même utilisateur ni d'un autre utilisateur.
- [ ] `radar_update_subject` / `radar_close_subject` / `radar_add_engagement` / `radar_mark_engagement` /
      `radar_merge_subjects` écrivent des corrections souveraines marquées de la preuve `USER_NOTE` du
      message, preuve rangée dans la chronologie ; une seule preuve pour plusieurs écritures du même message.
- [ ] Clore rend les engagements encore ouverts.
- [ ] Un échec d'écriture ne laisse ni preuve ni correction.
- [ ] Annuler `CREATE_SUBJECT` et `ADD_COMMITMENT` défait la création ; conflit si l'objet a vécu depuis.
- [ ] Migration 096 appliquée sur H2 (tests) ; colonne nullable, lignes existantes intactes.

---

## Périmètre

### Hors scope (explicite)

- L'écran *Donner la nouvelle*, le courriel collé, l'annulation d'une nouvelle entière (SF-104-02).
- La consigne système du terminal Teams, les libellés d'étape et le rendu à l'écran (SF-104-03).
- Écrire le résumé phrase par phrase, les rôles : réservés à la lecture des échanges (F-101).
- Écrire quoi que ce soit dans Teams (hors périmètre du cadrage §15).

---

## Valeurs initiales

| Champ | Valeur initiale | Règle |
|-------|----------------|-------|
| `radar_corrections.evidence_id` | `NULL` | les corrections d'avant F-104 n'ont pas de preuve |
| certitude d'un engagement ajouté | `CERTAIN` | la parole de l'utilisateur |
| sujets rendus par `radar_find_subject` | 15 | au-delà : `truncated` |
| phrases de résumé / engagements par sujet | 5 / 10 | — |

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format / Valeurs autorisées | Unicité | Normalisation |
|-------|-------------|-------------|----------------------------|---------|---------------|
| `subject_id`, `commitment_id`, `source_subject_id`, `into_subject_id` | selon l'outil | — | UUID du poste | — | — |
| `new_subject_name`, `name` | selon l'outil | 200 | texte | — | `trim` |
| `state` | Non | — | `NEW` `ADVANCING` `WAITING` `BLOCKED` | — | majuscules |
| `next_step` | Non | limite `RadarSubject` | texte, vide = effacer | — | `trim` |
| `due_date` | Non (requis pour `POSTPONE`) | — | `AAAA-MM-JJ`, vide = effacer | — | — |
| `direction` | Oui (ajout) | — | `ME_TO_OTHER` `OTHER_TO_ME` `INTRODUCTION` | — | — |
| `description` | Oui (ajout) | limite `RadarCommitment` | texte | — | `trim` |
| `status` | Oui (marquer) | — | `DONE` `ABANDON` `POSTPONE` `REOPEN` `NOT_MINE` | — | — |
| `query` | Non | 200 | texte | — | clé du Radar |
| preuve (citation) | Oui | 280 | message de l'utilisateur, tronqué | `source_ref` | `RadarText.quote` |

---

## Technique

### Endpoint(s)

Aucun nouvel endpoint ; l'annulation passe par `POST /api/radar/hosts/{hostId}/corrections/{id}/undo`
(existant, étendu à `CREATE_SUBJECT` et `ADD_COMMITMENT`).

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| `radar_corrections` | ALTER (+ `evidence_id`), INSERT, UPDATE | migration 096 |
| `radar_evidence`, `radar_evidence_links` | INSERT / DELETE (annulation) | `user_id` + `host_id` |
| `radar_subjects`, `radar_commitments`, `radar_people` | INSERT / UPDATE / DELETE (annulation) | via les services du Radar |

### Migration Liquibase

- [x] Oui — `096-radar-news.xml` : `radar_corrections.evidence_id uuid NULL` + index
  `(user_id, host_id, evidence_id)`. Types portables, un seul changeSet (H2 et PostgreSQL). Rollback :
  suppression de l'index puis de la colonne.

### Composants

- Backend : `RadarToolCatalog` (définitions, garde), `RadarToolExecutor` (exécution transactionnelle),
  `RadarNote` (la preuve d'une écriture), `RadarCorrection.evidenceId`, `RadarCorrectionAction`
  (`CREATE_SUBJECT`, `ADD_COMMITMENT`), `RadarCorrectionJournal.record(…, evidenceId)`,
  `RadarCorrectionService.undo` (deux cas), `AtelierChatService` (`buildTools`, routage de la boucle,
  second verrou, preuve du message).
- Frontend : aucun (SF-104-02 / SF-104-03).

### Préoccupations transversales

- **Plans / limites : oui.** Composants vérifiés : `TeamsAccessService.hasAccess(userId)` (droit Vigie via
  `SpaceEntitlementService`, bypass administrateur limité au principal courant), `TeamsToolCatalog.toolsFor`
  (inchangé), `HostSpaceService.isActive`. Aucun quota nouveau : les outils s'exécutent dans le tour, dont
  la consommation est déjà décomptée par `AtelierChatService`.
- **Contexte tenant : oui.** Composants vérifiés : `RadarScope` construit depuis le `userId` du tour et le
  `hostId` du workspace possédé (`WorkspaceService.requireOwned` en amont), `RadarRegistry.require*`
  (filtre `user_id` + `host_id`), `RadarCorrectionService`, `RadarClosureService`, `RadarStructureService`,
  `RadarReadService`.
- Navigation / routing : non. Auth / Principal : non.

---

## Plan de test

### Tests unitaires

- [ ] `RadarToolCatalogTest` — garde (terminal Teams + poste + droit + Vigie → six outils ; chaque condition
      manquante → vide) ; noms et schémas.
- [ ] `AtelierChatServiceRadarToolsTest` — `buildTools` : outils présents / absents ; appel forcé hors garde
      refusé sans appel à l'exécuteur.

### Tests d'intégration

- [ ] `RadarToolExecutorIntegrationTest` (H2) — trouver par mot et par alias ; mettre à jour (corrections
      marquées de la preuve, une seule preuve pour deux écritures) ; créer ; clore (engagements ouverts
      rendus) ; ajouter un engagement (personne retrouvée / créée) ; marquer ; fusionner ; valeurs
      identiques → rien ; échec → rien écrit ; annuler `CREATE_SUBJECT` / `ADD_COMMITMENT` et leurs conflits.

### Isolation workspace

- [x] Applicable — `radar_find_subject` et chaque écriture sous le périmètre du poste EDENRED d'Alice ne
      voient ni ne touchent un sujet du poste CAGIP d'Alice ni du poste de Bob (identifiant d'autrui →
      « introuvable », rien écrit).

---

## Dépendances

### Subfeatures bloquantes

- F-99 (registre, corrections, fusion, clôture), F-106 (espaces), F-107 / SF-107-03 (droit Vigie) — livrées.

### Questions ouvertes impactées

- Aucune.

---

## Notes et décisions

- **La parole de l'utilisateur est la preuve** (cadrage §9) : dans le terminal, c'est le message du tour, et
  non une citation que le modèle fournirait — le modèle ne peut pas fabriquer la preuve d'un fait.
- **Écriture = correction souveraine journalisée** : c'est ce qui rend « tout annulable depuis la
  chronologie » vrai sans nouveau mécanisme, et ce qui empêche une synchro de réécrire ce que l'utilisateur
  a dit (§4.2).
- **Pas de phrase de résumé ni de rôle** par ces outils : le résumé sourcé reste le produit de la lecture
  des échanges ; la note entre dans la chronologie, où elle se lit.
