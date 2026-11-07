# Mini-spec — F-233 / SF-233-16 — La boucle d'écoute ne s'arrête plus en silence (+ visage et version dans le compagnon)

## Statut
`in-review` — 2026-11-07 — branche `feat/SF-233-16-ecoute-sans-arret-silencieux`

## Objectif
> Corriger les arrêts silencieux de la boucle mains libres établis par l'audit du 2026-11-07, afficher le visage F-250
> dans le compagnon, et y servir toujours la dernière version de la page.

## Constat (audit `docs/audits/AUDIT-2026-11-07-ecoute-jarvis.md`, §3 et §4)
Prouvé par les données : voix bloquée 46 s pour 6 mots (13:23) ; ancienne version servie par le service worker puis
rechargement entre deux tours (13:21–13:22) ; `GET /jarvis/face` hors portée compagnon (403 ⇒ cœur). Prouvé par le
code (mécanismes déterministes) : ouverture d'écoute sans borne (`listenOpening` figé), réécoute à déclencheur unique,
`keepAlive` seulement aux changements d'état (écran natif fermé après 3 min), pause muette sur micro refusé, annonce
dite micro ouvert, état « écoute » orphelin, `AudioContext` du niveau jamais repris.

## Comportement nominal
1. **Lecture bornée** (`JarvisVoiceService.play`) : jamais démarrée en 10 s, ou position figée 8 s ⇒ close en échec
   (`voice_stall` journalisé) ; un contexte audio suspendu est réveillé avant la lecture.
2. **Ouverture bornée** : micro 6 s (dictée et flux, pistes tardives coupées), ticket 6 s, module de capture 4 s ;
   libération du micro de la détection de voix attendue ≤ 800 ms avant de rouvrir.
3. **Chien de garde** (3 s, `JARVIS_LOOP_WATCHDOG_MS`) : signe de vie au compagnon tant que la conversation ou un
   tour tourne ; ouverture d'écoute > 15 s abandonnée ; « écoute » sans micro 2 passages ⇒ close ; conversation active au
   repos sans rien d'armé 2 passages ⇒ réécoute (ou veille si le silence a assez duré) ; rendu 3D/2D/visage journalisé.
4. **Micro refusé** : un second essai après 700 ms ; en échec, la pause est **dite** (« Je n'arrive pas à ouvrir le
   micro : je me mets en pause. Dites « Jarvis » pour reprendre. »).
5. **Annonce de délégation** pendant l'écoute : l'écoute est close avant de parler.
6. **Mise à jour** (SF-233-12) : jamais en pleine conversation (attend qu'elle soit en pause ou en veille).
7. **Compagnon** : aucun service worker dans la WebView (`window.JarvisCompanion`) ; l'existant est désinscrit et la
   page servie par lui rechargée une fois (garde de session) ⇒ toujours la dernière version déployée.
8. **Visage** : `GET /jarvis/face` ajouté à la portée `jarvis-compagnon` (lecture d'un booléen).

## Cas d'erreur
- Le second essai du micro échoue aussi ⇒ pause dite, avis écrit (cause technique dans le compagnon).
- Une ouverture abandonnée qui aboutit tard ⇒ ce qu'elle a ouvert est rendu (`listen_open_late`).
- Une phrase coupée par le chien de garde ⇒ elle reste écrite, la file suivante continue (`lastQueueError`).
- Service worker inaccessible dans le compagnon ⇒ la page démarre telle quelle.

## Critères d'acceptation
- [ ] Une lecture jamais démarrée / figée est close en échec et journalisée ; une lecture qui avance ne l'est jamais.
- [ ] Une promesse d'ouverture (micro, ticket, module) ne peut plus figer l'écoute.
- [ ] Conversation active, repos, rien d'armé ⇒ réécoute au 2ᵉ passage ; écoute orpheline ⇒ close ; ouverture figée ⇒
  abandonnée.
- [ ] Micro refusé : 2ᵉ essai, puis pause dite.
- [ ] `isQuiet()` faux en conversation active.
- [ ] Compagnon : service worker désinscrit, une recharge au plus ; hors compagnon inchangé.
- [ ] `GET /api/jarvis/face` répond 200 avec une session compagnon.

## Contraintes de validation
Bornes : lecture 10 s / 8 s ; micro 6 s ; ticket 6 s ; module 4 s ; ouverture 15 s ; chien de garde 3 s ; second essai
700 ms ; libération VAD 800 ms. Aucun champ saisi.

## Plan de test
- Unitaires : `jarvis-voice.service.spec` (3 : jamais démarrée, figée, avance), `timed.spec` (3),
  `companion-webview.spec` (4), `jarvis.component.spec` (6 : relance, orpheline, ouverture figée, micro ×2, `isQuiet`),
  `JarvisCompanionScopeTest` (+`GET /jarvis/face`).
- Intégration : `JarvisCompanionSessionApiIntegrationTest` (`/api/jarvis/face` 200 en session compagnon).
- Isolation utilisateur : inchangée (`/jarvis/face` ne lit aucune donnée de compte).

## Impacts
- Front : `jarvis.component.ts`, `jarvis-conversation.ts`, `jarvis-barge-in.ts`, `jarvis-mic-meter.ts`,
  `jarvis-stream-listener.ts`, `core/services/jarvis-voice.service.ts`, `core/services/dictation.service.ts`,
  `core/timed.ts` (nouveau), `core/companion-webview.ts` (nouveau), `main.ts`, `app.config.ts`.
- Back : `JarvisCompanionScope` (+1 route). Aucune table, aucune migration.

### Préoccupations transversales
- [x] **Auth / Principal** — `GET /jarvis/face` ouvert à la portée compagnon. Composants : `JarvisCompanionScope`,
  `JarvisCompanionSessionGuard`, `JwtAuthenticationFilter` (inchangés hors liste). Non-régression :
  `JarvisCompanionScopeTest`, `JarvisCompanionSessionApiIntegrationTest` (routes fermées toujours 403).
- [ ] Contexte tenant — non.
- [ ] Plans / limites — non.
- [x] **Navigation / chargement** — service worker retiré dans la WebView du compagnon seulement. Composants :
  `main.ts` (amorçage), `app.config.ts` (`provideServiceWorker`), `JarvisAppUpdate`, `VersionUpdateBanner`,
  `PushActivationService` (tous gardés par `isEnabled` ⇒ inertes dans la WebView, inchangés ailleurs).

## Hors périmètre
- Le compagnon natif (aucune nouvelle APK). Les annonces F-249 dans le compagnon (portée laissée fermée, audit §4).
- Une phrase sur parole refusée (`empty`) : à trancher sur pièces du journal. La clôture des sessions de flux entre pods
  (audit §5, coût).
- Aucune consigne du cerveau rapide ne change ⇒ bancs ORAL/CONVERSATION non requis (V8).
