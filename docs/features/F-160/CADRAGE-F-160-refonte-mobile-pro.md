# F-160 — Refonte mobile pro : Chat, Forge, Vigie

> Cadrage du 2026-09-26, à la demande du PO. Maquette **validée par le PO** (référence de look) :
> `mobile-chat-forge-vigie-mockup.html` (onglets Chat / Forge / Vigie).
>
> **Ce document cadre ; il ne livre aucun code.** Suite de F-151 (coquille mobile), F-158 (écrans de
> contenu passés en 1 colonne) et F-159 (fondations transverses `.page` / `.table-scroll` / point de
> rupture unique 819 px). F-158/F-159 ont rendu les écrans **utilisables** sur téléphone (plus de
> scroll-x, 1 colonne, cibles ≥ 44 px). F-160 **ne réinvente pas la responsivité** : elle en fait une
> **refonte d'ergonomie et de look de qualité pro** sur trois écurans que le PO a maquettés — Chat,
> Forge, Vigie. **Pur frontend, mobile uniquement, display-only.**

## 0. Objectif

Porter Chat, Forge et Vigie à un **rendu mobile de niveau produit** conforme à la maquette validée :
bulles de conversation, bloc de code qui défile avec bouton « copier », composeur ancré en bas,
en-tête de conversation compact ; cartes de postes pleine largeur ; carte « résumé du matin » et
sections d'engagements de la Vigie. **Tout est sous `@media (max-width: 819px)` — le desktop
(≥ 820 px) reste rigoureusement identique** (test de non-régression desktop obligatoire, D3).

## 1. Ce qui existe déjà (vérifié — à RESTYLER, pas à recréer)

- **Point de rupture unique : 819 px** — `frontend/src/styles/_breakpoints.scss` (`$bp-phone: 819px`
  + `@mixin phone`), posé par F-159 / SF-159-01. **F-160 l'utilise, ne le duplique pas.**
- **Fondations transverses (F-159 / SF-159-01)** — `.page` (gouttière latérale `clamp(16px,4vw,24px)`,
  ≥ 16 px partout), `.table-scroll` (seul élément autorisé à défiler en x), garde-fou dialogs
  `@include bp.phone` dans `frontend/src/styles.scss:235+`. **Réutilisées telles quelles.**
- **Chat** : `frontend/src/app/chat/chat.component.{html,scss,ts}` + **feuille mobile dédiée déjà en
  place** `chat-mobile.component.scss` (112 lignes, 3 blocs `@media`, issue de SF-151-03 / SF-158-01).
  **F-160 étend cette feuille dédiée** (budget 12 ko, D5) plutôt que de gonfler `chat.component.scss`
  (362 lignes).
- **Forge** : `frontend/src/app/postes/postes.component.{html,scss}` (325 l.),
  `postes/forge-rail/forge-rail.component.{html,scss}`, `postes/forge-project-tile/*`, mixins
  `postes/_forge-layout*.scss`. Grille de cartes déjà en 1 colonne sous 819 px (SF-158-02/03).
- **Vigie** : `frontend/src/app/vigie/vigie.component.{html,scss}` (98 l.), `vigie/radar/*`,
  `vigie-forge-shell.scss` / `vigie-forge-detail.scss` (forme empruntée à la Forge, DS §16).
- **Patron de test de largeur réelle** : `atelier/terminal/terminal-largeur-reelle.spec.ts` mesure
  `scrollWidth <= clientWidth` en DOM réel (ChromeHeadless) à 360/400 px — **modèle imposé** pour le
  garde-fou anti-scroll-x de chaque SF (D4).
- **Patron de test de non-régression global** : `styles/fondations-responsive.spec.ts` aplatit la
  CSSOM (media incluses) — **modèle** pour vérifier que les nouvelles règles sont bien toutes sous
  `max-width: 819px` (D3).

## 2. La maquette, et pourquoi elle ne change PAS la charte

La maquette est **sombre** (fond `#0B1020`, surfaces `#121a30`/`#18223c`, accent `#F5A623`). C'est un
choix de **look mobile**, pas une couleur nouvelle : le DESIGN_SYSTEM porte déjà une surface sombre
charte (§13 : terminal en `--cg-primary` / `--cg-navy-2`, encres claires `--cg-divider` / `--cg-surface`).
**F-160 n'introduit AUCUNE couleur** : chaque hex de la maquette est **mappé sur un jeton `--cg-*`
existant**. Table de correspondance **normative** (BLOCAGE si un hex de la maquette est posé en dur) :

