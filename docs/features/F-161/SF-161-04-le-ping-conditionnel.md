# Mini-spec — F-161 / SF-161-04 — Le ping conditionnel qui exécute vraiment

## Identifiant
`F-161 / SF-161-04` — feature parente `F-161` (réserve du cadrage §7, **rouverte sur demande du PO**)

## Objectif
Fermer le **seul trou** que la porte (SF-161-01) et l'arrêt net (SF-161-02) laissent ouvert : un poste
qui **bat encore** et **déclare** les bonnes capacités, mais **n'exécutera pas** — en le faisant
**exécuter vraiment**, une seule fois, et **seulement quand plus rien ne prouve** qu'il exécute.

## Le cadrage, et pourquoi on l'ouvre maintenant
Le cadrage §6 écarte le **ping systématique** (« un aller-retour à chaque tour ajoute de la latence
à tous ») et le garde en réserve, **conditionnel**, avec sa condition écrite noir sur blanc :

> *seulement si le dernier appel réussi date de plus de N secondes*

Le §7 bis l'a maintenue en réserve le 2026-09-26 faute de mesure. **Le PO demande sa livraison.**
L'arbitrage est tracé au bas de cette mini-spec : ce qui est livré est **exactement la forme
conditionnelle** que le §6 autorise — pas le ping systématique qu'il refuse. Un tour dont
l'exécution vient d'être prouvée **ne ping pas**, donc ne paie **aucune** latence.

## Ce que la porte ne sait pas voir
| Savoir | Ce qu'il prouve | Ce qu'il ne prouve pas |
|---|---|---|
| Battement (`RunnerLiveness`, 30 s / 90 s) | le processus émet encore | qu'il **exécute** |
| Capacités déclarées (`ready`) | ce qu'il **savait** faire **à la connexion** | qu'il le fait **maintenant** |

Un runner dont le pool de workers est bloqué, dont le disque du projet a disparu, ou dont le canal
est remplacé sans que le battement s'arrête, **passe la porte** — et le tour est payé entier pour
apprendre qu'il ne passera pas. C'est le cas que 01 (l'entrée) et 02 (l'arrêt net) ne couvrent pas.

## Comportement attendu
1. La porte s'ouvre comme avant (battement, puis capacités). **Rien ne change** jusque-là.
2. Juste avant de laisser passer, si le poste n'a **rien exécuté** depuis `app.runner.ping.after`
   (défaut **PT2M**), la gateway envoie **un vrai appel d'outil** au runner et attend sa réponse.
3. **Une réponse quelconque du runner vaut preuve** — y compris une erreur d'outil : ce qui est
   prouvé, c'est que la trame a été reçue, que le projet a été résolu et qu'un worker a répondu.
4. **Silence** (délai dépassé) ou **canal disparu** → refus **nommé** `runner_unresponsive`, **zéro
   jeton**, avec « Demander quand même » comme les deux autres refus de la porte.
5. **Tout autre doute** (réponse illisible, outil refusé par la gateway, erreur inattendue du
   routeur) → **on laisse passer**. Une ignorance ne ferme rien — doctrine de SF-161-01.
6. Tout appel d'outil **réel** qui aboutit rafraîchit la preuve : une session active ne ping
   **jamais** après son premier tour.
7. Débranchable : `app.runner.ping.enabled=false` → comportement d'avant, à la ligne près.

| Cas | Comportement |
|---|---|
| Preuve d'exécution récente (< `after`) | **aucun ping**, aucune latence |
| Aucune preuve, runner qui répond | ping (quelques ms), preuve posée, le tour s'ouvre |
| Aucune preuve, runner **muet** | **refus `runner_unresponsive`**, zéro jeton |
| Aucune preuve, canal disparu | **refus `runner_unresponsive`**, zéro jeton |
| Réponse illisible / doute | **laisse passer** |
| Projet hébergé (`SANDBOX`) | jamais de porte, donc jamais de ping |
| « Demander quand même » | passe, comme pour les deux autres refus |
| Ping débranché | comportement d'avant |

## L'outil du ping, et pourquoi celui-là
Le ping émet `read_file` sur un **chemin qui ne peut pas exister** (`.cg-ping-<aléa>`).

