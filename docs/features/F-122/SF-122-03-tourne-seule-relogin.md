# Mini-spec — [F-122 / SF-122-03] Tourne seule + relogin Teams guidé à l'expiration

> Base : `project-governance/templates/subfeature-template.md`.
> Cadrage : `docs/features/F-122/CADRAGE-F-122-mise-en-service-vigie-automatique.md` (SF-122-03).

---

## Identifiant

`F-122 / SF-122-03`

## Feature parente

`F-122` — Mise en service automatique et guidée de la Vigie

## Statut

`ready`

## Date de création

2026-09-15

## Branche Git

`feat/SF-122-03-tourne-seule-relogin`

---

## Objectif

> Faire tourner la Vigie **en arrière-plan** (sans fenêtre visible) et **détecter l'expiration** de la
> session Teams (redirection login / 401-403) pour demander un **relogin guidé**, puis revenir seule à
> l'arrière-plan.

---

## Comportement attendu

### Cas nominal

1. La Vigie fonctionne en arrière-plan : le Chrome managé (SF-122-01) reste discret (hors champ) ;
   aucune fenêtre ne surgit au quotidien. La synchro (F-100) et les lectures à la demande s'appuient
   sur ce Chrome managé.
2. Un **observateur de session** (`TeamsSessionWatch`) reçoit les observations réseau `{url, statut}`
   déjà produites par `NetworkObserver` et tient un état :
   - une **redirection vers une page d'identification** Microsoft (`MicrosoftDomains.isSignIn`) →
     `RELOGIN_REQUIRED` ;
   - un **401/403 sur une réponse de la famille Microsoft** → `RELOGIN_REQUIRED` ;
   - une **réponse 2xx de la famille Microsoft** (Teams répond normalement) → `CONNECTED`.
3. À l'entrée en `RELOGIN_REQUIRED`, une **notification claire** est émise (« reconnectez-vous à
   Teams ») et un **rappel de la fenêtre managée** pour le login est déclenché (action injectée). Après
   le relogin (retour à `CONNECTED`), l'état repasse à normal et la fenêtre se remet en arrière-plan.
4. L'**assembleur d'arrière-plan** (`VigieBackground`) produit un rapport de readiness (le même que
   consomme SF-122-02 : `chromeReachable`, `teamsConnected`, `teamsSignInRequired`, `teamsReadTest`)
   à partir de l'état du Chrome managé, de l'état de session et du test de lecture — que la boucle fait
   remonter à la gateway (`POST /api/runner/vigie/readiness`, `VigieReadinessUploader`).

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Redirection login observée alors qu'on était `CONNECTED` | Transition `CONNECTED → RELOGIN_REQUIRED`, notification émise **une seule fois** (pas à chaque observation) |
| 401/403 sur une réponse Microsoft | `RELOGIN_REQUIRED`, notification |
| Réponse 2xx Microsoft après relogin | Transition `RELOGIN_REQUIRED → CONNECTED`, notification de rétablissement, fenêtre remise en arrière-plan |
| Aucun jeton runner / pas de gateway à joindre | L'uploader est `unavailable` : la remontée ne casse pas la boucle (elle lève une `IOException` que la boucle absorbe) |
| Observation hors famille Microsoft (bruit) | Aucun changement d'état |

---

## Critères d'acceptation

- [ ] `TeamsSessionWatch` passe à `RELOGIN_REQUIRED` sur une URL d'identification Microsoft.
- [ ] `TeamsSessionWatch` passe à `RELOGIN_REQUIRED` sur un 401/403 d'une réponse de la famille
      Microsoft.
- [ ] `TeamsSessionWatch` revient à `CONNECTED` sur une réponse 2xx de la famille Microsoft.
- [ ] La notification de relogin est émise **une seule fois** par transition (pas à chaque
      observation), de même pour la notification de rétablissement.
- [ ] Une observation hors famille Microsoft ne change pas l'état.
- [ ] `VigieBackground.assemble(...)` produit un rapport cohérent : `teamsConnected` ⇔ `CONNECTED`,
      `teamsSignInRequired` ⇔ `RELOGIN_REQUIRED`, `chromeReachable`/`teamsReadTest` reflétés.
- [ ] `VigieReadinessUploader.unavailable(...)` lève une `IOException` sans casser l'appelant, et le
      corps JSON produit par la remontée porte les cinq champs attendus.
- [ ] À l'entrée en `RELOGIN_REQUIRED`, l'action de rappel de fenêtre (injectée) est déclenchée ; au
      retour à `CONNECTED`, l'action de remise en arrière-plan (injectée) est déclenchée.

