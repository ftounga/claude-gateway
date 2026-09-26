# Mini-spec — F-161 / SF-161-01 — La porte d'entrée

## Identifiant
`F-161 / SF-161-01` — feature parente `F-161`

## Objectif
Refuser un tour qui a besoin du runner **avant** d'envoyer quoi que ce soit au fournisseur — donc
**sans dépenser un seul jeton**.

## La demande
> PO, 2026-09-26 : *« On peut tester que le runner répond en amont ? Avant de consommer les
> tokens ? »*

Sur la session KPMG : **8 tours « Non concluant », ≈ 9 $, 11 % de la facture**, dépensés pour
*découvrir* que la machine ne répondait pas. Le contexte part, le modèle raisonne, appelle `bash`,
et l'échec n'apparaît qu'à ce moment-là.

## Tout existe déjà — c'est le branchement qui manque
| Ce qu'il faut savoir | Ce qui le sait | Lu aujourd'hui |
|---|---|---|
| Le runner est-il **vivant** ? | `RunnerLiveness.isAlive(userId, hostId)` — battement `runner_tokens.last_seen_at`, seuil 90 s, **partagé par la base** | au moment d'exécuter un outil |
| Que **sait-il faire** ? | `runner_hosts.runner_capabilities`, réécrites à **chaque** déclaration du runner, **lisibles par tout pod** | idem |

**Rien à créer.** C'est une capacité dormante au sens de F-156 : deux savoirs justes, consultés trop
tard.

## L'arbitrage, tranché ici
Tous les tours n'ont pas besoin du runner — « donne-moi un message plus court » n'y touche pas.
Bloquer aveuglément serait insupportable ; laisser passer en prévenant le modèle **paie le tour
quand même**.

**Retenu : refuser, avec « demander quand même ».** Un runner mort est presque toujours la raison
pour laquelle le tour va décevoir ; un clic coûte moins cher qu'un euro. Et la main reste à
l'utilisateur, qui seul sait si sa question touche la machine.

## Comportement attendu
1. Avant tout appel fournisseur, si le projet exécute **sur un poste** (cible `RUNNER`) : vérifier
   **la vivacité**, puis **les capacités** que la panoplie du tour exigerait.
2. Refus → une erreur **nommée**, qui dit **ce qui manque** et **quoi faire** :
   - hors ligne → « le runner du poste *X* ne répond plus (dernier signe il y a *N*) » ;
   - `bash` non déclaré → « ton runner tourne **sans bash** — relance-le sans `--no-bash` ».
3. **Aucun jeton n'est consommé** : le refus précède la construction du contexte.
4. Un paramètre **« demander quand même »** passe outre — l'utilisateur garde la main.
5. Un projet **hébergé** (cible `SANDBOX`) n'est **jamais** concerné.
6. Si l'état est **indéterminable** (poste inconnu, capacités jamais déclarées), **on laisse
   passer** : une porte qui bloque sur une ignorance est pire que pas de porte.

| Cas | Comportement |
|---|---|
| Runner vivant, capacités complètes | le tour s'ouvre, rien ne change |
| Runner hors ligne | **refus nommé**, zéro jeton |
| Runner vivant sans `bash` | **refus nommé**, zéro jeton |
| Capacités jamais déclarées | **laisse passer** — on ne bloque pas sur une ignorance |
| « Demander quand même » | passe, quel que soit l'état |
| Projet hébergé | **jamais** de porte |

## Critères d'acceptation
- [ ] Le refus intervient **avant** tout appel fournisseur — vérifié en comptant les appels.
- [ ] Hors ligne → refus nommé portant **le nom du poste** et **l'ancienneté** du dernier signe.
- [ ] Capacité manquante → refus nommé qui dit **laquelle** et **quoi faire**.
- [ ] Capacités inconnues → **laisse passer**.
- [ ] « Demander quand même » → passe dans tous les cas.
- [ ] Cible `SANDBOX` → aucune vérification.
- [ ] **ISOLATION** : `requireOwned` d'abord ; la vivacité est lue par `isAlive(userId, hostId)`.
- [ ] La porte est **branchable/débranchable** : sans elle, le comportement d'avant.

## Hors scope
L'**arrêt net en plein tour** (**SF-161-02**) · le **journal des ruptures** (**SF-161-03**) · le
**ping** (**SF-161-04**, réserve) · toute correction de la cause des déconnexions.

## Technique
| Élément | Changement |
|---|---|
| `RunnerDoor` | la décision : vivant ? capacités ? — rend `Ouverte` ou un refus nommé |
| `RunnerNotReadyException` | le refus, traduit en **409** (l'état de la machine, pas la requête) |
| `AtelierChatService` | consulte la porte **en premier**, sauf « demander quand même » |
| `AtelierChatController` | un paramètre `force` facultatif, **lu sur le thread de requête** |
| `AtelierChatController` (SSE) | le refus devient un `error` **nommé**, porteur de sa raison |
| `atelier.component.ts` | le refus ouvre « **Demander quand même** » au lieu d'un simple constat |

**Aucune migration** : les deux colonnes existent et sont alimentées.

### Le drapeau est un ARGUMENT, jamais un état de thread
Première version : `force` posé en variable de thread sur `AtelierChatService`. **Faux** — le relais
SSE tourne sur un pool, et **c'est le seul chemin qu'emprunte l'écran**. L'échappatoire n'aurait
fonctionné que sur le chemin synchrone, que personne n'utilise : une porte sans sortie. Le drapeau
traverse donc `chat(...)` / `chatStreaming(...)` / `runLoop(...)` **en paramètre**, et
`AtelierChatControllerForceTest` le prouve sur un **vrai pool**, en vérifiant au passage que la
boucle ne tourne pas sur le thread de la requête — sans quoi le test ne prouverait rien.

### Un refus nommé doit le rester dans le flux
Sur le chemin SSE, une exception non prévue tombe dans `internal_error`. La porte y serait
**indiscernable d'un bogue**, et ferait plus de mal que le tour qu'elle évite. Elle a donc sa propre
capture, qui publie son code (`runner_offline` / `runner_missing_capability`) **et sa raison** — le
nom du poste et l'ancienneté de son dernier signe, qu'un code seul ne pourrait pas porter.

## Plan de test
- [ ] Vivant + capacités → ouvert, et le fournisseur est appelé.
- [ ] Hors ligne → refus, **`AIProvider` jamais appelé**.
- [ ] Sans `bash` → refus nommé, **`AIProvider` jamais appelé**.
- [ ] Capacités inconnues / poste inconnu → passe.
- [ ] `force=true` → passe malgré un runner mort.
- [ ] Cible `SANDBOX` → aucune lecture de vivacité.
- [ ] **ISOLATION** : projet d'autrui → 404 avant toute autre chose.

## Préoccupations transversales
| Préoccupation | Cochée | Composants impactés |
|---|---|---|
| Auth / Principal | non | aucun nouvel endpoint ; `POST /chat` gagne un paramètre facultatif |
| **Contexte tenant** | **oui** | `RunnerDoor` reçoit le `userId` et le `Workspace` **déjà vérifiés** ; la vivacité passe par `RunnerLiveness.isAlive(userId, hostId)`, qui porte déjà l'isolation ; les capacités sont lues par `RunnerHostService` sur le poste **du projet**. |
| **Plans / limites** | **oui** | **c'est l'objet même** : la porte empêche une dépense. Elle n'ouvre aucun droit, elle en ferme un — sans elle, comportement d'avant. |
| **Navigation / routing** | **non** | aucune route ; le refus est une erreur nommée que l'écran rend déjà |
