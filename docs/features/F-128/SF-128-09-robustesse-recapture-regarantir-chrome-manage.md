# Mini-spec — [F-128 / SF-128-09] Robustesse re-capture : re-garantir le Chrome managé avant d'échouer

---

## Identifiant

`F-128 / SF-128-09`

## Feature parente

`F-128` — Capturer et exploiter une réunion Teams (écran + audio → transcription → exploitation)

## Statut

`in-progress`

## Date de création

2026-09-19

## Branche Git

`feat/SF-128-09-robustesse-recapture-chrome-manage`

---

## Objectif

> En une phrase : rendre « Rejoindre & capturer » robuste à la répétition en (re)garantissant le Chrome managé (relance idempotente) avant de conclure « injoignable », et en laissant l'onglet dans un état ré-utilisable après une capture.

---

## Contexte — le bug (reproduit en prod CAGIP, 2026-09-19)

La **1ère** « Rejoindre & capturer » marche (artefact `STOPPED`, audio + 1 image en base). La **2ᵉ** tentative sur la même réunion échoue **au départ** : `teams_meeting_join` juge le Chrome managé **injoignable** (l'attache CDP échoue) alors qu'il l'était avant la 1ère capture.

**Cause exacte trouvée dans le code du runner :**

1. Après une capture puis la fin de réunion, le Chrome managé peut **tomber** : son unique onglet/fenêtre Teams se referme → le process sort → le port de débogage CDP ne répond plus.
2. `TeamsTools.meetingJoin` appelle `link()` → `TeamsSession.link()` → `BrowserLink.attach(port, …)`. Port muet ⇒ `attach` lève `BrowserLinkException(BROWSER_NOT_DETECTED)`, que `meetingJoin` **traduit immédiatement** en `error("browser_unreachable", …)`.
3. **`meetingJoin` ne tente jamais de (re)garantir le Chrome managé** : `TeamsTools` n'a **aucune** référence à `ManagedChrome`. L'instance `ManagedChrome` (avec son `ensureRunning()` idempotent de SF-122-01/06) vit **uniquement** dans `VigieLoop`, construit à part dans `RunnerConnection.startVigie`. Le join conclut donc « injoignable » **sans jamais tenter la récupération** ; la boucle Vigie (~20 s) finirait par relancer Chrome, mais le join synchrone échoue avant.

Côté backend, `TeamsMeetingService.mapRunnerFailure` mappe tout échec runner (dont `browser_unreachable`) sur le message actionnable « Impossible de rejoindre la réunion dans le Chrome managé : ouvrez la Vigie… et relancez ». **Aucun changement backend n'est donc nécessaire** : le correctif est entièrement runner-side.

**Constat secondaire :** `MeetingTabCapture.STOP_SCRIPT` stoppe pistes/recorder/`ctx` et encode le résultat, mais **ne remet jamais** `window.__cgMeetingCapture` à zéro — le résultat (base64) et les images clés restent en mémoire de l'onglet après un arrêt.

---

## Comportement attendu

### Cas nominal

1. `teams_meeting_join` tente la navigation (attache CDP + `Page.navigate`).
2. Si l'attache échoue (`BrowserLinkException`), **avant** de conclure « injoignable », le join **(re)garantit** le Chrome managé via `ManagedChrome.ensureRunning()` (idempotent : `REACHABLE` s'il répond déjà, sinon relance → `LAUNCHED`), puis **retente** la navigation.
3. Si la récupération aboutit, la navigation retentée réussit et le join rend `joined=true`. Le message « injoignable » **n'apparaît pas**.
4. `teams_meeting_capture_stop` : après la remontée (audio + images), l'onglet est **nettoyé** (`window.__cgMeetingCapture` remis à `null`, échantillonneur et pistes coupés best-effort) → une 2ᵉ capture repart proprement.

### Cas d'erreur

| Situation | Comportement attendu | Code |
|-----------|---------------------|------|
| Attache échoue **et** aucun `ManagedChrome` branché (repli long-polling) | `browser_unreachable` (comportement inchangé) | error |
| Attache échoue **et** récupération réellement impossible (`NO_BROWSER` / `UNREACHABLE`) | `browser_unreachable` — message actionnable, **seulement après** la tentative de récupération | error |
| Attache échoue, récupération OK, retentative échoue encore (attache) | `browser_unreachable` | error |
| URL de réunion absente | `invalid_input`, aucune récupération, aucune navigation | error |
| Une capture est déjà en cours (`start`) | `started=true` (`already`), **jamais** « injoignable » — comportement existant conservé | ok |
| Nettoyage post-stop qui échoue | Best-effort : n'annule **pas** la remontée réussie | ok |

---

## Critères d'acceptation

