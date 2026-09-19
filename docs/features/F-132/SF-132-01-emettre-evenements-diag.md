# Mini-spec — [F-132 / SF-132-01] Émettre les événements de diagnostic (runner)

## Identifiant

`F-132 / SF-132-01`

## Feature parente

`F-132` — Observabilité du runner (journal de diagnostic)

## Statut

`ready`

## Date de création

2026-09-19

## Branche Git

`feat/SF-132-01-emettre-evenements-diag`

---

## Objectif

> En une phrase : instrumenter les points clés du runner (Chrome managé, sonde Teams, capture, boucle Vigie, erreurs) pour émettre, sur le WebSocket existant, une trame `runner_diag` d'événements **structurés, à niveaux, EXPURGÉS et batchés**, sans jamais impacter le fonctionnement du runner.

---

## Comportement attendu

### Cas nominal

1. Un collecteur statique `RunnerDiag` (même esprit que `RunnerActivity`) reçoit des événements aux points d'instrumentation. Chaque événement porte : niveau (`DEBUG`/`INFO`/`WARN`/`ERROR`), catégorie (`chrome`/`teams`/`capture`/`vigie`/`error`), un code court, un message court **expurgé** (optionnel) et une petite carte de champs **scalaires** (nombres, états d'énumération, classe d'URL).
2. Un seuil de niveau (défaut **INFO**) filtre à l'émission : un événement `DEBUG` est ignoré tant que le seuil est INFO. Le seuil est **réglable** (`RunnerDiag.setLevel`) — utilisé par SF-132-05 ; ici il vaut INFO par défaut.
3. Le collecteur est un **anneau borné** (capacité fixe, ex. 500) : au-delà, le plus ancien est écrasé et un compteur `dropped` est tenu.
4. Un émetteur `RunnerDiagEmitter` **draine par lots** (~toutes les 5 s, planifié sur l'exécuteur du heartbeat), sérialise jusqu'à N événements (ex. 200) en une trame `runner_diag` et l'émet via le `FrameSender` existant. Rien à drainer → aucune trame (pas de spam).
5. Points d'instrumentation :
   - **Chrome managé** (`ManagedChrome.ensureRunning`) : état `REACHABLE`/`LAUNCHED`/`UNREACHABLE`/`NO_BROWSER`, exécutable retenu (**nom du fichier**, jamais le chemin complet), port ; échec + code.
   - **Sonde Teams** (`VigieSonde`/`TeamsProbe`) : verdict (connecté / reconnexion requise / onglet non ouvert), nombre de réponses observées (**un nombre, pas les valeurs**), URL de l'onglet **classifiée** (`MicrosoftDomains.isSignIn` → classe, jamais l'URL brute).
   - **Capture** (`TeamsTools`/`MeetingTabCapture`) : join ok/échec + raison, start/stop, octets audio, nombre d'images (**pas** le média).
   - **Boucle Vigie / heartbeat** (`VigieLoop`) : tick, readiness assemblée (états, pas de contenu).
   - **Erreurs** : type d'exception + message court **expurgé**.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Émission impossible (socket absente, file arrêtée) | La trame est **abandonnée** sans erreur (best-effort, contrat `FrameSender`) ; le runner continue. |
