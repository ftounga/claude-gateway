# Mini-spec — F-117 / SF-117-02 Le dépassement de fenêtre ne casse plus le tour

## Identifiant

`F-117 / SF-117-02`

## Feature parente

`F-117` — Le contexte d'un terminal ne déborde jamais

## Statut

`ready`

## Date de création

2026-09-14

## Branche Git

`feat/SF-117-02-repli-400`

---

## Objectif

> En une phrase : détecter le **400 « prompt too long »** du fournisseur (aujourd'hui non rejouable,
> il tue le tour) et, au lieu d'échouer, **déclencher une compaction (SF-117-01) puis relancer le
> tour une fois** — avec un message clair si ça dépasse encore. Plus jamais d'échec dur silencieux.

---

## Comportement attendu

### Cas nominal

1. `AnthropicAgentProvider.callWithRetry` traduit le **400 dont le corps dit « prompt is too long »**
   en une exception neutre `AgentPromptTooLongException` (Provider Independence : le domaine ne
   dépend pas d'Anthropic). Les autres 400 restent un échec fournisseur inchangé.
2. Dans `AtelierChatService`, l'appel fournisseur d'un tour est enveloppé : sur
   `AgentPromptTooLongException`, si la compaction est disponible et **pas encore déclenchée** pour ce
   message, on **force une compaction** (`compactNow`), on **rebâtit** la conversation depuis la
   nouvelle frontière + résumé, et on **relance l'appel une fois**.
3. Si la relance aboutit, l'utilisateur reçoit sa réponse — le débordement a été absorbé de façon
   transparente.

### Cas d'erreur

| Situation | Comportement attendu | Effet |
|-----------|---------------------|-------|
| Débordement mais compaction absente/désactivée | Message clair, pas d'échec dur | `finalText` = message de débordement, tour clos proprement |
| Débordement, rien à compacter (fil déjà court) | Message clair | idem |
| Débordement encore présent après compaction + relance | Message clair invitant au « nouveau départ » | idem |
| Appel de résumé lui-même en échec (fil énorme) | Best-effort (SF-117-01) → `compactNow` renvoie « non compacté » → message clair | idem |
| 400 autre que « prompt too long » | Échec fournisseur inchangé (`AIProviderException`) | comportement d'avant F-117 |

---

## Critères d'acceptation

- [ ] Un 400 « prompt too long » simulé **ne tue plus le tour** : la compaction est déclenchée et le
      tour est **relancé une fois**, aboutissant à une réponse.
- [ ] La compaction+relance a lieu **au plus une fois** par message (pas de boucle).
- [ ] Si ça dépasse encore après compaction, un **message clair** est rendu (pas d'exception qui
      remonte, pas de message vide).
- [ ] Un 400 « prompt too long » est traduit en exception **neutre** (`AgentPromptTooLongException`),
      pas une dépendance directe à Anthropic dans le domaine.
- [ ] Les autres statuts (429/529 rejoués, autres 400/500 en échec) sont **inchangés**.
- [ ] Le cache de prompt, le décompte d'usage, l'isolation restent inchangés (la relance passe par le
      même chemin, la conso de compaction est agrégée au tour).

---

## Périmètre

### Hors scope (explicite)

- La compaction **proactive** par estimation (SF-117-01, déjà livrée) — ici c'est le filet **réactif**.
- Le nouveau départ visible / suggéré (SF-117-03).
- Le reaper et les fuites (SF-117-04).

---

## Contraintes de validation

| Champ | Règle |
|-------|-------|
| Détection 400 | corps de réponse contenant « prompt is too long » (insensible à la casse) ; les autres 400 ne sont pas concernés |
| Relance | exactement **une** par message |

---

## Technique

### Endpoint(s)

Aucun.

### Tables impactées

Aucune (réutilise le résumé/frontière de SF-117-01).

### Migration Liquibase

- [ ] Non applicable.

### Composants Angular

- Aucun.

---

## Plan de test

### Tests unitaires

- [ ] `AgentRetryPolicy` / provider : un 400 « prompt too long » est reconnu et traduit ; un autre 400
      ne l'est pas ; 429/529 restent rejoués.
- [ ] `AtelierChatService` — 400 « prompt too long » simulé au premier appel → compaction forcée +
      relance → réponse (au lieu d'échec).
- [ ] `AtelierChatService` — débordement persistant (compaction ne réduit pas) → message clair, pas
      d'exception.
- [ ] `AtelierChatService` — compaction absente → message clair (pas d'échec dur).

### Tests d'intégration

- [ ] Contexte Spring inchangé (aucun schéma).

### Isolation workspace

- [x] Applicable — la compaction forcée réutilise le chemin isolé de SF-117-01 (`requireOwned` en
      amont, repository filtré `user_id`/`workspace_id`).

---

## Dépendances

### Subfeatures bloquantes

- `SF-117-01` — statut : **done** (la compaction est réutilisée par le repli).

### Questions ouvertes impactées

- Aucune.

---

## Notes et décisions

- **D1 — Exception neutre.** Le 400 est reconnu et traduit dans `AnthropicAgentProvider` (le seul
  endroit qui connaît Anthropic) ; le domaine ne voit qu'`AgentPromptTooLongException`.
- **D2 — Une seule compaction+relance par message.** Un drapeau par tour borne le filet : si ça
  déborde encore, on rend un message clair plutôt que de boucler (coûteux, et le signe d'un fil
  irréductible → « nouveau départ », SF-117-03).
- **D3 — Rebâtir depuis la base.** Après la compaction forcée, la conversation est reconstruite depuis
  la nouvelle frontière (le message utilisateur et les précisions sont déjà persistés) ; les messages
  en vol de l'itération courante sont abandonnés (au pire la 1re itération, sans messages en vol).
- **D4 — `compactNow` (forcé).** Variante de `compactIfOversized` sans la garde de seuil, pour le cas
  où l'estimation heuristique a sous-compté le contexte réel. Best-effort : si même le résumé
  déborde, `compactNow` renvoie « non compacté » et le message clair prend le relais.
