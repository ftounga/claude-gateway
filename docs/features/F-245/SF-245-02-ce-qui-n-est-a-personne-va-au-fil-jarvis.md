# Mini-spec — F-245 / SF-245-02 Ce qui n'est à personne va au fil Jarvis

## Identifiant
`F-245 / SF-245-02`

## Feature parente
`F-245` — Le fil central de Jarvis (cadrage `CADRAGE-F-245-le-fil-central-de-jarvis.md`, D2, D3, D4, D7, D11)

## Statut
`done` (2026-11-03) · **Estimation :** 1 j · **Branche :** `feat/SF-245-02-ce-qui-n-est-a-personne` · **Dépend de :** SF-245-01

## Objectif
« Bonjour », le tour d'horizon et le perso vivent dans le fil Jarvis : plus rien ne s'écrit dans un sujet sans tâche pour lui.

## Comportement attendu

### Cas nominal
1. **Échanges du cerveau rapide (D2a)** : `JarvisFastExchangeWriter.write(userId, said, answered)` écrit dans le **fil Jarvis** du compte (créé s'il manque, fil commun), avec ou sans sujet en vue. Plus jamais dans le sujet en vue.
2. **Délégation générale (D2b, D4)** : l'outil `deleguer` accepte `sujet: "jarvis"` (consigne **v13**) pour une demande qui ne vise aucun sujet précis — tour d'horizon de plusieurs clients, question générale ou personnelle. Le routage la dirige vers le fil Jarvis, `chantier = null`, **sans** `ASK_SUBJECT`. L'écran reçoit `delegated.jarvisThread = true` et ouvre le tour Opus dans le fil Jarvis **sans changer le sujet en vue**.
3. **Délégation ciblée (D3)** : inchangée — `sujet` vide ⇒ le sujet en vue (et son chantier) ; un sujet nommé ⇒ vérifié `findByIdAndUserId`.
4. **Doute (D4)** : demande qui vise un sujet sans qu'on sache lequel, sans sujet en vue ⇒ `ASK_SUBJECT` (inchangé).
5. **Contexte du cerveau rapide (D11)** : bloc « Derniers échanges du fil Jarvis » (6 au plus) avant celui du sujet en vue (6 au plus), dans le message ; le fil n'est que lu (`idOf`), jamais créé ici. Les réponses du cerveau rapide y sont étiquetées « Jarvis », celles d'Opus « Opus ».
6. **Opus dans le fil Jarvis (D7)** : la note d'univers (`JarvisFastContextBuilder.reference`, ≤ 9 000 caractères) et la règle « pour agir dans un chantier, dis que tu le confies » sont jointes **en tête du message** ; la consigne système reste celle d'un sujet hébergé à l'octet près.
7. **Origine (F-244)** : tout tour du fil Jarvis, même tapé, est d'origine `JARVIS` (budget de Jarvis, journal `JARVIS/OPUS`). La marque `via = JARVIS` du message reste réservée à la voix (mémoire du jour inchangée, D10).
8. **Banc** : la décision à blanc garde une cible provisoire (`JARVIS_THREAD_TARGET`) et ne crée aucun fil.

### Cas d'erreur
| Situation | Comportement attendu | Code HTTP |
|---|---|---|
| Service du fil indisponible | délégation générale ⇒ `ASK_SUBJECT` (comportement d'avant) ; trace non écrite (best-effort) | 200 |
| `sujet: "jarvis"` avec un `chantier` | chantier ignoré (fil commun) | 200 |
| Sujet d'autrui en vue | 404 sans appel modèle (inchangé) | 404 |
| Univers illisible | message sans note, tour normal | 200 |

## Critères d'acceptation
- [x] « Bonjour », avec un sujet en vue, ne crée **aucun** message dans ce sujet (intégration).
- [x] « Fais-moi un tour d'horizon de mes clients » est délégué au fil Jarvis, sans question (unitaire, avec ou sans sujet en vue).
- [x] « Relance le déploiement » avec un sujet en vue est délégué à ce sujet (non-régression).
- [x] « Relance le déploiement » sans sujet en vue et sans nom donne `ASK_SUBJECT` (test existant).
- [x] L'univers n'est pas dans la consigne système (test d'égalité) ; il est dans le message du fil Jarvis seulement.
- [x] Isolation A/B : l'échange d'Alice ne crée rien chez Bob.

## Plan de test
- **Unitaires** `JarvisFastBrainServiceTest` (+6 : général sans/avec sujet en vue, ciblé, repli sans service, banc sans création, consigne v13 ; trace dans le fil Jarvis) ; `JarvisFastContextBuilderTest` (+2 : bloc du fil Jarvis, rien créé) ; `AtelierChatServiceJarvisThreadPromptTest` (+1 : univers dans le message, système intact).
- **Intégration** `JarvisFastBrainApiIntegrationTest` (+1 : « Bonjour » avec sujet en vue ⇒ 0 message dans le sujet, 2 dans le fil Jarvis d'Alice, rien chez Bob).
- **Non-régression** de `deleguer(sujet, chantier)` : tests existants du routage (sujet nommé, précision au tour vivant, reformulation offerte).
- **Frontend** `jarvis.component.spec.ts` (+1 : délégation au fil, sujet en vue inchangé).
- Suites complètes + build.

## Impact
Aucune table, aucune migration. Endpoint modifié (additif) : flux `POST /api/jarvis/converse`, événement `delegated.jarvisThread`. Backend : `JarvisFastExchangeWriter`, `JarvisFastBrainService`, `JarvisFastBrainController`, `JarvisFastContextBuilder`, `JarvisFastBrainPrompt` (v13), `AtelierChatService` (`withJarvisUniverse`, `jarvisOrigin`). Frontend : `jarvis-fast-brain.ts`, `jarvis.component.ts`.

**Préoccupations transversales** : Plans / limites — **oui** (origine `JARVIS` des tours du fil Jarvis). Composants impactés : `AtelierChatService` (porte `JarvisBudgetService.gateDelegation`, `AtelierTurnBudget.byok`, `quotaService.recordUsage(…, JARVIS_OPUS)`, `jarvisUsage.addOpus`, `recordCutTurn`) ; aucun autre appel de limite touché. Contexte tenant : le fil est toujours résolu sous le `user_id` du principal (`JarvisThreadService.open/idOf`). Auth, navigation : non.

## Arbitrages (documentés dans la PR)
- **`sujet: "jarvis"` plutôt qu'un nouveau champ requis** dans l'outil : schéma inchangé, une valeur de plus documentée ; réversible.
- **Cible provisoire résolue au routage** : le banc reste à blanc (aucun fil créé).
- **Une délégation d'office (panne, délai) garde sa cible d'avant** (sujet en vue, sinon question) : la deviner « générale » risquerait d'envoyer une tâche ciblée dans le fil Jarvis (justesse avant coût). Mesuré au banc en SF-245-05.
- **Tours tapés du fil Jarvis** : origine `JARVIS` pour le coût et le budget, mais pas de consigne orale ni de marque `via` (ce ne sont pas des tours à la voix).

## Hors périmètre
La ligne-lien (SF-245-03) ; le volet « Fil » (SF-245-04) ; le banc de routage et la compaction sans `FAST` (SF-245-05).
