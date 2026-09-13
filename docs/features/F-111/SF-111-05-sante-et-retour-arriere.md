# Mini-spec — [F-111 / SF-111-05] Santé et retour arrière

## Identifiant

`F-111 / SF-111-05`

## Feature parente

`F-111` — Le runner se met à jour d'un clic (cadrage : `CADRAGE-F-111-le-runner-se-met-a-jour.md`)

## Statut

`done` — PR #576 mergée le 2026-09-14

## Date de création

2026-09-13

## Branche Git

`feat/SF-111-05-sante-retour`

---

## Objectif

Une version fraîchement installée doit **se reconnecter en 90 s** ; sinon, ou après **3 plantages**, le
lanceur **revient à la version précédente**, le runner le **signale à la reconnexion** (« mise à jour vers
1.6 échouée, retour à 1.4 » + motif), et seules **deux versions précédentes** restent sur le poste.

---

## Comportement attendu

### Cas nominal

1. **Essai** : sur une sortie 75 vers `V` depuis `P`, le lanceur ouvre un essai (`P → V`, échéance 90 s,
   plantages 0), efface le témoin de santé, démarre `V` avec `CLAUDE_RUNNER_HEALTH_FILE`.
2. **Témoin** : l'enfant écrit `<id> <pid>` dans ce fichier **dès que sa liaison est établie** (WebSocket
   ouvert, ou premier long-poll abouti) — une fois par processus.
3. **Succès** : le lanceur lit le témoin (toutes les secondes) ; `V` connecté → `current-version = V`,
   essai clos, **rétention** : `V` + les deux versions installées les plus récentes sous `V` ; les autres
   dossiers de `versions/` sont supprimés. (La gateway conclut `SUCCEEDED` au `ready` de `V`, SF-111-04.)
4. **Échec par délai** : 90 s sans témoin → l'enfant est arrêté (sans orphelin), `P` redémarre,
   `update-report.json` = `{from: P, to: V, result: "rolled_back", reason: "la version V ne s'est pas
   reconnectée en 90 s"}`, `current-version = P`, le dossier de `V` est supprimé (une prochaine mise à jour
   le retéléchargera et le revérifiera).
5. **Échec par plantages** : pendant l'essai, une sortie autre que `75` et `0` compte comme plantage ; `V`
   est relancée (nouvelle échéance) jusqu'au **3e** plantage, qui déclenche le retour à `P` (motif :
   « 3 plantages, dernier code N »). Les codes `2`–`6` d'une version neuve comptent comme plantages : ils
   n'arrivaient pas à `P` avec les mêmes arguments.
6. **Rapport** : à sa reconnexion, le runner (`P`) lit `update-report.json` et l'envoie dans sa trame `ready`
   (`lastUpdate: {from, to, result, reason}`), puis l'efface. La gateway clôt la ligne active visant `to` en
   **`ROLLED_BACK`** avec le motif ; l'écran dit « Mise à jour vers 1.6 échouée, retour à la version
   précédente : motif ».
7. **Jamais plus ancien** : le retour à `P` est le **seul** chemin qui démarre une version plus ancienne
   que celle en cours ; `startVersion` et `next-version` restent « plus récent seulement ».

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| `P` absent ou altéré au moment du retour | le jar lancé (embarqué) démarre, rapport écrit | — |
| `update-report.json` illisible | ignoré et effacé, `ready` sans `lastUpdate` | — |
| Rapport pour une autre version que la ligne active | ignoré par la gateway (la ligne suit la règle SF-111-04) | — |
| `Ctrl+C` pendant l'essai | tout s'arrête, pas de retour, `current-version` inchangé (`P`) | — |
| Témoin non inscriptible par l'enfant | l'essai échoue au délai → retour à `P`, motif dit | — |
| Suppression d'un dossier de version impossible (fichier verrouillé Windows) | ignorée, retentée à la prochaine rétention | — |

---

## Critères d'acceptation

- [ ] CA1 — `V` connectée dans les 90 s → `current-version = V`, deux versions précédentes conservées, les plus anciennes supprimées (tests unitaires + intégration Linux).
- [ ] CA2 — `V` muette 90 s → arrêt de `V`, redémarrage de `P`, rapport `rolled_back` écrit (intégration Linux avec échéance raccourcie).
- [ ] CA3 — 3 plantages de `V` → retour à `P` avec motif (intégration Linux).
- [ ] CA4 — `P` envoie `lastUpdate` dans `ready` et efface le rapport ; la gateway passe la ligne en `ROLLED_BACK` avec le motif (tests runner + intégration backend).
- [ ] CA5 — Le retour arrière est le seul chemin vers une version plus ancienne (tests unitaires).
- [ ] CA6 — L'écran affiche « échouée, retour à la version précédente : motif » (test frontend).

