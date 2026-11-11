# Mini-spec — F-255 / SF-255-01 Un identifiant de tour commun

## Identifiant
`F-255 / SF-255-01`

## Feature parente
`F-255` — Rejeu de séance Jarvis (cadrage `CADRAGE-F-255-rejeu-de-seance-jarvis.md`, §4)

## Statut
`in-progress` · **Estimation :** 1 j · **Branche :** `feat/SF-255-01-identifiant-de-tour` · **Date :** 2026-11-11

## Objectif
Rattacher sans ambiguïté, à un même tour vocal, ses événements du journal d'écoute, sa mesure, sa capture et sa requête
au cerveau rapide, et dater chaque événement du journal à l'heure du serveur.

## Comportement attendu

### Cas nominal
1. L'écran tire un **identifiant de tour** (`turnId`, UUID) à chaque ouverture d'écoute (`listen_open`) et à chaque parole
   qui coupe Jarvis (coupure, SF-233-08). Il reste le tour courant jusqu'au suivant.
2. **Journal d'écoute** : chaque événement porte `tu` (le tour courant, absent avant la première écoute) ; chaque lot
   porte `now` (ms depuis l'ouverture de la page, à l'envoi). La passerelle range `turn_id` et calcule
   `occurred_at = réception − (now − t)` (heure serveur de l'événement) ; sans `now`, `occurred_at` reste vide.
3. **Mesure du tour** (`POST /api/jarvis/timings`) : `turnId` et `pageId` rangés (`jarvis_turn_timings.turn_id`, `page_id`).
4. **Capture** (`POST /api/jarvis/captures`) : `turnId` rangé (`jarvis_audio_captures.turn_id`).
5. **Cerveau rapide** (`POST /api/jarvis/converse`) : `turnId` et `pageId` facultatifs ; quand ils sont là, le
   **contrôleur** écrit au journal d'écoute, sous le compte, un événement serveur `srv_converse` (tour, page, heure
   serveur exacte, détail `subject=<uuid>|none spec=0|1`). Un tour délégué existe donc même si la page se recharge
   avant d'envoyer sa mesure. `JarvisFastBrainService`, la consigne et les outils ne changent pas.

### Cas d'erreur
| Situation | Comportement attendu | Code |
|---|---|---|
| `tu`/`turnId`/`pageId` hors format `[A-Za-z0-9-]{1,36}` | valeur écartée, le reste est rangé | 204 |
| `now` absent, négatif ou inférieur à `t` | `occurred_at` vide (ou borné à la réception), l'événement est rangé | 204 |
| Écran d'avant SF-255-01 (aucun champ nouveau) | comportement inchangé, colonnes vides | 204 |
| Requête au cerveau rapide refusée (400/404/409/503) | aucun `srv_converse` écrit | inchangé |
| Plafond du journal atteint (20 000 lignes/24 h) | `srv_converse` non écrit, la requête continue | inchangé |

## Critères d'acceptation
- [ ] Un lot avec `now=10 000` reçu à T et un événement `t=4 000` donne `occurred_at = T − 6 s` et `turn_id = tu`.
- [ ] Une mesure avec `turnId`/`pageId` les range ; une mesure sans eux reste acceptée.
- [ ] Une capture avec `turnId` le range.
- [ ] Une requête au cerveau rapide avec `turnId` écrit un `srv_converse` sous le compte de l'appelant, avec le sujet de
      l'écran ; sans `turnId`, rien n'est écrit ; un refus (400/404) n'écrit rien.
- [ ] L'écran : un nouvel identifiant à chaque `listen_open` ; les événements, la mesure, la capture et la requête au
      cerveau rapide du même tour portent le même identifiant.
- [ ] Aucun contenu (texte dit ou entendu) n'entre au journal d'écoute.

## Périmètre — hors scope
Lire, assembler ou juger les tours (SF-255-02/03) ; l'écran d'administration (SF-255-04) ; toute modification de
`JarvisFastBrainService`, de la consigne ou des outils (pas de banc, V8) ; l'audio des tours en dictée de repli.

## Contraintes de validation
| Champ | Obligatoire | Max | Format | Normalisation |
|---|---|---|---|---|
| `tu`, `turnId`, `pageId` | Non | 36 | `[A-Za-z0-9-]` | écarté si hors format |
| `now` (lot) | Non | 1e9 ms | entier ≥ `t` | sinon `occurred_at` nul |

## Technique
- Endpoints modifiés (champs additifs) : `POST /api/jarvis/listen-trace`, `POST /api/jarvis/timings`,
  `POST /api/jarvis/captures`, `POST /api/jarvis/converse`. Aucun nouvel endpoint, rôles inchangés.
- Tables : `jarvis_listen_traces` (+ `turn_id`, `occurred_at`, index `(user_id, occurred_at)`),
  `jarvis_turn_timings` (+ `turn_id`, `page_id`), `jarvis_audio_captures` (+ `turn_id`). Migration
  `255-01-jarvis-turn-id.xml`, additive, rollback = retrait des colonnes et de l'index.
- Backend : `JarvisListenTraceService` (+ `recordServer`), `JarvisListenTraceEvent`, `JarvisLatencyService`,
  `JarvisTurnTiming`, `JarvisCaptureService`/`Controller`, `JarvisAudioCapture`, `JarvisFastBrainController`
  (requête + trace, rien d'autre).
- Écran : `jarvis-listen-trace.ts` (`newTurn`, `tu`, `now`), `jarvis-turn-clock.ts` (`turnId`, `pageId`),
  `jarvis.component.ts` (tirage aux ouvertures d'écoute et coupures, transmission), `jarvis-fast-brain.ts`
  (`ConverseBody`), `jarvis.service`/modèles (mesure, capture).

## Plan de test
- Unitaires backend : `JarvisListenTraceServiceTest` (heure serveur, `tu` valide/invalide, `now` absent, `recordServer`
  sous plafond) ; `JarvisCaptureServiceTest` (`turnId` rangé/écarté).
- Intégration : `JarvisLatencyApiIntegrationTest` (mesure avec `turnId`/`pageId` rangée) ;
  `JarvisFastBrainControllerTest` ou intégration existante (requête avec `turnId` ⇒ `srv_converse` ; 400 ⇒ rien).
- Écran : `jarvis-listen-trace.spec` (`newTurn`, `tu`, `now`), `jarvis-turn-clock.spec` (champs au rapport),
  `jarvis.component.spec` (même identifiant de l'ouverture d'écoute à la requête au cerveau rapide).
- Isolation : toute écriture se fait sous le compte du jeton ; aucune lecture nouvelle (la lecture arrive en SF-255-02).

## Préoccupations transversales
Aucune (ni auth, ni tenant, ni plans, ni navigation).

## Dépendances
Aucune SF bloquante. Questions ouvertes : aucune.
