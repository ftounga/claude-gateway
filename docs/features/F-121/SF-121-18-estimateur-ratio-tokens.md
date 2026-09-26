# Mini-spec — F-121 / SF-121-18 — Estimateur de compaction : 4 → 3,5 caractères par token

## Identifiant

`F-121 / SF-121-18`

## Feature parente

`F-121` — Parité avec Claude Code (boucle maison). Écart **Lot 3 / P3** du cadrage
`docs/features/F-121/CADRAGE-F-121-parite-claude-code-feuille-de-route.md` §4 :

> **F-121-18** — **Estimateur 4→~3,5 car./token** (une fois les traces intégrées, F-121-00). (P3)

## Statut

`done`

## Date de création

2026-09-26

## Branche Git

`feat/SF-121-18-estimateur-ratio-tokens`

---

## Objectif

Rapprocher l'estimateur de seuil de compaction du **décompte réel** de tokens en passant son
diviseur de **4** à **3,5 caractères par token**, pour que la compaction se déclenche **avant** le
débordement au lieu de laisser le filet réactif « prompt too long » rattraper après coup.

---

## Contexte — pourquoi c'est un écart de parité

`AtelierCompactionService.estimateReplayTokens` estime le contexte rejoué en divisant le nombre de
caractères par `CHARS_PER_TOKEN = 4` (`AtelierCompactionService.java:50,473`). Quatre caractères par
token est le rapport usuel pour de la **prose anglaise**. Or ce que l'Atelier rejoue n'est pas de la
prose : c'est un fil d'agent de développement — chemins de fichiers, diffs, sorties de commandes,
JSON de trajectoires d'outils (SF-119-03 en rejoue désormais jusqu'à 12 tours, et l'estimateur les
compte depuis F-121-00). Ce matériau se tokenise **plus densément** que la prose : la ponctuation,
les identifiants `camelCase`, les séparateurs de chemin et l'indentation coupent les tokens court.

Conséquence : l'estimateur **sous-estime systématiquement** — d'environ 14 % à 3,5 car./token — et le
seuil (`app.atelier.compaction.trigger-tokens`, défaut 120 000) est franchi **en réalité** alors que
l'estimation le croit encore respecté. Ce n'est pas théorique : c'est exactement le scénario que
F-121-00 avait déjà corrigé dans son autre moitié (compter les traces), et dont le résiduel est le
**ratio**. Quand la sous-estimation devient suffisante, la compaction ne se déclenche pas et c'est le
filet réactif « prompt too long » (400, SF-117-02) qui rattrape : un tour perdu, relancé, plus lent.

Une estimation **plus haute que la réalité** ne coûte rien de comparable : elle compacte un peu plus
tôt. L'asymétrie est nette, et elle décide du sens de l'arrondi.

