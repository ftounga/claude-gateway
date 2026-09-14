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

## Décision : opposés en tout, pas seulement en teinte

| | Forge (projet, poste) | Vigie (terminal Teams) |
|---|---|---|
| Fond | sombre `#141D33` (inchangé) | **clair « Papier » `#FAF8F3`** |
| Bandeau | `#0E1628` | **lavande pâle `#EFEAF9`**, texte `#2B2250`, filet `#DCD3F0` |
| Encre | `#C9D2E3` | `#1F2430` ; secondaire `#5E6472` ; accent violet encre `#4B3F8F` |
| Cartes | — | `#FFFFFF`, filet `#E6E0D2`, rayon 10 px |
| Écriture | police de code partout | **prose (Inter)**, titres de cartes et sections en **serif** (Source Serif 4, repli Georgia), code seulement pour heures et identifiants |
| Message de l'utilisateur | ligne d'invite « › » | **bulle alignée à droite** (`#4B3F8F`, texte blanc) |
| Étapes de l'agent | lignes de commande | puces discrètes (« transcription lue à l'écran · 41 min ») |
| Couleurs d'état | adaptées au sombre | **celles de la charte §5, §10, §12**, conçues pour un fond clair |

**Contraste** : toutes les paires texte/fond visées au moins AA (4,5:1 ; 3:1 pour les grands titres et
les contrôles) ; vérifié par test automatisé.

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

1. Côte à côte, un terminal de poste et un terminal Teams se distinguent **sans lire aucun libellé**
   (fond, police, forme des messages).
2. Balayage AA automatisé sur tous les textes et contrôles du terminal Teams et d'une tuile Teams.
3. Aucune régression visuelle sur les terminaux de projet et de poste, ni sur les écrans clairs.
4. Budgets de style respectés (`ng build` vert).

## Dépendances

- SF-30-14 (titres lisibles) : fait.
- SF-30-15 (boutons lisibles dans les terminaux sombres) : en livraison ; SF-89-09 part après son merge.

## Hors périmètre

Changer la mécanique, les outils ou les blocs du terminal Teams ; changer le terminal de la Forge.
