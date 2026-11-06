# Mini-spec — F-232 / SF-232-09 — Le micro de l'écran Jarvis dans le compagnon, et la reprise après mise à jour

## Identifiant

`F-232 / SF-232-09`

## Feature parente

`F-232` — Le compagnon Android de Jarvis (correctif de SF-232-08, essai réel du PO le 2026-11-06)

## Statut

`in-review`

## Date de création

2026-11-06

## Branche Git

`feat/SF-232-09-micro-compagnon`

---

## Objectif

> Ouvert au mot « Jarvis », l'écran Jarvis du compagnon obtient le micro (au lieu de « Le micro est refusé : autorise-le
> dans le navigateur ») ; et après une mise à jour de l'APK, l'écoute du mot ne reste plus silencieusement arrêtée.

## Constat (prod, 2026-11-05/06)

1. Après l'installation de la v2 (5/11 ~23 h), le compagnon n'a plus contacté la gateway pendant ~24 h (relève toutes
   les 20 s quand il écoute) : la mise à jour arrête le service, et rien ne le relance (seul `BOOT_COMPLETED` est écouté).
2. Écoute relancée à la main : l'écran s'ouvre (1re ligne `jarvis_companion_openings` le 6/11 22:07 UTC), mais la page
   affiche « Le micro est refusé ». Le manifeste ne déclare pas `MODIFY_AUDIO_SETTINGS`, exigé par la WebView Chromium
   pour la capture audio (`getUserMedia` échoue alors même si `RECORD_AUDIO` est accordé et la demande de la page
   acceptée). Toute erreur de `getUserMedia` est affichée comme un « refus », ce qui masque la cause.

## Comportement attendu

### Cas nominal

1. Manifeste : `android.permission.MODIFY_AUDIO_SETTINGS` (permission normale, accordée à l'installation).
2. `BootReceiver` écoute aussi `MY_PACKAGE_REPLACED` : même geste qu'au redémarrage (Android 14+ interdit de démarrer
   un service micro depuis un broadcast), notification « Jarvis a été mis à jour : toucher pour qu'il écoute à
   nouveau », publiée seulement si l'écoute était active.
3. APK `versionCode=3`, `versionName=0.3.0` (même clé de signature) : la gateway propose la mise à jour.
4. Page `/jarvis` ouverte dans le compagnon : un échec du micro affiche sa cause technique
   (« Le micro n'a pas pu s'ouvrir dans le compagnon (NotReadableError). »). Hors compagnon : message inchangé.

### Cas d'erreur

- `RECORD_AUDIO` retiré par le PO : la WebView refuse toujours (inchangé), le message montre `NotAllowedError`.
- Erreur sans nom (navigateur exotique) : « (inconnue) ».

## Critères d'acceptation

- [ ] Le manifeste de l'APK construit contient `MODIFY_AUDIO_SETTINGS`.
- [ ] `BootReceiver` réagit à `MY_PACKAGE_REPLACED` et ignore toute autre action que boot/replace.
- [ ] `/api/runner/download/android/version` sert `versionCode` 3 après déploiement.
- [ ] Dans le compagnon, le message d'échec du micro porte le nom de l'erreur ; ailleurs il est inchangé.
- [ ] Essai réel du PO : « Jarvis », téléphone verrouillé → l'écran Jarvis écoute la commande.

## Plan de test minimal

- Unitaire front : `companionMicMessage` (nom présent / absent) ; `DictationService` retient le nom de l'erreur.
- Build Android (`build-release.sh` via l'image) : compile, signe, `version.properties` = 3.
- Isolation utilisateur : aucune donnée nouvelle, aucun endpoint nouveau (sans objet).

## Impacts

- Android : `AndroidManifest.xml`, `BootReceiver.java`, `gradle.properties`.
- Front : `dictation.service.ts`, `jarvis.component.ts`.
- Aucune table, aucun endpoint.
- Préoccupations transversales : aucune (auth, tenant, plans, routing inchangés).

## Hors périmètre

- Redémarrer le service micro sans geste après mise à jour (interdit par Android 14+).
- Changer la source audio de la page ou le moteur d'écoute.
