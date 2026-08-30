# Mini-spec — F-38 / SF-38-11 — Le délai dépassé n'est plus rendu comme une annulation, et les suites ne clignotent plus

## Identifiant
`F-38 / SF-38-11`

## Feature parente
`F-38` — Exécution sur machine connectée (runner local)

## Statut
`ready`

## Date de création
2026-08-30

## Branche Git
`fix/SF-38-11-issue-terminale-avant-interruption`

---

## Objectif

> Garantir que l'issue terminale d'un appel d'outil est **réservée avant** l'interruption du worker,
> pour qu'un dépassement de délai remonte toujours `timeout` — et jamais `cancelled` volé par le
> worker réveillé par cette même interruption.

---

## Le défaut

`ToolDispatcher.onTimeout()` interrompt le worker **puis** publie l'issue :

```java
interrupt(call);                                                  // (1) réveille le worker
complete(call, ToolOutcome.error("timeout", "…"));                // (2) compareAndSet sur call.done
```

Entre (1) et (2), le worker réveillé remonte son propre `ToolOutcome.error("cancelled", …)` et peut
gagner le `compareAndSet`. La trame émise est alors `cancelled` alors que le délai a bel et bien été
dépassé. `onToolCancel()` a la même structure ; l'effet y est invisible puisque les deux issues
valent `cancelled`, mais la fragilité est identique.

**Portée réelle, au-delà du test** : `BashTool` rend explicitement `cancelled` sur
`InterruptedException`, en s'appuyant sur un commentaire qui affirme que « le code réel (timeout vs
cancelled) est posé par l'aiguilleur ». Cette garantie n'est pas tenue aujourd'hui : une commande
qui dépasse son `timeoutMs` peut être rapportée à la gateway comme annulée par l'utilisateur.

**Symptôme observé** : `ToolDispatcherTest.termineEnTimeoutQuandLeDelaiEstDepasse` échoue
20–30 % des exécutions (`expected: <timeout> but was: <cancelled>`), ce qui rend la suite runner
rouge par intermittence.

### Second défaut, de nature différente — côté backend

`RunnerCallDispatcherTest.cancelWorkspaceSendsAToolCancelForEachInFlightCall` vérifie deux
`sendMessage` **immédiatement** après `cancelWorkspace`. Or les trames passent par
`ConcurrentWebSocketSessionDecorator`, qui met la seconde en tampon tant que l'envoi de la première
est en cours et la flush sur un autre thread : au moment du `verify`, elle n'a pas toujours atteint
le mock (`TooFewActualInvocations: wanted at least 2 times, but was 1`).

Ici le code de production est **correct** — c'est l'assertion qui est trop pressée. Les deux défauts
sont réunis dans cette subfeature parce qu'ils ont la même conséquence : une suite rouge par
intermittence après le lot F-38. Ils sont corrigés différemment, chacun selon sa nature.

---

## Comportement attendu

### Cas nominal
1. Un `tool_call` dépasse son `timeoutMs` → la trame terminale porte **toujours** `error.code = timeout`,
   quelle que soit l'issue que le worker interrompu aurait voulu rendre.
2. Un `tool_cancel` sur un appel en vol → la trame terminale porte **toujours** `error.code = cancelled`.
3. Dans les deux cas, le worker est **quand même** interrompu : aucun thread ne reste à travailler
   pour un appel déjà terminé.
4. Exactement **une** trame terminale par identifiant, comme aujourd'hui (invariant du contrat §2.5).

### Cas d'erreur

| Situation | Comportement attendu |
|---|---|
| Le worker termine normalement juste avant l'échéance | Son résultat est publié ; le `timeout` qui suit ne publie rien (CAS déjà pris) |
| `tool_cancel` reçu après la trame terminale | Ignoré, aucune seconde trame |
| `tool_cancel` sur un identifiant inconnu | Ignoré en silence (inchangé) |
| Socket perdue (`abortAll`) | Appels abandonnés sans trame, worker interrompu (inchangé) |
| L'interruption lève une exception dans le worker | L'issue déjà réservée reste celle qui est émise |

---

## Critères d'acceptation

- [ ] `onTimeout` réserve l'issue (`complete`) **avant** `interrupt` ; `onToolCancel` de même.
- [ ] Le worker est interrompu dans tous les cas, y compris quand le `compareAndSet` a échoué.
- [ ] `termineEnTimeoutQuandLeDelaiEstDepasse` passe **50 fois sur 50** (le test était flaky à 20–30 %).
- [ ] Un test dédié prouve la course : un outil qui rend explicitement `cancelled` sur interruption,
      soumis à un dépassement de délai, produit `timeout`.
- [ ] L'invariant « une seule trame terminale par identifiant » reste vérifié.
- [ ] La suite runner complète est verte (142 tests) et la suite backend aussi (1048 tests).
- [ ] `RunnerCallDispatcherTest` attend le flush du décorateur au lieu de vérifier immédiatement.

---

## Périmètre

### Hors scope (explicite)
- Le flag multi-replica du mode `RUNNER` (routage `findLocal()`) — décision d'architecture distincte.
- La soumission de `write_file` à la validation d'action (arbitrage tracé en SF-38-08).
- Le budget SCSS du frontend et les avertissements de build.
- Toute évolution du contrat de messages : aucun type, champ ni code d'erreur n'est ajouté.

---

## Technique

### Fichiers impactés

| Fichier | Nature |
|---|---|
| `runner/src/main/java/fr/claudegateway/runner/ToolDispatcher.java` | inversion de l'ordre réserver/interrompre dans `onTimeout` et `onToolCancel` |
| `runner/src/test/java/fr/claudegateway/runner/ToolDispatcherTest.java` | test de course dédié |
| `backend/src/test/java/fr/claudegateway/runner/channel/RunnerCallDispatcherTest.java` | `verify(timeout(2000).atLeast(2))` au lieu d'un `verify` immédiat |

### Tables impactées
Aucune.

### Migration Liquibase
- [x] Non applicable

### Composants Angular
Aucun.

---

## Plan de test

### Tests unitaires (module runner)
- [ ] Dépassement de délai avec un outil qui rend `cancelled` sur interruption → issue `timeout`.
- [ ] `tool_cancel` sur appel en vol → issue `cancelled`.
- [ ] Une seule trame terminale par identifiant dans les deux cas.
- [ ] Le test historiquement flaky rejoué en boucle (50 itérations) reste vert.

### Tests d'intégration
Aucun endpoint n'est touché. Côté backend, seule l'assertion de
`RunnerCallDispatcherTest.cancelWorkspaceSendsAToolCancelForEachInFlightCall` change : elle attend
désormais le flush du décorateur au lieu de l'observer par chance. Le comportement vérifié est
identique (deux trames, la seconde étant le `tool_cancel` du bon identifiant).

### Isolation
- [x] Sans objet — aucun accès aux données, aucun endpoint, aucun changement de contrat.

---

## Dépendances

### Subfeatures bloquantes
- `SF-38-04` (aiguilleur d'outils) — **done** ; `SF-38-07` (bash) — **done**.

### Questions ouvertes impactées
Aucune.

---

## Préoccupation transversale
Aucune : ni auth, ni contexte tenant, ni plan/limite, ni routage. Le changement est interne au
module runner et ne modifie ni le protocole ni une signature publique.
