# Mini-spec — [F-208 / SF-208-03] L'overlay des chantiers

## Identifiant
`F-208 / SF-208-03` — Feature parente `F-208` (Le tableau des chantiers) — Statut `in-review` — Créée 2026-10-24
Branche : `feat/SF-208-03-overlay-des-chantiers` — cadrage : décisions 5 à 7. Dépend de SF-208-02 (mergée : API `chantiers[].plan`, route `versions`, SSE `journey`).

## Objectif
Un overlay, ouvert depuis la barre d'état du terminal, montre chaque chantier ouvert, ses phases, son avancement et toutes ses étapes avec leur état, leurs sous-étapes, leurs preuves et l'historique du plan, et se met à jour pendant le tour.

## Comportement nominal
- **Ouverture** : bouton « Chantiers » dans la barre d'état F-207 (`.terminal-dock__bar`), présent dès qu'un chantier est ouvert, avec un **badge** = nombre de gestes attendus de l'utilisateur sur l'ensemble des chantiers ouverts (plan à valider, diagnostic à confirmer, clôture proposée, étapes qui attendent une attente ouverte). Même entrée « Tableau des chantiers » dans le menu ⋯ de l'en-tête.
- **Overlay** (pas une page, aucune route) : panneau en surimpression `position: fixed` (modèle `terminal-actions-panel`), îlot clair, plein écran sous 900 px. Fermeture par le bouton, **Échap** ou un **clic dehors** (voile).
- **Onglets** : un par chantier ouvert, **focus en premier**, puis par numéro ; pastille « à vous » sur un onglet qui attend un geste.
- **Par chantier** : titre ; **frise des phases** Investigation → Plan → Exécution → Vérification → Clos (passées cochées, courante en évidence) ; **anneau** « 5/14 » (faites ou vérifiées / total) ; version du plan (« Plan v3 · validé » / « en attente de validation »).
- **Étapes (toutes)** : icône + libellé d'état — vérifiée, faite, en cours (sous-étape faite), bloquée (« après 4, 6 », « attente cert-gino »), à faire, échec — titre, risque, mention « modifiée » sur un amendement. Une étape se **déplie** : sous-étapes cochées avec preuve, vérification, retour arrière, preuve de l'étape. L'**étape en cours** (première non faite et débloquée, ou entamée) est dépliée d'office.
- **Historique** : « Historique du plan » repliable ; à l'ouverture, `GET …/chantiers/{n}/versions` ; chaque version « v2 · 5 étapes » avec les étapes ajoutées (+), retirées (−), modifiées (~). Relu quand la version du plan change.
- **Direct** : l'événement SSE `journey` (SF-208-02) signale un changement ; le terminal qui tourne relit `GET /journey` (le parcours est partagé avec la bande et la barre), l'overlay suit sans geste.
- Charte : tokens existants seulement (`--cg-success`, `--cg-error`, `--cg-accent`, `--cg-orange-2`, `--cg-text-*`, `--cg-divider`, `--cg-surface`), polices de charte, espacements multiples de 4 px.

## Cas d'erreur
| Situation | Comportement attendu |
|---|---|
| Aucun chantier ouvert | pas de bouton ; overlay ouvert alors : « Aucun chantier ouvert sur ce sujet. » |
| Chantier sans plan | « Pas encore de plan » + la phase |
| Historique illisible (erreur réseau) | « L'historique n'a pas pu être lu. » dans la section, rien d'autre ne change |
| Historique vide (plans d'avant F-208) | « L'historique est conservé à partir de la prochaine version du plan. » |
| Événement `journey` d'un tour d'un autre terminal | ignoré par les terminaux qui ne tournent pas |
| Backend d'avant F-208 (pas de `plan` par chantier) | onglet affiché avec « Pas encore de plan » ; aucune erreur |

## Critères d'acceptation
- [ ] CA1 Le bouton « Chantiers » apparaît dans la barre d'état dès qu'un chantier est ouvert, avec le badge du nombre de gestes attendus (0 ⇒ pas de badge).
- [ ] CA2 L'overlay se ferme par Échap, par le bouton et par un clic sur le voile.
- [ ] CA3 Onglets : focus en premier ; chaque onglet montre frise, anneau « faites/total » et la liste complète des étapes.
- [ ] CA4 États : vérifiée, faite, en cours, bloquée (avec ce qui bloque), à faire, échec — chacun avec son icône et son libellé.
- [ ] CA5 L'étape en cours est dépliée d'office ; une étape se déplie au clic (sous-étapes, preuves, vérification, retour arrière).
- [ ] CA6 L'historique se lit à la demande et liste ajouts / retraits / modifications.
- [ ] CA7 Un événement SSE `journey` pendant le tour relit le parcours (l'overlay se met à jour).
- [ ] CA8 Charte : aucune couleur ni police nouvelle ; suite frontend et `npm run build` verts.

## Plan de test
- `terminal-chantiers-board.component.spec.ts` : onglets (focus d'abord), anneau, états et libellés, blocage, dépliage d'office et au clic, historique (succès, vide, erreur), fermeture Échap / voile / bouton.
- `journey.models` : `stepState`, `chantierGestures`, `currentStepIndex` (fonctions pures).
- `atelier.service` : l'événement `journey` incrémente le signal partagé.
- `atelier-terminal` : bouton + badge dans la barre, entrée du menu, relecture du parcours sur `journey` pendant un tour.
- Isolation utilisateur : sans objet côté écran (routes déjà isolées, SF-208-02 ; aucune donnée d'un autre compte n'est lisible).

## Préoccupations transversales
- Navigation / routing : **non** — aucune route ajoutée, overlay interne au terminal. Auth, tenant, plans : non.

## Impacts
- Nouveau : `atelier/terminal/terminal-chantiers-board.component.ts` (+ spec).
- Modifiés : `core/models/journey.models.ts`, `core/services/journey.service.ts` (`versions`, signal `liveChanges`), `core/services/atelier.service.ts` (événement `journey`), `atelier-terminal.component.{ts,html}` (bouton, badge, menu, overlay, relecture), `atelier-terminal-dock.component.scss`, `terminal-dock.ts` (`chantiersBadge`).

## Hors périmètre
Boutons d'action (SF-208-04). Édition du plan, glisser-déposer.
