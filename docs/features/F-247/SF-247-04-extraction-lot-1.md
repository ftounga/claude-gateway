# Mini-spec — F-247 / SF-247-04 Extraction, lot 1

## Identifiant
`F-247 / SF-247-04`

## Feature parente
`F-247` — Le registre d'état (cadrage §4.2, D1 ; portes Z1, Z12)

## Statut
`done` (2026-11-05) · **Estimation :** 2 j (réestimé 1,5 j) · **Branche :** `feat/SF-247-04-extraction-lot-1` · **Dépend de :** SF-247-02, SF-247-03

## Objectif
Ce qu'Opus a vu pendant ses tours entre au registre sans rien coûter au tour : à la fin d'un tour, sa transcription part
en file durable, et des extracteurs purs en tirent les faits des tickets, pages, revues et CI.

## Comportement attendu

### Cas nominal
1. **Mise en file (Z1)** : après la persistance de la réponse (`AtelierChatService`, juste après le rappel F-162),
   `StateExtractEnqueuer.afterTurn` confie le travail à un **fil de remise** (une soumission en mémoire, ≪ 1 ms) qui
   insère `STATE_EXTRACT {messageId, workspaceId}` dans `async_jobs`. Aucune requête ni appel modèle dans le chemin.
2. **Exécutant** `StateExtractJobHandler` : relit la réponse et le sujet **sous le `user_id` du travail**, prend le
   client (`host_id` du sujet ; sujet hébergé ⇒ abandon), respecte le réglage du client (`enabled`,
   `extract_from_turns`) et le coupe-circuit `APP_JARVIS_STATE_EXTRACT_ENABLED`.
3. Pour chaque bloc `bash` de `terminal_json` : la sortie est relue (`BashOutput` : `$ commande`, sortie,
   `[code de sortie: N]`) ; **seules** les sorties en code 0, entières (ni « début tronqué » ni « sortie tronquée »)
   sont lues. En cas de doute, rien.
4. **Extracteurs du lot 1** (JSON d'API parsé ⇒ confiance `HAUTE`) :
   - Jira : `/rest/api/2|3|latest/issue|search`, `jira … --json/--raw`, `acli jira … --json` → statut, assigné, titre,
     priorité, sprint, mis à jour, bloqué par ;
   - Confluence : `/rest/api/content`, `/wiki/api/v2/pages` → titre, statut, version, mis à jour, `url` (base + webui de la sortie) ;
   - MR GitLab : `glab mr view|list -F json`, `/merge_requests` → état, titre, cible, branche, conflits, statut de fusion, pipeline, brouillon, auteur, `url` ;
   - PR GitHub : `gh pr view|list --json`, API `/pulls` → état (`MERGED` si fusionnée), titre, décision de revue, conflits, branches, `url` ;
   - CI : `/pipelines` (le plus récent par branche), `/jobs` (étape en échec), `gh run list|view --json`, `/actions/runs`.
   La clé est **tirée de la sortie** (`references.full`, `web_url`, `url`, `html_url`), jamais devinée.
5. Faits rangés au registre (source `TOUR`, `source_ref` = id de la réponse, `observed_at` = heure de l'appel au journal
   du runner si retrouvée, sinon celle de la réponse).
6. **Couverture** : par compte, jour et **forme** de commande d'état (`curl /rest/api/2/issue`, `glab mr view`,
   `kubectl get`…), appels vus / couverts / faits dans `jarvis_state_coverage`. `GET /api/admin/jarvis/state/measure`
   rend le taux, les 20 formes les plus fréquentes restées sans fait, et la file par type (`jobs`).
7. **Inventaire 30 j** : requête de classement des formes réelles dans `docs/ops/inventaire-commandes-etat.md`
   (lecture seule, à lancer par l'administrateur).

### Cas d'erreur
| Situation | Comportement attendu |
|---|---|
| Sortie en échec, tronquée, sans code | ignorée (comptée comme appel d'état non couvert) |
| JSON illisible ou incomplet | aucun fait |
| Commande d'écriture (`-X POST`, `merge`, `apply`, `push`…) | jamais une lecture d'état |
| Travail d'un autre compte | `DROPPED` (`no_client`), rien lu |
| Client coupé / extraction coupée | `DROPPED` (`disabled`) |
| File de remise pleine (1 000) | travail jeté, compté, le tour n'attend jamais |

## Contraintes de validation
Sortie bornée par le relevé (4 000 car., fin conservée) ; 200 éléments par sortie au plus ; forme ≤ 80 car.

## Critères d'acceptation
- [x] Z1 : la remise ne bloque pas l'appelant (< 50 ms mesuré, une soumission en mémoire) ; le travail arrive en file.
- [x] Les formes du lot 1 donnent leurs faits sur des sorties réelles typiques ; texte inconnu ⇒ aucun fait.
- [x] Isolation : le travail d'un autre compte ne lit rien ; client coupé ⇒ rien.
- [x] Couverture comptée par forme ; mesure exposée.

## Plan de test
- Unitaires : `StateExtractorsLot1Test` (7 : sortie bash, Jira, Confluence, MR GitLab, PR GitHub, CI, formes/écritures).
- Intégration : `StateExtractJobIntegrationTest` (3 : un tour réel → registre + couverture ; isolation + coupure ; remise non bloquante).
- Non-régression : `JarvisStateMeasureApiIntegrationTest`.

## Impact
- Table : `jarvis_state_coverage` (migration `247-04-jarvis-state-coverage.xml`, réversible).
- Backend : `jarvis/state/extract/*` (nouveau) ; `AtelierChatService` (setter facultatif + un appel après la persistance) ; `JarvisStateMeasureService` (couverture, file par type).
- Endpoint : `GET /api/admin/jarvis/state/measure` (+`coverage`, +`jobs`).
- Préoccupations transversales : Contexte tenant — `StateExtractJobHandler` (`AtelierMessageRepository.findByIdAndUserId`, `WorkspaceRepository.findByIdAndUserId`, `runner_audit` sous `user_id` + `workspace_id`), `JarvisStateRegistry.record` (client vérifié au compte), compteur de couverture (`user_id`).

## Arbitrages
- L'insertion en file passe **toujours** par le fil de remise (et non dans la transaction du tour) : le tour ne paie aucune requête ; une chute du pod entre la réponse et l'insertion perd une extraction (acceptable, le relevé rattrape).
- L'inventaire réel sur 30 j demande la base de production : la requête est livrée, les extracteurs couvrent les formes du cadrage en sortie JSON ; les formes texte ne sont ajoutées que sur échantillon réel (aucune forme supposée).

## Hors périmètre
Le lot 2 (déploiements, cloud, infra, base, git, API génériques, identités : SF-247-05). Les constats rédigés (SF-247-14).
