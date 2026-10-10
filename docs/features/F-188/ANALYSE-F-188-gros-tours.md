# Analyse des gros tours de la Forge (poste CAGIP), du 2026-10-02 au 2026-10-09

Analyse menée en lecture seule le 2026-10-10. Elle s'appuie sur les tables `usage_turns`, `runner_audit` et `atelier_messages`, et sur le code de `origin/main` (7cf73253).

Les extraits bruts et les scripts sont dans `data/` et `scripts/`, à côté de ce fichier.

## 0. Définitions et limites de la mesure

**Ce que compte `input_tokens`.** Dans `usage_turns`, la colonne `input_tokens` contient déjà les lectures et les écritures de cache : `input = non caché + cache_read + cache_write`. La somme `in + cr + cw` compterait donc deux fois. Ici, « tokens traités » veut dire `input_tokens`.

**Tarif Opus 5.5** (`ProviderPricingProperties:88`), en $ par million de tokens :

| Poste | Prix |
|---|---|
| Entrée | 4 |
| Sortie | 20 |
| Lecture de cache | 0,20 |
| Écriture de cache (TTL 1 h) | 8 |
| Recherche web | 0,01 $ par requête |

Le coût recalculé avec ces prix retombe au centime près sur `provider_cost_usd`.

**Le nombre d'étapes n'est stocké nulle part.**
- Il apparaît seulement dans un log : « Tour d'atelier terminé : N étape(s) », à `AtelierChatService:3418`.
- Les pods ont redémarré il y a environ 10 minutes, donc ces logs sont perdus.
- Le namespace n'est pas expédié vers CloudWatch : le groupe `/aws/eks/legalcase-shared/claude-gateway` contient 0 octet.
- `tool_trace` ne permet pas non plus de les compter : il est borné à 60 000 caractères et les étapes les plus anciennes sont supprimées (`AtelierToolTrace:40`).

**Comment j'ai approché le nombre d'étapes.**
- J'ai délimité chaque tour : il commence au `bootstrap` du runner et finit à la ligne `usage_turns`.
- J'ai compté les appels d'outils du poste dans `runner_audit` sur cet intervalle.
- J'ai ajouté les recherches web (`web_search_requests`). C'est un outil serveur : chaque recherche relance un échantillonnage qui relit tout le contexte.
- J'appelle « passes » le total `appels runner + recherches web + 1`.
- Dans `runner_audit`, `bytes` est la taille de la sortie pour bash, mais la taille du fichier pour `read_file` et `edit_file`.

**Fenêtres comparées.**
- AVANT : du 09-20 au 10-01, sur Opus 5.
- APRÈS : du 10-04 au 10-09, sur Opus 5.5 avec le plafond de 100 étapes.

## 1. Anatomie des gros tours

### 1.1 Vue d'ensemble

| | AVANT (389 tours) | APRÈS (246 tours) | APRÈS sans les 8 tours à recherche web |
|---|---|---|---|
| Tokens traités par tour, moyenne | 0,78 M | 1,13 M (+45 %) | 0,91 M (+17 %) |
| Sortie par tour, moyenne | 4,7 k | 8,3 k (+79 %) | 7,5 k |
| Part du coût des tours > 1,5 M | 34 % | 51 % | 43 % |
| Tours > 5 M | 2 | 6 | **1** |
| Coût moyen d'un tour | 1,55 $ | 0,92 $ | — |

**Effet de composition.** Le coût moyen d'un tour a baissé de 40 %, parce qu'Opus 5.5 est moins cher. Les petits tours ont donc baissé plus que les gros, ce qui gonfle mécaniquement la part des gros tours (35 % → 50 %).

**Hors recherche web, la dérive est modeste** : +17 % de tokens et 1 seul tour au-delà de 5 M. La sortie a augmenté de 61 %, ce qui vient de la verbosité et du raisonnement d'Opus 5.5.

