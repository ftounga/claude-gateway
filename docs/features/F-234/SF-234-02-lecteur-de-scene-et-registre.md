# Mini-spec — F-234 / SF-234-02 — Lecteur de scène, registre et scène générique (D1, D2, D3, D14)

## Identifiant
`F-234 / SF-234-02`

## Feature parente
`F-234` — L'écran vivant de Jarvis (cadrage `CADRAGE-F-234-ecran-vivant-jarvis.md`, vague A « socle »)

## Statut
`in-review`

## Date de création
2026-11-01

## Branche Git
`feat/SF-234-02-lecteur-de-scene`

---

## Objectif

> Pendant un tour, l'écran `/jarvis` traduit chaque action de l'agent en une **scène** (aujourd'hui la scène
> générique) et allume les noms de l'univers qui passent dans le texte — sans aucun appel réseau ni modèle.

## Comportement attendu

- **Registre unique** `frontend/src/app/jarvis/scenes/jarvis-scene-registry.json` : `tools` (nom exact), `prefixes`
  (`teams_`, `radar_`, `video_`), `steps` (types d'étape `read`, `bash`, `web`…) → `{ scene, cible, libelle, pluriel }`.
  Toutes les entrées du §5 du cadrage y sont, en `GENERIQUE` en attendant leur scène dédiée (`cible` = la scène
  prévue). Recherche : exact → préfixe le plus long → type d'étape → générique (nom humanisé).
- **Lecteur pur** `JarvisSceneReader` (aucune horloge, aucun réseau) : `action`, `text`, `confirm`,
  `confirmResolved`, `done`, `tick(now)` → `{ lit (≤ 3 nœuds), scene, pulses, toSendAdded }`.
  - Rapprochement de noms `NameIndex` (D2) : normalisation casse/accents/apostrophes, frontière de mot, ≥ 3 car.,
    mots courants exclus (`web`, `api`, `test`…), nom ambigu (deux nœuds) ⇒ rien ; le plus long nom gagne ;
    « chantier N » ⇒ chantier N du dernier sujet cité, sinon du sujet courant.
  - Priorité action (2) > ligne 🎙 (1) > corps (0) ; au plus 3 nœuds ; extinction à 6 s.
  - Rafale du même outil à < 2,5 s ⇒ une scène, compteur (« 10 fichiers lus » via `pluriel`, sinon « × n »).
  - Une scène reste au moins 1,2 s ; la plus récente gagne ensuite. L'autorisation (`confirm`) passe devant et y
    reste jusqu'à sa réponse.
  - Blocs ` ```mail ` / ` ```message ` reconnus au fil des deltas : « Mail en préparation », puis `À :`, `Objet :`,
    puis « Mail prêt · à envoyer » (et `toSendAdded` +1). **Le corps n'est ni lu ni rendu.**
  - Détail d'une ligne, 80 car., **secrets masqués** (D11 : `Authorization:`, `Bearer`, `password=`, `sk-…`, jetons
    GitHub/Slack, clés AWS).
- **Fil des scènes** `JarvisSceneBus` (racine) : lit l'univers (SF-234-01) à l'ouverture, **jamais pendant un
  tour**, et le relit à chaque `done` ; horloge sur `requestAnimationFrame`, arrêtée quand rien n'est à l'écran.
- **Scène générique rendue** `app-jarvis-scene` sous le statut de l'orbe : libellé, rafale, entité en surbrillance
  (cible reconnue, sinon sujet courant), détail mono masqué ; `aria-hidden` (l'équivalent accessible arrive avec le
  fil d'Ariane de SF-234-03) ; mouvement réduit respecté.
- **Point d'accroche minimal** dans `jarvis.component.ts` (coordination F-233) : le composant relaie au fil
  (`setSubject`, `turnStarted`, `action`, `text`, `done`, `aborted`, `confirm`, `confirmResolved`) les événements
  qu'il reçoit déjà ; une balise `<app-jarvis-scene>` dans le gabarit. Rien d'autre n'y change.
- **Gouvernance** : ligne ajoutée à `project-governance/checklists/review-checklist.md` (Architecture — BLOQUANT) :
  tout outil ajouté déclare sa scène au registre (D17, §12).

### Cas d'erreur
| Situation | Comportement |
|---|---|
| Univers illisible (500, réseau) | scènes génériques quand même, aucun nom allumé |
| Outil inconnu du registre | scène générique, nom humanisé |
| Nom ambigu ou exclu | rien ne s'allume |
| Écran de test sans client HTTP | aucune lecture d'univers, scènes intactes |

## Critères d'acceptation
1. Outil absent du registre ⇒ S-GEN avec nom humanisé ; `teams_read_conversation` ⇒ « Teams · read conversation ».
2. « CAGIP » allume le client ; « web » n'allume rien ; un nom présent dans deux sujets n'allume rien ;
   « chantier 3 » allume le bon losange.
3. Bloc mail sur 5 deltas ⇒ « en préparation », À, Objet, puis « prêt » ; corps jamais dans l'état.
4. 10 `read` en 1 s ⇒ une scène « 10 fichiers lus ».
5. Aucun appel HTTP pendant le tour ; une relecture à la fin (espion `HttpTestingController`).
6. La ligne de checklist est présente.

## Contraintes de validation
Bornes en constantes (`MAX_LIT=3`, `LIT_MS=6000`, `MIN_SCENE_MS=1200`, `BURST_MS=2500`, `SCENE_TTL_MS=8000`,
`DETAIL_CHARS=80`).

## Plan de test
- Unitaires : `jarvis-scene-reader.spec.ts` (registre, rapprochement, lecteur, masquage — 17 tests).
- Service : `jarvis-scene-bus.service.spec.ts` (aucun appel pendant le tour, relecture à la fin, univers illisible,
  horloge).
- Composant : `jarvis-scene.component.spec.ts` (rendu S-GEN, rafale, entité, `aria-hidden`, pas de secret).
- Non-régression : `jarvis.component.spec.ts` intact ; `npm run build` ; suite frontend complète.

## Impacts
- **Tables / endpoints** : aucun (consomme `GET /api/jarvis/universe`).
- **Composants** : nouveaux `scenes/*` ; `jarvis.component.ts|html` (accroche minimale) ;
  `review-checklist.md` (une ligne).

## Préoccupations transversales
- Auth / Principal : non. Contexte tenant : non (lecture via SF-234-01). Plans / limites : non (zéro appel IA).
- Navigation / routing : non (aucune route ; `/jarvis` inchangée).
- Préfixe de cache / consigne : **non touchés** (tout est à l'écran).

## Arbitrages (réversibles)
- Horloge sur `requestAnimationFrame` (suspendue onglet caché, tolérée par les tests à horloge simulée) plutôt
  qu'un `setInterval`.
- Client HTTP **optionnel** dans le fil : l'écran de test existant de Jarvis n'en fournit pas ; on n'y touche pas
  (coordination F-233).
- `confirm` affiché comme scène « S2 » minimale dès maintenant (D14 exige qu'elle passe devant) ; la scène S2
  complète (compte à rebours) reste à SF-234-06.

## Hors périmètre
Constellation et fil d'Ariane (SF-234-03), étiquettes passerelle (SF-234-04), scènes dédiées (vagues B/C),
synchronisation avec la phrase dite (SF-234-11).
