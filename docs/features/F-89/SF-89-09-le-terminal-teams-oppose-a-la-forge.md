# SF-89-09 — Le terminal Teams, opposé à celui de la Forge

> Cadrage du 2026-09-13. **Cadrage seul : la livraison attend le go du PO.**
> Maquette : `maquette-peau-terminal-teams.html` (republiée sur
> https://claude.ai/code/artifact/4f2a1e19-fff8-4617-b595-7b3f3d68692d).
> Remplace la peau « Prune » livrée par SF-89-07 (PR #573).

## Constat du PO, en production

> « Je trouve que le terminal Teams et celui de la Forge se ressemblent toujours. Choisis des thèmes de
> couleurs vraiment opposés. »

**Pourquoi Prune n'a pas suffi** : un violet sombre reste un fond sombre, avec la même densité et la
même police de code que le terminal de la Forge. À distance, les deux écrans ont la même silhouette.

## Décision : opposés par la couleur, rien d'autre

> **Correction du PO (2026-09-14)** : « quand j'ai demandé une différence entre les deux terminaux, je
> parle juste de la couleur, c'est tout. La police, la taille des caractères me vont. »

**Police, taille, disposition des messages, blocs et densité restent exactement ceux d'aujourd'hui.**
Seules les couleurs du terminal Teams changent, à l'opposé de la Forge :

| | Forge (projet, poste) — inchangé | Vigie (terminal Teams) — « Papier » |
|---|---|---|
| Fond | sombre `#141D33` | **clair `#FAF8F3`** |
| Bandeau | `#0E1628` | **lavande pâle `#EFEAF9`**, texte `#2B2250`, filet `#DCD3F0` |
| Texte | `#C9D2E3` | `#2A2F3A` ; secondaire `#5E6472` ; titres `#1F2430` |
| Accents | message `#E6A96B`, étapes `#8FB3DB` | message `#9A4A12`, étapes et liens `#4B3F8F` |
| Cartes et blocs | — | fond `#FFFFFF`, filet `#E6E0D2` |
| Couleurs d'état | adaptées au sombre | celles de la charte §5, §10, §12, conçues pour un fond clair |

**Contraste** : toutes les paires texte/fond au moins AA (4,5:1 ; 3:1 pour les grands titres et les
contrôles) ; vérifié par test automatisé.

## Comportement attendu

- Le terminal Teams (onglet Conversations de la Vigie) et une tuile Teams de la mosaïque portent la peau
  « Papier » ; **aucun** autre terminal ne change.
- Les blocs Teams (carte de réunion, moment avec capture, liste d'engagements), le bloc « Page publiée »,
  le bloc « Courriel envoyé », la ligne vivante, le bouton Préciser, les invites d'autorisation, les menus
  et boutons Material sont lisibles sur le fond clair.
- Le Markdown rendu (titres, liens, tableaux, citations, code) suit la peau (s'appuie sur SF-30-14).
- Les jetons `--cg-terminal-teams-*` de SF-89-07 sont **remplacés** (pas empilés) ; `DESIGN_SYSTEM.md`
  §15 réécrit : « le terminal Teams est un **document clair**, opposé au terminal sombre de la Forge ».

## Critères d'acceptation

1. Côte à côte, un terminal de poste et un terminal Teams se distinguent **sans lire aucun libellé**,
   par la seule couleur ; police, taille et disposition identiques (test : mêmes valeurs calculées de
   `font-family`, `font-size`, `line-height` dans les deux).
2. Balayage AA automatisé sur tous les textes et contrôles du terminal Teams et d'une tuile Teams.
3. Aucune régression visuelle sur les terminaux de projet et de poste, ni sur les écrans clairs.
4. Budgets de style respectés (`ng build` vert).

## Dépendances

- SF-30-14 (titres lisibles) : fait.
- SF-30-15 (boutons lisibles dans les terminaux sombres) : en livraison ; SF-89-09 part après son merge.

## Hors périmètre

Changer la police, la taille, la disposition, la mécanique, les outils ou les blocs du terminal Teams ; changer le terminal de la Forge.
