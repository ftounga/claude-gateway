# Mini-spec — F-121 / SF-121-05 — Porte de complétude générique (plan inachevé)

## Identifiant

`F-121 / SF-121-05`

## Feature parente

`F-121` — Parité avec Claude Code (boucle maison)

## Statut

`in-progress`

## Date de création

2026-09-23

## Branche Git

`feat/SF-121-05-porte-de-completude`

---

## Objectif

> En une phrase : quand le modèle rend la main alors que le plan du tour (`set_plan` /
> `AtelierPlan`) porte encore des étapes `pending`/`active`, un contrôle **déterministe** de fin de
> tour bloque la clôture et réinjecte une consigne de reprise — **zéro appel LLM**, in-flux.

---

## Comportement attendu

### Cas nominal

- À la fin d'un tour où le modèle rend sa réponse finale sans demander d'outil, la boucle interroge
  déjà le crochet `END_OF_TURN` (F-50 / SF-50-02). SF-121-05 y branche un **nouveau contrôle**
  (`PlanCompletudeCheckpoint`, bean Spring, patron F-50) qui reçoit désormais **le plan du tour**
  dans le contexte de fin de tour.
- Si le plan **existe** (au moins une étape) **et** porte au moins une étape `PENDING` ou `ACTIVE`,
  le contrôle **bloque** : la boucle repart avec une consigne de reprise (« il reste des étapes non
  terminées dans ton plan : … ; termine-les ou mets le plan à jour avant de conclure »). Le geste
  attendu est porté par le message (règle du verdict F-50).
- Aucun appel au fournisseur n'est fait par le contrôle : il lit l'état de plan déjà tenu en mémoire
  (`planOfTurn`) et rend un verdict synchrone.
- La borne existante `MAX_END_OF_TURN_BLOCKS` (3) de la boucle s'applique telle quelle : après 3
  blocages, la main est rendue au modèle — aucun risque de boucle infinie.
- Un **coupe-circuit** de configuration `app.atelier.plan-completeness-gate` (défaut **true**) permet
  de désactiver le contrôle ; à `false`, il laisse toujours passer (comportement d'avant SF-121-05).

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|----------------------|
| Aucun plan posé pendant le tour (`planOfTurn` vide) | le contrôle **laisse passer** — « ne se déclenche que si un plan existe » |
| Plan présent, toutes les étapes `DONE` | laisse passer — rien à reprendre |
| Plan présent avec ≥ 1 étape `PENDING`/`ACTIVE` | **bloque** avec la liste (bornée) des étapes non terminées |
| 3 blocages déjà atteints dans le tour | main rendue (borne `MAX_END_OF_TURN_BLOCKS` inchangée) |
| Coupe-circuit `plan-completeness-gate=false` | laisse toujours passer |
| Le contrôle lève une exception | ignoré par `AtelierCheckpointRunner` (repli passant D2) — le tour n'est jamais pris en otage |

---

## Critères d'acceptation

- [ ] Fin de tour, plan avec une étape `PENDING` → le contrôle **bloque** et la correction nomme
      l'étape restante.
- [ ] Fin de tour, plan avec une étape `ACTIVE` → bloque.
- [ ] Fin de tour, plan **entièrement** `DONE` → laisse passer.
- [ ] Fin de tour **sans plan** (`planOfTurn` vide) → laisse passer (déclenchement conditionné à
      l'existence d'un plan).
- [ ] Le contexte de fin de tour transporte le plan du tour jusqu'au contrôle (nouvelle donnée
      `plan` dans `AtelierCheckpointContext`, défaut `AtelierPlan.EMPTY` partout ailleurs).
- [ ] Coupe-circuit `app.atelier.plan-completeness-gate=false` → le contrôle laisse toujours passer.
- [ ] **Zéro appel LLM** dans le contrôle (vérifié par construction : aucune dépendance provider
      injectée ; test unitaire pur, sans fournisseur).
- [ ] La borne `MAX_END_OF_TURN_BLOCKS` reste le seul garde anti-boucle ; aucun nouveau chemin de
      relance n'est ajouté à la boucle.

---

## Périmètre

### Hors scope (explicite)

- **Aucune consigne de prompt** (le F-121-05 « historique » de la feuille de route — inciter
  `set_plan`, résumé « fait/vérifié/reste » — relève du prompt système et n'est **pas** cet objet ;
  cette SF est la **porte déterministe**, complémentaire, décidée par le PO).
- Neutralisation du crochet `END_OF_TURN` en mode Réponse/Plan (**F-121-17**, SF distincte) : ici on
  ne se déclenche que si un plan `set_plan` existe, ce qui écarte de fait les tours de simple réponse.
- Persistance du plan par thread, approbation oui/non du plan (**F-121-10**).
- Toute modification de la discipline d'investigation (**F-119**) ou du style (SF-121-03).
- Tout frontend : le blocage de fin de tour est déjà rendu à l'écran par le mécanisme existant
  (`CHECKPOINT_BLOCK_LABEL`, SF-39-17) ; aucun composant Angular nouveau.

---

## Valeurs initiales

Sans objet — aucune entité créée, aucun état persisté. L'état de plan (`planOfTurn`) est déjà tenu
en mémoire pendant le tour (F-39 / SF-39-13).

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format / Valeurs autorisées | Unicité | Normalisation |
|-------|-------------|-------------|----------------------------|---------|---------------|
| `app.atelier.plan-completeness-gate` | Non | — | booléen (défaut `true`) | — | — |
| correction rendue au modèle | — | `MAX_CORRECTION_CHARS` (2000, déjà borné par `AtelierCheckpointVerdict`) | texte | — | trim + troncature (existant) |

