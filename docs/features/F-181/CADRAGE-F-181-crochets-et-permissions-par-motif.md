# Cadrage — F-181 Crochets et permissions par motif (les hooks, à notre sauce)

> Demande PO le 2026-10-06 : *« Toujours dans le cadre de la parité Claude Code : est-ce que tu vois des
> workflows, des skills, des sous-agents, des hooks… qu'on peut mettre en place, propres à notre façon
> de travailler ? »* Audit : `docs/audits/AUDIT-2026-10-06-terminal-central-gouvernance-parite.md` §6.

## 1. Constat

- Points d'accroche **fermés** (`AtelierCheckpointKind` : après écriture, avant commande, fin de tour), en code serveur, qui ne peuvent que juger/bloquer — jamais lancer une commande.
- Permissions = outil + **préfixe** de commande, par terminal : pas de joker, pas de chemin, pas de portée poste.
- Claude Code permet à l'utilisateur de déclarer ses hooks (`PreToolUse`, `PostToolUse`, `Stop`, `SessionStart`…) et des permissions par motif. C'est **déterministe** : ça protège la justesse sans coûter de jetons.

## 2. Objectif

Le PO déclare, par poste ou par sujet, des règles **exécutées par le harnais, pas par la bonne volonté
du modèle** : « après un `git commit`, lancer les tests », « avant toute commande qui touche la prod,
demander », « en fin de tour, `mvn -q verify` doit passer », « jamais d'écriture sous `prod/**` ».

## 3. Décisions (par défaut, réversibles)

| # | Décision | Pourquoi |
|---|---|---|
| D1 | **Permissions par motif** : la règle gagne un **motif** de commande (jokers `*`, ex. `kubectl * --context *prod*`, `terraform apply*`, `glab mr merge*`) et un **motif de chemin** pour les outils de fichiers (`**/prod/**`) ; portée **poste** ou **sujet** ; effets `allow` / `ask` / `deny` ; ordre `deny` > `ask` > `allow`. | Restriction propre à un client, sans affaiblir les contrôles globaux |
| D2 | **Crochets déclaratifs** : `{événement, motif, action, effet, portée}`. Événements : `AVANT_COMMANDE`, `APRES_COMMANDE`, `APRES_ECRITURE`, `FIN_DE_TOUR`, `DEBUT_DE_SESSION`. Action : **une commande exécutée sur le poste par le runner** (même chemin que `bash`, même délai max) ou **un rappel** (texte injecté). Effet : `informer` (sortie réinjectée comme résultat) ou `bloquer` (code ≠ 0 → l'action est refusée / le tour ne se termine pas, plafonné par `MAX_END_OF_TURN_BLOCKS`). | Parité hooks, sur l'existant F-50 |
| D3 | **Stockage** : fichier `.claude/crochets.yaml` (poste et sujet) **sur le poste**, lisible et versionnable par le client, lu à chaque tour comme `GOUVERNANCE.md` ; affiché à l'écran Gouvernance (F-177 D6). Création depuis le terminal par `gouvernance_proposer(type: CROCHET)` (F-177). | Le savoir du client vit chez lui ; aucune exécution de code étranger (F-51 préservé : c'est la règle du propriétaire sur son poste) |
| D4 | **Visibilité** : chaque crochet déclenché laisse une ligne dans le fil (« ↳ crochet : tests après commit — 42 OK ») ; un blocage dit lequel et pourquoi. | Pas de magie invisible |
| D5 | **Gabarits proposés** : « tests après commit », « demander avant prod », « vérifier en fin de tour », « rappel de gouvernance en début de session ». | Démarrer vite |

## 4. Découpage

| SF | Titre | Contenu | Estim. |
|---|---|---|---|
| SF-181-01 | Permissions par motif | D1 (colonnes motif/chemin/portée, moteur de correspondance, écran). | 1,5 j |
| SF-181-02 | Le fichier des crochets | D3 lecture/validation du YAML, écran, `gouvernance_proposer(CROCHET)`. | 1,5 j |
| SF-181-03 | Avant et après une commande | D2 `AVANT_COMMANDE`/`APRES_COMMANDE`/`APRES_ECRITURE` exécutés par le runner, D4. | 2 j |
| SF-181-04 | Fin de tour et début de session | D2 `FIN_DE_TOUR` (bloquant) et `DEBUT_DE_SESSION` (rappel), D5. | 1,5 j |

## 5. Critères d'acceptation (extraits)

- Avec `deny kubectl * --context *prod*` au poste CAGIP, la commande est refusée dans tous ses sujets, et pas ailleurs.
- « tests après commit » : après `git commit`, la sortie des tests apparaît dans le fil et dans le contexte du modèle.
- Un crochet `FIN_DE_TOUR` en échec empêche la conclusion au plus N fois, puis le tour se termine en INCOMPLET.

## 6. Préoccupations transversales

- **Plans / limites** : un crochet consomme du temps poste, pas de jetons ; aucun impact quota. Composants : `AtelierPermissionService`, `AtelierCheckpointKind` + exécuteurs, runner (exécution), écran Gouvernance.

## 7. Hors périmètre

Crochets exécutés côté serveur avec du code utilisateur (jamais) ; crochets sur événements de
notification ; crochets en hébergé (sans runner).
