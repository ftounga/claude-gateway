# SF-228-03 — Les tableurs en aperçu (csv, xlsx)

**Feature :** F-228 · **Statut :** done (2026-10-31) · **Estimation :** < 1 j · **Branche :** `feat/SF-228-03-tableurs` · **Dépend de :** SF-228-02 (mergée)

## Objectif
Un CSV ou un classeur Excel posé dans le fil s'ouvre en tableau, en lecture seule.

## Comportement
1. **CSV** : analyseur maison (RFC 4180 : guillemets, guillemets doublés, retours à la ligne entre guillemets, BOM), séparateur deviné sur la première ligne (`;`, tabulation, `,`).
2. **xlsx** : bibliothèque `read-excel-file` (MIT) **chargée au clic seulement** (chunk paresseux) ; tous les onglets lus ; **premier onglet** affiché, **sélecteur d'onglets** quand il y en a plusieurs.
3. **500 lignes au plus** par onglet ; la coupe est dite (« Les 500 premières lignes seulement : téléchargez le fichier pour le reste. »). Première ligne en en-tête (collant au défilement). Cellules en texte (dates lisibles) ; jamais de HTML interprété (interpolation).
4. [Télécharger] reste offert.

## Erreurs
| Situation | Comportement |
|---|---|
| Classeur illisible (corrompu, format ancien `.xls`) | « Ce tableur n'a pas pu être lu : téléchargez-le. » |
| Pièce injoignable | message de la passerelle (SF-228-02) |

## Critères d'acceptation
- [x] CSV : séparateur, guillemets, guillemets doublés, retours à la ligne, BOM.
- [x] 500 lignes au plus, coupe signalée.
- [x] Tableau rendu sans HTML interprété.
- [x] xlsx : bibliothèque chargée au clic ; l'échec est dit.

## Plan de test
`deposit-preview-tableurs.spec` (5 tests) ; suite front complète (3642/3642), build (chunk paresseux).

## Impact
`deposit-preview.ts` (`parseCsv`, `csvDelimiter`, `cellText`), `deposit-preview-dialog.component` (vue tableau), `package.json` (+`read-excel-file@9.3.12`, version exacte publiée le 2026-10-07). Préoccupations transversales : aucune.

## Arbitrage (gate 🟠 réversible)
`read-excel-file` plutôt que SheetJS (`xlsx`) : la version publiée sur npm de SheetJS (0.18.5, 2022) n'est plus maintenue et porte des failles connues (pollution de prototype) corrigées seulement hors npm. `read-excel-file` est maintenu, sous licence MIT, et ne fait que lire. Retour arrière : retirer la dépendance (les classeurs retombent en téléchargement).

## Hors périmètre
docx (cadrage) ; `.xls` ancien format (téléchargement) ; formules recalculées (valeurs telles qu'enregistrées).
