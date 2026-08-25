# Cadrage — F-35 : Sous-agents

## Identifiant / Statut / Date

`F-35` · `cadré, décisions par défaut prises` · 2026-08-25

## Objectif

Permettre à l'agent de **déléguer** des sous-tâches à des agents parallèles, pour les travaux qui
fanent (analyser N fichiers, explorer plusieurs pistes).

## Contexte

Un run est aujourd'hui strictement séquentiel. Sur une tâche large — « audite ce projet », « corrige
tous les tests rouges » — l'agent traite en série et remplit son contexte de lectures.

## Ce que l'API offre

`multiagent: {type: "coordinator", agents: [{type: "self"}, "agent_xxx", {type: "advisor", model}]}`
sur la configuration d'agent : le coordinateur délègue à des copies de lui-même ou à des agents
dédiés, éventuellement sur un modèle moins coûteux.

## ⚠️ Le point qui commande tout : le coût

Chaque sous-agent consomme **sa propre session sandbox facturée**. Une tâche déléguée à cinq
sous-agents peut coûter plusieurs fois le run équivalent — et le surcompteur sandbox (SF-28-12) est
alimenté **après** coup, donc il constate le dépassement, il ne l'empêche pas.

C'est le seul écart de cette vague dont le coût n'est **pas réversible** : des sessions facturées ne
se récupèrent pas.

## Décisions par défaut (à contredire si besoin)

| # | Décision | Pourquoi |
|---|----------|----------|
| D1 | **Derrière un flag, désactivé par défaut** en production | Même prudence que la Phase 2 à ses débuts (SF-28-08) : on n'ouvre pas un robinet de coût sans l'avoir observé |
| D2 | Roster limité à **`{type: "self"}`** | Pas d'agent supplémentaire à provisionner ni à versionner ; le gain de parallélisme est déjà là |
| D3 | **Plafond du nombre de sous-agents** par run, configurable, défaut **3** | Borne le pire cas à un multiple connu, pas à un nombre décidé par le modèle |
| D4 | Pré-vol de quota **renforcé** avant un run susceptible de déléguer | Refuser avant d'engager coûte zéro ; constater après coûte le dépassement |
| D5 | Le coût affiché du tour **agrège** les sous-agents | Sinon l'utilisateur verrait un coût faux, et c'est exactement le piège que SF-30-05 a évité |

## Découpage

| SF | Contenu |
|----|---------|
| **SF-35-01** | Roster `multiagent` derrière flag + plafond + pré-vol renforcé (backend) |
| **SF-35-02** | Agrégation de la consommation des sous-agents dans le coût du tour (backend) |
| **SF-35-03** | Visibilité des sous-tâches dans la vue terminal (frontend) |

## Hors scope

Agents spécialisés dédiés (roster nommé) ; advisor sur un autre modèle ; choix du modèle des
sous-agents par l'utilisateur.

## Recommandation

**À livrer en dernier**, et à n'activer en production qu'après avoir observé le coût réel sur un
usage normal. C'est la seule feature de cette vague qui peut coûter cher sans prévenir.
