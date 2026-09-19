# Mini-spec — [F-128 / SF-128-06] Actions d'une réunion → engagements Radar (« À faire par moi »)

---

## Identifiant

`F-128 / SF-128-06`

## Feature parente

`F-128` — Capturer et exploiter une réunion Teams (écran + audio → transcription → exploitation)

## Statut

`ready`

## Date de création

2026-09-19

## Branche Git

`feat/SF-128-06-actions-reunion-engagements-radar`

---

## Note de re-scope (PO, 2026-09-19)

SF-128-06 était esquissée « option CRA » dans le découpage initial de F-128. Le PO la **redéfinit** :
les **actions** extraites par l'exploitation d'une réunion (SF-128-05) doivent devenir des
**engagements Radar « À faire par moi » (`ME_TO_OTHER`)**, pour apparaître dans le **résumé du matin**
(F-102). **PAS de nouvelle liste de tâches** : on réutilise le modèle Radar existant. Le CRA (F-124)
reste un débouché distinct, hors de cette SF.

---

## Objectif

> En une phrase : depuis une réunion exploitée, pousser les **actions** retenues par le PO dans le
> **Radar** comme engagements **« À faire par moi »**, rattachés à un sujet, avec la **réunion pour
> preuve** et **annulables** comme tout le Radar.

---

## Comportement attendu

### Cas nominal

1. Le PO a analysé une réunion (SF-128-05, `POST …/insights`) : l'écran affiche `insights.actions`.
2. Le PO **coche** les actions à suivre (par défaut toutes cochées) et **désigne le sujet cible**, puis
   clique « Pousser vers « À faire par moi » ».
3. Le front appelle `POST /vigie/hosts/{hostId}/meetings/{meetingId}/actions-to-radar` avec la liste des
   textes d'actions retenus et, optionnellement, un `subjectId`.
4. Le backend :
   - résout le **sujet cible** : `subjectId` de la requête s'il est fourni, sinon `meeting.subjectId`
     (le sujet auquel la réunion est déjà rattachée à la capture — F-128) ;
   - crée **une seule preuve** de type `TEAMS_MEETING` pour la réunion (`sourceRef` stable
     `teams-meeting:{meetingId}` → idempotente : re-pousser ne duplique pas la preuve) ;
   - pour chaque action retenue, crée un engagement `ME_TO_OTHER` sur le sujet, **description = le texte
     de l'action** (borné, traité comme **donnée**), **preuve = la réunion**, marqué **souverain** et
     **journalisé** (`ADD_COMMITMENT`) donc **annulable** depuis la chronologie du sujet ;
   - **idempotence** : une action déjà poussée (même `extraction_key` `meeting:{meetingId}:{clé-texte}`)
     est **ignorée** (statut `SKIPPED`), jamais dupliquée.
5. Réponse : bilan `{ subjectId, subjectName, added, actions[{description,status}], evidenceId,
   needsSubject:false, note }`. Le front affiche « N action(s) ajoutée(s) à « À faire par moi » sur
   « <sujet> » ».

### Réutilisation (ne rien réinventer)

- Logique d'engagement : `RadarToolExecutor.recordSovereignEngagement(...)` — **cœur extrait** de l'actuel
  `RadarToolExecutor.addEngagement` (F-104), qui appelle `RadarRegistry.recordCommitment` + marque
  souverain + `RadarCorrectionJournal.record(ADD_COMMITMENT)`. `addEngagement` (outil « Donner la
  nouvelle ») **délègue désormais** à ce cœur — aucune duplication, aucun changement de comportement de
  F-104.
- Preuve réunion : `RadarRegistry.recordEvidence` avec `RadarEvidenceSource.TEAMS_MEETING` (source déjà
  existante).
