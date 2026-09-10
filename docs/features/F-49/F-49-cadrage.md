# Cadrage — F-49 · Vue d'ensemble des postes

> Dérivé de `docs/features/CADRAGE-postes-et-gouvernance.md` (§6 et arbitrage n° 3, tranché par le
> PO le 2026-09-10). Ce document n'ouvre aucun arbitrage nouveau : il découpe.

---

## 1. Ce qui manque

F-48 a réuni les projets sous un **poste**. Le gain est net — un appairage par machine, un runner,
et ouvrir un projet de plus ne coûte plus rien — mais il a créé un angle mort : **il n'existe aucun
endroit d'où voir l'ensemble.** Les informations existent toutes, et elles sont toutes ailleurs :
la présence est dans le dialogue de mise en service d'un projet, l'interpréteur élu dans son détail,
les droits dans le journal, l'activité dans l'audit de chaque projet pris un par un. Pour savoir si
sa machine du bureau est encore connectée, l'utilisateur doit ouvrir un projet qui vit dessus.

F-49 rassemble : **tous les postes**, connectés ou non, depuis quand, quel système, quel interpréteur
élu, sous quels droits, quels projets vivent dessous, et ce qui a tourné en dernier sur chacun.

## 2. Ce que la décision déjà prise interdit

Arbitrage n° 3 du cadrage d'ensemble : **vue d'état rafraîchie, et non plusieurs terminaux vivants
en parallèle.** Un écran qui ouvrirait un flux SSE par poste multiplierait les tours facturés pour
un bénéfice de surveillance que l'état couvre déjà. Le terminal d'un projet reste **à un clic** —
mais c'est un clic, pas un abonnement.

Conséquence directe sur le découpage : la source de la vue est un **appel de lecture**, rejoué à
intervalle, jamais un canal.

## 3. Découpage

| SF | Titre | Portée |
|---|---|---|
| **SF-49-01** | La vue d'ensemble, côté gateway | Un appel de lecture qui agrège en une fois : les postes de l'utilisateur, leur état, leurs projets, et l'activité observée sur chacun |
| **SF-49-02** | L'écran des postes | La page `/postes`, ses cartes, son rafraîchissement, et le terminal d'un projet à un clic |

Le backend d'abord : le contrat de la vue décide de ce que l'écran peut montrer.

## 4. Ce que F-49 ne fait pas

- **Agir sur plusieurs postes à la fois** — hors périmètre, dit par `PRODUCT_SPEC.md`. Les gestes de
  machine (renommer, couper, révoquer) restent là où ils sont déjà, sur un poste à la fois.
- **Suivre un tour en direct** — c'est le terminal du projet, et il est à un clic.
- **Partager une vue entre comptes** — un poste appartient à un seul utilisateur (F-17 est V3).
