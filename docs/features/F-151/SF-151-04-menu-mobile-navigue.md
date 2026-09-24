# Mini-spec — F-151 / SF-151-04 — Le menu mobile navigue enfin (correctif P0 du hamburger)

---

## Identifiant

`F-151 / SF-151-04`

## Feature parente

`F-151` — Atelier mobile (responsive du chemin critique)

## Statut

`in-progress`

## Date de création

2026-09-25

## Branche Git

`feat/SF-151-04-menu-mobile`

---

## Objectif

> En une phrase : que fait cette subfeature ?

Corriger le bug **P0 mobile** où, menu hamburger ouvert (< 820 px), tapoter un lien de navigation
ne navigue pas — parce que le voile de fermeture capte le tap avant le lien.

---

## Contexte du bug (P0, mobile uniquement, desktop OK)

Sur téléphone (< 820 px), le panneau replié `.app-nav` s'ouvre mais les liens sont inertes :

- `.app-nav` (panneau, `z-index: 20`) est **à l'intérieur** de `<mat-toolbar class="app-bar">`, qui a
  `position: relative; z-index: 10`. Le `z-index: 20` du panneau est donc **confiné dans le contexte
  d'empilement de `.app-bar`** : à la racine, tout le sous-arbre de `.app-bar` peint au niveau 10.
- `.app-nav-backdrop` (voile, `position: fixed; z-index: 15`) est un **frère de `<mat-toolbar>`**, donc
  au niveau racine. À la racine, **15 > 10** → le voile passe **au-dessus** de toute la barre, panneau
  compris. Le tap sur un lien atteint le **voile** → `closeMenu()` → aucune navigation.

Effet secondaire du même piège : le voile couvrait aussi la barre (bouton X, bouton Compte) — inatteignables tant que le menu est ouvert.

---

## Comportement attendu

### Cas nominal

> Description précise du flux principal (entrée → traitement → sortie).

- < 820 px, menu ouvert : tapoter un lien (Chat, Forge, Vigie, Gouvernance, Bibliothèque, Q&A, Templates)
  **navigue vers la page cible** (`routerLink` respecté) **et referme le panneau**.
- Tapoter **hors** du panneau (le voile) referme le panneau sans naviguer.
- Le bouton X du hamburger et le bouton Compte restent atteignables menu ouvert.
- Les états actifs (`routerLinkActive` / `forgeActive()` / `vigieActive()`) restent corrects.
- ≥ 820 px : barre horizontale inchangée, aucun voile, aucun changement de comportement.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Tap sur un lien menant à la page courante (pas de `NavigationEnd`) | Le panneau se referme quand même (fermeture au `(click)`, sans dépendre de la navigation) | N/A (frontend) |
| Tap hors panneau (voile) | Referme le panneau, aucune navigation déclenchée | N/A (frontend) |

---

## Critères d'acceptation

> Chaque critère est vérifiable. Pas d'ambiguïté.

- [ ] Menu replié ouvert, tapoter un lien **navigue** (le lien porte le bon `href`/`routerLink`) **et** referme le panneau (`app-nav--open` retiré).
- [ ] Le voile referme le panneau au clic extérieur (non-régression).
- [ ] Le panneau `.app-nav` est empilé **au-dessus** du voile `.app-nav-backdrop`, le voile restant **au-dessus du contenu de page** (ferme au tap extérieur).
- [ ] Le bouton X (hamburger) et le bouton Compte restent cliquables menu ouvert (voile plus au-dessus de la barre).
- [ ] Les états actifs Forge/Vigie et `routerLinkActive` restent corrects après le correctif.
- [ ] ≥ 820 px : aucun voile, menu horizontal inchangé (non-régression desktop).
- [ ] `npm run build` frontend vert ; `shell.component.spec.ts` vert.

---

## Périmètre

### Hors scope (explicite)

- Toute modification backend, base de données, endpoint, route, guard, redirection.
- Le shell Atelier (SF-151-02) et la barre d'outils terminal (SF-151-03) — inchangés.
- Le comportement desktop (≥ 820 px) — inchangé.
- Aucun composant cluster, aucune migration.

