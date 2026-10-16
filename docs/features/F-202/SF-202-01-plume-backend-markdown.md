# Mini-spec — F-202 / SF-202-01 Plume backend : sortie Markdown, troncature détectée, matière enrichie

## Identifiant
`F-202 / SF-202-01`

## Feature parente
`F-202` — Plume refondue : une demande vraiment meilleure (cadrage `CADRAGE-F-202-plume-refonte.md`)

## Statut
`in-progress` — 2026-10-16

## Branche Git
`feat/SF-202-01-plume-markdown-back`

---

## Objectif
« ✨ Enrichir » rend un prompt **rédigé en Markdown**, complet (jamais tronqué, jamais du JSON brut), nourri d'une matière plus riche et bornée.

## Comportement attendu

### Cas nominal
1. `POST /api/workspaces/{id}/enrich-prompt` (contrat **inchangé** : `enrichedPrompt`, `toConfirm`, `sources`).
2. `requireOwned(userId, workspaceId)` d'abord (404 indiscernable), coupe-circuit ensuite.
3. Matière (donnée, jamais consigne ; chaque bloc borné) :
   - titre du sujet ;
   - **passation du fil** (F-117, `workspaces.chat_thread_summary`) si présente — début, ≤ 6 000 car. ;
   - **plan / chantier** (F-176) si le sujet est **guidé** — phase, chantier, diagnostic, étapes et statut — ≤ 3 000 car. ;
   - `STATE.md` (≤ 4 000) et sommaire de la carte (≤ 3 000), inchangés ;
   - **fichiers touchés récemment** : mémoire de résolutions du terminal (`resolution_memory`, filtre `user_id` + `workspace_id`, 5 dernières entrées, ≤ 20 chemins distincts) ;
   - **fil récent** : 12 derniers messages (au lieu de 6) ; réponses de l'agent par leur **début** (l'essentiel F-126, ≤ 2 000) + leur fin (≤ 600) ; messages utilisateur ≤ 1 500 ; budget global du fil ≤ 16 000 car. (les plus récents gardés en priorité) ; retours à la ligne **conservés** ;
   - la phrase à enrichir.
4. Consigne système **stable** (aucune donnée volatile — préfixe en cache) : produire un prompt Markdown rédigé, en phrases complètes, avec les sections `## Objectif`, `## Contexte établi`, `## Ce qu'il faut faire`, `## Contraintes et garde-fous`, `## Vérification attendue`, `## Points à confirmer` ; « détailler sans inventer » (supposé ⇒ « (à confirmer) »).
5. Appel via `AIProvider` (modèle de la boucle ou réglage propre), plafond **6 000** jetons de sortie.
6. `ChatCompletionResult` porte un drapeau **provider-neutre** `truncated` (Anthropic : `stop_reason = max_tokens`, mappé dans `AnthropicProvider` seul). Si tronqué ⇒ **un** nouvel essai à **10 000** ; encore tronqué ⇒ erreur claire, **jamais un fragment**.
7. Sortie : clôture ```` ```markdown ```` retirée ; « Points à confirmer » lus depuis leur section (repli : lignes portant « à confirmer ») ; texte rendu tel quel, retours à la ligne conservés.
8. Chaque appel (essai et nouvel essai) est inscrit au journal d'usage à son coût réel.

### Cas d'erreur
| Situation | Comportement | HTTP |
|---|---|---|
| Terminal d'autrui / inconnu | 404, aucun appel | 404 |
| Texte vide / > 4 000 car. | message explicite | 400 |
| Coupe-circuit fermé | aucun appel | 503 |
| Sortie vide ou objet JSON (ancien format) | « L'enrichissement a échoué… » | 502 |
| Tronqué deux fois | « La demande enrichie dépassait la longueur prévue… Votre texte est intact. » | 502 |
| Fournisseur en échec | message neutre (détail fournisseur non relayé) | 502 |
| Source annexe en panne (STATE, carte, parcours, mémoire) | ignorée, l'enrichissement part | 200 |

## Critères d'acceptation
- [ ] Sortie Markdown restituée telle quelle (multiligne), sans accolade JSON ; un objet JSON en sortie n'est jamais injecté.
- [ ] `stop_reason = max_tokens` ⇒ `truncated = true` dans `AnthropicProvider` ; le métier ne connaît que `truncated`.
- [ ] Tronqué ⇒ un nouvel essai au plafond supérieur ; tronqué deux fois ⇒ 502 avec message dédié ; les deux appels journalisés.
- [ ] `MAX_TOKENS ≥ 4 000` (6 000).
- [ ] Matière : passation, plan guidé, fichiers touchés, début des réponses de l'agent, 12 messages — tous bornés ; sources listées en toutes lettres.
- [ ] Isolation : `requireOwned` avant toute lecture ; mémoire de résolutions lue filtrée `user_id` + `workspace_id`.
- [ ] Consigne système constante (aucune donnée volatile).
- [ ] Aucun contenu journalisé (classe d'erreur seule).

## Plan de test
- **Unitaires** (`PlumeServiceTest`) : nominal Markdown + sources ; toConfirm depuis la section ; repli lignes ; sortie JSON ⇒ exception ; troncature ⇒ 2ᵉ essai 10 000 ; double troncature ⇒ `PlumeTruncatedException` ; matière : passation, plan guidé (et pas en Libre), fichiers distincts bornés, début des réponses de l'agent présent, budget du fil ; sources en panne ignorées.
- **Fournisseur** (`AnthropicProviderTest` ou équivalent) : `stop_reason: max_tokens` ⇒ `truncated`, `end_turn` ⇒ non.
- **Intégration** (`PlumeApiIntegrationTest`) : nominal Markdown ; double troncature ⇒ 502 message dédié ; **isolation** Alice/Bob (404, aucun appel) conservée.

## Impacts
- Endpoints : `POST /api/workspaces/{id}/enrich-prompt` (contrat inchangé).
- Classes : `PlumeService`, `PlumeController`, `PlumeTruncatedException` (nouvelle), `ChatCompletionResult` (+`truncated`, constructeurs existants conservés), `AnthropicResponse` (+`stop_reason`), `AnthropicProvider.toResult`, `AtelierMessageRepository` (+`findTop12…`), `ResolutionMemoryRepository` (+`findTop5ByUserIdAndWorkspaceId…`).
- Tables : aucune nouvelle ; aucune migration.

## Préoccupations transversales
- [ ] Auth / Principal — non touché.
- [ ] Contexte tenant — non touché (lectures nouvelles filtrées `user_id`).
- [ ] Plans / limites — non touché (usage journalisé comme avant, pas de pré-vol).
- [ ] Navigation — non touché.

## Hors périmètre
- Écran (SF-202-02) ; paramètre d'effort/raisonnement étendu (non porté par `ChatCompletionRequest` aujourd'hui — modèle principal conservé) ; streaming.
