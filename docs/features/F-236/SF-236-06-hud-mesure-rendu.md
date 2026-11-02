# Mini-spec — [F-236 / SF-236-06] HUD de base et mesure du rendu

## Identifiant
SF-236-06

## Feature parente
F-236 — Jarvis en 3D (cadrage §12.1, §14, §18 ; itération 1 « Jarvis s'allume »)

## Statut
Livrée (PR à la suite de cette mini-spec)

## Date de création
2026-11-02

## Branche Git
`feat/SF-236-06-hud-mesure-rendu`

---

## Objectif

Donner à l'écran 3D un HUD plein écran (barre, état, sous-titres, compteur, fil d'Ariane, volet « Réglages », plein
écran PC) sans perdre aucune commande, et mesurer — sans contenu — comment le rendu est vécu (3D ou 2D et pourquoi,
niveaux, i/s, gestes, touchers, cerveaux, entrées).

## Comportement attendu

### Cas nominal

1. **HUD 3D** (`jarvis.component`) : l'écran passe en pleine largeur ; la barre garde retour, sujet, terminal et
   ajoute le **compteur « À envoyer (n) »** (couleur de l'attente, univers + messages finis du tour), le **plein
   écran** (PC : pointeur fin et API disponible) et **« Réglages »** ; l'état (`statusLabel`), les sous-titres
   (échange) et le fil d'Ariane restent. Les commandes secondaires (mode sobre, Vue 2D, mot d'éveil, humour,
   compagnon) passent dans le **volet « Réglages »** (verre holographique §7.2, cibles ≥ 44 px) ; « Parler » reste
   en bas. En 2D, l'écran est **identique à aujourd'hui**.