| Un point d'instrumentation lève dans `RunnerDiag.event(...)` | **Avalé** : jamais propagé à l'appelant (le fonctionnement du runner n'est jamais impacté). |
| Débit d'événements supérieur à la capacité de l'anneau | Le plus ancien est écrasé ; `dropped` est incrémenté et remonté dans la trame suivante. |
| Un champ non scalaire / potentiellement sensible est passé | Ignoré / réduit par l'expurgation à la source (seuls scalaires acceptés ; URL → classe). |

---

## Critères d'acceptation

- [ ] Un collecteur `RunnerDiag` accepte des événements à niveaux et filtre selon un seuil réglable (défaut INFO).
- [ ] Les événements émis ne contiennent **jamais** : secret, jeton, URL brute (seule sa classe), chemin complet sensible, contenu Teams. (Test d'expurgation explicite.)
- [ ] La trame `runner_diag` est **batchée** (un lot de plusieurs événements en une trame) et n'est émise que s'il y a des événements.
- [ ] L'anneau est borné : au-delà de la capacité, le plus ancien est écrasé et `dropped` est comptabilisé.
- [ ] L'émission est **best-effort** : une émission impossible (pas de socket) ou une exception à un point d'instrumentation n'interrompt jamais le runner ni le heartbeat.
- [ ] Les points clés (Chrome, sonde Teams, capture, Vigie, erreurs) émettent effectivement des événements (tests par point).
- [ ] `ManagedChrome` remonte le **nom** de l'exécutable, jamais le chemin complet ; l'URL d'onglet est **classifiée**.

---

## Périmètre

### Hors scope (explicite)

- La **réception / le stockage / l'exposition** côté gateway (SF-132-02).
- Le **panneau UI** (SF-132-03).
- La **commande** de passage en DEBUG depuis la gateway (SF-132-05) — ici seul le mécanisme de seuil réglable existe (défaut INFO).
- Le **snapshot à la demande** (SF-132-04, option).
- Tout log brut / firehose / streaming de contenu.

---

## Valeurs initiales

| Champ | Valeur initiale | Règle |
|-------|----------------|-------|
| seuil de niveau | `INFO` | défaut confirmé PO ; réglable via `RunnerDiag.setLevel` (SF-132-05) |
| capacité anneau | 500 | borné en mémoire, écrasement du plus ancien |
| taille de lot max | 200 | par trame `runner_diag` |
| période de drain | ~5 s | planifiée sur l'exécuteur du heartbeat |

---

## Contraintes de validation

| Champ | Obligatoire | Format / Valeurs autorisées | Normalisation |
|-------|-------------|----------------------------|---------------|
| `level` | Oui | `DEBUG` / `INFO` / `WARN` / `ERROR` | — |
| `cat` (catégorie) | Oui | texte court `[a-z_]` (chrome/teams/capture/vigie/error) | tronqué |
| `code` | Oui | texte court `[a-z0-9_]` | tronqué |
| `msg` | Non | texte court **expurgé** | tronqué (ex. 300 car), pas d'URL brute/secret |
| `fields` | Non | **scalaires uniquement** (nombre, booléen, énum/état, classe d'URL) | valeurs non scalaires rejetées |

Notes :
- L'**expurgation est à la source** : aucun appelant ne passe d'URL brute (on passe `TeamsUrls.classify`/`MicrosoftDomains` → classe) ni de contenu.
- Le `msg` d'erreur = type d'exception + message court, jamais une stacktrace ni un chemin sensible.

---

## Technique

### Endpoint(s)

Aucun (runner). Émission d'une trame `runner_diag` sur le WebSocket existant.

### Trame `runner_diag` (contrat, consommée par SF-132-02)

```json
{
  "type": "runner_diag",
  "events": [
    {"ts":"<ISO-8601 instant>","level":"INFO","cat":"chrome","code":"chrome_state","msg":"...","fields":{"state":"REACHABLE","port":9222}}
  ],
  "dropped": 0
}
```

### Tables impactées

Aucune (runner).

### Migration Liquibase

- [ ] Non applicable (module runner, aucune base).

### Composants Angular (si applicable)

- Aucun.

---

## Plan de test

### Tests unitaires (module `runner`, JUnit 5)

- [ ] `RunnerDiag` — le seuil filtre (DEBUG ignoré à INFO ; WARN/ERROR passent).
- [ ] `RunnerDiag` — anneau borné : au-delà de la capacité, plus ancien écrasé + `dropped` incrémenté.
- [ ] `RunnerDiag` — `event(...)` n'explose jamais (entrées nulles/aberrantes avalées).
- [ ] `RunnerDiagRedaction` (expurgation) — URL brute → classe ; message tronqué ; champ non scalaire rejeté ; **aucun secret/URL brute** ne survit.
- [ ] `RunnerDiagEmitter` — draine par lots dans une trame `runner_diag` valide ; rien à drainer → aucune trame ; émission best-effort (un `send` qui lève n'interrompt pas).
- [ ] Instrumentation `ManagedChrome` — un `ensureRunning` produit un événement d'état (nom d'exécutable, port ; jamais le chemin complet). *(via test ciblé de la couture d'émission)*
- [ ] Instrumentation `VigieSonde`/`VigieLoop` — un tick produit un événement de verdict/état.
- [ ] Instrumentation capture (`MeetingTabCapture`/`TeamsTools`) — start/stop produit octets audio / nb images (jamais le média).

### Tests d'intégration

- N/A (runner : pas de contexte Spring ; couvert par tests unitaires + non-régression).

### Isolation utilisateur

- [ ] Non applicable — le runner n'a pas de contexte tenant ; l'isolation `user_id`+`host_id` est **côté gateway** (SF-132-02), à partir de l'identité de session runner.

### Non-régression

- [ ] `heartbeat`, `ready`, `tool_result`, boucle Vigie, capture, `TeamsAdapterV1` inchangés (suite runner complète verte).

---

## Dépendances

### Subfeatures bloquantes

- Aucune (additif). Coexiste avec SF-128-09 (re-capture) et le lot exploitation réunion — additions à `TeamsTools`/`ManagedChrome`/`VigieLoop` uniquement.

### Questions ouvertes impactées

- Aucune.

---

## Préoccupations transversales

| Préoccupation | Impacté ? | Composants |
|--------------|-----------|-----------|
| Auth / Principal | Non | — |
| Contexte tenant | Non (runner) | l'isolation vit côté gateway (SF-132-02) |
| Plans / limites | Non | — |
| Navigation / routing | Non | — |

**Confidentialité (invariant, poste client/banque)** — composants portant l'expurgation à la source :
`RunnerDiag`, `RunnerDiagRedaction`, et les appelants `ManagedChrome`, `VigieSonde`, `VigieLoop`, `TeamsTools`, `MeetingTabCapture`. Réutilise `TeamsUrls.classify` / `MicrosoftDomains` (classe d'URL) et l'esprit `PayloadShape` (SF-89-12).

---

## Notes et décisions

- **Collecteur statique** (comme `RunnerActivity`) plutôt qu'une dépendance injectée dans chaque classe : minimise la surface de conflit avec les livraisons parallèles (additif), sans threader un objet à travers tous les constructeurs. Testable via `reset()`.
- **Transport = WebSocket existant** via `FrameSender` (pas de nouveau transport). La readiness Vigie continue par son POST HTTP (inchangé) ; seuls les événements de diagnostic passent par `runner_diag`.
- **Best-effort strict** : mêmes garanties que le heartbeat / la boucle Vigie (aucune exception ne remonte).