- Annulabilité : le journal `ADD_COMMITMENT` rend chaque engagement annulable par le geste Radar
  existant (`POST /radar/hosts/{hostId}/corrections/{correctionId}/undo`).

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Réunion inexistante ou d'un autre `user_id`/`host_id` | Introuvable, rien écrit | 404 |
| Sujet désigné inexistant / fusionné mort / d'un autre poste | Introuvable, rien écrit | 404 |
| Aucun sujet cible (ni `subjectId`, ni `meeting.subjectId`) | Bilan `needsSubject:true`, rien écrit, note « désignez un sujet » — le front invite à choisir | 200 |
| Liste d'actions vide / uniquement des blancs | Bilan `added:0`, note « aucune action à pousser », rien écrit | 200 |
| Sans droit Teams | Refus | 403 |
| Poste hors Vigie | Conflit | 409 |
| Action déjà poussée (même clé) | Ignorée (`SKIPPED`), pas de doublon | 200 |

---

## Critères d'acceptation

- [ ] Les actions retenues d'une réunion créent des engagements `ME_TO_OTHER` sur le sujet désigné.
- [ ] Chaque engagement porte pour **preuve la réunion** (`RadarEvidence` `TEAMS_MEETING`,
      `sourceRef=teams-meeting:{meetingId}`) et est **souverain**.
- [ ] Chaque engagement est **annulable** : un `RadarCorrection` `ADD_COMMITMENT` est écrit et l'undo
      Radar existant le défait.
- [ ] Le sujet cible = `subjectId` de la requête si fourni, sinon `meeting.subjectId`.
- [ ] Sans sujet cible : `needsSubject:true`, **aucune écriture**.
- [ ] Liste vide/blancs : `added:0`, **aucune écriture**.
- [ ] Idempotence : re-pousser les mêmes actions ne crée pas de doublon (`SKIPPED`), une seule preuve.
- [ ] Isolation : un autre utilisateur ne peut pousser sur une réunion/un sujet qui ne sont pas les siens
      → **404** ; tout accès filtré `user_id` + `host_id`.
- [ ] Le contenu des actions est traité comme **donnée** (borné à `MAX_DESCRIPTION_LENGTH`), jamais comme
      consigne ; rien n'est poussé sans matière réelle.
- [ ] Non-régression : F-104 « Donner la nouvelle » (engagements/annulation) et SF-128-05 (exploitation)
      inchangés.
- [ ] Frontend : depuis l'écran de détail réunion, section « Suivi dans le Radar » sous les actions
      (cases + sujet cible + bouton), retour clair du nombre ajouté.

---

## Plan de test minimal

### Backend — unitaires (`MeetingActionsToRadarServiceTest`, registry réel via base H2 de test)

- Pousse 2 actions sur `meeting.subjectId` → 2 engagements `ME_TO_OTHER`, direction/description/souverain
  OK, 1 preuve `TEAMS_MEETING`, statut `ADDED`.
- `subjectId` de la requête **prime** sur `meeting.subjectId`.
- Sans sujet (ni requête ni réunion) → `needsSubject:true`, 0 engagement, 0 preuve.
- Liste vide → `added:0`, rien écrit.
- Idempotence : deux pushes des mêmes actions → 2 engagements au total (2ᵉ push `SKIPPED`), 1 preuve.
- Annulabilité : le `correctionId` renvoyé, une fois passé à l'undo Radar, retire l'engagement (statut
  `ABANDONED`/défait).
- Sujet cross-poste → 404 (`RadarNotFoundException` traduit).

### Backend — non-régression (`RadarToolExecutorTest` existant)

- `addEngagement` (F-104) via `recordSovereignEngagement` : engagement souverain + journal inchangés.

### Backend — intégration (`TeamsMeetingActionsApiIntegrationTest`)

- `POST …/actions-to-radar` nominal → 200, `added=N`, engagements visibles dans le registre.
- **Isolation** : Bob pousse sur une réunion d'Alice → **404**.
- Gardes : sans droit Teams → 403 ; hors Vigie → 409.
- `needsSubject` (réunion sans sujet, pas de `subjectId`) → 200 `needsSubject:true`, rien écrit.

### Frontend

- `TeamsMeetingService.pushActionsToRadar(...)` appelle le bon endpoint avec le bon corps.
- `MeetingDetailPageComponent` : rendu de la section quand `insights.actions` non vide ; message de
  retour après succès.

---

## Tables / endpoints / composants impactés

