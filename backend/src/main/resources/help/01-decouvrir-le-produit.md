# Découvrir Claude Portal

Claude Portal est une **passerelle** vers Claude. Elle donne accès au modèle depuis un poste où
l'accès direct est bloqué, elle garde l'historique des conversations, elle mesure la consommation et
elle facture. Elle n'est **pas** un moteur d'intelligence artificielle : elle relaie les demandes au
fournisseur et rend sa réponse.

## Les écrans

| Écran | À quoi il sert |
|---|---|
| **Chat** | Converser avec Claude, choisir le modèle, joindre des fichiers, retrouver ses conversations |
| **Forge** | Travailler sur un **projet** : parcourir et modifier ses fichiers, lancer des commandes, laisser l'assistant agir pas à pas |
| **Bibliothèque** | Déposer des documents, suivre leur traitement, les retrouver |
| **Q&A** | Poser une question dont la réponse est citée depuis vos propres documents |
| **Templates** | Enregistrer des consignes réutilisables et les rejouer |
| **Rapports d'usage** | Historique mensuel de consommation et coût estimé |
| **Facturation** | Abonnement, changement d'offre, recharges |
| **Réglages** | Préférences du compte, clé API personnelle, jeton d'accès à un dépôt de code |
| **Profil** | Nom affiché, mot de passe |

Le menu **Compte**, en haut à droite, ouvre Rapports d'usage, Facturation, Réglages et Profil.

## Deux façons de faire travailler l'assistant sur du code

1. **Un projet hébergé** — vous déposez une archive ou vous rattachez un dépôt Git. Tout se passe
   dans la passerelle, rien n'est installé chez vous.
2. **Le mode runner** — vous lancez un petit programme sur **votre** machine, et l'assistant
   travaille sur les fichiers qui y sont, sans qu'ils quittent le poste. C'est le mode décrit dans
   les pages suivantes.

## Ce que l'aide ne peut pas faire

Cette aide répond sur **l'usage du produit**. Elle ne voit ni vos projets, ni vos fichiers, ni vos
conversations : elle ne peut pas dire ce que contient un dossier, pourquoi une commande a échoué
chez vous, ni relire votre code. Pour cela, ouvrez la Forge et posez la question à l'assistant, qui
lui a accès au projet.
