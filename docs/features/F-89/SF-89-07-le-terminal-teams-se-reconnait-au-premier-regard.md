# SF-89-07 — Le terminal Teams se reconnaît au premier regard

> Cadrage de correctif du 2026-09-13. **Cadrage seul : la livraison attend le go du PO.**
> Maquette : `maquette-peau-terminal-teams.html` (publiée sur
> https://claude.ai/code/artifact/4f2a1e19-fff8-4617-b595-7b3f3d68692d).

## Constat du PO

> « J'avais aussi dit que le terminal Teams doit visuellement être très différent de l'autre. Par
> exemple via la couleur du background. »

## Écart entre la demande et la livraison

Le cadrage F-89 (§5.1) retenait, **sur décision du PO**, « un vrai basculement visuel » et « sa propre
peau ». La livraison de SF-89-03 l'a réduit : `atelier-terminal-teams.component.scss` et
`DESIGN_SYSTEM.md` §15 écrivent « **le basculement est typographique, pas chromatique** ; la surface
reste celle d'un terminal ». Seule la police change : le terminal Teams et le terminal de poste se
confondent. **C'est une réduction du besoin, pas un arbitrage du PO** ; ce correctif la répare.

## Comportement attendu

- Le terminal Teams a **son propre fond**, franchement distinct de celui des autres terminaux, **sombre**
  pour rester un terminal. Proposition par défaut : **« Prune » `#231A36`** (barre `#1B1429`, cartes
  `#2E2345`, filets `#43335F`, texte `#D9CFEA`, titres `#FFFFFF`). Alternatives présentées au PO :
  « Pétrole » `#0D2A30`, « Papier » `#F7F5F0` (clair). **Tranché le 2026-09-13 : « Prune »** — le PO a délégué le choix (« Choisis »), retenu sur la recommandation : distinct au premier regard, reste un terminal, ne rappelle pas la marque Microsoft.
- La barre dit **« Conversations Teams »** à côté du client, avec l'indicateur de liaison (§14).
- La **couleur d'identité du client** (§9) ne change pas ; les états (§10, §11, §12) gardent leurs
  palettes, vérifiées lisibles sur le nouveau fond (contraste AA au minimum).
- La **mosaïque** (F-83) peint une tuile Teams de la même peau.
- Titres, liens, tableaux du Markdown rendu : lisibles sur ce fond (s'appuie sur SF-30-14).

## Charte

`DESIGN_SYSTEM.md` : §15 amendé (« le basculement est **chromatique et typographique** »), et ajout des
jetons de la surface Teams (`--cg-terminal-teams-*`) — **ajout de palette explicitement demandé par le
PO**, limité au terminal Teams et à ses tuiles.

## Critères d'acceptation

1. Un terminal Teams et un terminal de poste ouverts côte à côte se distinguent sans lire le libellé.
2. Contraste AA de tous les textes et badges sur le nouveau fond (test automatisé sur les couleurs).
3. Une tuile Teams de la mosaïque porte la même peau.
4. Aucun autre terminal ne change d'apparence.

## Hors périmètre

Changer la mécanique du terminal Teams, ses blocs ou ses outils.