---

## Périmètre

### Hors scope (explicite)

- L'ouverture/rappel **réel** de la fenêtre par CDP (bring-to-front) et la **planification** de la
  boucle : ce sont des **coutures injectées** ; leur implémentation CDP concrète et le cadencement
  sont validés **sur machine réelle** (le CI n'a ni Chrome ni compte Teams).
- Le parsing Teams (`TeamsAdapterV1`) et le relevé (SF-89-*) : intacts.
- La check-list backend/frontend (SF-122-02, livrée) : SF-03 l'**alimente**, ne la redéfinit pas.
- Les messages nommés des cas d'entreprise (policy/NTLM/Chrome absent) → SF-122-04.

---

## Technique

### Composants (runner)

- `TeamsSessionState` (enum `CONNECTED`/`RELOGIN_REQUIRED`/`UNKNOWN`).
- `TeamsSessionWatch` — état de session + notifications (une par transition) + coutures
  `onReloginRequired`/`onReconnected` (rappel/remise en arrière-plan de la fenêtre).
- `VigieReadinessReport` — le rapport (mêmes champs que l'instantané SF-122-02).
- `VigieBackground` — assemble le rapport depuis l'état Chrome (SF-01) + l'état de session + le test
  de lecture.
- `VigieReadinessUploader` — `over(HttpClient, gatewayBaseUrl, token)` (POST `/runner/vigie/readiness`,
  `X-Runner-Token`) et `unavailable(why)`, sur le modèle de `MomentUploader`.
- Réutilise sans les modifier : `MicrosoftDomains`, `ManagedChrome`, `NetworkObserver`, `BrowserPort`.

### Tables impactées / endpoints

Aucune table, aucune migration. Réutilise l'endpoint d'ingestion `POST /api/runner/vigie/readiness`
livré en SF-122-02.

## Plan de test

### Tests unitaires (runner, `./mvnw -q test`)

- [ ] `TeamsSessionWatchTest` — transitions login/401/403 → `RELOGIN_REQUIRED` ; 2xx → `CONNECTED` ;
      notification une seule fois par transition ; bruit hors Microsoft ignoré ; coutures
      `onReloginRequired`/`onReconnected` déclenchées aux bonnes transitions.
- [ ] `VigieBackgroundTest` — assemblage cohérent selon l'état Chrome + session + test de lecture.
- [ ] `VigieReadinessUploaderTest` — `unavailable` lève une `IOException` ; le corps JSON porte les
      cinq champs.

### Tests d'intégration

- Sans objet (composants runner ; le POST réel réutilise l'endpoint testé en SF-122-02, et le
  lancement/rappel de fenêtre est validé sur machine réelle).

### Isolation utilisateur / tenant

- [x] Applicable — la session Teams et le Chrome managé sont **par poste** ; la remontée est
      authentifiée par le **jeton du poste** (isolation jeton→hostId, déjà tenue par SF-122-02).

---

## Préoccupations transversales

| Préoccupation | Impact | Composants |
|---------------|--------|------------|
| **Auth / Principal** | Le runner **n'automatise jamais** le login : `RELOGIN_REQUIRED` demande à l'utilisateur de se connecter dans la fenêtre managée. Aucune saisie d'identifiants, aucun cookie remonté (gardes SF-87/F-108 inchangées). | `TeamsSessionWatch` (détection seulement) |
| **Contexte tenant** | Aucun nouveau moyen de résoudre le tenant : la remontée passe par le jeton du poste (SF-122-02). | `VigieReadinessUploader` |
| **Plans / limites** | Aucun. | — |
| **Navigation / routing** | Aucun (pas de frontend dans cette SF). | — |

---

## Dépendances

### Subfeatures bloquantes

- `SF-122-01` (Done) — `ManagedChrome` (état du Chrome pour le rapport).
- `SF-122-02` (Done) — l'endpoint d'ingestion `POST /api/runner/vigie/readiness`.

### Questions ouvertes impactées

- Aucune de `docs/OPEN_QUESTIONS.md`.

---

## Notes et décisions

- **Notification une seule fois par transition** : une session expirée produit beaucoup
  d'observations ; répéter la notification à chaque 401 serait du bruit. Seule la **transition** parle.
- **Coutures injectées** pour le rappel/masquage de fenêtre : l'action CDP concrète et la boucle sont
  validées sur machine ; la **décision** (quand rappeler, quand remettre en fond) est testée ici.
