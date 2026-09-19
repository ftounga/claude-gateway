# Mini-spec — [F-128 / SF-128-14] Démarrer la capture seulement une fois la réunion stabilisée (in-call)

## Identifiant

`F-128 / SF-128-14`

## Feature parente

`F-128` — Capturer et exploiter une réunion Teams (écran + audio → transcription → exploitation)

## Statut

`in-progress`

## Date de création

2026-09-19

## Branche Git

`feat/SF-128-14-capture-page-stabilisee`

---

## Objectif

> En une phrase : que fait cette subfeature ?

Ne démarrer la capture d'onglet (`START_SCRIPT`) **qu'une fois la page de réunion stabilisée** —
plus aucune navigation Teams depuis N secondes, ou un plafond global atteint — pour que le capteur
injecté **survive** au lieu de mourir à chaque navigation puis de partir dans une course de
ré-injection perdante (`reattach:ok` → `reinject:browser_unreachable` en boucle → `no_active_capture`,
`audio_bytes=0`, `image_count=0`, observé prod CAGIP test 7).

---

## Comportement attendu

### Cas nominal

1. `teams_meeting_join` navigue l'onglet vers la réunion (inchangé).
2. `teams_meeting_capture_start` : **avant** d'injecter `START_SCRIPT`, on **attend la stabilité** de
   la page. On active le domaine `Page` (best-effort) et on s'abonne à ses événements de cycle de vie
   (`Page.frameNavigated` du cadre principal, `Page.loadEventFired`). Un **minuteur de silence** est
   ré-armé à **chaque** navigation observée ; la page est déclarée **stable** dès qu'aucune navigation
   n'est survenue depuis `QUIET_MILLIS`. Diag F-132 `capture/awaiting_stable`.
3. Page stable → on injecte `START_SCRIPT` **une seule fois** (`getDisplayMedia preferCurrentTab` +
   micro + mix WebAudio + `MediaRecorder`, auto-accept SF-122-05, `userGesture:true`). Comme il n'y a
   plus de navigation après, le capteur **survit** jusqu'au `stop`. Diag F-132 `capture/start`
   (résultat, source micro, ré-armement, `stable`).
4. Le ré-armement SF-128-12/13 (`CaptureReinjector`) reste **armé comme filet** au cas où une
   navigation tardive surviendrait — mais on n'en dépend plus.
