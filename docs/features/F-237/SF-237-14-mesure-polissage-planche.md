# Mini-spec — F-237 / SF-237-14 — Mesure d'intérêt, polissage, planche de captures

## Identifiant
`F-237 / SF-237-14` · Feature parente `F-237` · Statut `in-review` — **visa du PO délégué** pour cette vague (non
bloquant) · 2026-11-02 · Branche `feat/SF-237-14-mesure-polissage`
Cadrage : §19 (mesure J+7 / J+14), §15 itération D.

## Objectif
> Savoir à J+7 / J+14 si les pièces servent (et lesquelles), sans aucun contenu ; polir ce que l'itération C/D a
> laissé ; produire la planche de référence de F-237.

## Comportement attendu

### Mesure (§19) — vocabulaire fermé, aucun contenu
Les compteurs passent par le compte-rendu de rendu de l'ouverture (`RENDER`, F-236 SF-236-06) ⇒
`jarvis_usage_days.render_counts`, lus par la vue d'adoption admin existante. Clés (`RenderCounts.KEYS` ≡
`PIECE_COUNT_KEYS`, garde de test) :

| Clés | Signal §19 |
|---|---|
| `p_gal`, `p_ent`, `p_prf`, `p_prd` | galeries ouvertes, par catégorie |
| `p_ask`, `p_pro` | pièces montrées demandées / d'initiative |
| `p_img` … `p_fil` | pièces ouvertes par type (« types jamais ouverts à J+14 ») |
| `n_loc`, `n_ai`, `n_tch` | ordres de navigation : voix locale / IA / toucher (« locale ≥ 60 % ») |
| `v_play`, `v_sec`, `pdf_pg` | vidéos lues et secondes (sauts exclus), pages de PDF tournées |
| `p_story`, `p_cmp`, `p_sheet`, `p_pin`, `p_note` | récits, avant / après, planches, épinglages, retours vocaux |
| `pf_off`, `pf_ok`, `pf_gen` | propositions de preuve dites / acceptées / captures générées (« acceptées ≥ 50 % ») |
| `p_fclose` | pièce d'initiative fermée en < 2 s (« > 30 % ⇒ resserrer ») |
| `lat_0_1` … `lat_5p` | délai parole → première pièce, 5 classes (« p50 ≤ 2,5 s ») |

`RenderCounts.format` ne dépasse jamais 1 000 caractères (la colonne) : au-delà, les plus petits compteurs sont
laissés de côté. Le coût du cerveau rapide reste lu par la mesure F-235 existante (budget 50 €/mois).

### Polissage
- Bouton « Cockpit » de la galerie : suit l'arrivée et le départ du cockpit (rappel du meneur), plus seulement les
  changements de scène.
- Banc de captures : scènes `pieces-galerie`, `pieces-proactif`, `pieces-grille`, `pieces-image`, `pieces-texte`,
  `pieces-avant-apres`, `pieces-planche` (contenus dessinés sur place, aucun réseau).

### Planche
`docs/features/F-237/captures/planche.json` ⇒ `index.html` (générateur `frontend/scripts/jarvis-planche.mjs`) :
galerie en éventail, grille 2D, héros image, besoin initial, avant / après, planche contact — PC et téléphone.

### Cas d'erreur
- Compteur hors vocabulaire ⇒ refusé par la passerelle (existant), jamais stocké.
- Capture vide ou écran d'erreur du serveur de dev ⇒ refaite (garde du script existant).

## Critères d'acceptation
- [ ] Clés écran ≡ clés passerelle (test) ; ≤ 1 000 caractères (test) ; comptes justes (test magasin).
- [ ] Planche produite, chemins vérifiés.
- [ ] Visa PO : délégué (non bloquant) ; i/s galerie ouverte à relever en usage (`RENDER`).

## Préoccupations transversales
| Préoccupation | Cochée | Composants impactés |
|---|---|---|
| Plans / limites | Non | aucun quota touché ; compteurs seulement |
| Contexte tenant | Non | `JarvisAdoptionService` / `JarvisUsageService.addRender` existants (par `user_id`) |

## Plan de test
Back : `RenderCountsTest` (+2 : garde écran ≡ passerelle, plafond). Front : `jarvis-piece-measure.spec.ts`
(magasin, secondes, délai, preuves, compte-rendu, cockpit).

## Hors périmètre
Un tableau de bord dédié aux pièces (la vue d'adoption admin suffit pour J+7) ; i/s mesurés sur matériel réel.