C'est un **réglage d'heuristique de la boucle** : aucune capacité IA n'est réimplémentée
(Provider-First), aucun appel supplémentaire au fournisseur (un décompte exact exigerait un aller-retour
réseau à chaque tour, précisément ce que l'on cherche à borner — Gateway-First), et rien ne change
dans `AIProvider` (Provider Independence).

---

## Comportement attendu

### Nominal

1. `estimateReplayTokens(résumé, messages, traceTurns)` compte les mêmes caractères qu'avant (résumé +
   contenu des messages rejoués + trajectoires d'outils des `traceTurns` derniers tours assistant).
2. Le total de caractères est divisé par **3,5** au lieu de 4. Pour un même fil, l'estimation monte
   d'environ **+14,3 %**.
3. `compactIfOversized` compare cette estimation au seuil configuré, **inchangé** : à fil constant, la
   compaction se déclenche **plus tôt** (en nombre de caractères rejoués), jamais plus tard.
4. Le seuil reste réglable par `app.atelier.compaction.trigger-tokens` : un déploiement qui voudrait
   l'ancien comportement relève le seuil de ~14 %, sans changement de code.

### Cas d'erreur / limites

- **Fil vide ou résumé nul** : 0 caractère → estimation `0`, aucune compaction (inchangé).
- **Fenêtre de traces ≤ 0** : les trajectoires ne sont pas comptées (inchangé) ; seul le ratio change.
- **Estimation toujours approximative** : le ratio reste une **heuristique**, pas un décompte. Le filet
  réactif « prompt too long » (SF-117-02) demeure le garde-fou de dernier recours et n'est pas touché.
- **Débordement arithmétique** : le calcul reste en `long` sur un nombre de caractères borné par la
  sérialisation des traces ; aucun risque nouveau.

---

## Critères d'acceptation

1. `AtelierCompactionService.CHARS_PER_TOKEN` vaut **3,5** et le calcul divise par cette valeur.
2. Pour un même fil, l'estimation est **strictement supérieure** à l'ancienne (ratio 4) et vaut
   `caractères / 3,5` à l'unité près (troncature).
3. Un fil dont l'estimation « ancienne formule » passait **juste sous** le seuil et dont la nouvelle
   passe **au-dessus** déclenche effectivement la compaction (bout en bout).
4. Le seuil `trigger-tokens` n'est pas modifié (défaut 120 000).
5. Aucun appel supplémentaire au fournisseur n'est introduit pour estimer.
6. Aucune migration, aucun endpoint, aucun frontend, aucun changement de protocole runner.

---

## Plan de test minimal

### Unitaires (`AtelierCompactionServiceTest`)

- `estimateUsesThreeAndAHalfCharactersPerToken` : pour un texte de longueur connue, l'estimation vaut
  `longueur / 3,5` (et non `longueur / 4`) — le ratio est **verrouillé par un témoin**, pas seulement
  par une constante.
- `estimateCountsReplayedToolTracesNotJustText` (existant) : la parité traces/texte reste vraie.

### Intégration (bout en bout, `AtelierCompactionServiceTest`)

- `aThreadJustUnderTheOldThresholdNowCompacts` : un fil calibré pour tomber **entre** les deux
  estimations (sous le seuil à 4 car./token, au-dessus à 3,5) déclenche la compaction.

### Isolation utilisateur

- Inchangée et déjà couverte : `compactIfOversized` lit l'historique par
  `findByWorkspaceIdAndUserId...` — la subfeature ne touche **aucun accès données** (calcul pur sur une
  liste déjà filtrée par `(workspaceId, userId)`). Aucun nouveau chemin d'accès n'est introduit.

---

## Tables / endpoints / composants impactés

- **Tables** : aucune. **Migration** : aucune.
- **Endpoints** : aucun.
- **Frontend** : aucun.
- **Protocole runner** : aucun.
- **Backend** : `AtelierCompactionService` (constante + calcul + commentaire), tests associés.

---

## Préoccupations transversales

| Préoccupation | Concernée | Composants impactés |
|---|---|---|
| Auth / Principal | Non | — |
| Contexte tenant | Non | aucun accès données modifié ; l'historique est déjà filtré `(workspaceId, userId)` en amont |
| Plans / limites | **Oui, marginalement** | `AtelierCompactionService.compactIfOversized` (seul appelant du seuil) : la compaction se déclenche plus tôt → un appel de résumé un peu plus fréquent, imputé comme aujourd'hui (F-118). Aucun autre appelant de `estimateReplayTokens` en production (vérifié : seuls le service et son test le référencent). |
| Navigation / routing | Non | — |

---

## Hors périmètre

- **Décompte exact de tokens** (endpoint `count_tokens` du fournisseur) : un aller-retour réseau par
  tour, pour borner un coût — le remède serait plus cher que le mal. Reste hors sujet.
- **Ratio par type de contenu** (prose vs JSON vs diff) : sur-ingénierie pour un garde-fou ; un ratio
  unique, volontairement pessimiste, suffit.
- **Changement du seuil `trigger-tokens`** : le seuil est un réglage d'exploitation, pas l'objet ici.
- Toute autre partie du Lot 3 (SF-121-16, SF-121-20) : subfeatures distinctes.
