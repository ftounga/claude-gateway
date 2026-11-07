# Mini-spec — F-250 / SF-250-06 Les lèvres dans l'écran

## Identifiant
`F-250 / SF-250-06`

## Feature parente
`F-250` — Le visage de Jarvis (cadrage §3 étape 4, portes P2, P4)

## Statut
`done` (2026-11-07) · **Estimation :** 1,5 j · **Branche :** `feat/SF-250-06-les-levres` · **Dépend de :** SF-250-02, 03, 05

## Objectif
Brancher la frise de visèmes de la phrase en cours (horloge `audio.currentTime`) sur la bouche du visage, avec repli
amplitude automatique, sans erreur, quand l'alignement manque ou est mal formé.

## Comportement attendu

### Cas nominal
1. `face/jarvis-lip-sync.ts` (`LipSync`) : lit `playhead()` / `alignment()` / `level()` de la voix ; frise recalculée
   **seulement** quand l'alignement grandit (morceau suivant) ; échantillon réutilisé (aucune allocation par image).
2. Modes : `visemes` (alignement présent), `amplitude` (voix OpenAI, voix locale, clip sans alignement, alignement mal
   formé), `silence` (bouche au repos).
3. L'écran (`jarvis.component.ts`) passe `faceMouth` à la scène 3D ; la scène l'appelle à chaque image hors zone.
4. Clips de réaction : mp3 seuls aujourd'hui ⇒ repli amplitude (sans erreur). L'alignement compagnon des clips est
   laissé en réserve (R1 ci-dessous).

### Cas d'erreur
| Situation | Comportement attendu | Code HTTP |
|---|---|---|
| Alignement de longueurs différentes / temps non finis | frise vide ⇒ amplitude | — |
| Exception dans la frise | attrapée ⇒ amplitude | — |
| Lecture en pause / finie | silence (bouche au repos) | — |

## Porte P2 (écart lèvres ↔ son) — méthode et résultat
Banc hors ligne (`docs/features/F-250/bancs/p2/`) : 6 réponses **réelles** ElevenLabs (prod, voix Daniel) ; la chaîne
du produit (`LipSync` → `faceLookAt`, 60 i/s, image affichée une image après le calcul) est rejouée sur l'horloge audio.
- Fermetures M B P (instant de fermeture maximale vs centre de la consonne dans la frise) : n = 38 (ressort 30 s⁻¹ :
  médiane +4 ms, p90 du retard 10 ms).
- Ouvertures (« a » à mi-hauteur vs début de la voyelle) : n = 41 ; avec le ressort de bouche du rendu (30 s⁻¹) la
  médiane était −27 ms mais le p90 du retard **45 ms** (à la limite) ⇒ ressort porté à **40 s⁻¹** : médiane **−39 ms**
  (lèvres en avance), p90 du retard **38 ms** ; fermetures M B P : médiane 0 ms, p90 du retard **7 ms**.
- Contrôle du fournisseur : creux d'énergie de l'audio décodé vs consonne intervocalique alignée, n = 11, médiane
  +12 ms (son légèrement après l'alignement ⇒ lèvres en avance), p90 |écart| 41 ms.
- La latence de sortie audio du navigateur (`outputLatency`, 10-40 ms) fait entendre le son **après**
  `currentTime` : elle joue en faveur des lèvres.
**Verdict P2 : PASS (≤ 45 ms p90 : 7 ms fermetures, 38 ms ouvertures)** — à confirmer à l'œil sur l'appareil du PO.

## Critères d'acceptation
- [x] Visèmes sur l'horloge audio ; frise suivie morceau par morceau.
- [x] Repli amplitude automatique sans erreur (P4).
- [x] P2 mesurée et documentée.

## Plan de test
Unitaires `jarvis-lip-sync.spec.ts` (4). Banc P2 hors ligne. Intégration / isolation : sans objet.

## Impact
Frontend : `jarvis/face/jarvis-lip-sync.ts`, `jarvis/face/jarvis-face-motion.ts` (ressort 40 s⁻¹), `jarvis.component.ts/html` (bouche), `jarvis-stage-3d.component.ts`
(entrée `mouth`), banc (`visage-parole` articule la phrase EDENRED sur son alignement réel enregistré).

## Réserve
R1 — clips de réaction alignés à leur génération (fichier compagnon) : non fait ; les clips restent en amplitude
(phrases de 1-3 mots, repli prévu par le cadrage).

## Hors périmètre
Cohabitation et réglage (SF-07), bancs de captures et de performance (SF-08).
