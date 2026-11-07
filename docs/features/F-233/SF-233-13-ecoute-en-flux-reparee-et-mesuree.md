# Mini-spec — F-233 / SF-233-13 — L'écoute en flux réparée, la réécoute sans sujet, et la raison d'un repli mesurée

## Identifiant

`F-233 / SF-233-13`

## Feature parente

`F-233` — Jarvis temps réel (correctif, essai réel du PO le 2026-11-06 dans le compagnon 0.3.0)

## Statut

`in-review`

## Date de création

2026-11-07

## Branche Git

`feat/SF-233-13-flux-et-reecoute`

---

## Objectif

> L'écoute en flux de Jarvis s'ouvre de nouveau, la conversation mains libres se relance après chaque réponse même sans
> sujet choisi, et chaque tour dit pourquoi il n'a pas été écouté en flux (et s'il venait du compagnon).

## Constat (prod, preuves)

1. Tour du 2026-11-06 23:23 UTC : `listen_mode=CONV_BATCH`, `stt_provider=null`, `transcript_ms=4769`,
   `workspace_id=NULL`, `brain=FAST` ; puis plus aucun tour.
2. **Le flux est cassé pour tout le monde depuis le 2026-11-02, pas seulement dans la WebView** :
   `jarvis_turn_timings` = `CONV_STREAM`/`STREAM` jusqu'au 02/11 07 h UTC, puis uniquement `CONV_BATCH`/`BATCH`
   (02/11 23 h → 06/11). Entre les deux : SF-239-04 (#1503) ajoute un paramètre `keyterms` par terme du vocabulaire du
   compte (jusqu'à **100**) à l'URL WebSocket ElevenLabs.
3. Essai réel contre ElevenLabs (jeton à usage unique, même URL que la passerelle) : 0, 10, 50 termes →
   `session_started` ; **100 termes → `{"message_type":"invalid_request","error":"Number of keyterms cannot exceed 50.
   You provided 100 keyterms."}`**. L'écran ne reconnaît pas `invalid_request` comme une erreur (`parseSttMessage` ne
   cherche que `*error*`) : la session se ferme, l'écran retombe sur la dictée. Le compte du PO a 32 sujets à lui seul
   (plus clients, interlocuteurs, tickets, chantiers) : son vocabulaire dépasse 50.
4. Le repli batch est lent : `STT_UTTERANCE` (23:22:52) facturé mais rendu après l'attente de 2,5 s, puis la dictée
   `DICTATION` (23:22:54) — d'où `transcript_ms` ≈ 4,8 s. Le flux réparé supprime ce chemin dans le cas nominal.
5. **La réécoute ne se relance pas sans sujet** : `onConversationState` exige `this.subjectId()` avant `listen()`.
   Depuis F-235 D19, aucun sujet n'est choisi par défaut ; la WebView du compagnon a son propre `localStorage` (vide),
   et le cerveau rapide répond sans sujet (`workspace_id=NULL` du tour) → après la réponse, la conversation reste
   « active » mais n'écoute plus jamais.
   Confirmé par l'essai du 2026-11-07 00:04-00:10 UTC (APK 0.4.0) : 7 ouvertures, 6 tours, **tous** `CONV_BATCH`,
   `brain=FAST`, `workspace_id=NULL`, exactement un tour par ouverture (« le 2e message n'est pas écouté »).

## Comportement attendu

### Cas nominal

1. Passerelle : l'URL du flux ElevenLabs porte **au plus 50** `keyterms` (les 50 premiers du vocabulaire, déjà triés par
   priorité). La retranscription batch (`/jarvis/utterance`) garde son vocabulaire complet (inchangée).
2. Écran : un message du fournisseur `invalid_request`, ou portant un champ `error`, est une erreur (la session échoue
   franchement au lieu d'attendre la fermeture).
3. Écran : en conversation mains libres, la réécoute se relance au repos **même sans sujet** (un sujet manquant est
   demandé par Jarvis au moment d'envoyer, comme avant — D19 inchangé).
4. Mesure (`POST /api/jarvis/timings`, `jarvis_turn_timings`) :
   - `streamFallback` (liste fermée, `null` si le flux a servi) : `ticket_refuse` (401/403/429), `ticket_indisponible`
     (503, ou flux déjà déclaré indisponible), `ticket_echec` (autre échec de `/jarvis/stt-session`), `micro`
     (`getUserMedia` du flux), `ouverture_delai`, `ouverture_refusee`, `ouverture_reseau`, `ouverture_fermee`
     (session du fournisseur), `capture` (AudioContext/AudioWorklet), `coupe` (session coupée pendant l'écoute),
     `autre` ;
   - `companion` (booléen) : la page tourne dans le compagnon Android.
   Vocabulaire fermé côté passerelle via `closed(...)` (400 hors liste) ; migration Liquibase `248-02` (colonnes
   nullables, réversible).

### Cas d'erreur

- Valeur hors liste pour `streamFallback` → 400 (comme `rejectedKind`).
- Écran ancien (sans ces champs) → colonnes `NULL`, rien ne casse.
- Vocabulaire vide ou ≤ 50 termes → URL inchangée.

## Critères d'acceptation

- [ ] `sessionUrl(...)` n'émet jamais plus de 50 `keyterms` (test).
- [ ] `parseSttMessage` classe `invalid_request` et tout message avec `error` en `error` (test).
- [ ] La réécoute part sans sujet en conversation (test composant).
- [ ] Un repli rapporte `streamFallback` selon la cause (ticket 503 → `ticket_indisponible`, ticket 403 →
  `ticket_refuse`, ouverture rejetée → classe de l'erreur) ; `companion` est porté (tests).
- [ ] `POST /api/jarvis/timings` enregistre `streamFallback`/`companion`, refuse une valeur hors liste (test).
- [ ] Après déploiement : des tours `CONV_STREAM` réapparaissent dans `jarvis_turn_timings`.

## Plan de test minimal

- Back : `ElevenLabsStreamingTranscriptionProviderTest` (borne 50), `JarvisLatency*Test` / test d'API des timings
  (enregistrement, 400 hors liste).
- Front : `jarvis-stream-listener.spec.ts` (`parseSttMessage`, `streamFailureOf`), `jarvis-turn-clock.spec.ts`
  (rapport), `jarvis.component.spec.ts` (réécoute sans sujet, `streamFallback` sur 503/403).
- Isolation : la mesure est écrite sous `CurrentUser` (inchangé) ; aucune lecture nouvelle.

## Impacts

- Back : `ElevenLabsStreamingTranscriptionProvider`, `JarvisLatencyService` (record + liste fermée),
  `JarvisTurnTiming`, migration `248-02-jarvis-turn-timings-stream-fallback.xml`.
- Front : `jarvis-stream-listener.ts`, `jarvis-turn-clock.ts`, `jarvis.models.ts`, `jarvis.component.ts`.
- Table `jarvis_turn_timings` : +`stream_fallback varchar(24)`, +`companion boolean` (nullables).
- Endpoint `POST /api/jarvis/timings` : 2 champs facultatifs.
- Préoccupations transversales : aucune (auth, tenant, plans, routing inchangés). Aucune consigne ni modèle touché
  (V8 : pas de banc).

## Hors périmètre

- La comptabilité des sessions de flux non closes (carte `open` en mémoire par pod, 2 pods en prod : une clôture qui
  tombe sur l'autre pod répond 404 et la session est comptée au maximum par le balayage) — constat, à cadrer à part.
- Le compagnon Android : SF-232-10.
