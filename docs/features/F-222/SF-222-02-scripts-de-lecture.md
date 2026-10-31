# SF-222-02 — Les scripts de lecture du poste ne sont plus bloqués

**Feature :** F-222 · **Statut :** done (2026-10-31) · **Estimation :** < 0,5 j · **Branche :** `feat/SF-222-02-scripts-de-lecture`

## Objectif
Un script de lecture du poste (`jira.py voir PROJ-1`, `releve-couts.sh`, `sync.mjs --dry-run`) est classé LECTURE : il passe la porte du parcours guidé hors Exécution.

## Constat (cadrage F-222, cause 4)
`JourneyRiskClassifier` classe `python`, `node` (et tout script inconnu) en modification : hors Exécution, la porte refusait un relevé, et l'agent demandait « dis continue pour que je lance les relevés ».

## Comportement
Liste blanche **conservatrice** (`isReadScript`) : un script lancé par `python`, `python3`, `node`, `bash`, `sh`, ou directement (`./x.py`, `.sh`, `.js`, `.mjs`, `.cjs`), est une LECTURE si :
1. son nom commence par « releve » (accents ignorés) ;
2. ou son premier argument positionnel est un verbe de lecture : voir, lire, liste, lister, list, show, get, status, statut, describe, info, releve, relever, consulter, afficher ;
3. ou il porte `--dry-run`.

Jamais pour du code en ligne ou un module (`-c`, `-e`, `-m`, `-p`, `-i`, `--eval`, `--print`). Une redirection vers un fichier, `$(…)` ou un pipe vers un shell restent des modifications (règles existantes, appliquées à la commande entière). Sinon : la classification d'avant.

## Erreurs
| Situation | Comportement |
|---|---|
| `python3 jira.py creer PROJ-1` | modification (inchangé) |
| `python3 -c '…' voir` | modification (code en ligne) |
| `python3 jira.py voir X > rapport.txt` | modification (redirection) |

## Critères d'acceptation
- [x] Les 9 formes de lecture de la spec sont LECTURE.
- [x] Les 7 contre-exemples restent REVERSIBLE.
- [x] Les classifications existantes ne changent pas (tests historiques verts).

## Plan de test
`JourneyRiskClassifierTest.readScripts` (9 cas) et `modifies` (+7 cas). Isolation : non applicable (fonction pure, aucune donnée).

## Impact
`JourneyRiskClassifier` seul. Effets : la porte du parcours guidé (`journeyGateRefusal`) et le parallélisme des lectures (`isParallelSafe`, un script de lecture peut tourner en parallèle d'autres lectures, après résolution des autorisations comme avant). `agent_ask_before_bash` et les règles d'autorisation ne sont pas touchés. Préoccupations transversales : aucune.

## Arbitrage (gate 🟠 réversible)
Le verbe de lecture est cherché sur le premier argument positionnel seulement (pas n'importe où) et la liste est fermée : un faux positif ferait passer une écriture hors Exécution. Retour arrière : retirer l'appel `isReadScript`.

## Hors périmètre
Lire le contenu du script pour juger ce qu'il fait (non déterministe, coûteux).