### 1.2 Les tours > 1,5 M (APRÈS : 50 tours, 114,7 $)

| | Tours > 1,5 M | Tours ≤ 1,5 M |
|---|---|---|
| Passes, médiane | 20,5 | 3 |
| Contexte moyen par passe, médiane | 148 k | 146 k |
| Durée, médiane | 5,7 min | 0,8 min |
| Sortie, moyenne | 22 k | 4,8 k |

**Un gros tour est un tour LONG, pas un tour à gros contexte.** Le contexte par passe est le même que dans un petit tour : environ 150 k.

**Ce que contient le contexte d'une passe.** Le plus petit tour à une seule passe fait environ 92 k. C'est le plancher fixe : consigne système, outils, résumé de compaction et derniers messages. Il représente à lui seul à peu près 60 % du contexte d'une passe.

**Outils utilisés.** bash domine nettement, entre 80 et 95 % des appels. Viennent ensuite `read_file`, `edit_file`, `multi_edit` et `grep`. Les sorties d'outils sont petites : quelques Ko par appel, avec un maximum observé de 100 à 170 Ko. Elles sont déjà bornées à 128 Ko.

**Plafonds atteints depuis le 10-04.**
- Plafond de 100 étapes : un seul tour (10-04 12:13).
- Interruptions par le PO : 3 (10-04 16:13, 10-05 14:12, 10-07 19:03).
- Réponse tronquée à 32 k de sortie : 1 (10-07 18:17).
- Plafond de consommation : aucun depuis le 30-09.
- AVANT, avec le plafond à 60 : 5 tours l'ont atteint en 10 jours.

**Compaction.**
- La compaction maison F-117 n'intervient qu'entre deux tours, avant la boucle (`CS:2427`).
- Dans un tour, elle ne se déclenche que si le fournisseur répond « prompt too long » (`CS:2841`). Ce cas n'a pas été observé : le contexte par passe ne dépasse jamais environ 260 k.
- Dans le tour, seul `clear_tool_uses` agit, avec ces réglages :
  - déclenchement à 200 k ;
  - 3 résultats d'outils gardés ;
  - au moins 20 k effacés à chaque fois.
- Quelques passes dépassent 200 k : `clear_tool_uses` a donc pu se déclencher. On ne peut pas le prouver, faute d'instrumentation.

### 1.3 Les 6 tours au-delà de 5 M (APRÈS)

| Date | Espace | Tokens | Sortie | Coût | Appels runner | Recherches web | Durée | Particularités |
|---|---|---|---|---|---|---|---|---|
| 10-04 12:13 | bf4a | 11,5 M | 45 k | 6,03 $ | 54 (bash) | 30 | 33 min | **Plafond de 100 étapes atteint.** 20 commandes REFUSÉES (« une commande est déjà en cours »), 4 erreurs ou délais dépassés, puis une oscillation « Calendrier EKS VÉRIFIÉ / NON VÉRIFIÉ » |
| 10-04 12:27 | bf4a | 11,8 M | 40 k | 5,66 $ | 29 (bash) | **74** | 12 min | La même commande `grep -v Calendrier NOTES.md && echo …` **23 fois**, en alternance VÉRIFIÉ / NON VÉRIFIÉ. Le tour finit sur « INCOMPLET, je l'arrête là » |
| 10-04 12:43 | bf4a | 10,0 M | 47 k | 4,96 $ | 27 | 40 | 15 min | Même fil. La réponse reconnaît : « aucune recherche web n'a abouti dans cette session » |
| 10-04 13:35 | bf4a | 6,8 M | 46 k | 3,85 $ | 23 | 27 | 11 min | Diagnostic d'un module Terraform, avec beaucoup de recherches web |
| 10-05 14:12 | 8210 | 9,7 M | 31 k | 4,92 $ | 23 | **57** | 12 min | Le PO relance un sujet de mail. `exploitation.md` est édité ou relu **12 fois** de suite, chaque fois sur la phrase « dates vérifiées le … ». **Le PO interrompt le tour** |
| 10-06 10:24 | 8210 | 9,6 M | 53 k | 9,24 $ | 76 (44 bash, 19 read_file, 10 grep) | 0 | 15 min | Tâche **légitime** : parcourir l'historique et créer 13 tickets Jira validés un par un. Écriture de cache anormale : 804 k, soit environ 4 fois le contenu nouveau |

