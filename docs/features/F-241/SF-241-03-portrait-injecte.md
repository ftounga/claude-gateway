# Mini-spec — F-241 / SF-241-03 Jarvis vous connaît : portrait injecté, interdits, présentation, livrables

## Identifiant
`F-241 / SF-241-03`

## Feature parente
`F-241` — Jarvis vous connaît : le portrait (cadrage §4 D5, D6, D10, D12a, D17, D18 ; §6 SF-241-03)

## Statut
`in-progress`

## Date de création
2026-11-02

## Branche Git
`feat/SF-241-03-portrait-injecte`

---

## Objectif
Le cerveau rapide et Opus en mode Jarvis disposent du portrait entier et en respectent les bornes (interdits, présentation, livrables).

---

## Comportement attendu

### Cas nominal
- **`JarvisPortraitNote`** (pur) : `RULES` figées (D10, 8 bornes, rien de volatil) ; `block(portrait)` = `RULES` + chaque section non vide (« sensible », « mis à jour en mars 2026 ») + souvenirs `S<n> (noté en …)` — le portrait **entier**.
- **Cerveau rapide (D5)** : consigne = `CORE + PERSONNALITÉ` (inchangées) + `\n\n` + bloc, si le portrait est présent, allumé (D20) et non vide. Sinon, et toujours en présentation : **identique à l'octet près** à celle d'avant. Le bloc ne dépend que du portrait (version) : relu en cache d'un appel à l'autre ; pré-chauffage compris. Le bloc rejoint aussi l'**ancrage** de la porte des phrases (un prénom du portrait n'est pas un « fait non ancré »).
- **Message (volatil)** : la date qui compte aujourd'hui ou sous 7 jours (`[Jarvis — date qui compte, aujourd'hui : …]`, D3/D10-6) ; en présentation, la note figée D17.
- **Opus en mode Jarvis (D6)** : `[Jarvis — portrait de Monsieur, pour converser seulement]` + bloc joint **au message** après la consigne orale F-240 ; en présentation, la note figée seule. Consigne système du terminal inchangée.
- **Filet D12 (a)** : `SentenceGate` écarte toute phrase qui touche un terme de « À ne pas aborder » absent de la parole du PO (compté `portraitBlocked`) ; l'écran applique la même règle aux lignes 🎙 d'Opus (termes rendus par `GET /api/jarvis/portrait` → `blockedTerms`).
- **Présentation (D17)** : `presentation` transmis par l'écran à `POST /api/jarvis/converse` et au tour vocal Opus (`AtelierChatRequest.presentation`, suivi par la marque « né à la voix »).
- **Livrables (D18)** : un encart mail/message d'un tour Jarvis qui reprend un nom propre d'une section personnelle (hors « Qui vous êtes ») ou une suite de 4 mots du portrait porte `personal: true` → « élément personnel — à relire » dans « À envoyer » (tableau client) et dans l'encart d'absence du terminal ; journal `portrait_leak=1` une fois par message, sans contenu.
- **Mesure** : `jarvis_turn_timings.portrait_on`, `portrait_blocked` (migration `241-jarvis-turn-timings-portrait.xml`).
- **Bancs** : CONVERSATION accepte par scénario `portrait` (fictif), `presentation`, `recentAllusions` ; le juge gagne 3 défauts **graves** à zéro (sujet interdit, humour sur sujet sensible non ouvert, élément personnel en présentation) ; P01-P05 ajoutés. Banc de la parole : O9 (« rédige un mail au client », zéro fuite, contrôlé sans modèle) et O10 (« point client » avec portrait, interdit contrôlé sans modèle).

### Cas d'erreur
| Situation | Comportement |
|---|---|
| Scellé dormant / portrait illisible | consigne d'avant, aucun portrait (jamais d'erreur à l'oral) |
| Portrait coupé (D20) ou vide | consigne d'avant |
| Présentation | consigne d'avant + note figée ; aucun portrait, ni au cerveau rapide ni à Opus |
| Sujet interdit amené par le PO | la phrase passe (répondre sobrement relève du modèle) |
| Faux positif du détecteur de livrable | simple mention « à relire », jamais de blocage |

---

## Critères d'acceptation
- [ ] CA1 — sans portrait ou en présentation, consigne du cerveau rapide identique à l'octet près ; consigne système du terminal inchangée.
- [ ] CA2 — deux appels sans modification donnent la même consigne.
- [ ] CA3 — une phrase sur un sujet interdit non amené n'est jamais dite (cerveau rapide comme lignes 🎙 d'Opus).
- [ ] CA4 — en présentation, aucun élément du portrait n'est envoyé au modèle ni dit.
- [ ] CA5 — un encart qui reprend le portrait porte la mention ; O9 contrôle zéro fuite.
- [ ] CA6 — banc CONVERSATION ≥ 90 % et zéro défaut grave avec P01-P05 (**porte de déploiement**, jouée au déploiement).
- [ ] CA7 — coût estimé dans la PR (mesure réelle au banc / J+7).
- [ ] CA8 — aucun texte du portrait dans les journaux, `jarvis_bench_runs` ni `jarvis_turn_timings`.

## Périmètre — hors scope
La trace des allusions et la variété (SF-241-04) ; retenir / oublier / relancer (SF-241-05) ; le contenu d'une **page** créée pendant le tour (D18 étend le détecteur aux encarts seulement — voir arbitrage A8).

## Technique
| Méthode | URL | Changement |
|---|---|---|
| POST | `/api/jarvis/converse` | `presentation` (facultatif) ; `done.portrait` |
| POST | `/api/workspaces/{id}/chat[/stream]` | `presentation` (facultatif, voix seulement) |
| GET | `/api/jarvis/portrait` | `blockedTerms` |
| GET | `/api/jarvis/to-send`, absence | `personal` par encart |
| POST | `/api/jarvis/latency` | `portraitOn`, `portraitBlocked` |

Migration : `241-jarvis-turn-timings-portrait.xml` (additive, rollback = colonnes retirées).

### Préoccupations transversales
- [x] Contexte tenant — portrait lu par `JarvisFastBrainService.portraitFor(request.userId())`, `AtelierChatService.withPortrait(userId)`, `JarvisReturnService.portraitOf(userId)` : toujours le compte du tour ; au banc, **seulement** la fixture (`BENCH_PORTRAIT`), jamais `snapshot()`.
- [ ] Auth, plans, navigation — non touchés.

## Plan de test
- Unitaires : `JarvisPortraitNoteTest` (RULES figées, bloc daté, date qui compte, interdits amenés ou non, détecteur vrai/faux positifs) ; `JarvisFastBrainPortraitTest` (octet près sans portrait, fin de consigne, stabilité, présentation, interdit filtré/amené, ancrage, isolation A/B, banc = fixture seule, scellé illisible) ; `JarvisConversationBenchPortraitTest` (P01-P05, défaut grave ⇒ échec et porte, O9 fuite/interdit sans modèle, message Opus portrait/présentation).
- Écran : `jarvis-portrait.spec.ts` (filet `portraitBlocks`/`portraitSafe`) ; `jarvis.component.spec.ts` (ligne 🎙 interdite non dite ; drapeau présentation transmis).
- Non-journalisation : le seul journal ajouté (`portrait_leak=1`) ne porte aucun contenu ; `jarvis_turn_timings` ne reçoit que des booléens/compteurs.

## Notes et décisions (arbitrages réversibles)
- **A6** : la consigne du cerveau rapide ne change **pas** de texte (`VERSION` inchangée) : le bloc portrait est ajouté **après**, seulement s'il sert ; le CORE ne mentionne pas le portrait.
- **A7** : la date qui compte au banc vient du **contexte du scénario** (jamais de l'horloge réelle), pour un banc déterministe.
- **A8** : le détecteur D18 couvre les encarts mail/message (lecture à la volée, sans stockage) ; le contenu d'une **page** n'est pas encore comparé (résidu suivi) ; `portrait_leak` = journal CloudWatch sans contenu, compté une fois par message par pod.
- **A9** : le juge du banc reçoit le portrait **fictif** du scénario et ses trois nouveaux critères graves ; les scénarios sans portrait les rendent faux.