| Rôle maquette (hex) | Jeton `--cg-*` existant | Référence DS |
|---|---|---|
| Fond d'écran `#0B1020` | `--cg-primary` / `--cg-navy` | §2 |
| Surfaces `#121a30` `#18223c` `#1d294a` | `--cg-navy-2` (`#141D33`) | §2, §13 |
| Fond bloc de code `#080c17` | `--cg-navy-2` | §13 |
| Texte principal `#E7ECF6` | `--cg-divider` (encre claire sur sombre) | §13 |
| Titres `#f4e6c9` / surbrillance | `--cg-surface` | §13 |
| Texte atténué `#8b95ad` `#5f6b86` | `--cg-text-secondary` | §2 |
| Accent `#F5A623` (bouton envoyer, onglet actif, filet digest) | `--cg-orange` / `--cg-accent` (`#E07B39`) | §2 |
| Bulle utilisateur `--accent-soft #3a2f17` | filet/pastille de charte (jamais un aplat orange plein) — voir D7 | §8 |
| Pastille « connecté / ok » `#43c08a` | §5 statut `.badge--success` (`#E8F5E9`/`#16A34A`) | §5 |
| Pastille « urgent / en attente » (ambre) | §5 `.badge--warning` (`#FFF8E1`/`#F9A825`) | §5, §12 |
| Pastille « hors ligne / en attente » (gris) | §5 `.badge--neutral` (`#F5F5F5`/`#64748B`) | §5 |
| Danger `#e8836b` | `--cg-error` (`#DC2626`) | §2 |
| Police mono (code, méta) | `--cg-font-mono` (JetBrains Mono) | §3 |
| Police corps | Inter | §3 |

