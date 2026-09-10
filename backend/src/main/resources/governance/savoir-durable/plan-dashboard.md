---
name: plan-dashboard
description: Relit la carte du projet et l'état du sujet, promeut ce qui est durable, et rend un tableau de bord : décisions, contraintes, dette restante.
---

# /plan-dashboard

Fait le point sur la carte du projet : ce qu'elle dit, ce qui lui manque, et ce qui reste dû.

## Comment procéder

1. **Lis `PLAN-ACTION.md` et `STATE.md`** en entier. S'ils n'existent pas, dis-le et propose de les
   créer à partir des gabarits — ne les invente pas à partir du code.
2. **Cherche ce qui est durable et absent.** Parcours `STATE.md`, et les derniers messages de commit :
   une décision prise, une contrainte découverte, une limite mesurée, un piège rencontré. Compare-les
   à la carte. Ce qui n'y figure pas est une **promotion à faire**.
3. **Promeus.** Ajoute les éléments manquants à la bonne section de `PLAN-ACTION.md` — décision avec
   son motif, contrainte avec ce qu'elle impose. Une décision sans motif se rouvre tous les trois
   mois.
4. **Compte la dette.** Les cases non cochées de la section « À faire ». Pour chacune : encore
   d'actualité, ou devenue sans objet ? Une ligne sans objet se retire, elle ne se traîne pas.
5. **Rends le tableau de bord.**

## Ce que ça produit

Un état en quatre points, dans cet ordre :

- **Ce que le projet fait** — trois lignes, reprises de la carte.
- **Décisions et contraintes** — le nombre, et celles ajoutées à ce passage.
- **Dette** — le nombre de cases non cochées, et lesquelles.
- **Ce qui manque encore à la carte** — ce que tu as trouvé mais pas su où ranger.

## Ce qu'il ne faut pas faire

- Réécrire la carte : on l'enrichit, on ne la refond pas. Ce qui y est écrit a été décidé.
- Cocher une case parce qu'elle est vieille. Une case se coche quand le travail est fait.
- Promouvoir le contenu de `STATE.md` en bloc : la carte n'est pas une archive des brouillons.
