# Mini-spec — [F-236 / SF-236-03] Les deux cerveaux

## Identifiant
SF-236-03

## Feature parente
F-236 — Jarvis en 3D (cadrage §10, D8 ; itération 1 « Jarvis s'allume »)

## Statut
Livrée (PR à la suite de cette mini-spec)

## Date de création
2026-11-02

## Branche Git
`feat/SF-236-03-deux-cerveaux`

---

## Objectif

Montrer quel cerveau travaille : quand Opus (le cerveau principal) est sollicité, un champ neuronal violet se déploie
autour du petit cœur (le cerveau rapide, qui reste le noyau et continue d'écouter), respire pendant le travail, puis se
replie à la fin.

## Comportement attendu

### Cas nominal

1. **Machine d'état** (`jarvis-brains.ts`, pur, `brainsFor`) :
   - cerveau rapide actif (`fastBrainOn`) : déployé tant qu'une **délégation** vit (n → n+1 déploie ; la dernière
     finie replie) ;
   - coupe-circuit (`fastBrainOn` faux, situation actuelle en production) : **chaque tour est un tour Opus** ⇒
     déployé pendant `turnActive` — la scène est donc visible dès aujourd'hui ;
   - une perle par délégation vivante (1 pendant un tour Opus direct, ≤ 6).
2. **Aller T-B1 (1,6 s)** : inspiration 0-200 ms (petit cœur 1 → 0,85, lumière −20 %) · étincelle 200-450 ms (flash
   au centre, onde de choc violette, bloom 1,6) · éclosion 450-1 300 ms (particules du rayon 1,4 au rayon 3,2 avec
   retard par particule, connexions révélées du centre vers l'extérieur, teinte → violet, anneaux 3 → 5, fond et sol
   qui virent au violet, caméra qui recule de 15 %) · stabilisation 1 300-1 600 ms (rebond 1,03 → 1, bloom 1,3).
3. **Régime T-B2** : respiration 3 s, rotation lente, **éclairs synaptiques** permanents sur les connexions, perles
   en orbite, enveloppe de Fresnel ; le noyau garde ses états d'écoute / parole (SF-236-02) au centre.
4. **Retour T-B3 (1,2 s)** : rassemblement 0-300 ms (éclairs qui convergent vers le centre) · repli 300-900 ms ·
   dépôt 900-1 200 ms (flash doux, la perle entre dans le noyau).
5. **Variantes** : annulé (« annule », « stop ») ⇒ repli 0,8 s sans éclat, perle grise ; échec (délégation ou tour
   en erreur) ⇒ perle rouge, repli sans flash.
6. Budgets : PC 12 000 particules / 8 000 connexions ; téléphone 2 500 / 1 500 ; Bas 1 200 / 600 ; connexions aux
   plus proches voisins par grille spatiale (jamais O(n²)), précalculées une fois ; zéro allocation par image.

### Cas d'erreur

| Cas | Comportement |
|---|---|
| Aller interrompu par la fin du travail | Le retour part du déploiement atteint (aucun saut, aucun retour au début). |
| Retour interrompu par une nouvelle délégation | L'aller reprend là où l'éclosion en est. |
| Délégations > 6 | 6 perles. |
| Ouverture de l'écran pendant un travail Opus | Cerveau posé déployé, sans rejouer l'aller (l'écran apparaît en fondu). |
| Écran en 2D | Rien de nouveau (la liste « Recherches en cours » existante reste). |

---

## Critères d'acceptation

- [x] CA1 — Machine d'état testée : délégation n → n+1 ⇒ déploiement ; dernière finie ⇒ repli ; annulation ⇒ repli
  0,8 s sans flash ; `fastBrainOn=false` ⇒ déploiement à `turnActive`.
- [x] CA2 — Phases aux temps prévus (tests) ; séquences de 6 captures aller + 6 retour
  (`docs/features/F-236/captures/cerveaux/`), plus le régime et l'annulation.
- [x] CA3 — Aucun saut de propriété (test du pas maximal par image) ; interruptions dans les deux sens.
- [ ] CA4 — ≥ 50 i/s p50 sur le téléphone du PO pendant l'aller : mesure sur l'appareil réel à faire (rendu logiciel au
  banc) ; la qualité adaptative (SF-236-02) protège le plancher.

## Périmètre

### Hors scope (explicite)
- Salve d'éclairs du grand cerveau **vers l'entité** à chaque action (`pulses`) : il faut l'univers 3D (SF-236-04).
- Panneau « Cerveau principal · <libellé> » (T-P1) : panneaux holographiques en SF-236-08 ; la liste DOM « Recherches
  en cours » (F-235) reste affichée.
- Comète de la demande, perle cyan en attente d'annonce (file F-235) : itération 2.
- Aucun appel IA, aucune consigne touchée ; aucun endpoint, aucune table.

## Contraintes de validation
- Perles : 0-6. Délégations négatives impossibles (longueur d'une liste).

---

## Technique

| Fichier | Rôle |
|---|---|
| `stage3d/jarvis-brains.ts` | Pur — `brainsFor`, `unfoldFrame`, `foldFrame`, `deployedFrame`, `BrainsClock`, `nearestPairs`. |
| `stage3d/engine/jarvis-brain-3d.ts` | Moteur — champ, connexions à éclairs, enveloppe, gyroscope, onde de choc, perles. |
| `stage3d/engine/jarvis-engine.ts` | Horloge des cerveaux, noyau (échelle, lumière, flash), fond / sol violets, recul caméra. |
| `stage3d/jarvis-stage-director.ts` | Plan : `brains`. |
| `jarvis.component.ts` | `stageSignals` + délégations, `fastBrainOn`, `turnActive`, `brainEnd` (fin : normal / annulé / échec). |
| `stage3d/jarvis-banc.ts` | Scènes `cerveau-aller`, `cerveau`, `cerveau-retour`, `cerveau-annule`. |

Endpoints, tables, migration : aucun.

## Plan de test
- `jarvis-brains.spec.ts` : machine d'état, phases aller / retour, variantes, pas maximal, interruptions, connexions
  (déterministes, sans doublon, bornées, proches).
- `jarvis-stage-3d.component.spec.ts` : délégation ⇒ déployé, fin annulée ⇒ replié `cancel`, coupe-circuit ⇒ tour Opus,
  plan identique non renvoyé.
- Visuel : 32 captures au banc (aller, retour, régime, annulation ; téléphone + PC).
- Isolation : sans objet (aucune donnée, aucun appel réseau).

## Dépendances
SF-236-02 (livrée, #1460).

## Préoccupations transversales
Aucune cochée (ni auth, ni tenant, ni limites, ni navigation).

## Notes et décisions

| # | Arbitrage | Pourquoi | Alternative écartée | Réversible |
|---|---|---|---|---|
| A1 | Fin « annulé » = `stop`/« annule » ; « échec » = délégation `failed` ou erreur du tour ; posée par l'écran dans `brainEnd`. | Ce sont les seuls signaux existants qui disent comment le travail finit ; rien n'est ajouté au flux. | Déduire la fin du texte de la réponse. | Oui. |
| A2 | Le surcroît de bloom des cerveaux s'**ajoute** à celui du cœur. | Le cœur en veille garde sa lumière basse ; les cerveaux n'imposent pas un plancher. | Maximum des deux (la veille resterait lumineuse). | Oui. |
| A3 | Lumière du champ normalisée par le nombre de particules (`√(1 200 / n)`). | 12 000 points additifs saturent l'image en blanc sur PC. | Même éclat par point (écran blanc). | Oui. |
| A4 | Nébuleuse du décor atténuée (0,30 → 0,16). | Sa tache la plus claire passait le seuil du bloom et faisait des traînées roses sous le cœur. | Seuil de bloom plus haut (cœur plus terne). | Oui. |
| A5 | Ouverture pendant un travail : cerveau posé déployé, sans rejouer l'aller. | L'écran apparaît déjà en fondu ; rejouer l'éclosion mentirait sur le moment du début. | Rejouer l'aller. | Oui. |
