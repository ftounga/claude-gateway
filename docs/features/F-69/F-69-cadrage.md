# Cadrage — F-69 · Supprimer un projet, supprimer un poste

> Dérivé de la ligne **F-69** de `docs/PRODUCT_SPEC.md`. Ce document n'ouvre **aucun périmètre
> nouveau** : il vérifie ce que la suppression fait *réellement* aujourd'hui, il tranche les pièges,
> et il découpe.

---

## 1. Ce qui manque

Constaté par le PO en testant : **on ne peut pas supprimer un projet depuis l'écran.** Le geste
existe pourtant côté serveur depuis F-28 — `DELETE /workspaces/{id}` (`AtelierController:286`) — il
n'a simplement **jamais été exposé**. Résultat : les projets d'essai s'accumulent, et la liste de la
Forge devient un cimetière que personne ne peut balayer.

Même chose côté postes : `DELETE /runner-hosts/{hostId}` existe, il n'est appelé par aucun écran.

## 2. Ce que la suppression efface — vérification, pas supposition

La consigne du PO était explicite : *vérifier ce que `DELETE /{id}` supprime **réellement** avant
d'exposer le bouton*, parce que SF-11-03 (suppression de compte) a écrit une purge large — fichiers
du stockage objet, messages, jetons runner, journal d'audit. **Si ce chemin touchait un fichier de
la machine de l'utilisateur, ce serait un défaut à corriger avant tout bouton.**

Chemin réel de `DELETE /workspaces/{id}`, lu ligne à ligne :

| Étape | Code | Ce que ça touche | Machine de l'utilisateur ? |
|---|---|---|---|
| 1 | `sessionService.resetSession` | `AIProvider.terminateSession(sessionId)` — la **sandbox du fournisseur** | **Non.** L'appel part chez le fournisseur d'agents, pas dans le canal runner. Best-effort. |
| 2 | `storage.deletePrefix(prefix)` | clés `<prefix>/<userId>/<workspaceId>/` du **stockage objet de la gateway** | **Non.** C'est le bucket de la gateway. Un projet en cible `RUNNER` n'y a même rien d'autre que ce qu'on y a importé. |
| 3 | `atelierMessageRepository.deleteByWorkspaceId` | la **conversation** | Non |
| 4 | `governanceActivations.deleteByUserIdAndWorkspaceId` | les **réglages** de gouvernance | Non |
| 5 | `workspaceRepository.delete` | la **ligne du projet** | Non |

**Verdict : aucune étape n'ouvre le canal runner, aucune n'émet de commande, aucune ne nomme un
chemin de la machine.** Le `project_path` d'un projet local n'est lu à aucun moment de ce chemin. Le
dossier sur la machine **n'est pas touché** — et il ne peut pas l'être, puisque rien n'appelle la
machine. Il n'y a donc **pas de défaut à corriger** : le bouton peut être exposé. C'est un test de
non-régression qui le dira désormais, et non plus une lecture.

### Le seul manque réel : le journal

