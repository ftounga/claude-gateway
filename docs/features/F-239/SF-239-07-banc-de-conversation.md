# Mini-spec — F-239 / SF-239-07 — Le banc de conversation, porte de déploiement

## Identifiant
`F-239 / SF-239-07` · Feature parente `F-239` · Statut `in-review` · 2026-11-02 · Branche `feat/SF-239-07-banc-de-conversation`

## Objectif
> Aucun déploiement de Jarvis sans preuve qu'il converse comme une personne, sans formule robotique, sans
> délégation inutile ni dépense inutile : un banc de dialogues multi-tours, noté par un juge, avec une porte chiffrée.

## Comportement attendu
### Cas nominal
- `POST /api/admin/jarvis/fast-brain/bench {"set":"CONVERSATION","model":…}` (ADMIN, asynchrone comme les jeux
  `REAL` / `PERSONALITY`) rejoue le jeu `jarvis/conversation-scenarios.json` : **34 scénarios, 43 tours** —
  salutations, petite conversation, « commençons », point, demandes métier, phrases mal entendues, interruption
  (« coupé après : … »), silence, remerciement, au revoir, enchaînement de 5 tours, et 4 **tours réels du PO du
  2026-11-02** (rendus fictifs : noms du jeu). Contexte figé fictif (aucune donnée client).
- Chaque tour est rejoué **à blanc** sur le modèle candidat (ni trace, ni mémoire, ni registre), les échanges
  précédents du scénario dans son contexte (comme le fil en prod), et **jugé** (Opus 5.5) par l'outil `noter_tour` :
  naturel (1-5), concis, formule robotique, délégation inutile, sens juste, question en retour, fait non ancré,
  action non déléguée.
- **Scénario réussi** : chaque tour sur la route attendue, naturel ≥ 3, concis, sans formule robotique, sans
  délégation inutile, sens juste, sans fait non ancré ni action non déléguée ; au plus **une** question en retour ;
  **coût ≤ budget** (0,02 € par tour + ≈ 0,98 € par délégation attendue ; une délégation à Opus coûte ≈ 0,98 €).
- **Porte** : ≥ 90 % de scénarios réussis, 0 délégation inutile, 0 formule robotique, 0 fait non ancré, 0 action non
  déléguée. Le rapport donne aussi le **coût moyen par échange (€)** et le **taux de délégation**.
- `release-checklist.md` : « aucun déploiement de Jarvis sans banc de conversation au vert ».

### Cas d'erreur
| Situation | Comportement |
|---|---|
| Jeu inconnu | 400 (inchangé) |
| Juge sans réponse | Tour « non jugé » ⇒ scénario échoué |
| Non-ADMIN | 403 (inchangé) |

## Critères d'acceptation
- [ ] Le jeu couvre toutes les catégories demandées, ≥ 30 scénarios, les tours réels inclus.
- [ ] Une salutation déléguée fait échouer son scénario (budget, délégation inutile, route).
- [ ] Le rapport porte taux de réussite, coût moyen par échange en €, taux de délégation, verdicts.
- [ ] Rejeu à blanc (aucune écriture), sous le compte appelant.

## Hors périmètre
Le passage lui-même sur la prod (après déploiement : c'est la porte des SF suivantes) ; une page d'écran pour le banc.

## Technique
`JarvisConversationBench` (jeu, grille, budget, porte), `JarvisFastBrainBenchService` (jeu `CONVERSATION`,
`Report.conversation`), ressource `jarvis/conversation-scenarios.json`, `release-checklist.md`. Aucune table.

## Plan de test
`JarvisConversationBenchTest` (jeu, scénario réussi, salutation déléguée, questions en retour, porte) ;
`JarvisFastBrainBenchServiceTest` (rejeu complet à blanc, contexte du tour suivant, coût et taux rapportés).