**Le PO a dû relancer.** Le fil bf4a du 10-04 (inventaire du drift Terraform) se compose de 4 tours enchaînés par **8 messages « continue »** du PO, pour 19,5 $ et 38 M de tokens.

**Les 8 tours APRÈS qui ont utilisé la recherche web** représentent :
- 32 $, soit **14 % de la facture APRÈS** et 28 % du coût des gros tours ;
- 59 M de tokens, soit 21 % du volume total ;
- 263 recherches au total ;
- **5 des 6 tours au-delà de 5 M**.

## 2. Cause racine principale : les résultats de recherche web disparaissent du contexte d'une étape à l'autre

**Où ça se passe dans le code.**
- La Forge déclare les outils serveur `web_search_20260209` et `web_fetch_20260209` (`AnthropicAgentProvider:77-79`).
- À la lecture de la réponse (`AnthropicAgentProvider:1078-1113`), seuls les blocs `text`, `thinking`, `redacted_thinking` et `tool_use` sont conservés. Les blocs `server_tool_use`, `web_search_tool_result`, `web_fetch_tool_result` et ceux d'exécution de code sont **silencieusement abandonnés**.
- Le flux streamé repasse par le même analyseur, donc il les perd aussi.
- `pause_turn` n'est pas traité : `finished = !"tool_use".equals(stopReason)`, si bien qu'une pause de l'outil serveur passe pour une fin de tour.

**Ce que ça produit.**
1. Pendant l'appel N, le modèle cherche et lit les résultats. Il écrit alors « Calendrier VÉRIFIÉ » dans `NOTES.md`.
2. À l'appel N+1, l'historique rejoué ne contient plus ni la recherche ni ses résultats. Le modèle ne voit que sa propre affirmation, sans preuve. Fidèle à la doctrine de vérification, il conclut : « aucune recherche web n'a été exécutée, toute mention “vérifié” était fausse ».
3. Il réécrit « NON VÉRIFIÉ », puis recherche à nouveau. La boucle repart.

On voit la boucle dans `runner_audit` le 10-04, de 12:16 à 12:27 : les lignes alternent VÉRIFIÉ et NON VÉRIFIÉ toutes les 5 à 40 secondes. On la retrouve le 10-05 à 14:04 sur `exploitation.md`.

**C'est d'abord un défaut de justesse.**
- L'agent s'accuse à tort d'avoir menti.
- Il marque « non vérifié » ce qu'il avait vérifié.
- Il s'arrête en déclarant le travail « INCOMPLET ».

Le coût vient en conséquence.

## 3. Ce qui coûte vraiment

**Décomposition du coût.**

| Poste | Gros tours APRÈS (> 1,5 M, 2,29 $ en moyenne) | Tours > 5 M (5,78 $ en moyenne) |
|---|---|---|
| Lecture de cache (passes × contexte) | 30 % | 33 % |
| Écriture de cache | 48 % | 45 % |
| Sortie | 19 % | 15 % |
| Recherche web | 2 % | 7 % |

**La lecture de cache dépend presque entièrement de la longueur du tour.** Elle vaut le nombre de passes multiplié par environ 150 k. Dans ces 150 k :
- le plancher fixe d'environ 92 k pèse à peu près 60 % ;
- la croissance du contexte pendant le tour pèse à peu près 40 %.

Un tour d'une seule passe coûterait 0,03 $ en lecture. **Presque toute la lecture vient donc du nombre de passes, pas du contexte initial.**

