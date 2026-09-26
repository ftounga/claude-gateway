# Mini-spec — F-121 / SF-121-00 — L'estimateur de compaction compte les traces d'outils réellement rejouées

## Identifiant

`F-121 / SF-121-00`

## Feature parente

`F-121` — Parité avec Claude Code (boucle maison). Dépendance **dure** listée en §1 du cadrage
`docs/features/F-121/CADRAGE-F-121-parite-claude-code-feuille-de-route.md` :

> **F-121-00 — L'estimateur de compaction doit compter les traces d'outils.** […] Sans corriger
> l'estimateur *en même temps* que l'élargissement de la fenêtre (SF-119-03), le seuil de compaction
> est franchi **sans se déclencher**, et c'est le filet réactif « prompt too long » qui rattrape.

C'est aussi la **dépendance dure de SF-121-18** (ratio 4 → 3,5 car./token) : le ratio ne corrige
qu'un facteur multiplicatif ; il ne peut rien contre des caractères **jamais comptés**.

## Statut

`done`

## Date de création

2026-09-26

## Branche Git

`feat/SF-121-00-estimateur-compte-les-traces`

---

## Objectif

Faire compter à l'estimateur de seuil de compaction **exactement les trajectoires d'outils que la
boucle rejoue réellement** — la même fenêtre, calculée par la même règle — pour que le seuil se
déclenche avant le débordement au lieu d'être rattrapé après coup.

---

## Contexte — ce qui existe déjà, et ce qui manque encore

**Vérification de l'existant d'abord** (règle projet : vérifier avant de cadrer).

La **moitié** de SF-121-00 est déjà livrée, à l'intérieur de SF-119-03 comme le cadrage le
demandait : `AtelierCompactionService.estimateReplayTokens(summary, messages, traceTurns)`
(`backend/src/main/java/fr/claudegateway/atelier/AtelierCompactionService.java:459`) additionne
désormais les caractères des `toolTrace` en plus du texte, et deux témoins la verrouillent
(`AtelierCompactionServiceTest.estimateCountsReplayedToolTracesNotJustText`,
`voluminousToolTracesTriggerCompactionEvenWhenTextIsShort`). Cette partie-là n'est **pas**
redéveloppée.

**Le résiduel est la fenêtre**, et il a été rouvert par F-134 sans que l'estimateur suive :

- L'estimateur compte les traces des `traceTurns` **derniers** tours assistant, **comptés depuis la
  fin** (`AtelierCompactionService.java:469-482`).
- La boucle, elle, ne rejoue plus ainsi depuis **F-134 / SF-134-01** : la coupure est calculée
  **depuis le début** et ne bouge **que par paliers**, pour ne pas muter le préfixe mis en cache à
  chaque tour (`AtelierChatService.firstTracedIndex`, `AtelierChatService.java:2725-2755`). Le
  nombre de tours rejoués **avec** leur trajectoire passe donc de *exactement* `traceTurns` à
  **entre `traceTurns` et `2 × traceTurns − 1`** — c'est écrit noir sur blanc dans la javadoc de
  `firstTracedIndex`, et c'est assumé côté qualité (« le modèle voit autant ou plus, jamais moins »).

Conséquence : jusqu'à `traceTurns − 1` tours de traces (11 sur les 12 de défaut) partent au
fournisseur **sans jamais être comptés**. Les trajectoires sérialisées atteignant l'ordre de
40 000 caractères, l'angle mort vaut jusqu'à ~440 000 caractères, soit ~125 000 tokens — davantage
que le seuil lui-même (120 000). C'est **exactement** le mode de panne que SF-121-00 existe pour
interdire, réintroduit par un correctif de cache sans rapport.

L'écart va toujours dans le **mauvais sens** : l'estimateur **sous**-compte, donc la compaction se
déclenche **trop tard** ou pas du tout, et le filet réactif « prompt too long » (SF-117-02) paie un
tour perdu et relancé. L'asymétrie est la même qu'en SF-121-18 : surestimer coûte une compaction un
peu précoce, sous-estimer coûte un tour.