Le chemin **laisse derrière lui** les lignes de `runner_audit` du projet — le **journal** que le PO
range explicitement dans ce qui part avec un projet. Elles deviennent inatteignables (la seule
lecture est `GET /workspaces/{id}/runner/audit`, sur un projet qui n'existe plus) tout en continuant
de porter des **commandes exécutées et des chemins lus**. SF-11-03 les purge à la suppression de
compte, pour cette raison exacte ; à la suppression d'un projet, personne ne le faisait. **C'est le
seul correctif backend de F-69.**

### Ce qui ne part PAS, et c'est voulu

`usage_turns` — le journal de consommation par tour (F-61). Ce sont des **pièces de facturation** :
la dépense a bien eu lieu. F-61 l'avait déjà anticipé, noir sur blanc dans
`UsageByClientService.projectNames` : *« Un projet supprimé depuis reste absent de cette table : son
relevé survit, et l'écran le dira supprimé. »* Effacer ces lignes ferait **baisser** une
consommation déjà facturée. On n'y touche pas.

## 3. Le dossier de la machine — ce que l'écran doit écrire

Décision PO, non rouvrable : **supprimer un projet efface uniquement ce qui est côté gateway.**
Supprimer les fichiers d'un client depuis une application web serait irréversible et illégitime.

La conséquence n'est pas seulement technique, elle est **rédactionnelle** : la confirmation doit
l'**écrire**, en clair, sans jargon. Quelqu'un qui hésite devant un bouton « Supprimer » se demande
exactement une chose — *est-ce que ça touche à mes fichiers ?* La réponse doit être sur l'écran, pas
dans une documentation.

## 4. Suppression d'un poste — refus, pas cascade

Décision PO, non rouvrable : **refusée tant qu'il reste des projets.**

Le comportement actuel de `DELETE /runner-hosts/{hostId}` **détache** les projets (ils survivent,
orphelins). Ce n'est ni la cascade ni le refus : c'est une troisième voie, qui laisse des projets
sans machine sans que personne l'ait demandé. F-69 la remplace par le refus.

Pourquoi le refus plutôt que la cascade : une cascade effacerait des conversations que l'utilisateur
ne voyait même plus. Le refus l'oblige à **regarder ce qu'il jette**. Et le message doit être
actionnable : **combien** de projets restent, et **où** les trouver.

## 5. Arbitrages du cadrage

| # | Question | Décision | Alternative écartée | Réversible |
|---|---|---|---|---|
| A1 | Où vit « Supprimer un projet » ? | **La liste des projets de `/atelier`** (menu de dépassement par ligne). C'est la seule vue qui liste **tous** les projets, y compris ceux rattachés à **aucun** poste — or ce sont précisément les projets d'essai que le PO veut balayer. | Les lignes de projet de `/forge` : elles n'affichent que les projets **rattachés**, le ménage y serait partiel. | Oui |
| A2 | Où vit « Supprimer le poste » ? | **La carte du poste sur `/forge`.** Les postes ne sont listés nulle part ailleurs. | Un écran de réglages par poste : il n'existe pas, et l'inventer pour un bouton serait disproportionné. | Oui |
| A3 | `/forge` était « lecture seule » (F-49) | On y pose **un** geste destructif — celui du poste — et rien d'autre. La règle F-49 tenait contre un écran « champ de mines » ; SF-60-02 y a déjà posé le changement d'état de mission. Un geste, sous menu de dépassement, derrière un dialogue, dont le cas dangereux est **refusé par le serveur**. | Tout déplacer ailleurs : il faudrait créer un écran pour un bouton. | Oui |
| A4 | Refus du poste : écran ou serveur ? | **Les deux, et le serveur fait foi.** Le serveur refuse en 409 avec le compte ; l'écran dit la même chose **avant** le clic, pour ne pas faire cliquer sur un bouton qui va refuser. | Écran seul : contournable, et faux dès qu'un autre onglet crée un projet. | Non (garde serveur) |
| A5 | Purge du journal (`runner_audit`) à la suppression d'un projet | **Oui.** Le PO range le journal dans ce qui part, il porte des commandes et des chemins, et il devient illisible sans son projet. Même règle qu'à la suppression de compte. | Le garder : des données personnelles sans porte d'entrée, que plus rien ne permet d'effacer sauf supprimer son compte. | **Non** — irréversible, mais c'est la décision PO explicite, et c'est le pendant exact de SF-11-03. |
| A6 | Purge de `usage_turns` | **Non** (voir §2). | La purger : une facture qui rétrécit. | Non |
| A7 | Quatrième registre de couleur ? | **Aucun.** Le dialogue de suppression emploie `mat-flat-button color="warn"` — le bouton destructif **déjà** défini au §5 de la charte. Ni filet, ni pastille, ni fond nouveau. La couleur d'identité du poste (§9, SF-49-03) et les pastilles de mission (F-60) restent **intactes**. | Une couleur « danger » propre à F-69 : ce serait le quatrième registre que le cadrage interdit. | — |

## 6. Ce qui ne bouge pas

- Les URL : aucune route ajoutée, aucune redirection.
- La vue des missions (F-49, SF-49-03, F-60, F-68) : cartes, tri, couleurs, fil d'Ariane — inchangés.
- Le contrat de `DELETE /workspaces/{id}` : même verbe, même chemin, même 204, même 404 d'isolation.
- La facturation : `usage_turns` et les mois-postes (F-65) suivent leurs règles existantes.

## 7. Hors périmètre (rappel de la ligne PRODUCT_SPEC)

- La **corbeille** et la restauration.
- L'**archivage** — l'état *clôturé* de F-60 le couvre déjà.
- Toute action sur les **fichiers de la machine**, à quelque titre que ce soit.

## 8. Découpage

| Subfeature | Objet | Périmètre |
|---|---|---|
| **SF-69-01** | Ce qu'une suppression efface, et ce qu'elle refuse | Backend seul |
| **SF-69-02** | Le bouton, et ce qu'il écrit avant d'effacer | Frontend seul |

Ordre imposé : **SF-69-01 avant SF-69-02** — l'écran ne doit jamais promettre un refus que le
serveur n'applique pas encore.
