# Mini-spec — F-151 / SF-151-01 — Shell global responsive (nav → menu repliable)

## Identifiant

`F-151 / SF-151-01`

## Feature parente

`F-151` — Atelier mobile (responsive du chemin critique)

## Statut

`ready`

## Date de création

2026-09-24

## Branche Git

`feat/SF-151-01-shell-global-responsive`

---

## Objectif

Sous 819 px, replier les 7 liens horizontaux de la barre de navigation globale
(`shell.component`) dans un **menu accessible déclenché par un bouton hamburger**, sans
toucher au rendu ≥ 820 px.

---

## Comportement attendu

### Cas nominal

- **≥ 820 px** : rien ne change. Les 7 liens (`Chat`, `Forge`, `Vigie`, `Gouvernance`,
  `Bibliothèque`, `Q&A`, `Templates`) restent affichés en ligne dans `.app-nav` ; le bouton
  hamburger est masqué (`display: none`). Rendu **byte-identique** à l'existant.
- **< 820 px** : les liens en ligne sont masqués ; un bouton hamburger (`menu`) apparaît à
  gauche de la marque. Au clic, `.app-nav` se déploie en **panneau vertical** sous la barre.
  Chaque lien reste le même élément `<a>` (mêmes `routerLink`, mêmes classes
  `app-nav__forge`/`app-nav__vigie`, même calcul d'état actif).
- Le menu se ferme : au **clic sur un lien** (changement de route), au **clic hors zone**
  (backdrop), à la touche **Échap**, et à toute **navigation terminée**.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Redimensionnement de < 820 px vers ≥ 820 px avec menu ouvert | Le panneau ne masque pas la barre desktop : `.app-nav` reprend son affichage en ligne (le CSS `@media` prime), l'état `menuOpen` est neutre au prochain rendu mobile |
| Navigation déclenchée alors que le menu est ouvert | Le menu se referme automatiquement (écoute des `NavigationEnd` déjà présente) |
| Échap pressé menu fermé | Aucun effet (pas d'erreur) |

---

## Critères d'acceptation

- [ ] À ≥ 820 px, `.app-nav` est affiché en ligne et `.app-nav-toggle` est masqué (aucune régression visuelle desktop).
- [ ] À < 820 px, `.app-nav` est masqué tant que le menu n'est pas ouvert ; `.app-nav-toggle` est visible.
- [ ] Le bouton hamburger porte `aria-expanded` reflétant l'état, `aria-controls="app-nav"`, et un `aria-label` explicite.
- [ ] `toggleMenu()` bascule `menuOpen` ; `closeMenu()` le remet à `false`.
- [ ] Un `NavigationEnd` referme le menu (via un `effect` sur l'URL courante déjà exposée).
- [ ] La touche Échap referme le menu.
- [ ] Un clic sur le backdrop referme le menu.
- [ ] Les 7 liens restent présents et interrogeables via `.app-nav a[...]` (non-régression des tests existants du shell).
- [ ] Aucune nouvelle route, aucun guard, aucune redirection ; les liens existants restent joignables aux deux tailles.

---

## Périmètre

### Hors scope (explicite)

- Le shell **Atelier** (`atelier.component`) → SF-151-02.
- La barre d'outils du terminal → SF-151-03.
- Toute installabilité/PWA (F-152) et toute notification (F-153).
- Toute modification de logique métier, d'auth, de tenant, de plan.

---

## Contraintes de validation

Aucune donnée saisie. Point de rupture imposé : **819 px** (`max-width: 819px`), aligné sur le
patron `/forge` (`_forge-layout-shell.scss:148`) et `DESIGN_SYSTEM.md:686`.

---

## Technique

### Endpoint(s)

Aucun.

### Tables impactées

Aucune.

### Migration Liquibase

- [x] Non applicable

### Composants Angular

- `ShellComponent` (`shell.component.{ts,html,scss}`) — ajout d'un signal `menuOpen`, des
  méthodes `toggleMenu`/`closeMenu`, d'un `effect` de fermeture sur navigation, d'un
  `HostListener` Échap, d'un bouton hamburger, d'un backdrop et des règles `@media (max-width: 819px)`.

---

## Préoccupation transversale — Navigation / routing

Composants qui résolvent la navigation du shell : **`ShellComponent`** (seul). Impact :

- Aucune route ajoutée/modifiée ; aucun guard ; aucune redirection.
- Les 7 liens conservent leurs `routerLink` (`/chat`, `spaceLinks().forge`, `spaceLinks().vigie`,
  `/gouvernance`, `/documents`, `/ask`, `/templates`) et le menu compte (`account_circle`) reste
  intact.
- Non-régression : les tests existants (`shell.component.spec.ts`) qui interrogent
  `.app-nav a[href="/forge"]`, `.app-nav__vigie`, l'état actif Forge/Vigie et les passerelles
  restent verts (les éléments et classes sont conservés).

---

## Plan de test

### Tests unitaires (Angular / Karma)

- [ ] Le menu est fermé au départ (`menuOpen()` faux) et le backdrop absent.
- [ ] `toggleMenu()` ouvre puis referme ; `aria-expanded` du hamburger suit l'état.
- [ ] Un `NavigationEnd` referme le menu ouvert.
- [ ] Échap referme le menu ouvert.
- [ ] Un clic sur le backdrop referme le menu.
- [ ] Non-régression : les 7 liens restent présents ; `.app-nav a[href="/forge"]` interrogeable.

### Tests d'intégration

Sans objet (pur frontend, aucun endpoint).

### Isolation workspace / user_id

- [x] Non applicable — aucun accès données (pur affichage). Auth/tenant non touchés.

---

## Dépendances

Aucune subfeature bloquante. Aucune question ouverte impactée.

---

## Notes et décisions

- **D-navcollapse** : un seul jeu de liens (pas de duplication desktop/mobile). `.app-nav` bascule
  d'une rangée en ligne (desktop) à un panneau déroulant (mobile) par CSS `@media`, piloté par une
  classe `.app-nav--open`. Accessibilité gérée à la main (aria-expanded/controls, Échap, backdrop)
  plutôt que via `mat-menu`, pour ne pas dupliquer les 7 liens ni changer le rendu desktop.
- Charte : jetons `--cg-*` uniquement ; cible tactile du hamburger ≥ 44 px ; jamais de scroll-x.
