# Mini-spec — [F-257 / SF-257-06] Répondre en recouvrement (la boucle en flux prend la main)

## Identifiant

`F-257 / SF-257-06`

## Feature parente

`F-257` — Jarvis converse en flux (cadrage §3.2, §4.1 ; décision PO du 2026-11-11 « voie A »)

## Statut

`in-review`

## Date de création

2026-11-11

## Branche Git

`feat/SF-257-06-repondre-en-recouvrement`

---

## Objectif

En réglage « Conversation en flux » (par appareil, éteint par défaut), le service vocal **tient l'écoute** et décide
les tours ; le cerveau rapide part **dès le candidat** en réponse provisoire (F-252), adoptée seulement si le texte
confirmé est le même ; si le lien tombe, l'écran **revient seul** à l'ancienne boucle, et le repli est tracé.

---

## Comportement attendu

### Cas nominal

1. **Prise de main** : lien prêt (`ready`) et pas d'entretien en cours → `listen()` n'ouvre **aucun** micro ni session
   de l'ancienne boucle ; l'écran passe en `ecoute` et attend les tours du service (`stream_open service`). Une
   ancienne écoute déjà ouverte quand le lien devient prêt cède la place (`voice_link takeover`).
2. **Tours** (événements du service, SF-257-05) :
   - `speech` : activité de conversation, nouvel identifiant de tour (F-255) ;
   - `candidate` (avec le texte du moment) : si la parole est destinée au cerveau rapide (`speculable`, mêmes règles
     que F-252), **réponse provisoire** `JarvisSpeculation` lancée — rien n'est dit ni montré ;
   - `cancelled` : la provisoire est jetée (verdict refusé, appel interrompu, compté `anticipationsDropped`) ;
   - `confirmed` : le texte suit **exactement** le chemin d'une parole de l'ancienne boucle (`acceptHeard` : porte de
     parole réelle, confirmation, commandes, changement de sujet, cerveau) ; `claimSpeculation` adopte la provisoire si
     c'est le même texte (`adoptee`), sinon la jette et relance sur le texte confirmé (`relancee`) — règles F-252 D1/D2
     inchangées ;
   - `rejected` : la provisoire est jetée, l'écoute continue.
3. **Mesure** : la mesure du tour porte `loop: 'stream'`, `listenMode` `CONV_STREAM`/`STREAM`, `sttProvider`
   `elevenlabs`, `speechMs` et `endSilenceMs` du service ; t0 = fin de parole (instant de confirmation − silence).
4. **Pendant la voix de Jarvis**, le micro du lien envoie du silence (et 150 ms après, queue d'écho) : sa voix ne part
   jamais au service ; l'interruption reste celle de l'ancienne boucle jusqu'à SF-257-08.
5. **Repli** : le lien cesse d'être prêt (reprise, abandon) → `voice_link fallback <détail>` ; une écoute tenue par le
   service est rendue et, en conversation, l'ancienne écoute repart aussitôt.
6. **Fin de conversation** : 60 s sans parole (réglage existant) → fin habituelle (`listen_idle_expired service`).
7. **Banc à vraie voix** : `lancer.sh --boucle stream` lance `jarvis-voice` en local derrière la passerelle locale
   (coupe-circuit ouvert pour le banc seulement), relaie `/voice/…` et règle la page sur la boucle en flux ;
   `--voix elevenlabs` (défaut, voix du PO comme en prod) ou `openai`. Le service ajoute le texte du moment à
   l'événement `candidate` (jamais journalisé).

### Cas d'erreur

| Situation | Comportement |
|---|---|
| Coupe-circuit fermé (503) ou réglage `legacy` | aucun lien : l'ancienne boucle, inchangée |
| Lien qui tombe pendant l'écoute | repli tracé, ancienne écoute rouverte |
| Tour du service pendant la réflexion ou la voix | ignoré, tracé `voice_turn_ignored` |
| Entretien du portrait en cours | l'ancienne boucle écoute (sa fin de tour longue reste celle de l'écran) |

---

## Critères d'acceptation

- [ ] CA1 — Lien prêt : aucune session d'écoute de l'ancienne boucle ; état `ecoute`.
- [ ] CA2 — Candidat → un seul appel provisoire, rien de dit ; confirmé même texte → verdict adopté, un seul appel,
      réponse dite ; mesure `loop=stream`, `speculation=adoptee`.
- [ ] CA3 — Reprise de parole → provisoire jetée ; texte confirmé différent → appel normal sur le texte confirmé.
- [ ] CA4 — Lien qui tombe en conversation → repli tracé et ancienne écoute rouverte ; tour hors écoute ignoré.
- [ ] CA5 — Lien pas prêt / réglage `legacy` → ancienne boucle comme avant (suite complète verte).
- [ ] CA6 — Banc : `--boucle stream` mesure la boucle en flux, voix ElevenLabs, résumé marqué `boucle stream`.

---

## Périmètre

### Hors scope

- La voix au premier fragment (SF-257-07), l'interruption côté serveur (SF-257-08), la bascule du défaut (SF-257-09).
- Le compagnon Android natif (SF-257-10).

### Décision technique (écart au cadrage, notée dans la PR)

Le cadrage dessinait le service appelant `/jarvis/converse` lui-même. Ici **l'écran** lance le cerveau au candidat
du service, avec `JarvisSpeculation` (F-252) telle quelle : mêmes règles d'adoption D1/D2, mêmes commandes locales
(pause, confirmation, correction, changement de sujet), aucun relais SSE à écrire, un saut réseau de moins ; le service
ne détient toujours aucun droit d'agir. Le cerveau part au même instant (le candidat).

---

## Technique

### Composants

- Écran : `jarvis.component.ts` (`streamHooks`, `streamTakesOver`, `onStreamReady`, `enterStreamListening`,
  `onStreamTurn`, `acceptStreamTurn` ; fin de `closeListening` extraite en `acceptHeard`, commune aux deux boucles),
  `jarvis-voice-shadow.ts` (`ready`, `VoiceLoopHooks`, micro muet pendant la voix).
- Service : `app/turn.py` (texte du moment sur `candidate`).
- Banc : `banc.mjs` (`--boucle`, relais WebSocket `/voice/`), `lancer.sh` (`--boucle stream`, `--voix`),
  `mesure.mjs` (boucle et voix au résumé), `README.md`.

### Endpoints / tables

Aucun nouveau. Mesure : `voice_loop` (SF-257-01).

### Préoccupations transversales

- **Navigation / routing — cochée** : aucune route ; `/jarvis` et `/jarvis?source=compagnon` : en `legacy` (défaut)
  rien ne change (suite de l'écran verte) ; en `stream`, le comportement ci-dessus, compagnon compris (le ticket y est
  ouvert depuis SF-257-03). Composants : `jarvis.component.ts`.

---

## Plan de test

- [ ] `jarvis.component.spec.ts` — prise de main, provisoire et adoption, reprise et relance, repli, lien pas prêt.
- [ ] `jarvis-voice-shadow.spec.ts` — `ready` et `onReadyChange`, tours remontés, micro muet pendant la voix.
- [ ] Python `test_turn.py` — le candidat porte le texte du moment.
- [ ] `mesure.test.mjs` (banc) — inchangé, vert.
- [ ] Banc à vraie voix : les deux boucles, voix ElevenLabs.

### Isolation

- [x] Inchangée : tickets et appels sous le `user_id` de l'utilisateur.

## Dépendances

- SF-257-03/04/05 fusionnées ; F-258 fusionnée avant toute modification de `jarvis.component.ts` sur `main`
  (consigne du coordinateur) — rebase.
