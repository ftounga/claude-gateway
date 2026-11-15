# Mini-spec — [F-257 / SF-257-13] Le cerveau ne retient plus le début de sa réponse

## Identifiant

`F-257 / SF-257-13`

## Feature parente

`F-257` — Jarvis converse en flux (cadrage §4.1, ligne « Premier jeton cerveau rapide » : « préfixe en cache gardé chaud ;
mesuré séparément »)

## Statut

`in-review`

> Renumérotée SF-257-11 → **SF-257-13** le 2026-11-15 : SF-257-11 est pris sur `main` par « le service voix en prod »
> (#1731), SF-257-12 par « la voix prête avant la confirmation ».

## Date de création

2026-11-12

## Branche Git

`feat/SF-257-13-cerveau-sans-tampon`

---

## Objectif

Faire partir la première phrase du cerveau rapide ≈ 0,6 s plus tôt, **sans retirer aucune capacité au modèle** : sa
réflexion passe de « éteinte » (`between_tools` sur Sonnet 5.5) à « adaptative à l'effort `low` » (le modèle décide).

---

## Mesure qui fonde la subfeature (2026-11-12)

### Où partent les ≈ 2 s du cerveau (banc à vraie voix `sf257-08-stream`, 21 tours, ms)

| Étape (depuis la fin réelle de la parole) | Médiane | p90 |
|---|---:|---:|
| Fin de parole → candidat de fin de tour (service voix) | 502 | 561 |
| Candidat → requête `/jarvis/converse` | 2 | 6 |
| Préparation serveur (lectures parallèles) | 35 | 52 |
| **Requête fournisseur → premier jeton** (`firstTokenMs` serveur) | **1 460** | **2 071** |
| Premier jeton → ligne `compris:` complète (événement `understood`) | ≈ 200 | — |
| `understood` → première phrase dicible | 132 | 1 613 |
| = **fin de parole → première phrase** | **2 314** | **3 741** |
| (fin de parole → confirmation du tour, grâce 1,8 s ; en parallèle) | 1 811 | 2 113 |
| Première phrase → synthèse ElevenLabs (requête) | 388 | 513 |
| Premier octet → son | 38 | 292 |
| **Premier son** (page) | **3 739** | **5 062** |

### Le premier jeton, décomposé (sondes directes de l'API, vraie consigne v20 + 6 outils stricts, 36 k jetons dont 32 k en cache)

| Constat | Mesure |
|---|---|
| Connexion TLS neuve vers l'API | ≈ 20 ms (connexion gardée chaude : gain négligeable) |
| Cache | **sain** : prod 20,7 k lus / ≈ 50 écrits par tour (médiane, `usage_turns`), ≈ 3 k non cachés (le dernier message, volatil par construction) |
| Réflexion / outils appelés avant de parler | aucune réflexion (`between_tools`) ; les tours d'outil sont l'exception (p90) |
| Même requête **sans outils** | 1er jeton 0,76 s |
| Avec outils, `strict` ou non, 1 ou 6 outils, `disable_parallel_tool_use` | 1er jeton 1,5-1,7 s, **la ligne `compris:` et la 1re phrase arrivent d'un bloc** |
| **A/B entrelacé, 4 paroles réelles × 3** — `between_tools` | 1re phrase **1 617 / 1 900 ms** (p50/p90), 0/12 réflexion |
| — `adaptive` + effort `low` | 1re phrase **979 / 1 091 ms**, **0/12 réflexion**, mêmes outils choisis (afficher, deleguer), mêmes phrases |
| — `adaptive` effort par défaut (`high`) | 1re phrase 1 472 / 1 611 ms, 9/12 réflexion |

**Cause** : sur Sonnet 5.5, « réflexion éteinte » s'écrit `between_tools` ; outils déclarés, le fournisseur **retient
le début du texte** (il doit savoir si un appel d'outil suit) ≈ 0,6 s. En réflexion adaptative, le texte part dès qu'il
est écrit.

### Second levier : le préfixe chaud pendant la parole (mesure du 2026-11-12)

Après ≈ 10 s sans appel, le premier jeton remonte à ≈ 1,8 s ; 0,6 à 4 s après un appel portant **le même préfixe**, il
tombe à ≈ 0,7 s. L'utilisateur parle 1 à 5 s avant chaque tour : on chauffe le préfixe pendant ce temps.

---

## Comportement attendu

### Cas nominal

1. `app.jarvis.fast-brain.thinking` (`APP_JARVIS_FAST_THINKING`) : `adaptive` (défaut) ou `off`.
2. `adaptive` : l'appel du cerveau rapide (conversation et pré-chauffage) demande la réflexion adaptative à l'effort
   configuré (`low`) ; le plafond de sortie reçoit **+ 4 000 jetons** de place pour la réflexion (elle y compte) : la
   réponse dite garde ses 600 jetons, rien n'est raccourci.
3. Boucle d'outils (pièces, agenda, état, conversations, afficher) : les blocs de réflexion du tour sont **rejoués tels
   quels**, en tête du message assistant, avec les résultats d'outils (exigence du fournisseur).
4. `off` : requête strictement identique à aujourd'hui (coupe-circuit sans livraison).
5. **Pré-chauffage du tour** (boucle en flux) : au début de la parole (`speech`), puis toutes les 3 s tant qu'elle
   dure, et à la reprise (`cancelled`), l'écran appelle `POST /api/jarvis/converse/warm` avec `{turn: true,
   presentation}`. La passerelle envoie, en tâche de fond, un appel au **préfixe exactement identique** à celui de la
   réplique (outils, consigne et portrait, univers de référence, échanges de la session, même réflexion) — seul le
   dernier message diffère — avec `max_tokens: 0` (rien n'est généré, rien n'est dit). Au plus un par compte toutes les
   1,5 s ; jamais au-delà du budget dur. Arrêt au candidat, au rejet, à la sortie de l'écoute.

### Cas d'erreur

| Situation | Comportement |
|---|---|
| Réflexion longue (rare à `low`) | place de 4 000 jetons ; au-delà, troncature déjà traitée (`autoEmpty`) |
| Régression constatée en usage | `APP_JARVIS_FAST_THINKING=off`, sans livraison |
| Pré-chauffage en échec (réseau, fournisseur) | ignoré (journal `debug`), la réplique part normalement |
| Parole hachée (bruits, reprises) | au plus un pré-chauffage par compte toutes les 1,5 s |
| Budget dur atteint | aucun pré-chauffage |

---

## Critères d'acceptation

- [x] CA1 — `adaptive` : requête `reasoning` adaptative, effort `low`, non éteinte, plafond 4 600.
- [x] CA2 — `off` (défaut du code) : requête inchangée (éteinte, 600).
- [x] CA3 — Boucle d'outils : le bloc de réflexion du 1er appel est rejoué en tête du message assistant du 2e.
- [x] CA4 — Banc CONVERSATION ×2 (`JarvisConversationBenchLiveTest`, `JARVIS_LIVE_BENCH=2`) : **les deux passages
      verts** ; sinon arrêt et rapport (pas de rejeu jusqu'au vert).
- [x] CA6 — Pré-chauffage : même modèle, consigne, outils, réflexion et messages (hors le dernier) que la réplique ;
      `max_tokens` 0 ; un second dans les 1,5 s est refusé ; l'écran l'appelle à la parole, toutes les 3 s, à la reprise,
      et plus après le candidat ou le rejet.
- [x] CA5 — Banc à vraie voix (`--boucle stream --voix elevenlabs`) : premier son médian et p90 meilleurs que
      `sf257-08-stream` (3,92 / 5,53 s), sans régression notable (phrases entendues, réponses répétées, « Voici » sans image).

---

## Périmètre

### Inclus

- `JarvisFastBrainService` : réglage `thinking`, `reasoning()`, `shape()`, rejeu des blocs de réflexion.
- `application.yml` : `thinking: ${APP_JARVIS_FAST_THINKING:adaptive}`.
- `JarvisFastBrainService.warmTurn` + `JarvisFastBrainController` (`WarmRequest.turn`, `presentation`) ; endpoint
  existant `POST /api/jarvis/converse/warm`, même authentification, sujet résolu par `findByIdAndUserId`.
- Écran : `JarvisFastBrainClient.warmTurn`, `JarvisComponent` (boucle en flux seulement).
- Journal d'un appel sans contenu (premier jeton, total, jetons, cache, outils) pour mesurer.

### Hors scope

- Modèle, consigne v20, outils, `max_tokens` de la réponse, effort : **inchangés**.
- Le chemin « poste éteint » (`JarvisOfflinePrompt`) garde sa forme (mesuré à part si besoin).
- La ligne `compris:` (≈ 0,2 s) : la retirer toucherait la consigne et une garde (F-239) → décision PO.
- La grâce de confirmation de 1,8 s (SF-257-08) et la voix au premier fragment (SF-257-07) : autres leviers.

---

## Préoccupations transversales

- [ ] Auth / Principal — non
- [ ] Contexte tenant — non (aucun accès données nouveau)
- [x] Plans / limites — **composants impactés** : `JarvisFastBrainService.budgetState` (le pré-chauffage est refusé
      au budget dur) et `account()` (chaque pré-chauffage est compté comme un appel du cerveau rapide : écriture/lecture
      de cache, aucune sortie). Aucun gate modifié ; une réflexion éventuelle est comptée comme sortie.
- [ ] Navigation / routing — non

## Plan de test

- [x] Unitaires `JarvisFastBrainStateTest` : défaut éteint (CA2), adaptatif + rejeu des blocs (CA1, CA3).
- [x] Non-régression `JarvisFastBrainServiceTest` (forme compacte, effort `low`) ; pré-chauffage au préfixe identique,
      cadence 1,5 s (CA6).
- [x] Écran : `jarvis-fast-brain.spec.ts` (corps de la requête), `jarvis.component.spec.ts` (cadence, arrêt) (CA6).
- [x] Banc CONVERSATION ×2 (CA4), banc à vraie voix (CA5).

### Isolation

- [x] Le pré-chauffage lit les mêmes données que la réplique, par les mêmes accès filtrés par `user_id` (sujet par
      `findByIdAndUserId`) ; aucune donnée écrite hors le compteur de coût du compte.

---

## Résultats (2026-11-12 et 2026-11-15)

### Banc CONVERSATION (CA4, justesse) — réflexion adaptative

| Passage | Taux | Porte | Délégation |
|---|---:|---|---:|
| 1 | 98,6 % | verte | 0,167 |
| 2 | 100,0 % | verte | 0,167 |

(Pour mémoire, réflexion éteinte, consigne v19 : banc instable, 95,8 % rouge le 2026-11-09.)

### Banc à vraie voix (CA5) — `--boucle stream --voix elevenlabs`

| Passage | Réglage | Premier son p50 / p90 | Entendues | Répétées | « Voici » sans image | 1er jeton cerveau p50 / p90 | Coût |
|---|---|---:|---:|---:|---:|---:|---:|
| `sf257-08-stream` (référence) | éteinte | 3,92 / 5,53 s | 24/25 | 2 | 1 | — | 0,57 € |
| `sf257-11-stream` | adaptative seule | 4,00 / 6,09 s | 25/25 | 0 | 0 | — | 0,53 € |
| `sf257-11-stream-2` | adaptative seule | 3,52 / 5,45 s | 25/25 | 0 | 1 | 972 / 1 807 ms | 0,48 € |
| `sf257-11-stream-chaud` | + pré-chauffage | **invalide** (panne réseau, 4/20 entendues) | | | | | |
| `sf257-13-chaud-1` | + pré-chauffage | **3,77 / 4,94 s** | 25/25 | 1 | 1 | 873 / 1 152 ms | 0,69 € |
| `sf257-13-chaud-2` | + pré-chauffage | **3,74 / 4,59 s** | 25/25 | 2 | 0 | 879 / 1 297 ms | 0,58 € |

**Lecture** : la réflexion adaptative seule ne tient pas sur deux passages (un meilleur, un pire que la référence) ; avec
le pré-chauffage, **les deux passages** battent la référence — médiane − 0,15 à − 0,18 s, **p90 − 0,6 à − 0,9 s** — sans
régression (entendues 25/25, répétées et « Voici » sans image au niveau de la référence, mêmes cas). Le pré-chauffage est
gardé. « Jarvis n'écoutait pas » (4, `captures-prod`) = mise en veille normale après les captures vides (« Je me mets en
veille. »), déjà vue sans pré-chauffage : minutage du banc, pas une régression. Le passage 1 a partagé le poste avec une
suite `ng test` (V9) ; le passage 2 était seul. Coût : + 0,1 à 0,2 € par passage par rapport à la réflexion seule (le
préfixe est écrit/lu en cache pendant la parole), du même ordre que la référence.

## Dépendances

- SF-257-08 fusionnée.
