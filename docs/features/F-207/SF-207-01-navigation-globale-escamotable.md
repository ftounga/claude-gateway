# Mini-spec — [F-207 / SF-207-01] Navigation globale escamotable sur les écrans de travail

## Identifiant
`F-207 / SF-207-01` — Feature parente `F-207` (Regagner l'espace de travail) — Statut `done` — Créée 2026-10-21
Branche : `feat/SF-207-01-navigation-escamotable` — aucune dépendance.

## Objectif
Sur les écrans de travail (terminal `/atelier/:id`, mosaïque `/forge/mosaique`), la barre de navigation globale cède ses 64 px au contenu : elle se replie en une lisière fine et glisse en surimpression au survol, avec une punaise pour la garder affichée.

## Comportement nominal
- **Écran de travail** = chemin `/atelier/<id>` (pas `/atelier/<id>/fichiers`) ou `/forge/mosaique`. Fonction pure `isWorkspaceScreen(url)`.
- Sur un écran de travail, à partir de 820 px : la barre (`.app-bar`) sort du flux. Une **lisière** de 6 px reste en haut, sur toute la largeur, avec au centre une petite poignée (marque « NG » + chevron) visible au repos à faible opacité.
- **Ouvrir** : survol de la lisière, focus clavier sur la poignée (`button`, `aria-label="Afficher la navigation"`), ou focus d'un élément de la barre. La barre glisse du haut (180 ms) **par-dessus** le contenu, avec l'ombre de la charte ; le contenu ne bouge pas.
- **Fermer** : la souris quitte la barre et la lisière pendant 350 ms, ou touche Échap. Un menu ouvert depuis la barre (Compte, cloche) la garde ouverte jusqu'à sa fermeture.
- **Punaise** (icône `push_pin`, à droite de la barre, seulement sur un écran de travail) : la barre revient dans le flux, comme ailleurs. Préférence mémorisée sur l'appareil (`localStorage` `cg.nav.pinned`, lecture et écriture protégées par try/catch). Re-cliquer la replie.
- Hors écran de travail, ou sous 820 px : rendu **inchangé** (barre fixe ; hamburger F-151 sur téléphone).
- Le bandeau d'alerte de quota (`app-quota-alert-banner`) reste dans le flux, sous la lisière.

## Cas d'erreur
| Situation | Comportement attendu |
|---|---|
| `localStorage` inaccessible (navigation privée, bloqué) | barre repliée par défaut, punaise fonctionnelle pour la session, aucune erreur |
| Navigation d'un écran de travail vers un autre écran | la barre redevient fixe immédiatement |
| Appareil sans survol (tactile ≥ 820 px) | un tap sur la poignée ouvre la barre ; un tap hors barre la referme |
| `prefers-reduced-motion` | apparition sans glissement |

## Critères d'acceptation
- [ ] CA1 `isWorkspaceScreen` : vrai pour `/atelier/abc`, `/atelier/abc?x=1`, `/forge/mosaique` ; faux pour `/atelier`, `/atelier/abc/fichiers`, `/chat`, `/forge`, `/vigie/x`.
- [ ] CA2 Sur `/atelier/:id` à 1366 px, barre non épinglée : la zone de contenu commence à ≤ 6 px du haut (au lieu de 64).
- [ ] CA3 Survol de la lisière → barre visible (classe `app-bar--revealed`) en surimpression ; la position du contenu est inchangée.
- [ ] CA4 Sortie de la souris → barre masquée après 350 ms ; Échap → masquée tout de suite.
- [ ] CA5 Focus clavier sur la poignée → barre visible ; tous les liens restent atteignables au clavier.
- [ ] CA6 Punaise : la barre revient dans le flux ; préférence relue au rechargement ; sans `localStorage`, aucune exception.
- [ ] CA7 Non-régression : `/chat` et `/gouvernance` gardent la barre fixe ; téléphone (< 820 px) inchangé (hamburger) ; tests shell existants verts ; `ng build` vert.

## Plan de test
- `shell.component.spec.ts` : `isWorkspaceScreen` (CA1) ; classe d'escamotage selon l'URL ; ouverture par survol et par focus, fermeture différée (fakeAsync) et par Échap (CA3–CA5) ; punaise et `localStorage` absent (CA6) ; barre fixe hors écran de travail (CA7).
- Capture headless avant/après à 1366 × 768 (`captures/`).
- Isolation utilisateur : sans objet (aucune donnée, aucun endpoint).

## Impacts
- Tables / endpoints : aucun.
- Composants : `ShellComponent` (ts, html, scss).
- Préoccupation transversale **Navigation / routing** : cochée. Composants impactés : `ShellComponent` seul. Aucune route, aucun guard, aucune redirection modifiés ; les 7 liens, la cloche et le menu Compte sont les mêmes éléments, seule leur visibilité change sur 2 chemins. Auth, tenant, plans : non.
- Design system : aucune couleur ni police nouvelle (`--cg-surface`, `--cg-divider`, `--cg-accent`, ombre existante).

## Hors périmètre
Autres écrans (barre fixe), mise en page téléphone, contenu de la barre.
