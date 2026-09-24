# Cadrage — F-155 — Le bilan de session

> Demande du PO, 2026-09-24 :
> *« Lorsque je ferme une session — quand je clique sur “reprendre depuis le début” —, qu'un processus
> se lance : qu'il analyse ce qui a été fait, ce que ça a coûté, s'il y a des choses à améliorer. »*
>
> *« Il peut se rendre compte que telle question, je n'aurais pas dû la poser comme ça, c'est pour ça
> que ça a pris du temps et coûté cher. Les axes, c'est toujours les mêmes : le coût, le temps, le
> raisonnement. »*
>
> **Arbitrages du PO, même jour** : déclenchement **automatique au-delà d'un seuil** · bilan **gardé
> comme artefact** · lecture de l'état du poste **plus tard** · **réservé à l'administrateur**.

## 1. Le périmètre, et sa frontière

Ce cadrage couvre **une session** : ce qu'elle a produit, ce qu'elle a coûté, et ce que **l'utilisateur**
aurait pu faire autrement.

**Ce qu'il ne couvre pas** : les optimisations de **l'application elle-même**. Elles relèvent d'un autre
objet — **F-156, le diagnostic du produit** — parce qu'elles regardent une **accumulation** de sessions,
coûtent cher à produire, et débouchent sur des **features**, pas sur un changement d'habitude. Les
mélanger reviendrait à ré-analyser dix fois les mêmes données pour aboutir dix fois à la même
conclusion : exactement le gaspillage que cette feature est censée traquer.

**Le lien entre les deux** : quand le bilan voit un motif revenir (« 4ᵉ fois en dix sessions »), il
**propose** de lancer le diagnostic. Proposé quand c'est justifié, jamais à chaque fermeture.

## 2. Ce qui existe déjà

| Existant | Ce qu'il donne | Ce qu'il ne donne pas |
|---|---|---|
| `usage_turns` (F-133) | par tour : jetons d'entrée, de sortie, **de cache lu et écrit**, coût réel, modèle | aucune **lecture** de ce que la session a fait |
| `runner_audit` | par appel d'outil : nom, cible, **durée**, octets, résultat, code d'erreur | rien d'agrégé à l'échelle de la session |
| Le relevé du tour (F-144) | plan, interruptions, plafond, fichiers modifiés | il sert à **suggérer la suite**, pas à **juger le passé** |
| F-117 | la compaction du contexte | elle **oublie**, elle n'**apprend** pas |

**La matière est là.** Ce qui manque, c'est le regard en arrière.

## 3. Ce que contient le bilan

1. **Ce qui a été fait** — tours, durée, fichiers modifiés, livrables produits, décisions prises.
2. **Ce que ça a coûté** — euros, jetons, temps, **et où l'argent est parti** (quels tours, quels outils).
3. **Ce qui aurait mieux valu, dans l'usage** — sur les trois axes du PO : **coût**, **temps**,
   **raisonnement**.

## 4. La règle qui commande : le seuil d'impact

> *« Je ne veux pas des trucs d'augmentation de 2-3 %. Je vise 10, 20 % minimum. »*

Elle est **structurelle**, pas une consigne polie :

- toute suggestion cite **la mesure de la session d'où elle sort** ; sans mesure, elle est **jetée** ;
- le gain est **calculé** à partir de cette mesure (le prix d'un jeton plein contre celui d'un jeton lu
  en cache est connu, `pricing_version` est stocké), **jamais estimé au jugé** ;
- sous le seuil, la suggestion est **écartée** — et le bilan **dit combien** il en a écartées, plutôt
  que de les diluer pour faire nombre ;
- **« rien à signaler » est une conclusion valide.** Une session bien menée doit pouvoir s'entendre
  dire qu'elle l'était, sinon le bilan devient un bruit qu'on cesse de lire.

## 5. Déclenchement, mémoire, accès

- **Automatique au-delà d'un seuil — en euros ET en tours** (arbitrage du PO, 2026-09-24) : le premier
  des deux atteint déclenche. Une session courte mais coûteuse mérite un bilan ; une session longue et
  bon marché aussi. **En dessous des deux**, le bilan est **proposé d'un clic** — une session de trois
  tours à quelques centimes ne mérite pas un appel modèle payant.
- **Gardé comme artefact** : consultable, comparable d'une semaine à l'autre.
- **Réservé à l'administrateur.**

## 6. Ce qu'on écarte, et pourquoi
- **Analyser tout le transcript** : sur une session de plusieurs heures, l'appel serait démesuré — on
  reproduirait le défaut que F-134 et F-149 viennent de corriger. On part du **relevé** et d'un
  **échantillon** ciblé.
- **Lire le code de l'application** : c'est F-156. Ici, les chiffres suffisent.
- **Lire l'état des fichiers du poste** : reporté (arbitrage du PO). On saura **quoi** aller chercher
  une fois qu'on aura vu ce qui manque au bilan.

## 7. Découpage proposé
| SF | Objet |
|---|---|
| **SF-155-01** | L'agrégation d'une session : ce qui a été fait, ce que ça a coûté, où est parti l'argent |
| **SF-155-02** | Les suggestions d'**usage**, avec **gain calculé** et **seuil d'impact** |
| **SF-155-03** | Le déclenchement : automatique au-delà du seuil, proposé en dessous |
| **SF-155-04** | L'écran et l'**artefact gardé**, réservé à l'administrateur |
| **SF-155-05** | Le **renvoi vers F-156** quand un motif se répète |

## 8. Tranché
- **Seuil** : **les deux** — euros **et** tours, au premier atteint (PO, 2026-09-24).
- **Mémoire** : bilan **gardé comme artefact**.
- **Accès** : **administrateur** seulement.
- **État du poste via le runner** : **reporté**, une fois qu'on saura ce qui manque au bilan.
