# Mini-spec — [F-160 / SF-160-01] Chat mobile pro

## Identifiant

`F-160 / SF-160-01`

## Feature parente

`F-160` — Refonte mobile pro : Chat, Forge, Vigie

## Statut

`ready`

## Date de création

2026-09-26

## Branche Git

`feat/SF-160-01-chat-mobile-pro`

---

## Objectif

Porter l'écran de **Chat** à un rendu mobile de niveau produit conforme à la maquette validée
(bulles de conversation, bloc de code copiable qui défile, composeur ancré en bas, en-tête compact),
**sous `@media (max-width: 819px)` uniquement**, sans toucher au desktop ni à la logique Angular.

---

## Comportement attendu

### Cas nominal

Sur téléphone (< 820 px), l'écran de chat adopte le **look sombre de charte** (surfaces `--cg-navy` /
`--cg-navy-2`, encres claires `--cg-divider`, accent `--cg-orange`) et la **forme** de la maquette :

- **En-tête compact** : bouton conversations (☰) et bouton « nouvelle » ≥ 44 px, titre de conversation
  ellipsé, contrôles (modèle / dossier / export) resserrés sur une ligne, sans débordement.
- **Fil de messages** : bulles **utilisateur** alignées à droite (surface `--cg-navy-2`, **filet
  `--cg-orange`**, rayon asymétrique `14 14 4 14`) ; bulles **assistant** alignées à gauche (surface
  `--cg-navy-2`, filet discret, rayon `14 14 14 4`). Aucun aplat orange plein (D7 du cadrage).
- **Bloc de code** (composant `app-copy-block`, réutilisé) : fond sombre `--cg-navy-2`, police mono,
  bouton « copier » ancré, contenu qui **défile en x DANS le bloc** (`overflow-x: auto`), jamais la page.
- **Composeur ancré en bas** : `position: sticky; bottom: 0`, respect de `env(safe-area-inset-bottom)`,
  champ de saisie pleine largeur au-dessus, rangée d'outils dessous (joindre / bibliothèque à gauche,
  Envoyer poussé à droite), toutes cibles ≥ 44 px.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Ligne de code insécable très large | Défile **dans le bloc** (`overflow-x`), la page ne défile PAS horizontalement (assert à 360/400 px) |
| Titre de conversation très long | Ellipsé dans l'en-tête, aucun débordement horizontal de la page |
| Message utilisateur très long sans espace | `word-break` dans la bulle, pas de scroll-x de page |
| Rendu à ≥ 820 px (desktop) | **Aucune** règle F-160 ne s'applique (toutes sous `max-width: 819px`) — desktop strictement inchangé |

---

## Critères d'acceptation

- [ ] Toutes les nouvelles règles vivent sous `@media (max-width: 819px)` (test de non-régression desktop D3).
- [ ] À 360 px et 400 px, avec un bloc de code insécable très large et une bulle utilisateur très longue,
      `host.scrollWidth <= host.clientWidth` (aucun scroll-x de page, test réel D4, rouge-avant/vert-après).
