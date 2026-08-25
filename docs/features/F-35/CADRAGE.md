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

## Révision de D1 (2026-08-26) — le flag passe à « activé par défaut »

La décision d'origine — livrer désactivé — reposait sur une analyse **erronée sur deux points**,
corrigée en cadrant F-36 :

1. **« Chaque sous-agent consomme sa propre session sandbox facturée. »** Faux. Les sous-agents sont
   des **threads de la même session** : un seul conteneur, donc aucune multiplication du coût de
   sandbox. L'usage remonté au niveau session inclut déjà tous les threads.
2. **« Le surcompteur constate le dépassement au lieu de l'empêcher. »** Vrai de notre implémentation,
   mais **F-36 y répond** : le budget de session est un plafond dur, partagé entre threads, appliqué
   en verrou pré-requête. Un run avec délégation ne peut pas dépasser son plafond — les threads se
   mettent en pause.

**L'argument « dépense non bornée » ne tient donc plus**, à condition que F-36 soit livrée avant —
c'est l'ordre retenu.

**Ce qui reste vrai, et qui est plus faible** : le risque résiduel n'est pas financier mais
**qualitatif**. La documentation prévient qu'une petite tâche en une étape est un mauvais usage de la
délégation (chaque délégation coûte un aller-retour et un ré-briefing). L'agent pourrait déléguer là
où ça n'apporte rien : plus cher, plus lent, pas meilleur. Cela **se mesure à l'usage**, cela ne se
craint pas à l'avance.

**Décision (owner, 2026-08-26)** : livrer **activé**. Une capacité livrée mais désactivée n'est pas
testée — elle reste du code mort en production, dont on découvrirait le comportement réel le jour de
son activation. Le flag est **conservé** pour couper sans redéployer si l'usage révèle une dérive.

**Condition impérative** : F-35 ne doit pas être activée sans F-36. Si F-36 n'était pas livrée, le
défaut devrait rester « désactivé ».

---

## ⚠️ Le point qui commande tout : le coût

Chaque sous-agent consomme **sa propre session sandbox facturée**. Une tâche déléguée à cinq
sous-agents peut coûter plusieurs fois le run équivalent — et le surcompteur sandbox (SF-28-12) est
alimenté **après** coup, donc il constate le dépassement, il ne l'empêche pas.

C'est le seul écart de cette vague dont le coût n'est **pas réversible** : des sessions facturées ne
se récupèrent pas.

## Décisions par défaut (à contredire si besoin)

| # | Décision | Pourquoi |
|---|----------|----------|
| D1 | **Derrière un flag, ACTIVÉ par défaut** — *révisé le 2026-08-26* | Voir § Révision de D1 ci-dessous : F-36 borne la dépense par construction, l'argument d'origine ne tient plus. Le flag reste, pour pouvoir couper en une variable d'environnement sans redéployer |
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

**À livrer après F-36**, qui borne la dépense par construction. Activée par défaut depuis la révision
de D1 ; surveiller le **taux de délégation** (déléguer une tâche qui n'en valait pas la peine coûte
plus pour un résultat qui n'est pas meilleur) plutôt que le coût brut, désormais plafonné.
