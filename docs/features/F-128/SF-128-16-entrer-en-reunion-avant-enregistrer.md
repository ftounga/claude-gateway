# Mini-spec — [F-128 / SF-128-16] Entrer réellement en réunion (in-call) AVANT de démarrer l'enregistrement

## Identifiant

`F-128 / SF-128-16`

## Feature parente

`F-128` — Capturer et exploiter une réunion Teams (écran + audio → transcription → exploitation)

## Statut

`in-progress`

## Date de création

2026-09-19

## Branche Git

`feat/SF-128-16-entrer-en-reunion-avant-enregistrer`

---

## Objectif

> En une phrase : que fait cette subfeature ?

Séparer **« Rejoindre »** de **« Démarrer l'enregistrement »** : le runner entre réellement en réunion
(clic « Rejoindre maintenant » best-effort puis détection du **vrai** état in-call, remonté à l'UI), et
la capture n'est lancée **qu'une fois in-call confirmé par l'utilisateur** — après la dernière
navigation Teams — pour que le capteur **survive** au lieu de mourir sur l'écran de pré-jonction.

---

## Le vrai problème (constat PO, confirmé F-132)

« Rejoindre & capturer » navigue vers l'URL de réunion → tombe sur l'écran de **pré-jonction** Teams
(aperçu + bouton « Rejoindre maintenant ») → et **démarre l'enregistrement immédiatement**. Le Chrome
managé étant **hors écran**, personne ne clique « Rejoindre maintenant ». On capture donc le pré-join
(silence), PUIS la transition pré-join→in-call **navigue** et **tue le capteur**
(`reinject→browser_unreachable`, `stop:no_active_capture`, `audio_bytes=0`). SF-128-14 démarrait sur un
**faux calme** (le pré-join est stable) → trop tôt.

---

## Le correctif (conception validée PO — flux en DEUX TEMPS contrôlé par l'utilisateur)

> **CHANGEMENT DE CONCEPTION acté le 2026-09-19** (remplace l'auto-détection / auto-start initialement
> envisagée) : l'utilisateur contrôle les deux temps. On réutilise les trois outils runner **déjà
> séparés** (`teams_meeting_join`, `teams_meeting_capture_start`, `teams_meeting_capture_stop`).

1. **« Rejoindre »** (`teams_meeting_join`) : le runner navigue vers l'URL, **clique « Rejoindre
   maintenant »** (best-effort, CDP), puis **DÉTECTE le vrai état in-call** (signal réel de réunion en
   cours) et le **remonte**. Le backend persiste la réunion en **`JOINED`** avec `inCall`. L'UI affiche
   « En réunion ✅ » quand c'est réellement in-call ; le bouton « Démarrer l'enregistrement » reste
   **désactivé** tant que l'in-call n'est pas confirmé.
2. **« Démarrer l'enregistrement »** (`teams_meeting_capture_start`) : action **séparée**, activée
   uniquement une fois in-call confirmé → lance la capture sur la page **déjà in-call et stable** → plus
   de navigation après → le capteur **survit**. C'est le cœur du fix. Transition `JOINED → RECORDING`.
3. **« Arrêter »** (`teams_meeting_capture_stop`) : inchangé (`RECORDING`/`PAUSED`/`JOINED → STOPPED`).

Le filet SF-128-12/13 (reattach/reinject) et le portillon de stabilité SF-128-14 restent **armés comme
filet** dans `capture_start`, mais la logique principale devient « in-call réel confirmé, puis record ».

### Comment on clique « Rejoindre maintenant » (best-effort, drapeau fragilité)

Script JS injecté par CDP (`MeetingPresence.JOIN_NOW_SCRIPT`, via `PageActions.runScript`) qui cherche
le bouton de jonction par **plusieurs sélecteurs et libellés FR/EN** puis le clique :
- sélecteurs : `[data-tid="prejoin-join-button"]`, `#prejoin-join-button`, boutons dont
  `aria-label`/texte contient « Rejoindre maintenant », « Join now », « Rejoindre », « Join »,
  « Rejoindre l'appel », « Demander à rejoindre », « Ask to join ».
- **DRAPEAU** : sélecteur/label « v2 » **fragile** (dépend de la version Teams web) — `SELECTORS_FLAG`
  documenté, **à valider sur call réel**. Si déjà in-call (contrôle « Quitter » présent) ou pas de
  bouton → on **continue** (`already_in_call` / `absent`).

### Comment on détecte le VRAI in-call (signal retenu)

`MeetingPresence.IN_CALL_PROBE_SCRIPT` rend `{inCall, by}`. Signal **principal retenu = présence des
contrôles d'appel** (bouton « Quitter »/« Leave »/« Raccrocher »/hangup, barre de contrôles d'appel) —
c'est le signal **distinctif** de l'in-call (le pré-join, lui, n'a que « Rejoindre maintenant » et un
**aperçu de sa propre caméra**, donc « média actif » seul ne distingue pas). Un repli « média actif »
(élément média avec piste audio **live**) est tenté ensuite (`by:'media'`). Plafond de temps **borné**
(`InCallGate`, `CAP_MILLIS = 25s`, `POLL_MILLIS = 500ms`) : atteint sans in-call → best-effort
(`inCall=false`) + diag. Robuste de préférence, mais **à valider sur call réel**.

