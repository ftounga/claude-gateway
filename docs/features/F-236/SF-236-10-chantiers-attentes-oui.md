# Mini-spec — [F-236 / SF-236-10] Chantiers, étapes, attentes, « oui » (S9, S11 + T-L4, S2, S14, S15)

## Identifiant
SF-236-10

## Feature parente
F-236 — Jarvis en 3D (cadrage §9.2 S9/S11/S2/S14/S15, §7.4 T-L4, §8.2 L-attente, §9.3 ; familles 3-5)

## Statut
done

## Date de création
2026-11-02

## Branche Git
`feat/SF-236-10-chantiers-attentes-oui`

---

## Objectif

Rendre en 3D le travail sur les chantiers et ce qui attend le PO : l'étape qui avance, l'attente posée ou levée,
l'autorisation « oui », les points de route et « le point ».

## Comportement attendu

### Cas nominal

1. **S9 Étape** : la caméra cadre le **chantier** ; son chapelet est déployé ; le panneau écrit « Ch. n — titre »,
   la phase, l'étape et son état (vert si faite / vérifiée) ; la **barre = (`done` + `stepsDone`) / `total`** ; la perle
   qui vient de se remplir s'allume en vert et **l'éclair court du losange à elle**. Les étapes cochées pendant le tour
   remplissent les perles **avant** la relecture de l'univers (`ReaderState.stepsDone`, lu).
2. **S11 Attente** : « personne · catégorie » en teinte d'attente, ancré au losange du chantier qui attend cette
   personne ; **L-attente** en pointillé du chantier à la personne ; **levée** : **rupture T-L4** (0,7 s, `entree` :
   le filament se scinde au milieu, chaque fragment se rétracte et s'éteint) + lueur verte.
3. **S2 « Oui » / question** : panneau d'attente **au-dessus du cœur, devant tout tant qu'il attend** (jamais écarté
   du pool) ; l'**arc se vide** autour du cœur au rythme de `remaining` (sur la durée accordée), ondes lentes toutes
   les 2,4 s ; la caméra recule (règle existante §9.3). Sans échéance : arc plein, aucun chiffre.
4. **S14 Points de route** : couronne de perles sous le cœur, une par question, **verte quand traitée** ; la question
   en cours en gras.
5. **S15 Le point** : **vague** sur tous les sujets du client (anneaux, 80 ms d'écart) + **tour lent de 60°** de la
   caméra, joué une seule fois par scène.

### Cas d'erreur

| Cas | Comportement |
|---|---|
| Chantier non reconnu | Chantier focalisé du sujet ; à défaut, panneau au sujet / au cœur. |
| `stepsDone` dépasse le total | Barre et perles bornées à `total`. |
| Personne absente de l'univers | Le pointillé part vers un point flottant ; « Quelqu'un » si aucun nom. |
| Autorisation sans échéance | Arc plein, aucun compte à rebours écrit. |
| Client sans sujet posé | Pas de vague ; le panneau « le point » reste. |

---

## Critères d'acceptation

- [x] CA1 — S2 devant tout tant que `pinned` (pool téléphone compris) ; compte à rebours = `remaining` (tests).
- [x] CA2 — Barre = `done + stepsDone` / `total`, bornée ; perle remplie dans la disposition avant relecture (tests).
- [x] CA3 — Attente posée ⇒ lien attaché ; levée ⇒ lien rompu (T-L4) + vert (tests) ; séquence T-L4 de 6 images.
- [x] CA4 — S14 vert = traité ; S15 vague sur les sujets du client, un seul travelling de 60° (tests).
- [x] CA5 — Captures téléphone + PC de chaque scène, non vides, sans erreur console.

## Contraintes de validation

| Élément | Règle |
|---|---|
| Titre de chantier | 48 c. au plus. |
| Points de route | ≤ 6 (lecteur F-234), 48 c. |
| Travelling S15 | 60°, une fois par scène (`id` = clé + instant). |

## Plan de test

- Unitaires : `scenes3d/jarvis-scenes-chantier.spec.ts` (S9, S11, S2, S14, S15, disposition `stepsDone`, directeur).
- Garde du registre verte.
- Isolation utilisateur : sans objet (aucune lecture nouvelle, aucun appel).
- Visuel : `scene-etape`, `scene-attente`, `scene-oui`, `scene-question`, `scene-points`, `scene-le-point` ; séquence
  `scene-attente-levee` (T-L4, 0 → 700 ms).

## Périmètre

### Impacté
`scenes3d/jarvis-scenes-chantier.ts` (nouveau), `scenes3d/index.ts`, modèle (effets `tether`, `countdown`, `crown`,
`wave`, `travel`), fixture ; `engine/jarvis-effects-3d.ts` (pointillé + rupture, arc, couronne, vague),
`engine/jarvis-engine.ts` (travelling) ; `jarvis-layout-3d.ts` (`stepsDone`), `jarvis-stage-director.ts`,
`jarvis.component.ts` (`stepsDone` relayé) ; banc.

### Hors scope (explicite)
- La cartographie des personnes (SF-236-18), la météo des chantiers (SF-236-16).
- Aucun endpoint, aucune table, aucun appel IA, aucune consigne touchée.

## Préoccupations transversales
Aucune cochée.

## Notes et décisions

| # | Arbitrage | Pourquoi | Alternative écartée | Réversible |
|---|---|---|---|---|
| A1 | Teinte de l'attente = **cyan** (`wait`), le vert pour la levée. | Goût PO V1 (§17) : l'orange est la teinte normale, le cyan celle de l'attente. | Orange du cadrage d'origine. | Oui. |
| A2 | Le chantier d'une scène S9 prime sur l'entité : posé, allumé, chapelet déployé. | « Zoom sur le chantier » ; l'entité du lecteur est le sujet. | Allumer le sujet. | Oui. |
| A3 | Durée accordée à S2 = `deadline − since` (échéance posée par le lecteur). | Le lecteur ne garde que l'échéance et les secondes restantes. | Ajouter un champ au lecteur (§19). | Oui. |
| A4 | Le travelling S15 est un décalage de lacet ajouté au plan de caméra (ressort critique, sans roulis). | Respecte §7.3-8 ; le directeur de caméra garde la main. | Animation de caméra dédiée. | Oui. |
