# Cadrage F-217 — Les notifications qui sonnent

**Demande PO du 2026-10-25** : « Toutes les actions qui génèrent des notifications au niveau du navigateur, je veux qu'il y ait un son dessus. Je loupe à chaque fois les confirmations, les notifications. Si les notifications, sur téléphone ou sur ordinateur, peuvent faire un bruit, je suis preneur. » Consigne : suivre les préconisations (diagnostic d'abord, notifications système sonores, son dans l'application, préférences, téléphone).

## Diagnostic — pourquoi le PO n'entend rien aujourd'hui (preuves au 2026-10-25, `origin/main` 7fb955f9)

### Ce qui notifie aujourd'hui
| Événement | Web Push (système) | Dans l'application ouverte |
|---|---|---|
| Accord demandé — poste (`AtelierChatService.askPermission` → `notifyAuthorizationRequested`) | oui, **sauf terminal regardé** | invite dans le flux ; titre d'onglet figé « ● Autorisation demandée » **si caché** ; **aucun son** |
| Question structurée (`askQuestion` → `notifyQuestionAsked`) + rappel 2 min avant l'échéance | oui (rappel toujours émis) | carte ; titre « ● Question en attente » si caché ; **aucun son** |
| Fin de tour : réponse prête, plan, validation, continué sans vous, travail arrêté, poste perdu (`notifyingOutcome` → `TurnOutcomes`) | oui, sauf terminal regardé | titre « ● Réponse prête » / « ● Plan à approuver » si caché ; **aucun son** |
| Routines (F-183), récap des attentes (F-185/F-180) | oui | cloche (relevé 30 s, onglet visible) ; bandeau ; **aucun son** |
| Tâche d'un autre sujet du poste terminée (bande du Fil, F-191) | non | son de fin de tâche **coupé par défaut** (`FinishSoundService`, réglage par appareil) |

