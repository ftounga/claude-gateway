# Mini-spec — [F-257 / SF-257-05] Le serveur décide du tour

## Identifiant

`F-257 / SF-257-05`

## Feature parente

`F-257` — Jarvis converse en flux (cadrage §3.1, §3.2, §4.1, ADR-021)

## Statut

`in-review`

## Date de création

2026-11-11

## Branche Git

`feat/SF-257-05-serveur-decide-du-tour`

---

## Objectif

Le service `jarvis-voice` décide lui-même où finit le tour de parole — VAD Silero puis détecteur sémantique
smart-turn v3 sur CPU, avec un plafond de silence en repli — et rend à l'écran un tour **candidat**, **annulé**,
**confirmé** (avec son texte final) ou **rejeté**, sans rien journaliser de ce qui est dit.

---

## Comportement attendu

### Cas nominal

1. **Parole** : chaque bloc du micro passe par le VAD Silero de Pipecat (confiance 0,5, départ 0,1 s, arrêt 0,2 s,
   réglables par variables d'environnement) ; une parole < 0,15 s n'ouvre pas de tour (bruit). Un **texte partiel**
   de la session d'écoute ouvre aussi le tour (un « Oui. » bref que le VAD manque reste entendu). Événement
   `turn speech` (`via=vad|texte`).
2. **Candidat** : à l'arrêt de la parole, smart-turn v3 (Pipecat, ONNX 8 Mo, 1 fil) juge sur l'audio. Fini et phrase
   écrite **pas en suspens** (règle `isSuspended` de l'écran, F-239 / SF-239-10, reprise à l'identique) → candidat
   immédiat (`by=modele`, probabilité). Sinon **plafond** : 2,0 s de silence, + 1,5 s pour une phrase en suspens
   (`by=plafond`). Au candidat, `commit` est envoyé à la session d'écoute (SF-257-04).
3. **Confirmation** : texte final revenu **et** silence ≥ 0,7 s (la grâce de F-251) → `turn confirmed` avec le texte
   (cumul des finals du tour). Parole qui reprend avant → `turn cancelled`, le tour continue avec ce qui est déjà
   final. Texte final absent 1,5 s après le `commit` → on confirme sur ce qui est là (`via=delai`). Tour sans texte →
   `turn rejected reason=empty`.
4. **Journal** : l'écran (lien en ombre, SF-257-03) écrit chaque événement de tour et d'écoute dans le journal d'écoute
   (`voice_link turn candidate by=modele silence=100 … srv=<heure serveur>`) — **jamais le texte** — : le banc F-256
   et le rejeu F-255 les lisent.
5. **Rien n'est dit ni envoyé au cerveau** à cette étape : l'ancienne boucle reste seule à répondre (SF-257-06 branche
   le cerveau au candidat).

### Cas d'erreur

| Situation | Comportement |
|---|---|
| Bruit bref (< 0,15 s), sans texte | aucun tour, compté `noise` |
| Session d'écoute coupée pendant un candidat | le délai de 1,5 s confirme sur le texte déjà final, ou rejette |
| Modèles absents de l'image | l'image ne se construit pas (vérifiés au build, SF-257-02) |
| `JARVIS_VOICE_TURN=off` | aucun détecteur (repli d'exploitation) |

---

## Critères d'acceptation

- [ ] CA1 — Phrase finie : `speech` → `candidate by=modele` dès l'arrêt de la parole → `confirmed` après le final et
      0,7 s de silence ; un seul `commit`.
- [ ] CA2 — Parole qui reprend après un candidat : `cancelled`, puis un seul `confirmed` avec les deux morceaux.
- [ ] CA3 — Phrase jugée inachevée : candidat au plafond (2,0 s) ; phrase en suspens : pas avant 3,5 s même si le
      modèle la dit finie.
- [ ] CA4 — Bruit court : aucun tour ; partiel seul : le tour s'ouvre ; tour sans texte : `rejected empty`.
- [ ] CA5 — Les vrais modèles se chargent depuis le paquet et traitent l'audio bien plus vite que le temps réel.
- [ ] CA6 — Le journal d'écoute reçoit les événements de tour sans texte.
- [ ] CA7 — Essai local sur le corpus synthétique : candidat ≈ 0,3 s après la fin réelle de parole.

---

## Périmètre

### Hors scope

- Démarrer le cerveau au candidat, adopter au confirmé : SF-257-06.
- Interruption de la voix de Jarvis : SF-257-08.
- Réglage fin des seuils : au banc à vraie voix (SF-257-09), par variables d'environnement, sans nouveau code.

---

## Technique

### Composants

- Service : `app/turn.py` (`TurnDetector`, `is_suspended`), `app/turn_models.py` (adaptateurs Pipecat
  `SileroVADAnalyzer`, `LocalSmartTurnAnalyzerV3`), `app/main.py` (branchement : micro → VAD/smart-turn, partiels et
  finals → détecteur, `commit` → session d'écoute, tic de 100 ms).
- Écran : `jarvis-voice-shadow.ts` (`serviceEventTrace`, `onEvent` du lien). **Aucune** modification de
  `jarvis-stream-listener.ts` ni de `jarvis.component.ts` (F-258 en cours sur la boucle actuelle).

### Endpoints / tables

Aucun.

### Décision technique (notée dans la PR)

Le service utilise les **analyseurs** de Pipecat (VAD, smart-turn) mais **pas son pipeline de trames** : le « cerveau »
et la « voix » sont des appels à la passerelle avec des règles d'adoption propres (F-252 D1/D2), que l'orchestrateur
mince `TurnDetector` porte explicitement et que les tests couvrent un à un.

---

## Plan de test

### Unitaires (Python)

- [ ] `test_turn.py` — candidat par le modèle puis confirmation après la grâce ; annulation et cumul ; plafond ;
      phrase en suspens ; bruit ; partiel qui ouvre le tour ; rejet vide et délai ; `is_suspended`.
- [ ] `test_turn_models.py` — chargement des vrais modèles, silence ≠ parole, vitesse.
- [ ] `test_app.py` — bout en bout dans le service : parole → candidat → `commit` → final → confirmé.

### Front

- [ ] `jarvis-voice-shadow.spec.ts` — `serviceEventTrace` : tour et écoute journalisés, texte jamais.

### Isolation

- [x] Non applicable : aucune donnée lue ni écrite ; le texte confirmé ne va qu'au WebSocket de l'utilisateur.

## Dépendances

- SF-257-04 (session d'écoute longue, `commit`).