**Ce que F-160 prend de la maquette = la FORME** (silhouette des bulles, composeur ancré, en-tête
compact, cartes pleine largeur, carte digest, cartes d'engagement) ; **la couleur reste 100 % charte**.

## 3. Décisions de conception

- **D1 — Mobile uniquement.** Toute règle de F-160 vit sous `@include bp.phone` (`max-width: 819px`).
  Aucune règle hors media, aucun palier supplémentaire. Le desktop est hors périmètre par construction.
- **D2 — Charte stricte, zéro couleur nouvelle.** Jetons `--cg-*` uniquement, via la table §2. Aucun
  hex de la maquette en dur (garde-fou de test : la feuille compilée ne contient aucun hex hors jetons).
- **D3 — Zéro régression desktop (test obligatoire).** Chaque SF ajoute/étend un spec qui aplatit la
  CSSOM du composant (modèle `fondations-responsive.spec.ts`) et **échoue si une règle nouvelle porte
  sur ≥ 820 px** ; complété par un rendu du composant à ≥ 1024 px vérifiant que la structure desktop
  (grille, tailles) est **inchangée** (rouge si une règle fuit hors media).
- **D4 — Jamais de scroll horizontal (test réel).** Chaque SF ajoute un spec en DOM réel qui mesure
  `host.scrollWidth <= host.clientWidth` à **360 px et 400 px** avec un contenu volontairement large
  (nom de poste très long, ligne de code insécable), **rouge-avant / vert-après** (modèle
  `terminal-largeur-reelle.spec.ts`). Blocs de code et tableaux larges → `.table-scroll` / conteneur
  `overflow-x:auto` isolé, jamais la page.
- **D5 — Budget 12 ko / feuille.** Les règles mobile vont dans une **feuille dédiée** ajoutée à
  `styleUrls` quand la feuille du composant approche le budget (chat : `chat-mobile.component.scss`
  existe déjà ; Forge et Vigie créent la leur si besoin — `postes-mobile.component.scss`,
  `vigie-mobile.component.scss`). Chaque feuille reste **< 12 ko**, mesuré.
- **D6 — Cibles ≥ 44 px, gouttières ≥ 16 px.** Tout élément tactile (bouton d'icône, onglet,
  carte cliquable, champ) fait ≥ 44 × 44 px sous 819 px ; la gouttière latérale d'écran est ≥ 16 px
  (réutilise `.page` / `padding-inline` de F-159).
- **D7 — La couleur ne porte jamais seule l'info, pas d'aplat sur carte (§8).** La bulle utilisateur
  n'est pas un aplat orange plein (la maquette suggère un fond teinté) : elle se distingue par
  l'alignement, le rayon asymétrique et un filet `--cg-orange`, sur surface `--cg-navy-2` — conforme
  §8 « pas de fond coloré sur les cartes ». Les statuts passent par les pastilles §5 écrites.
- **D8 — Restyle, pas refonte logique.** On ne touche **ni le TypeScript métier, ni les bindings, ni
  les routes** : uniquement SCSS et, si strictement nécessaire, des ajustements de structure HTML
  (classes, wrappers) **sans changer les `*ngIf` / `*ngFor` / `(click)` / `[attr]` existants**. Les
  specs Angular existants des trois écrans doivent rester **verts sans modification de leur logique**.
- **D9 — Pur frontend, display-only.** Aucun endpoint, aucune migration, aucun DTO, aucun changement
  de protocole runner, **aucun composant cluster**. Gateway-First et Provider Independence intactes.

## 4. Découpage en subfeatures (3)

| SF | Titre | Contenu (restyle mobile < 819 px) | Composants |
|----|-------|-----------------------------------|-----------|
| **SF-160-01** | **Chat mobile pro** *(prioritaire)* | Bulles **utilisateur** (alignées à droite, filet `--cg-orange`, rayon asymétrique) et **assistant** (alignées à gauche, surface `--cg-navy-2`) ; **bloc de code** avec bouton « copier » ancré, contenu qui **défile en x** dans le bloc (`.table-scroll`, jamais la page) ; **composeur ancré en bas** (`position: sticky; bottom`, respect `env(safe-area-inset-bottom)`, champ + joindre/dicter/envoyer, cibles ≥ 44 px) ; **en-tête de conversation compact** (titre ellipsé, sous-titre modèle, boutons conversations / nouveau ≥ 44 px). Réutilise et étend `chat-mobile.component.scss`. Le bouton « copier » réutilise le composant existant `chat/copy-block`. | `chat/chat.component.{html,scss}`, `chat-mobile.component.scss`, `chat/copy-block/*` (réutilisé) + spec |
| **SF-160-02** | **Forge mobile pro** | Barre de **recherche** pleine largeur en tête (filet `--cg-divider`, focus `--cg-orange`) ; **cartes de postes pleine largeur** empilées : nom + pastille de statut §5 (connecté / hors ligne), méta en mono (`n projets`, `actif il y a …`, interpréteur), cibles ≥ 44 px ; en-tête « Forge · n postes connectés » compact. Restyle de `postes.component` et `forge-rail` / `forge-project-tile` sous 819 px, feuille dédiée si budget. Aucune couleur d'identité §9 modifiée (badges via `app-host-badge` / `app-mission-badge` inchangés). | `postes/postes.component.{html,scss}`, `postes/forge-rail/*`, `postes/forge-project-tile/*` + spec |
| **SF-160-03** | **Vigie mobile pro** | **Carte « résumé du matin »** (digest : titre + phrase, filet gauche `--cg-orange`, surface `--cg-navy-2`, **pas d'aplat**) ; sections **« À faire par moi »** et **« J'attends des autres »** en **cartes d'engagements** (sujet + pastille §5, ligne d'engagement + méta « depuis 1 j » / « relancé 2× » en mono) ; en-tête « Vigie · résumé du matin » compact + bouton rafraîchir ≥ 44 px. Restyle de `vigie.component` et des cartes du Radar sous 819 px, feuille dédiée si budget. Formes empruntées à la Forge (DS §16) conservées. | `vigie/vigie.component.{html,scss}`, `vigie/radar/*` + spec |

**Ordre imposé** : **SF-160-01 (Chat) en premier** (prioritaire, mot du PO). 02 et 03 indépendantes,
démo-ables isolément.

Chaque SF ≤ 2 jours (restyle SCSS d'un écran + 3 specs : non-régression desktop, anti-scroll-x réel,
budget de feuille). Découpage conforme (aucune SF > 2 j).

## 5. Préoccupations transversales

- **Navigation / routing** : **non touchée** — F-160 est display-only, même URL aux deux tailles,
  aucune route/guard/redirection. (À reconfirmer en mini-spec de chaque SF : aucun `routerLink`
  ajouté/retiré.)
- **Auth / Principal / tenant / plans / limites** : **non touchés** (pur affichage ; aucun accès
  données, isolation `user_id` hors sujet par construction).
- **Design system (transverse)** : chaque SF **liste les composants impactés** (colonne « Composants »
  du §4) et respecte la table de mapping §2 — exigence anti-régression charte.

## 6. Garde-fous

Gateway-First, Provider Independence, isolation `user_id` (aucun accès données), DESIGN_SYSTEM strict
(jetons `--cg-*` uniquement, table §2, cibles ≥ 44 px, gouttières ≥ 16 px), point de rupture unique
819 px (`_breakpoints.scss`), fondations F-159 réutilisées (`.page`, `.table-scroll`, `@mixin phone`),
budget **12 ko / feuille** (feuille dédiée si besoin), **aucun composant cluster**, aucune migration,
aucun endpoint, aucun changement runner, **jamais de scroll-x**, **desktop ≥ 820 px inchangé**
(test de non-régression obligatoire), **logique / bindings Angular intacts** (D8).

## 7. Hors périmètre

- Toute modification du **desktop** (≥ 820 px reste identique — c'est la règle absolue).
- Toute **couleur / police nouvelle** (charte stricte, mapping §2).
- Toute **logique métier**, tout **binding** Angular, toute route, tout endpoint, toute migration.
- Les autres écrans mobiles déjà traités par F-158 / F-159 (hors Chat, Forge, Vigie).
- La coquille (F-151), la PWA (F-152), les notifications (F-153).
- Toute **fonctionnalité nouvelle** (dictée réelle, pièce jointe réelle…) : les boutons de la maquette
  se branchent sur les gestes **déjà existants**, sans nouveau comportement.

## 8. Drapeaux

Aucun drapeau de déploiement (pur frontend, aucun secret, aucune migration) ; effet visible après
déploiement de l'image `frontend`.
