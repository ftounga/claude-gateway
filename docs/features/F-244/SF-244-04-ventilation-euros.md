# Mini-spec — F-244 / SF-244-04 — La ventilation en € à l'écran

## Identifiant
`F-244 / SF-244-04` · Feature parente `F-244` « Le budget propre de Jarvis » · Statut `in-review` · 2026-11-03 ·
Branche `feat/SF-244-04-ventilation-euros` · Estimation 0,75 j · Dépend de SF-244-01 et SF-244-03 (jauge)

## Objectif
> Le PO voit en € ce que Jarvis a coûté, poste par poste, et où il en est de son plafond — et le règle depuis l'écran.

## Comportement attendu

### Cas nominal
- `GET /api/jarvis/costs` : aujourd'hui et le mois, **six postes** (`fastBrain` « Conversation rapide », `voice`
  « Voix », `listening` « Transcription » = flux + retranscription + dictée, `opus` « Recherches Opus », `mail`
  « Courrier », `agenda` « Agenda »), `total` = **somme des postes affichés** (arrondis au centime), et `budget`
  (l'état de SF-244-03 : plafond, dépense, %, état, s'applique) — ou `null` si illisible.
- `/jarvis` : le compteur existant (« Jarvis : x € aujourd'hui · y € ce mois ») devient un bouton qui ouvre le volet
  **« Ce que coûte Jarvis »** : tableau des six postes (aujourd'hui / ce mois) + total ; jauge du mois face au plafond
  (crème, **orange `--cg-orange-2` à partir de 80 %**) ; note d'état (80 %, plafond atteint, 2 ×) ; réglage du plafond
  (`mat-form-field` outline, 0 à 10 000 €) et « Appliquer aussi avec ma clé personnelle » (BYOK, D7) ; enregistrer relit
  le compteur. **Masqué en présentation** (compteur et volet).
- Mesure admin `GET /api/admin/jarvis/measure` : + `costs`, la même ventilation en € sur la fenêtre.
- Écrans de coût Gateway : `/rapports` (consommation par client) affiche « Jarvis (budget propre, hors quota) : x € ·
  n jetons » ; `/admin/cost` affiche « dont Jarvis x € » au total et « dont x € via Jarvis » par client.

### Cas d'erreur
| Situation | Comportement attendu | Code HTTP |
|---|---|---|
| Plafond saisi hors bornes | Message dans le volet, rien n'est envoyé | — |
| Enregistrement en échec | « Le plafond n'a pas pu être enregistré. » | 4xx/5xx |
| Budget illisible | Le volet montre la ventilation sans jauge | 200 (`budget: null`) |
| Mode présentation | Ni compteur ni volet | — |

## Critères d'acceptation
- [x] Les six postes et le total du jour et du mois sont affichés en € à 2 décimales, et le total égale la somme.
- [x] La jauge passe à l'orange à 80 %.
- [x] En présentation, rien n'est affiché.
- [x] Pas de débordement à 360 × 740 (volet pleine largeur moins 2 × 8 px, `overflow-wrap: anywhere`, chiffres
      `nowrap` en JetBrains Mono).
- [x] Aucun montant en $.

## Plan de test
- Specs : `jarvis-cost-panel.component.spec.ts` (six postes, total, « $ » absent, jauge 79/80 %, réglage valide /
  hors bornes / échec, largeur 360), `jarvis.component.spec.ts` (le compteur ouvre le volet ; rien en présentation),
  `reports.component.spec.ts` (ligne Jarvis), `admin-cost.component.spec.ts` (« dont Jarvis »).
- Intégration : `JarvisCostApiIntegrationTest` (six postes, total = somme, plafond et état, isolation A/B).
- Contrôle visuel headless : remplacé par la spec de largeur (aucun déploiement dans cette vague).

## Impacts
- Endpoints : `GET /api/jarvis/costs` (+`mail`, `agenda`, `budget` ; total = somme des postes arrondis),
  `GET /api/admin/jarvis/measure` (+`costs`). Ajouts rétro-compatibles.
- Composants : `JarvisCostService`, `JarvisCostController`, `JarvisAdoptionService` ; front
  `JarvisCostPanelComponent` (nouveau), `JarvisComponent`, `jarvis.models`, `ReportsComponent`, `AdminCostComponent`,
  `usage-by-client.models`, `admin-cost.models`.
- `docs/DESIGN_SYSTEM.md` §22 : une ligne pour le volet et la jauge (aucune couleur nouvelle).

## Préoccupations transversales
Aucune (lecture et affichage ; le réglage passe par l'API de SF-244-03).

## Arbitrages
- Le réglage du plafond (prévu en SF-244-03) est dans ce volet, à côté de la jauge.
- Total = somme des postes **arrondis** : l'écran additionne juste (écart au centime avec l'ancien total supprimé).
- Courrier et agenda affichés à 0,00 € tant que F-242 / F-243 ne sont pas livrées (postes prévus, D10).

## Hors périmètre
Un export.
