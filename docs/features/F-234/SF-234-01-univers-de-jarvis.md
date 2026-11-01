# Mini-spec — F-234 / SF-234-01 — L'univers de Jarvis (passerelle, D4)

## Identifiant
`F-234 / SF-234-01`

## Feature parente
`F-234` — L'écran vivant de Jarvis (cadrage `CADRAGE-F-234-ecran-vivant-jarvis.md`, D4, vague A « socle »)

## Statut
`in-review`

## Date de création
2026-11-01

## Branche Git
`feat/SF-234-01-univers-jarvis`

---

## Objectif

> Un seul appel, en lecture seule et filtré par `user_id`, rend à `/jarvis` tout ce que la constellation dessine :
> clients → sujets → chantiers ouverts → étape courante, interlocuteurs et destinataires « À envoyer », **sans aucun
> contenu**.

## Comportement attendu

`GET /api/jarvis/universe` (droit runner, comme `GET /api/jarvis/to-send`) rend :

```json
{ "generatedAt": "…", "toSendCount": 3,
  "clients": [ { "id": "…", "name": "CAGIP", "virtual": false, "connected": true, "missionStatus": "ACTIVE",
                 "truncated": false,
                 "subjects": [ { "id": "…", "name": "Terminal du poste", "hostTerminal": true, "live": false, "chantiers": [] },
                               { "id": "…", "name": "Migration Forge", "hostTerminal": false, "live": true,
                                 "chantiers": [ { "number": 3, "title": "Bascule DNS", "phase": "EXECUTION",
                                                  "phaseLabel": "Exécution", "done": 2, "total": 5,
                                                  "step": { "rank": 3, "title": "Basculer" }, "focus": true,
                                                  "live": false, "waitingOn": ["Zahi"] } ] } ],
                 "people": [ { "name": "Zahi Ben", "variants": ["zahi"], "role": "RSSI" } ],
                 "toSend": ["Zahi"] } ] }
```

- **Construit sur l'existant** : `RunnerHostService.list`, `RunnerStatusService.statusOf` (connecté),
  `WorkspaceService.list`, `ClientBoardService.board` (F-220 : chantiers, étape, `waitingOn`),
  `InterlocuteurRepository` (F-180), `JarvisReturnService.toSend` (SF-231-06), `LiveTerminalService` (F-70) — tous
  avec `currentUser.requireId()`.
- Clients : connectés d'abord, puis par nom ; « Hébergé » (sujets sans poste, `id` nul, `virtual`) en dernier s'il
  porte un sujet. Sujets : terminal du poste d'abord (nommé « Terminal du poste »), puis par nom ; **le terminal Teams
  est exclu** (pas un sujet qu'on choisit à la voix).
- **Bornes** : ≤ 12 clients, ≤ 40 sujets/client, ≤ 10 chantiers/sujet, ≤ 60 personnes/client (≤ 5 variantes),
  ≤ 20 destinataires/client ; `truncated` signale une coupe.
- **Best-effort par client** : tableau ou interlocuteurs illisibles ⇒ ce client sans chantiers / personnes, jamais
  une erreur pour tout l'écran.
- Nouveau code dans un **sous-paquet** `fr.claudegateway.jarvis.universe` : aucune classe existante de
  `fr.claudegateway.jarvis` modifiée (contrainte de coordination F-233).

### Cas d'erreur
| Situation | Comportement | Code |
|---|---|---|
| Sans jeton | refus | 401 |
| Sans droit runner | refus (`AtelierAccessDeniedException`) | 403 |
| Compte sans poste ni sujet | `clients: []`, `toSendCount: 0` | 200 |
| Tableau d'un poste illisible | ce client sans chantiers, les autres intacts | 200 |

## Critères d'acceptation
1. La réponse contient clients → sujets → chantiers ouverts (numéro, titre, phase, étape courante, `done/total`,
   `live`, personnes en attente) + interlocuteurs (nom, variantes) + destinataires à envoyer.
2. **Isolation** : Bob ne voit ni les postes, ni les sujets, ni les personnes d'Alice.
3. 13 postes ⇒ 12 clients.
4. **Aucun contenu** : ni corps ni objet de mail, ni question, ni description d'attente, ni `user_id` (test de forme).
5. Un seul aller-retour suffit à l'écran (SF-234-03 l'appelle à l'ouverture et à chaque `done`).

## Contraintes de validation
Aucune entrée (pas de paramètre). Bornes ci-dessus, en constantes de `JarvisUniverseService`.

## Plan de test
- Intégration (`JarvisUniverseApiIntegrationTest`) : univers d'Alice (ordre, terminal du poste, Teams exclu, chantier
  guidé, personne et variantes, destinataire, « Hébergé », compteur) ; absence de contenu ; isolation Bob ; borne
  13 → 12 ; 401.
- Non-régression : suite backend complète (nouveau bean, constructeur unique).

## Impacts
- **Tables** : aucune (lecture seule).
- **Endpoints** : `GET /api/jarvis/universe` (nouveau).
- **Composants** : `JarvisUniverse`, `JarvisUniverseService`, `JarvisUniverseController` (nouveaux).

## Préoccupations transversales
- **Contexte tenant : OUI** — composants qui résolvent le tenant et sont appelés : `RunnerHostService.list(userId)`,
  `RunnerStatusService.statusOf(userId, host)`, `WorkspaceService.list(userId)`, `ClientBoardService.board(userId,
  hostId)` (404 sur poste d'autrui, jamais atteint : on n'itère que les postes possédés),
  `InterlocuteurRepository.findByUserIdAndHostIdOrderByDisplayNameAsc`, `JarvisReturnService.toSend(userId, null)`,
  `LiveTerminalService.liveWorkspaceIds(userId)`. Double garde : chaque ligne relue est rejetée si son `userId`
  diffère. Test d'isolation A/B.
- Auth / Principal : non. Plans / limites : non (aucun appel IA). Navigation : non.

## Arbitrages (réversibles)
- Droit **runner** (comme `/jarvis/to-send`) : l'univers est fait de postes ; sans runner, il est vide de sens.
- Le **terminal Teams** est exclu de l'univers (D7 : sobriété ; il n'est pas choisi à la voix). Réversible.
- `waitingOn` ne porte que le **nom** de la personne qui débloque (`ChantierUnblocker.Mark.waitingOn`), jamais la
  description de l'attente (D4 : aucun contenu).

## Hors périmètre
Le rendu (SF-234-03), le lecteur de scène (SF-234-02), les étiquettes d'étape (SF-234-04), tout rafraîchissement
poussé (l'écran relit à chaque `done`).
