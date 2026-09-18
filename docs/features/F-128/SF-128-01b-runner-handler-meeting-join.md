# Mini-spec — F-128 / SF-128-01b — Finalisation runner : handler `teams_meeting_join`

## Identifiant

`F-128 / SF-128-01b` (finalisation runner de SF-128-01 — **choix documenté** : SF dédiée courte plutôt
que rouvrir SF-128-01 déjà mergée, pour rester traçable et mergeable indépendamment)

## Feature parente

`F-128` — Capturer et exploiter une réunion Teams

## Statut

`ready`

## Date de création

2026-09-18

## Branche Git

`feat/SF-128-01b-runner-meeting-join`

---

## Objectif

> Côté **runner**, implémenter le tool `teams_meeting_join` : **naviguer l'onglet Teams du Chrome managé**
> (F-122) vers l'URL de la réunion via l'attache **CDP** existante (`Page.navigate`), pour que
> « Rejoindre & capturer » (SF-128-01) **ouvre réellement** la réunion dans le Chrome managé.

---

## Comportement attendu

### Cas nominal

1. Le backend (`TeamsMeetingService`) appelle le tool `teams_meeting_join` avec
   `{ url, purpose:"meeting", participants_informed:true }` (déjà en place, SF-128-01).
2. Le runner s'attache au **Chrome managé** (port de debug F-122, `BrowserPort`/`ManagedChrome`),
   récupère l'**onglet Teams** (`BrowserTargets.teamsTab`), ouvre une `CdpConnection` et envoie
   `Page.navigate { url }`.
3. Rend `{ joined:true, tabUrl:<url> }`. Le backend passe l'artefact en `RECORDING`.

### Cas d'erreur

| Situation | Comportement runner | Effet backend |
|-----------|---------------------|---------------|
| `url` absente / non http(s) | résultat d'échec nommé `invalid_input` | 409 `managed_chrome_unreachable` (générique) — l'URL est déjà validée côté backend, ce cas ne devrait pas survenir |
| Chrome managé injoignable (port down) | échec nommé (`browser_unreachable`) | 409 `managed_chrome_unreachable` (guide « Rejoindre & capturer ») |
| Aucun onglet Teams | échec nommé (`teams_tab_not_found`) | 409 `managed_chrome_unreachable` |
| Échec CDP `Page.navigate` | échec nommé | 409 `managed_chrome_unreachable` |

> Le backend `mapRunnerFailure` (SF-128-01) traduit tout échec runner non-`runner_unavailable` en
> `managed_chrome_unreachable` : aucun changement backend requis.

---

## Critères d'acceptation

- [ ] Le tool `teams_meeting_join` est reconnu et routé par `TeamsTools.dispatch` (nouveau `case`).
- [ ] Sur succès, le runner envoie `Page.navigate` avec l'URL reçue et rend `{joined:true, tabUrl}`.
- [ ] URL absente/invalide → résultat d'échec nommé, sans navigation.
- [ ] Chrome injoignable / onglet Teams introuvable → échec nommé (pas de crash, message lisible).
- [ ] `teams_meeting_join` **n'est pas** ajouté à `TeamsTools.CATALOG` (ce n'est pas un outil d'agent ;
      c'est une commande d'orchestration appelée directement par le backend — symétrie avec le côté
      gateway où la constante est hors `TeamsToolCatalog.CATALOG`).
- [ ] `Page.navigate` est dans la liste `CdpCommands.ALLOWED` (l'ajouter si absent).

---

## Périmètre

### Hors scope (explicite)

- La **capture des octets** (audio/vidéo) → SF-128-02.
- Les **images clés** → SF-128-03.
- La logique de pré-jonction Teams (cliquer « Rejoindre maintenant ») : v1 = navigation vers l'URL
  (la page de pré-jonction s'ouvre ; l'utilisateur rejoint). **DRAPEAU** : automatiser le clic
  « Rejoindre » est une évolution.

---

## Technique

### Runner (module `runner`)

- `TeamsTools` : nouveau `case MEETING_JOIN` + méthode `meetingJoin(JsonNode input)` (attache CDP,
  `Page.navigate`). Constante `MEETING_JOIN = "teams_meeting_join"`.
- `CdpCommands.ALLOWED` : garantir `Page.navigate` (déjà présent d'après l'inventaire ; sinon ajouter).
- Aucun changement backend (l'appel + le mapping d'échec existent depuis SF-128-01).

### Tables / endpoints / composants

- **Aucune** table, **aucun** endpoint nouveau, **aucun** frontend (N/A — finalisation runner).

---

## Plan de test

### Tests unitaires (runner, JUnit5)

- [ ] `meetingJoin` nominal : navigation invoquée avec l'URL, résultat `{joined:true, tabUrl}`
      (CDP/BrowserLink mockés).
- [ ] URL absente → échec nommé, aucune navigation.
- [ ] Chrome injoignable / onglet Teams introuvable → échec nommé.
- [ ] `dispatch("teams_meeting_join", …)` route vers `meetingJoin`.
- [ ] `teams_meeting_join` absent de `CATALOG` (test de garde).

### Isolation

- [x] Non applicable au sens `user_id` : le runner opère sur la machine du poste sous jeton runner ;
      l'isolation `user_id`+`host_id` est garantie côté backend (SF-128-01).

---

## Dépendances

- SF-128-01 (backend + front) — **Done** (PR #673). F-122 (Chrome managé), F-87 (attache CDP).

---

## Notes et décisions

- **DRAPEAU « À VALIDER SUR CALL RÉEL »** : l'attache CDP réelle au Chrome managé et l'effet de
  `Page.navigate` ne sont **pas vérifiables en bac à sable** (pas de Chrome managé + réunion). Les tests
  unitaires couvrent le routage, l'assemblage des paramètres, les échecs nommés ; la navigation
  effective se valide sur le call réel du PO.
