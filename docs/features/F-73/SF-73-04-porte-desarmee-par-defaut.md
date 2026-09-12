# SF-73-04 — La porte de confirmation redevient désarmée par défaut

## Objectif

Rendre un projet neuf utilisable, en attendant que l'invite d'autorisation s'affiche réellement.

## Pourquoi, et ce que cela coûte

**Décision du PO le 2026-09-12**, prise devant un défaut constaté en production : *« remets
l'autorisation par défaut, au moins pour les premières démos. On va fixer ça plus tard. »*

SF-73-02 avait armé la porte à la création. Le raisonnement tenait : le confinement retiré, la
confirmation devenait ce qui s'interpose. Mais il supposait que **l'invite s'affiche** — et elle ne
s'affiche pas. Journaux de production, deux tours consécutifs :

```
10:20:34  Autorisation demandée — décision attendue sous 120 000 ms
10:22:34  Aucune décision dans le délai : commande refusée
10:22:34  Autorisation demandée (seconde tentative)
10:24:34  Aucune décision dans le délai : commande refusée
10:24:38  Tour terminé : 2 étapes, 247 s
```

Conséquence, tant que le défaut d'affichage vit : **toute première commande de tout projet neuf
attend deux minutes, puis est refusée**. Ce n'est pas une gêne, c'est un produit inutilisable en
démonstration.

**Ce que ce retour arrière coûte, et que le PO assume en connaissance de cause** : depuis F-73, le
confinement n'existe plus — ni pour `bash`, ni pour les outils fichiers, ni pour les secrets. Avec la
porte désarmée, **un projet neuf exécute donc des commandes sur la machine sans confinement et sans
confirmation**. Il reste le journal d'audit, le coupe-circuit, et la déclaration de portée au
démarrage du runner.

**Ce n'est pas la fin de F-73 ni de l'ADR-019** : c'est une mesure d'attente, explicitement
temporaire. Le défaut d'affichage (famille F-47) doit être traité, et la porte réarmée ensuite.

## Comportement attendu

- Un projet **créé après** cette livraison a `agent_ask_before_bash = false` : aucune invite, la
  commande s'exécute.
- Un projet **existant** garde exactement le réglage qu'il porte — y compris ceux créés armés depuis
  le 2026-09-12. Aucun `UPDATE`, rien ne se désarme dans le dos de personne.
- Le réglage reste modifiable à l'écran, dans les deux sens.

## Cas d'erreur

Aucun : le changement ne porte que sur une valeur par défaut.

## Critères d'acceptation

1. La colonne `agent_ask_before_bash` a pour défaut `false` (PostgreSQL **et** H2).
2. L'entité `Workspace` initialise le champ à `false`.
3. Les trois sources de projet — local, archive, dépôt Git — créent désarmé.
4. Aucun projet existant n'est modifié : la migration ne contient **aucun** `UPDATE`.
5. Le réglage reste modifiable dans les deux sens.

## Plan de test

- Création par les trois sources → `agentAskBeforeBash` faux.
- Un projet existant armé reste armé après migration.
- Bascule du réglage dans les deux sens.
- Isolation `user_id` inchangée sur le chemin de réglage.

## Impacté

`Workspace.java` (valeur du champ), migration `077`. **Aucun endpoint, aucun écran.**

## Hors périmètre

Le défaut d'affichage de l'invite lui-même. Il est la vraie cause, il sera traité séparément — cette
subfeature ne fait que retirer la conséquence la plus bloquante en attendant.