---

## Comportement attendu

### Cas nominal

1. L'utilisateur saisit l'URL + consentement, clique **« Rejoindre »**.
2. Runner : navigue → clique « Rejoindre maintenant » (best-effort) → attend l'in-call réel (borné) →
   rend `{joined:true, tabUrl, inCall:true}`. Diags F-132 `capture/join_click`, `capture/incall`.
3. Backend : persiste `state=JOINED`, `inCall=true`. Réponse → UI affiche « En réunion ✅ », bouton
   « Démarrer l'enregistrement » **activé**.
4. L'utilisateur clique **« Démarrer l'enregistrement »** → `teams_meeting_capture_start` sur la page
   in-call stable → capteur unique qui **survit** (`getDisplayMedia preferCurrentTab` + micro + mix
   WebAudio + `MediaRecorder`, auto-accept SF-122-05, `userGesture`). Transition `JOINED → RECORDING`.
   Diag `capture/start` avec `in_call:true`.
5. **« Arrêter »** → `STOPPED`, remontée audio + images (inchangé).

### Cas d'erreur

| Situation | Comportement attendu | Code |
|-----------|---------------------|------|
| URL absente / invalide (join) | Échec nommé, aucune ligne persistée | `invalid_input` / 400 |
| Chrome managé injoignable (join) | Échec nommé, aucune ligne fantôme | `browser_unreachable` → `MANAGED_CHROME_UNREACHABLE` / 502 |
| In-call jamais confirmé (plafond atteint) | Réunion persistée `JOINED` avec `inCall=false` ; bouton « Démarrer » **désactivé** ; message « réunion non confirmée » ; diag `capture/incall result=cap` | 201 |
| « Démarrer l'enregistrement » sur une réunion **non `JOINED`** | Refus d'état | `MeetingStateException` / 409 |
| `capture_start` échoue côté runner | Reste `JOINED`, erreur nommée remontée | `MANAGED_CHROME_UNREACHABLE` / 502 |
| Clic « Rejoindre maintenant » : bouton absent / déjà in-call | On continue (best-effort), diag `capture/join_click result=absent|already_in_call` | — |
| Accès réunion d'un autre `user_id`/`host_id` | 404 (isolation) | 404 |

---

## Critères d'acceptation

- [ ] **Détection in-call (pure)** : signal réel présent → `IN_CALL` (start possible) ; pré-join (pas de
      signal) → `WAITING` (pas de start) ; plafond atteint → `CAP_REACHED` (best-effort) + diag.
- [ ] **Clic « Rejoindre maintenant »** : bouton présent → cliqué (`clicked`) ; absent → `absent` ;
      déjà in-call → `already_in_call` ; jamais fatal (best-effort), diag `capture/join_click`.
- [ ] `teams_meeting_join` rend `inCall` (bool) dans son JSON, après clic + détection ; diag
      `capture/incall` (`in_call`/`cap`, `by`).
- [ ] Un `capture_start` **après** in-call réel (plus de navigation) démarre le capteur **une fois** et
      ne déclenche **pas** de `reinject` en boucle (non-régression SF-128-14) ; diag `capture/start`
      porte `in_call`.
- [ ] Backend : `create()` = **join seul** → `state=JOINED` (plus de `startCapture` automatique) ;
      endpoint séparé `POST …/{meetingId}/capture-start` → `JOINED → RECORDING` ; isolation `user_id`
      + `host_id` sur les deux.
- [ ] Frontend : bouton unique remplacé par **« Rejoindre »** → état « En réunion ✅ » (via `inCall`)
      → **« Démarrer l'enregistrement »** (désactivé si `!inCall`) → **« Arrêter »**.
