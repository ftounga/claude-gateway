# Mini-spec — F-234 / SF-234-11 — Synchronisation voix, mode sobre, mesure (D9, D13, D16)

## Identifiant
`F-234 / SF-234-11`

## Feature parente
`F-234` — L'écran vivant de Jarvis (cadrage D9, D13, D16, §14 ; vague C)

## Statut
`in-review`

## Date de création
2026-11-01

## Branche Git
`feat/SF-234-11-voix-sobre-mesure`

---

## Objectif

> Ce qu'on voit suit ce qu'on entend (nom surligné pendant la phrase dite, scène figée puis effacée à une coupure),
> le PO peut réduire l'écran à l'orbe et au fil d'Ariane, et l'intérêt de l'écran vivant se mesure sans contenu.

## Comportement attendu

- **D13 — voix** : chaque phrase 🎙 mise en file (F-233 / SF-233-03) porte un crochet `onPlaying` : quand elle
  **commence à jouer**, les noms qu'elle contient s'allument et sont **soulignés** (`said`) ; la phrase suivante
  remplace la surbrillance. **Coupure** (SF-233-08, `cut`) : la scène se **fige** (atténuée, rien ne la remplace)
  1 s, puis s'efface. L'autorisation (S2) n'est jamais figée.
- **D9 — mode sobre** : interrupteur « Mode sobre / Écran complet » sous le statut (cible ≥ 44 px), gardé **par
  appareil** (`localStorage` `jarvis.sobre`, lu et écrit sous try/catch : sans stockage, le choix vaut pour
  l'ouverture) ; sobre ⇒ constellation et scènes retirées, **orbe et fil d'Ariane seuls**.
- **D16 — mesure** (aucun contenu) : par tour, l'écran déclare `POST /api/jarvis/usage {event:"SCENES", scenes:
  {S3:2, GENERIQUE:1…}, lit, shows}` (rien pour un tour sans scène) ; `TOUCH` à chaque toucher de sujet ; `SOBER` à
  l'activation du mode sobre. Migration **234** additive sur `jarvis_usage_days` : `scenes_shown`, `scenes_generic`,
  `scene_counts` (« S3=12;S5=4 », identifiants de scène seulement), `nodes_lit`, `node_touches`, `sober_on`,
  `show_requests`. `GET /api/admin/jarvis/measure` (ADMIN, filtré `user_id`) expose `scenes { shown,
  genericSharePct, perScene, nodesLit, nodeTouches, soberOn, showRequests }`.
- **Validation** : un identifiant de scène doit matcher `^[A-Z][A-Za-z0-9]{0,11}$` (un nom de personne est refusé,
  400) ; nombres 0-500 ; au plus 40 scènes distinctes.

### Cas d'erreur
| Situation | Comportement |
|---|---|
| Stockage local qui lève | écran complet par défaut, interrupteur fonctionnel pour l'ouverture |
| Déclaration avec identifiant non conforme ou nombre hors bornes | 400, rien d'inscrit |
| Envoi de mesure en échec | ignoré (best-effort) |
| Coupure pendant une autorisation | S2 reste |

## Critères d'acceptation
1. Coupure ⇒ scène figée ≤ 1 s puis effacée.
2. Nom surligné pendant la phrase qui le contient.
3. Mode sobre persistant, rendu correct si `localStorage` lève.
4. Compteurs (dont part de S-GEN) incrémentés sans contenu ; `GET /api/admin/jarvis/measure` les expose ; isolés par compte.

## Plan de test
- Front `jarvis-voix-sobre.spec.ts` : coupure (figée, rien ne remplace, effacée), phrase dite, mesure (ids seulement,
  remise à zéro), mode sobre (DOM, persistance, stockage qui lève). `jarvis.component.spec.ts` : crochet de phrase.
- Back `SceneCountsTest` (lecture, écriture, validation) ; `JarvisAdoptionApiIntegrationTest.scenesMeasure`
  (SCENES/TOUCH/SOBER, 400 sur nom, mesure, isolation A/B). Suite backend complète.

## Impacts
- **Table** : `jarvis_usage_days` + 7 colonnes (migration `234-jarvis-usage-scenes.xml`, rollback fourni).
- **Endpoints** : `POST /api/jarvis/usage` (événements `SCENES`, `TOUCH`, `SOBER` ; champs additifs) ;
  `GET /api/admin/jarvis/measure` (champ `scenes` additif).
- **Back** : `JarvisUsageDay`, `JarvisUsageService`, `JarvisAdoptionService`, `JarvisAdoptionController`,
  `SceneCounts` (nouveau). **Front** : lecteur, fil, constellation, scène, `jarvis.component.ts|html|scss`,
  `jarvis.service.ts`.

## Préoccupations transversales
- Contexte tenant : écriture et lecture par `currentUser.requireId()` (inchangé) ; test d'isolation.
- Auth / plans / navigation : non.

## Arbitrages (réversibles)
- Détail par scène dans une colonne texte bornée (« S3=12;… ») plutôt qu'une colonne par famille (le registre évolue).
- Numéro de migration = numéro de feature (234), comme F-235 (évite les collisions entre vagues parallèles).
- Mesure envoyée en fin de tour (un appel), pas à chaque scène.

## Hors périmètre
Écran d'administration de la mesure (le JSON suffit à J+7/J+14) ; profilage par image (non mesurable en CI).
