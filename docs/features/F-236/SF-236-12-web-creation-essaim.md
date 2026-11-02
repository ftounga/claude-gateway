# Mini-spec — [F-236 / SF-236-12] Web, création, vidéo, passation, mémoire, essaim (S6, S7, S5b, S10 + T-Z3, S12, S13 + N8, S16, S17)

## Identifiant
SF-236-12

## Feature parente
F-236 — Jarvis en 3D (cadrage §9.2 S6/S7/S5b/S10/S12/S13/S16/S17, §7.4 T-Z3, §8.2 L-essaim / L-passation, §11 N8)

## Statut
done

## Date de création
2026-11-02

## Branche Git
`feat/SF-236-12-web-creation-essaim`

---

## Objectif

Donner leur rendu 3D aux scènes restantes de l'itération 2 : le web, ce qui se crée, la vidéo regardée, la passation
entre sujets, la mémoire, l'essaim des sous-agents et la veille.

## Comportement attendu

### Cas nominal

1. **S6 Web** : barre d'adresse holographique (mono) au-dessus du cœur — « Recherche : … » ou le domaine lu — ;
   **globe filaire** derrière le cœur, en rotation lente ; **impulsions globe → cœur**.
2. **S7 Création** : l'objet **se construit par couches** sous son titre (titre, type : page, PDF, diagramme, visuel,
   document, vidéo, audio), les particules du cœur s'y déposent ; **salle de montage** : une couche par suivi du rendu
   (« Rendu suivi × n ») ; **audio** = onde ; **page lue** = pages qui défilent (N11).
3. **S5b Vidéo** : la **pellicule en arc** au-dessus du cœur, le plan extrait agrandi ; le film et l'extraction
   écrits.
4. **S10 Passation** : **arc lumineux** du sujet courant au sujet visé (perles qui le parcourent) ; « Migration
   Forge → Doc archi » ; **un seul travelling T-Z3** (40°, ≤ 60°) par passation ; la caméra cadre le sujet visé.
5. **S12 Mémoire** : un souvenir (« Tour 34 ») **remonte des profondeurs** du cœur (perles venues d'en dessous) ; la
   **carte du savoir** = constellation secondaire (12 étoiles stables) au-dessus du cœur ; la compaction = onde.
6. **S13 Essaim + N8** : un **petit cœur satellite** par délégation en orbite, relié au cœur (**L-essaim**), avec sa
   traînée ; questions écrites ; **≤ 4 sur PC, ≤ 2 + « +n » sur téléphone**.
7. **S17 Veille** : un **cadran** attaché au sujet (cercle, 12 graduations, aiguille). **S16** reste « S-GEN libellée »
   (repli déclaré), comme S18 / S19 (vague D F-234, D-PO-7).
8. Les scènes centrées sur le cœur (S6, S7, S5b, S12, S13, et S14) font prendre la **vue d'ensemble** à la caméra :
   leurs effets restent à l'écran (téléphone compris).

### Cas d'erreur

| Cas | Comportement |
|---|---|
| Sujet visé d'une passation inconnu | Pas d'arc ; panneau au sujet courant ; travelling joué une fois. |
| Plus de 4 délégations | Les 4 plus récentes (lecteur F-234) ; téléphone : 2 + « +n ». |
| Création sans titre | Seul le type s'écrit. |
| Même scène relue par le directeur | Même travelling (même `id`) : il ne se rejoue pas. |

---

## Critères d'acceptation

- [x] CA1 — Essaim ≤ 4 (téléphone ≤ 2 + « +n ») (tests).
- [x] CA2 — Un seul travelling par passation (même `id` pour la même scène, un nouveau pour une nouvelle) (tests).
- [x] CA3 — S6 globe + flux, S7 couches / montage / onde / pages, S5b pellicule, S12 profondeurs / constellation,
  S17 cadran, S16 en repli S-GEN (tests).
- [x] CA4 — Captures téléphone + PC de chaque scène (web, page, montage, vidéo, passation, souvenir, carte, essaim,
  veille), non vides, sans erreur console.

## Contraintes de validation

| Élément | Règle |
|---|---|
| Satellites | PC 4, téléphone 2 + « +n » ; question 48 c. |
| Travelling T-Z3 | 40° (≤ 60°), une fois par passation. |
| Constellation du savoir | 12 étoiles, positions stables (hachage du libellé). |

## Plan de test

- Unitaires : `scenes3d/jarvis-scenes-monde.spec.ts` (S6, S7, S5b, S10, S12, S13, S16, S17, registre).
- Garde du registre : ne restent en repli que S16, S18, S19 (test).
- Isolation utilisateur : sans objet (aucune lecture nouvelle, aucun appel).
- Visuel : `scene-web`, `scene-page`, `scene-montage`, `scene-video`, `scene-passation`, `scene-souvenir`,
  `scene-carte`, `scene-essaim`, `scene-veille`.

## Périmètre

### Impacté
`scenes3d/jarvis-scenes-monde.ts` (nouveau), `scenes3d/index.ts`, modèle (ancrages hors univers, `globe`, `swarm`,
`dial`, pellicule, flux en arc), fixture ; `engine/jarvis-effects-3d.ts` (globe, essaim, cadran, pellicule, arc),
`engine/jarvis-engine.ts` (scène centrée sur le cœur ⇒ vue d'ensemble) ; `scenes3d/jarvis-scenes-chantier.ts` (S14) ;
banc.

### Hors scope (explicite)
- L'horloge des veilles et le radar des alertes (SF-236-25) ; la vague D F-234 (S18, S19 restent en S-GEN).
- Aucun endpoint, aucune table, aucun appel IA, aucune consigne touchée.

## Préoccupations transversales
Aucune cochée.

## Notes et décisions

| # | Arbitrage | Pourquoi | Alternative écartée | Réversible |
|---|---|---|---|---|
| A1 | Ancrages d'effets hors univers (`@globe`, `@profondeur`, `@dessus`, `@cote`) placés par rapport au cœur et à la caméra. | Le globe, les profondeurs, la constellation n'ont pas de nœud ; ils restent lisibles quel que soit l'angle. | Positions fixes du monde. | Oui. |
| A2 | `focus: @coeur` = scène centrée sur le cœur ⇒ vue d'ensemble. | Au téléphone, la caméra partait sur l'entité et le cœur sortait du cadre (vu au banc). | Garder l'entité. | Oui. |
| A3 | Travelling de passation à 40°. | « Arc de 60° max » ; 40° suffit à faire sentir le passage sans mal des transports. | 60°. | Oui. |
| A4 | Satellites = particules (cœur + traînée) dans le pool commun, filaments en un seul tracé. | Zéro allocation, pool T9 respecté. | Un objet par satellite. | Oui. |
