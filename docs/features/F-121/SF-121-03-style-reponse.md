# Mini-spec — F-121 / SF-121-03 — Bloc « style de réponse » dans le prompt principal

## Identifiant

`F-121 / SF-121-03`

## Feature parente

`F-121` — Parité avec Claude Code (boucle maison)

## Statut

`in-progress`

## Date de création

2026-09-16

## Branche Git

`feat/SF-121-03-style-reponse`

## Objectif

Ajouter au prompt principal (RUNNER + SANDBOX) un bloc « Style de réponse » — réponse concise orientée
terminal, pas de préambule ni de politesse, markdown léger, citer `chemin:ligne`, pas d'émoji sauf
demande — repris de l'esprit d'`AtelierExploration.SYSTEM`, sans écraser la discipline SF-119-02 ni la
doctrine SF-120-01 déjà présentes.

## Comportement attendu

### Cas nominal

- `buildSystemPrompt` insère un bloc « Style de réponse » **stable** (donc caché par le cache de
  prompt) sur les **deux** cibles, placé dans le préfixe en tête (après la discipline d'investigation
  SF-119-02 et la doctrine de retenue SF-120-01, avant la consigne de mode ANSWER_PLAN), de sorte
  qu'il survive à la coupe `SYSTEM_MAX_CHARS`.
- Le bloc coexiste avec l'existant : la discipline d'investigation (« Vérifie avant d'affirmer ») et
  la doctrine (« Répondre d'abord, agir sur demande ») restent présentes, inchangées.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|----------------------|
| CLAUDE.md absent / arbre vide | le bloc de style reste présent (il vit dans le préfixe, avant toute lecture optionnelle) |
| Consigne très longue (dépasse `SYSTEM_MAX_CHARS`) | le bloc survit (placé en tête, avant la coupe) |

## Critères d'acceptation

- [ ] Le prompt contient le bloc « Style de réponse » sur la cible **RUNNER**.
- [ ] Le prompt contient le bloc « Style de réponse » sur la cible **SANDBOX**.
- [ ] Le bloc dit : concision terminal, pas de préambule/politesse, markdown léger, citer `chemin:ligne`, pas d'émoji sauf demande.
- [ ] La discipline d'investigation (SF-119-02) et la doctrine de retenue (SF-120-01) restent présentes (non écrasées).

## Périmètre

### Hors scope (explicite)

- Toute application du style par post-traitement de la réponse (on ne réécrit pas la sortie du modèle).
- Toute modification d'`AtelierExploration.SYSTEM` (la sous-boucle garde sa consigne propre).
- Tout changement de comportement d'outil ou d'API.

## Technique

### Tables / endpoints

Aucun. Aucune migration. Aucun frontend.

### Composants impactés

| Composant | Changement |
|-----------|-----------|
| `AtelierChatService` | constante `RESPONSE_STYLE` + insertion dans `buildSystemPrompt` (deux cibles) |

### Analyse transversale

- **Auth / tenant / plans / navigation** : aucun impact. Changement de texte de consigne système
  uniquement ; aucune donnée, aucun gate, aucune route.

## Plan de test

### Tests unitaires

- [ ] `buildSystemPrompt(RUNNER)` contient le bloc de style et ses marqueurs clefs.
- [ ] `buildSystemPrompt(SANDBOX)` contient le bloc de style.
- [ ] Le bloc coexiste avec « Vérifie avant d'affirmer » (SF-119-02) et « Répondre d'abord » (SF-120-01).

### Isolation workspace

- [ ] Non applicable — le prompt ne lit aucune donnée d'un autre utilisateur (repli passant si CLAUDE.md absent).

## Notes et décisions

- Placé dans le **préfixe stable** (avant les lectures CLAUDE.md/skills) pour rester caché (cache de
  prompt préservé) et survivre à la coupe `SYSTEM_MAX_CHARS`, exactement comme SF-119-02 et SF-120-01.
- Réversibilité : purement additif (une constante insérée). Le retirer rétablit le prompt d'avant.
