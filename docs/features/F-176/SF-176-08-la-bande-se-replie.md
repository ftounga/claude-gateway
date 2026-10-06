# Mini-spec — F-176 / SF-176-08 — La bande se replie

## Identifiant
`F-176 / SF-176-08` — rouverture du 2026-10-06 (D8), validée PO. Dépend de SF-176-07 (#1085).
Branche : `feat/SF-176-08-la-bande-se-replie`. Statut : `in-progress`. Date : 2026-10-06.

## Objectif
Qu'un chantier clos ne laisse plus de bande des phases au-dessus de la saisie : une seule ligne
repliée « Chantier clos le … · voir le plan », masquable (défaut G2).

## Comportement attendu

### Cas nominal
1. La bande des phases n'est rendue qu'en **Guidé actif** (`mode=GUIDE`, phase ≠ `CLOS`).
2. En Libre avec `phase=CLOS` : une ligne « Chantier clos le JJ/MM/AAAA », un lien « voir le plan »
   (s'il y a un plan) qui déplie les étapes en lecture seule (statut, titre, risque, preuve), et un
   bouton « × » qui masque la ligne.
3. Le masquage est mémorisé dans ce navigateur (clé = date de clôture) : un chantier clos plus tard
   réaffiche sa ligne.
4. Repasser en Guidé fait disparaître la ligne (la bande revient).

### Cas d'erreur
| Situation | Comportement |
|---|---|
| stockage du navigateur indisponible | masqué pour la session seulement |
| date de clôture illisible | « Chantier clos » sans date |
| chantier clos sans plan | pas de lien « voir le plan » |
| reste `GUIDE` + `CLOS` (avant migration 146) | ni bande, ni verrou |

## Contraintes de validation
Aucune saisie.

## Critères d'acceptation
- [ ] Après clôture, aucune bande des phases ; une ligne « Chantier clos le … ».
- [ ] « voir le plan » déplie les étapes du plan clos ; « masquer le plan » les replie.
- [ ] « × » masque la ligne, et elle reste masquée au rechargement.

## Plan de test
- **Front** : `terminal-journey.spec.ts` (ligne repliée, plan consultable, masquage ; reste GUIDE+CLOS sans bande).
- **Back** : aucun changement. **Isolation** : sans objet (lecture du parcours existant, déjà isolé).
- Suites complètes front.

## Impacts
- Front : `terminal-journey-strip.component.ts` uniquement. Aucun endpoint, aucune table.
- Design system : tokens existants (`--cg-text-secondary`, `--cg-success`, `--cg-divider`).

### Préoccupations transversales
- Auth / tenant / plans / navigation : **non**.

## Hors périmètre
Liste des chantiers archivés (SF-176-11) ; relance au clic (SF-176-09).