---

## Périmètre

### Hors scope (explicite)

- Remplacement du lanceur lui-même (reste au prochain démarrage manuel, cadrage §4).
- Retour arrière déclenché depuis la Forge.
- Mise à jour automatique sans clic.

---

## Valeurs initiales

| Élément | Valeur initiale | Règle |
|-------|----------------|-------|
| `update-report.json` | absent | écrit par le lanceur au retour arrière, effacé par le runner après `ready` |
| témoin de santé | absent | effacé avant chaque démarrage d'essai |

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format / Valeurs autorisées | Unicité | Normalisation |
|-------|-------------|-------------|----------------------------|---------|---------------|
| `lastUpdate.result` | Oui | 16 | `rolled_back` | — | inconnu ignoré |
| `lastUpdate.reason` | Non | 500 | texte | — | tronqué |
| `lastUpdate.from/to` | Oui | 64 | identifiant de version | — | — |

---

## Technique

### Endpoint(s)

Aucun nouveau (trame `ready` enrichie).

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| `runner_update_journal` | UPDATE | état `ROLLED_BACK` (valeur déjà prévue en 103) |

### Migration Liquibase

- [x] Non applicable

### Composants

- Runner : `launcher.Launcher` (essai, témoin, retour, rétention), `launcher.LauncherPolicy` (plantages d'essai), `launcher.LauncherHome` (rapport, témoin, `prune`), `launcher.LauncherWatch` (écriture du témoin), `RunnerConnection`/`PollingConnection` (témoin à l'établissement), `ToolDispatcher.readyFrame` (`lastUpdate`).
- Backend : `RunnerUpdateService.onReady` (`ROLLED_BACK`), `RunnerDeclaration`/événement (rapport).
- Frontend : `progressLine` (`ROLLED_BACK`, déjà prévu).

### Préoccupations transversales

- Auth : non. Tenant : **oui** — le rapport est appliqué au poste de la **session** (`RunnerUpdateFrameEvent.hostId`), jamais à un poste nommé dans la trame. Composants : `RunnerCallDispatcher.onReady`, `RunnerUpdateService.onReady`. Plans : non. Navigation : non.

---

## Plan de test

### Tests unitaires

- [ ] `LauncherPolicyTest` — plantages d'essai : 3e → retour ; `0` pendant l'essai → arrêt.
- [ ] `LauncherHomeTest` — rapport écrit/lu/effacé ; `prune` garde la courante + 2 précédentes.
- [ ] `LauncherTest` — décision d'essai (succès, délai, plantages) sur une horloge injectée.
- [ ] `ToolDispatcherTest` — `lastUpdate` dans `ready`.

### Tests d'intégration

- [ ] `LauncherIntegrationTest` (Linux) — succès avec témoin ; délai (échéance raccourcie) → retour ; 3 plantages → retour.
- [ ] `RunnerUpdateCommandApiIntegrationTest` — `ready` avec `lastUpdate` → `ROLLED_BACK` + motif.
- [ ] Smoke de bout en bout (hors suite) : lanceur réel → runner réel → fausse gateway → `update` → vérification (clé de test) → 75 → nouvelle version connectée.

### Isolation workspace

- [x] Applicable — rapport appliqué au poste de la session (test d'intégration).

---

## Dépendances

### Subfeatures bloquantes

- SF-111-02, SF-111-04 — done.

### Questions ouvertes impactées

- Aucune.

---

## Notes et décisions

- **D1 — Témoin fichier** plutôt qu'un canal lanceur ↔ enfant : le lanceur reste sans code réseau et sans
  protocole ; un fichier sous le compte de l'utilisateur suffit à dire « connecté ».
- **D2 — Codes 2–6 d'une version neuve = plantages** : avec les mêmes arguments, `P` tournait ; c'est donc la
  nouvelle version qui est en cause.
- **D3 — Dossier de `V` supprimé au retour** : une prochaine tentative repasse par téléchargement et
  vérification plutôt que de réutiliser un jar qui a échoué.
