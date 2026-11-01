# Mini-spec — F-234 / SF-234-09 — S7 atelier de création et S5b regarder une vidéo

## Identifiant
`F-234 / SF-234-09`

## Feature parente
`F-234` — L'écran vivant de Jarvis (cadrage §5, §6.2 ; vague C : création ≈ 207 appels sur 30 j, vidéo 86)

## Statut
`in-review`

## Date de création
2026-11-01

## Branche Git
`feat/SF-234-09-atelier-de-creation`

---

## Objectif

> Ce que l'agent fabrique prend forme à l'écran sous son titre — page, PDF, diagramme, visuel, document Office,
> salle de montage vidéo, voix off — et la vidéo qu'il regarde défile en pellicule.

## Comportement attendu

- **Registre** : `page_publish`, `page_pdf`, `page_lire`, `pages_lister`, `render_diagram`, `generate_image`,
  `build_presentation`, `presentation_publish`, `build_document`, `build_spreadsheet`, `voix_off`, `audio_publier`,
  préfixe `video_`, étape `video` → `S7` ; `video_images`, `video_images_status` → `S5b`.
- **S7** : une forme par création (feuillet qui se remplit, rond du diagramme, coin du PDF, cadre de la vidéo) et le
  **titre qui se compose** (étiquette : titre de page, nom de fichier, dossier du rendu). L'événement `page` (F-109 /
  F-184) ou `video` (F-213) confirme : « Page publiée », « PDF prêt », « Vidéo publiée » + titre.
- **Salle de montage** : rendu, relecture, briques et **suivis répétés** (`video_render_status`, espacés de plus de
  la rafale) forment **une seule** scène dans le tour, avec l'avancement (un point par suivi, « suivi n »).
- **S5b** : l'étape `video` qui **regarde** (« extraction des plans + son · demo.mp4 », « zoom … », « suivi de
  l'extraction ») ⇒ pellicule qui défile, nom du fichier, ce qui est extrait.
- **Composant à part** `app-jarvis-scene-extra` pour les scènes de la vague C (S6, S7, S5b, S10, S12, S13, S15,
  S17) : le style de la scène principale dépassait le budget Angular par composant (12 ko).

### Cas d'erreur
| Situation | Comportement |
|---|---|
| Étiquette absente | forme seule, libellé |
| Suivi sans rendu préalable dans le tour | salle de montage, « suivi 1 » |
| Événement `page` sans action | scène « Page publiée » + titre |

## Critères d'acceptation
1. `page_publish` ⇒ titre de la page qui se compose.
2. `video_render_status` répété ⇒ une seule scène avec avancement.
3. `video_images` ⇒ pellicule + nom du fichier.

## Plan de test
- `jarvis-scenes-creation.spec.ts` : registre, page + événement, montage espacé (2 suivis, 1 scène), pellicule ;
  rendu (titre, fichier).
- Non-régression : suite `jarvis/**` (dont les rendus S6/S10/S15 déplacés), garde passerelle, build (budget).

## Impacts
- **Tables / endpoints** : aucun. **Back** : aucun.
- **Front** : registre, lecteur (`created`, données S7/S5b, avancement du montage), fil, scène + nouveau
  `jarvis-scene-extra.component.ts`, `jarvis.component.ts` (relais `onPage`, `onVideo`).

## Préoccupations transversales
Aucune.

## Arbitrages (réversibles)
- Scènes de la vague C extraites dans un composant dédié plutôt que relever le budget de style (`angular.json`).
- S5b vs S7 sur l'étape `video` décidé par l'étiquette (« extraction » / « zoom » = regarder).

## Hors périmètre
Aperçu réel de la page ou de la vidéo (jamais de contenu) ; durée restante d'un rendu (non diffusée).