5. `teams_meeting_capture_stop` trouve un enregistrement actif → `bytes > 0` / `images > 0`.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| La page ne se stabilise jamais (navigations en continu) | **Plafond global** `CAP_MILLIS` atteint → on démarre **quand même en best-effort** + diag `capture/awaiting_stable result=cap` (le filet SF-128-12/13 reprend le relais). |
| `Page.enable` échoue (déjà activé ailleurs, ou refusé) | Best-effort : avalé, on s'abonne et on attend quand même (même politique que `CaptureReinjector.arm`). |
| Chrome managé injoignable au démarrage | Inchangé : `browser_unreachable` (le `!enabled` court-circuite avant, et une liaison morte lève `BrowserLinkException` comme aujourd'hui). |
| Aucune navigation pendant l'attente (page déjà stable / 2ᵉ capture) | La stabilité est atteinte au premier créneau de silence → on démarre **sans** boucle de ré-injection. |

---

## Critères d'acceptation

- [ ] La capture n'injecte `START_SCRIPT` **qu'après** avoir constaté la stabilité de la page : le
      minuteur de silence est **ré-armé à chaque navigation** et la stabilité n'est déclarée que
      lorsqu'il expire **sans nouvelle navigation** (`QUIET_MILLIS`).
- [ ] Un **plafond global** (`CAP_MILLIS`) borne l'attente : s'il est atteint sans stabilité, on
      démarre **quand même** (best-effort) et on le **diagnostique** (`capture/awaiting_stable
      result=cap`).
- [ ] Un `start` sur une page **stable sans navigation ultérieure** ne déclenche **aucune**
      ré-injection en boucle (le capteur survit ; `capture/reinject` absent).
- [ ] Les diagnostics F-132 sont émis : `capture/awaiting_stable` (nb de navigations observées,
      résultat `stable`/`cap`) et `capture/start` (résultat, `stable`).
- [ ] Non-régression : SF-128-09/12/13 (ré-armement, re-résolution de cible, recover), SF-122-05/06/07/08,
      la boucle Vigie, `TeamsAdapterV1`, le heartbeat et F-132 restent verts.

---

## Périmètre

### Hors scope (explicite)

- Le comportement navigateur réel (rechargement de la page de réunion, `getDisplayMedia`, séquence
  exacte des navigations Teams) reste **« À VALIDER SUR CALL RÉEL »** (non CI) — on teste la **logique
  pure** de détection de stabilité (ré-armement du minuteur, démarrage à expiration, plafond).
- **Aucune dépendance à un sélecteur DOM v2 « in-call »** : la stabilité de navigation est le signal
  principal ; on ne lit pas la barre d'appel Teams (fragile). Optionnel non retenu ici.
- On **ne réécrit pas** la logique de `CaptureReinjector` / `MeetingTabCapture` / `TeamsSession.link()` :
  le filet de ré-injection reste tel quel, seulement précédé de l'attente de stabilité.
- Aucun changement backend / frontend / base de données.

---

## Valeurs initiales

| Champ | Valeur initiale | Règle |
|-------|----------------|-------|
| `QUIET_MILLIS` | `3000` | Durée de silence de navigation qui déclare la page stable. |
| `CAP_MILLIS` | `20000` | Plafond global de l'attente : au-delà, démarrage best-effort. |
| `POLL_MILLIS` | `250` | Granularité de scrutation (via le `Sleeper` existant). |

---

## Contraintes de validation

- `QUIET_MILLIS`, `CAP_MILLIS`, `POLL_MILLIS` strictement positifs (le plafond garantit la
  terminaison : `totalElapsed` croît de `POLL_MILLIS` à chaque créneau).
- La détection est **pure** (pas d'horloge murale) : elle avance au rythme des créneaux de scrutation,
  ce qui la rend déterministe en test et rapide en CI (`Sleeper` no-op).

---

## Technique

### Fichiers impactés (runner)

| Fichier | Opération | Notes |
|---------|-----------|-------|
| `CaptureStabilityGate.java` | **NOUVEAU** | logique pure de détection de stabilité (ré-armement du minuteur de silence + plafond) + fin driver `awaitStable(Sleeper)`. |
| `TeamsTools.java` | MODIF | `meetingCaptureStart` attend la stabilité (`awaitMeetingStable`) avant `START_SCRIPT` ; diag `capture/awaiting_stable`, champ `stable` sur `capture/start`. |
| `CaptureStabilityGateTest.java` | **NOUVEAU** | tests unitaires purs : navigations successives → attente ; silence `QUIET_MILLIS` → stable ; plafond → cap. |
| `TeamsMeetingCaptureReinjectTest.java` | MODIF | ajoute : un `start` stable (sans navigation) démarre, émet `capture/awaiting_stable result=stable`, et ne part **pas** en `capture/reinject`. |

### Migration Liquibase

- [x] Non applicable (fichiers runner uniquement).

### Composants Angular

- Aucun.

---

## Plan de test

### Tests unitaires (runner, sans navigateur)

- [ ] `CaptureStabilityGateTest` — sans navigation, la stabilité est atteinte après `QUIET_MILLIS`
      de créneaux de silence → `STABLE`.
- [ ] `CaptureStabilityGateTest` — une navigation **ré-arme** le minuteur : le silence doit repartir
      de zéro ; tant que les navigations s'enchaînent (< `QUIET_MILLIS` d'écart), l'état reste
      `WAITING`.
- [ ] `CaptureStabilityGateTest` — navigations continues jusqu'au **plafond** → `CAP_REACHED`.
- [ ] `CaptureStabilityGateTest` — un sous-cadre (`frame.parentId` présent) **n'est pas** une
      navigation (seul le cadre principal ré-arme).
- [ ] `CaptureStabilityGateTest` — le nombre de navigations observées est correctement remonté (diag).

### Tests d'intégration (CDP simulé)

- [ ] `TeamsMeetingCaptureReinjectTest` — `start` sur page stable (aucune navigation) : démarre,
      émet `capture/awaiting_stable result=stable`, **aucun** `capture/reinject`, `stop` remonte
      `audio_bytes > 0` / `image_count > 0`.
- [ ] `TeamsMeetingCaptureReinjectTest` — non-régression SF-128-12/13 (le filet ré-injecte toujours
      si une navigation tardive détruit le global) reste vert.

### Isolation utilisateur

- [x] Non applicable — code runner local ; la remontée audio reste sous `workspace_id`+`meeting_id`
      (inchangée).

---

## Préoccupations transversales

- **Auth / Principal** : non touché.
- **Contexte tenant** : non touché (aucun accès données ; remontée inchangée sous `workspace_id`).
- **Plans / limites** : non touché.
- **Navigation / routing** : au sens **frontend**, non touché. La « navigation » ici est celle du
  navigateur piloté (CDP `Page.frameNavigated`/`loadEventFired`) — composants impactés :
  `TeamsTools.meetingCaptureStart` (nouveau point d'attente) et la nouvelle `CaptureStabilityGate` ;
  `CaptureReinjector` reste le filet, inchangé.

---

## Drapeau

**« À VALIDER SUR CALL RÉEL »** — la séquence réelle des navigations Teams et le comportement de
`getDisplayMedia` après stabilisation ne se vérifient qu'en réunion réelle (DEBUG côté PO). La CI
n'éprouve que la décision de stabilité et le câblage CDP contre un faux.
