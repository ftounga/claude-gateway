# Mini-spec — [F-128 / SF-128-13] La ré-injection re-résout la cible CDP après navigation

## Identifiant

`F-128 / SF-128-13`

## Feature parente

`F-128` — Capturer et exploiter une réunion Teams (écran + audio → transcription → exploitation)

## Statut

`in-progress`

## Date de création

2026-09-19

## Branche Git

`feat/SF-128-13-reinjection-re-resout-cible`

---

## Objectif

> En une phrase : que fait cette subfeature ?

Faire en sorte que la **ré-injection du capteur** (SF-128-12) **re-résolve la cible CDP courante** (l'onglet Teams/réunion, devenu une **nouvelle cible** après la navigation Teams) et **s'y rattache à neuf** avant d'injecter — au lieu de réutiliser le lien/target capturé au démarrage, désormais obsolète — pour qu'un arrêt de capture remonte enfin `audio_bytes > 0` et `image_count > 0`.

---

## Comportement attendu

### Cas nominal

1. `teams_meeting_capture_start` : le capteur est injecté et le **ré-armement** est mis en place (SF-128-12 : `Page.enable`, abonnement `Page.loadEventFired` / `Page.frameNavigated`).
2. Teams (SPA) **navigue** vers la réunion → l'onglet de réunion est une **nouvelle cible CDP** (nouveau `webSocketDebuggerUrl`) ; le socket capturé au démarrage est **fermé** par Chrome (cible détruite).
3. À l'événement de chargement, avant toute injection, le ré-injecteur **re-résout la cible courante** par le **même chemin que la boucle Vigie** (`BrowserLink.attach` → `BrowserTargets.teamsTab`, via `TeamsSession.link()` qui ré-attache quand le socket précédent est mort) et obtient un `Eval` **rattaché à neuf**.
4. Il sonde l'activité (`ACTIVE_PROBE_SCRIPT`) **sur la cible fraîche** ; si inactive, ré-injecte `START_SCRIPT` avec **`userGesture:true`** (auto-accept SF-122-05) **sur la cible fraîche**.
5. `teams_meeting_capture_stop` trouve un enregistrement actif et remonte `bytes > 0` / `images > 0`.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Aucun onglet Teams réellement joignable à la ré-attache | La re-résolution rend « aucune cible » → diag `WARN capture/reattach reason=no_teams_tab`, aucune ré-injection ; **c'est le SEUL cas qui conclut à l'injoignabilité** |
| Cible fraîche trouvée mais devient injoignable pendant la sonde/l'injection | Best-effort strict : avalé, diagnostiqué `WARN capture/reinject reason=browser_unreachable` |
| Événement de chargement alors que la capture est déjà active (même contexte, pas de swap de cible) | Aucune ré-injection : garde `shouldReinject` (pas de double `getDisplayMedia`) |
| Désarmé (`stop`/cleanup) puis événement de chargement | Ignoré (non-régression SF-128-12) |

---

## Critères d'acceptation

- [ ] À chaque navigation, la ré-injection **re-résout la cible CDP courante** (ne réutilise **jamais** un `BrowserLink`/target capturé avant la navigation) via le **même chemin** que la Vigie (`BrowserLink.attach` / `BrowserTargets.teamsTab`).
- [ ] Quand l'ancienne cible est morte **mais** une nouvelle cible Teams est présente : la ré-attache réussit et `START_SCRIPT` est ré-injecté avec `userGesture:true` **sur la cible fraîche** → `reinjections` augmente.
- [ ] La ré-injection ne conclut `browser_unreachable`/injoignable **que si aucune** cible Teams n'est réellement joignable.
- [ ] Un événement diag clair est émis sur la ré-attache : `INFO capture/reattach result=ok` (cible retrouvée) et `WARN capture/reattach reason=no_teams_tab` (aucune).
- [ ] Non-régression : SF-128-12 (armement/désarmement, garde `shouldReinject`, désarmé au stop/cleanup), SF-128-09 (recover), SF-122-05/06/07/08, la boucle Vigie, `TeamsAdapterV1`, le heartbeat et F-132 restent verts.

---

## Périmètre

### Hors scope (explicite)

- Le comportement navigateur réel (rechargement de la page de réunion, `getDisplayMedia` après ré-injection) reste **« À VALIDER SUR CALL RÉEL »** (non CI) — on teste la logique pure : re-résolution de cible et décision de ré-attache.
- Aucun changement backend / frontend / base de données.
- Le **déclenchement** de la ré-injection (abonnement aux événements de cycle de vie) est **inchangé** (SF-128-12) : F-132 confirme qu'il se déclenche bien ; seule l'**action** de ré-injection est corrigée pour re-résoudre la cible.
- Aucune ré-écriture de la logique CDP : on **réutilise** `BrowserLink.attach` / `BrowserTargets.teamsTab` / `TeamsSession.link()`.

---

## Technique

### Fichiers impactés (runner)

| Fichier | Opération | Notes |
|---------|-----------|-------|
| `CaptureReinjector.java` | MODIF | remplace l'`Eval` figé (cible capturée) par un `Reattacher` re-résolu à chaque navigation ; diag `capture/reattach` |
| `TeamsTools.java` | MODIF | `armReinjection` fournit un `Reattacher` qui re-résout via `link()` (= `TeamsSession.link()`, chemin Vigie) et rend un `Eval` frais ; `null` si aucun onglet Teams joignable |
| `CaptureReinjectorTest.java` | MODIF | adapte au `Reattacher` + ajoute les cas re-résolution / no_teams_tab |
| `TeamsMeetingCaptureReinjectTest.java` | MODIF | ajoute l'e2e « ancienne cible morte + nouvelle cible Teams → reinject réussit » |

### Migration Liquibase

- [x] Non applicable (fichiers runner uniquement)

### Composants Angular

- Aucun.

---

## Plan de test

### Tests unitaires (runner, sans navigateur)

- [ ] `CaptureReinjectorTest` — `arm()` envoie `Page.enable` et s'abonne aux événements (non-régression SF-128-12).
- [ ] `CaptureReinjectorTest` — navigation + capture inactive + **ré-attache qui rend une cible fraîche** ⇒ `START_SCRIPT` ré-injecté avec geste utilisateur ; compteur +1 ; diag `capture/reattach result=ok`.
- [ ] `CaptureReinjectorTest` — navigation mais capture encore active ⇒ pas de ré-injection (garde).
- [ ] `CaptureReinjectorTest` — **ré-attache qui ne trouve aucune cible Teams** ⇒ aucune ré-injection + diag `capture/reattach reason=no_teams_tab`.
- [ ] `CaptureReinjectorTest` — cible fraîche trouvée mais l'`Eval` lève (injoignable en cours) ⇒ avalé + diag `capture/reinject browser_unreachable`.
- [ ] `CaptureReinjectorTest` — désarmé ⇒ événement de chargement ignoré ; sous-cadre ignoré.

### Tests d'intégration (CDP simulé)

- [ ] `TeamsMeetingCaptureReinjectTest` — **ancienne cible morte après navigation + nouvelle cible Teams présente** : `start → (navigation) → stop` remonte `audio_bytes > 0` et `image_count > 0` (la ré-injection a re-résolu la cible).
- [ ] `TeamsMeetingCaptureReinjectTest` — non-régression : le flux SF-128-12 (même cible qui survit) reste vert.

### Isolation utilisateur

- [x] Non applicable — code runner local ; la remontée audio reste sous `workspace_id`+`meeting_id` (inchangée).

---

## Préoccupations transversales

- **Auth / Principal** : non touché.
- **Contexte tenant** : non touché (remontée audio inchangée, `workspace_id`/`meeting_id`).
- **Plans / limites** : non touché.
- **Navigation / routing** : ici « navigation » = navigation de la page Teams dans le Chrome managé (CDP), pas le routing Angular. Composants runner impactés listés ci-dessus (`CaptureReinjector`, `TeamsTools`). Aucun impact sur le routing produit. La re-résolution de cible réutilise le chemin partagé `TeamsSession.link()` / `BrowserLink.attach` / `BrowserTargets.teamsTab` (déjà utilisé par la boucle Vigie et tous les outils Teams) — aucun nouveau moyen de résoudre la cible.

---

## Dépendances

### Subfeatures bloquantes

- `SF-128-12` (capture robuste à la navigation Teams) — done
- `SF-128-02` (capture onglet) — done
- `SF-128-09` (robustesse re-capture) — done
- `SF-122-05` (auto-accept capture d'onglet) — done
- `SF-122-06` (boucle Vigie branchée, chemin `TeamsSession.link()`) — done

### Questions ouvertes impactées

- Aucune.

---

## Notes et décisions

- **Cause exacte** (F-132 DEBUG, prod CAGIP 2026-09-19, test 6) : la capture reste vide (`audio_bytes=0`, `image_count=0`, `stop → no_active_capture`) malgré SF-128-12. Les événements DEBUG prouvent : `start` ok (`reinjectArmed:true`) → `reinject → browser_unreachable` (13:04:45 et 13:04:55) **au moment même** où le tick de la boucle Vigie rapporte `reachable:true`, `session:CONNECTED` (13:04:50). Le Chrome managé **n'est pas** injoignable (la Vigie l'atteint car elle **re-résout** sa cible à chaque tick). C'est la ré-injection SF-128-12 qui échoue parce qu'elle **réutilise un lien/target CDP obsolète** après la navigation Teams : l'onglet de réunion est une **nouvelle cible CDP**, donc le socket capturé au `start` est mort (`WebSocketCdpConnection.send` lève `LINK_LOST` sur socket fermé).
- **Remède** : la ré-injection re-résout la cible courante **à neuf** avant d'injecter, exactement comme `BrowserLink.attach` / la boucle Vigie. Concrètement, `TeamsTools` fournit au `CaptureReinjector` un `Reattacher` qui appelle `link()` (= `TeamsSession.link()`) : quand le socket capturé au `start` est fermé (`isOpen()==false`, cas confirmé), `link()` ré-liste les cibles (`/json/list`), retrouve l'onglet Teams courant (`BrowserTargets.teamsTab`) et ouvre un socket neuf — puis on ré-injecte `START_SCRIPT` (`userGesture:true`) sur cette connexion fraîche.
- **Pourquoi réutiliser `TeamsSession.link()`** : c'est le **chemin même** de la boucle Vigie (via `VigieSonde.sense()`), celui qui donne `reachable:true`. Aucune logique CDP réécrite ; aucun nouveau moyen de résoudre la cible.
- **Déclenchement inchangé** : l'abonnement aux événements de cycle de vie reste sur la connexion d'armement (F-132 confirme que la ré-injection **se déclenche** — l'événement de chargement arrive pendant la transition). Seule l'**action** est corrigée. Le correctif reste **best-effort strict** (aucune exception ne remonte de la boucle d'événements).
- **Fichiers runner uniquement → mise à jour runner requise** après merge.
