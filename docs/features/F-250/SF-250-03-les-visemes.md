# Mini-spec — F-250 / SF-250-03 Les visèmes

## Identifiant
`F-250 / SF-250-03`

## Feature parente
`F-250` — Le visage de Jarvis (cadrage §3 étape 3, §3.1)

## Statut
`done` (2026-11-07) · **Estimation :** 1,5 j · **Branche :** `feat/SF-250-03-les-visemes` · **Dépend de :** —

## Objectif
Un module pur `jarvis/face/jarvis-visemes.ts` qui transforme un alignement par caractère en frise de 13 visèmes +
silence (règles graphème → visème françaises), et l'échantillonne avec co-articulation et lèvres en avance de 30 ms.

## Comportement attendu

### Cas nominal
1. Table §3.1 (`VISEMES`) : ouverture, étirement, arrondi, fermeture, dominance.
2. Règles : graphèmes multiples d'abord (`eau`, `ain`, `ou`, `oi`, `ch`, `gn`, `qu`…) ; nasale seulement devant
   consonne ; `c`/`g` doux devant e i y ; `h` muet ; finales muettes `s t d x z p` ; `e` muet final et devant `-s`/`-nt` ;
   `s` intervocalique ; `x` → K + S ; `oi` → OU puis A sur le même intervalle.
3. **Nombres et symboles** : `normalized_alignment` ne développe pas les nombres (constaté sur réponse réelle,
   « 12 » reste « 12 ») ⇒ les chiffres sont dits en toutes lettres (`numberWords`), leurs sons répartis sur les
   caractères qui les portent ; `% € $ & +` idem.
4. `sampleVisemes(events, t, out)` : noyaux `dominance × exp(−((t − centre)/(demi-durée + 40 ms))²)`, avance 30 ms,
   recherche dichotomique, **aucune allocation**.
5. `sampleAmplitude(level, out)` : repli (ouverture seule).

### Cas d'erreur
| Situation | Comportement attendu | Code HTTP |
|---|---|---|
| Texte vide | frise vide | — |
| Mot étranger, liaison | forme approximative, minutage intact | — |
| Nombre > 6 chiffres | dit chiffre par chiffre | — |

## Critères d'acceptation
- [x] Phrase EDENRED : fermetures sur « Bonjour », « Monsieur », « commence » ; nasales ; silences de ponctuation.
- [x] Nombres, nasales, finales muettes, `oi`, `x` couverts par des tests.
- [x] Avance de 30 ms prouvée ; échantillon réutilisé.

## Plan de test
Unitaires `jarvis-visemes.spec.ts` (10 cas). Intégration / isolation : sans objet (module pur).

## Impact
Frontend : `jarvis/face/jarvis-visemes.ts` (+ spec). Préoccupations transversales : aucune.

## Hors périmètre
Langues autres que le français (le minutage, lui, marche pour toute langue) ; modèle d'IA audio → visèmes.
