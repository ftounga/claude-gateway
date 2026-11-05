# Mini-spec — F-248 / SF-248-05 Citer = montrer

## Identifiant
`F-248 / SF-248-05`

## Feature parente
`F-248` — Les pancartes (cadrage §4.3 ; D2, D3, D10 ; portes K2, K3)

## Statut
`done` (2026-11-05) · **Estimation :** 1,5 j (réestimé ¾ j sur le code) · **Branche :** `feat/SF-248-05-citer-montrer` · **Dépend de :** SF-248-04

## Objectif
Brancher l'événement `cards` sur l'écran et montrer une pancarte **au moment où la phrase qui la nomme commence à être
jouée** : lecture ciblée ⇒ tout le jeu dès la première phrase jouée ; lecture d'office ⇒ seules les citées.

## Comportement attendu

### Cas nominal
1. `jarvis-fast-brain.ts` relaie l'événement SSE `cards` (`onCards`) ; un `cards` malformé est ignoré.
2. `CardIndex` / `cardsCited(sentence, catalogue, deck)` (pur, patron `jarvis-name-matcher`) : normalisation casse /
   accents, frontière de mot, alias ≥ 3 car. hors mots courants ; clés techniques telles quelles (`PROJ-123`, `!42`,
   `#17`, `2.14.1`) tirées des alias et du titre ; ordinaux d'un jeu ouvert (« le deuxième ») ; alias ou clé ambigus
   (deux pancartes) ⇒ rien.
3. `CardShowing` (une par réponse) : catalogue reçu (le dernier remplace), phrases jouées, citées ; `visible()` rend
   **rien avant la première phrase jouée** (K2), puis ciblées + citées ; un catalogue reçu après une phrase est
   rapproché des phrases déjà jouées.
4. `JarvisComponent` : chaque phrase du cerveau rapide est mise en file avec `cardHooks` (crochet `onPlaying`, comme
   `sayingHooks` SF-234-11) ⇒ `showCards` + `litCards` (la pancarte nommée s'illumine) ; premier jeu montré ⇒
   `cardsLagMs` (première phrase jouée → jeu) et `cardsCount` dans le chronomètre du tour ; `c_tgt` / `c_cit` (une fois
   par pancarte).
5. Fin de réponse : rien n'a été dit ⇒ le jeu ciblé part à la fin (D10) ; aucun jeu pour la réponse ⇒ l'ancien reste
   60 s puis s'efface (D11) ; changement de sujet ⇒ effacé.
6. Réglage « Pancartes : non » ou présentation ⇒ l'événement est ignoré.

### Cas d'erreur
| Situation | Comportement attendu | Code HTTP |
|---|---|---|
| `cards` sans `responseId` ou `cards` non tableau | ignoré | — |
| Une réponse plus récente a commencé | l'ancien catalogue est ignoré | — |
| Galerie demandée ouverte | pastille (SF-04) | — |

## Contraintes de validation
Alias ≥ 3 caractères ; ordinaux 1-6 ; un alias ambigu n'allume rien.

## Critères d'acceptation
- [x] Rien avant la première phrase jouée, même ciblé.
- [x] Lecture d'office : seules les citées, à leur phrase ; catalogue tardif rattrapé.
- [x] Clés techniques, alias, ordinaux ; prudence (ambigu, mot courant).
- [x] `cards_lag_ms` / `cards_count` posés au premier jeu.

## Plan de test
- Unitaires : `jarvis-card-cite.spec.ts` (rapprocheur 4, jeu au fil de la voix 2, relais SSE 1).
- Non-régression : `jarvis.component.spec.ts`, `jarvis-fast-brain.spec.ts`, suite complète (4618 / 4618).
- Isolation : sans objet (écran).

## Impact
- Frontend : `cards/jarvis-card-cite.ts` ; `jarvis-fast-brain.ts` (`onCards`) ; `jarvis.component.ts` (`cardHooks`, `receiveCards`, `refreshCards`, `endCards`, effacement au changement de sujet).
- Préoccupations transversales : aucune.

## Arbitrages (réversibles)
- L'« essentiel affiché » (étage 2, F-240) n'est pas passé au rapprocheur : il est lu sur l'appareil à la demande (« ⤵ ») et ses phrases jouées passent déjà par `cardHooks`.
- Une pancarte citée reste montrée jusqu'à la fin de la réponse (pas de retrait à la phrase suivante) : seul l'éclairage `lit` suit la phrase.

## Hors périmètre
Le plein écran, la comparaison et les ordres vocaux (SF-06) ; les lignes 🎙 d'Opus (SF-08).
