# Mini-spec — [F-121 / SF-121-07] Bash en arrière-plan (run_in_background + suivi)

> Cadrage : `docs/features/F-121/CADRAGE-F-121-parite-claude-code-feuille-de-route.md` (Lot 2, F-121-07).
> Audit : `docs/audits/AUDIT-2026-09-23-parite-claude-code-consolidee.md`.

---

## Identifiant

`F-121 / SF-121-07`

## Feature parente

`F-121` — Parité avec Claude Code (boucle maison)

## Statut

`ready`

## Date de création

2026-09-24

## Branche Git

`feat/SF-121-07-bash-arriere-plan`

---

## Objectif

> En une phrase : donner à `bash` un `timeout` paramétrable (plafond élargi) et un `run_in_background`
> détaché, avec deux outils `bash_output`/`kill_shell` pour relire et arrêter un processus lancé en
> fond — pour qu'un serveur de dev ou un build long ne bloque plus le tour.

---

## Comportement attendu

### Cas nominal

**Timeout paramétrable (foreground, aucun changement runner)**
1. Le modèle appelle `bash` avec `{command, timeout}` (ms).
2. La gateway clampe `timeout` dans `[1 000 ; MAX_BASH_TIMEOUT_MS = 600 000]` **et** au budget de tour
   restant, puis l'émet dans le champ `timeoutMs` du contrat (§2.2) — que le runner honore déjà par son
   propre chronomètre. Sans `timeout`, le défaut reste 120 s (comportement d'avant).

**Lancement en arrière-plan (`run_in_background: true`, nécessite un runner à jour)**
1. Le modèle appelle `bash` avec `{command, run_in_background: true}` (déclaré seulement si le poste
   annonce la capacité `bash_background`).
2. La gateway relaie l'appel `bash` avec `input.background = true` et un délai court (le runner rend la
   main tout de suite).
3. Le runner **démarre le processus détaché**, l'enregistre dans un registre machine
   (`BackgroundShells`) sous un identifiant (`bash_1`, `bash_2`, …), pompe sa sortie dans un tampon
   borné, et rend **immédiatement** un `tool_result` dont le `content` porte l'identifiant. Le processus
   **ne prend pas** le sémaphore « une commande à la fois » : un travail de fond ne bloque pas un
   `bash` de premier plan.
4. La gateway rend au modèle : « Commande lancée en arrière-plan. Identifiant : `bash_1`. Relis sa
   sortie avec `bash_output`, arrête-la avec `kill_shell`. »

**Relecture (`bash_output`)**
1. Le modèle appelle `bash_output` avec `{bash_id}`.
2. Le runner rend la **sortie nouvelle depuis la dernière lecture** (curseur) + l'état
   (`en cours` / `terminé (code N)` / `arrêté`).

**Arrêt (`kill_shell`)**
1. Le modèle appelle `kill_shell` avec `{shell_id}`.
2. Le runner tue le processus (`destroyForcibly`), marque l'état `arrêté`, et le confirme.

### Cas d'erreur

| Situation | Comportement attendu | Code |
|-----------|---------------------|------|
| `timeout` non entier / hors bornes | clampé silencieusement dans `[1 000 ; 600 000]` (jamais d'erreur) | — |
| `run_in_background` sur un poste sans capacité `bash_background` | outil non déclaré ; si forcé quand même, la gateway retombe en **synchrone** (aucune régression) | — |
| Registre plein (> `MAX_BACKGROUND_SHELLS = 8`) | refus `denied` : « Trop de commandes en arrière-plan (8 au plus). Arrête-en une avec kill_shell. » | `denied` |
| `bash_output` / `kill_shell` avec un `id` inconnu | `not_found` : « Aucune commande en arrière-plan sous cet identifiant. » | `not_found` |
| `bash_output` sur un runner ancien (non déclaré) | outils non déclarés ; jamais émis. Défensif : `unsupported_tool` relayé « non concluant » | `unsupported_tool` |
| `--no-bash` | `run_in_background` refusé comme `bash` l'est déjà (`unsupported_tool`) | `unsupported_tool` |
| Processus de fond dépassant le tampon (1 Mio) | on tronque par la **tête** (curseur ajusté), marqueur `… (début tronqué)` | — |

---

## Critères d'acceptation

- [ ] `bash` accepte `timeout` (ms) : la gateway clampe dans `[1 000 ; 600 000]` **et** au budget de tour
      restant, et l'émet en `timeoutMs`. Sans `timeout`, défaut 120 s inchangé.
- [ ] `run_in_background` et les outils `bash_output`/`kill_shell` ne sont **déclarés** que sur cible
      RUNNER **et** quand le poste annonce `bash_background` (retro-compat : poste ancien = inchangé).
- [ ] Un `bash` `run_in_background: true` rend **immédiatement** un identifiant sans attendre la fin du
      processus, et **ne prend pas** le sémaphore « une commande à la fois ».
- [ ] `bash_output(bash_id)` rend la sortie **nouvelle depuis la dernière lecture** + l'état (en cours /
      terminé code N / arrêté).
