# Mini-spec — F-247 / SF-247-10 Jarvis sait (cerveau rapide)

## Identifiant
`F-247 / SF-247-10`

## Feature parente
`F-247` — Le registre d'état (cadrage §4.5, D8, D9, D11 ; portes Z3, Z7, Z9, Z10, Z12, Z13)

## Statut
`done` (2026-11-05) · **Estimation :** 2 j · **Branche :** `feat/SF-247-10-jarvis-sait` · **Dépend de :** SF-247-02, SF-247-04

## Objectif
Poste éteint, Jarvis répond aux questions d'état avec ce qui a été lu sur le poste, en disant toujours de quand date
l'information, sans jamais ralentir sa première parole.

## Comportement attendu

### Cas nominal
1. **Bloc « État connu » préparé à l'avance** (`JarvisStateBlock`, Z12) : composé **hors réplique** (fil démon à
   priorité minimale) quand le registre change (`JarvisStateRegistry.onChange`) ou quand il a plus de 2 min, gardé en
   mémoire par compte. Par client : connexion, dernière rupture (`runner_disconnects` : « déconnecté depuis hier 18:05
   (arrêt propre) »), attentes ouvertes comptées en direct, 20 objets récents du registre (index, `LIMIT`) regroupés en
   une ligne par objet (« - PROJ-123 « Migration SSO » : En revue (vu hier 17:42, tour) — avant : En cours »), « ancien »
   au-delà de 24 h.
2. **Dans la réplique**, aucune requête : `ready()` prend les lignes prêtes et les trie **en mémoire** par pertinence —
   le client du sujet en vue d'abord (sujet en vue, puis changé, puis bloqué, puis récent), puis les autres clients **au
   poste déconnecté** ; **20 faits et 2 000 caractères au plus** (Z9). Poste connecté : seuls les faits de plus de 15 min
   entrent, avec la mention « une question qui exige le frais se délègue ». Cache absent (pod neuf) : réplique **sans**
   bloc, composition lancée à côté, jamais attendue. Le bloc va **dans le message** (préfixe stable).
3. **Outil `etat`** (`JarvisStateTool`) : lecture seule, une fois par réponse, par clé (préfixes essayés : `JIRA:`,
   `GITLAB:`, `GITHUB:`, `PIPELINE:`, `K8S:`, `HELM:`, `ARGO:`, `GIT:`), par famille ou récents, sur le client nommé, sinon
   celui du sujet, sinon tous (12 au plus) ; 20 lignes au plus, datées ; encadré `<etat_non_fiable>` ; ancré
   (`gate.ground`).
4. **Outil `conversations`** déclaré dès maintenant (le préfixe ne change qu'une fois, D-Z7) ; sans lecteur branché il
   répond « Aucune conversation capturée n'a encore été lue » (branché par SF-247-13).
5. **Consigne v19** : six outils ; section « L'état connu » — poste éteint ⇒ réponse du registre datée, « Je n'ai rien
   de relevé sur … ; le poste est éteint depuis … » s'il ne sait pas, **pas de délégation** (D9) ; action ⇒ toujours
   déléguée ; poste connecté ⇒ le frais se délègue ; données non fiables ; présentation ⇒ rien.
6. **Garde de fraîcheur D8** : toute phrase dite qui cite un fait (du bloc ou de `etat`) de plus d'1 h **sans marque de
   temps** reçoit « (relevé de HH:MM) » / « (relevé d'hier HH:MM) » avant sa ponctuation.
7. **Présentation** : ni bloc, ni lecture ; `etat` et `conversations` rendent « MODE PRÉSENTATION ».
8. **Mesure** : `state_block_ms` (lecture du bloc prêt dans la réplique) passe par `done.state` (SF-247-01).
9. Coupe-circuit `APP_JARVIS_STATE_BLOCK_ENABLED` (bloc et note) ; registre coupé pour un client ⇒ absent du bloc.

### Cas d'erreur
| Situation | Comportement attendu |
|---|---|
| Cache absent | réplique sans bloc, composition à côté |
| Registre illisible à la composition | bloc précédent conservé (ou aucun) |
| `etat` en panne | « Rien de relevé. » (jamais d'exception) |
| Client nommé inconnu | « Aucun client connu nommé … » |
| Numéro seul (« 42 ») | aucune clé devinée |

## Critères d'acceptation
- [x] Z9 : 20 faits / 2 000 caractères au plus à 10, 10 000 et 100 000 faits.
- [x] Z12 : la réplique ne lit pas le registre (lecture du prêt en mémoire ; mesurée dans `state_block_ms`).
- [x] Banc `ETAT` déterministe : 12 cas (éteint frais / ancien / changé / absent, allumé > 15 min, deux clients, sujet en vue, D8 ×2, vide).
- [x] Outil `etat` lu une fois, rendu au modèle, ancré ; D8 appliquée à la phrase dite.
- [x] Présentation : ni bloc ni lecture.
- [x] Consigne v19, outils figés identiques d'un appel à l'autre (test existant mis à jour).

## Plan de test
- Unitaires : `JarvisStateBlockTest` (9 : Z9 ×3 et banc `ETAT` 12 cas), `JarvisFastBrainStateTest` (3 : bloc dans le message, présentation, `etat` + D8).
- Non-régression : `JarvisFastBrainServiceTest` (version 19, six outils), suites `JarvisFastBrain*`, `JarvisConversationBench*`.
- Banc live CONVERSATION (≥ 90 %) : **précondition de déploiement** (clé et coût), comme v14-v18.

## Impact
- Backend : `jarvis/state/JarvisStateBlock`, `JarvisStateTool`, `JarvisConversationLookup` (nouveaux) ; `JarvisFastBrainPrompt` (v19, 2 outils, section) ; `JarvisFastBrainService` (bloc dans le message, 2 outils, garde D8) ; `JarvisStateTurnMeter` (`state_block_ms`).
- Aucune table, aucun endpoint, aucune migration.
- Préoccupations transversales : Contexte tenant — `JarvisStateBlock.compose` (`RunnerHostService.list(userId)`, registre sous `user_id` + `host_id`, `runner_disconnects` et `terminal_actions` sous `user_id` + `host_id`), `JarvisStateTool` (clients du compte seulement). Auth, plans, navigation : non.

## Arbitrages
- Le bloc est préparé par compte (pas par sujet) : la sélection par pertinence se fait en mémoire dans la réplique, sur les candidats prêts — aucune requête dans la réplique (Z12) et le sujet en vue est respecté.
- Le banc `ETAT` est rejoué de façon déterministe (composition, sélection, D8) ; le banc live du modèle est une précondition de déploiement, comme pour les consignes v14-v18.

## Hors périmètre
`JarvisStateNote` (Opus, SF-247-11), la lecture des conversations (SF-247-13), l'écran (SF-247-15).
