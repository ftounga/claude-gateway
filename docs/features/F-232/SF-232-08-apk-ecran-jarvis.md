# Mini-spec — F-232 / SF-232-08 — L'APK : Jarvis s'ouvre dans le compagnon, par-dessus le verrou

## Identifiant

`F-232 / SF-232-08`

## Feature parente

`F-232` — Le compagnon Android de Jarvis (cadrage `CADRAGE-F-232-compagnon-android.md`, **amendement du 2026-11-04**, D5, D7, D8)

## Statut

`in-review`

## Date de création

2026-11-04

## Branche Git

`feat/SF-232-08-apk-ecran-jarvis`

---

## Objectif

> Au mot « Jarvis », le compagnon affiche l'écran Jarvis **dans sa propre WebView, par-dessus l'écran verrouillé**,
> écoute aussitôt la commande, rend le micro au mot d'éveil à la fermeture, et ne demande le déverrouillage qu'au
> « oui » d'une action engageante — APK version 2, signé avec la même clé.

---

## Comportement attendu

### Cas nominal

1. **`WakeActivity`** (lancée par l'intention plein écran existante, ou la bannière) : `setShowWhenLocked(true)` +
   `setTurnScreenOn(true)` (API ≥ 27 ; drapeaux de fenêtre sinon), `FLAG_KEEP_SCREEN_ON`, thème noir plein écran,
   **sans** `requestDismissKeyguard` à l'ouverture.
2. Elle prévient le service (`ACTION_JARVIS_OPENED`) : le micro du mot d'éveil est relâché, la reprise d'office des
   10 min est annulée, la notification de réveil retirée.
3. **WebView** : JavaScript + stockage DOM, lecture audio **sans geste** (`setMediaPlaybackRequiresUserGesture(false)`),
   aucun accès fichier ni contenu, contenu mixte interdit, pas de fenêtres multiples. Charge
   `<gateway>/jarvis?source=compagnon&ecoute=1&compagnon=<versionCode>`.
4. **Origine** : seules les pages de la gateway (même schéma, hôte, port) se chargent ; un lien externe est bloqué
   verrouillé, ouvert dans le navigateur déverrouillé. **Micro** (`onPermissionRequest`) : accordé
   (`RESOURCE_AUDIO_CAPTURE` seul) à l'origine de la gateway si `RECORD_AUDIO` est accordé à l'application ; refusé
   sinon.
5. **Pont `window.JarvisCompanion`** (aucune méthode ne rend de donnée hormis l'état verrouillé et la version) :
   - `requestSession(purpose)` ⇒ `POST /api/runner/jarvis/session` avec le jeton dédié (`X-Jarvis-Token`, jamais
     exposé à la page), `locked` = état du clavier ; la session (JSON) est remise par `evaluateJavascript`
     (**cadre principal seulement**) à `window.jarvisCompanionSession(...)` ; réutilisée tant qu'il lui reste plus de
     5 min et que l'état verrouillé n'a pas changé ; `RENEW` force une nouvelle session.
   - `requestUnlock()` ⇒ `requestDismissKeyguard` **pendant que l'écran Jarvis est affiché** (plus de clignotement) ;
     réussi ⇒ nouvelle session déverrouillée (`UNLOCK`) **puis** `window.jarvisCompanionUnlocked(true)` ; annulé ou
     erreur ⇒ `(false)`.
   - `isLocked()`, `keepAlive()`, `close()`, `version()`.
6. **Fermeture** : la page la demande (fin de conversation) ; écran éteint ou autre application devant (`onStop`, sauf
   pendant la demande de déverrouillage) ; retour ; ou **3 min** sans toucher ni signe de vie. À la destruction :
   WebView détruite, pont retiré, `ACTION_JARVIS_CLOSED` ⇒ le service reprend le mot d'éveil **400 ms** après (le
   temps que la WebView relâche le micro). Écran éteint pendant que l'écran Jarvis est ouvert : le service attend la
   fermeture (jamais deux captures).
7. **Non appairé** : message plein écran « Appairez d'abord le compagnon… », fermeture après 6 s. Jeton refusé (401) :
   oublié, message « réappairez », fermeture.
8. **Version** : `jarvisVersionCode=2`, `jarvisVersionName=0.2.0` ; même clé de signature (AWS Secrets Manager
   `claude-gateway/jarvis-companion-signing`) ; l'écran Jarvis propose la mise à jour à un compagnon v1.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Compagnon non appairé | message, fermeture 6 s ; rien n'est chargé |
| Jeton du compagnon refusé (401) | jeton oublié, message « réappairez », fermeture |
| Gateway injoignable | `jarvisCompanionSession(null)` ; l'écran se ferme (SF-232-07) |
| Page hors gateway | non chargée (bloquée verrouillé, navigateur déverrouillé) |
| Demande de micro d'une autre origine | refusée |
| Moteur de la WebView arrêté | fermeture |
| Déverrouillage annulé | `jarvisCompanionUnlocked(false)` ; la session reste verrouillée |

---

## Critères d'acceptation

- [x] Verrouillé, « Jarvis » : l'écran Jarvis s'affiche par-dessus le verrou, sans demande de déverrouillage, et écoute.
- [x] Le micro n'est jamais tenu par le mot d'éveil et la WebView à la fois (OPENED / CLOSED, reprise différée).
- [x] La WebView ne charge, ne donne le micro et ne remet la session qu'à l'origine de la gateway (cadre principal).
- [x] Au « oui » engageant, la demande de déverrouillage s'affiche au-dessus de l'écran Jarvis ; réussie, une session déverrouillée est remise avant l'issue.
- [x] L'écran se ferme à la fin de la conversation, à l'extinction, ou après 3 min sans signe de vie.
- [x] APK release signé v1 + v2 avec la clé existante, `versionCode` 2 ; tests du cœur pur verts.

---

## Contraintes de validation

| Champ | Règle |
|---|---|
| Renouvellement de session | < 5 min restantes |
| Fermeture d'office | 3 min sans signe de vie |
| Passage du micro | 400 ms |

---

## Plan de test minimal

- **Unitaires (cœur pur)** : `JarvisScreenPolicyTest` — adresse de l'écran (`ecoute=1`), même origine (schéma, hôte,
  port, `userinfo`, `javascript:`), renouvellement, fermeture d'office, script de retour du déverrouillage (booléen
  seul). `./test-core.sh` et `./gradlew :app:testDebugUnitTest`.
