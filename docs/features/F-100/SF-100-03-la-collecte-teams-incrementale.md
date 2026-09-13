# Mini-spec — F-100 / SF-100-03 — La collecte Teams incrémentale

> Base : `docs/features/F-99/CADRAGE-le-radar.md` §5 et §12 bis (SF-100-03 ; corrections du PO : **rien à
> déclarer à l'avance, le Radar découvre seul ; adresses Microsoft identiques pour tous les clients,
> reconnues par motif**) ; `docs/features/F-108/CADRAGE-F-108-agir-dans-microsoft-365.md` §4.8 et §5.3
> (**la synchro navigue et observe cadres et workers, sans écrire**) ; contrat d'entrée des lots :
> `docs/features/F-101/SF-101-01-la-file-d-analyse.md`. Cadrages validés : cette mini-spec les applique.

## Identifiant

`F-100 / SF-100-03`

## Feature parente

`F-100` — Le Radar : la synchro du soir

## Statut

`in-progress`

## Date de création

2026-09-13

## Branche Git

`feat/SF-100-03-collecte-teams`

---

## Objectif

Sur la machine, **découvrir seul** les conversations Teams actives depuis la dernière synchro, lire
**seulement le nouveau** de chacune (curseur par fil), limiter les canaux d'équipe aux fils où
l'utilisateur a écrit, répondu ou été mentionné, écarter les fils ignorés, lire les **transcriptions des
réunions** en naviguant sans rien écrire, et faire remonter le tout **par lots idempotents** dans la file
d'analyse de F-101.

---

## Contexte

SF-100-02 lance la synchro et reçoit battement et fin ; le collecteur du runner y était « non
disponible ». Cette sous-feature le branche. Elle reste **écrite sur les hypothèses actuelles de
l'adaptateur** (échantillons fabriqués, `TeamsUrls`) : le relevé réel (SF-100-00, à faire par le PO)
confirmera les chemins ; tout écart se corrige **dans l'adaptateur unique**. Les manques sont **nommés**
dans la couverture, jamais tus — c'est ce qui rend ces hypothèses sûres à livrer.

---

## Comportement attendu

### Côté gateway

1. `teams_radar_collect` reçoit en plus : `cursors` (au plus 2 000, les plus récents :
   `{ref, kind, at}`), `ignored` (fils ignorés) et `read_channels` (canaux à lire en entier) — tirés des
   tables du poste.
2. `POST /api/runner/radar/syncs/{syncId}/batches` (jeton runner, périmètre du jeton) :
   `{ "batch": { …contrat SF-101-01… }, "cursors": [ {"ref","kind","at"} ] }`
   - synchro inconnue / d'un autre poste → 404 ; synchro close → 409 `{status}` ;
   - compte sans droit Teams → 403 (rien n'entre) ;
   - `batch` présent → `RadarAnalysisIntake.submit(scope, syncId, batch)` (idempotent par `batchKey`) ;
     lot hors contrat → 400, **les curseurs ne bougent pas** ;
   - puis les curseurs **avancent** (jamais ne reculent) : upsert `radar_sync_cursors` par
     `(user_id, host_id, source, conversation_ref)`, `cursor_at = max(existant, reçu)` ;
   - le battement est mis à jour ; réponse `{status: RUNNING, batchId, batchStatus, duplicate}`.

### Côté runner — `TeamsRadarCollector`

3. **Session** : liaison au navigateur ; échec (non détecté, onglet absent, **session Microsoft
   expirée**) → fin `FAILED` avec `failure.code` (`BROWSER_NOT_DETECTED`, `TEAMS_NOT_OPEN`,
   `SESSION_EXPIRED`), la phrase et **le geste** (« rouvrez Teams dans Chrome et reconnectez-vous ») —
   jamais un résumé vide présenté comme calme.
4. **Observation élargie** (F-108 §4.8) : l'auto-attach est demandé ; chaque cadre ou worker **sur un
   domaine Microsoft** voit son réseau écouté **sur sa session**, et ses corps récupérés sur la même
   session ; hors liste, rien. Aucune commande ajoutée à la liste blanche.
5. **Reconnaissance par motif** dans l'adaptateur unique : un hôte `*.sharepoint.com` (dont
   `*-my.sharepoint.com`) dont le chemin porte `/transcripts` est une **transcription**
   (`MEETING_TRANSCRIPT`) ; sans configuration par client, sans nom de tenant.
