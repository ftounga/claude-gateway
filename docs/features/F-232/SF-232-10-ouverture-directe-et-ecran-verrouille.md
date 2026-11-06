# Mini-spec — F-232 / SF-232-10 — Jarvis s'ouvre vraiment : téléphone verrouillé, et directement téléphone en main

## Identifiant

`F-232 / SF-232-10`

## Feature parente

`F-232` — Le compagnon Android de Jarvis (correctif de SF-232-08/09, essai réel du PO le 2026-11-06, Samsung SM-S918B,
compagnon 0.3.0)

## Statut

`in-review`

## Date de création

2026-11-07

## Branche Git

`feat/SF-232-10-ouverture-directe`

---

## Objectif

> Au mot « Jarvis », l'écran Jarvis du compagnon s'ouvre et reste ouvert, téléphone verrouillé comme déverrouillé —
> sans bannière « Toucher pour ouvrir Jarvis » quand le PO a autorisé l'ouverture directe.

## Constat (prod, 2026-11-06)

1. **Verrouillé** : l'écran essaie de s'allumer, reste noir, clignote ; rien ne s'ouvre. `jarvis_companion_openings`
   n'a AUCUNE ligne pendant ces essais (seules 22:07 `locked=t` et 23:22 `locked=f`) : `WakeActivity` se ferme avant
   que la page ne demande sa session. Cause lue dans le code : `android:noHistory="true"` au manifeste ET `finish()`
   dans `onStop()`. Lancée par l'intention plein écran par-dessus le verrou, l'activité passe par `onPause`/`onStop`
   pendant la transition du keyguard et de l'allumage : elle se ferme aussitôt, puis l'intention la relance
   (clignotement).
2. **Déverrouillé** : Android ne lance une intention plein écran que écran éteint ou verrouillé ; sinon il affiche
   une bannière. Le PO veut l'ouverture directe « comme OK Google ».

## Comportement attendu

### Cas nominal

1. Manifeste : `noHistory` retiré de `WakeActivity` ; permission `SYSTEM_ALERT_WINDOW` déclarée.
2. `WakeActivity` ne se ferme plus dans `onStop()`. Elle se ferme :
   - à l'extinction de l'écran (`ACTION_SCREEN_OFF`, récepteur enregistré dynamiquement par l'activité) ;
   - au bouton accueil (`onUserLeaveHint`) ;
   - au retour (comportement par défaut) ;
   - à la demande de la page (`JarvisCompanion.close()`, inchangé) ;
   - après `IDLE_CLOSE_MS` sans signe de vie (`idleCheck`, inchangé) ;
   - filet : restée **arrêtée** (invisible) plus de `STOPPED_CLOSE_MS` = 10 s d'affilée (une transition du keyguard
     dure moins d'une seconde ; au-delà, le micro ne doit pas rester tenu par un écran invisible).
   Aucune de ces fermetures pendant la demande de déverrouillage (`unlocking`, inchangé).
3. Écran de réglage (étape « 2. Les autorisations ») : une ligne d'explication et un bouton « Autoriser l'ouverture
   directe de Jarvis » qui ouvre `Settings.ACTION_MANAGE_OVERLAY_PERMISSION` pour le paquet ; une fois accordée, le
   bouton dit « Ouverture directe autorisée ».
4. `CompanionService.showWake()` : si `Settings.canDrawOverlays(this)` → `startActivity(WakeActivity)` directement
   (lancement d'activité depuis l'arrière-plan permis par cette autorisation), verrouillé comme déverrouillé ; aucune
   notification n'est publiée. Sinon : comportement actuel (intention plein écran, ou bannière).
5. Pas de double ouverture : `WakeActivity` reste `singleInstance` ; la notification n'est publiée en repli que si
   l'écran ne s'est pas ouvert (`JARVIS_OPENED` non reçu) `DIRECT_OPEN_CHECK_MS` = 2 s après le lancement direct.
6. La décision « direct / plein écran / bannière » et les délais vivent dans le cœur pur (`core/WakeLaunchPolicy`),
   testé sans Android.
7. APK `versionCode=4`, `versionName=0.4.0`, même clé de signature (empreinte `48da5ad8…`).

### Cas d'erreur

- Autorisation d'ouverture directe non accordée : bannière / plein écran comme avant (rien ne casse).
- Lancement direct refusé par le système (exception) ou bloqué en silence : la notification de repli est publiée
  après 2 s (étape 5).
- Écran éteint pendant la demande de déverrouillage : l'activité reste (la demande la tient), comme avant.
- Le service est arrêté (Quitter) : rien n'est lancé (inchangé).

## Critères d'acceptation

- [ ] Le manifeste de l'APK construit ne contient plus `noHistory` pour `WakeActivity` et déclare
  `SYSTEM_ALERT_WINDOW`.
- [ ] `WakeActivity.onStop()` n'appelle plus `finish()` ; extinction de l'écran, accueil, retour, page, inactivité et
  arrêt prolongé (> 10 s) ferment l'écran.
- [ ] `WakeLaunchPolicy` : direct si l'autorisation est accordée (verrouillé ou non) ; plein écran si elle ne l'est
  pas et que le plein écran est permis ; bannière sinon ; testé.
- [ ] Bouton « Autoriser l'ouverture directe de Jarvis » présent dans l'étape 2, avec son explication.
- [ ] Build signé : `version.properties` = 4 / 0.4.0, empreinte du certificat inchangée (`48da5ad8…`).
- [ ] Essai réel du PO (après déploiement) : « Jarvis », téléphone verrouillé → l'écran Jarvis s'ouvre et écoute ;
  téléphone en main, autorisation accordée → Jarvis s'ouvre sans toucher.

## Plan de test minimal

- Unitaires (cœur pur, `test-core.sh`) : `WakeLaunchPolicyTest` (choix du lancement ×4, fermeture sur arrêt prolongé
  et pendant le déverrouillage, repli après lancement direct).
- Build Android signé (image Docker, `docker-signed.sh`) : compile, signe, `version.properties` = 4, empreinte.
- Isolation utilisateur : aucune donnée nouvelle, aucun endpoint nouveau (sans objet).

## Impacts

- Android : `AndroidManifest.xml`, `WakeActivity.java`, `CompanionService.java`, `MainActivity.java`,
  `core/WakeLaunchPolicy.java` (+ test), `gradle.properties`.
- Aucune table, aucun endpoint, aucun écran web.
- Préoccupations transversales : aucune (auth, tenant, plans, routing inchangés ; la session `jarvis-compagnon` est
  émise comme avant).

## Contraintes de validation

- `STOPPED_CLOSE_MS` = 10 000 ms ; `DIRECT_OPEN_CHECK_MS` = 2 000 ms (tranchés ici, réglables dans le cœur).

## Hors périmètre

- La page `/jarvis` (écoute en flux, réécoute, mesure) : SF-233-13.
- Une fenêtre flottante (overlay) dessinée par le compagnon : l'autorisation sert seulement au lancement d'activité.
