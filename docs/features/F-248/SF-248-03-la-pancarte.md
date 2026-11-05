# Mini-spec — F-248 / SF-248-03 La pancarte (écran)

## Identifiant
`F-248 / SF-248-03`

## Feature parente
`F-248` — Les pancartes (cadrage §4.1, §4.9 ; D7, D8)

## Statut
`done` (2026-11-05) · **Estimation :** 1,5 j (réestimé ¾ j sur le code) · **Branche :** `feat/SF-248-03-la-pancarte` · **Dépend de :** SF-248-02 (contrat)

## Objectif
Un composant Angular **unique**, `JarvisCardComponent`, qui rend n'importe quelle fiche `JarvisCard` de la passerelle
selon un gabarit **en données** (`CARD_TEMPLATES`), avec statut mot + ton, champs, lien rapide et badge de fraîcheur, en
peau verre (3D) ou plate (2D).

## Contrat (importé de SF-248-02)
`JarvisCard { id, type, title, status?{word,tone}, fields[{label,value}], link?, observedAt?, source?, group?, aliases[], targeted }`
— `frontend/src/app/jarvis/cards/jarvis-card.models.ts`.

## Comportement attendu

### Cas nominal
1. Gabarit par type (forme + mot) ; type inconnu ⇒ `GENERIQUE` ; garde `cardTypesWithoutTemplate()` vide.
2. Statut : mot écrit, couleur du ton (`TONE_TOKENS`, palette §23) ; ton inconnu ⇒ `normal`.
3. Champs : 5 au plus, valeurs passées par `maskSecrets` (filet écran, en plus de la liste blanche serveur).
4. Lien : `http(s)://` ⇒ nouvel onglet `noopener noreferrer` ; route `/…` ⇒ « Voir le chantier » ; tout autre (`javascript:`, `//hôte`) ⇒ aucun lien.
5. Fraîcheur (`cardFreshness`) : « à l'instant » (poste connecté, < 15 min), « relevé HH h MM », « relevé hier … »,
   « relevé lun. … », au-delà « relevé 3 nov. … » ; > 24 h ⇒ badge « ancien » et palier 0,45.
6. Sorties : `opened` (toucher la pancarte), `linked` (lien ouvert) — branchées en SF-04/06.
7. DESIGN_SYSTEM §25 ajouté (aucune couleur ni police nouvelle).

### Cas d'erreur
| Situation | Comportement attendu | Code HTTP |
|---|---|---|
| Type inconnu | gabarit `GENERIQUE` | — |
| `observedAt` absent ou illisible | pas de badge | — |
| Lien dangereux | non rendu | — |
| Valeur à forme de secret | masquée `•••` | — |

## Contraintes de validation
≤ 5 champs affichés ; titre sur 2 lignes au plus ; cible tactile ≥ 44 px.

## Critères d'acceptation
- [x] Garde de couverture vide ; type inconnu ⇒ générique.
- [x] Badge de fraîcheur conforme aux 5 formes ; « ancien » atténue.
- [x] Lien sûr seulement ; secret masqué ; bouton « Ouvrir … ».
- [x] Peau plate en 2D.

## Plan de test
- Unitaires : `jarvis-card.spec.ts` (gabarits, tons, fraîcheur, liens — 4 ; composant — 4).
- Intégration / isolation : sans objet (composant pur ; l'isolation est portée par la passerelle, SF-02).

## Impact
- Frontend : `jarvis/cards/jarvis-card.models.ts`, `jarvis-card-templates.ts`, `jarvis-card.component.ts`.
- Docs : `DESIGN_SYSTEM.md` §25.
- Préoccupations transversales : aucune (pas de route, pas d'auth, pas de tenant).

## Hors périmètre
Le jeu de pancartes et sa disposition (SF-04), la citation (SF-05), le plein écran (SF-06).