**Cadre architectural** : réglage d'heuristique de la boucle. Aucune capacité IA réimplémentée
(Provider-First), **aucun appel supplémentaire au fournisseur** (un décompte exact exigerait un
aller-retour réseau par tour — Gateway-First), rien ne change dans `AIProvider` (Provider
Independence), aucun accès données nouveau.

---

## Comportement attendu

### Nominal

1. `estimateReplayTokens(résumé, messages, traceTurns)` détermine la frontière des tours tracés avec
   **la règle de la boucle elle-même** — `AtelierChatService.firstTracedIndex(messages, traceTurns)`
   — au lieu de recompter `traceTurns` tours depuis la fin.
2. Les caractères des `toolTrace` de **tous** les messages assistant d'index `≥ firstTracedIndex`
   sont additionnés au texte, puis le total est divisé par `CHARS_PER_TOKEN` (3,5, SF-121-18).
3. À fil constant, l'estimation est **supérieure ou égale** à l'ancienne, jamais inférieure : elle
   couvre désormais les `traceTurns` à `2 × traceTurns − 1` tours réellement rejoués.
4. `compactIfOversized` et `compactNow` comparent cette estimation au seuil **inchangé**
   (`app.atelier.compaction.trigger-tokens`, défaut 120 000) : la compaction se déclenche plus tôt
   quand le fil est gros en traces, jamais plus tard.

### Cas d'erreur / limites

- **Fenêtre `traceTurns ≤ 0`** : forme historique « texte seul » **conservée** — c'est le sens de la
  surcharge à deux arguments `estimateReplayTokens(résumé, messages)`, qui n'a pas de fenêtre à
  connaître. Aucune exception, aucune division par zéro. Ce cas est **inatteignable en production** :
  `AtelierProperties` replie toute valeur nulle ou négative de `app.atelier.replayed-trace-turns` sur
  son défaut (12) avant que le service ne la lise.
- **Fil vide / résumé nul / aucun tour assistant** : 0 caractère compté, estimation `0`, aucune
  compaction (inchangé).
- **Trajectoire illisible** (JSON invalide) : l'estimateur compte la chaîne brute alors que la boucle
  la rejoue vide (`AtelierToolTrace.fromJson(...).replay()` rend une liste vide). Surestimation
  volontairement conservée : le sens de l'erreur est le bon.
- **Message assistant au contenu vide** : la boucle saute le message (et sa trace) ; l'estimateur la
  compte quand même. Surestimation marginale, assumée pour la même raison.
- **Estimation toujours heuristique** : le filet réactif « prompt too long » (SF-117-02) demeure le
  garde-fou de dernier recours et n'est pas touché.

---

## Critères d'acceptation

1. `AtelierCompactionService.estimateReplayTokens(..., traceTurns)` utilise
   `AtelierChatService.firstTracedIndex` — une **seule** source de vérité pour la fenêtre de rejeu,
   aucune arithmétique dupliquée.
2. Pour un fil de `traceTurns + 1` à `2 × traceTurns − 1` tours assistant tracés, l'estimation compte
   **toutes** les traces rejouées (et non `traceTurns` seulement) : strictement supérieure à ce que
   rendait l'ancien calcul.
3. Pour un fil d'au plus `traceTurns` tours assistant, l'estimation est **inchangée** (aucune
   régression de comportement sur les fils courts).
4. Un fil dont les traces hors-fenêtre-glissante suffisent à franchir le seuil **déclenche**
   effectivement la compaction, bout en bout via `compactIfOversized`.
5. `traceTurns ≤ 0` ne lève rien et laisse l'estimation « texte seul » de la surcharge à deux
   arguments strictement inchangée.
6. Seuil `trigger-tokens` inchangé, ratio `CHARS_PER_TOKEN` inchangé (3,5).
7. Aucun appel supplémentaire au fournisseur, aucune migration, aucun endpoint, aucun frontend,
   aucun changement du protocole runner.

---

## Plan de test minimal

### Unitaires (`AtelierCompactionServiceTest`)

