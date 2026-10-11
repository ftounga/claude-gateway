# Mini-spec — F-191 / SF-191-01 La route des tours du poste

## Identifiant
`F-191 / SF-191-01` — Feature parente : F-191 « Le fil des tâches » (cadrage `CADRAGE-F-191-le-fil-des-taches.md`).
Statut : `ready` — 2026-10-11 — Branche `feat/SF-191-01-tours-du-poste`.

## Objectif
Exposer, depuis n'importe quel terminal, le résumé des tours **en cours ou finis depuis moins de 10 min** de **tous les terminaux du même poste** de l'utilisateur, sans aucun appel modèle.

## Comportement attendu

### Cas nominal
`GET /api/workspaces/{id}/fil` (terminal possédé, accès terminal requis) :
1. Résout le poste du terminal (`host_id` de l'entité possédée). Sans poste (bac à sable), la portée est l'ensemble des terminaux sans poste de l'utilisateur.
2. Lit les **résumés de tours** de l'utilisateur : ceux de ce pod (`LiveTurnRegistry.summariesOf(userId)`) **et** ceux des pairs (`RemoteTurnSource.remoteSummariesOf(userId)`, nouvelle route relais `POST /internal/atelier/user-turns`, un appel par pair).
3. Ne garde que les tours des terminaux du poste ; dédoublonne par `turnId` ; un terminal qui a un tour vivant ne montre pas son tour fini précédent ; au plus un tour fini par terminal.
4. Trie : `waiting` → `running` (le plus ancien d'abord) → finis (le plus récent d'abord). Au plus 20 lignes.

Le **résumé** d'un tour est tenu par le tour lui-même (`TurnDigest`, nourri par les événements déjà publiés — `action`, `plan`, `confirm_*`, `question_*`, `done`, `error`) : aucune écriture en base, aucun appel modèle.

### Contrat API (figé, consommé par SF-191-02 et SF-191-03)
```json
{
  "hostId": "uuid|null",
  "serverTime": 1760000000000,
  "turns": [{
    "turnId": "uuid", "workspaceId": "uuid", "workspaceName": "Forge CAPFM",
    "root": false, "here": true,
    "state": "waiting|running|done|failed|interrupted",
    "waiting": "approval|question|null",
    "demand": "≤ 160 caractères",
    "startedAt": 1760000000000, "endedAt": null,
    "planDone": 2, "planTotal": 5, "planStep": "titre de l'étape active|null",
    "tool": "read|write|list|search|bash|explore|task|…|null",
    "subagents": [{"kind": "task|explore", "label": "≤ 80 car.", "state": "running|done", "startedAt": 1760000000000}]
  }]
}
```
`root` = terminal du poste (racine). `here` = le terminal de l'appel. Dates en millisecondes epoch ; `serverTime` sert à calculer les durées sans dépendre de l'horloge du navigateur.

### Cas d'erreur
| Situation | Comportement | Code |
|---|---|---|
| Terminal d'un autre utilisateur / inexistant | introuvable | 404 |
| Pas d'accès terminal (droits) | refus | 403 |
| Non authentifié | refus | 401 |
| Pair injoignable / relais éteint | dégradation : seuls les tours locaux | 200 |
| Enveloppe relais invalide (`userId` absent) | refus | 400 |

## Contraintes de validation
- Demande tronquée à 160 caractères, libellé de sous-agent à 80, titre d'étape à 120.
- Au plus 6 sous-agents par tour (les plus récents), 20 tours rendus, 20 tours finis retenus par utilisateur et par pod, rétention 10 min.

## Critères d'acceptation
- [ ] Un tour vivant d'un autre terminal du même poste apparaît, avec demande, outil courant, plan fait/total.
- [ ] Un tour en attente d'autorisation ou de question a `state=waiting` et passe en tête.
- [ ] Un tour fini apparaît `done` (ou `failed` sur événement `error`) pendant 10 min puis disparaît.
- [ ] Les tours d'un **autre poste** du même utilisateur n'apparaissent jamais.
- [ ] Les tours d'un **autre utilisateur** n'apparaissent jamais (clef `userId` du registre + filtre poste).
- [ ] Les délégations (`task`, `explore`) apparaissent comme sous-agents : en cours tant qu'aucune action non déléguée n'a suivi.
- [ ] Le relais entre pods rend les résumés du pair ; pair injoignable ⇒ liste locale seule, jamais d'erreur.
- [ ] Aucun appel modèle, aucune écriture en base, rien du contenu (demande) n'est journalisé.

## Plan de test
- Unitaires `TurnDigestTest` : plan, outil, attente, sous-agents, issue, troncature.
- Unitaires `LiveTurnRegistryTest` (ajouts) : fini retenu puis oublié après 10 min, isolation userId.
- Unitaires `PosteTurnsServiceTest` : filtre poste, autre poste exclu, dédoublonnage, tri, terminal sans poste, tours distants fusionnés.
- `AtelierRelayControllerUserTurnsTest` : requête invalide 400, résumés rendus.
- Contrôleur `PosteTurnsControllerTest` : 200 nominal, 404 terminal d'autrui (exception propagée).

## Impacts
- Tables : **aucune** (pas de migration Liquibase).
- Endpoints : `GET /api/workspaces/{id}/fil` (nouveau) ; `POST /internal/atelier/user-turns` (relais, secret partagé existant).
- Composants : `LiveTurn`, `LiveTurnRegistry`, `RemoteTurnSource`, `RelayTurnSource`, `AtelierRelayController`, `WorkspaceRepository` (+ `findByUserIdAndHostId`), nouveau paquet `atelier.fil`, `AtelierChatController`/`AtelierAgentController` (la demande est confiée au résumé).
- Préoccupations transversales : Auth/Principal ☐ ; **Contexte tenant ☑** — composants qui résolvent le tenant ici : `CurrentUser.requireId()` (contrôleur), `WorkspaceService.requireOwned` (poste du terminal possédé), `LiveTurnRegistry.summariesOf(userId)`, relais `user-turns` (userId = critère rejoué, jamais authentification) ; Plans/limites ☐ ; Navigation ☐.
- Ne casse pas : F-70 (aucune place prise au registre des terminaux), F-83/F-84 (flux `attach` inchangé), F-175, F-185.

## Hors périmètre
Le rendu (SF-191-02), le bandeau de fin et le son (SF-191-03) ; interrompre/répondre depuis la bande ; autres postes ; persistance des tours finis au-delà d'un redémarrage de pod.

## Arbitrages (décidés par défaut, réversibles)
- **Tours finis gardés en mémoire du pod** 10 min (et relayés), pas relus en base : un déploiement les efface — un déploiement tue de toute façon les tours (F-84).
- **Sous-agents** = délégations `task`/`explore` ; « terminé » dès qu'une action non déléguée suit (la boucle ne publie pas de fin de délégation).
- **Interruption** d'un tour de conversation : vue `done` (le flux de chat ne la distingue pas) ; `interrupted` seulement quand l'événement `done` le porte (exécution Managed Agent).
- **Terminal sans poste** : portée = terminaux sans poste de l'utilisateur.
- **Polling** côté écran (SF-191-02) plutôt que SSE : une requête JSON courte, bornée, sans occuper le pool `turnAttachExecutor`.
