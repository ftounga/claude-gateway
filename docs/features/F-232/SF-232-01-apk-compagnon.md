# Mini-spec — F-232 / SF-232-01 — L'APK compagnon : écouter « Jarvis » téléphone verrouillé

## Identifiant

`F-232 / SF-232-01`

## Feature parente

`F-232` — Le compagnon Android de Jarvis (cadrage `CADRAGE-F-232-compagnon-android.md`)

## Statut

`in-review`

## Date de création

2026-11-01

## Branche Git

`feat/SF-232-01-apk-compagnon`

---

## Objectif

> Un petit APK Android, installé hors store, qui écoute « Jarvis » **sur l'appareil** même téléphone
> verrouillé et ouvre alors la PWA en mode Jarvis (`/jarvis`, F-231) — sans jeton, sans rien envoyer.

---

## Comportement attendu

### Cas nominal

1. **Premier lancement** (`MainActivity`) : l'écran explique puis demande, une par une,
   les permissions micro, notifications (Android 13+), l'exemption d'optimisation de batterie et
   l'usage des notifications plein écran (Android 14+). L'adresse de la gateway est pré-remplie
   (`https://portal.ng-itconsulting.com`), modifiable.
2. **Apprentissage** : le PO dit « Jarvis » trois fois. Même moteur que SF-231-05 (D5), **porté en
   Java** : empreinte MFCC (12 coefficients, trames 25 ms / pas 10 ms, 26 bandes mel), alignement
   DTW, seuil = écart maximal entre gabarits × 1,35. Les gabarits restent dans les préférences
   privées de l'application. Aucun modèle téléchargé, aucune licence.
3. **Écoute** (`CompanionService`, service de premier plan `foregroundServiceType="microphone"`) :
   notification permanente « Jarvis écoute » ; `AudioRecord` 16 kHz mono, source
   `VOICE_RECOGNITION` ; découpe des mots isolés (plancher de bruit adaptatif, 0,2 s à 1,5 s, 0,25 s
   de calme) ; chaque mot est comparé aux gabarits. **Aucun son, aucun octet ne sort de l'appareil.**
4. **Au mot** : le service relâche le micro (la PWA en aura besoin pour la dictée F-145) et publie
   une notification à **intention plein écran** qui lance `WakeActivity`
   (`showWhenLocked` + `turnScreenOn` + demande de déverrouillage). Une fois déverrouillé,
   `WakeActivity` ouvre `https://<gateway>/jarvis?source=compagnon` (lien profond F-231 ; la PWA
   installée — WebAPK — capte l'URL dans son périmètre, sinon Chrome l'ouvre).
5. **Reprise** : l'écoute reprend quand l'écran s'éteint (le PO a fini) ou au plus tard 10 min après
   le mot.
6. **Interrupteur (D3)** : action « Pause » / « Reprendre » dans la notification, « Quitter » qui
   arrête le service. **Seuil batterie** : sous 15 % hors charge, l'écoute se met en pause d'elle-même
   (notification « En pause : batterie faible ») ; elle reprend en charge ou au-dessus de 20 %
   (hystérésis, pas de bascule continue autour du seuil).
7. **Démarrage avec le téléphone** : Android 14+ interdit de démarrer un service micro depuis
   `BOOT_COMPLETED` ; le récepteur de démarrage publie donc une notification « Toucher pour
   réactiver Jarvis » (un seul geste) au lieu de démarrer le micro dans le dos du système.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Permission micro refusée | Le service ne démarre pas ; l'écran l'explique et propose les réglages |
| Aucun gabarit appris | Le bouton « Démarrer l'écoute » reste désactivé |
| Micro occupé / `AudioRecord` non initialisé | Notification « Micro indisponible », nouvel essai à 30 s, jamais de boucle serrée |
| Notification plein écran refusée (Android 14+) | Au mot, une notification haute priorité « Jarvis t'écoute — toucher pour ouvrir » est publiée à la place |
| Batterie < 15 % hors charge | Pause automatique, reprise à > 20 % ou en charge |
| Adresse gateway invalide (pas `https://`) | Refusée à la saisie (seul `http://localhost` / `10.0.2.2` toléré pour le développement) |