- Il relève de la capacité **`files`**, que **tout** runner déclare — le ping marche donc avec les
  runners **déjà installés**, sans mise à jour du poste. C'est décisif : le ping doit protéger le
  parc **tel qu'il est**.
- Il traverse **toute** la chaîne d'exécution : réception de la trame, résolution du **projet**,
  soumission au **pool de workers**, émission de la trame terminale. C'est ce que « exécuter
  vraiment » veut dire.
- Il est **le moins cher** des outils fichiers : un `stat` qui échoue. `list_files`, `grep` ou
  `glob` parcourent l'arborescence — inacceptable avant chaque tour éligible.
- Il n'écrit **rien**, ne lit **rien** : l'aléa garantit l'absence du fichier, donc un `not_found`
  immédiat.

## Critères d'acceptation
- [ ] Preuve d'exécution fraîche → **aucun appel** au routeur : le ping ne coûte rien à une session active.
- [ ] Sans preuve, runner répondant (`not_found`) → la porte s'ouvre et **la preuve est posée**.
- [ ] Sans preuve, runner **muet** (`runner_timeout`) → refus `runner_unresponsive`, **`AIProvider` jamais appelé**.
- [ ] Sans preuve, canal disparu (`runner_unavailable`) → refus `runner_unresponsive`, zéro jeton.
- [ ] Réponse illisible (`runner_protocol_error`) ou exception du routeur → **laisse passer**.
- [ ] Un appel d'outil réel abouti **rafraîchit** la preuve (plus de ping au tour suivant).
- [ ] `app.runner.ping.enabled=false` → **aucun** ping, comportement d'avant.
- [ ] Cible `SANDBOX` → aucun ping.
- [ ] « Demander quand même » → passe malgré un poste muet.
- [ ] **ISOLATION** : la cible du ping est construite depuis le `Workspace` rendu par `requireOwned` — jamais depuis un identifiant client.
- [ ] La capacité entre sur la **carte** (F-156) avec ses témoins de branchement, comme SF-161-05 l'exige.

## Plan de test
**Unitaires — `RunnerPingTest`**
- [ ] preuve fraîche → routeur **jamais** appelé, verdict « passe »
- [ ] réponse d'erreur du runner (`not_found`) → verdict « passe » **et** preuve posée
- [ ] `runner_timeout` → verdict « muet », preuve **non** posée
- [ ] `runner_unavailable` / `runner_not_on_this_node` → verdict « muet »
- [ ] `runner_protocol_error` → verdict « passe » (doute), preuve non posée
- [ ] exception du routeur → verdict « passe »
- [ ] désactivé → routeur jamais appelé
- [ ] le délai du ping est **celui de la configuration**, pas celui d'un outil

**Unitaires — `RunnerExecutionProofTest`**
- [ ] `ok` et erreur **d'outil** valent preuve ; les codes **du backend** ne valent pas preuve
- [ ] fenêtre de fraîcheur respectée ; poste inconnu = aucune preuve
- [ ] la carte ne croît pas sans fin (purge)

**Unitaires — `RunnerDoorTest`**
- [ ] porte + poste muet → `runner_unresponsive` avec le **nom du poste**
- [ ] le ping n'est consulté **qu'après** battement et capacités (jamais sur un poste déjà mort)
- [ ] sans ping branché → comportement d'avant

**Intégration — `AtelierChatServiceRunnerDoorTest`**
- [ ] poste muet au ping → `RunnerNotReadyException`, **`AIProvider` jamais appelé**, rien persisté
- [ ] poste répondant → le tour s'ouvre normalement
- [ ] `force=true` → passe malgré le poste muet

**Non-régression**
- [ ] `RunnerCallRouterTest` : le routage est inchangé, la preuve n'est posée que sur réponse du runner
- [ ] `CapabilityMapTest` : les capacités non mesurables sont désormais **trois**, toutes avec témoins

**Frontend — `atelier.component.spec.ts`**
- [ ] `runner_unresponsive` ouvre « Demander quand même », comme les deux autres codes de la porte