- [ ] Aucune couleur littérale (hex / rgb) dans la feuille mobile : jetons `--cg-*` uniquement (ou
      `color-mix` dérivé d'un jeton) — mapping du cadrage §2.
- [ ] Bulle utilisateur alignée à droite, filet `--cg-orange`, rayon asymétrique ; bulle assistant à gauche.
- [ ] Composeur `sticky bottom` avec `env(safe-area-inset-bottom)` ; champ + joindre/bibliothèque + Envoyer.
- [ ] Cibles tactiles ≥ 44 px (boutons d'icône, Envoyer, bascule/fermeture du tiroir).
- [ ] Feuille(s) mobile < 12 ko chacune (budget build `anyComponentStyle` = 12 ko, échec de build sinon).
- [ ] `npm run build` vert ; suite Karma ciblée (chat) verte ; specs existants du chat inchangés (logique).
- [ ] Aucun `*ngIf` / `*ngFor` / `(click)` / `[attr]` / `routerLink` ajouté, retiré ou modifié (D8).

---

## Périmètre

### Hors scope (explicite)

- Tout le **desktop** (≥ 820 px) : rigoureusement inchangé.
- Toute **couleur / police nouvelle** (charte stricte, mapping cadrage §2).
- Toute **logique métier**, tout **binding** Angular, toute route, tout endpoint, toute migration.
- Toute **fonctionnalité nouvelle** (dictée réelle, nouveau bouton) : on restyle les gestes existants
  (joindre, importer depuis la bibliothèque) sans en ajouter.
- Les écrans Forge (SF-160-02) et Vigie (SF-160-03).

---

## Technique

### Endpoint(s)

Aucun. Pur frontend, display-only.

### Tables impactées

Aucune. Aucune migration Liquibase.

### Composants Angular (impactés — restyle SCSS uniquement)

- `chat/chat.component` (`.scss` + `chat-mobile.component.scss` étendue) — surfaces, bulles, en-tête, composeur.
- `chat/copy-block/copy-block.component.scss` — bloc de code sombre sous 819 px (composant réutilisé, propre au chat).
- `chat/chat-mobile-largeur.spec.ts` (nouveau) — tests largeur réelle + non-régression desktop.

> **Préoccupations transversales** — Navigation/routing : **non touchée** (aucun `routerLink` ajouté/retiré,
> même URL aux deux tailles). Auth / Principal / tenant / plans / limites : **non touchés** (aucun accès
> données ; isolation `user_id` hors sujet, pur affichage). Design system : composants impactés listés
> ci-dessus, mapping cadrage §2 respecté (jetons `--cg-*` uniquement).

---

## Plan de test

### Tests unitaires (composant / CSSOM)

- [ ] **Non-régression desktop (D3)** : à la largeur Karma par défaut (1440 px), les règles mobile ne
      s'appliquent pas — `.composer` n'est PAS `sticky` (preuve que la règle est bien gardée sous 819 px).
- [ ] **Non-régression desktop (D3)** : aplatissement CSSOM — toute règle nommant les sélecteurs mobile
      introduits (bulles, composeur ancré) apparaît uniquement sous `max-width: 819px`.
- [ ] **Charte (D2)** : la feuille mobile du chat ne contient aucun hex/rgb littéral.

### Tests d'intégration (DOM réel ChromeHeadless)

- [ ] **Largeur réelle (D4)** : règles mobile appliquées au layout réel ; à 360 px puis 400 px, avec un
      bloc de code insécable très large + une bulle utilisateur très longue, `host.scrollWidth <=
      host.clientWidth` (aucun scroll-x de page).
- [ ] **Sanity** : sans les règles mobile, le même contenu FAIT déborder (prouve que le test mesure le layout).

### Isolation workspace

- [ ] Non applicable — raison : pur affichage, aucun accès données.

---

## Dépendances

### Subfeatures bloquantes

- `SF-159-01` (fondations responsive : `.page`, `.table-scroll`, `@mixin phone`, point de rupture 819 px) — done.
- `SF-158-01` (chat 1 colonne + tiroir mobile, `chat-mobile.component.scss`) — done.

### Questions ouvertes impactées

- [ ] Aucune (`docs/OPEN_QUESTIONS.md` non impacté).

---

## Notes et décisions

- **D7 (cadrage)** : la bulle utilisateur n'est PAS un aplat orange plein ; elle se distingue par
  l'alignement, le rayon asymétrique et un **filet `--cg-orange`** sur surface `--cg-navy-2` (§8 DS :
  pas de fond coloré plein sur les cartes).
- **Look sombre = charte, pas couleur nouvelle** : chaque hex de la maquette est mappé sur un jeton
  `--cg-*` existant (cadrage §2). Les filets discrets sur fond sombre utilisent `color-mix(... , var(--cg-divider) ...)`
  — technique déjà employée dans `chat.component.scss` (aucune couleur nouvelle).
- **Composeur** : réagencement champ-au-dessus / outils-dessous obtenu par `flex-wrap` + `order` sous
  819 px **sans réordonner le DOM** (desktop conserve sa rangée unique).
