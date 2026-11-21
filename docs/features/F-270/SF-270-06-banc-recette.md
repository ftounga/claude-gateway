# Mini-spec — [F-270 / SF-270-06] Banc et recette guidée

## Identifiant
`F-270 / SF-270-06`

## Feature parente
`F-270` — Parler à Jarvis pendant qu'il travaille

## Statut
`in-progress`

## Date de création
2026-11-21

## Branche Git
`feat/SF-270-06-banc-recette`

---

## Objectif
Le banc à vraie voix couvre les scénarios S1 à S7 du cadrage, joués en deux passages consécutifs sur la feature
complète, et le PO dispose d'une recette guidée (phrases exactes, attendus) pour son essai.

## Comportement attendu

### Cas nominal
1. Corpus ajouté au banc (poste simulé, sujet `facturation`) :
   - **S1** deux commandes, deux sujets : une recherche sur `facturation` qui tourne, puis une question sur un autre
     sujet, répondue pendant le travail ;
   - **S2** `oui-en-fond` (SF-270-02) ; **S3** `precision-ou-nouvelle` (SF-270-04) ; **S4** `pendant-qu-il-pense`
     (SF-270-03) ;
   - **S5** « Qu'est-ce qui tourne en ce moment ? » pendant un travail : réponse dite, travail nommé ;
   - **S6** « Arrête facturation. » (SF-270-04) ;
   - **S7** poste éteint (CAGIP, postes du banc éteints) : « Relance le pipeline de dev d'agenor. » ⇒ refus dit, rien
     n'est lancé ni gardé.
2. Critères pré-fixés (cadrage §8) : aucune parole perdue sur les scénarios F-270 ; bonne cible de chaque « oui » ;
   relation correcte ; premier son médian du jeu de référence ≤ référence `main` mesurée à la même période (+0,3 s de
   tolérance de bruit) ; coût affiché.
3. Recette guidée `docs/features/F-270/RECETTE-F-270.md` : 11 étapes, phrases exactes, ce que Jarvis dit, ce que
   l'écran montre, ce qu'il faut vérifier.

### Cas d'erreur
| Situation | Comportement attendu |
|---|---|
| Une phrase F-270 perdue | passage rouge, cause cherchée avant tout autre passage |
| Poste éteint : une action lancée ou gardée | passage rouge (bloquant) |

## Critères d'acceptation
- [ ] Scénarios S1, S5, S7 ajoutés ; S2, S3, S4, S6 présents.
- [ ] Deux passages consécutifs verts selon les critères pré-fixés, rapports `f270-06-legacy-1/2`.
- [ ] Recette guidée publiée dans le dépôt.

## Périmètre

### Hors scope (explicite)
- Tournée en prod (après déploiement, hors de cette livraison).

## Valeurs initiales
Néant.

## Contraintes de validation
Néant.

## Technique

### Endpoint(s)
Aucun.

### Tables impactées
Aucune.

### Migration Liquibase
Aucune.

### Composants Angular (si applicable)
Aucun (banc : `phrases.json`).

## Plan de test

### Tests unitaires
`node --test frontend/scripts/banc-jarvis-voix/mesure.test.mjs`.

### Tests d'intégration
Banc à vraie voix, 2 passages, boucle legacy.

### Isolation workspace
Sans objet.

## Dépendances

### Subfeatures bloquantes
SF-270-01 à 05 (mergées).

### Questions ouvertes impactées
Aucune.

## Notes et décisions
- **D1** Le poste simulé ne passe pas par la passerelle pour le tour Opus : l'enchaînement d'une commande derrière un
  travail vivant (`queued`) n'est pas observable au banc (la passerelle ne voit pas le tour comme vivant) ; il est
  couvert par les tests d'intégration et de composant ; le banc mesure la **relation** qualifiée.
