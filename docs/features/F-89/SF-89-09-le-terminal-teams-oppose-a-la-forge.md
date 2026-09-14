# Mini-spec — F-89 / SF-89-09 : Le terminal Teams, opposé à celui de la Forge (couleurs seulement)

> Cadrage validé par le PO (2026-09-13, corrigé 2026-09-14). Ce document est la mini-spec de
> livraison, dérivée du cadrage `SF-89-09-le-terminal-teams-oppose-a-la-forge.md` (renommé ici en
> mini-spec) et de la maquette `docs/features/F-89/maquette-peau-terminal-teams.html`.
> **Le cadrage n'est pas rediscuté : seule la livraison est décrite.**

---

## Identifiant

`F-89 / SF-89-09`

## Feature parente

`F-89` — Le volet Teams — le terminal Teams (**Terminée** ; SF-89-09 est un correctif de peau, comme SF-89-07)

## Statut

`in-progress`

## Date de création

2026-09-14

## Branche Git

`feat/SF-89-09-terminal-teams-papier`

---

## Objectif

Remplacer la peau sombre « Prune » du terminal Teams (SF-89-07) par la peau claire « Papier »
(fond `#FAF8F3`), opposée à la Forge par la **seule couleur** — police, taille et disposition
strictement inchangées.

---

## Comportement attendu

### Cas nominal

- Le terminal Teams (onglet Conversations de la Vigie) et une tuile Teams de la mosaïque portent la
  peau **« Papier »** : fond clair `#FAF8F3`, bandeau lavande `#EFEAF9` (texte `#2B2250`, filet
  `#DCD3F0`), texte `#2A2F3A` (secondaire `#5E6472`, titres `#1F2430`), accent message `#9A4A12` et
  étapes/liens `#4B3F8F`, cartes `#FFFFFF` filet `#E6E0D2`, couleurs d'état de la charte approfondies
  pour un fond clair.
- La Forge (terminal de projet / de poste) est **inchangée** : fond sombre `--cg-primary`.
- Police (`font-family`), taille (`font-size`) et interligne (`line-height`) du terminal Teams sont
  **identiques** à ceux de la Forge : seules les couleurs diffèrent.
- Les jetons `--cg-terminal-teams-*` sont **remplacés** (valeurs Papier), pas empilés.

### Cas d'erreur / limites

| Situation | Comportement attendu |
|-----------|---------------------|
| Un texte/contrôle tomberait sous l'AA sur la surface claire | Corrigé : jeton Papier qui tient ≥ 4,5:1 (≥ 3:1 grands titres, icônes) |
| Le rouge d'échec `--cg-error` (`#D32F2F`) tombe à 4,2:1 sur le bandeau lavande | Jeton `--cg-terminal-teams-error` `#C62828` (rouge charte approfondi) |
| Le vert d'ajout `--cg-success` (`#27AE60`) tombe à ~2,9:1 sur fond clair | Jeton `--cg-terminal-teams-add` `#166F38` (vert charte approfondi) |
| L'or/l'orange de la charte (liens, gestes) illisibles sur fond clair | Repris par les accents Papier : message `#9A4A12`, étapes/liens `#4B3F8F` |
| Un autre terminal (projet, poste) changerait | Interdit : la peau est bornée à `.terminal-view--teams` et `.mosaique__tile--teams` (tests) |

---

## Critères d'acceptation

- [ ] Côte à côte, un terminal de poste et un terminal Teams se distinguent **sans lire aucun libellé**, par la seule couleur (fond sombre vs clair).
- [ ] Le terminal Teams et la Forge ont les **mêmes** `font-family`, `font-size`, `line-height` calculés (test).
- [ ] Balayage AA automatisé : chaque texte et chaque contrôle du terminal Teams et d'une tuile Teams tient l'AA sur son fond calculé.
- [ ] Les jetons de texte/accent/état Papier tiennent ≥ 4,5:1 sur `bg`, `bandeau` et `carte` (test par les couleurs calculées).
- [ ] La tuile Teams de la mosaïque porte la même peau Papier (fond `#FAF8F3`).
- [ ] Aucune régression : un terminal de projet garde `--cg-primary` (test).
- [ ] `DESIGN_SYSTEM.md` §15 réécrit : « le terminal Teams est un document clair, opposé au terminal sombre de la Forge ».
- [ ] `ng build` vert (budgets de style respectés).

---

## Plan de test minimal

- **`terminal-teams-peau.spec.ts`** (réécrit) :
  - Surface Papier par valeurs calculées : fond `#FAF8F3`, bandeau `#EFEAF9`, carte `#FFFFFF`.
  - Jetons de texte/accent/état ≥ 4,5:1 sur les trois fonds (bg, bandeau, carte).
  - **Balayage du DOM** d'un terminal Teams chargé (tout ce qu'il sait afficher) : chaque texte ≥ AA.
  - **Nouveau test** : `font-family`, `font-size`, `line-height` du `.terminal-scrollback` Teams **égaux** à ceux d'un terminal de projet.
  - Non-régression : terminal de projet garde `--cg-primary` ; tuile en lecture seule porte `#FAF8F3`.
- **`terminal-markdown-lisible.spec.ts`** : inchangé pour la Forge ; le lien Markdown sous Teams suit l'accent étapes/liens (vérifié par le balayage AA de la peau).
- **`mosaique.component.spec.ts`** : la tuile Teams porte le fond Papier (déjà par jetons — non-régression).
- Isolation `user_id` / `host_id` : **sans objet** (aucun accès données, changement purement CSS).

---

## Tables / endpoints / composants impactés

- **Aucune table, aucun endpoint, aucune migration** (changement purement front / charte).
- `frontend/src/styles.scss` — valeurs des jetons `--cg-terminal-teams-*` (remplacées) + jetons Papier ajoutés (`-bar-ink`, `-bar-rule`, `-message`, `-step`, `-add`).
- `frontend/src/app/atelier/terminal/atelier-terminal-teams.component.scss` — mappe les accents/états de la Forge (or, orange, vert, rouge) sur les accents/états Papier, sous `.terminal-view--teams`.
- `frontend/src/app/atelier/terminal/atelier-terminal-markdown.component.scss` — lien Markdown sous Teams → accent étapes/liens.
- `frontend/src/app/mosaique/mosaique.component.scss` — tuile Teams (jetons, non-régression).
- `frontend/src/app/atelier/terminal/terminal-teams-peau.spec.ts` — test réécrit.
- `docs/DESIGN_SYSTEM.md` §15 — réécrit (surface claire « Papier »).

### Préoccupations transversales

- **Auth / Principal** : non concerné.
- **Contexte tenant (`user_id`/`host_id`)** : non concerné (aucun accès données).
- **Plans / limites** : non concerné.
- **Navigation / routing** : non concerné.
- **Charte / DESIGN_SYSTEM** : concerné — §15 réécrit ; aucune couleur hors charte (les jetons Papier sont ajoutés au design system et bornés au terminal Teams).

---

## Hors périmètre

- Changer la police, la taille, la disposition, la mécanique, les outils ou les blocs du terminal Teams.
- Changer quoi que ce soit au terminal de la Forge (projet, poste).
- Changer les pastilles de statut de la charte (§5, §9, §12) : elles gardent leur palette ; seule la surface qui les porte change.
- Toute évolution backend (registre `teamsTerminal`, droits) : déjà livrée par SF-89-07/08.