Notes :
- La liste des étapes non terminées reportée dans la correction est **bornée** (au plus quelques
  titres) et déjà tronquée globalement par le verdict à 2000 caractères.
- Les titres d'étapes sont déjà normalisés/élagués par `AtelierPlan.from` (F-39).

---

## Technique

### Endpoint(s)

Aucun.

### Tables impactées

Aucune. **Aucune migration Liquibase.** L'état de plan vit en mémoire pendant le tour.

### Migration Liquibase

- [ ] Oui
- [x] Non applicable

### Composants Angular (si applicable)

Aucun.

### Composants impactés (backend)

| Composant | Changement |
|-----------|-----------|
| `AtelierCheckpointContext` | Nouveau champ `AtelierPlan plan` (défaut `AtelierPlan.EMPTY`) ; nouvelle fabrique `endOfTurn(..., plan)` ; les fabriques existantes défaltent le plan à `EMPTY` (rétrocompat stricte des appelants et tests) |
| `PlanCompletudeCheckpoint` (nouveau) | Bean `AtelierCheckpoint` de kind `END_OF_TURN` : bloque si `context.plan()` non vide et porte une étape `PENDING`/`ACTIVE` ; coupe-circuit `@Value app.atelier.plan-completeness-gate` (défaut true) ; **aucune** dépendance provider |
| `AtelierChatService` | Au point d'accroche `END_OF_TURN` (≈ ligne 1698), passer `planOfTurn.get()` à la fabrique `endOfTurn(...)` — seul appel modifié ; aucune autre logique de boucle touchée |

### Analyse transversale — préoccupations

- **Auth / Principal** : aucun changement. Le contrôle reçoit `(userId, workspaceId)` déjà résolu et
  possédé (isolation garantie en amont par la boucle, comme tout crochet F-50). Endpoints inchangés.
- **Contexte tenant** : aucun nouveau moyen de résoudre le tenant. Le plan lu est celui du tour en
  cours, jamais d'un autre workspace. Composants résolvant le tenant : inchangés.
- **Plans / limites** : le contrôle **ne consomme aucun token** (zéro appel LLM). Une relance
  éventuelle consomme du budget de tour comme n'importe quel blocage END_OF_TURN existant, et reste
  bornée par `MAX_END_OF_TURN_BLOCKS` (3) — aucun nouveau gate de quota, aucun nouveau plafond.
- **Navigation / routing** : aucun impact UI ; le blocage réutilise le rendu de bloc de contrôle
  existant (`CHECKPOINT_BLOCK_LABEL`).

---

## Plan de test

### Tests unitaires

- [ ] `PlanCompletudeCheckpoint` — plan avec une étape `PENDING` → `blocked()` vrai, correction non vide.
- [ ] `PlanCompletudeCheckpoint` — plan avec une étape `ACTIVE` → bloque.
- [ ] `PlanCompletudeCheckpoint` — plan entièrement `DONE` → `proceed()`.
- [ ] `PlanCompletudeCheckpoint` — plan vide / `null` → `proceed()`.
- [ ] `PlanCompletudeCheckpoint` — coupe-circuit désactivé → `proceed()` même avec étape `PENDING`.
- [ ] `PlanCompletudeCheckpoint` — kind() == `END_OF_TURN`.
- [ ] `AtelierCheckpointContext` — `endOfTurn(..., plan)` transporte le plan ; les fabriques
      historiques rendent `AtelierPlan.EMPTY`.

### Tests d'intégration (boucle)

- [ ] `AtelierChatService` — au bout d'un tour dont le plan porte une étape `pending`, le crochet
      `END_OF_TURN` bloque puis la boucle repart (réutilise l'infra de test de blocage END_OF_TURN
      existante ; vérifie que `planOfTurn` est bien câblé au contexte).

### Isolation workspace

- [x] Non applicable — le contrôle ne lit aucune donnée d'autrui : il inspecte l'état de plan du tour
      en cours, déjà cadré par `(userId, workspaceId)` possédé. Aucune requête base.

---

## Dépendances

### Subfeatures bloquantes

- F-50 / SF-50-01, SF-50-02 (crochet `END_OF_TURN` + registre) — **Done**.
- F-39 / SF-39-13 (`AtelierPlan` / `set_plan`, état de plan du tour) — **Done**.

### Questions ouvertes impactées

- [ ] Aucune question de `docs/OPEN_QUESTIONS.md` impactée.

---

## Notes et décisions

- **Réutilisation, pas réinvention** : on branche un bean sur le crochet `END_OF_TURN` existant et on
  lit l'état de plan `planOfTurn` existant. Aucun nouveau point d'accroche, aucun nouveau chemin de
  relance, aucune nouvelle borne — la seule protection anti-boucle reste `MAX_END_OF_TURN_BLOCKS`.
- **Déterministe / Gateway-First** : le contrôle est une comparaison d'états (`Status`), zéro
  intelligence, zéro appel modèle → il ne fait pas du backend un « moteur IA ».
- **Provider Independence** : le contrôle n'a aucune dépendance à un provider ; il n'invoque jamais
  `AIProvider`/`AiAgentProvider` (a fortiori jamais Anthropic en dur).
- **Cache de prompt (F-134)** : la porte **ne touche pas** le préfixe système ; rien de volatil n'est
  ajouté au prompt. Le cache reste intact.
- **F-119** : aucune modification de la discipline d'investigation ni des descriptions edit/write.
- **Aucun composant cluster** : tout se joue dans le processus de la gateway, dans le tour.
- **Toujours-actif par défaut** (bean `@Component` auto-découvert par `AtelierCheckpointRunner`),
  avec coupe-circuit de config — c'est un comportement de parité de la boucle maison, pas une règle
  de gouvernance activable par paquet (F-51). Distinct des contrôles gouvernance (délégués à
  l'activation F-51).