- [ ] Après un cycle join → capture_start → capture_stop où le Chrome managé est **tombé**, un **2ᵉ** `teams_meeting_join` **réussit** (le Chrome est re-garanti puis la navigation retentée aboutit).
- [ ] La récupération réutilise `ManagedChrome.ensureRunning()` (SF-122-01/06) **sans réécrire** sa logique ; elle est **idempotente** (Chrome déjà joignable ⇒ **aucune** relance).
- [ ] Le message `browser_unreachable` n'est rendu **qu'après** un échec **réel** de récupération (ou quand aucun `ManagedChrome` n'est branché).
- [ ] `teams_meeting_capture_stop` laisse un état **ré-utilisable** : `window.__cgMeetingCapture` est remis à zéro (script de nettoyage), et une 2ᵉ capture repart proprement (le garde de démarrage ne bloque que sur un enregistrement **actif**).
- [ ] Non-régression : 1ère capture, `VigieLoop`/`VigieSonde` (SF-122-06), `TeamsAdapterV1`, heartbeat, auto-accept SF-122-05, catalogues d'outils — inchangés.
- [ ] Le Chrome managé est une **instance unique partagée** entre `VigieLoop` et `TeamsTools` (méthodes `synchronized`, pas de double process).

---

## Périmètre

### Hors scope (explicite)

- Aucun changement backend (le mapping `browser_unreachable` → message actionnable existe déjà).
- Aucun changement frontend, aucune migration, aucun endpoint.
- SF-128-04 (STT), SF-128-05 (exploitation), SF-128-07 (purge rétention) : hors sujet.
- Récupération du Chrome dans `teams_meeting_capture_start` / `_stop` : hors périmètre (le bug reproduit est au **join** ; un capture_start survient après un join qui a déjà re-garanti le Chrome). Le nettoyage d'état post-stop reste dans le périmètre.
- Branchement d'une Vigie complète en mode repli long-polling (`PollingConnection`) : pré-existant, hors sujet — en repli, `ManagedChrome` reste `null` et le join se comporte comme avant.

---

## Technique

### Endpoint(s)

Aucun.

### Tables impactées

Aucune.

### Migration Liquibase

- [x] Non applicable

### Composants Angular

Aucun.

### Fichiers impactés (runner uniquement)

| Fichier | Opération | Notes |
|---------|-----------|-------|
| `runner/.../teams/TeamsTools.java` | modif | `meetingJoin` : (re)garantie + retentative ; champ + setter `withManagedChrome` ; `recoverManagedChrome()` ; nettoyage post-stop dans `meetingCaptureStop` |
| `runner/.../teams/MeetingTabCapture.java` | modif | `CLEANUP_SCRIPT` (remet `window.__cgMeetingCapture` à zéro) |
| `runner/.../ToolStack.java` | modif | surcharge `create(…, ManagedChrome)` ; branche `withManagedChrome` ; 3-arg délègue avec `null` (repli inchangé) |
| `runner/.../RunnerConnection.java` | modif | crée l'instance `ManagedChrome` **une fois**, la passe à `ToolStack.create` **et** à `startVigie` (instance partagée) |

---

## Préoccupations transversales

Aucun déclencheur (Auth/Principal, Contexte tenant, Plans/limites, Navigation/routing) — le changement est confiné à l'orchestration de process du runner Teams. L'isolation `user_id`/`host_id` reste **entièrement backend** (SF-128-01→03) et n'est pas touchée.

---

## Plan de test

### Tests unitaires (runner)

- [ ] `TeamsMeetingRecaptureToolTest` — un 2ᵉ join **récupère** un Chrome tombé (probe/`ManagedChrome` simulés : Chrome « tombé » entre les deux joins) et rend `joined=true` ; le Chrome est relancé (une seule fois).
- [ ] `TeamsMeetingRecaptureToolTest` — récupération **idempotente** : Chrome déjà joignable ⇒ aucune relance (0 lancement).
- [ ] `TeamsMeetingRecaptureToolTest` — récupération **impossible** (`NO_BROWSER`) ⇒ `browser_unreachable` (message rendu seulement après tentative).
- [ ] `TeamsMeetingRecaptureToolTest` — aucun `ManagedChrome` branché ⇒ `browser_unreachable` (non-régression du comportement de repli).
- [ ] `MeetingTabCaptureTest` — `CLEANUP_SCRIPT` remet `window.__cgMeetingCapture` à `null` (coupe échantillonneur/pistes) ; `START_SCRIPT` ne court-circuite que sur un enregistrement **actif** (donc un global nettoyé ⇒ nouvelle capture).

### Tests d'intégration

- Non applicable (aucun endpoint/DB touché). Le comportement navigateur réel reste marqué **« À VALIDER SUR CALL RÉEL »** (non vérifiable en CI, cf. SF-128-02/03).

### Isolation workspace / user

- [x] Non applicable — raison : aucun accès données ; l'isolation reste backend (inchangée). Non-régression validée par la suite runner (catalogues, `TeamsAdapterV1`, Vigie).

---

## Dépendances

### Subfeatures bloquantes

- `SF-122-01` (ManagedChrome), `SF-122-06` (VigieLoop/VigieSonde branchés) — **Done**
- `SF-128-01b` (teams_meeting_join), `SF-128-02` (capture onglet), `SF-128-03` (images) — **Done**

### Questions ouvertes impactées

- Aucune de `docs/OPEN_QUESTIONS.md`.

---

## Notes et décisions

- **Décision d'archi (documentée en PR)** : le `ManagedChrome` devient une **instance unique** créée dans `RunnerConnection`, partagée entre `VigieLoop` (cycle de vie) et `TeamsTools` (récupération à la demande). Ses méthodes de cycle de vie sont déjà `synchronized` → partage sûr, pas de double process. Cohérent avec la philosophie « volet Teams monté une fois » de `ToolStack`.
- Le message actionnable et son mapping backend restent **inchangés** ; on n'en change que la **fréquence** d'apparition (seulement après échec réel de récupération).
- Le nettoyage post-stop est **best-effort** : il ne transforme jamais une remontée réussie en échec.
</content>
</invoke>