**L'écriture de cache est le premier poste, et elle est anormale.**
- La médiane des écritures vaut **3,2 fois** le contenu nouveau visible, c'est-à-dire la sortie plus les sorties d'outils.
- Exemples : 23 fois sur 10-09 15:39, 15 fois sur 10-05 16:23, 4 fois sur 10-06 10:24.
- Trois sources sont possibles, et on ne peut pas les départager sans mesure par appel :
  - (a) le cache est froid en début de tour (plus d'une heure depuis le tour précédent, ou une compaction a changé le préfixe). Cela réécrit 100 à 180 k, soit 0,8 à 1,4 $ ;
  - (b) `clear_tool_uses` s'est déclenché, ce qui invalide le cache à partir du premier résultat effacé ;
  - (c) le rejeu entre deux tours ne reproduit pas ce qui était en cache, parce que la trace est tronquée à 8 k par résultat et 60 k par tour.

**La sortie** (19 %) a augmenté de 61 % avec Opus 5.5. Elle relève de la qualité du raisonnement. Ce n'est pas un levier à toucher.

**Part due à la longueur et part due au contexte initial**, pour un gros tour : environ 70 à 80 % du coût suit le nombre de passes. Il s'agit de la lecture, de la sortie et de l'écriture du contenu nouveau. Le reste, soit l'écriture à froid et les réécritures, ne dépend pas de la longueur.

## 4. Le travail était-il utile ? Quatre tours lus

| Tour | Demande | Déroulé | Verdict |
|---|---|---|---|
| 10-04 12:13 et 12:27 (bf4a) | « continue », puis « C'est relancé. Continue » : inventaire du drift Terraform | Une erreur de protocole du runner, puis **20 refus « déjà en cours »** relancés à l'identique. Ensuite 23 réécritures VÉRIFIÉ / NON VÉRIFIÉ et 104 recherches web | **Gaspillage**, environ 80 % du tour. L'inventaire lui-même, fait par AWS Config, CloudTrail et le state, était légitime et avait été produit plus tôt |
| 10-04 12:43 (bf4a) | « continue » | Classeur Excel produit, puis correction : « aucune recherche web n'a abouti » | Moitié utile (le livrable), moitié boucle de recherche |
| 10-05 14:12 (8210) | « Tu te rappelles de ce mail qu'on avait envoyé ? » | 57 recherches, 12 éditions et relectures successives de la même phrase de `exploitation.md` | **Gaspillage**. Interrompu par le PO après 12 minutes |
| 10-06 10:24 (8210) | « Je veux que tu parcoures tout ce qu'on a fait dans notre terminal… » | 76 appels de lecture de l'historique et des fichiers, arbitrage ticket par ticket avec le PO, 13 tickets créés | **Gros chantier légitime.** Le seul surcoût est l'écriture de cache, 804 k |

**Autres gros tours APRÈS sans recherche web.** Ce sont des chantiers ops : diagnostic Terraform, plan de MR, analyse de 107 constats de sécurité, pages publiées. Ils comptent 10 à 40 appels bash et des sorties modestes. Je n'y ai pas vu de boucle, à deux exceptions près :
- 10-07 15:51 : 7 refus « runner occupé » ;
- 10-06 19:37 : 6 relectures du même fichier.

## 5. Leviers, classés par rapport gain / risque pour la justesse

**L1. Conserver les blocs d'outils serveur dans le rejeu du tour, et traiter `pause_turn`.**
- Gain : il porte sur les 8 tours, qui coûtent 32 $ sur 6 jours. Si un tour sans boucle fait 3 à 4 fois moins, on économise environ **20 à 25 $ sur 6 jours, soit environ 10 % de la facture**, et 5 des 6 tours au-delà de 5 M disparaissent.
- Justesse : elle **s'améliore**, puisque c'est un défaut de justesse.
- Risque : les résultats de recherche restent dans le contexte, donc le contexte par passe augmente un peu. Il faut vérifier, dans la documentation du fournisseur, le format exact du renvoi (contenu chiffré) et l'interaction avec `clear_tool_uses`.
- Mesure : recherches par tour, tours au-delà de 5 M, nombre de réécritures d'un même fichier dans un tour, et absence de « aucune recherche n'a abouti » dans les réponses.

**L0. Instrumenter le tour (prérequis à toute mesure).**
- Ce qu'il faut persister, dans `terminal_json` ou dans des colonnes de `usage_turns` :
  - le nombre d'itérations et le motif d'arrêt ;
  - le contexte maximal et moyen par passe ;
  - l'écriture de cache de la première passe, puis celle des suivantes ;
  - le nombre d'effacements `clear_tool_uses` appliqués (`context_management.applied_edits`) ;
  - le nombre de refus du runner.
- Gain direct : nul. Risque : nul.
- C'est la condition pour prouver L4 et L5.

**L2. Runner occupé : attendre côté serveur au lieu de renvoyer un refus au modèle.**
- Avant de rendre « déjà en cours » au modèle, le serveur attend que le runner se libère, avec une borne (par exemple 30 à 60 s).
- Gain : 27 passes gaspillées sur 2 tours en 6 jours, soit environ 1,5 $. Le gain est faible, mais il élimine aussi un motif de boucle.
- Risque pour la justesse : nul.
- Mesure : le nombre de `DENIED` dans `runner_audit` doit tendre vers zéro.

**L3. Détecteur de répétition qui informe sans arrêter.**
- Déclencheurs : la même commande normalisée 3 fois ou plus, le même fichier édité ou relu 5 fois ou plus dans le tour, 3 refus consécutifs.
- Effet : une note factuelle injectée au modèle, du type « tu as exécuté 4 fois la même commande ; résultat inchangé ». Jamais d'arrêt.
- Gain : il borne les pathologies futures, au-delà de la recherche web. Il est faible une fois L1 en place.
- Risque : faible à modéré. Un sondage légitime (`sleep 30; test -f done`) produit des faux positifs, d'où une simple note et des seuils à décider par le PO.
- Mesure : déclenchements par semaine et longueur moyenne du tour après un déclenchement.

**L4. Corriger l'écriture de cache en excès** (48 % du coût des gros tours, médiane 3,2 fois le nécessaire).
- Gain potentiel : jusqu'à 20 ou 30 % du coût des gros tours si la cause est (b) ou (c). Il **n'est pas chiffrable avant L0**.
- Pistes, une fois la mesure faite :
  - régler `clear_at_least` et le déclenchement de `clear_tool_uses`, pour moins d'invalidations ;
  - aligner le rejeu entre tours sur ce qui a été mis en cache.
- Risque : toute modification de `clear_tool_uses` touche au contexte, donc elle est **suspecte par défaut** et relève du PO.

**L5. Point d'étape non bloquant au-delà de N passes ou de X $.**
- Un indicateur en direct, ou une notification, signale le tour qui s'emballe. Le PO décide d'interrompre.
- Gain : il dépend du PO. Le 10-05, le PO a interrompu après 12 minutes ; un signal à 5 minutes aurait économisé environ 2,5 $.
- Risque pour la justesse : nul s'il est non bloquant. S'il est bloquant, il couperait des chantiers légitimes comme celui du 10-06.

**Écartés ou déconseillés à ce stade.**

| Levier | Pourquoi |
|---|---|
| Compaction plus tôt dans le tour, ou résumé des vieilles sorties d'outils | La croissance dans le tour ne pèse qu'environ 40 % de la lecture, soit au plus environ 12 % du coût d'un gros tour. Chaque résumé ou effacement invalide le cache, donc on réécrit. Le gain net est douteux et le risque pour la justesse élevé (perte de preuves, déjà constatée en F-119) |
| Déléguer les lectures massives à un sous-agent | `explore` existe déjà (Sonnet 5, contexte séparé, plafonné à 5 délégations par message). Les gros tours sont à 80-95 % du bash d'opérations, pas de la lecture. Le gain est faible et le risque réel : le résumé d'un sous-agent perd du détail |
| Baisser le plafond d'étapes (100 → 60) | Depuis le 10-04, 1 seul tour a atteint 100, et c'était la boucle de recherche. À 60, on aurait coupé en plus le tour légitime du 10-06 (77 itérations ou plus). Gain d'environ 3 à 5 $ sur 6 jours, au prix de « continue » qui repaient tout le contexte. **On garde 100** |
| Plafonner la sortie ou baisser l'effort | La sortie relève du raisonnement. C'est contraire à la règle absolue du PO |

## 6. Découpage proposé (2 jours au plus par sous-feature)

| SF | Contenu | Estimation | Dépendances |
|---|---|---|---|
| SF-a | **Conserver les blocs d'outils serveur** (`server_tool_use`, `web_search_tool_result`, `web_fetch_tool_result` et exécution de code) dans la réponse et dans le rejeu du tour, à l'identique. Traiter `pause_turn` comme une reprise de boucle, pas comme une fin. Tests : rejeu octet pour octet et reprise sur pause | 1,5 j | — |
| SF-b | **Instrumentation du tour** : itérations, motif d'arrêt, contexte maximal et moyen, écriture de cache à la première passe puis aux suivantes, effacements appliqués, refus du runner. Persistance en base et requête de mesure admin | 1 à 1,5 j | — |
| SF-c | **Runner occupé** : attente bornée côté serveur avant de renvoyer un refus | 0,5 j | — |
| SF-d | **Détecteur de répétition** : note factuelle injectée, compteur persisté | 1,5 j | SF-b, pour le compteur |
| SF-e | **Diagnostic de l'écriture de cache** : mesure sur 7 jours avec SF-b, puis fiche de décision sur `clear_tool_uses` et le rejeu entre tours. Aucun changement de contexte sans accord du PO | 0,5 j de mesure et d'analyse, puis une SF de réglage éventuelle | SF-b |
| SF-f | (Option) **Indicateur « tour long »** non bloquant : passes et coût en direct, notification au-delà d'un seuil | 1 à 1,5 j | SF-b |

**Ordre conseillé** : SF-a et SF-b en parallèle, puis SF-c, puis une mesure à J+7 (base : les chiffres de ce document), puis SF-d, SF-e et SF-f selon le résultat.

## 7. Décisions à faire remonter au PO

1. **SF-a.** Accepter que les résultats de recherche web restent dans le contexte du tour. Le contexte par passe grossit, mais c'est ce que le modèle est censé voir. Je recommande OUI : c'est une correction de justesse.
2. **Plafond d'étapes.** Je recommande de **ne pas y toucher** et de le garder à 100. Toute baisse est une décision PO.
3. **Détecteur de répétition.** Choisir entre une note seule (recommandé) et un arrêt, et fixer les seuils (3 commandes identiques, 5 éditions ou relectures du même fichier).
4. **`clear_tool_uses` et compaction.** Aucun changement avant la mesure SF-e. Tout réglage qui retire du contexte est soumis au PO, avec une mesure de justesse.
5. **Point d'étape.** Non bloquant (recommandé) ou bloquant, et à quel seuil de passes ou de dollars.
6. **Observabilité.** Expédier les logs du namespace vers CloudWatch, dont le groupe existe mais reste vide. C'est hors de la feature, mais c'est ce qui a manqué pour compter les itérations.

## Annexe : données

- `data/turnstats2.json` : un enregistrement par tour Opus depuis le 09-20 (appels runner, refus, erreurs, Ko, doublons, recherches web, coût).
- `data/big.json` : traces des gros tours.
- Scripts : `scripts/turns.py` et `scripts/agg.py`.