---

## Technique

### Endpoint(s)

Aucun.

### Tables impactées

Aucune.

### Migration Liquibase

- [ ] Oui
- [x] Non applicable

### Composants Angular

- `ShellComponent` (`frontend/src/app/layout/shell/shell.component.{html,scss,spec.ts}`) — correctif d'empilement (SCSS) + fermeture du menu au clic sur un lien (HTML). La logique de fermeture sur `NavigationEnd` (effet, déjà présent depuis SF-151-01) est conservée.

**Correctif retenu (minimal, sûr) :** abaisser le voile `.app-nav-backdrop` **sous** `.app-bar`
(`z-index: 9` < 10) tout en le gardant **au-dessus du contenu** (contenu au niveau racine ~0). Le panneau
`.app-nav` reste dans le contexte de `.app-bar` (niveau racine 10) → il peint au-dessus du voile (9). La barre
(X, Compte) redevient atteignable menu ouvert. Ajout de `(click)="closeMenu()"` sur les 7 liens pour fermer
immédiatement, y compris quand le lien mène à la page courante (aucun `NavigationEnd`).

---

## Plan de test

### Tests unitaires / composant (`shell.component.spec.ts`)

- [ ] Menu ouvert : cliquer un lien de `.app-nav` referme le panneau (`app-nav--open` retiré) — nouveau.
- [ ] Menu ouvert : les liens portent le bon `href` (`routerLink` respecté), en particulier un lien standard (`/documents`) — nouveau.
- [ ] Le voile referme au clic extérieur (non-régression, existant).
- [ ] Fermeture sur navigation terminée (`NavigationEnd`) conservée (non-régression, existant).
- [ ] États actifs Forge/Vigie inchangés (non-régression, existant).

### Tests d'intégration

- [ ] Non applicable — correctif frontend pur, aucun endpoint.

### Isolation workspace / `user_id`

- [x] Non applicable — raison : aucun accès données, aucune route, aucun endpoint ; correctif CSS + template de la coquille.

---

## Préoccupations transversales

| Préoccupation | Concernée ? | Composants impactés |
|--------------|-------------|---------------------|
| Auth / Principal | Non | — (aucun changement d'auth ni de Principal) |
| Contexte tenant | Non | — (aucune résolution de tenant touchée) |
| Plans / limites | Non | — |
| Navigation / routing | **Marginale** | `ShellComponent` uniquement : aucune route/guard/redirection ajoutée ni modifiée. On corrige uniquement l'empilement CSS et on ajoute une fermeture de panneau au clic. Les `routerLink` existants (Chat, Forge, Vigie, Gouvernance, Bibliothèque, Q&A, Templates) sont inchangés ; `forgeActive()`/`vigieActive()`/`routerLinkActive` inchangés. Aucun autre chemin de navigation n'est affecté (le correctif ne touche que le shell mobile). |

---

## Dépendances

### Subfeatures bloquantes

- `SF-151-01` — statut : done (le hamburger et le voile viennent de là).

### Questions ouvertes impactées

- [ ] Aucune.

---

## Notes et décisions

- **Pourquoi abaisser le voile plutôt que déplacer le panneau ?** Le panneau reste dans `<mat-toolbar>`
  pour son positionnement (`top: 100%`). Le sortir au niveau racine complexifierait le layout pour un
  hotfix P0. Abaisser le voile sous la barre (`z-index: 9`) est le plus petit changement correct : il
  place le voile sous le panneau ET sous la barre (X/Compte redeviennent cliquables), tout en le gardant
  au-dessus du contenu pour fermer au tap extérieur.
- **`(click)="closeMenu()"` sur les liens** : ceinture + bretelles avec l'effet `NavigationEnd` déjà en
  place. Il couvre le cas du lien menant à la page courante (aucun `NavigationEnd`) et rend la fermeture
  immédiate. Sans effet visible ≥ 820 px (le signal est déjà `false` et le menu n'y est pas replié).
</content>
</invoke>
