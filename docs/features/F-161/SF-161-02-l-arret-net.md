# Mini-spec — F-161 / SF-161-02 — L'arrêt net

## Identifiant
`F-161 / SF-161-02` — feature parente `F-161`

## Objectif
Quand le poste tombe **en plein tour**, arrêter la boucle **sans rappeler le fournisseur** — et
rendre ce qui a déjà été fait, au lieu de payer un appel complet pour lire « Non concluant ».

## Le constat
Sur la session mesurée du 25/09 : quand le runner décroche au milieu d'un tour, l'agent reçoit
l'erreur d'outil, **raisonne sur tout le contexte**, et écrit une prose qui dit ce que la gateway
savait déjà. **On paie un appel complet pour apprendre une chose connue.**

C'est le gain que le cadrage F-161 §5 appelle « le plus gros » : la porte (SF-161-01) protège
l'**entrée** du tour ; rien ne protège son **milieu**.

## Tout existe déjà — c'est encore un branchement
| Ce qu'il faut savoir | Ce qui le sait | Lu aujourd'hui |
|---|---|---|
| Le poste a-t-il décroché **pendant ce tour** ? | `noteMachine(...)` écrit `AtelierMachineReach.OFFLINE` dans `machineOfTurn`, à chaque appel runner | pour le point de reprise, pas pour arrêter |
| Où s'arrêter sans rien casser ? | la **frontière sûre** juste après les résultats d'outils — celle qu'emprunte déjà l'interruption (`interruptedTurns`) | seulement pour l'interruption |
| Qu'a-t-on déjà fait ? | `AtelierToolTrace` : chaque étape, chaque appel, son succès ou son échec | rendu à l'écran, jamais résumé |

**Rien à créer.** Capacité dormante au sens de F-156.

## L'arbitrage, tranché ici
**On s'arrête au premier constat d'indisponibilité, pas au bout de N échecs.** Un runner qui a
décroché ne revient pas dans la seconde ; attendre un second échec, c'est un appel fournisseur de
plus — exactement ce qu'on veut éviter.

**Mais le dernier appel fait foi** : `noteMachine` repasse `REACHED` dès qu'un appel réussit. Un
runner revenu **ne déclenche pas** l'arrêt. C'est la règle de F-93 / SF-93-04, et on ne la change pas.

**Précision que le développement a imposée** : cette récupération ne peut jouer qu'**à l'intérieur
d'une même étape**, entre deux appels d'outils du même tour d'assistant. D'une étape à l'autre, il
faudrait rappeler le fournisseur — c'est-à-dire exactement la dépense que l'arrêt évite. La règle
n'est donc pas affaiblie : elle s'applique là où elle peut encore s'appliquer, et c'est le test
`aRecoveredRunnerStopsNothing` qui le fixe.

## Comportement attendu
1. À la **frontière sûre** (après les résultats d'outils, avant de rappeler le fournisseur) : si
   l'état machine du tour est `OFFLINE`, **arrêter**.
2. La réponse est **déterministe** — aucun appel modèle — et **enrichie des étapes déjà
   accomplies**, tirées de la trace : ce qui a réussi, et ce qui a échoué en dernier.
3. Le tour est marqué comme **arrêté par la machine**, pas comme une erreur du fournisseur ni comme
   une interruption de l'utilisateur : ce sont trois causes différentes, l'écran doit les distinguer.
4. Le travail déjà fait **n'est pas perdu** : messages, trace et fichiers écrits sont persistés comme
   pour un tour ordinaire.
5. Si le poste **revient** avant la frontière, rien ne se passe — comportement d'aujourd'hui.
6. **Débranchable** : sans le réglage, la boucle se comporte exactement comme avant.

| Cas | Comportement |
|---|---|
| Poste vivant tout le tour | rien ne change |
| Poste décroché, puis **revenu dans la même étape** | rien ne change — le dernier appel fait foi |
| Poste décroché à la frontière | **arrêt net**, zéro appel fournisseur, étapes rendues |
| Décrochage au **dernier** tour d'itération | arrêt net également — la boucle n'allait de toute façon plus appeler |
| Interruption utilisateur **et** poste décroché | l'**interruption gagne** : c'est un geste, pas un incident |

## Critères d'acceptation
- [ ] Un décrochage à la frontière arrête la boucle **sans aucun appel fournisseur supplémentaire**
      — vérifié en comptant les appels.
- [ ] La réponse rendue **nomme les étapes déjà accomplies** et **ne vient pas du modèle**.
- [ ] Un poste revenu **dans la même étape** n'arrête rien.
- [ ] L'issue est **distincte** de l'interruption utilisateur et d'une panne fournisseur.
- [ ] Le tour arrêté **persiste** son message et sa trace comme un tour ordinaire.
- [ ] Réglage à `false` → comportement d'avant, à l'identique.
- [ ] **ISOLATION** : l'état machine est lu par `(userId, workspaceId)`, comme aujourd'hui.

## Plan de test minimal
**Unitaires** — la boucle s'arrête quand `machineOfTurn` vaut `OFFLINE` à la frontière, et le
fournisseur n'est **pas** rappelé (compteur d'appels du double) · un `REACHED` après un `OFFLINE`
n'arrête pas · l'interruption utilisateur prime · le texte rendu cite les outils réussis et le
dernier échec · réglage à `false` → la boucle continue.
**Intégration** — le tour arrêté rend un `done` porteur de son issue propre, et le message est
persisté.
**Isolation** — l'état machine d'un autre projet n'arrête pas celui-ci.

## Technique
| Élément | Changement |
|---|---|
| `AtelierChatService` | à la frontière sûre, tester l'état machine **après** `interruptedTurns` |
| `RunnerStopSummary` | construit le texte déterministe à partir de `AtelierToolTrace` |
| `AtelierChatResult` | une issue « arrêtée par la machine », distincte de `interrupted` |
| réglage `app.runner.stop-on-offline` | défaut `true`, débranchable |

**Aucune migration.**

## Préoccupations transversales
Aucune. Ni auth, ni contexte tenant, ni plans/limites, ni routing. L'état machine est déjà lu par
`(userId, workspaceId)` et sa portée reste le tour.

## Hors périmètre
Le **journal des ruptures** (**SF-161-03**) · le **ping conditionnel** (**SF-161-04**) · la reprise
du tour après retour du poste (le tour vit dans le flux, F-84) · **corriger la cause** des
déconnexions — on ne la connaît pas, et la deviner produirait un correctif qui ne corrige rien.