- **Construction** : `./build-release.sh` (tests + `assembleRelease` + vérification v1 `jarsigner` et v2 `apksigner`).
- **Intégration** : la route `POST /api/runner/jarvis/session` est couverte côté gateway (SF-232-05) ; l'enchaînement
  complet se vérifie sur le téléphone du PO (aucun appareil sur la machine de livraison).
- **Isolation utilisateur** : la session porte le compte du compagnon (SF-232-05).

---

## Impacts

- **Android** : `WakeActivity` (réécrite), `CompanionService` (`ACTION_JARVIS_OPENED` / `ACTION_JARVIS_CLOSED`,
  écran éteint pendant l'écran Jarvis), `AnnouncementClient.session`, `core/JarvisScreenPolicy` (nouveau),
  `AndroidManifest` (thème, `configChanges`), `gradle.properties` (version 2), `README`.
- **Préoccupations transversales** :
  - [x] **Auth / Principal** — le jeton dédié reste natif ; la page ne reçoit qu'une session à portée (SF-232-05).
    Composants : `WakeActivity` (pont), `AnnouncementClient`, `Prefs` (jeton inchangé).
  - [ ] Contexte tenant — sans objet.
  - [ ] Plans / limites — sans objet.
  - [x] **Navigation** — `/jarvis?source=compagnon&ecoute=1&compagnon=N` (avant : navigateur) ; navigation bornée à
    l'origine de la gateway.

---

## Hors périmètre

- Reconnaître la commande sur l'appareil (la parole reste traitée par l'écran Jarvis, comme dans la PWA).
- iPhone ; écran de verrouillage personnalisé ; widget.
