---
name: plan-dashboard
description: À la demande, relit la carte du projet et l'état du sujet et rend un tableau de bord : ce que le projet fait, décisions, contraintes, ce qui reste à faire.
---

# /plan-dashboard

Fait le point sur la carte du projet **quand on te le demande** : ce qu'elle dit, ce qui lui manque,
et ce qui reste à faire. Ce n'est pas un rituel de fin de tour — c'est un instantané qu'on réclame.

## Comment procéder

1. **Lis `PLAN-ACTION.md` et `STATE.md`** en entier. S'ils n'existent pas, dis-le et propose de les
   créer à partir des gabarits — ne les invente pas à partir du code.
2. **Cherche ce qui est durable et absent.** Parcours `STATE.md`, et les derniers messages de commit :
   une décision prise, une contrainte découverte, une limite mesurée, un piège rencontré. Compare-les
   à la carte. Ce qui n'y figure pas est à **ranger**.
3. **Range ce qui manque.** Ajoute les éléments durables absents à la bonne section de
   `PLAN-ACTION.md` — décision avec son motif, contrainte avec ce qu'elle impose. Une décision sans
   motif se rouvre tous les trois mois.
4. **Fais le point sur ce qui reste à faire.** Les cases non cochées de la section « À faire ». Pour
   chacune : encore d'actualité, ou devenue sans objet ? Une ligne sans objet se retire, elle ne se
   traîne pas.
5. **Rends le tableau de bord.**

## Ce que ça produit

Un état en quatre points, dans cet ordre :

- **Ce que le projet fait** — trois lignes, reprises de la carte.
- **Décisions et contraintes** — le nombre, et celles ajoutées à ce passage.
- **Ce qui reste à faire** — le nombre de cases non cochées, et lesquelles.
- **Ce qui manque encore à la carte** — ce que tu as trouvé mais pas su où ranger.

## Ce qu'il ne faut pas faire

- Réécrire la carte : on l'enrichit, on ne la refond pas. Ce qui y est écrit a été décidé.
- Cocher une case parce qu'elle est vieille. Une case se coche quand le travail est fait.
- Promouvoir le contenu de `STATE.md` en bloc : la carte n'est pas une archive des brouillons.