- [ ] Isolation vérifiée en test : `capture-start` et l'accès réunion filtrent `user_id`+`host_id`.
- [ ] Non-régression : SF-128-09/12/13/14, SF-122-05→08, boucle Vigie, `TeamsAdapterV1`, heartbeat,
      F-132 restent verts.

---

## Périmètre

### Hors scope (explicite)

- Le **poll live** de l'état in-call côté frontend (via diag) : on retient la confirmation renvoyée par
  la réponse `join` (`inCall`), suffisante pour activer le bouton ; un rafraîchissement en continu est
  une amélioration ultérieure.
- La **re-vérification** in-call d'une réunion `JOINED` non confirmée (`inCall=false`) sans re-naviguer :
  hors scope ici (le remède immédiat = abandonner via « Arrêter » puis re-« Rejoindre »).
- Le comportement navigateur réel (séquence exacte des navigations Teams, `getDisplayMedia`, libellés
  DOM réels du bouton de jonction et des contrôles d'appel) reste **« À VALIDER SUR CALL RÉEL »** (non
  CI) : on teste la **logique pure** (détection in-call, décision de clic) + le câblage CDP contre un faux.
- On **ne réécrit pas** `CaptureReinjector` / `MeetingTabCapture` / `CaptureStabilityGate` /
  `TeamsSession.link()` : ils restent le filet, seulement précédés de l'entrée réelle en réunion.
- Aucune capacité IA nouvelle (Gateway-First / Provider-First inchangés).

---

## Valeurs initiales & contraintes de validation

| Champ | Valeur | Règle |
|-------|--------|-------|
| `InCallGate.DEFAULT_CAP_MILLIS` | `25000` | Plafond borné de l'attente d'in-call ; garantit la terminaison (`totalElapsed` croît de `POLL_MILLIS`). |
| `InCallGate.DEFAULT_POLL_MILLIS` | `500` | Granularité de scrutation (via le `Sleeper` existant, no-op en test → déterministe). |
| `in_call` (colonne) | `boolean not null default false` | Additif, compatible ; réunions existantes → `false`. |
| `MeetingState.JOINED` | nouvel état | « rejointe, pas encore en enregistrement » ; non `isLive()`. |