6. **Découverte** : récolte sur place (coup de coude F-88) de la liste que Teams charge ; les
   conversations sont prises **par activité décroissante** et retenues si leur dernière activité est
   postérieure à leur plancher (curseur du fil, sinon `window_from`). Si **toutes** les conversations
   listées sont plus récentes que le plancher, la liste a peut-être été coupée : couverture
   `discovery.complete = false`.
7. **Écartés** : les fils `ignored` (comptés). **Canaux d'équipe** (`CHANNEL`) : lus en entier s'ils sont
   dans `read_channels` ; sinon **seuls les fils** (message racine et réponses) où l'utilisateur a
   **écrit, répondu ou été mentionné** (flux d'activité, messages observés) ; un canal actif sans tel fil
   est **compté** « canal actif non lu » avec son libellé.
8. **Lecture** : au plus 150 conversations par synchro (le reste est **reporté** et compté) ; pour chacune,
   battement, puis lecture du fil sur la fenêtre `[plancher, maintenant]` (F-88 : défilement, plafond de
   500 messages, manques nommés, **fil de l'utilisateur remis**). Seuls les messages **strictement
   postérieurs** au plancher, non supprimés et non vides entrent.
9. **Lots** : les messages d'un fil sont découpés en échanges de 200 messages et ≤ 350 000 caractères
   (message tronqué à 8 000) ; un lot par échange ; `batchKey` = `teams:` + empreinte SHA-256 (fil,
   premier et dernier identifiant) — **rejouer une collecte reprise ne duplique rien**. Chaque message :
   `sourceRef = <fil>/<message>`, `occurredAt`, `authorKey` (adresse, sinon identifiant), `authorName`,
   `fromMe`, `text`, `deepLink`. Le curseur du fil (message le plus récent du lot) part **avec** le lot et
   n'avance qu'une fois le lot accepté.
10. **Réunions** (§5.3 de F-108, **aucune écriture**) : l'onglet est **navigué** vers le calendrier de
    Teams (gardes F-108 : domaine vérifié avant émission, jamais une page d'identification) puis remis
    à l'adresse d'origine ; pour chaque réunion **terminée** après son plancher, le fil de la réunion est
    affiché (geste F-88 `show`) le temps d'observer ce que Teams sert. Les répliques de transcription
    observées pendant ce passage deviennent un échange `TEAMS_MEETING` (`sourceRef = <réunion>/<instant>`,
    horodatage à la seconde, locuteur). Sinon la réunion est comptée : **`NO_TRANSCRIPT`** (non servie ou
    non annoncée) ou **`DENIED`** (refus 401/403). **Jamais un lien de participation n'est ouvert.**
11. **Arrêt** : dès qu'un battement ou un lot répond « synchro close », la collecte s'arrête sans rien
    envoyer de plus.
12. **Couverture** (forme posée ici, lue et affichée par SF-100-04) : fenêtre, compteurs par nature
    (conversations découvertes / lues / partielles / échouées / ignorées / reportées ; canaux actifs non
    lus ; réunions vues / transcrites / sans transcription / refusées ; messages ; lots), liste bornée des
    fils **non entièrement lus** (40 au plus, libellé ≤ 80 caractères) ; issue `SUCCEEDED` si tout a été
    lu, `PARTIAL` sinon, `FAILED` si la session a manqué ou qu'aucun lot n'a pu remonter.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Jeton absent / refusé (lots) | 401 générique | 401 |
| Synchro d'un autre poste | 404 | 404 |
| Synchro close | 409 `{status}` ; le runner s'arrête | 409 |
| Compte sans droit Teams | 403, rien n'entre | 403 |
| Lot hors contrat | 400, curseurs inchangés | 400 |
| Même lot rejoué | 200 `duplicate=true`, rien de réécrit | 200 |
| Session Microsoft expirée | fin `FAILED` `SESSION_EXPIRED` avec le geste | — |
| Fil qui ne s'ouvre pas / lot refusé | fil compté `FAILED`, curseur inchangé, la collecte continue | — |
| Navigation refusée (hors domaine, identification) | réunions comptées non lues avec la raison | — |
| Aucune réunion servie par le calendrier | couverture `meetings.calendarServed = false` (route à confirmer par le relevé) | — |

---

## Critères d'acceptation

- [ ] Migration `088-radar-sync-cursors.xml` : `radar_sync_cursors` (unique `user_id, host_id, source,
      conversation_ref`) et `radar_thread_rules` (unique `user_id, host_id, conversation_ref, rule`) ;
      PostgreSQL et H2 ; rollback ; purgées avec le Radar.
- [ ] `teams_radar_collect` porte curseurs, fils ignorés et canaux à lire, du **poste** seulement.
- [ ] `POST …/batches` : lot déposé dans la file (idempotent), curseurs avancés **après** acceptation et
      jamais reculés ; synchro close 409 ; sans droit 403 ; hors contrat 400 sans curseur bougé.
- [ ] Runner : conversations actives découvertes seules ; plancher = curseur, sinon `window_from` ; seul le
      nouveau remonte ; fil de l'utilisateur remis.
- [ ] Canaux : fils de l'utilisateur seuls (écrit, répondu, mentionné) ; canal actif sans tel fil compté
      non lu ; `read_channels` lu en entier ; fils `ignored` écartés.
- [ ] Lots conformes au contrat F-101 ; `batchKey` stable d'une collecte rejouée à l'autre.
- [ ] `*.sharepoint.com/…/transcripts` classé transcription ; cadres et workers Microsoft écoutés sur leur
      session, jamais un cadre hors liste.
- [ ] Réunions : navigation gardée et vue remise ; transcription observée → échange `TEAMS_MEETING` ;
      sinon `NO_TRANSCRIPT` / `DENIED` ; aucun lien de participation ouvert ; aucune écriture.
- [ ] Session expirée → `FAILED` avec le geste ; synchro close → arrêt.
- [ ] **Isolation** : le jeton du poste B ne dépose aucun lot ni curseur sur une synchro du poste A ; les
      curseurs et règles d'un poste ne partent jamais vers un autre.

---

## Périmètre

### Hors scope (explicite)

- Les routes *ignorer ce fil* / *lire ce canal*, l'annulation, la lecture de la couverture et la
  progression affichable (SF-100-04) — la table des règles est créée ici parce que la collecte la lit.
- Le dossier de dépôt (SF-100-05).
- Télécharger un enregistrement : **la vidéo n'est pas nécessaire au Radar** (cadrage §12 bis).
- Corriger les chemins d'après le relevé réel (après SF-100-00, par le PO).

---

## Valeurs initiales

| Champ | Valeur initiale | Règle |
|-------|----------------|-------|
| conversations lues par synchro | 150 | le reste est reporté |
| messages par échange | 200 | contrat F-101 |
| caractères par lot | 350 000 | < 400 000 du contrat |
| caractères par message | 8 000 | contrat F-101 |
| curseurs transmis | 2 000 | les plus récents |
| fils listés dans la couverture | 40 | les non entièrement lus |

---

## Contraintes de validation

| Champ | Obligatoire | Règle |
|-------|-------------|-------|
| `cursors[].ref` | Oui | ≤ 512 caractères |
| `cursors[].kind` | Non | `CONVERSATION`, `CHANNEL`, `MEETING` |
| `cursors[].at` | Oui | ISO-8601, pas dans le futur de plus de 1 h |
| `cursors` | — | 200 au plus par requête |
| `batch` | Non | contrat SF-101-01 |

---

## Technique

### Endpoint(s)

| Méthode | URL | Auth | Rôle minimum |
|---------|-----|------|-------------|
| POST | `/api/runner/radar/syncs/{syncId}/batches` | jeton runner | droit Teams du compte du jeton |

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| `radar_sync_cursors` | INSERT / UPDATE / SELECT / DELETE | nouvelle (088) |
| `radar_thread_rules` | SELECT / DELETE | nouvelle (088) ; écriture en SF-100-04 |
| `radar_analysis_batches` | INSERT | par `RadarAnalysisIntake` (F-101) |
| `radar_syncs` | UPDATE | battement |

### Migration Liquibase

- [x] Oui — `088-radar-sync-cursors.xml`

### Composants

| Composant | Rôle |
|-----------|------|
| `radar/sync/RadarSyncCursor(+Repository)`, `RadarThreadRule(+Repository)` | curseurs et règles d'un poste |
| `radar/sync/RadarSyncSessionService.batch` + `RunnerRadarSyncController` | dépôt des lots, avancée des curseurs |
| `radar/sync/RadarSyncLauncher` | curseurs, fils ignorés, canaux à lire dans l'entrée |
| `radar/RadarPurgeService` | purge des curseurs et règles |
| `runner/RunnerSecurityConfig` | route `…/batches` déclarée une par une |
| runner `teams/TeamsRadarCollector` | découverte, canaux, lecture, lots, réunions, couverture |
| runner `teams/RadarExchanges` | découpage en échanges et lots, `batchKey` |
| runner `teams/TeamsUrls` | motif `*.sharepoint.com` + `/transcripts` |
| runner `teams/NetworkObserver` | réseau des cadres/workers Microsoft écouté sur leur session |
| runner `teams/TeamsLedger` | répliques observées depuis une marque |
| runner `teams/TeamsTools` | collecteur Teams branché sur la synchro |

### Composants Angular

- Aucun (F-102).

---

## Plan de test

### Tests unitaires

- [ ] runner `RadarExchangesTest` — découpage 200 messages / 350 000 caractères, troncature 8 000,
      `batchKey` stable et ≤ 128, champs du contrat.
- [ ] runner `TeamsRadarCollectorTest` (Teams de papier) — découverte par activité et plancher ; curseur
      respecté (seul le nouveau) ; fil ignoré écarté ; canal : fil mentionné lu, canal sans fil de
      l'utilisateur compté non lu, `read_channels` lu ; lot refusé → fil `FAILED` et curseur non envoyé ;
      synchro close → arrêt ; session expirée → `FAILED` `SESSION_EXPIRED` ; réunion avec transcription →
      échange `TEAMS_MEETING` ; sans → `NO_TRANSCRIPT` ; refus → `DENIED` ; aucune navigation hors domaine ;
      vue remise ; couverture bornée.
- [ ] runner `TeamsUrlsTest` — motif SharePoint / OneDrive pour les transcriptions, tenant quelconque.
- [ ] runner `NetworkObserverTest` — cadre Microsoft : `Network.enable` et corps sur sa session ; cadre hors
      liste : rien.

### Tests d'intégration

- [ ] `RunnerRadarBatchApiIntegrationTest` — dépôt (file F-101), doublon, curseurs avancés puis jamais
      reculés, hors contrat 400 sans curseur, synchro close 409, sans droit 403, jeton 401.
- [ ] `RadarScheduleApiIntegrationTest` — l'entrée de `teams_radar_collect` porte curseurs, fils ignorés,
      canaux à lire du poste.

### Isolation utilisateur

- [x] Applicable — jeton du poste B → 404 sur une synchro du poste A (ni lot ni curseur) ; curseurs et
      règles du poste B absents de l'entrée envoyée au poste A ; purge du poste A : curseurs et règles du B
      intacts.

---

## Dépendances

### Subfeatures bloquantes

- SF-100-02 — prérequis de branche. F-101 / SF-101-01 (file d'analyse, contrat) — `done`. F-108 / SF-108-01
  (gestes gardés, auto-attach) — `done`. F-88 (récolte, gestes) — `done`.

### Questions ouvertes impactées

- Aucune.

---

## Préoccupations transversales

- **Contexte tenant : oui.** Composants impactés : `RunnerRadarSyncController` / `RadarSyncSessionService`
  (périmètre du jeton), `RadarSyncLauncher` (curseurs et règles lus à `user_id` + `host_id`),
  `RadarAnalysisIntake` (inchangé, déjà isolé), `RadarPurgeService`.
- **Plans / limites : oui (réemploi).** `TeamsAccessService.hasAccess(userId)` sur le dépôt de lots (droit
  Teams provisoire du Radar). La réserve de synchro (F-107 / SF-101-05) n'est pas touchée.
- **Sécurité du volet Teams : oui.** Composants : `NetworkObserver` (écoute par session, filtre de domaine
  inchangé), `PageActions` (navigation gardée, inchangé), `PageGestures` (`show`, inchangé), `TeamsUrls`
  (motif). `CdpCommands` **non modifié**.
- **Auth / Principal : non** (chaîne runner : une route de plus, déclarée une par une). **Navigation : non.**

---

## Notes et décisions

- **Canaux par le flux d'activité et les messages observés** plutôt qu'en ouvrant chaque canal : un canal
  d'équipe est souvent très volumineux (cadrage) ; les fils de l'utilisateur sont exactement ceux que
  Teams signale. Réversible.
- **Réunions par le fil de la réunion** plutôt que par le lien de participation : ouvrir un lien de
  participation pourrait faire rejoindre une réunion — ce n'est pas une lecture. Réversible.
- **Répliques attribuées à la réunion affichée pendant l'observation** : une transcription servie par
  SharePoint ne porte pas l'identifiant de la réunion dans son adresse. À confirmer par le relevé réel.
- **Couverture honnête plutôt que chemin deviné** : si le calendrier ne sert rien sur la route supposée,
  la couverture le dit ; la route sera corrigée dans l'adaptateur après le relevé.
