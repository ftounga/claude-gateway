# Mini-spec — [F-207 / SF-207-05] L'aide rangée dans la barre sur les écrans de travail

## Identifiant
`F-207 / SF-207-05` — Feature parente `F-207` (Regagner l'espace de travail) — Statut `done` — Créée 2026-10-21
Branche : `feat/SF-207-05-bulle-aide-envoyer` — dépend de SF-207-01 (mergée).

## Objectif
Sur les écrans de travail, la bulle d'aide flottante « ? » ne recouvre plus le bouton Envoyer : elle quitte le coin bas-droit et se range dans la barre de navigation escamotable, d'où elle ouvre le même panneau, en haut à droite.

## Comportement nominal
- **Écran de travail** = `isWorkspaceScreen(url)` (SF-207-01). La coquille passe `docked` au widget d'aide.
- À partir de 820 px, sur un écran de travail :
  - la bulle flottante (`mat-fab`) n'est pas affichée ;
  - la barre globale porte un bouton `help_outline` « Aide » (à gauche de la cloche), qui ouvre ou ferme le panneau ;
  - le panneau s'ouvre **en haut à droite**, sous la barre (`top: calc(var(--cg-shell-bar-h) + 8px)`, donc en haut de l'écran quand la barre est repliée), loin du composeur, au-dessus du rail des questions ;
  - le panneau reste ouvert quand la barre se replie ; il se ferme par sa croix ou par le bouton de la barre.
- Hors écran de travail, ou sous 820 px : rendu **inchangé** (bulle en bas à droite ; remontée SF-158-19 sur téléphone ; pas de bouton dans la barre).

## Cas d'erreur
| Situation | Comportement attendu |
|---|---|
| Panneau ouvert, navigation vers un autre écran | le panneau reste ouvert, il reprend sa place en bas à droite au-dessus de la bulle |
| Barre épinglée (punaise) | même bouton dans la barre, même panneau en haut à droite |
| Panneau plus haut que la fenêtre | hauteur bornée (`calc(100vh - var(--cg-shell-bar-h) - 32px)`), contenu défilant |

## Critères d'acceptation
- [ ] CA1 `docked = true` ≥ 820 px : la bulle `.help-widget__bubble` est masquée ; le widget porte la classe `help-widget--docked`.
- [ ] CA2 La barre d'un écran de travail porte `.app-help` ; un clic bascule `panelOpen` du widget ; `aria-expanded` suit.
- [ ] CA3 Hors écran de travail (`/chat`) : pas de `.app-help`, widget non `docked`, bulle visible.
- [ ] CA4 À 1366 × 768 sur `/atelier/:id`, aucun élément du widget d'aide ne recouvre le bouton Envoyer (capture).
- [ ] CA5 Non-régression : specs du widget et de la coquille vertes ; suite frontend verte ; `ng build` vert.

## Plan de test
- `shell.component.spec.ts` : bouton d'aide présent sur `/atelier/x`, absent sur `/chat` ; clic → `panelOpen()` vrai ; `docked` transmis.
- `help-chat-widget.component.spec.ts` : `docked` pose la classe `help-widget--docked`.
- Capture headless 1366 × 768 du terminal (`captures/sf05-*`).
- Isolation utilisateur : sans objet (aucune donnée, aucun endpoint).

## Impacts
- Tables / endpoints : aucun.
- Composants : `ShellComponent` (ts, html, scss), `HelpChatWidgetComponent` (ts, html, scss).
- Préoccupation transversale **Navigation / routing** : non cochée — aucune route, aucun guard ; seule la place d'un bouton change sur les 2 chemins de SF-207-01. Auth, tenant, plans : non.
- Design system : aucune couleur ni police nouvelle.

## Hors périmètre
Le contenu du panneau d'aide, le service d'aide, la mise en page téléphone.
