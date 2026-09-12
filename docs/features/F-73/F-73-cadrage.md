# Cadrage — F-73 — Le runner n'est plus confiné

> Le PO a tranché le 2026-09-12. Ce cadrage **ne rouvre rien** : il découpe, et il écrit noir sur
> blanc ce que l'application devra dire à la place de ce qu'elle promettait.

**Date** : 2026-09-12 · **Feature parente** : `F-73` (`docs/PRODUCT_SPEC.md`)

---

## Le constat, vérifié dans le code

Le confinement annoncé **n'existait déjà pas** pour `bash`.

`BashTool.resolveWorkingDirectory` fait passer le **répertoire de départ** par le `PathGuard` — donc
le `cwd` est bien sous la racine. Mais **la commande elle-même n'est jamais inspectée** :
`cat ../autre-client/.env`, `ls ~`, `cd /etc` s'exécutent sans obstacle. Le commentaire du code
affirmait pourtant :

> *« Une commande ne s'exécute jamais hors de la racine exposée. »*

Vrai de son `cwd`. **Faux de ce qu'elle fait ensuite.** Cette phrase a induit le PO en erreur pendant
des jours : c'est elle qu'il faut corriger en premier, avant même le code.

Et **un shell ne se confine pas par inspection de texte** : toute liste d'interdits se contourne par
une variable, un `eval`, un script intermédiaire ou un encodage. Seul un **conteneur** confinerait
vraiment — ce qui interdirait l'usage même du produit : travailler sur la machine du client, avec
ses outils, ses accès et son réseau.

## Ce que le PO a tranché (repris tel quel, non rouvert)

| # | Décision |
|---|----------|
| D1 | **Le confinement est retiré partout** — outils fichiers (`PathGuard`) compris. Un chemin absolu, un `..`, un `~` sont acceptés. |
| D2 | **Les exclusions de secrets sont retirées** : `DEFAULT_DENY` (`.env`, `*.pem`, `id_rsa*`, `.aws/`, `.kube/config`, `.ssh/`) disparaît. *« Il doit pouvoir tout faire. »* |
| D3 | **La porte de confirmation redevient armée par défaut** (`agent_ask_before_bash = true` à la création). Elle **annule le défaut de SF-47-04**, pris quand le confinement paraissait exister. |
| D4 | Les projets **existants ne sont pas modifiés**. |
| D5 | **L'application doit le dire** : le runner l'annonce au démarrage (`StartupDisclosure`, F-57), et l'écran le dit **là où l'on autorise une commande**. |
| D6 | **Factuel, jamais alarmiste** : c'est sa machine, il a lancé le runner lui-même. |
| D7 | **Journal d'audit** (`runner_audit`) et **coupe-circuit** : **inchangés**. |
| D8 | **Risque connu et assumé** : la porte ne couvre que `bash`. Un `read_file` sur un `.env` ou `~/.ssh/id_rsa` ne demande rien, et son contenu part chez le fournisseur dans le contexte du tour. |
| D9 | **Hors périmètre** : conteneuriser le runner. |

## Découpage

| SF | Titre | Côté | Dépend de |
|----|-------|------|-----------|
| SF-73-01 | Le confinement tombe, et le runner le dit | runner | — |
| SF-73-02 | La porte de confirmation redevient armée par défaut | backend | — |
| SF-73-03 | Ce que l'écran dit au moment d'autoriser | frontend | SF-73-02 |

Ordre de livraison : **runner puis backend** (indépendants), **frontend en dernier**.

## Registres de couleur — ce qu'on ne touche pas

Trois registres cohabitent déjà, F-73 **n'en ajoute pas un quatrième** :

| Registre | Porte quoi | Où |
|---|---|---|
| Identité du client / du poste (F-49 / SF-49-03) | **quelle machine** | filet de carte, `app-host-badge` |
| État de mission (F-60) | **où en est la mission** | `app-mission-badge`, pastilles `badge--*` |
| Signe de vie (F-70) | **un onglet vit** | `app-live-badge`, `currentColor` |

La mention ajoutée dans l'invite d'autorisation (SF-73-03) vit **dans le bloc `.terminal-ask`
existant**, qui porte déjà l'orange de charte (`--cg-orange-2`) depuis F-33. Elle n'introduit
**aucune couleur nouvelle** : elle emprunte celle du bloc et les tokens de texte déjà en place.

## Arbitrages du cadrage (réversibles, tracés)

| # | Décision | Pourquoi | Alternative écartée |
|---|----------|----------|---------------------|
| A1 | `PathGuard` devient **`PathResolver`** | Une classe qui ne garde plus rien ne doit pas s'appeler « garde » — c'est exactement le mensonge que F-73 corrige | Garder le nom : le prochain lecteur y croirait |
| A2 | Les exclusions ne filtrent plus que le **balayage** (`list_files`, `search_files`) ; un chemin **adressé** (`read_file`, `write_file`) n'est jamais refusé | Refuser la lecture d'un fichier nommé serait un confinement résiduel, contraire à D1/D2 | Garder `excluded` sur les chemins adressés |
| A3 | Le **bruit de construction** (`node_modules/`, `target/`…) reste écarté du balayage | Ce n'est pas un secret mais une question de lisibilité (SF-38-21 : 40 112 fichiers, dont 40 112 − 478 de dépendances) ; il reste **négociable** par `!node_modules/` | Tout retirer : le listage redevient inutilisable |
| A4 | `ProjectScopes` garde la **normalisation** du chemin de projet | Ce n'est plus une garantie de sécurité mais la **validité** du dossier de départ que la gateway indique ; le code doit le dire ainsi | Accepter n'importe quelle valeur : une faute de frappe démarrerait ailleurs en silence |
| A5 | Le défaut armé vaut pour **les trois sources** de projet (local, archive, dépôt) | SF-47-04 les avait alignées à `false` ; les désaligner créerait deux régimes invisibles | N'armer que `createLocal()` |

## Hors périmètre

- Conteneuriser le runner (D9).
- Demander une confirmation supplémentaire à la **lecture** d'un fichier sensible (D8 : risque assumé).
- Toucher au journal d'audit ou au coupe-circuit (D7).
- Le terminal au niveau du poste (**F-74**) et la gouvernance par poste (**F-75**).
