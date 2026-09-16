# Mini-spec — F-121 / SF-121-04 — Retry d'un appel runner transitoire

## Identifiant

`F-121 / SF-121-04`

## Feature parente

`F-121` — Parité avec Claude Code (boucle maison)

## Statut

`in-progress`

## Date de création

2026-09-16

## Branche Git

`feat/SF-121-04-retry-runner`

## Objectif

Réessayer, de façon bornée et dans le budget de tour restant, un appel runner qui échoue en
**transport** (`runner_timeout` / `runner_unavailable` / `runner_not_on_this_node` /
`runner_protocol_error`) **avant** de rendre la main au modèle ; si l'échec persiste, taguer le
résultat « non concluant (réessayé) » — sans jamais rejouer un vrai refus.

## Comportement attendu

### Cas nominal

- Après chaque appel runner (`callRunner`), si l'issue est un **échec de transport**, la boucle
  réessaie jusqu'à `app.atelier.runner-retries` fois (défaut **2**), avec une courte attente
  `app.atelier.runner-retry-backoff-ms` (défaut **250 ms**), **sans dépasser le budget de tour**
  restant ni ignorer une interruption. Un réessai qui aboutit rend un résultat normal, sans erreur.
- Si tous les réessais échouent encore en transport, le résultat est **tagué « (réessayé) »** et rendu
  au modèle comme « non concluant » (cohérent SF-119-04) — jamais comme un résultat négatif. La sortie
  partielle d'un `bash` (SF-119-04) est préservée.
- Un **vrai refus** (`unsupported_tool`, `invalid_input`, argument manquant) n'est **jamais** réessayé.
- Réglable ; `runner-retries=0` désactive le réessai (coupe-circuit).

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|----------------------|
| Timeout puis succès au 2e essai | aucun résultat d'erreur rendu au modèle |
| Timeout persistant après tous les réessais | résultat « non concluant (réessayé) », `isError` vrai |
| Refus dur (`unsupported_tool`/`invalid_input`) | un seul appel, aucun réessai, message inchangé (pas de « réessayé ») |
| Budget de tour presque écoulé | on ne lance pas un réessai qui déborderait la deadline |
| Interruption pendant le réessai | on cède : pas d'attente sur un tour arrêté |

## Critères d'acceptation

- [ ] Un appel transitoire qui réussit au 2e essai ne remonte pas d'erreur.
- [ ] Un échec transitoire persistant reste « non concluant » et est tagué « réessayé ».
- [ ] Un refus dur (`unsupported_tool`/`invalid_input`) n'est pas rejoué.
- [ ] Le réessai reste borné (défaut 2) et dans le budget de tour ; configurable, `0` désactive.

## Périmètre

### Hors scope (explicite)

- Le retry des appels internes d'`edit_file` pris isolément (l'édition entière est réessayée via
  `callRunner`, ce qui suffit).
- Tout retry côté sous-boucle `explore` autre que celui hérité de `callRunner`.
- Toute nouvelle sémantique de « non concluant » : on réutilise celle de SF-119-04.

## Technique

### Tables / endpoints

Aucun. Aucune migration. Aucun frontend.

### Composants impactés

| Composant | Changement |
|-----------|-----------|
| `AtelierChatService` | `callRunnerWithRetry` / `callRunnerOnce` / `retriedTag` autour de `callRunner` dans `executeToolOnRunner` ; `textOutcome` cadre les échecs de transport en « non concluant » pour tout outil ; `@Value` `runner-retries` / `runner-retry-backoff-ms` (+ setter de test) |

### Analyse transversale

- **Auth / tenant** : aucun changement. Le réessai porte sur la même cible `(host, workspace, projet)`
  déjà résolue ; l'interruption est consultée via `turnKey(userId, workspaceId)` (isolation inchangée).
- **Plans / limites** : le réessai consomme du **temps de tour** (budget) mais **aucun token** de
  plus tant que le runner ne répond pas ; il s'arrête net avant la deadline. Aucun nouveau gate de quota.
- **Navigation / routing** : aucun impact UI.

## Plan de test

### Tests unitaires

- [ ] Timeout puis succès au 2e essai → pas d'erreur, `bash` appelé 2 fois.
- [ ] Timeout persistant → 1 + 2 réessais, résultat « non concluant » + « réessayé ».
- [ ] Refus dur → un seul appel, pas de « réessayé ».
- [ ] `runner-retries=0` → un seul appel (coupe-circuit).

### Isolation workspace

- [ ] Non applicable — le réessai vise la cible déjà résolue du tour ; aucune donnée d'autrui lue.

## Notes et décisions

- Réglages par `@Value` (`runner-retries`, `runner-retry-backoff-ms`) plutôt que par composant
  `AtelierProperties` : surface minimale, réversible, sans toucher aux constructeurs de compat.
- Le tag « (réessayé) » est appliqué en **préservant tous les champs** du résultat (dont `streamed`
  pour la sortie partielle d'un `bash`), pour ne rien perdre de l'acquis SF-119-04.
- La reconnaissance d'un échec de transport réutilise `isInconclusiveFailure` (SF-119-04), donc la
  liste close TIMEOUT/UNAVAILABLE/NOT_ON_THIS_NODE/PROTOCOL_ERROR ; `invalid_input`/`unsupported_tool`
  en sont exclus, donc jamais réessayés.
