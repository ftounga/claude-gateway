# Mini-spec — [F-128 / SF-128-12] La capture d'onglet survit à la navigation Teams

## Identifiant

`F-128 / SF-128-12`

## Feature parente

`F-128` — Capturer et exploiter une réunion Teams (écran + audio → transcription → exploitation)

## Statut

`in-progress`

## Date de création

2026-09-19

## Branche Git

`feat/SF-128-12-capture-survit-navigation`

---

## Objectif

> En une phrase : que fait cette subfeature ?

Rendre l'enregistrement d'onglet **robuste à la navigation** de l'application Teams (SPA qui recharge la page entre `start` et `stop`), pour qu'un arrêt de capture remonte bien de l'audio (`audio_bytes > 0`) et des images clés (`image_count > 0`) au lieu d'échouer en `no_active_capture`.

---

## Comportement attendu

### Cas nominal

1. Le backend appelle `teams_meeting_join` (navigue l'onglet Teams managé vers l'URL de réunion).
2. Il appelle `teams_meeting_capture_start` : le capteur (`window.__cgMeetingCapture`) est injecté **et** un **ré-armement** est mis en place — le runner active le domaine `Page` (`Page.enable`, déjà en liste blanche) et s'abonne à `Page.loadEventFired` / `Page.frameNavigated`.
3. Quand la page Teams **navigue/recharge** (pré-jonction → en réunion, ou rechargement SPA), le contexte JS est détruit et `window.__cgMeetingCapture` est perdu. À chaque événement de chargement, le runner **sonde** l'état de capture (`ACTIVE_PROBE_SCRIPT`) et, si aucun enregistrement n'est actif, **ré-injecte** le script de démarrage (`START_SCRIPT`) **avec geste utilisateur simulé** (sans quoi `getDisplayMedia` refuse ; l'auto-accept SF-122-05 évite la boîte de dialogue).
4. Une fois la page en réunion stabilisée, le dernier ré-armement **persiste** jusqu'à `stop`.
5. `teams_meeting_capture_stop` trouve un enregistrement actif, assemble l'audio et les images, et remonte `bytes > 0` / `images > 0`.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Chrome managé injoignable au ré-armement | Le ré-armement est best-effort : l'échec est avalé (ne casse jamais la boucle d'événements), diagnostiqué en `WARN` (`capture/reinject reason=browser_unreachable`) |
| `Page.loadEventFired` reçu alors que la capture est déjà active (même contexte) | Aucune ré-injection : le garde `recorder.state === 'recording'` protège (pas de double enregistrement) |
| Aucune capture jamais démarrée puis `stop` | Comportement inchangé : `no_active_capture` nommé, rien ne remonte (non-régression) |
| Ré-injection après navigation mais `getDisplayMedia` refusé | `START_SCRIPT` rend `{started:false}` ; l'état reste non actif, retenté au prochain événement de chargement |

---

## Critères d'acceptation

- [ ] Après `capture_start`, le runner active `Page.enable` et s'abonne à `Page.loadEventFired` (et `Page.frameNavigated` cadre principal).
- [ ] À un événement de chargement, si la capture n'est **pas** active, `START_SCRIPT` est **ré-injecté avec `userGesture:true`** ; si elle est active, **rien** n'est ré-injecté.
- [ ] Le ré-armement est **désarmé** à l'arrêt de capture (`stop`) et au nettoyage (SF-128-09) — pas d'événement traité après coup.
- [ ] `MeetingTabCapture` expose un script pur de sonde d'activité (`ACTIVE_PROBE_SCRIPT`) et le garde de ré-injection (logique pure `shouldReinject`).
- [ ] Diagnostics F-132 clairs : `capture/start` porte `reinjectArmed`, un `capture/reinject` est émis à chaque ré-injection (avec le compte), `capture/stop` continue de porter `bytes`/`images`.
- [ ] Non-régression : la 1ʳᵉ capture qui marchait, le flux re-capture SF-128-09, l'auto-accept SF-122-05, la boucle Vigie, `TeamsAdapterV1`, le heartbeat et F-132 restent verts.

---

## Périmètre

### Hors scope (explicite)

- La validation du comportement navigateur réel (invite de partage, mixage, enregistrement) reste **« À VALIDER SUR CALL RÉEL »** (non CI).
- Aucun changement backend / frontend / base de données.
- Aucune capture OS ; toujours capture d'**onglet** via CDP.
- SF C (onglet Teams maintenu) et SF B (reprise après fermeture Chrome) sont des subfeatures distinctes (F-122).

---

## Technique

### Fichiers impactés (runner)

| Fichier | Opération | Notes |
|---------|-----------|-------|
| `MeetingTabCapture.java` | MODIF | + `ACTIVE_PROBE_SCRIPT`, + `shouldReinject(...)` pur, + constantes d'événements |
| `CaptureReinjector.java` | AJOUT | ré-armement sur navigation (arm/onNavigation/disarm), best-effort, testable avec CDP simulé |
| `TeamsTools.java` | MODIF | arme le ré-injecteur après un `start` réussi, le désarme au `stop`/cleanup, diag enrichis |

### Migration Liquibase

- [x] Non applicable (fichiers runner uniquement)

### Composants Angular

- Aucun.

---

## Plan de test

### Tests unitaires (runner, sans navigateur)

- [ ] `MeetingTabCaptureTest` — `ACTIVE_PROBE_SCRIPT` sonde `recorder.state === 'recording'`.
- [ ] `CaptureReinjectorTest` — `arm()` envoie `Page.enable` et s'abonne à `Page.loadEventFired`.
- [ ] `CaptureReinjectorTest` — un événement de chargement quand la capture est **inactive** ⇒ `START_SCRIPT` ré-injecté avec geste utilisateur ; le compteur augmente.
- [ ] `CaptureReinjectorTest` — un événement de chargement quand la capture est **active** ⇒ **pas** de ré-injection (garde `shouldReinject`).
- [ ] `CaptureReinjectorTest` — après `disarm()`, un événement de chargement est ignoré.
- [ ] `CaptureReinjectorTest` — un Chrome injoignable pendant le ré-armement est avalé (best-effort) et diagnostiqué.

### Tests d'intégration (CDP simulé)

- [ ] `TeamsMeetingCaptureToolTest` — après un `start`, un événement `Page.loadEventFired` déclenche une ré-injection observable (le flux `start → (nav) → stop` conserve un capteur actif via le faux navigateur qui reste actif après ré-injection).
- [ ] Garde anti-`no_active_capture` : un `stop` après ré-injection réussie ne rend plus `no_active_capture`.

### Isolation utilisateur

- [x] Non applicable — code runner local, aucune donnée multi-tenant traversée ; l'audio remonte déjà sous `workspace_id`+`meeting_id` (inchangé).

---

## Préoccupations transversales

- **Auth / Principal** : non touché.
- **Contexte tenant** : non touché (remontée audio inchangée, toujours `workspace_id`/`meeting_id`).
- **Plans / limites** : non touché.
- **Navigation / routing** : ici « navigation » = navigation **de la page Teams dans le Chrome managé** (CDP), pas le routing Angular. Composants runner impactés listés ci-dessus (`MeetingTabCapture`, `CaptureReinjector`, `TeamsTools`). Aucun impact sur le routing produit.

---

## Dépendances

### Subfeatures bloquantes

- `SF-128-02` (capture onglet) — done
- `SF-128-03` (images clés) — done
- `SF-128-09` (robustesse re-capture) — done
- `SF-122-05` (auto-accept capture d'onglet) — done

### Questions ouvertes impactées

- Aucune.

---

## Notes et décisions

- **Cause racine** : `window.__cgMeetingCapture` est un global du contexte JS de la page. Teams étant une SPA qui **recharge la page** lors de la transition pré-jonction → en réunion, ce global est effacé entre `start` et `stop` → `stop` voit `no_active_capture`, d'où `audio_bytes=0` / `image_count=0`. Ce n'est **pas** une régression SF-128-09 (le `CLEANUP_SCRIPT` ne tourne qu'après un `stop` réussi ; l'ordre join → start → stop est respecté).
- **Choix** : re-armement piloté par le runner sur événements de cycle de vie de la page (`Page.loadEventFired`/`Page.frameNavigated`), plutôt que `Page.addScriptToEvaluateOnNewDocument` — car `getDisplayMedia` exige un geste utilisateur (transient activation) que seul `Runtime.evaluate userGesture:true` synthétise ; un script auto-injecté au chargement ne l'aurait pas.
- Les chunks enregistrés avant une navigation sont perdus avec le contexte (inévitable sur un vrai rechargement de document) ; ce qui compte est que le capteur **soit vivant pendant la phase en réunion stable**, ce que le ré-armement garantit.
