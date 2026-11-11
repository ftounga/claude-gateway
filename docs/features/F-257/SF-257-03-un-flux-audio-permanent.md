# Mini-spec — [F-257 / SF-257-03] Un flux audio permanent

## Identifiant

`F-257 / SF-257-03`

## Feature parente

`F-257` — Jarvis converse en flux (cadrage, ADR-021)

## Statut

`in-review`

## Date de création

2026-11-11

## Branche Git

`feat/SF-257-03-flux-audio-permanent`

---

## Objectif

Quand l'appareil est réglé sur la boucle `stream` et que le serveur l'autorise, l'écran Jarvis tient **un seul
WebSocket par conversation** vers `jarvis-voice` et y envoie le micro en continu (PCM 16 kHz), avec reprise
automatique — **en ombre** de l'ancienne boucle, qui reste seule à décider et à répondre tant que les SF suivantes ne
sont pas livrées.

---

## Comportement attendu

### Cas nominal

1. **Drapeau par appareil** `cg.jarvis.loop` = `legacy` (défaut) | `stream`, réglage visible dans le panneau de
   réglages de Jarvis : « Conversation en flux (essai) » (bouton bascule, même patron que le mot d'éveil).
2. Réglage `stream` + conversation engagée (`startConversation`, F-231 D5 maintenue : rien avant le réveil) : l'écran
   demande un ticket (`POST /api/jarvis/voice-session`, sujet courant), ouvre le lien (`VoiceLink`), envoie `hello`
   puis le micro (contraintes `LISTEN_AUDIO`, capture AudioWorklet existante, rééchantillonné à 16 kHz, blocs PCM 16
   bits) ; un `ping` toutes les 10 s mesure l'aller-retour et le décalage d'horloge (crochet du banc F-256).
3. Reprise : coupure réseau ou du service → reprise à délais croissants (0,25 → 5 s, 8 essais) ; ticket refusé (4401)
   → nouveau ticket ; au-delà, le lien s'arrête et l'ancienne boucle continue seule.
4. Pause, fin de conversation (60 s de silence), sortie de l'écran : lien fermé (1000) et micro du lien rendu.
5. Chaque transition du lien est tracée dans le journal d'écoute (`voice_link`, détail sans contenu :
   `ready ms=…`, `reconnecting code=…`, `closed abandon …`), avec le `turnId` courant (F-255) quand il existe.
6. L'**audio descendant** (trames binaires) est remonté par le lien (`onAudio`) ; sa **lecture** (lecteur
   `SourceBuffer`) est livrée avec SF-257-07, quand le service envoie réellement de la voix (décision : ne pas livrer un
   lecteur sans source à tester — écart au découpage du cadrage, noté dans la PR).
7. Coupe-circuit serveur fermé (503 `voice_stream_disabled`) ou réglage `legacy` : **aucun** lien, aucune différence
   de comportement avec aujourd'hui.
8. Portée compagnon : `POST /jarvis/voice-session` ajouté à la liste fermée `jarvis-compagnon` (l'écran `/jarvis`
   ouvert dans le compagnon Android peut demander un ticket).

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| 503 `voice_stream_disabled` | pas de lien, trace `voice_link closed off`, ancienne boucle seule |
| Micro du lien refusé | pas de lien, trace `voice_link closed micro` |
| Service injoignable / 4503 / 4429 | reprises bornées puis abandon tracé ; ancienne boucle seule |
| Ticket expiré pendant une reprise | nouveau ticket demandé avant la reconnexion |

---

## Critères d'acceptation

- [ ] CA1 — `legacy` (défaut) : aucun appel à `/jarvis/voice-session`, aucun WebSocket ouvert.
- [ ] CA2 — `stream` : un seul lien par conversation, `hello` porte le ticket, le PCM part après `ready`.
- [ ] CA3 — reprise bornée et nouveau ticket sur 4401 ; abandon tracé après 8 essais.
- [ ] CA4 — pause / fin de conversation / destruction ferment le lien (aucune reprise après).
- [ ] CA5 — 503 : pas de lien, ancienne boucle inchangée.
- [ ] CA6 — le réglage est visible et mémorisé par appareil.
- [ ] CA7 — la portée compagnon ouvre `POST /jarvis/voice-session`.

---

## Périmètre

### Hors scope

- Toute décision de tour, transcription, réponse ou voix par le service (SF-257-04 → 08) : le lien est en **ombre**.
- Compagnon Android natif (SF-257-10).

---

## Technique

### Endpoints

| Méthode | URL | Auth |
|---------|-----|------|
| POST | `/api/jarvis/voice-session` (SF-257-02, désormais ouvert à la portée compagnon) | session / compagnon |

### Tables impactées

Aucune.

### Composants Angular

- `jarvis-voice-link.ts` — `VoiceLink` (WebSocket, `hello`, reprise, ping, compteurs) — pur, injectable.
- `jarvis-voice-shadow.ts` — `JarvisVoiceShadow` : drapeau, ticket, micro du lien (capture existante), ping.
- `jarvis.component.ts/html` — réglage « Conversation en flux (essai) » ; ouverture/fermeture avec la conversation.
- `jarvis.service.ts` — `voiceSession(workspaceId)`.

### Préoccupations transversales

- **Auth / Principal — cochée** : la portée compagnon gagne une route (`JarvisCompanionScope`) ; composants :
  `JarvisCompanionScope`, `JarvisCompanionScopeTest` ; aucun autre endpoint touché.
- **Navigation / routing — cochée** : aucune route nouvelle ; `/jarvis` et `/jarvis?source=compagnon` vérifiés
  inchangés en `legacy` (le lien n'existe qu'en `stream`). Composants : `jarvis.component.ts` seul.

---

## Plan de test

### Unitaires (front)

- [ ] `jarvis-voice-link.spec.ts` — `hello` (ticket hors URL), `ready`, PCM, ping/pong (aller-retour, décalage),
      reprise, 4401 → nouveau ticket, abandon, `close()` sans reprise, événements et audio remontés, URL `wss`.
- [ ] `jarvis-voice-shadow.spec.ts` — drapeau `legacy` : rien ; `stream` : ticket demandé avec le sujet, lien ouvert ;
      503 : rien ; fermeture.

### Intégration (back)

- [ ] `JarvisCompanionScopeTest` — `POST /jarvis/voice-session` ouvert à la portée compagnon.

### Isolation

- [x] Inchangée : le ticket est émis sous `user_id` (SF-257-02).

## Dépendances

- SF-257-02 fusionnée.
