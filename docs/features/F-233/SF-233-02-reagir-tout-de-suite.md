# Mini-spec — F-233 / SF-233-02 — Réagir tout de suite (D3, D6)

## Identifiant
`F-233 / SF-233-02` · Feature parente `F-233` (cadrage D3, D6) · Statut `in-review` · 2026-11-01
Branche `feat/SF-233-02-reagir-tout-de-suite`

## Objectif
> Jarvis réagit audiblement en moins d'une seconde après la fin de la parole, sans appel réseau à cet instant, et
> l'accusé « Je m'en occupe. » arrive à 2 s au lieu de 5.

## Comportement attendu
- **Clips** (liste fermée, phrases fixes, aucun contenu client) : `oui` → « Oui, Monsieur ? » (D-PO-8),
  `je-regarde` → « Je regarde. », `une-seconde` → « Une seconde. ».
- `GET /api/jarvis/clips/{name}` (accès Forge) : mp3 synthétisé **une fois par voix** (`SpeechProvider.synthesize`,
  voix de Jarvis), gardé en mémoire par la passerelle ; le premier appel est compté (journal d'usage + journée
  Jarvis) et plafonné ; les suivants ne coûtent rien. `Cache-Control: private, max-age=3600`.
- Écran : à l'ouverture, les trois clips sont chargés (URL locales) ; à la **fin de parole**, un clip de réaction est
  joué localement (alternance « Je regarde. » / « Une seconde. »), sauf pendant une confirmation (« oui » / « non »
  attendus) ; au **réveil** (« Jarvis » ou compagnon), « Oui, Monsieur ? » puis l'écoute. Marque `reaction` du
  chronomètre (SF-233-01) au premier son du clip.
- Accusé `ACK_DELAY_MS` 5 s → **2 s**.
- Silence de fin de phrase 1,4 s → **0,7 s**, réglable par appareil (`localStorage cg.jarvis.silenceMs`, borné
  300..3000 ms ; illisible → défaut).

## Cas d'erreur
| Situation | Comportement | Code |
|---|---|---|
| Clip inconnu | `not_found` (aucun texte libre n'est synthétisable par cette route) | 404 |
| Voix non configurée | `speech_unavailable` ; l'écran ne joue pas de clip | 503 |
| Plafond atteint, clip pas encore en cache | `jarvis_voice_budget` | 429 |
| Fournisseur en échec | `speech_failed` | 502 |
| Sans jeton | refus | 401 |

## Critères d'acceptation
1. Deux comptes qui demandent le même clip ne déclenchent qu'une synthèse ; le premier est compté.
2. Changer de voix produit de nouveaux clips (clé = voix).
3. La fin de parole déclenche un clip local avant la transcription ; aucun clip pendant une confirmation.
4. L'accusé part à 2 s.
5. Le silence de fin de phrase vaut 0,7 s par défaut, borné si réglé.

## Plan de test
- Intégration : `JarvisClipApiIntegrationTest` (mp3, cache par voix, compte, 404, 401) ; non-régression
  `JarvisVoiceApiIntegrationTest`, `JarvisVoiceServiceTest`.
- Écran : spec Jarvis (préchargement, clip à la fin de parole, accusé à 2 s) ; `jarvis-mic-meter.spec.ts` (0,7 s,
  bornes du réglage).

## Impacts
Endpoint `GET /api/jarvis/clips/{name}` ; `JarvisClipService` (nouveau), `JarvisVoiceService` (compte factorisé,
`synthesizeClip`), `JarvisVoiceController` ; écran `JarvisComponent`, `JarvisVoiceService` (front : `preloadClips`,
`playClip`), `jarvis-oral.ts`, `jarvis-mic-meter.ts`. Aucune table.

## Préoccupations transversales
Plans / limites : **oui** — composants : `JarvisVoiceService.prepare/synthesizeClip` (plafond vérifié avant appel),
`JarvisUsageService.addSpeech`, `SpeechUsage.record`. Auth, tenant, navigation : non.

## Hors périmètre
Fichiers audio statiques livrés dans l'image (les clips dépendent de la voix choisie en SF-233-07 : cache par voix à
la place, arbitrage réversible) ; réglage du silence dans une interface.
