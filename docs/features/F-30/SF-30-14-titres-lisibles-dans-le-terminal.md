# SF-30-14 — Les titres du terminal sont lisibles

> Cadrage de correctif du 2026-09-13, constat du PO en production : *« dans le terminal, les titres
> s'affichent en noir »*. **Cadrage seul : la livraison attend le go du PO.**

## Constat

Dans le terminal (fond sombre), les titres Markdown des réponses de l'agent (`#`, `##`, `###`,
`####`) s'affichent en couleur de texte foncée : illisibles.

## Cause, vérifiée dans le code

- `frontend/src/app/atelier/terminal/atelier-terminal.component.html` rend la réponse par
  `[innerHTML]="message.content | markdown"` (et `live.text | markdown` pour la ligne vivante).
- `atelier-terminal.component.scss` prévoit bien les titres (`.terminal-agent h1…h6 { color:
  var(--cg-surface) }`), **mais** le composant est en encapsulation émulée : les éléments insérés par
  `innerHTML` ne portent pas l'attribut `_ngcontent-…`, **la règle ne les atteint pas**.
- La règle globale `frontend/src/styles.scss` (`h1, h2, h3, h4 { color: var(--cg-text-primary) }`,
  couleur de texte des écrans clairs) s'applique donc. `h5` et `h6` ne sont pas touchés par la règle
  globale : c'est pourquoi le défaut ne se voit que sur les quatre premiers niveaux.
- **Même mécanisme, à vérifier au passage** : tout élément du Markdown rendu qui reçoit un style
  global (liens, tableaux, citations, code en ligne) dans le terminal, la mosaïque (F-83) et le
  terminal Teams (F-89).

## Comportement attendu

- Dans tous les terminaux (projet, poste, Teams, tuiles de la mosaïque), titres, liens, listes,
  tableaux, citations et code en ligne du Markdown rendu sont lisibles sur le fond du terminal, avec
  les couleurs de la charte (`DESIGN_SYSTEM.md` §13), la hiérarchie portée par la graisse et non par
  la taille (règle SF-30-12 inchangée).
- Aucun effet sur le chat clair, l'aide ni les autres usages du pipe `markdown`.

## Correctif retenu

Porter les styles du contenu Markdown du terminal **là où ils atteignent le HTML inséré** : une classe
dédiée au contenu rendu du terminal, déclarée dans une feuille **globale** et limitée à cette classe
(ou `:host ::ng-deep` borné à `.terminal-agent`), plutôt que d'élargir la règle globale des titres.

## Critères d'acceptation

1. Une réponse contenant `#`, `##`, `###`, `####`, un lien, un tableau, une citation et du code en
   ligne est lisible dans le terminal de projet, de poste, Teams et dans une tuile de mosaïque.
2. Le chat, l'aide et les pages claires rendent leurs titres comme avant.
3. Test de composant qui vérifie la couleur calculée d'un `h2` rendu par `innerHTML` dans le terminal
   (échoue avant le correctif).

## Hors périmètre

Changer la taille des titres dans le terminal, ou le rendu Markdown lui-même.
