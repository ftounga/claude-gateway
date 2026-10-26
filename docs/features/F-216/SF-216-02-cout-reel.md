# Mini-spec — F-216 / SF-216-02 Le coût réel d'une vidéo, mesuré et rattaché

## Identifiant
`F-216 / SF-216-02` — Feature parente : F-216 « Le coût d'une vidéo, annoncé avant et mesuré après » — Statut : `in-progress` — 2026-10-26
Branche : `feat/SF-216-02-cout-reel`

## Objectif
Ce qu'une vidéo a réellement coûté (tours de l'agent, voix, Plume du sujet pendant sa fabrication) est rattaché à ses
versions et à son devis, et rendu par l'API à côté de l'estimation, avec le total du mois.

## Comportement attendu
### Cas nominal
1. **Écoute du journal d'usage** : `UsageLedgerService`, après avoir écrit une ligne, prévient les écouteurs
   (`UsageRecordedListener`, interface du paquet `quota`) ; une erreur d'écouteur est avalée (le relevé n'échoue jamais).
2. **Imputation** (`VideoCostTracker.onRecorded`, transaction propre) — ligne d'un compte **et** d'un sujet, coût > 0 :
   - s'il existe dans ce sujet des versions **en attente** (publiées pendant le tour en cours), le coût est partagé à
     parts égales entre elles (version **et** vidéo) et ajouté à la dépense de leur devis ;
   - sinon, il va au **devis ouvert le plus récent** du sujet (`spent_usd` et `unassigned_usd` += coût) ;
   - un devis sans activité depuis `idle-close-hours` (48 h) est clos au lieu de recevoir le coût.
3. **Publication** (`VideoService.commit`, même transaction) : le devis de rattachement est la retouche ouverte de
   **cette** vidéo s'il y en a une, sinon le devis vidéo/série ouvert le plus récent du sujet ; la version reçoit
   `quote_id`, `cost_usd = unassigned_usd` (remis à 0), passe **en attente** ; la vidéo cumule `cost_usd` et, la
   première fois que ce devis la touche, son estimation (devis vidéo/retouche : bas/haut ; série : bas/haut ÷ épisodes).
   Sans devis ouvert : rien (« coût non mesuré »).
4. **Fin de tour** (`AtelierChatService`, après l'inscription du tour, garde `try/catch`) : `VideoCostTracker.turnEnded`
   lève l'attente des versions du sujet, puis clôt : devis vidéo unique ou retouche qui a publié ; devis série dont le
   nombre de vidéos publiées atteint `episodes`.
5. **Lecture** : `GET /videos` (cartes : `costUsd`, `estimateLowUsd`, `estimateHighUsd` ; série : total et estimation
   sommés sur les épisodes) et `GET /videos/{id}` (mêmes champs) ; `GET /videos/costs` : `{ month, spentUsd, quotes }`
   — somme des `spent_usd` des devis du compte créés ce mois-ci (plans abandonnés compris : c'est la dépense réelle).

### Cas d'erreur
| Situation | Comportement | HTTP |
|---|---|---|
| Ligne sans sujet, sans coût ou coût nul | ignorée | — |
| Pas de devis ouvert ni de version en attente dans le sujet | ignorée | — |
| Panne de l'imputation | journalisée `warn`, le relevé et le tour continuent | — |
| Vidéo d'un autre compte | 404 (inchangé) | 404 |
| `GET /videos/costs` sans authentification | 401 | 401 |

## Critères d'acceptation
- [ ] CA1 — Les coûts inscrits au journal pour un sujet pendant qu'un devis y est ouvert lui sont imputés (`spent_usd`), voix et tours.
- [ ] CA2 — La version publiée reçoit ce qui a été dépensé depuis la publication précédente du même devis, puis le coût du tour qui la publie (parts égales si plusieurs).
- [ ] CA3 — Clôture : vidéo unique / retouche à la fin du tour qui publie ; série quand tous les épisodes prévus sont publiés ; inactivité 48 h ; nouveau devis (SF-216-01).
- [ ] CA4 — Une retouche ne se rattache qu'aux versions de sa vidéo ; une vidéo publiée sans devis n'a pas de coût (`null`).
- [ ] CA5 — `GET /videos` et `GET /videos/{id}` rendent coût et estimation ; `GET /videos/costs` le total du mois du compte.
- [ ] CA6 — Isolation : les coûts d'un compte ne touchent jamais les devis/vidéos d'un autre ; `GET /videos/costs` ne somme que le compte courant ; 404 croisé inchangé.
- [ ] CA7 — Une panne de l'écouteur ne fait jamais échouer le relevé ni le tour.

## Technique
- Migration **174** `174-video-costs.xml` : `video_quotes` + `spent_usd`, `unassigned_usd` (numeric(12,6) défaut 0),
  `last_activity_at` ; `video_versions` + `quote_id`, `cost_usd` numeric(12,6), `cost_pending` (défaut faux) ;
  `videos` + `cost_usd` numeric(12,6), `estimate_low_usd`, `estimate_high_usd` numeric(10,4), `last_quote_id`. Rollback :
  colonnes retirées.
- `quota.UsageRecordedListener` + mutateur facultatif dans `UsageLedgerService` (un seul constructeur conservé).
- `videos.VideoCostTracker` (écouteur, publication, fin de tour, total du mois), verrou pessimiste sur le devis.
- `VideoService.commit` (mutateur facultatif du traqueur), `VideoGalleryResponse`, `VideoResponse`, `VideoController`
  (`GET /videos/costs`), `AtelierChatService` (fin de tour).

## Plan de test
- Unitaires : `UsageLedgerServiceTest` (écouteur appelé, panne avalée).
- Intégration : `VideoCostTrackerIntegrationTest` (H2) — vidéo unique bout en bout (voix → publication → tour → clôture),
  série (2 épisodes, coût réparti, clôture au dernier), retouche superposée à une série, publication sans devis,
  inactivité 48 h, isolation (coût de Bob ignoré par le devis d'Alice) ; `VideoControllerTest`/intégration API :
  coût dans la galerie et la fiche, `GET /videos/costs` (mois, compte courant seulement, 401).
- Suite complète backend.

## Préoccupations transversales
- **Plans / limites** : aucune règle de quota modifiée ; on **lit** après écriture. Composants : `UsageLedgerService`
  (écouteur après écriture, erreurs avalées), `QuotaService.recordUsage`, `SpeechUsage`, `PlumeService` (inchangés).
- **Contexte tenant** : imputation par `(user_id, workspace_id)` de la ligne ; toutes les requêtes du traqueur filtrent
  `user_id` ; lectures API par le compte courant. Composants : `VideoCostTracker`, `VideoService`, `VideoController`,
  `AtelierChatService`.
- Auth / navigation : non.

## Hors périmètre
Écran (SF-216-03) ; tours BYOK (pas de ligne au journal : seule la voix est comptée) ; auto-étalonnage.

## Notes et décisions
- **A1** — Rattachement par le **devis ouvert du sujet** + versions **en attente** jusqu'à la fin du tour (signal
  explicite de la boucle) plutôt qu'un identifiant porté par chaque tour : aucune modification du chemin de mesure.
- **A2** — Limites assumées : un travail sans rapport fait dans le sujet pendant un devis ouvert lui est imputé ; une
  voix d'épisode suivant produite dans le tour qui publie va à l'épisode publié (le total de la série reste exact).
- **A3** — Cumuls dénormalisés sur `videos` : la purge des anciennes versions ne fait pas disparaître la dépense.
