# Mini-spec — F-231 / SF-231-01 La voix de Jarvis

## Identifiant
`F-231 / SF-231-01` — Feature parente : F-231 « Le mode Jarvis » (cadrage, décisions **D2, D8, D9**).
Statut : `ready` — 2026-11-01 — Branche `feat/SF-231-01-la-voix-de-jarvis`.

## Objectif
Donner une voix à Jarvis : une route qui lit à voix haute une phrase courte, en flux, avec une voix masculine posée par défaut, au coût compté en euros et plafonné par mois.

## Constat de l'existant (vérifié avant de cadrer)
La synthèse vocale **existe déjà** : `SpeechProvider` (F-215 / SF-215-01, Provider Independence, OpenAI `gpt-4o-mini-tts`), `SpeechProperties` (`app.speech.*`, clé = celle du STT, coupe-circuit), `SpeechUsage` (journal d'usage `UsageLedgerService`). Le `SpeechSynthesisProvider` du cadrage **est** ce `SpeechProvider` : on le **réutilise**, on ne le duplique pas. Seul ajout à l'interface : une lecture **en flux** (méthode par défaut, rétrocompatible).

## Comportement attendu

### Cas nominal
1. `POST /api/jarvis/speech` `{ "text": "…", "workspaceId"?: uuid }` → `200 audio/mpeg`, corps **diffusé en flux** (les premiers octets partent dès que le fournisseur les rend).
2. Voix par défaut **`onyx`** (grave, posée) et consignes de ton Jarvis fixes (« voix masculine posée, calme… ») — réglables par `app.jarvis.voice.*`. Modèle = celui de la voix off (`gpt-4o-mini-tts`, **voix standard** : ≈ 0,015 $ / 1 000 caractères).
3. Le texte est **borné** (`max-chars`, défaut 600) : au-delà, coupé à la dernière fin de phrase avant la borne. Jarvis lit des phrases courtes (D3) ; le détail reste à l'écran.
4. Chaque lecture est **comptée** : (a) une ligne au journal d'usage (`SpeechUsage` → `UsageLedgerService`, sujet rattaché si fourni et possédé), (b) le cumul du jour dans `jarvis_usage_days` (caractères lus, coût en $). 
5. **Plafond mensuel** en euros (`app.jarvis.voice.monthly-budget-eur`, défaut **15 €**) : atteint ⇒ `429 jarvis_voice_budget`, aucun appel fournisseur. L'écran retombe sur le texte.
6. `GET /api/jarvis/voice` → `{ enabled, voice, monthlyBudgetEur, monthSpentEur, budgetReached }` (montants en **€**).

### Contrat API
```json
// POST /api/jarvis/speech  (requête)
{ "text": "C'est fait : l'étape 3 est validée.", "workspaceId": "…" }
// → 200 audio/mpeg (flux)
// GET /api/jarvis/voice
{ "enabled": true, "voice": "onyx", "monthlyBudgetEur": 15.00, "monthSpentEur": 0.42, "budgetReached": false }
```

### Cas d'erreur
| Situation | Comportement | Code |
|---|---|---|
| Sans jeton | refus | 401 |
| Sans accès Forge | refus | 403 |
| Texte vide | `validation_error` | 400 |
| Voix non configurée / coupe-circuit | `speech_unavailable` | 503 |
| Plafond du mois atteint | `jarvis_voice_budget` | 429 |
| Fournisseur en échec (avant le 1er octet) | `speech_failed` | 502 |
| `workspaceId` d'autrui | ignoré pour le rattachement (jamais d'erreur qui révèle son existence) | 200 |

## Contraintes de validation
Texte : 1 à 600 caractères lus (coupé au-delà) ; requête ≤ 4 000 caractères (400 au-delà). Budget mensuel ≥ 0 € (0 = voix coupée).

## Critères d'acceptation
- [ ] La route rend de l'`audio/mpeg` avec la voix `onyx` par défaut.
- [ ] Le texte long est coupé à une fin de phrase avant 600 caractères.
- [ ] Chaque lecture ajoute une ligne au journal d'usage et au cumul du jour.
- [ ] Plafond atteint ⇒ 429 sans appel au fournisseur.
- [ ] `GET /api/jarvis/voice` rend la dépense du mois **en euros**.
- [ ] Ni le texte ni la clé ne sont journalisés.

## Plan de test
- **Unitaires** : `JarvisVoiceServiceTest` (coupe à la phrase, plafond, comptage, rattachement au seul sujet possédé), `OpenAiSpeechProviderTest` (flux).
- **Intégration** : `JarvisVoiceApiIntegrationTest` (200 audio/mpeg, 400, 401, 503, 429, cumul du jour, isolation : le cumul et le budget d'Alice n'apparaissent pas chez Bob, sujet de Bob ignoré).

## Tables / endpoints / composants impactés
- **Table** `jarvis_usage_days` (migration **190**) : `user_id`, `day`, `chars_spoken`, `speech_cost_usd`, `sessions`, `spoken_seconds`, `wakes` — unique `(user_id, day)`. Les trois dernières colonnes servent la mesure d'adoption (SF-231-05).
- **Endpoints** : `POST /api/jarvis/speech`, `GET /api/jarvis/voice`.
- **Composants** : `SpeechProvider` (+ `synthesizeStream` par défaut), `OpenAiSpeechProvider`, nouveau paquet `fr.claudegateway.jarvis`.

## Préoccupations transversales
- **Plans / limites** ✅ — composants : `UsageLedgerService` (chaque lecture y est inscrite via `SpeechUsage`), `CostBudget` (**non modifié** : il ne refuse rien par conception, F-133) ; le plafond propre à la voix est `JarvisVoiceService` (`monthly-budget-eur`).
- **Contexte tenant** ✅ — cumul et budget lus sous `user_id` ; `workspaceId` rattaché seulement si `requireOwned` passe.
- Auth / navigation : non concernées.

## Confidentialité (D8 — décision par défaut, à valider par le PO)
Le texte lu part chez le fournisseur de synthèse, **même régime que la dictée F-145** (déjà chez le même fournisseur). Rien n'est conservé ni journalisé côté passerelle (ni texte ni audio). **Flagué au récap.**

## Hors périmètre
L'écran (SF-231-02), la phrase orale (SF-231-03), une voix premium (réglable par configuration, pas de code).
