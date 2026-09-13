# SF-89-07 — Le terminal Teams se reconnaît au premier regard

> Cadrage de correctif du 2026-09-13. Cadrage validé par le PO (« Prune » tranché) ; livraison lancée
> le 2026-09-13.
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

---

## Identifiant

`F-89 / SF-89-07`

## Feature parente

`F-89` — Le volet Teams — le terminal Teams

## Statut

`done` — mergée le 2026-09-14 (PR #573)

## Date de création

2026-09-13

## Branche Git

`feat/SF-89-07-peau-terminal-teams`

---

## Objectif

Donner au terminal Teams — ouvert ou en tuile de mosaïque — sa propre surface sombre « Prune », sous
des jetons `--cg-terminal-teams-*`, avec « Conversations Teams » dans la barre, sans qu'aucun autre
terminal ne change et avec un contraste AA vérifié par test.

## Comportement attendu (détaillé)

### Cas nominal — le terminal Teams ouvert

| Élément | Terminal de projet / poste (inchangé) | Terminal Teams |
|---|---|---|
| Surface du flux | `--cg-primary` | `--cg-terminal-teams-bg` `#231A36` |
| Barre | fond de la surface, filet `--cg-navy-2` | `--cg-terminal-teams-bar` `#1B1429`, filet `--cg-terminal-teams-rule` |
| Filets internes (bandeaux, saisie, citations, tableaux) | `--cg-navy-2` | `--cg-terminal-teams-rule` `#43335F` |
| Code en ligne, bloc de code, sous-tâche | fond `--cg-navy-2` | fond `--cg-terminal-teams-bar` |
| Texte du flux | `--cg-divider` | `--cg-terminal-teams-text` `#D9CFEA` |
| Titres (Markdown, carte), demande | `--cg-surface` | `--cg-terminal-teams-title` `#FFFFFF` |
| Texte secondaire (aide, coûts, ligne vivante, sources) | `--cg-text-secondary` | `--cg-terminal-teams-muted` `#B5A6CF` |
| Carte de réunion (§15) | `--cg-surface` (blanc) | `--cg-terminal-teams-card` `#2E2345`, filet `--cg-terminal-teams-rule` |
| Ligne de diff retirée, commande en échec (texte) | `--cg-error` | `--cg-terminal-teams-error` (rouge éclairci pour fond sombre) |
| Libellé du fil d'Ariane (projet) | nom du projet | **« Conversations Teams »** (lien inchangé) |
| Police du flux | `--cg-font-mono` | `--cg-font-body` (SF-89-03, inchangé) |

Ce qui **ne change pas** : couleur d'identité du client (§9, pastille et filet), pastilles de
mission (§10), signe de vie (§11, encre de la barre), attente (§12, ambre), indicateur de liaison
(§14), accents d'or et orange de la charte (liens, invite, chronomètres), la mécanique du terminal.

### Cas nominal — la tuile de mosaïque

- `GET /api/terminals/live` expose, par terminal, `teamsTerminal` (booléen, lu sur le projet de
  l'utilisateur, `false` si le projet n'est pas résolu).
- La mosaïque passe `[teamsTerminal]` au terminal en lecture seule de la tuile : la tuile porte la
  même surface « Prune » ; son en-tête d'une ligne prend `--cg-terminal-teams-bar`, le nom du projet
  `--cg-terminal-teams-title` et le reste `--cg-terminal-teams-muted`. Le filet gauche garde la
  couleur du client (§9), l'anneau d'attente reste ambre (§12).

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|---|---|---|
| Backend antérieur : `teamsTerminal` absent de l'entrée du registre | La tuile garde la peau d'un terminal ordinaire (`#141D33`) ; rien ne casse | — |
| Projet du registre non résolu (supprimé entre-temps, ou autre utilisateur) | `teamsTerminal: false` ; aucun nom ni attribut d'un projet tiers exposé | 200 |
| Terminal de projet, de poste, ou tuile non Teams | Couleurs calculées strictement identiques à avant (fond `--cg-primary` / `#141D33`, carte non concernée) | — |
| Pastille §5 posée dans la barre Teams (moteur, liaison « navigateur non détecté », « Teams a changé ») | Garde sa paire de couleurs §5 ; son fond se détache de la barre (contraste non textuel ≥ 3:1) | — |
| Page publiée (§18) dans le terminal Teams | Inchangée : document sur `--cg-surface` | — |

## Critères d'acceptation (vérifiables)

- [ ] CA1 — Terminal Teams : fond calculé `rgb(35, 26, 54)` (`#231A36`), barre `rgb(27, 20, 41)` ;
  le terminal de projet garde `--cg-primary`, la tuile ordinaire `#141D33` (distincts au premier regard).
- [ ] CA2 — Test automatisé de contraste : dans un terminal Teams rendu avec fil, demande, commande,
  ligne vivante, Markdown (titres, lien, code, citation, tableau), carte de réunion complète
  (mention, sources, moments, pied), coût, aide et bandeau « Option Teams non active », **chaque
  élément porteur de texte** a un contraste ≥ 4,5:1 contre son fond effectif calculé ; les jetons
  texte / titre / muted / erreur sont vérifiés ≥ 4,5:1 sur fond, barre et carte ; les fonds des
  pastilles de la barre se détachent de la barre (≥ 3:1).
- [ ] CA3 — Tuile Teams de la mosaïque : fond `#231A36`, en-tête `#1B1429` ; une tuile non Teams
  garde `#141D33`.
- [ ] CA4 — Barre du terminal Teams : le fil d'Ariane dit « Conversations Teams » ; le lien du
  niveau projet reste `/atelier/{id}` ; un terminal de projet garde son nom.
- [ ] CA5 — Aucun autre terminal ne change : tests existants (`terminal-markdown-lisible`,
  `terminal-lecture-seule`, `mosaique`) verts sans modification de leurs attentes non Teams.
- [ ] CA6 — Backend : `teamsTerminal` vrai pour le terminal Teams, faux pour un projet ordinaire,
  dans `GET /api/terminals/live` ; filtrage `user_id` inchangé.
- [ ] CA7 — `DESIGN_SYSTEM.md` §15 amendé (« chromatique et typographique ») et jetons
  `--cg-terminal-teams-*` déclarés ; commentaire de `atelier-terminal-teams.component.scss` amendé.
- [ ] CA8 — `ng build` passe les budgets de style par composant.

## Périmètre

### Hors scope (explicite)

- Changer la mécanique du terminal Teams, ses blocs ou ses outils.
- Les paires de couleurs §5 elles-mêmes (pastilles « En attente », « neutre »…) : non retouchées.
- Le libellé de la tuile de mosaïque (nom du projet inchangé).
- Le bloc « Page publiée » (§18) : inchangé.
- Tout autre terminal, écran ou composant partagé.

## Valeurs initiales

Non applicable — aucune entité créée. Champ de réponse `teamsTerminal` : `false` par défaut.

## Contraintes de validation

Non applicable — aucun champ saisi.

## Technique

### Endpoint(s)

- `GET /api/terminals/live` (et les réponses identiques de prise de place / battement) : ajout du
  champ `teamsTerminal` à chaque entrée de `terminals`. Additif, rétrocompatible.

### Tables impactées

Aucune (lecture de `workspaces.teams_terminal`, déjà existant). Pas de migration.

### Migration Liquibase

- [x] Non applicable

### Composants

- Backend : `LiveTerminalsResponse.LiveTerminal` (+ `teamsTerminal`), `LiveTerminalService.describe`.
- Frontend : `styles.scss` (jetons `:root`), `atelier-terminal-teams.component.scss` (peau),
  `atelier-terminal-markdown.component.scss` (Markdown sous peau Teams), `atelier-terminal.component.ts`
  (libellé du fil), `atelier.models.ts` (`LiveTerminalEntry.teamsTerminal`), `mosaique.component.{ts,html,scss}`.
- Docs : `DESIGN_SYSTEM.md` §2 (renvoi) et §15.

### Préoccupations transversales

- [ ] Auth / Principal — non concerné
- [ ] Contexte tenant — non concerné (le champ est lu sur les projets déjà filtrés par `user_id`)
- [ ] Plans / limites — non concerné
- [ ] Navigation / routing — non concerné (libellé du fil seul ; liens inchangés)

## Plan de test

### Tests unitaires (Karma, styles globaux chargés)

- [ ] `terminal-teams-peau.spec.ts` (nouveau) : fond / barre / carte calculés ; balayage de tous les
  éléments porteurs de texte d'un terminal Teams riche → contraste ≥ 4,5:1 sur fond effectif ;
  jetons vs fond, barre, carte ; pastilles de la barre ≥ 3:1 contre la barre ; terminal de projet
  inchangé (fond `--cg-primary`, carte blanche absente) ; fil d'Ariane « Conversations Teams ».
- [ ] `terminal-markdown-lisible.spec.ts` : cas Teams — code en ligne et filets sous jetons Teams.
- [ ] `mosaique.component.spec.ts` : tuile Teams `#231A36` / en-tête `#1B1429` ; tuile ordinaire
  `#141D33` (existant).

### Tests d'intégration

- [ ] `LiveTerminalApiIntegrationTest` : un terminal Teams vivant est décrit `teamsTerminal: true`,
  un projet ordinaire `false`.

### Isolation utilisateur

- [ ] Le champ est calculé depuis `workspaceRepository.findByUserIdOrderByCreatedAtDesc(userId)` —
  aucun nouvel accès. Test existant d'isolation du registre inchangé et vert.

## Dépendances

### Subfeatures bloquantes

- SF-89-03 — done ; SF-30-14 — done (PR #561).

### Questions ouvertes impactées

- Aucune.

## Notes et décisions

- **D1 — Jetons globaux dans `styles.scss`.** Le terminal et la mosaïque les emploient tous deux ;
  les déclarer à `:root` évite deux copies.
- **D2 — Rouge éclairci `--cg-terminal-teams-error`.** `--cg-error` tombe à 3,3:1 sur `#231A36` ;
  le critère AA « tous les textes » impose une variante, bornée à la surface Teams.
- **D3 — Pastilles §5 dans la barre.** Leur paire intérieure n'est pas touchée (hors périmètre) ;
  le test vérifie que leur fond se détache de la barre. La paire « En attente » (`#F9A825` sur
  `#FFF8E1`, ~1,9:1) est un écart **préexistant** de la charte, signalé comme risque résiduel.
- **D4 — Tuile : `[teamsTerminal]` transmis.** Un seul composant rend le terminal (§13) : la tuile
  Teams reçoit la même peau par la même classe, et ses blocs riches s'y lisent comme dans le terminal.
- **D5 — Constat en dev, révélé par le balayage AA.** Les boutons Material sans encre propre
  (« Saisir un code d'accès », « Racheter des tokens », « Tout autoriser… ») prenaient la couleur
  primaire du thème (2,6:1) et le sélecteur de cible l'encre des écrans clairs (1,0:1). Sous la peau
  Teams seulement, leurs jetons Material (`--mdc-text-button-label-text-color`,
  `--mdc-outlined-button-*`, `--mat-standard-button-toggle-*`) sont redéfinis sur la surface. Même
  défaut probable dans les autres terminaux : hors périmètre (« aucun autre terminal ne change »),
  signalé comme risque résiduel.
- **D6 — Étape de plan « faite ».** Opacité cumulée 0,55 × 0,85 → 3,5:1 ; ramenée à 0,75 × 0,85 sous
  la peau Teams (≥ 4,5:1), l'effacement reste lisible.
