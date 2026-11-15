# Mini-spec — [F-257 / SF-257-12] La voix de la première phrase est prête avant la confirmation

## Identifiant

`F-257 / SF-257-12`

## Feature parente

`F-257` — Jarvis converse en flux (cadrage §4.1, ligne « Voix : premier octet → son » ; préalable léger à SF-257-07)

## Statut

`in-review`

## Date de création

2026-11-12

## Branche Git

`feat/SF-257-12-voix-prete-avant-confirmation`

---

## Objectif

Dans la boucle en flux, demander la synthèse de la **première phrase** de la réponse provisoire **pendant** que le
service confirme le tour, pour que le son parte dès l'adoption au lieu d'attendre ≈ 0,4 s de synthèse ensuite.

---

## Constat (banc à vraie voix `sf257-08-stream`, 21 tours)

- La réponse provisoire part au **candidat** (fin de parole + 0,50 s) ; le tour n'est **confirmé** qu'à fin de parole
  + 1,81 s (grâce SF-257-08). Rien n'est dit avant l'adoption (règles F-252 D1/D2).
- La synthèse de la 1re phrase n'est **demandée qu'à l'adoption** : 388 ms médiane (513 p90) de requête ElevenLabs
  `eleven_multilingual_v2` avant le premier octet, sur le chemin critique.
- Avec SF-257-13 (premier jeton ≈ 0,6 s plus tôt), la 1re phrase est prête **avant** la confirmation dans la plupart des
  tours : la synthèse peut se faire pendant l'attente.

---

## Comportement attendu

### Cas nominal

1. Boucle en flux, candidat de fin de tour : la réponse provisoire signale **sa première phrase retenue** (une fois,
   avant l'adoption).
2. L'écran demande sa synthèse (`POST /api/jarvis/speech`, même texte, même sujet) **sans la jouer** : un seul
   emplacement, la préparation précédente est abandonnée.
3. Adoption : la file de la voix reçoit la même phrase et **reprend** la synthèse préparée (pas de second appel) ; le
   premier octet est posé sur le chronomètre du tour.
4. Réponse jetée (parole reprise, rejet, texte final différent) : la synthèse est **abandonnée** (requête interrompue),
   jamais jouée.

### Cas d'erreur

| Situation | Comportement |
|---|---|
| Phrase confiée différente de la préparée (ou autre sujet) | préparée abandonnée, synthèse normale |
| Préparée plus vieille que 15 s | abandonnée, synthèse normale |
| Ligne-marque (✦, 📌, ⤵) | rien n'est préparé |
| Synthèse préparée en échec (voix absente, plafond) | même erreur que sans préparation, à la reprise |

---

## Critères d'acceptation

- [x] CA1 — Même phrase et même sujet : un seul appel de synthèse, la file reprend la préparée, premier octet posé.
- [x] CA2 — Autre phrase / autre sujet / plus de 15 s : préparée abandonnée (requête interrompue), synthèse normale.
- [x] CA3 — Réponse jetée : la préparée est abandonnée ; rien n'est joué avant l'adoption.
- [x] CA4 — La réponse provisoire signale sa première phrase une seule fois, jamais après abandon ni adoption.
- [x] CA5 — Banc à vraie voix (`--boucle stream --voix elevenlabs`) : premier son meilleur que la référence sans
      régression notable (phrases entendues, réponses répétées, « Voici » sans image).

---

## Périmètre

### Inclus

- `JarvisVoiceService` : `warm`, `unwarm`, reprise par `enqueue`.
- `JarvisSpeculation` : signal de la première phrase retenue.
- `JarvisComponent` : boucle en flux seulement (candidat du service) ; abandon à l'abandon de la réponse.

### Hors scope

- Ancienne boucle (`legacy`) : inchangée.
- Synthèse au premier **fragment** et audio dans le WebSocket : SF-257-07.
- Cerveau, consigne, voix (modèle `eleven_multilingual_v2` gardé), grâce de confirmation : inchangés.

---

## Préoccupations transversales

- [x] Plans / limites — **composants impactés** : `JarvisVoiceService` côté passerelle (`/api/jarvis/speech` compte la
      voix à la demande) et `JarvisBudgetService` : une préparation abandonnée est comptée comme une phrase dite. Borne :
      **une seule** phrase par réponse provisoire jetée (≈ 3 jetées sur 24 tours au banc) ; critère cadrage « coût voix
      ≤ 1,5 × » tenu. Aucun gate modifié.
- [ ] Auth / Principal — non (même appel authentifié qu'aujourd'hui)
- [ ] Contexte tenant — non
- [ ] Navigation / routing — non

## Plan de test

- [x] `jarvis-voice.service.spec.ts` : reprise (CA1), abandon autre phrase/sujet/âge (CA2), `unwarm` (CA3).
- [x] `jarvis-speculation.spec.ts` : signal unique, rien après abandon/adoption (CA4).
- [x] `jarvis.component.spec.ts` : préparée à la 1re phrase provisoire, reprise à l'adoption (même sujet), abandonnée à la reprise de parole (CA1, CA3).
- [x] Banc à vraie voix (CA5).

### Isolation

- [x] Non applicable (aucune donnée nouvelle ; appel déjà filtré par l'utilisateur).

---

## Résultats (2026-11-15, par-dessus SF-257-13 fusionnée)

| Passage | Premier son p50 / p90 | Entendues | Répétées | « Voici » sans image | Coût |
|---|---:|---:|---:|---:|---:|
| `sf257-08-stream` (référence) | 3,92 / 5,53 s | 24/25 | 2 | 1 | 0,57 € |
| `sf257-13-chaud-1` / `-2` (SF-257-13 seule) | 3,77 / 4,94 s — 3,74 / 4,59 s | 25/25 — 25/25 | 1 — 2 | 1 — 0 | 0,69 — 0,58 € |
| `sf257-12-voix-1` | **3,46 / 5,00 s** | 25/25 | 0 | 1 | 0,63 € |
| `sf257-12-voix-2` | **3,11 / 5,96 s** | 24/25 (`mots-courts/n07`) | 0 | 1 | 0,62 € |

**Lecture** : la médiane gagne sur les deux passages (− 0,3 à − 0,6 s contre SF-257-13 seule). Le mécanisme joue sur ≈ 1
tour sur 3 (`voix-1` : 9 synthèses lancées avant la confirmation, ≈ 1,2 s après le candidat ; aucune synthèse en plus au
total). Le p90 de `voix-2` (5,96 s) vient de tours où **le cerveau** lit un outil (`n03`, `n08`, `n09` : première phrase
≈ 3 s après la confirmation) ; la préparation n'y peut rien et n'y retarde rien (sans phrase avant la confirmation, le
chemin est celui d'avant). `mots-courts/n07` (« mot bref » de SF-257-08) : sonde d'écoute, sans lien avec la synthèse,
déjà oscillante (entendu mais vide dans les autres passages). Aucune phrase dite avant l'adoption (règles F-252 D1/D2).

## Dépendances

- SF-257-13 (gain maximal quand la première phrase est prête avant la confirmation) ; indépendante dans le code.