- **Tables** : **aucune** nouvelle. Réutilise `radar_commitments`, `radar_evidence`,
  `radar_evidence_links`, `radar_corrections` (F-99/F-104) et `meetings` (F-128, `subjectId` lu). **Aucune
  migration.**
- **Endpoints** : `POST /vigie/hosts/{hostId}/meetings/{meetingId}/actions-to-radar` (nouveau).
- **Backend** : `MeetingActionsToRadarService` (nouveau), `TeamsMeetingRadarController` (nouveau), DTO
  `MeetingActionsToRadar` (requête + bilan). `RadarToolExecutor` : extraction du cœur
  `recordSovereignEngagement` (refactor sans changement de comportement).
- **Frontend** : `MeetingDetailPageComponent` (section « Suivi dans le Radar »), `TeamsMeetingService`
  (`pushActionsToRadar`), modèles `teams-meeting.models.ts` (requête + bilan), `radar.service.ts`
  (`subjects()` pour le sélecteur de sujet).

---

## Valeurs initiales

| Champ | Valeur initiale | Règle |
|-------|----------------|-------|
| engagement `direction` | `ME_TO_OTHER` | Toujours — « À faire par moi » |
| engagement `status` | `OPEN` | Imposé par `RadarRegistry.recordCommitment` |
| engagement `sovereign` | `true` | Poussé par un geste du PO (comme F-104) |
| engagement `certainty` | `CERTAIN` | Le PO valide (comme `addEngagement`) |
| engagement `extraction_key` | `meeting:{meetingId}:{clé-normalisée-du-texte}` | Idempotence anti-doublon |
| preuve `source` / `sourceRef` | `TEAMS_MEETING` / `teams-meeting:{meetingId}` | Idempotente par `sourceRef` |
| preuve `occurredAt` | `meeting.startedAt` sinon `now` | Instant de la réunion |

## Contraintes de validation

| Champ | Contrainte | Source |
|-------|-----------|--------|
| description d'action | ≤ `RadarCommitment.MAX_DESCRIPTION_LENGTH` (500), non blanc | tranchée (réutilisée) |
| nombre d'actions par push | ≤ 30 | tranchée (borne de garde-fou) |
| clé d'extraction | ≤ `RadarCommitment.MAX_EXTRACTION_KEY_LENGTH` (128) | tranchée (réutilisée) |

Aucune contrainte structurante nouvelle non tranchée → aucun ajout à `docs/OPEN_QUESTIONS.md`.

---

## Préoccupations transversales

- **Contexte tenant** : le pont lit et écrit **uniquement** sous `(user_id, host_id)` via `RadarScope`
  (résolu par `RadarScopeResolver.requireInVigie`) et `MeetingRepository.findByIdAndUserIdAndHostId`.
  Composants qui résolvent le tenant et sont vérifiés : le nouveau controller (même patron que
  `TeamsMeetingCardController`/`TeamsMeetingExploitationController`), `MeetingActionsToRadarService`
  (require meeting + `registry.requireLiveSubject`). **Aucun autre composant modifié.**
- **Plans / limites** : aucun nouveau gate ; **aucun appel modèle** (pas de quota consommé — c'est une
  écriture pure, le PO a déjà validé les textes). `RadarToolExecutor` refactoré : seuls appelants
  `RadarNewsService` (F-104) et le nouveau service — vérifiés.
- **Navigation / routing** : **aucune** nouvelle route front (la section vit dans l'écran de détail
  réunion existant).
- **Auth / Principal** : inchangé.

---

## Hors scope (explicite)

- Le **CRA** (F-124) : les actions ne partent pas vers un compte-rendu d'activité ici.
- **Auto-rattachement heuristique** par similarité de texte : le sujet vient de la réunion ou du choix du
  PO — pas de devinette silencieuse (« le PO reste maître »).
- **Création de sujet** depuis ce pont : si aucun sujet ne convient, le PO crée/ouvre le sujet via les
  gestes Radar existants (F-104), puis pousse.
- Toute **mise à jour du runner** : aucune (pont 100 % backend + frontend).
- Extraction/analyse de la réunion : déjà faite en SF-128-05 (on consomme `insights.actions`).
