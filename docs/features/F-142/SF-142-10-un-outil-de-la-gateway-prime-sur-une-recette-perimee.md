# Mini-spec — F-142 / SF-142-10 — Un outil de la gateway prime sur une recette périmée du poste

## Identifiant
`F-142 / SF-142-10` — feature parente `F-142`

## Objectif
Qu'une recette **périmée** déposée sur la machine du client ne fasse plus échouer un travail que la
gateway sait faire.

## Le défaut — constaté en production
> PO, 2026-09-24, après le déploiement de SF-142-09 : *« Je viens de regarder le résultat. La 2ᵉ image
> générée. Elle est exactement comme la première. Zéro différence. Les mêmes défauts. »*

Vérifié en production, dans cet ordre :

| Ce qu'on croyait | Ce que disent les faits |
|---|---|
| « le correctif n'a pas été déployé » | il l'est : `APP_DIAGRAMS_BASE_URL` est dans le pod, le paquet est republié en **version 15** |
| « l'outil échoue » | il n'a **jamais été appelé** — zéro trace en trois heures |

La cause est ailleurs, et elle est **structurelle** : les fichiers d'un paquet de gouvernance sont
**déposés sur la machine du client**, et une republication **ne les réécrit pas** — c'est une décision
explicite du produit (D5 : on ne modifie rien chez le client dans son dos). Le poste garde donc
l'ancien `pptx.md`, qui enseigne `npm install -g @mermaid-js/mermaid-cli`.

Et l'ordre de la consigne système achève le tableau : les **guides d'outils** sont injectés **avant**
le catalogue des skills. La recette du poste, lue ensuite, l'emporte.

**Ce n'est pas propre aux diagrammes** : tout outil de la gateway peut être contredit par une recette
locale plus ancienne. C'est cette classe de défaut qu'on ferme.

## Ce qu'on ne fait pas
**Réécrire les fichiers du poste automatiquement.** La décision D5 tient : on ne touche pas à la
machine d'un client sans geste de sa part. On agit sur ce que **nous** maîtrisons — la consigne système,
toujours à jour, servie à chaque tour.

## Comportement attendu
1. Une **règle d'arbitrage** est ajoutée à la consigne, **après** le catalogue des skills :
   quand une recette locale suppose d'**installer** un moteur alors qu'un **outil** fait la même chose,
   **l'outil prime**, et rien ne s'installe.
2. La règle **nomme les cas connus** : rendu de diagramme, construction de deck.
3. Elle n'apparaît **que si** un de ces outils est réellement ouvert pour le tour — sinon elle parlerait
   dans le vide.
4. Elle ne dit pas « ignore le skill » : le skill reste la référence pour **tout le reste** (structure
   du livrable, style, contenu). Seule la **fabrication** passe par l'outil.
5. **Préfixe stable** (F-134) : la règle est un texte **fixe**, sous une condition stable pour un poste
   donné — le cache de prompt n'est pas touché.

| Cas d'erreur | Comportement |
|---|---|
| Aucun outil de production ouvert | la règle n'est pas injectée |
| Skill absent du poste | rien ne change : la règle est sans objet, et inoffensive |

## Critères d'acceptation
- [x] La règle est présente dans la consigne quand un outil de production est ouvert.
- [x] Elle est placée **après** le catalogue des skills.
- [x] Elle est **absente** quand aucun de ces outils n'est ouvert.
- [x] Elle nomme explicitement : ne jamais installer un moteur de rendu ou `python-pptx`.
- [x] Le préfixe système reste **stable** pour un même poste (cache F-134 préservé).

## Hors scope
Le redépôt automatique des paquets (**écarté** : décision D5) · un mécanisme de péremption des skills
(la vue de gouvernance signale déjà « republié depuis », F-96) · la relecture des skills par la gateway.

## Technique
| Élément | Changement |
|---|---|
| `AtelierChatService` | la règle d'arbitrage, injectée **après** les skills, sous condition d'outil ouvert |
| `AtelierToolPrimacy` *(nouveau, texte)* | la règle elle-même, au même endroit que les autres doctrines |

Aucune table, aucune migration, aucune route.

## Plan de test — `AtelierChatServiceSystemPromptTest`, 53 verts
- [x] Poste portant encore l'ancienne recette **et** outil ouvert : la règle est présente, et son
      **indice de position** est **supérieur** à celui du catalogue des skills — l'ordre est figé par
      le test, pas seulement écrit dans un commentaire.
- [x] Sans outil de production ouvert : la règle est **absente**.
- [x] Elle nomme `render_diagram`, `python-pptx`, et « N'installe rien ».

## Préoccupations transversales
| Préoccupation | Cochée | Composants impactés |
|---|---|---|
| Auth / Principal | non | — |
| Contexte tenant | non | la règle ne dépend d'aucune donnée de compte |
| Plans / limites | non | aucun appel fournisseur |
| Navigation / routing | non | aucun écran |
