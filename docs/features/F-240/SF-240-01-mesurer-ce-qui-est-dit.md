# Mini-spec — F-240 / SF-240-01 — Mesurer ce qui est dit

## Identifiant
`F-240 / SF-240-01` · Feature parente `F-240` « Jarvis va à l'essentiel » · Statut `in-review` · 2026-11-02 ·
Branche `feat/SF-240-01-mesurer-ce-qui-est-dit`

## Objectif
> Savoir combien Jarvis parle (mots, secondes, coupe, invitation, étage atteint), avant et après F-240, en prod et au
> banc — sans rien changer à ce qui est dit.

## Comportement attendu
### Cas nominal
- **Écran** : chaque tour vocal déclare, via `POST /api/jarvis/timings` (existant), sous le `user_id` de l'appelant :
  `spokenWords`, `spokenChars`, `spokenMs` (durée audio de la réponse dite, hors annonce), `announceWords` (lignes 🎙
  dites avant la dernière : l'annonce avant outils, D3), `oralCut`, `invitation` (posés par SF-240-02), `depthReached`
  (1 à 3) et `depthVia` (`voix` | `toucher`, posés par SF-240-03/04). **Jamais le texte.**
  - Tour Opus direct : la réponse dite = la ligne 🎙 finale ; l'annonce = les phrases 🎙 dites sur d'autres lignes.
  - Réponse du cerveau rapide : la réponse dite = ce qui a été dit.
  - Échange délégué : l'annonce de délégation compte en `announceWords` ; la mesure **attend que la fin de la
    délégation soit dite** (sa phrase compte en `spokenWords`), au plus 10 min.
  - **La mesure d'un tour fini est gardée** jusqu'au tour suivant, la sortie de l'écran ou 10 min : l'étage atteint
    ensuite (« dis-m'en plus », toucher) y entre (préparation de SF-240-03/04).
- **Mot** (même règle des deux côtés, `JarvisSpokenWords` / `spokenWordCount`) : groupe séparé par des blancs qui
  contient une lettre ou un chiffre ; l'élision et le trait d'union ne coupent pas ; « Monsieur » compte.
- **`GET /api/admin/jarvis/latency`** (ADMIN, **ses** tours) ajoute `spoken` (réponses mesurées, p50/p90 des mots et
  de la durée dite, p90 des mots d'annonce, taux de coupe et d'invitation, étages atteints et chemins) et `oralLines`
  (p50/p90 des mots des lignes 🎙 **écrites** par Opus dans **ses** réponses de la fenêtre — la base de départ,
  relisible avant comme après F-240 ; réponses rapides exclues).
- **Banc CONVERSATION** : chaque tour jugé porte `words` ; le rapport expose `fastWordsP50` / `fastWordsP90` (réponses
  du cerveau rapide seulement), **à titre informatif** : la porte est inchangée.

### Cas d'erreur
| Situation | Comportement attendu | Code HTTP |
|---|---|---|
| `spokenWords` / `spokenChars` / `announceWords` négatif | Refus de la mesure | 400 |
| `spokenMs` hors [0, 600 000] | Refus (même borne que les marques) | 400 |
| `depthReached` hors 1-3 | Refus | 400 |
| `depthVia` hors liste (`voix`, `toucher`) | Refus | 400 |
| `depthVia` sans `depthReached` | Ignoré (null) | 204 |
| Longueur énorme | Plafonnée (2 000 mots, 20 000 caractères) | 204 |
| Lecture par un non-ADMIN | Refus | 403 |

## Critères d'acceptation
- [ ] Un tour Jarvis (direct, rapide, délégué) déclare les mots et la durée dite ; l'annonce est comptée à part.
- [ ] L'administrateur lit p50/p90 sur ses tours ; un autre compte n'y figure jamais ; non-ADMIN ⇒ 403.
- [ ] Le banc CONVERSATION rend le p90 des mots du cerveau rapide (informatif).
- [ ] La base de départ figure dans la PR (relevé lecture seule des 14 derniers jours) et reste lisible via `oralLines`.

## Périmètre — hors scope
Changer ce qui est dit (SF-240-02) ; l'écran en étages (SF-240-03) ; « dis-m'en plus » (SF-240-04).

## Contraintes de validation
| Champ | Obligatoire | Valeurs | Normalisation |
|---|---|---|---|
| `spokenWords`, `announceWords` | Non | entier ≥ 0 | plafonné à 2 000 |
| `spokenChars` | Non | entier ≥ 0 | plafonné à 20 000 |
| `spokenMs` | Non | [0, 600 000] | — |
| `oralCut`, `invitation` | Non | booléen | — |
| `depthReached` | Non | 1, 2, 3 | — |
| `depthVia` | Non | voix, toucher | insensible à la casse ; ignoré sans `depthReached` |

## Technique
- Endpoints modifiés : `POST /api/jarvis/timings` (champs facultatifs, rétrocompatible) ; `GET
  /api/admin/jarvis/latency` (blocs `spoken`, `oralLines` ajoutés).
- Table : `jarvis_turn_timings` + `spoken_words`, `spoken_chars`, `spoken_ms`, `announce_words` (int), `oral_cut`,
  `invitation` (boolean), `depth_reached` (int), `depth_via` (varchar 8) — migration
  `240-jarvis-turn-timings-spoken.xml` (additive, nullable, rollback = retrait des colonnes).
- Lecture `atelier_messages` : `findTop500ByUserIdAndRoleAndCreatedAtGreaterThanEqualAndContentContaining…`, **sous
  `user_id`**, bornée à 500.
- Code : `JarvisSpokenWords` (nouveau), `JarvisTurnTiming`, `JarvisLatencyService`, `JarvisConversationBench`,
  `JarvisFastBrainBenchService` ; écran : `TurnClock` (`said`, `reach`, `sinceMark`), `OralStream` (`pushLines`,
  `lastLine`, `spokenOutside`), `JarvisComponent` (mesure gardée, mesure d'un échange délégué).

## Plan de test
- Unitaires : `JarvisSpokenWordsTest` (ponctuation, « Monsieur », chiffres, apostrophes, ligne réelle) ;
  `JarvisConversationBenchTest` (mots par tour, p90 du cerveau rapide) ; specs `jarvis-turn-clock` (said, reach,
  sinceMark, comptage) et `jarvis-oral` (lignes 🎙).
- Intégration : `JarvisLatencyApiIntegrationTest` (enregistrement et lecture des nouvelles colonnes, bornes 400,
  `oralLines`, 403 hors ADMIN).
- Isolation `user_id` : les tours et les réponses de B ne sont jamais comptés pour A (test).
- Liquibase : la migration passe sur la base de test (contexte Spring complet).
- Écran : spec du composant (tour direct avec annonce, tour rapide, échange délégué mesuré à la fin dite).

## Dépendances
Aucune (SF-237-09 mergée). Aucune question ouverte.

## Notes et décisions
- **Mesure gardée jusqu'au tour suivant** (arbitrage) : l'étage atteint se produit après la fin du tour ; garder la
  ligne évite une seconde ligne (qui fausserait le nombre de tours). Perte possible si l'onglet est tué (pas de
  `beforeunload`) : accepté, la mesure est statistique.
- **Base de départ** : relevé lecture seule (`q-prod-ro.sh`, transaction en lecture seule) des réponses portant une
  ligne 🎙 sur 14 jours : **7 réponses d'Opus, p50 28 mots, p90 40 mots, max 252 caractères** (dont la réponse de
  4 950 caractères du 01/11 18:44 : 35 mots dits).
