# Mini-spec — F-143 / SF-143-01 — Ce que chaque projet a coûté

## Identifiant
`F-143 / SF-143-01` — feature parente `F-143`

## Objectif
Savoir, projet par projet, **combien il a coûté** — cette semaine et depuis l'origine.

## Ce qui existe, et ce qui manque
Vérifié avant d'écrire :

| Existe | Manque |
|---|---|
| La dépense réelle **par client** (poste), par semaine ou par mois — F-133 / SF-133-03 | La dépense réelle **par projet** |
| Un agrégat par poste **et projet**… mais **en jetons** (`aggregateByHostAndWorkspace`, F-16) | …le même **en coût réel** (`provider_cost_usd`) |
| Le coût de **chaque réponse** (SF-133-02) | Le **cumul** d'un projet |

Un client porte plusieurs projets. Savoir lequel coûte est ce qui permet d'arbitrer — et aujourd'hui
c'est le seul grain qui manque entre la réponse et le client.

## Comportement attendu
1. Pour chaque projet d'un poste : la dépense de la **semaine en cours** et le **total depuis
   l'origine**.
2. Affiché là où l'on choisit un projet — la **tuile de projet** dans la Forge — et dans la barre du
   **terminal** pour le projet ouvert.
3. Un projet sans dépense affiche **0,00 €** : l'information est vraie et utile (il n'a rien coûté).
4. **Réservé à l'administrateur** : pour les autres, la route ne rend rien et l'écran n'affiche rien.
5. Les montants sont convertis en euros **à la lecture**, au taux configuré — comme partout dans
   F-133, pour qu'un vieux relevé ne mente pas quand le change bouge.

| Cas d'erreur | Comportement |
|---|---|
| API en échec | rien ne s'affiche, aucune erreur — c'est un indicateur |
| Projet supprimé depuis | absent de la réponse ; rien ne s'affiche |
| Tours antérieurs à F-133 | comptés **pour zéro** en coût (leur `provider_cost_usd` est nul), comme partout ailleurs — leur substituer une estimation donnerait un montant crédible et faux |

## Critères d'acceptation
- [ ] `GET /api/admin/cost/projects` rend, par projet : identifiant, nom, poste, dépense de la semaine, dépense totale.
- [ ] Les deux montants viennent d'**une seule requête** par fenêtre — aucun cumul recalculé côté navigateur.
- [ ] Un projet sans dépense est rendu à **zéro**, et non omis.
- [ ] Un compte ne voit **jamais** les projets d'un autre (isolation `user_id`, testée).
- [ ] Un non-administrateur reçoit **403** et l'écran n'affiche rien.
- [ ] La tuile de projet affiche les deux montants ; le terminal affiche le total du projet ouvert.
- [ ] Une erreur d'API n'affiche rien et ne casse pas l'écran.

## Hors scope
Le coût par **mois** pour un projet (la semaine et le total suffisent à arbitrer) · un budget **par
projet** — le budget reste par client (SF-133-04) · tout graphique.

## Technique
| Élément | Changement |
|---|---|
| `UsageTurnRepository` | agrégat de `provider_cost_usd` **par `workspace_id`**, sur une fenêtre et sans borne |
| **`ProjectCostService`** *(nouveau)* | assemble les deux fenêtres et les noms de projets |
| **`ProjectCostView`** *(nouveau)* | le DTO |
| `CostSummaryController` *(ou voisin)* | `GET /admin/cost/projects` |
| **`ProjectCostComponent`** *(nouveau)* | l'affichage, deux densités |
| `forge-project-tile`, `atelier-terminal` | l'insèrent |

Aucune table, aucune migration.

## Plan de test
- [ ] Agrégat : deux projets, deux dépenses, la bonne fenêtre.
- [ ] Projet sans dépense ⇒ zéro, présent.
- [ ] Isolation : deux comptes, deux projets homonymes.
- [ ] 403 pour un non-administrateur.
- [ ] Écran : affichage, absence sur erreur.

## Préoccupations transversales
| Préoccupation | Cochée | Composants impactés |
|---|---|---|
| Auth / Principal | non | — |
| **Contexte tenant** | **oui** | L'agrégat filtre `user_id` **dans la requête SQL** (jamais après coup), et le nom des projets est relu par `WorkspaceService` déjà borné au propriétaire. Aucun identifiant de projet ne vient de l'appelant. |
| Plans / limites | non | lecture seule ; aucun budget par projet n'est introduit |
| **Navigation / routing** | **oui** *(deux écrans)* | Tuile de projet et barre du terminal reçoivent un composant de plus ; aucune route d'écran ne change. Les specs des deux écrans sont complétés, comme pour SF-133-15. |
