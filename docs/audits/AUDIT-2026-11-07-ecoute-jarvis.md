# Audit — L'écoute mains libres de Jarvis s'arrête en silence (2026-11-07)

> Demande du PO (excédé) : « Jarvis répond à 1 ou 2 messages puis n'écoute plus, comme si le micro était coupé — de plus
> en plus souvent » (Samsung SM-S918B, compagnon Android 0.4.0 = WebView `/jarvis?source=compagnon&ecoute=1`), et « le
> visage F-250 n'apparaît pas sur le téléphone ».
>
> Livrables : ce document (A), le journal de l'écoute SF-233-15 (B), les corrections SF-233-16 (C).
>
> **Prouvé vs supposé.** Prouvé par les données : la voix bloquée (#1), la version périmée et le rechargement en
> conversation (#6), le visage (§4), les paroles refusées sans retour (#8). Prouvé par le code (mécanismes
> déterministes, corrigés car ce sont des défauts quoi qu'il arrive) : #2, #3, #4, #5, #9, #10. **Supposé** — lequel de
> ces points arrête la boucle chez le PO à chaque essai : c'est ce que le journal tranchera au prochain essai.

## 1. La boucle, telle que le code la déroule

```
mot « Jarvis » (natif)  →  WakeActivity + WebView /jarvis?ecoute=1  →  session compagnon (pont natif)
  → onWake() → startConversation() (mode 'active') → listen()
      → listenOpening = true → openListening()
          → startStreaming(): ticket POST /jarvis/stt-session → JarvisStreamOpener.open():
                getUserMedia → WebSocket (3 s) → AudioContext + audioWorklet.addModule
            sinon dictation.start(): getUserMedia → MediaRecorder
          → state 'ecoute' → MicMeter (AudioContext) → boucle requestAnimationFrame (niveau, fin de phrase)
      → fin : EndOfSpeech (silence après voix) | commit du fournisseur | plafond 60 s
          | en conversation, silence sans voix : batch → cancelListening() ; flux → on continue
  → finishListening() : 'reflexion' → retranscription (utterance) → porte de parole (speechGate)
  → handle() → cerveau rapide (converse) | Opus (send → streamChat)
  → voix : voice.enqueue()/speak() → <audio> (MediaSource) → 'parole' → onended
  → 'repos' → effet onConversationState() → relistenTimer (250 ms) → listen()   ← la réécoute
  → 60 s sans activité → endConversation() : « Je me mets en veille » + close() du compagnon
```

Points structurants :
- **La réécoute n'a qu'un seul déclencheur** : l'effet Angular sur le **signal** `state` / `conversation.mode`
  (`onConversationState`). `turnRunning`, `listenOpening`, `relistenTimer` sont des champs simples : si l'état est déjà
  `repos` quand l'un d'eux change, ou si `listen()` rend la main sans changer d'état, **plus rien ne relance l'écoute**.
  Aucun chien de garde.
- Dans le compagnon, l'écran natif se ferme seul après **3 min sans `keepAlive()`** ; `keepAlive()` n'était appelé
  qu'à un **changement d'état** (un tour Opus de plus de 3 min en `reflexion` = écran fermé, micro rendu, en silence).

## 2. Ce que disent les données de prod

| Source | Constat |
|---|---|
| `jarvis_companion_openings` × `jarvis_turn_timings`, nuit du 06→07/11 (00:04–00:09 UTC) | **7 ouvertures, 6 tours : jamais plus d'un tour par ouverture.** Le PO redit « Jarvis » 22 à 42 s après chaque réponse : la page n'écoutait plus, il a refermé et relancé (le service natif n'entend le mot que si l'écran Jarvis est fermé). |
| `jarvis_turn_timings` 07/11 13:23:05 | `CONV_BATCH`, `stream_fallback=ouverture_refusee` (corrigé par SF-233-14), réponse FAST de **6 mots**, `spoken_ms = 46 149` : **la file de la voix est restée bloquée ~44 s** de trop (normal : ~2 s pour 6 mots, cf. nuit : 1 976 ms / 5 mots). Pendant tout ce temps l'état reste `parole` : **pas d'écoute**. Puis plus aucun tour. |
| `jarvis_turn_timings` 07/11 13:21:59 | Tour sans `companion` ni `stream_fallback` (champs de SF-233-13, déployés **avant**) ⇒ la WebView exécutait **une ancienne version** de la page. |
| Journaux nginx (UA `SM-S918B … wv`) 13:21:50 → 13:22:11 | Page servie **par le service worker** (aucun `GET /jarvis`), puis téléchargement de la nouvelle version (≈110 morceaux), puis **rechargement à 13:22:11** (rechargement SF-233-12 « au repos »… en pleine conversation). |
| `jarvis_turn_timings` 04/11 22:33 et 07/11 13:21:59 | 4 paroles **refusées `empty`** (3,4 à 6,6 s de « voix » au micro, transcription vide) : le PO parle, **rien ne se passe, rien n'est dit**. |
| `jarvis_usage_days.stt_stream_seconds` | 1 080 s le 07/11 = 9 × 120 s : 9 sessions d'écoute en flux **jamais closes** (comptées au maximum par le balayage). Indice d'une ouverture restée en suspens **ou** d'une clôture partie vers un autre pod (sessions en mémoire, 2 à 3 pods ce jour-là, aucune affinité) — **non concluant** (§5). |
| Journaux backend | Aucun appel `/api` journalisé : **le serveur ne peut pas dire où la boucle s'arrête** ⇒ journal SF-233-15. |

## 3. Les points d'arrêt silencieux, classés par probabilité

« Silencieux » = la boucle s'arrête sans phrase de Jarvis, sans mesure, sans trace.

| # | Point d'arrêt | Preuve | Probabilité | Correction |
|---|---|---|---|---|
| 1 | **Voix sans borne** : `JarvisVoiceService.play()` ne se résout que sur `ended`/`error` ; un flux MediaSource qui cale, un `endOfStream` jamais appelé, un `<audio>` routé dans un `AudioContext` suspendu ⇒ `voice.idle()` ne se résout pas ⇒ état `parole` figé ⇒ pas de réécoute. `playClip` (formule de réveil) bloque aussi tout `speak()` suivant (`clipPlaying`). | **Données** : 46 s pour 6 mots (13:23). Code. | **Élevée** | SF-233-16 : chien de garde de lecture (pas de progression 8 s, ou jamais démarrée 10 s ⇒ phrase close en échec, journalisée). |
| 2 | **Ouverture de l'écoute sans borne** : `getUserMedia` (dictée, flux), ticket HTTP, `audioWorklet.addModule` n'ont **aucun délai** ; une promesse qui ne revient pas laisse `listenOpening = true` **pour toujours** ⇒ chaque réécoute suivante sort sur `if (this.listenOpening …) return` sans un mot. | Code (déterministe). Cohérent avec « 1 tour par ouverture ». Sur Android, `getUserMedia` concurrent d'une capture en cours de libération (détection de voix détruite en asynchrone 250 ms avant la réécoute) est un cas connu. | **Élevée** | SF-233-16 : délais (6 s micro, 6 s ticket, 4 s module), pistes tardives coupées, attente bornée de la libération de la détection de voix, chien de garde sur `listenOpening`. |
| 3 | **Réécoute à déclencheur unique** : relancée seulement sur un changement de **signal** ; `turnRunning` (champ) qui repasse à faux alors que l'état est déjà `repos`, un `listen()` qui rend la main sans changer d'état ⇒ conversation « active » mais muette, indéfiniment (la veille à 60 s n'arrive même pas : elle est vérifiée dans la boucle d'écoute et la réécoute). | Code (déterministe). | **Élevée** | SF-233-16 : chien de garde de conversation (3 s) : `repos` + conversation active + rien en cours pendant 2 passages ⇒ réécoute (journalisée `watchdog_relisten`). |
| 4 | **Écran du compagnon fermé sans signe de vie** : `keepAlive()` seulement à un changement d'état ; une réflexion (tour Opus outillé) ou une écoute en flux longue > 3 min sans changement ⇒ le natif ferme l'écran, le micro revient au mot d'éveil, la conversation meurt sans un mot. | Code natif `JarvisScreenPolicy.IDLE_CLOSE_MS = 3 min` + page. | Moyenne | SF-233-16 : `keepAlive()` à chaque passage du chien de garde tant que la conversation est active ou qu'un tour tourne. |
| 5 | **Micro refusé ⇒ pause muette** : `dictation.start()` en échec (`NotReadableError` fréquent sur Android quand une capture précédente se libère) ⇒ `conversation.pause()` + **avis écrit seulement**. Le PO, téléphone posé, n'entend rien. | Code. | Moyenne | SF-233-16 : un nouvel essai après 700 ms, puis la pause est **dite** (« Je n'arrive pas à ouvrir le micro… »). |
| 6 | **Version périmée et rechargement en pleine conversation** : la WebView du compagnon est servie par le service worker (ancienne version au premier lancement après un déploiement) ; la mise à jour SF-233-12 recharge « au repos » — c'est-à-dire **entre deux tours** d'une conversation active (13:22:11). Dans un navigateur, le rechargement perd le geste ⇒ invite plein écran, plus d'écoute. | **Données** (13:21:59, nginx). Code. | Moyenne | SF-233-16 : **pas de service worker dans la WebView du compagnon** (désinscrit, rechargé une fois : toujours la dernière version déployée) ; ailleurs, la mise à jour attend la fin de la conversation. |
| 7 | **Écoute sourde** : l'`AudioContext` du niveau (`MicMeter`) n'est jamais repris ; suspendu ⇒ niveau 0 ⇒ en batch, annulation au silence toutes les 8 s et réécoute, sans trace, jusqu'à la veille. | Code ; non observé (WebView : lecture sans geste autorisée). | Faible-moyenne | SF-233-16 : `resume()` à l'ouverture ; l'état est journalisé (`meter ctx=…`). |
| 8 | **Parole refusée sans retour** : transcription vide ou porte de parole ⇒ aucun tour, aucune phrase. | **Données** : 4 `empty` (3,4–6,6 s de voix). | Moyenne (ressenti « il n'écoute pas ») | Mesurée (SF-233-15 : pic de niveau, temps de voix, longueur). Aucune phrase ajoutée (risque d'écho/bavardage) — à trancher sur pièces après le prochain essai. |
| 9 | **Annonce dite pendant l'écoute** : `flushAnnouncements()` tient `ecoute` pour « libre » ⇒ `say()` alors que le micro est ouvert ; la boucle `requestAnimationFrame` s'arrête (son `cancelListening()` ne fait rien hors `ecoute`), la dictée/le flux restent ouverts (fuite), et la dictée suivante réutilise l'enregistrement en cours (qui contient la voix de Jarvis). | Code (délégations seulement). | Faible | SF-233-16 : l'écoute est close proprement avant l'annonce. |
| 10 | **Écoute orpheline** : coupure par la voix (`bargeIn.cut`) met l'état à `ecoute` sans dictée ni flux ; si la fin de parole de la détection n'arrive pas, `ecoute` reste figé et `listen()` refuse (`state === 'ecoute'`). | Code. Aucune coupure mesurée en prod (`interruptions_confirmed = 0`). | Faible | SF-233-16 : chien de garde `watchdog_orphan_ecoute`. |
| 11 | **Moteur de page arrêté** (mémoire : 3D + visage + détection de voix ONNX/WASM) : `onRenderProcessGone` ⇒ `finish()` natif, sans un mot. | Hypothèse. | Inconnue | Le journal le montrera : dernier événement sans `screen_exit` ni `pagehide`. |
| 12 | Gardes de budget / coût | Plafond de voix (429) ⇒ réponse écrite puis `repos` ⇒ réécoute ; budget du cerveau rapide ⇒ repli Opus. **Pas un arrêt.** | — | — |
| 13 | Veille après 60 s de silence | Voulue ; **dite** (« Je me mets en veille. Dites « Jarvis » pour reprendre. ») puis `close()`. **Pas silencieuse.** | — | — |

## 4. Le visage F-250 absent du téléphone — cause prouvée

`GET /api/jarvis/face` (coupe-circuit serveur du visage) **n'est pas dans la portée `jarvis-compagnon`**
(`JarvisCompanionScope`) : dans la WebView, la session compagnon reçoit **403 `jarvis_companion_scope`**, l'erreur est
avalée (`error: () => undefined`), `faceServer` reste `false` ⇒ `faceOn()` faux ⇒ **le cœur s'affiche, et le bouton
« Jarvis : visage / cœur » n'apparaît même pas**. Le même écran dans le navigateur (jeton complet) montre le visage.
Correction : route ajoutée à la portée (lecture d'un booléen, rien d'engageant) — SF-233-16. Restent à vérifier sur le
téléphone, par le journal (`render …`) : la décision 3D/2D de l'appareil (WebGL, qualité adaptative).

Autres routes appelées par `/jarvis` hors portée (relevé du code) : `GET /jarvis/notices` + `POST …/spoken|seen`
(annonces d'interface F-249) — **laissées fermées volontairement** : les ouvrir ferait parler Jarvis d'annonces dans la
conversation du téléphone, changement de comportement hors de cet audit.

## 5. Constat annexe (coût, sans effet sur l'écoute)

Les sessions d'écoute en flux sont tenues **en mémoire du pod** qui a émis le ticket ; sans affinité d'ingress, la
clôture peut partir vers un autre pod (404 avalé) et la session est comptée **au maximum (120 s)** par le balayage.
À traiter à part (registre partagé ou affinité) ; noté pour ne pas fausser la lecture de `stt_stream_seconds`.

## 6. Le journal de l'écoute (SF-233-15) — lire le prochain essai

Une ligne par événement, **sans contenu** ; une répétition identique incrémente `repeat_count`. Événements clés :
`page_load` (sw=1 : page servie par le service worker), `state` (`repos>ecoute`…), `mode`, `wake`, `listen_open`,
`listen_skip` (opening/ecoute), `stream_open`/`stream_fallback`, `mic_open`/`mic_fail`, `meter` (`ctx=running|
suspended`, pistes vivantes), `listen_silence`, `listen_end` (voix, pic), `transcript` (longueur), `speech_rejected`,
`brain_done`, `voice_idle` (attente de la voix, ms), `say_start/say_end/say_fail`, `relisten_arm/fire/skip/wait`,
`conv_pause` (voix|bouton|micro), `conv_idle_end`, `appupdate_reload`, `page_hidden`, `pagehide`, `js_error`,
`screen_exit` ; avec SF-233-16 : `voice_stall`, `listen_open_timeout`, `watchdog_relisten`, `watchdog_orphan_ecoute`,
`mic_retry`, `render`.

**Où la boucle s'est arrêtée** (dernier événement de chaque ouverture de page, 24 h) :

```sql
select distinct on (page_id)
       page_id, page_version, companion, created_at at time zone 'Europe/Paris' as heure,
       seq, at_ms, event, state, mode, detail, repeat_count
from jarvis_listen_traces
where user_id = 'a7541dcd-e7c5-476e-88fb-72ca87973095'
  and created_at > now() - interval '24 hours'
order by page_id, seq desc;
```

**Le déroulé complet** d'un essai (2 dernières heures) :

```sql
select created_at at time zone 'Europe/Paris' as heure, page_id, page_version, companion,
       seq, round(at_ms / 1000.0, 1) as t_s, event, state, mode, detail, repeat_count
from jarvis_listen_traces
where user_id = 'a7541dcd-e7c5-476e-88fb-72ca87973095'
  and created_at > now() - interval '2 hours'
order by page_id, seq;
```

Lecture : `bash ~/.local/share/claude-gateway/q-prod-ro.sh "<requête>"`. Rétention 7 jours.

## 7. Ce que le PO fait au prochain essai

1. Rien à installer : la page se met à jour d'elle-même (le compagnon n'a plus de cache de page après la première
   ouverture qui suit le déploiement — cette première ouverture peut se recharger une fois).
2. Dire « Jarvis », enchaîner **au moins 4 échanges**, en notant l'heure du premier « il n'écoute plus ».
3. Laisser l'écran tel quel 10 s avant de le refermer (le journal part par lots ; la fermeture envoie le reste).
4. Me donner l'heure : la requête « où la boucle s'est arrêtée » nomme l'étape.