2. **Mesure du rendu** (cadrage §18) : à la fermeture de l'écran, un événement `RENDER` (canal `UsageEvent`
   existant, `POST /api/jarvis/usage`) : `mode` = `3d` (la 3D a été affichée) ou `2d`, et `render` = compteurs d'un
   **vocabulaire fermé** — `r_<raison>` (repli 2D : `no_webgl`, `perf_caveat`, `reduced_motion`, `save_data`,
   `sober`, `manual`, `context_lost`, `low_perf`), `sec_high|medium|low` (secondes par niveau), `fps_*` (classe de
   l'i/s p50 de la session, 6 classes), `gestures`, `t_client|subject|chantier|step|person|core` (touchers),
   `brains` (passages au grand cerveau), `entries` / `entries_skipped`, `to2d` (bascules 3D → 2D).
3. **Backend** : migration additive `236-jarvis-usage-render.xml` sur `jarvis_usage_days` (`render_3d`, `render_2d`,
   `render_counts`) ; `RenderCounts` valide le vocabulaire et les bornes (0-86 400) ; `GET /api/admin/jarvis/measure`
   renvoie `render` = `{ sessions3d, sessions2d, share3dPct, counts }`.

### Cas d'erreur

| Cas | Comportement |
|---|---|
| Clé hors vocabulaire (un nom, un texte) | 400 `validation_error` ; rien n'est inscrit. |
| Nombre < 0 ou > 86 400 | 400. |
| Mode autre que `3d` / `2d` | 400. |
| Non-ADMIN sur la mesure | 403 (règle existante). |
| Sans jeton | 401. |
| Envoi raté (réseau) | Ignoré (best-effort, comme les autres compteurs). |
| Fermeture de l'écran deux fois | Un seul compte-rendu. |
| Plein écran refusé par le navigateur | Rien ne change. |

---

## Critères d'acceptation

- [x] CA1 — Toutes les commandes existantes accessibles (tests `jarvis.component.spec.ts` verts ; volet « Réglages »
  en 3D avec les mêmes commandes, test).
- [x] CA2 — `?sujet=` / `?alerte=` / `?source=compagnon` non régressés (tests existants verts).
- [x] CA3 — Compteurs sans contenu (vocabulaire fermé, tests unitaire + intégration) ; isolation `user_id` (test A/B :
  la mesure d'un autre compte reste à 0) ; 403 non-ADMIN (test existant).
- [x] CA4 — Préfixe byte-stable : aucune consigne, description d'outil ni consigne orale touchée (suite backend
  complète verte, tests de préfixe F-134 compris).
- [x] CA5 — Migration additive, réversible (rollback écrit), défauts 0 / chaîne vide.

## Périmètre

### Hors scope (explicite)
- Panneaux latéraux PC, tableaux flottants, chronologies (itération 3) ; vues ouvertes, replays, présentation,
  cockpit (compteurs ajoutés avec leurs SF — le vocabulaire fermé s'étend sans migration).
- Lecture de la mesure à J+7 (SF-236-27).

## Contraintes de validation
- Clés : vocabulaire fermé `RenderCounts.KEYS` ; valeurs 0-86 400 ; mode `3d` | `2d` (casse indifférente).

---

## Technique

### Endpoint(s)
- `POST /api/jarvis/usage` (existant) : nouvel événement `RENDER` (+ `mode`, `render`).
- `GET /api/admin/jarvis/measure` (existant, ADMIN) : champ `render` ajouté.

### Tables impactées
`jarvis_usage_days` (+ `render_3d` int, `render_2d` int, `render_counts` varchar(1000)) — clé (user_id, jour)
inchangée : toute écriture passe par `currentUser.requireId()`.

### Migration Liquibase
`236-jarvis-usage-render.xml` (numéro = feature, sans collision ; rollback : colonnes retirées).

### Fichiers

| Fichier | Rôle |
|---|---|
| `backend/.../jarvis/RenderCounts.java` | Pur — vocabulaire fermé, lecture, écriture, validation. |
| `backend/.../jarvis/JarvisUsageDay.java`, `JarvisUsageService.java` | Colonnes, `addRender`. |
| `backend/.../jarvis/JarvisAdoptionService.java`, `JarvisAdoptionController.java` | Événement `RENDER`, `RenderMeasure`. |
| `frontend/.../stage3d/jarvis-render-stats.ts` | Pur — collecte de la session (`RenderStats`), classes d'i/s. |
| `frontend/.../stage3d/engine/jarvis-engine.ts`, `jarvis-gestures.ts` | Histogramme d'i/s sans allocation, gestes commencés. |
| `frontend/.../stage3d/jarvis-stage-3d.component.ts` | Collecte (décision, niveaux, touchers, cerveaux, entrée), `report()`. |
| `frontend/.../core/services/jarvis.service.ts` | `render(report)`. |
| `frontend/.../jarvis/jarvis.component.{ts,html,scss}` | HUD 3D (compteur, plein écran, « Réglages »), envoi à la fermeture. |

## Plan de test
- Backend unitaire : `RenderCountsTest` (lecture tolérante, écriture triée, validation, raisons couvertes).
- Backend intégration : `JarvisAdoptionApiIntegrationTest.renderMeasure` (3D / 2D, part, détail, refus de contenu,
  isolation A/B) ; `errors` (403 / 401).
- Frontend : `jarvis-render-stats.spec.ts` ; `jarvis-stage-3d.component.spec.ts` (compte-rendu 3D unique) ;
  `jarvis.component.spec.ts` (compte-rendu 2D à la fermeture, une fois ; volet « Réglages » en 3D, rien en 2D).
- Suite complète backend et frontend.

## Dépendances
SF-236-05 (livrée, #1463).

## Préoccupations transversales

| Préoccupation | Cochée ? | Composants impactés |
|---|---|---|
| Contexte tenant | **Oui** | `JarvisAdoptionController.record` / `measure` : `currentUser.requireId()` (inchangé) ; `JarvisUsageService.addRender` écrit la journée du compte appelant ; test A/B. |
| Mesure | **Oui** | Migration additive, événement `RENDER`, mesure admin étendue. |
| Auth / Principal | Non | Règle ADMIN existante réutilisée. |
| Navigation / routing | Non | Aucune route ni paramètre. |
| Plans / limites | Non | Aucun appel IA. |

## Notes et décisions

| # | Arbitrage | Pourquoi | Alternative écartée | Réversible |
|---|---|---|---|---|
| A1 | Une colonne de détail `render_counts` à vocabulaire fermé (+ deux compteurs de sessions) plutôt qu'une colonne par compteur. | Les itérations suivantes ajoutent des compteurs (vues, replays, cockpit) sans migration ; le vocabulaire fermé garantit l'absence de contenu. | 25 colonnes, une migration par itération. | Oui. |
| A2 | Un compte-rendu par ouverture, envoyé à la fermeture de l'écran. | Une seule requête par session ; les secondes par niveau et l'i/s p50 n'ont de sens qu'à la fin. | Un événement par changement (bavard). | Oui. |
| A3 | Commandes secondaires dupliquées dans le volet « Réglages » (mêmes méthodes), originaux masqués en 3D. | L'écran 2D reste identique (gelé) ; aucune commande perdue en 3D. | Déplacer les commandes pour tous (régression 2D). | Oui. |
| A4 | Compteur « À envoyer » en couleur d'attente (cyan, goût PO V1). | Un message à envoyer attend le PO (grammaire §23). | Orange (couleur normale). | Oui. |
| A5 | HUD non capturé au banc. | Le HUD est le DOM de `/jarvis` (session requise) ; testé en composant. | Faux écran de banc. | — |