### Ce qui empêche le son (causes établies)
1. **Aucun son dans l'application.** Le seul son existant (`finish-sound.service.ts`, F-191) est **coupé par défaut** et ne sert qu'à la bande du Fil. Accord, question, fin de tour et cloche n'émettent aucun son. Or quand le terminal est **regardé** (visible + au focus + geste < 2 min, `isWatched`, `live-terminal.service.ts` l.34), le Web Push est **volontairement supprimé** (D7 de F-153, `PushNotificationService.deliver` : `if (watched …) return`) : l'utilisateur assis devant l'écran mais qui regarde ailleurs (téléphone, autre écran sans focus le temps de l'inactivité) n'a **ni notification système ni son** — rien que l'invite muette dans le flux. C'est le cas « je loupe les confirmations ».
2. **Le signal d'onglet est muet et figé** (`tab-alert.service.ts`) : un préfixe de titre fixe, levé seulement onglet caché ; pas de clignotement — un onglet parmi vingt ne se remarque pas.
3. **La charge Web Push ne dit rien du son** (`PushNotificationService.buildPayload`) : pas de `silent: false` explicite, pas de `requireInteraction` (la notification d'accord disparaît après quelques secondes sur macOS/Windows), pas de `vibrate`, pas d'horodatage. **Le `tag` est unique par terminal** (`cg-<terminal>`) : chaque nouvel événement du même terminal **remplace** le précédent. `renotify: true` le fait re-sonner sous Chrome, **mais `renotify` n'existe ni dans Safari (macOS, iPhone) ni dans Firefox** : chez eux un remplacement de même `tag` est **silencieux** — le 2e accord d'un tour ne sonne pas sur iPhone.
4. **Aucune urgence Web Push** (`MartijndwarsWebPushTransport.send` : ni en-tête `Urgency`, ni `TTL` → urgence `normal`, TTL 28 jours par défaut de la bibliothèque). Un Android en économie d'énergie peut **différer** une urgence `normal` ; un accord expiré (2 min, `APP_ATELIER_AGENT_CONFIRM_TIMEOUT`) peut s'afficher des heures plus tard.
5. **Moteur hébergé muet** : l'exécution sur le terminal hébergé (`AtelierAgentController.relay`, Managed Agents) publie `confirm_request` et `done` dans le flux **sans aucun Web Push** — ni accord, ni fin de tour.
6. **Le son système dépend du système** : même une notification parfaite est muette si le son des notifications est coupé pour le navigateur / la PWA dans les réglages de macOS, Windows, Android ou iOS, ou en mode Concentration / Ne pas déranger. Aucun écran ne l'explique aujourd'hui.

### Ce qui fonctionne et reste tel quel
- **Onglet ouvert mais caché** : le Web Push part bien — `isWatched` exige `visibilityState === 'visible'` **et** `hasFocus()` (testé : `live-terminal.service.spec.ts` l.243-244) ; tout changement de visibilité/focus envoie aussitôt un battement `watched=false` (l.119-121) ; la présence se périme en 45 s côté serveur (`WATCH_FRESHNESS`).
- **Le service worker n'étouffe rien** : `ngsw-worker.js` (`handlePush`) affiche toujours la notification, qu'un client ait le focus ou non, et recopie tels quels `silent`, `requireInteraction`, `vibrate`, `renotify`, `tag`, `timestamp` (liste `NOTIFICATION_OPTION_NAMES`). **Aucun service worker maison n'est nécessaire.**
- **Rendu vidéo fini** : le rendu `video_render` est détaché et suivi par l'agent dans un tour ; sa fin se notifie par la **fin du tour** qui le suit (« Une réponse est prête »). Aucun événement vidéo dédié (F-216 livre le coût des vidéos en parallèle : on ne touche à rien de vidéo).

## Limite à dire honnêtement
Un site web **ne peut pas imposer un son personnalisé** à une notification système : le son est celui du système (macOS, Windows, Android, iOS), et il doit être **autorisé pour le navigateur ou la PWA** dans les réglages du système. L'application peut seulement (a) demander une notification non silencieuse, persistante et urgente, et (b) jouer **son propre carillon quand elle est ouverte**. Un guide par plateforme est ajouté aux préférences.

## Décisions
- **D1 — Charge Web Push sonore.** `silent: false` explicite ; `requireInteraction: true` pour ce qui attend une action (accord, question, rappel, plan, validation) ; `vibrate` (motif marqué pour l'action, court sinon) ; `timestamp`. `renotify: true` avec un **`tag` par événement** : `cg-<terminal>-<EVENEMENT>` pour l'information ; pour ce qui attend une action, **un tag par occurrence** (`…-<horodatage>`) afin que **Safari et Firefox** (sans `renotify`) sonnent aussi au 2e accord. Charge toujours neutre (D4 F-153).
- **D2 — Urgence et durée de vie.** `Urgency: high` et `TTL` 15 min pour accord / question / rappel (passé ce délai, la demande a expiré) ; `high` + TTL 24 h pour plan et validation ; `normal` + TTL 24 h pour le reste.
- **D3 — Moteur hébergé.** Accord demandé et fin de tour du terminal hébergé émettent le même Web Push que le poste.
- **D4 — Notification système de test.** `POST /api/push/test` : envoie une notification de test aux seuls appareils du compte (`user_id`), hors cloche, hors anti-doublon et hors préférences ; rend le nombre d'appareils visés. Envoi asynchrone.
- **D5 — Carillon dans l'application.** Généré en Web Audio (aucun fichier, aucune dépendance, aucun CDN). Deux sons : **« action requise »** (trois notes marquées, **rejoué une fois après 30 s** si la demande attend toujours) et **« information »** (deux notes douces). Joué sur accord, question, plan à approuver, fin de tour (et validation, arrêt), nouvelle notification de la cloche (action si critique, information sinon), tâche du Fil terminée. Joué **même terminal regardé** (c'est précisément le cas où le Web Push se tait) et onglet caché. Audio **débloqué au premier geste** de l'utilisateur sur la page (règle des navigateurs) ; avant, silence sans erreur. Anti-rafale : pas deux carillons à moins de 2 s.
- **D6 — Onglet qui clignote.** Tant que l'onglet est caché, le titre alterne « ● Accord requis » / titre d'origine chaque seconde (libellés : Accord requis, Question en attente, Plan à approuver, Réponse prête) ; tout est rétabli au retour.
- **D7 — Fermer les notifications périmées.** Quand le terminal redevient regardé, l'écran ferme les notifications système de **ce** terminal encore affichées (`registration.getNotifications`) — sinon les notifications persistantes (D1) s'empileraient.
- **D8 — Préférences du son, par appareil** (comme le son F-191, un son est un comportement de l'appareil) : **son activé par défaut** (décision PO), **volume**, réglage **par catégorie** (« action requise », « information »), bouton **« Tester le son »** qui joue le carillon **et** envoie la notification système de test (D4) en disant le résultat. Le réglage « son de fin de tâche » de F-191 est absorbé (catégorie « information »). Stockage local de l'appareil : aucune table, aucune migration.
- **D9 — Guide par plateforme** dans les préférences : macOS, Windows, Android, iPhone/iPad (PWA installée sur l'écran d'accueil, iOS 16.4+, Réglages › Notifications › l'app › Sons), et le mode Concentration / Ne pas déranger.
- **D10 — Téléphone** : même comportement en PWA Android et iOS 16.4+ installée (la charge D1/D2 est la même) ; `vibrate` ignoré par iOS.

## Préoccupations transversales
- **Contexte tenant** : `POST /api/push/test` ne lit que les abonnements de `currentUser.requireId()` ; test d'isolation (un autre compte ne reçoit rien). Moteur hébergé : `userId`/`workspaceId` du tour, déjà possédé (`requireOwned` en amont).
- **Plans / limites** : aucun. **Auth / navigation** : aucun changement (endpoint authentifié comme les autres `/api/push/**`).
- **Préfixe stable** : rien ne touche la consigne système ni le modèle.

## Découpage
| SF | Objet | Risque |
|---|---|---|
| SF-217-01 | Backend : charge Web Push sonore (D1), urgence et TTL (D2), moteur hébergé (D3), notification de test (D4) ; tests | 🟢 |
| SF-217-02 | Front : carillon deux sons + rejeu 30 s + déblocage au premier geste (D5), onglet qui clignote (D6), fermeture des notifications périmées (D7), branchements terminal / cloche / Fil ; tests Karma | 🟢 |
| SF-217-03 | Front : préférences du son (D8) + « Tester le son » + guide par plateforme (D9) ; tests Karma | 🟢 |

## Hors périmètre
Son personnalisé des notifications système (impossible pour un site web) ; application native ; préférences du son par compte synchronisées entre appareils ; nouvel événement « rendu vidéo fini » (F-216 en cours, couvert par la fin de tour) ; changement de la règle D7 (le Web Push reste tu quand le terminal est regardé : le carillon prend le relais).