- [ ] `kill_shell(shell_id)` tue le processus et le confirme ; un second appel est idempotent.
- [ ] Un `id` inconnu rend `not_found` propre (pas de fuite d'arborescence machine).
- [ ] Le registre plafonne à 8 commandes de fond et refuse au-delà avec un message actionnable.
- [ ] La capacité `bash_background` est annoncée dans la trame `ready` ; le contrat runner passe de 2 à 3.
- [ ] `run_in_background: true` **reste soumis à la porte de confirmation**, à la politique de permission
      (F-121-02) et au point de contrôle avant commande (F-52) — c'est un `bash`.
- [ ] Le cache de prompt (F-134) est préservé : les déclarations d'outils dépendent d'une propriété
      **stable par poste** (capacité annoncée), rien de volatil n'entre dans le préfixe.
- [ ] Provider Independence intacte : aucune dépendance directe Anthropic ajoutée ; on passe par le
      contrat runner et le modèle neutre d'outils.

---

## Périmètre

### Hors scope (explicite)

- **Bouton / affichage frontend dédié** : les nouveaux outils s'affichent déjà via le rendu générique
  d'étapes (`stepFor` défaut). Pas d'écran neuf.
- **`filter` (regex) sur `bash_output`** (option Claude Code) : non repris ici.
- **Persistance des shells de fond au-delà de la vie du runner** : un processus de fond meurt avec le
  runner (comme un shell de fond meurt avec la session Claude Code). Pas de reprise après reconnexion.
- **Équivalent sandbox** : la cible SANDBOX n'a pas de `bash` — les trois outils n'y existent pas.
- **`run_in_background` sur les outils fichiers / Teams** : réservé à `bash`.

---

## Valeurs initiales

Aucune entité persistée. Le registre `BackgroundShells` vit **en mémoire du runner**, sur la machine
de l'utilisateur. Aucune donnée ne remonte en base.

---

## Contraintes de validation

| Champ | Obligatoire | Bornes | Format | Normalisation |
|-------|-------------|--------|--------|---------------|
| `bash.command` | Oui | 8192 car. | non vide, sans `\0` | strip (existant) |
| `bash.timeout` | Non | `[1 000 ; 600 000]` ms | entier | clamp + min(budget restant) |
| `bash.run_in_background` | Non | — | booléen | défaut `false` |
| `bash_output.bash_id` | Oui | 64 car. | identifiant `bash_N` | — |
| `kill_shell.shell_id` | Oui | 64 car. | identifiant `bash_N` | — |
| Registre de fond | — | 8 shells, tampon 1 Mio / shell | — | ring par la tête |

---

## Technique

### Endpoint(s)

Aucun endpoint REST nouveau. L'évolution est au **contrat runner** (trames WebSocket / repli) et au
**catalogue d'outils** de la boucle maison.

### Tables impactées

Aucune. **Aucune migration Liquibase.**

### Évolution du protocole runner — **OUI, mise à jour runner nécessaire**

- `bash` : nouvel `input.background` (booléen, optionnel). Runner à jour = lancement détaché + réponse
  immédiate ; runner ancien = ignore le champ, exécute en synchrone (retro-compat).
- Nouveaux outils `bash_output({id})` et `kill_shell({id})`. Runner ancien = `unsupported_tool` (jamais
  émis : non déclarés tant que la capacité manque).
- Nouvelle capacité `bash_background` dans la trame `ready` (contrat §2.1).
- Niveau de contrat runner : **2 → 3**.

### Composants impactés

**Runner (`runner/`)** :
- `BackgroundShells` (nouveau) — registre machine des processus détachés.
- `BackgroundShell` (nouveau) — un processus de fond : tampon borné + curseur + état.
- `BashTool` — branche `background` (contourne le sémaphore) ; réutilise résolution commande/cwd.
- `ToolRouter` — aiguille `bash_output`/`kill_shell` ; annonce `bash_background`.
- `ProjectScopes` — instancie un `BackgroundShells` **unique** (machine) partagé par les routeurs.
- `RunnerBuild.CONTRACT` — 2 → 3.

**Gateway (`backend/`)** :
- `RunnerToolGateway` — `MAX_BASH_TIMEOUT_MS` (600 000) ; `bashBackground`, `bashOutput`, `killShell`.
- `AtelierChatService` — `buildToolsFull` (params + outils gatés capacité) ; `callRunner` (timeout
  effectif, routage background/output/kill) ; `runnerOutcome`, `stepFor`, `auditTarget`.
- `RunnerHostService` — `declaredCapabilities(hostId)`.

### Préoccupations transversales

- **Plans / limites** : un `bash` de fond consomme des jetons runner et de la mémoire machine, jamais
  du budget de tour (il rend la main tout de suite). Le plafond de 8 shells borne la charge machine.
  Aucun quota gateway impacté (pas de crédit, pas d'appel LLM supplémentaire).
- **Auth / tenant** : `bash_output`/`kill_shell` sont routés vers le **même poste** que le workspace
  qui a lancé (RunnerTarget existant) — isolation par le routage runner, inchangée. Aucune donnée
  persistée, aucun accès base : rien à filtrer par `user_id` de plus.
- **Navigation** : aucune route frontend nouvelle.
- **Composants transversaux touchés** (liste exhaustive) : `BashTool`, `ToolRouter`, `ProjectScopes`,
  `RunnerBuild`, `RunnerToolGateway`, `AtelierChatService` (buildTools, callRunner, runnerOutcome,
  stepFor, auditTarget), `RunnerHostService`. **Non touchés et vérifiés** : porte de confirmation
  (`requiresConfirmation` reste `bash`), permissions (F-121-02, `bash` inchangé), checkpoint commande
  (F-52), estimateur de compaction (aucun nouveau volume dans le préfixe), discipline F-119.

---

## Plan de test

### Tests unitaires — runner

- [ ] `BackgroundShells` — `start` rend un id, l'output est bufferisé, l'état passe à `terminé` code 0.
- [ ] `BackgroundShells` — `output` rend le **nouveau** depuis le curseur, puis vide.
- [ ] `BackgroundShells` — `kill` tue le processus, état `arrêté`, idempotent.
- [ ] `BackgroundShells` — `id` inconnu → `not_found`.
- [ ] `BackgroundShells` — au-delà de 8 shells → `denied`.
- [ ] `BashTool` — `background: true` rend immédiatement un id **sans** attendre la fin, et ne prend pas
      le sémaphore (un `bash` synchrone reste possible pendant qu'un fond tourne).
- [ ] `ToolRouter` — `bash_output`/`kill_shell` aiguillés ; `capabilities()` inclut `bash_background`.

### Tests unitaires — gateway

- [ ] `RunnerToolGateway.bash` — `timeout` clampé dans `[1 000 ; 600 000]`.
- [ ] `RunnerToolGateway.bashBackground` — pose `input.background = true`.
- [ ] `RunnerToolGateway.bashOutput`/`killShell` — posent `id`, relaient l'issue.
- [ ] `AtelierChatService.buildTools` — sans capacité : pas de `run_in_background`, pas de
      `bash_output`/`kill_shell` ; avec capacité : présents.
- [ ] `AtelierChatService` — `run_in_background: true` non déclaré ⇒ retombe en synchrone (défensif).
- [ ] `RunnerHostService.declaredCapabilities` — parse la liste, tolère `null`.

### Tests d'intégration / boucle

- [ ] Un tour appelle `bash run_in_background` puis `bash_output` : l'outcome du premier porte l'id, le
      second rend la sortie ; les deux sont **tracés à l'audit**.
- [ ] Un `bash` de fond **passe par la porte de confirmation** quand `agent_ask_before_bash` est actif.

### Isolation workspace

- [ ] Non applicable (aucune donnée persistée, aucun accès base ajouté) — l'isolation d'exécution reste
      celle du RunnerTarget (poste+projet), déjà couverte. Raison tracée ici.

---

## Dépendances

### Subfeatures bloquantes

- `SF-121-06` (MultiEdit) — Done. Aucune dépendance de code dure, mais séquencement Lot 2.
- Runner à jour requis pour les capacités de fond ; retro-compat garantie pour l'existant.

### Questions ouvertes impactées

- Aucune de `docs/OPEN_QUESTIONS.md`.

---

## Notes et décisions

- **D1 — Un `bash` de fond reste un `bash`** : même porte de confirmation, mêmes règles de permission,
  même point de contrôle avant commande, même marque de mutation. Seuls `bash_output`/`kill_shell`
  sont des lectures/gestes sans porte.
- **D2 — Registre machine, pas par projet** : un serveur de dev appartient à la machine. Le registre
  est un singleton `BackgroundShells` partagé par les routeurs de projet, pour que `bash_output` le
  retrouve quel que soit le projet du tour.
- **D3 — Le timeout paramétrable ne touche pas le runner** : il voyage déjà dans `timeoutMs` (§2.2), que
  tout runner honore. Seuls `run_in_background`/`bash_output`/`kill_shell` sont l'évolution du protocole.
- **D4 — Sortie de fond bufferisée, pas streamée** : le processus survit à l'appel qui l'a lancé ; ses
  trames `tool_stream` n'auraient plus de `tool_result` à précéder. On bufferise et on relit par
  `bash_output`, curseur oblige.
- **D5 — Cache F-134** : la déclaration des outils dépend de la capacité annoncée par le poste, stable
  d'un tour à l'autre — aucun ajout volatil au préfixe.
