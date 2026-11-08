# Cadrage F-251 — Jarvis entend tout

**Demande PO du 2026-11-08**, après l'essai du 07/11 jugé « une horreur » : « il n'entend qu'une fois sur trois, il
coupe ».

**Périmètre** : l'écoute mains libres de l'écran `/jarvis` (navigateur et compagnon). **Aucune consigne ni aucun modèle
touché** (bancs Jarvis non requis, V8). Hors périmètre : la latence de réponse (F-252), l'affichage (F-253).

## 1. Preuve (essai réel du 07/11, 16:13–16:20 UTC, `jarvis_listen_traces`, 282 événements, 21 écoutes)

Environ **6 écoutes sur 21 perdues**. Causes établies sur la trace et dans le code :

| # | Cause | Preuve (seq de la trace) | Code |
|---|---|---|---|
| 1 | **Micro rouvert pendant que Jarvis parle** (3 cas). Course A : une phrase dite hors du tour démarre juste avant la fin du tour ; la fin du tour remet « repos » sans la voir et arme la réécoute. Course B : `say()` démarre pendant l'ouverture du micro ; `openListening` passe à « écoute » sans condition. | A : 190-200, 230-239 ; B : 123-128 | `jarvis.component.ts` fin de tour (`state.set('repos')`), `say()`, `openListening` |
| 2 | **≈ 1,05 s de surdité à chaque réouverture** (0,25 s d'attente + ≈ 0,78 s : ticket, micro, WebSocket, module audio) ; rien n'est gardé avant `session_started` : le début des phrases est perdu. | `relisten_arm` → `state repos>ecoute`, 21 fois | `startStreaming`, `JarvisStreamOpener.open` |
| 3 | **Rejet à tort `no_voice`** : une vraie phrase de 20 caractères jetée (96 ms de voix mesurée) ; aucun signe pour le PO. | 66-69 | `jarvis-speech-gate.ts` (`MIN_VOICED_MS`, `NO_VOICE_MAX_WORDS`) |
| 4 | **Fin de tour trop tôt** : la fin de tour du fournisseur (1,2 s de silence) clôt la demande au premier commit, sauf phrase « en suspens ». Le banc SF-239-10 interdit de baisser ce seuil. | écoutes coupées en deux (ex. 173-188) | `onCommitted` → `finishListening` |
| 5 | **Interruption impossible** : `vad_fail TypeError` dès la première parole ; la détection est alors abandonnée pour toute la page. | 16 | `startVad` (`vadFailed` définitif) |
| 6 | **Journal muet** sur ces points : rien ne dit qu'une phrase a été dite pendant l'écoute, ce que le tampon a envoyé, si la fin de tour a été prolongée, ni où la détection de voix a échoué. | — | `jarvis-listen-trace.ts` |

## 2. Correctifs retenus

| # | Correctif | Garde-fou |
|---|---|---|
| 1 | **Compteur de phrases en cours** : pas de « repos » tant qu'une phrase est dite ou que la file de voix joue ; `say()` annule la réécoute armée et l'ouverture en cours (et clôt une écoute ouverte) ; `openListening` abandonne si Jarvis parle. | aucune réécoute sous la voix de Jarvis |
| 2 | **Micro et module audio ouverts une fois par conversation** ; **tampon circulaire de 1,5 s** envoyé en tête de chaque session (mécanisme `sendPreRoll` existant) ; **ticket demandé pendant que Jarvis parle**. Fermés à la pause, à la veille et à la sortie. | anti-écho : le tampon est **purgé pendant la parole de Jarvis** (et 150 ms après) — jamais la voix de Jarvis renvoyée |
| 3 | **Porte de parole** : un texte rendu par la retranscription complète (≥ 2 mots) n'est plus rejeté pour `no_voice` (`empty`, `hallucination`, `noise`, `implausible` gardés). Une écoute rejetée **alors que de la voix a été entendue** (≥ 400 ms) : Jarvis dit « Pardon, Monsieur ? » — au plus une fois par 30 s, jamais deux fois de suite. | les hallucinations connues restent refusées par leur texte |
| 4 | **Délai de grâce de 0,7 s après la fin de tour du fournisseur** : si la parole reprend (texte provisoire non vide), la même demande continue (généralisation de `holdIfSuspended`). Le seuil de 1,2 s n'est pas touché. | latence ajoutée ≤ 0,7 s, jamais plus ; la fin de tour confirmée = après la grâce (point d'appui de F-252/F) |
| 5 | **Détection de voix réparée** : l'échec est tracé avec son étape (chargement, modèle, micro, démarrage), son nom, son message technique et le haut de la pile ; la détection écoute le **micro de la conversation** (plus de second `getUserMedia`) ; un échec n'est plus définitif (3 essais par page). | — |
| 6 | **Journal enrichi** (liste fermée, jamais de texte du PO) : `say_during_listen`, `listen_open_drop`, `mic_keep`, `preroll ms=`, `ticket_ready`, `grace_start` / `grace_resume` / `grace_end`, `speech_rejected … voiced=`, `pardon`, `vad_fail <étape> …`, `vad_ok`. | aucune migration (événements libres `[a-z0-9_]`) |

## 3. Découpage (chaque subfeature ≤ 2 j)

| SF | Titre | Contenu |
|---|---|---|
| SF-251-01 | Jarvis ne s'écoute plus parler | cause 1 + traces `say_during_listen`, `listen_open_drop` |
| SF-251-02 | Le micro reste ouvert | cause 2 + traces `mic_keep`, `preroll`, `ticket_ready` |
| SF-251-03 | « Pardon, Monsieur ? » | cause 3 + traces `speech_rejected voiced=`, `pardon` |
| SF-251-04 | Laisser finir | cause 4 + traces `grace_*` |
| SF-251-05 | Se laisser couper | cause 5 + traces `vad_fail <étape>`, `vad_ok` |

## 4. Décisions

- **D1** — Aucune migration : `jarvis_listen_traces` accepte tout nom d'événement `[a-z0-9_]{1,40}` et un détail de
  120 caractères en liste blanche.
- **D2** — Le délai de grâce et le tampon sont des constantes réglables en test (jetons d'injection), pas des réglages
  utilisateur.
- **D3** — Le message d'erreur de la détection de voix entre au journal (tronqué, liste blanche) : il ne porte jamais de
  parole (la détection ne manipule que du son).
- **D4** — Coordination F-252 : la « fin de tour confirmée » est l'appel à `finishListening`, **après** le délai de
  grâce ; l'anticipation de F-252 ne libère rien avant.

## 5. Vérification après déploiement

Requêtes SQL sur `jarvis_listen_traces` dans chaque mini-spec et dans le bilan de la feature : écoutes ouvertes pendant
la parole (doit être 0), `preroll` envoyé, `grace_resume`, `speech_rejected` par motif, `pardon`, `vad_fail`.