## Tables / endpoints / composants impactés
| Élément | Changement |
|---|---|
| `RunnerExecutionProof` (nouveau) | mémoire du **dernier fait d'exécution** par poste, en mémoire de pod |
| `RunnerPing` (nouveau) | le ping **conditionnel** : ne part que sans preuve fraîche |
| `RunnerPingVerdict` (nouveau) | passe / muet, avec sa raison |
| `RunnerDoor` | consulte le ping **en dernier**, via un mutateur facultatif (aucun 2ᵉ constructeur) |
| `RunnerDoorVerdict` | nouveau code `runner_unresponsive` |
| `RunnerCallRouter` | pose la preuve quand le **runner** a répondu |
| `AtelierChatService` | passe la **cible** (poste + projet) à la porte |
| `CapabilityMap` | nouvelle capacité `ping-du-poste`, non mesurable, jugée par ses témoins |
| `atelier.component.ts` | `runner_unresponsive` rejoint les codes de la porte |

**Aucune migration** : la preuve est un fait **volatil** (« ce poste a exécuté il y a 12 s »), pas
une donnée du produit. La persister obligerait à l'écrire à **chaque** appel d'outil — une écriture
par outil pour économiser un aller-retour toutes les deux minutes.

## Contraintes de validation
| Champ | Contrainte | Valeur |
|---|---|---|
| `app.runner.ping.enabled` | booléen | `true` |
| `app.runner.ping.after` | durée ; fenêtre sans preuve au-delà de laquelle on ping | `PT2M` |
| `app.runner.ping.timeout-ms` | délai armé côté runner | `1200` |
| Chemin sondé | relatif, aléatoire, inexistant | `.cg-ping-<16 hex>` |

**Coût du pire cas, assumé et tracé** : le dispatcher attend `timeoutMs + app.runner.call.grace-ms`
(5 s par défaut) avant de conclure au silence. Un poste **bloqué** coûte donc ≈ **6,2 s** d'attente,
**une fois toutes les deux minutes** — contre un tour complet (≈ 1 $) sans la porte. Réduire la grâce
globale serait un réglage de transport, hors de cette subfeature.

## Hors périmètre
Corriger la **cause** des déconnexions (cadrage §6 — toujours refusé) · la **reprise** de tour
(F-84) · un ping **systématique** · un ping **périodique** de fond (il coûterait à *tous* les postes,
y compris ceux dont personne ne se sert) · toute persistance de la preuve · toute modification du
**runner** (le ping doit marcher avec le parc installé).

## Préoccupations transversales
| Préoccupation | Cochée | Composants impactés |
|---|---|---|
| Auth / Principal | **non** | aucun endpoint nouveau ou modifié ; aucun changement de Principal |
| **Contexte tenant** | **oui** | `RunnerDoor` reçoit une `RunnerTarget` construite par `RunnerTargets.of(workspace)` **après** `requireOwned` ; `RunnerCallRouter` route déjà par poste possédé ; `RunnerExecutionProof` est indexée par **poste** (un poste n'a qu'un propriétaire) et ne porte aucune donnée. Composants vérifiés : `AtelierChatService.checkRunnerDoor`, `RunnerDoor.check`, `RunnerPing.probe`, `RunnerCallRouter.call`. |
| **Plans / limites** | **oui** | comme SF-161-01, la porte **ferme** un droit, elle n'en ouvre aucun. Aucun appel aux services de quota n'est ajouté ni déplacé : le refus tombe **avant** `QuotaService`, exactement là où SF-161-01 l'avait posé. |
| Navigation / routing | **non** | aucune route ; un code d'erreur de plus sur un chemin déjà rendu par l'écran |

## Arbitrage tracé — pourquoi la réserve est levée
Le §7 bis conditionnait 04 à une mesure (`runner_disconnects`) alors **vide**. Le PO demande la
livraison de la réserve. Ce qui est livré **respecte la condition du §6** — le ping est
**conditionnel**, pas systématique : sans preuve d'exécution depuis deux minutes, et seulement là.
Une session active n'en paie **jamais** la latence, et le drapeau `app.runner.ping.enabled` rend la
décision **réversible d'un réglage**, sans redéploiement de code.