- `CAP_MILLIS`, `POLL_MILLIS` strictement positifs (validés à la construction).
- Détection **pure** (pas d'horloge murale) : avance au rythme des créneaux.

---

## Technique

### Fichiers impactés

**Runner**
| Fichier | Opération | Notes |
|---------|-----------|-------|
| `InCallGate.java` | **NOUVEAU** | logique pure de détection in-call (signal → IN_CALL, plafond → CAP_REACHED) + driver `awaitInCall(Sleeper, Signal)`. |
| `MeetingPresence.java` | **NOUVEAU** | scripts JS purs (`JOIN_NOW_SCRIPT`, `IN_CALL_PROBE_SCRIPT`) + sélecteurs FR/EN + `SELECTORS_FLAG` + parseurs purs. |
| `TeamsTools.java` | MODIF | `meetingJoin` : clic join + détection in-call + `inCall` dans le JSON + diags `capture/join_click`/`capture/incall` ; `meetingCaptureStart` : ajoute `in_call` au diag `capture/start`. |
| `InCallGateTest.java`, `MeetingPresenceTest.java` | **NOUVEAUX** | tests purs. |
| `TeamsMeetingJoinToolTest.java`, `TeamsMeetingCaptureReinjectTest.java` | MODIF | join remonte `inCall` ; non-régression start. |

**Backend**
| Fichier | Opération | Notes |
|---------|-----------|-------|
| `MeetingState.java` | MODIF | + `JOINED` (non `isLive()`). |
| `Meeting.java` | MODIF | + colonne `in_call`. |
| `dto/MeetingResponse.java` | MODIF | + `inCall`. |
| `TeamsMeetingService.java` | MODIF | `create` = join seul (`JOINED`, parse `inCall`) ; nouveau `startCapture(scope, meetingId)` public (`JOINED → RECORDING`). |
| `TeamsMeetingController.java` | MODIF | `POST …/{meetingId}/capture-start`. |
| `db/changelog/migrations/117-meetings-in-call.xml` | **NOUVEAU** | `in_call boolean not null default false`, réversible. |
| `TeamsMeetingServiceTest.java`, `TeamsMeetingApiIntegrationTest.java` | MODIF | nouveau flux 2 temps + isolation `capture-start`. |

**Frontend**
| Fichier | Opération | Notes |
|---------|-----------|-------|
| `core/models/teams-meeting.models.ts` | MODIF | + `'JOINED'`, + `inCall`. |
| `core/services/teams-meeting.service.ts` | MODIF | + `startCapture(hostId, meetingId)`. |
| `vigie/meeting-capture/join-and-capture-dialog.component.ts` | MODIF | libellé « Rejoindre ». |
| `vigie/meeting-capture/meeting-capture-panel.component.ts` | MODIF | section `JOINED` (« En réunion ✅ » + « Démarrer l'enregistrement » désactivé si `!inCall`). |
| `*.spec.ts` (service, panel, dialog) | MODIF | couverture du flux 2 temps. |

### Migration Liquibase

- [x] `117-meetings-in-call.xml` — `in_call boolean not null default false`, additif et **réversible**.

---

## Plan de test

### Tests unitaires (runner, sans navigateur)

- [ ] `InCallGateTest` — signal présent → `IN_CALL` ; absent → `WAITING` ; plafond → `CAP_REACHED` ;
      `awaitInCall` via `Sleeper` no-op déterministe ; durées non positives refusées.
- [ ] `MeetingPresenceTest` — `JOIN_NOW_SCRIPT`/`IN_CALL_PROBE_SCRIPT` contiennent les sélecteurs FR/EN
      attendus ; `SELECTORS_FLAG` présent ; parseurs purs (`clickReason`, `inCall`, `detectedBy`).

### Tests d'intégration (CDP simulé)

- [ ] `TeamsMeetingJoinToolTest` — join nominal remonte `inCall` (signal présent) ; pré-join → `inCall=false` ;
      clic join présent/absent ; injoignable → `browser_unreachable`.
- [ ] `TeamsMeetingCaptureReinjectTest` — non-régression : start sur page in-call/stable → une seule
      injection, pas de `reinject` en boucle ; `capture/start` porte `in_call`.

### Tests backend

- [ ] `TeamsMeetingServiceTest` — `create` = `JOINED` sans `capture_start` auto ; `startCapture` :
      `JOINED → RECORDING` ; refus si non `JOINED` ; aucune ligne fantôme si join échoue.
- [ ] `TeamsMeetingApiIntegrationTest` — `POST …/capture-start` (nominal + 409 mauvais état) ;
      **isolation** `user_id`+`host_id` (404 hors périmètre).

### Tests frontend

- [ ] `teams-meeting.service.spec.ts` — `startCapture` POST `…/{id}/capture-start`.
- [ ] `meeting-capture-panel.component.spec.ts` — réunion `JOINED` `inCall=true` → bouton « Démarrer »
      activé ; `inCall=false` → désactivé.
- [ ] `join-and-capture-dialog.component.spec.ts` — libellé/soumission « Rejoindre ».

### Isolation utilisateur

- [x] `capture-start` et l'accès réunion filtrent `user_id`+`host_id` (via `RadarScope` + `require`).
      Remontée média inchangée sous `workspace_id`+`meeting_id`.

---

## Préoccupations transversales

- **Auth / Principal** : non touché (même `CurrentUser` + garde `TeamsAccessService` + `RadarScopeResolver`).
- **Contexte tenant** : le nouvel endpoint `capture-start` résout le tenant **exactement** comme les
  endpoints réunion existants — `TeamsMeetingController.scope(hostId)` (`teamsAccess.requireAccess()` +
  `scopeResolver.requireInVigie(userId, hostId)`) puis `TeamsMeetingService.require(scope, meetingId)`
  (filtre `id`+`user_id`+`host_id`). Composants impactés vérifiés : `TeamsMeetingController` (2 endpoints
  join+capture-start), `TeamsMeetingService` (`create`, `startCapture`, `require`). Aucun autre chemin de
  résolution tenant ajouté.
- **Plans / limites** : non touché (aucun nouveau gate/quota).
- **Navigation / routing (frontend)** : **non touché** — aucune route Angular ni guard ajouté ; on
  reste sur `/vigie/:hostId` onglet « reunions » ; seul le contenu du panneau change (boutons).

---

## Drapeau

**« À VALIDER SUR CALL RÉEL »** — les libellés/sélecteurs DOM réels du bouton « Rejoindre maintenant »
et des contrôles d'appel Teams, le comportement de `getDisplayMedia` une fois in-call, et la séquence
réelle de navigations ne se vérifient qu'en réunion réelle (DEBUG côté PO). La CI n'éprouve que la
décision (in-call, clic) et le câblage CDP contre un faux. `MeetingPresence.SELECTORS_FLAG` marque la
fragilité v2.
