# Mini-spec — F-253 / SF-253-05 Poste éteint : le dire, et répondre par Opus en lecture seule

## Identifiant
`F-253 / SF-253-05`

## Feature parente
`F-253` — Jarvis montre vraiment (cadrage §3 #5, D3 ; audit #46)

## Statut
`in-review` · **Estimation :** 2 j · **Branche :** `feat/SF-253-05-poste-eteint` · **Dépend de :** SF-253-01

## Objectif
Une délégation vers un poste éteint ne part plus : Jarvis le dit et répond par Opus côté passerelle, en lecture seule.

## Comportement attendu

### Cas nominal
1. Après routage et adoption (`settle`), une issue `DELEGATE` dont le poste cible est éteint (même règle que la mesure
   F-247 `hostOffline`) est remplacée : « Le poste de CAGIP est éteint ; je vous réponds avec ce que je sais. », puis la
   réponse d'Opus (`AiAgentProvider`, modèle du terminal), en phrases.
2. Opus reçoit le contexte de la réplique et les outils `pieces`, `agenda`, `etat`, `conversations`, chacun lu une fois
   (au plus 4 appels). Ni `deleguer` ni `afficher` : aucune action possible.
3. Le coût va au journal étiqueté `JARVIS / OPUS` et à la journée Jarvis (F-244), jamais au quota du terminal.

### Cas d'erreur
| Situation | Comportement attendu |
|---|---|
| Poste connecté, sujet hébergé ou fil Jarvis | Délégation inchangée |
| Sujet d'un autre compte | Délégation inchangée (`findByIdAndUserId`) |
| État du poste illisible | Délégation inchangée |
| Échec d'Opus ou réponse vide | « Je ne peux pas vous en dire davantage pour le moment, Monsieur. » |
| Demande d'action | Opus dit que ce sera possible une fois le poste rallumé |
| Réponse provisoire jetée | Aucun appel à Opus (la bascule est dans `settle`, après l'adoption) |

## Critères d'acceptation
- [ ] Poste éteint : aucun événement `delegated`, l'issue est `ANSWER`, la phrase d'annonce précède la réponse.
- [ ] Outils proposés à Opus : exactement les quatre de lecture.
- [ ] Sujet hébergé ou poste connecté : délégation intacte.
- [ ] Opus en échec : phrase de repli, jamais d'exception vers la réplique.
- [ ] Isolation : sujet et lectures sous le `user_id` de l'appelant.

## Plan de test
Unitaires `JarvisOfflineTest` (bascule, outils, repli, connecté, hébergé, sujet d'autrui). Intégration : le flux SSE
existant couvre `converse` ; la bascule est testée au niveau du service avec fournisseur simulé. Isolation : sujet
d'autrui ⇒ délégation inchangée, aucun appel au modèle.

## Impact
`JarvisFastBrainService`, `JarvisOfflinePrompt` (nouveau), `JarvisStateTurnMeter` (visibilité). Aucune table, aucun
endpoint. La consigne, les outils et le chemin de décision du cerveau rapide sont **inchangés** (le banc passe par
`decide`, pas par `settle`) : pas de banc V8 applicable ; à vérifier en prod par un tour poste éteint.
Préoccupations transversales : **Contexte tenant** — composants : `offlineInstead` (`findByIdAndUserId`),
`JarvisStateTurnMeter.hostOffline`, `readPieces` / `readAgenda` / `readState` / `readConversations` (déjà sous
`request.userId()`), `account`.

## Hors périmètre
Montrer à l'écran depuis cette réponse ; l'outil univers (SF-253-02, non fusionnée).
