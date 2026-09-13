# SF-30-14 — Les titres du terminal sont lisibles

> Cadrage de correctif du 2026-09-13, constat du PO en production : *« dans le terminal, les titres
> s'affichent en noir »*. Cadrage validé par le PO ; livraison lancée le 2026-09-13.

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

---

## Identifiant

`F-30 / SF-30-14`

## Feature parente

`F-30` — Atelier, expérience terminal

## Statut

`in-review`

## Date de création

2026-09-13

## Branche Git

`feat/SF-30-14-titres-lisibles`

---

## Objectif

Rendre lisibles, sur le fond sombre de tous les terminaux, les titres et autres éléments du Markdown
rendu dans le commentaire de l'agent, en faisant enfin **atteindre** au HTML inséré les styles déjà
prévus par SF-30-12.

## Comportement attendu (détaillé)

### Cas nominal

Le commentaire de l'agent (historique et ligne vivante) est rendu par `innerHTML`. Ses éléments
reçoivent les styles du terminal :

| Élément | Rendu dans le terminal | Jeton |
|---|---|---|
| `h1`…`h6` | graisse 700, taille du texte, police du corps | `--cg-surface` |
| `strong` | graisse 700 | `--cg-surface` |
| lien `a` | orange clair de la charte | `--cg-orange-2` |
| code en ligne | pastille monospace | fond `--cg-navy-2`, texte hérité |
| bloc de code | apparence d'une sortie de commande | fond `--cg-navy-2` |
| citation | filet gauche, texte hérité | filet `--cg-navy-2`, texte `--cg-divider` |
| tableau | défile chez lui, cellules filetées | filet `--cg-navy-2`, texte hérité |

Mêmes règles dans les quatre usages du composant `AtelierTerminalComponent` : terminal de projet,
terminal de poste, terminal Teams (`[teamsTerminal]`), tuile de mosaïque (`[readOnly]`).

### Cas d'erreur

Pas d'appel réseau ni de donnée : les « cas d'erreur » sont les régressions à éviter.

| Situation | Comportement attendu | Code HTTP |
|---|---|---|
| Markdown rendu **hors** terminal (chat, bloc à copier, aide) | Titres toujours en `--cg-text-primary` (règle globale inchangée) | — |
| Titre écrit par l'agent dans la ligne vivante (tour en cours) | Même rendu que dans l'historique | — |
| Citation dans le terminal | Ne pas appliquer `--cg-text-secondary` (contraste ~2,7:1 sur `--cg-primary`) : le texte hérite de `--cg-divider` | — |

## Critères d'acceptation (vérifiables)

- [ ] CA1 — Une réponse contenant `#`, `##`, `###`, `####`, un lien, un tableau, une citation et du
  code en ligne est lisible dans le terminal de projet, de poste, Teams et dans une tuile de
  mosaïque (couleurs calculées vérifiées par test dans les quatre usages).
- [ ] CA2 — Le chat, l'aide et les pages claires rendent leurs titres comme avant (couleur calculée
  d'un `h2` hors terminal = `--cg-text-primary`).
- [ ] CA3 — Test de composant qui vérifie la couleur calculée d'un `h2` rendu par `innerHTML` dans
  le terminal ; il échoue avant le correctif.
- [ ] CA4 — Hiérarchie par la graisse, non par la taille (SF-30-12 inchangée) : `font-size` d'un `h1`
  = celle du bloc.
- [ ] CA5 — `ng build` passe le budget de style par composant (12 ko).

## Périmètre

### Hors scope (explicite)

- Changer la taille des titres dans le terminal, ou le rendu Markdown lui-même (pipe `markdown`).
- Toute couleur nouvelle : seuls des jetons de §2 sont employés.
- La peau « Prune » du terminal Teams (SF-89-07, livrée à part).

## Valeurs initiales

Non applicable — aucune entité.

## Contraintes de validation

Non applicable — aucun champ saisi.

## Technique

### Endpoint(s)

Aucun.

### Tables impactées

Aucune.

### Migration Liquibase

- [x] Non applicable

### Composants Angular

- `AtelierTerminalComponent` — les règles descendantes de `.terminal-agent` quittent
  `atelier-terminal.component.scss` pour une quatrième feuille du composant,
  `atelier-terminal-markdown.component.scss`, sous `.terminal-agent ::ng-deep`, **bornées** au bloc
  `.terminal-agent` du composant (le sélecteur produit reste préfixé par l'attribut
  d'encapsulation du bloc : aucune fuite hors du terminal). La règle globale des titres de
  `styles.scss` n'est pas touchée.

### Préoccupations transversales

- [ ] Auth / Principal — non concerné
- [ ] Contexte tenant — non concerné
- [ ] Plans / limites — non concerné
- [ ] Navigation / routing — non concerné

## Plan de test

### Tests unitaires (composant, Karma, styles globaux chargés)

- [ ] `terminal-markdown-lisible.spec.ts` — `h1`…`h4` d'un commentaire rendu : couleur calculée
  `--cg-surface` (`rgb(255, 255, 255)`), et non `--cg-text-primary` — **échoue avant**.
- [ ] Lien : `--cg-orange-2` ; code en ligne : fond `--cg-navy-2` ; cellule de tableau : filet
  `--cg-navy-2` ; citation : texte `--cg-divider`.
- [ ] Même vérification pour la ligne vivante (tour en cours).
- [ ] Même vérification en terminal de poste (`hostName` + cible `RUNNER`), Teams
  (`teamsTerminal`) et tuile (`readOnly`).
- [ ] Taille d'un `h1` = taille du bloc (SF-30-12).
- [ ] Non-régression : un `h2` inséré par `innerHTML` hors du terminal garde `--cg-text-primary`.

### Tests d'intégration

Non applicable — aucun endpoint.

### Isolation utilisateur

- [x] Non applicable — raison : correctif de feuille de style, aucun accès aux données.

## Dépendances

### Subfeatures bloquantes

- SF-30-12 — statut : done.

### Questions ouvertes impactées

- Aucune.

## Notes et décisions

- **D1 — `::ng-deep` borné plutôt que feuille globale.** Le cadrage admet les deux. Le sélecteur
  `.terminal-agent[_ngcontent-…] h2` reste attaché au composant (pas de classe globale à maintenir
  à part). Précédent dans le code : `legal-page.component.scss`.
- **D1 bis — Une quatrième feuille (constat en dev).** Laissées dans `atelier-terminal.component.scss`,
  les règles sous `::ng-deep` portaient la feuille à 12,10 ko : `ng build` échouait sur le budget
  de 12 ko (11,91 ko avant). Même réponse qu'en F-83 et F-89 : une feuille qui nomme ce qu'elle
  porte. La feuille principale retombe à 10,56 ko.
- **D2 — Citation : texte hérité.** La règle SF-30-12 posait `--cg-text-secondary` ; elle n'a jamais
  atteint le DOM. L'activer telle quelle rendrait les citations *moins* lisibles (~2,7:1 sur
  `--cg-primary`). Le filet gauche suffit à marquer la citation.