---

## Critères d'acceptation

- [x] Les sources du projet Android sont versionnées sous `android/jarvis-companion/` (Gradle, Java 17, minSdk 26, targetSdk 34).
- [x] Le moteur de mot d'éveil est **pur Java** (aucun `android.*`) et reproduit SF-231-05 : MFCC, DTW, seuil × 1,35, découpe des mots.
- [x] Service de premier plan `microphone`, notification permanente, actions Pause/Reprendre/Quitter.
- [x] Politique batterie pure et testée : pause < 15 % hors charge, reprise > 20 % ou en charge.
- [x] Le lien ouvert est `<gateway>/jarvis?source=compagnon` ; seule une adresse `https://` est acceptée (sauf hôtes de développement).
- [x] **Aucun jeton, aucun secret, aucune requête réseau** dans SF-232-01 (le manifeste ne demande même pas `INTERNET` ; l'ouverture passe par le navigateur).
- [x] Tests unitaires du cœur exécutés hors SDK (javac + JUnit 4) et verts.
- [ ] **NON VÉRIFIÉ** : compilation Gradle, APK, essai sur téléphone — aucun SDK Android sur la machine ni en CI (voir « Limites »).

---

## Contraintes de validation

| Champ | Règle |
|---|---|
| Adresse gateway | `https://` obligatoire ; `http://` seulement pour `localhost`, `127.0.0.1`, `10.0.2.2` ; slash final retiré |
| Apprentissage | exactement 3 répétitions ; mot entre 20 et 150 trames (0,2 à 1,5 s) |
| Seuils batterie | pause < 15 %, reprise ≥ 20 % (constantes) |

---

## Plan de test minimal

- **Unitaires (JUnit 4, hors SDK)** : `WakeEngineTest` (MFCC dimensions et centrage, DTW identité = 0 et symétrie, seuil, rejet d'un mot trop court, reconnaissance d'un mot proche et rejet d'un signal différent sur signaux synthétiques), `WordSegmenterTest` (mot isolé détecté, phrase trop longue ignorée, bruit de fond), `WakeListenerTest` (flux PCM découpé en paquets → un mot reconnu), `BatteryPolicyTest` (hystérésis), `JarvisLinkTest` (https exigé, slash final, hôtes de dev), `WakeProfileCodecTest` (aller-retour).
- **Intégration** : impossible sans SDK — à faire sur appareil (procédure dans `android/jarvis-companion/README.md`).
- **Isolation utilisateur** : sans objet — l'APK ne porte aucun jeton et n'accède à aucune donnée ; la session reste celle de la PWA (isolation `user_id` inchangée côté serveur).

---

## Impacts

- **Tables** : aucune. **Endpoints** : aucun. **Frontend** : aucun (le lien profond `/jarvis` existe, F-231).
- **Préoccupations transversales** :
  - **Navigation** : ouverture de `/jarvis?source=compagnon`. Composant vérifié : `JarvisComponent` lit seulement `sujet` et `alerte` dans l'URL — le paramètre `source` est ignoré, aucune régression ; la garde d'authentification de la route renvoie à la connexion si la session PWA a expiré (comportement existant).
  - **Auth** : non touchée (aucun jeton porté ; l'appairage par jeton dédié est SF-232-03).

---

## Hors périmètre

- Annonces dites téléphone verrouillé et jeton dédié (SF-232-03).
- Téléchargement, signature, mise à jour (SF-232-02).
- iPhone, montre, écoute envoyée à un service distant.

---

## Limites (constat d'environnement)

Aucune chaîne de build Android n'existe sur la machine de livraison ni en CI (pas de Gradle, `sdkmanager`,
`adb`, `ANDROID_HOME`). L'APK **n'a pas été compilé, signé ni essayé**. Pour le faire : SDK Android
(platform 34, build-tools 34), Gradle 8.7 (ou le wrapper généré par `gradle wrapper`), un keystore de
signature (hors dépôt), puis un job CI (voir README du projet).
