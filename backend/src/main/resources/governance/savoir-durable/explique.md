---
name: explique
description: Explique un morceau du projet — un fichier, une décision, un mécanisme — en partant de ce qui est réellement écrit, jamais de suppositions.
---

# /explique

Explique un morceau du projet à quelqu'un qui le découvre : un fichier, une fonction, une décision,
un mécanisme, un message d'erreur qu'on ne comprend pas.

## Comment procéder

1. **Lis avant de parler.** Ouvre les fichiers concernés, en entier s'ils sont courts. Une
   explication fondée sur le nom d'une fonction est une supposition déguisée.
2. **Pars du pourquoi.** Ce qu'un lecteur cherche n'est presque jamais « ce que fait cette ligne »,
   mais « pourquoi elle existe ». Cherche la contrainte qui l'a rendue nécessaire — un commentaire,
   un message de commit, une décision de `PLAN-ACTION.md`.
3. **Dis ce que ça remplace.** Un mécanisme se comprend contre son alternative : ce qui se passerait
   sans lui, et pourquoi cette alternative a été écartée.
4. **Nomme les limites.** Ce que le morceau expliqué ne fait pas, et ce qui casserait s'il changeait.
   C'est la partie qu'on découvre autrement en production.
5. **Termine par le fil à tirer.** Le fichier suivant à lire pour aller plus loin.

## Ce qu'il ne faut pas faire

- Paraphraser le code ligne à ligne : le lecteur l'a sous les yeux.
- Inventer une intention. Si le motif n'est écrit nulle part, dis-le : « rien n'explique ce choix
  dans le dépôt » est une information utile, et souvent le début d'une promotion vers la carte du
  projet.
- Conclure sans avoir cherché si la réponse était déjà écrite quelque part.

## Ce que ça produit

Une explication en prose, structurée par le raisonnement et non par la structure du fichier. Si elle
fait apparaître un élément durable absent de `PLAN-ACTION.md`, propose de l'y ajouter.
