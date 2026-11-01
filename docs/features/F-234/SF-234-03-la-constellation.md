# Mini-spec — F-234 / SF-234-03 — La constellation (D6, D7, D8, D9, D10, D12)

## Identifiant
`F-234 / SF-234-03`

## Feature parente
`F-234` — L'écran vivant de Jarvis (cadrage `CADRAGE-F-234-ecran-vivant-jarvis.md`, vague A « socle »)

## Statut
`in-review`

## Date de création
2026-11-01

## Branche Git
`feat/SF-234-03-constellation`

---

## Objectif

> L'univers du PO entoure l'orbe : clients en couronne, sujets du client courant en anneau ; le nœud dont il est
> question s'allume, un fil lumineux le relie à l'orbe, son nom passe en surbrillance, et un fil d'Ariane texte dit
> la même chose.

## Comportement attendu

- **Disposition radiale déterministe** (`layoutUniverse`, pure) dans un repère −100…100 : clients sur la couronne
  (r = 88, le client courant en haut), sujets du client courant en anneau (r = 62) ; desktop : sujets des autres
  clients en grappe discrète ; **téléphone (< 768 px) : non rendus** (points nommés en lisière seulement).
- **Nœuds cités** (lecteur SF-234-02) : client / sujet allumés sur place ; **chantier** (losange, « Ch. 3 — titre »)
  et **personne** (carré arrondi) n'apparaissent que cités ; un fil lumineux de chaque nœud cité vers l'orbe ;
  l'anneau pulse une fois par appel d'outil ; un sujet avec un terminal vivant respire.
- **Fil d'Ariane** sous la constellation : dernier chantier / sujet / client cité, remonté jusqu'au client, avec
  l'étape courante du chantier (« CAGIP › Migration Forge › Chantier 3 › Étape 3 — Basculer ») ; sinon le sujet
  courant.
- **Toucher un sujet** (clic, Entrée, Espace) = `chooseSubject` existant (même effet que le menu) ; client et
  chantier ne se touchent pas (D-PO-1, défaut validé).
- **Mouvement réduit** : aucune animation de tracé, de pulsation ni de respiration.
- **Accessibilité** : sujets en `role="button"` « Passer sur X » ; parties décoratives `aria-hidden` ; région
  `aria-live` limitée (≤ 1 annonce / 4 s, jamais pendant que Jarvis parle).
- **Charte** : `DESIGN_SYSTEM.md` §22 (aucune couleur nouvelle, contrastes mesurés).
- **Accroche** : gabarit seulement — l'orbe est projetée dans `<app-jarvis-constellation>` ; une règle de taille de
  l'orbe dans `jarvis.component.scss` ; l'import du composant. Aucune logique de `jarvis.component.ts` modifiée.
- Univers lu une seule fois à l'ouverture même si scène et constellation le demandent (lecture en vol partagée).

### Cas d'erreur
| Situation | Comportement |
|---|---|
| Univers absent ou illisible | orbe seule, fil d'Ariane vide |
| Sujet courant hors univers | premier client déployé |
| 40 sujets sur l'anneau | noms écrits seulement allumés ; cible tactile réduite à l'espacement (≥ 4 unités) |

## Critères d'acceptation
1. Même univers ⇒ mêmes positions.
2. < 768 px : sujets des autres clients non rendus.
3. `prefers-reduced-motion` ⇒ aucune animation de tracé (règle CSS dédiée).
4. Contraste des noms ≥ 4,5:1 sur navy (crème et orange de surbrillance — test).
5. Toucher un sujet = même effet que le menu (`chooseSubject`).
6. `?alerte=` et `?source=compagnon` intacts (suite `jarvis.component.spec.ts` verte, aucune logique touchée).
7. Budget ≤ 4 ms/image : SVG ≤ ~60 nœuds, animations `opacity`/`stroke-dashoffset`/`transform` seulement, aucun
   `filter` animé, signaux recalculés au changement d'état seulement (mesure sur Android = vérification manuelle à
   faire après déploiement, notée en risque résiduel).

## Contraintes de validation
Rayons 62 / 76 / 88 ; ≤ 10 sujets nommés au repos ; cible tactile 4…12 unités (≈ 44 px à 390 px de large).

## Plan de test
- `jarvis-constellation.component.spec.ts` : disposition (déterminisme, téléphone/desktop, client courant, ancrage),
  fil d'Ariane, contraste, rendu (clients, sujets, bouton clavier, choix), nœud cité + fil + chantier + fil
  d'Ariane, téléphone sans sujets étrangers, silence pendant la parole.
- Non-régression : `jarvis/**` (dont `jarvis.component.spec.ts`), `npm run build`.

## Impacts
- **Tables / endpoints** : aucun.
- **Composants** : nouveaux `jarvis-constellation.component.ts`, `jarvis-constellation-layout.ts` ;
  `jarvis.component.html|scss|ts` (accroche gabarit) ; `jarvis-scene-bus.service.ts` (lecture partagée) ;
  `DESIGN_SYSTEM.md` §22.

## Préoccupations transversales
- **Navigation / routing : OUI (faible)** — aucune route nouvelle ; `/jarvis` (authGuard) inchangée ; toucher un
  sujet appelle `chooseSubject` existant (pas de navigation) ; entrées `?alerte=` (SF-231-05) et
  `?source=compagnon` (SF-232-02) non touchées — vérifiées par `jarvis.component.spec.ts` vert.
- Auth / tenant / plans : non.

## Arbitrages (réversibles)
- D-PO-1/3 (validés par défaut, « je valide tout ») : seul le sujet est touchable ; téléphone = client courant
  déployé + autres en lisière.
- Taille de texte en unités du repère (6,5 ≈ 12 px à 390 px) plutôt qu'en px fixes : la carte garde ses proportions.
- La respiration des sujets à terminal vivant (A2) est posée dès le socle (donnée déjà dans l'univers, aucun relevé
  nouveau) ; les autres scènes d'ambiance restent à SF-234-10.

## Hors périmètre
Zoom / recherche dans l'univers ; scènes dédiées ; mode sobre (SF-234-11).
