# Mini-spec — F-155 / SF-155-02 — Les suggestions d'usage, avec gain calculé et seuil d'impact

## Identifiant
`F-155 / SF-155-02` — feature parente `F-155` — dépend de **SF-155-01** (le relevé)

## Objectif
Dire **ce qui aurait mieux valu**, sur les trois axes — **coût**, **temps**, **raisonnement** — et
**seulement** quand le gain dépasse le seuil.

## La règle qui commande
> PO : *« Je ne veux pas des trucs d'augmentation de 2-3 %. Je vise 10, 20 % minimum. »*

Elle est **structurelle**, pas une consigne polie :
- toute suggestion **cite la mesure** de la session d'où elle sort ; sans mesure, elle n'existe pas ;
- le gain est **calculé** à partir de cette mesure et de la **grille de tarifs réelle**, jamais
  estimé au jugé ;
- sous le seuil, la suggestion est **écartée**, et le bilan **dit combien** il en a écartées plutôt
  que de les diluer pour faire nombre ;
- **« rien à signaler » est une conclusion valide.** Une session bien menée doit pouvoir s'entendre
  dire qu'elle l'était, sinon le bilan devient un bruit qu'on cesse de lire.

## La décision de conception : des détecteurs, pas un modèle
Un appel modèle produirait des conseils **plausibles** et **invérifiables** — exactement ce que le
seuil interdit. Chaque suggestion naît donc d'un **détecteur déterministe** qui lit le relevé et
**calcule** son gain. Conséquence pratique : **tout est testable sans dépenser un jeton**, et deux
bilans de la même session disent la même chose.

## Les détecteurs de départ

| Axe | Détecteur | Le gain calculé |
|---|---|---|
| **Coût** | **Cache froid** — la part du cache est basse alors que la session a plusieurs tours | ce que les jetons d'entrée auraient coûté **lus en cache** (grille réelle du modèle), rapporté au coût de la session |
| **Coût** | **Tour hors norme** — un seul tour pèse une part démesurée du coût | ce que la session économiserait si ce tour pesait comme la moyenne des autres |
| **Temps** | **Outil dominant** — un outil concentre l'essentiel de la durée | la part de temps qu'il concentre |
| **Raisonnement** | **Échecs répétés** — un même outil échoue plusieurs fois | la part d'appels perdus sur cet outil |

La liste est **ouverte** : un détecteur s'ajoute sans toucher aux autres, et chacun est vérifiable
seul.

## Comportement attendu
1. Chaque suggestion porte : son **axe**, ce qu'il faut faire **en une phrase**, la **mesure citée**,
   et le **gain calculé** (pourcentage, et euros quand l'axe est le coût).
2. Les suggestions sont rendues **du plus fort gain au plus faible**.
3. Celles **sous le seuil** ne sont pas rendues ; leur **nombre** l'est.
4. Un relevé **vide** ou une session **sans matière** ne produit **aucune** suggestion, et ce n'est
   pas un échec.
5. Aucun détecteur ne **divise par zéro**, ne s'applique à un échantillon trop mince, ni ne rend un
   gain supérieur à 100 %.

| Cas d'erreur | Comportement |
|---|---|
| Session d'un seul tour | les détecteurs qui exigent une accumulation **ne s'appliquent pas** — une anecdote n'est pas un motif |
| Coût de session nul | aucun gain en pourcentage n'est calculable → aucune suggestion de coût |
| Modèle absent de la grille | repli documenté (`fallbackPricing`), jamais d'échec |

## Critères d'acceptation
- [ ] Chaque suggestion cite **une mesure** et un **gain calculé**, jamais un adjectif.
- [ ] Une suggestion sous **10 %** est **écartée**, et **comptée**.
- [ ] Le seuil est **configurable**, avec 10 % pour défaut — le chiffre du PO.
- [ ] Une session propre rend **zéro suggestion** et le dit.
- [ ] Le gain du cache froid utilise la **grille réelle** du modèle du tour, pas un tarif moyen.
- [ ] Les détecteurs qui exigent une accumulation **ne se déclenchent pas** sous leur minimum.
- [ ] **Aucun appel fournisseur** : le bilan des suggestions ne coûte rien.

## Hors scope
Le **déclenchement** (**SF-155-03**) · l'**écran** et l'artefact (**SF-155-04**) · le renvoi vers
F-156 (**SF-155-05**) · les optimisations de **l'application** (c'est **F-156**) · toute suggestion
issue d'un modèle.

## Technique
| Élément | Changement |
|---|---|
| `SessionSuggestion` (record) | axe, énoncé, mesure citée, gain (%, euros) |
| `SessionSuggestionService` | les détecteurs, le tri, le seuil, le compte des écartées |
| `SessionBilanProperties` | seuil d'impact (défaut **10 %**) et minimums des détecteurs |

**Aucune migration. Aucun appel fournisseur.**

## Plan de test
- [ ] Cache froid : gain calculé sur la grille réelle ; au-dessus du seuil → rendu, en dessous → écarté et compté.
- [ ] Tour hors norme, outil dominant, échecs répétés : chacun seul, chacun avec sa mesure.
- [ ] Tri par gain décroissant.
- [ ] Session d'un seul tour → les détecteurs d'accumulation se taisent.
- [ ] Coût nul, relevé vide → aucune suggestion, aucune exception.
- [ ] Session propre → zéro suggestion, zéro écartée.
- [ ] Le seuil configuré est **respecté** (test avec un seuil différent du défaut).

## Préoccupations transversales
| Préoccupation | Cochée | Composants impactés |
|---|---|---|
| Auth / Principal | non | aucun endpoint ; le service reçoit un relevé déjà produit sous isolation (SF-155-01) |
| **Contexte tenant** | **oui** | `SessionSuggestionService` ne fait **aucune** lecture en base : il ne voit que le `SessionLedger` que `SessionLedgerService` a produit **après** `requireOwned`. Aucun identifiant n'entre ni ne sort. |
| Plans / limites | non | **aucun appel fournisseur** — le bilan des suggestions ne consomme rien |
| Navigation / routing | non | aucune route |
