# Mini-spec — [F-236 / SF-236-26] Carte d'activité 7 jours

## Identifiant
SF-236-26

## Feature parente
F-236 — Jarvis en 3D (cadrage §13 NF-16, §14, §15 ; itération 4 « Au-delà »)

## Statut
done

## Date de création
2026-11-02

## Branche Git
`feat/SF-236-26-activite-7j`

---

## Objectif

Savoir où est passé le temps cette semaine : chaque client s'entoure d'un anneau à 7 segments dont l'éclat suit le
nombre de tours du jour, à partir d'un agrégat **en lecture seule** sans contenu.

## Comportement attendu

### Cas nominal

1. **Backend — `GET /api/jarvis/activity?days=7&tz=Europe/Paris`** (`JarvisActivityController` /
   `JarvisActivityService`) : `atelierAccess.requireRunnerAccess()` puis `currentUser.requireId()` ; tours = messages
   `USER` de `atelier_messages` **filtrés `user_id`** depuis le début du premier jour (nouvelle requête de projection
   `AtelierMessageRepository.userTurnsSince`, `[workspaceId, createdAt]` seulement) ; ventilés par jour dans le
   fuseau demandé ; restreints aux sujets du compte (`WorkspaceService.list(userId)`, terminal Teams exclu).
   Réponse : `{ days: ["2026-10-27", …], subjects: [{ subjectId, turns: [7 entiers] }] }` — sujets avec au moins un
   tour seulement. **Aucun contenu**.
2. **Bornes** : `days` ∈ [1, 7] (borné, jamais d'erreur) ; au plus 12 × 40 sujets (les plus actifs si coupe) ;
   `tz` illisible ⇒ Europe/Paris.
3. **Mesure** : clés fermées `RenderCounts.KEYS` étendues — `replays`, `presentations`, `cockpits`, `nights`,
   `activity` (vues de l'itération 4) ; comptées côté écran (`RenderStats.view`).
4. **Front** : section « Activité · 7 jours » du cockpit (panneau droit PC ; volet « Aujourd'hui » au téléphone) :
   lue **à la demande, hors tour**, dans le fuseau de l'appareil (`JARVIS_ACTIVITY`) ; une ligne par client
   (7 pastilles, total, libellé accessible « CAGIP : lun. 3, … ») ; en 3D, **anneaux à 7 segments** autour des
   clients posés (effet `activity`, le plus ancien en haut puis sens horaire, éclat ∝ √(tours / jour le plus chargé),
   segment vide à peine visible) ; second toucher ⇒ retirés. Ni en mode sobre ni en présentation.

### Cas d'erreur

| Cas | Comportement |
|---|---|
| Non authentifié | 401. |
| Sans droit runner | 403 (garde existante). |
| Réponse illisible / erreur | « L'activité n'a pas pu être lue. » ; aucun anneau. |
| Sujet inconnu de l'univers | Ignoré (rien d'inventé). |

---

## Critères d'acceptation

- [x] CA1 — Test d'isolation A/B (Bob ne voit que ses sujets, jamais ceux d'Alice).
- [x] CA2 — Bornes : `days` ∈ [1, 7], 12 × 40 sujets, fuseau illisible ⇒ Europe/Paris (test).
- [x] CA3 — Aucun contenu dans la réponse : ni texte de message, ni nom de sujet (test).
- [x] CA4 — Préfixe byte-stable : aucune consigne, description d'outil ni prompt touché (suite backend verte,
  test F-134 inclus).
- [x] CA5 — Front : anneaux = agrégat (tests purs), à la demande hors tour, illisible ⇒ message (tests composant).

## Plan de test

- Intégration backend : `JarvisActivityApiIntegrationTest` (ventilation par jour, réponses et Teams exclus, A/B,
  bornes, 401, aucun contenu, clés de mesure).
- Unitaires front : `hud/jarvis-activity.spec.ts` (somme par client, éclat, sujets inconnus, illisible, libellés,
  `RenderStats.view`).
- Composant : `jarvis-cockpit.component.spec.ts` (à la demande hors tour, anneaux posés, vue comptée, retrait ;
  illisible).
- Isolation utilisateur : A/B ci-dessus.

## Périmètre

### Impacté
Backend : `jarvis/activity/{JarvisActivity,JarvisActivityService,JarvisActivityController}.java`,
`atelier/AtelierMessageRepository.java` (`userTurnsSince`), `jarvis/RenderCounts.java` (clés). Front :
`hud/jarvis-activity.ts`, `hud/jarvis-cockpit.component.{ts,html,scss}` (`JARVIS_ACTIVITY`),
`core/services/jarvis.service.ts` (`activity`), `jarvis-render-stats.ts` (`view`), `jarvis-stage-3d.component.ts`
(`setActivity`, `countView`), `jarvis-stage-director.ts` (`StagePlan.activity`), `engine/jarvis-engine.ts`,
`engine/jarvis-effects-3d.ts`, `scenes3d/jarvis-scene3d.model.ts`, `jarvis.component.ts` (vues comptées).

### Hors scope (explicite)
- Aucune table, aucune migration (lecture d'une table existante ; `render_detail` garde son format).
- Durée des tours (seulement leur nombre).

## Préoccupations transversales

| Préoccupation | Cochée | Composants impactés |
|---|---|---|
| Contexte tenant | Oui | `JarvisActivityController` : `currentUser.requireId()` seul ; requête `userTurnsSince` filtrée `user_id` ; intersection avec `WorkspaceService.list(userId)`. Lectures existantes (`universe`, `watches`, historique) inchangées. Test A/B. |
| Mesure | Oui | `RenderCounts.KEYS` étendu (additif) ; `POST` de mesure existant inchangé ; test des clés. |
| Navigation / routing | Non | Aucune route. |

## Notes et décisions

| # | Arbitrage | Pourquoi | Alternative écartée | Réversible |
|---|---|---|---|---|
| A1 | Ventilation par jour en Java (fuseau de l'appareil), pas en SQL. | Portable H2 / PostgreSQL ; au plus 7 jours de lignes `[id, date]`. | `date_trunc` SQL. | Oui. |
| A2 | Un tour = un message de l'utilisateur (tapé ou dit). | Le plus simple et fidèle ; aucune durée inventée. | Compter les réponses. | Oui. |
| A3 | Éclat ∝ racine carrée, relatif au jour le plus chargé. | Un jour à un tour reste visible. | Linéaire. | Oui. |
| A4 | Clés de mesure des vues de l'itération 4 ajoutées ici. | Seule SF de l'itération qui touche la passerelle ; vocabulaire fermé. | Une SF dédiée. | Oui. |