- `estimateCountsEveryTraceTheLoopReplaysNotJustTheLastWindow` : fil de 13 tours assistant tracés,
  fenêtre 12 → `firstTracedIndex` vaut 0, donc **13** traces rejouées ; l'estimation les compte
  toutes et dépasse strictement l'ancien décompte à 12.
- `estimateIsUnchangedForThreadsShorterThanTheWindow` : fil de 3 tours assistant, fenêtre 12 →
  l'estimation vaut le texte + les 3 traces (identique à avant).
- `estimateToleratesANonPositiveTraceWindow` : `traceTurns = 0` et `-5` → aucune exception, et
  l'estimation reste celle du texte seul.
- `estimateCountsReplayedToolTracesNotJustText` (existant) : la parité traces/texte reste vraie.

### Intégration (bout en bout, `AtelierCompactionServiceTest`)

- `tracesBeyondTheSlidingWindowStillTriggerCompaction` : fil calibré pour rester **sous** le seuil si
  l'on ne compte que la fenêtre glissante et **au-dessus** en comptant la fenêtre réellement rejouée
  → `compactIfOversized` compacte.

### Non-régression fenêtre (`AtelierReplayWindowTest`)

- La règle de paliers de `firstTracedIndex` n'est pas modifiée : ses témoins existants doivent rester
  verts (le cache F-134 ne doit rien perdre).

### Isolation utilisateur

- Inchangée et déjà couverte : `compactIfOversized` / `compactNow` lisent l'historique par
  `findByWorkspaceIdAndUserId...`. La subfeature est un **calcul pur** sur une liste déjà filtrée
  `(workspaceId, userId)` ; aucun nouveau chemin d'accès aux données n'est introduit.

---

## Tables / endpoints / composants impactés

- **Tables** : aucune. **Migration** : aucune.
- **Endpoints** : aucun. **Frontend** : aucun. **Protocole runner** : aucun.
- **Backend** : `AtelierCompactionService.estimateReplayTokens` (fenêtre + javadoc) et ses tests.
  `AtelierChatService.firstTracedIndex` est **lu**, jamais modifié.

---

## Contraintes de validation

| Champ / réglage | Contrainte | Source |
|---|---|---|
| `app.atelier.replayed-trace-turns` | entier > 0, replié sur le défaut (12) si nul/négatif, plafonné | `AtelierProperties:376-381` (inchangé) |
| `app.atelier.compaction.trigger-tokens` | seuil en tokens, défaut 120 000 | inchangé |
| `CHARS_PER_TOKEN` | 3,5 | SF-121-18, inchangé |

Aucune contrainte structurante nouvelle ; aucune question ouverte de `docs/OPEN_QUESTIONS.md`
impactée.

---

## Préoccupations transversales

| Préoccupation | Concernée | Composants impactés |
|---|---|---|
| Auth / Principal | Non | — |
| Contexte tenant | Non | aucun accès données modifié ; historique déjà filtré `(workspaceId, userId)` en amont (`compactIfOversized`, `compactNow`) |
| Plans / limites | **Oui, marginalement** | `AtelierCompactionService.compactIfOversized` (seul appelant du seuil) : compaction un peu plus fréquente sur les fils riches en traces → un appel de résumé de plus, imputé par le chemin d'usage existant (F-117 / F-118), sans quota séparé. Appelants de `estimateReplayTokens` en production : `compactIfOversized` et `compactNow` uniquement (vérifié). |
| Navigation / routing | Non | — |

---

## Hors périmètre

- **Décompte exact de tokens** (endpoint `count_tokens` du fournisseur) : un aller-retour réseau par
  tour pour borner un coût — le remède plus cher que le mal.
- **Modification de la règle de paliers** de `firstTracedIndex` : c'est l'acquis de cache F-134, on
  s'y **aligne**, on n'y touche pas.
- **Changement du seuil** `trigger-tokens` ou du **ratio** `CHARS_PER_TOKEN` (SF-121-18) : réglages
  d'exploitation déjà tranchés ailleurs.
- Toute autre partie de F-121 : feuille de route close par ailleurs.
