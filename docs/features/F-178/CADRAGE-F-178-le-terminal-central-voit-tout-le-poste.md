# Cadrage — F-178 Le terminal central voit tout le poste

> Demande PO le 2026-10-06 : *« Je voudrais qu'à partir du terminal central on soit capable d'interroger
> tout ce qui a été fait dans l'historique des autres conversations, via le recall qu'on a déjà mis en
> place. Je veux que le terminal central soit beaucoup plus central, conscient de presque toute
> l'application ; mets-lui à disposition les outils qu'il faut. »*
> Audit : `docs/audits/AUDIT-2026-10-06-terminal-central-gouvernance-parite.md` §4.

## 1. Constat

Le terminal du poste retrouve le travail des sujets **par les traces écrites** (`STATE.md`, journaux,
`git log`) et les résolutions F-148 — **pas par les conversations** : `recall` est filtré
`user_id` + `workspace_id` (le cadrage F-162 excluait l'inter-projets). Bilans de session, Radar,
réunions, coûts et pages lui sont invisibles ; le serveur MCP F-112 expose une partie de ces lectures,
mais seulement à une IA externe.

## 2. Objectif

Depuis le terminal du poste, on peut demander *« qu'est-ce qu'on a décidé sur le jeton Atlantis ? »*,
*« où en est chaque sujet ? »*, *« qu'est-ce qui s'est dit en réunion avec CFT ? »*, *« combien m'a
coûté agenor cette semaine ? »* — et l'agent répond en citant ses sources (sujet, date).

## 3. Décisions (par défaut, réversibles)

| # | Décision | Pourquoi |
|---|---|---|
| D1 | **`recall` à portée poste, au poste seulement.** Paramètre `portee: "fil" \| "poste"` déclaré **uniquement** au terminal du poste (garde `isHostTerminal`, comme `create_subject`). Requête : `user_id = ? AND workspace_id IN (sujets du même host_id du même user)`. Chaque extrait est étiqueté `[sujet · date]`. Mot-clé + sémantique (index hnsw existant). Relecture doublement filtrée. Description de l'outil **fixe** (cache). | Demande PO ; l'isolation `user_id` reste totale ; un sujet ne lit pas ses voisins (pas de fuite de contexte entre clients… et un poste = un client). |
| D2 | **`sujets_etat`** (poste seulement) : un tableau par sujet — dernière activité, phase du parcours (F-176), attentes ouvertes (F-175), dernier bilan de session (F-155), coût de la semaine (F-143). Une seule lecture, bornée. | « Où en est chaque sujet ? » sans `bash` |
| D3 | **Lectures de l'application branchées** via un **adaptateur** des outils MCP F-112 existants → `AgentTool` (même code, `userId` du tour) : `radar_resume`, `radar_sujets`, `page_lire`, `pages_lister`, `compte_consommation`. Au poste seulement, et seulement si le poste a la donnée (Radar alimenté). Aucune écriture. | Réutiliser, ne pas réécrire |
| D4 | **Doctrine « le poste sait où chercher »** (littéral stable, poste seulement) : pour une question sur le passé ou un autre sujet → `recall(portee:"poste")` puis `sujets_etat`, avant de fouiller les fichiers ; citer la source. | Justesse : la conversation est la source la plus fidèle des décisions |
| D5 | **Rien n'est joint d'office** au message : tout passe par des outils à la demande. | Coût (règle absolue « justesse avant coût » respectée : on ajoute un accès, on ne retire rien) |

## 4. Découpage

| SF | Titre | Contenu | Estim. |
|---|---|---|---|
| SF-178-01 | Se souvenir de tout le poste | D1 (back : requête, garde, étiquettes ; test d'isolation inter-utilisateurs et inter-postes) + rendu de l'indicateur « recherche dans N sujets ». | 1,5 j |
| SF-178-02 | L'état de chaque sujet | D2. | 1 j |
| SF-178-03 | Les lectures de l'application | D3 : adaptateur MCP → outil de boucle, 5 lectures, au poste. | 1,5 j |
| SF-178-04 | Le poste sait où chercher | D4 + mesure (usage des outils, coût par tour avant/après). | 0,5 j |

## 5. Critères d'acceptation (extraits)

- Au poste, « qu'a-t-on décidé pour le jeton Atlantis ? » renvoie l'extrait du sujet `data-platform` avec date.
- Dans un sujet, `recall` n'expose pas le paramètre `portee` et ne renvoie que son fil.
- Un autre utilisateur, ou un autre poste du même utilisateur, n'apparaît jamais dans les résultats.

## 6. Préoccupations transversales

- **Contexte tenant** : nouveau moyen de lire plusieurs workspaces → composants : `AtelierMessageEmbeddingStore`, `AtelierMessageRepository.searchByContent`, relecture `recall` (`AtelierChatService` ~4930), `ResolutionMemoryStore` (référence). Test de non-régression d'isolation obligatoire.

## 7. Hors périmètre

Lecture d'une boîte de réception (n'existe pas) ; écriture dans un autre sujet depuis le poste ;
rappel inter-postes ; Teams en direct depuis le poste (reste au terminal Teams).
