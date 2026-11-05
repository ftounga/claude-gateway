# Mini-spec — F-248 / SF-248-08 Opus en mode Jarvis

## Identifiant
`F-248 / SF-248-08`

## Feature parente
`F-248` — Les pancartes (cadrage §4.8 ; D3, D4 ; portes K1, K2, K6, Z10)

## Statut
`done` (2026-11-05) · **Estimation :** 1,25 j (réestimé ½ j sur le code) · **Branche :** `feat/SF-248-08-opus` · **Dépend de :** SF-248-07, SF-247-11 (mergée)

## Objectif
Quand Opus répond en mode Jarvis, les clés techniques qu'il **dit** dans ses lignes 🎙 (`PROJ-123`, `projet!42`) font
apparaître leurs pancartes, lues **après le premier mot** par `GET /api/jarvis/cards?keys=` — consigne du terminal et
outils intacts.

## Contrat API
`GET /api/jarvis/cards?keys=PROJ-123,projet!42[&subjectId=<uuid>]`
- 200 `JarvisCard[]` (ciblées) — faits **déjà au registre** du compte ; une clé inconnue ne rend rien.
- Clés : 6 au plus, distinctes, reconnues (ticket `[A-Za-z][A-Za-z0-9]{1,15}-\d{1,7}` ⇒ `JIRA:` ; revue `…!\d+` ⇒
  `GITLAB:` / `GITHUB:`) ; le reste ignoré. 400 si aucune clé reconnue ; 503 coupe-circuit.
- `subjectId` du compte ⇒ lecture bornée au poste du sujet ; sinon (ou sujet d'un autre compte) les postes du compte.
- Ouvert à la portée `jarvis-compagnon` (`GET /jarvis/cards`).

## Comportement attendu

### Cas nominal
1. `JarvisCardKeyLookup` : par clé, par candidat, par poste, `JarvisStateRegistry.byKey(user_id, host_id, clé)` (index
   Z10), filtre exact, première trouvée ; fiches de `JarvisStateCards` (badge = heure du relevé).
2. Écran : chaque ligne 🎙 d'Opus est mise en file avec `opusCardHooks` — au `onPlaying` (la phrase **est dite**), ses
   clés neuves (`opusKeys`, une fois par tour, 6 au plus) sont demandées ; les pancartes revenues rejoignent le jeu du
   tour (`OpusCards`), `c_cit`, `cardsLagMs` (phrase jouée → pancarte).
3. Poste éteint (`JarvisStateNote`, SF-247-11) : les faits de la note sont au registre — leurs clés dites passent par
   la même voie (arbitrage ci-dessous). **Aucun changement** au tour d'Opus côté serveur : consigne système, outils et
   message du terminal identiques à l'octet (tests SF-247-11 et SF-241 rejoués).

### Cas d'erreur
| Situation | Comportement attendu | Code HTTP |
|---|---|---|
| Aucune clé reconnue | refus | 400 |
| Plus de 6 clés | les 6 premières | 200 |
| Clé d'un autre compte | rien | 200 (`[]` ou sans elle) |
| Coupe-circuit | indisponible | 503 |
| Présentation / réglage « non » | aucune demande | — |

## Contraintes de validation
≤ 6 clés par demande et par tour ; ≤ 12 postes lus ; ≤ 50 faits par clé.

## Critères d'acceptation
- [x] Clés citées déjà au registre ⇒ pancartes ciblées ; clé inconnue ⇒ rien.
- [x] Isolation : Bob ne lit que son registre ; le sujet de Bob ne borne rien pour Alice.
- [x] Jamais avant le premier mot : la demande part au `onPlaying` de la ligne.
- [x] Consigne du terminal intacte (aucun code serveur d'Opus touché).

## Plan de test
- Intégration : `JarvisCardKeysApiIntegrationTest` (3).
- Unitaires : `jarvis-card-opus.spec.ts` (2).
- Non-régression : `JarvisStateNoteTest`, `AtelierChatService*`, `JarvisCompanionScopeTest`, suites complètes.

## Impact
- Endpoints : `GET /api/jarvis/cards` (nouveau) ; `JarvisCompanionScope.ROUTES` (+1).
- Backend : `jarvis/cards/JarvisCardKeyLookup` ; `JarvisCardController` (+1 méthode, constructeur unique).
- Frontend : `cards/jarvis-card-opus.ts`, `jarvis-card-client.ts` (`byKeys`), `jarvis.component.ts` (`opusCardHooks`).
- Préoccupations transversales : **Auth / Principal** — route ajoutée à la liste fermée (`POST` reste fermé). **Contexte tenant** — `RunnerHostService.list(userId)`, `WorkspaceRepository.findByIdAndUserId`, `JarvisStateRegistry.byKey(user_id, host_id)`.

## Arbitrages (réversibles)
- **Une seule voie pour Opus** : les clés dites. Les faits de `JarvisStateNote` (poste éteint) sont au registre ; ceux qui sont cités par leur clé reviennent par la même lecture. Porter un événement `cards` dans le flux du tour d'Opus aurait touché `AtelierChatService` (le chemin de tout tour) pour un gain marginal (citation par alias seulement).
- Les numéros seuls (`#17`, `!42`) et les versions ne sont pas demandés : sans projet, ils ne désignent rien au registre.

## Hors périmètre
Les accès (SF-09) ; le banc (SF-10).
