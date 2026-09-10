# Mini-spec — F-52 / SF-52-01 — Le crochet de commande, et le commit sans trace

## Identifiant

`F-52 / SF-52-01`

## Feature parente

`F-52` — Premier paquet de gouvernance

## Statut

`done`

## Date de création

2026-09-10

## Branche Git

`feat/SF-52-01-crochet-de-commande`

---

## Objectif

Donner à la boucle un **troisième point d'accroche — avant l'exécution d'une commande** — et y
brancher le premier contrôle du produit : un `git commit` dont le message porte une trace de LLM est
**refusé avant d'être émis**, avec l'action corrective.

---

## Comportement attendu

### Cas nominal

1. Le modèle appelle `bash` sur un projet en cible `RUNNER`.
2. **Avant** la porte de confirmation et **avant** toute émission vers la machine, la boucle passe le
   point de contrôle `BEFORE_COMMAND` au registre `AtelierCheckpointRunner`, avec la commande
   demandée et son `cwd`.
3. Aucun contrôle enregistré pour ce point → la commande suit **exactement** le chemin d'aujourd'hui.
   Le comportement de la boucle est strictement inchangé.
4. Un contrôle bloque → la commande **n'est pas émise**, le `tool_result` rendu au modèle est une
   **erreur** dont le texte est `Commande contrôlée : <action corrective>`, et l'appel est journalisé
   `DENIED` (refusé avant émission, comme un refus de la porte — SF-38-08 §6).
5. Le contrôle `commit-sans-trace-llm` bloque quand, et seulement quand, la commande **est un
   `git commit`** (y compris `--amend`, y compris précédé d'options `git -C … commit`) **et** que son
   texte porte l'un des marqueurs : `Co-Authored-By:` suivi de `Claude`, `Generated with` suivi de
   `Claude`, `Claude-Session:`, `noreply@anthropic.com`, ou l'émoji `🤖`.
6. L'action corrective nomme le marqueur trouvé et le geste : réécrire le message sans cette ligne,
   puis relancer la commande.

Le contexte remis à un contrôle porte : l'utilisateur, le projet, l'outil (`bash`), la **commande**
et le **répertoire** demandés. Jamais un secret, jamais une relecture de la machine.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|---|---|---|
| Commande absente ou vide dans l'appel d'outil | Le contrôle rend « passe » : il n'y a rien à juger, et l'appel échouera de lui-même sur l'argument manquant | — |
| Le contrôle lève une exception | Il est **ignoré** (repli passant de `AtelierCheckpointRunner`) : la commande part. Un contrôle cassé ne condamne pas le travail | — |
| Les activations du projet sont illisibles (base indisponible) | Repli passant, comme SF-51-04 : aucun contrôle n'est interrogé | — |
| Le blocage survient sur une cible `SANDBOX` | Sans objet : `bash` n'y est pas déclaré, le point d'accroche n'est jamais atteint | — |
| Un `git commit` légitime cite « Anthropic » hors des marqueurs listés | Passe : la liste de marqueurs est **close**, pas heuristique | — |

---

## Critères d'acceptation

- [x] `AtelierCheckpointKind` porte une troisième valeur `BEFORE_COMMAND`, documentée comme fermée.
- [x] `AtelierCheckpointContext` sait décrire une commande (`beforeCommand`) : commande + `cwd`.
- [x] La boucle interroge ce point **avant** la porte de confirmation et **avant** toute émission.
- [x] Sans contrôle enregistré, aucune lecture, aucun appel supplémentaire : `hasCheckpoints` garde.
- [x] Un blocage rend un `tool_result` en erreur préfixé `Commande contrôlée : ` et journalise `DENIED`.
- [x] `commit-sans-trace-llm` est enregistré dans `GovernanceControlRegistry` avec le point
      `BEFORE_COMMAND` et une description d'une ligne.
- [x] Il bloque `git commit -m "… Co-Authored-By: Claude …"`, `git commit --amend` portant `🤖`,
      `git -C /chemin commit -m "… Claude-Session: …"` et `cd x && git commit -m "…"` fautif.
- [x] Il laisse passer `git status`, `git log --grep=commit`, `echo "Co-Authored-By: Claude"`,
      et un `git commit` propre.
- [x] Isolation : le couple `(userId, workspaceId)` vient du contexte de la boucle, déjà vérifié par
      `requireOwned` ; aucun accès données n'est fait sans lui.

---

## Périmètre

### Hors scope (explicite)

- **Les autres commandes qui publient** (`gh pr create --body`, `git tag -m`, `git notes`) :
  arbitrage A3 du cadrage, le périmètre écrit dit « messages de commit ».
- **Le contrôle du contenu des fichiers** : arbitrage A6, c'est une règle et non un verrou.
- **Un marqueur configurable par l'utilisateur** : la liste est close et vit dans le produit.
- **Toute exécution de code fourni par un paquet** : limite héritée de F-50 / F-51.
- **La réécriture automatique du message** : un contrôle juge, il n'écrit pas (contrat F-50).
- **Un message que la ligne de commande ne porte pas** (`git commit -F fichier`,
  `-m "$(cat msg)"`, éditeur interactif) : la gateway ne relit pas la machine de l'utilisateur pour
  contrôler (contrat de contexte F-50). Le contrôle voit l'intention, pas le disque — et c'est
  précisément ce qui l'empêche de devenir une lecture de fichiers déguisée.

---

## Contraintes de validation

| Champ | Règle |
|---|---|
| `command` (contexte) | Borné à 8 000 caractères dans le contexte ; au-delà, tronqué — un contrôle juge une commande, pas un fichier |
| `cwd` (contexte) | Facultatif, `null` accepté |
| Action corrective | Bornée par `AtelierCheckpointVerdict.MAX_CORRECTION_CHARS` (2 000), déjà en place |
| Identifiant du contrôle | `commit-sans-trace-llm` — minuscules, chiffres, tirets ; immuable une fois publié |
| Marqueurs refusés | Liste close de 5 entrées, comparaison **insensible à la casse**, sur le texte brut de la commande |

---

## Technique

### Endpoint(s)

Aucun.

### Tables impactées

Aucune.

### Migration Liquibase

Aucune.

### Classes créées

- `fr.claudegateway.governance.GovernanceCommandCheckpoint` — le bean F-50 du nouveau point.
- `fr.claudegateway.governance.control.CommitSansTraceLlmControl` — le contrôle.

### Classes modifiées

- `fr.claudegateway.atelier.checkpoint.AtelierCheckpointKind` — valeur `BEFORE_COMMAND`.
- `fr.claudegateway.atelier.checkpoint.AtelierCheckpointContext` — fabrique `beforeCommand`,
  composants `command` / `cwd`.
- `fr.claudegateway.atelier.checkpoint.AtelierCheckpointRunner` — message `commandBlockedMessage`.
- `fr.claudegateway.atelier.AtelierChatService` — appel du point dans `executeToolOnRunner`.

### Composants Angular

Aucun — F-52 n'apporte pas d'écran (§7 du cadrage).

---

## Plan de test

### Tests unitaires

- `CommitSansTraceLlmControlTest` : les 5 marqueurs bloquent ; casse ignorée ; `--amend` couvert ;
  `git -C … commit` couvert ; commandes non-commit passantes ; commande vide passante ; l'action
  corrective nomme le geste attendu.
- `AtelierCheckpointRunnerTest` : le message de commande porte le préfixe et le repli.
- `GovernanceCheckpointDelegateTest` : un contrôle `BEFORE_COMMAND` n'est interrogé que sur ce point.

### Tests d'intégration (boucle)

- `AtelierChatServiceCommandCheckpointTest` : cible `RUNNER`, contrôle bloquant → aucune trame émise
  vers le runner, `tool_result` en erreur, audit `DENIED` ; contrôle passant → comportement inchangé ;
  aucun contrôle → aucun appel au registre.

### Isolation workspace / utilisateur

- Le contexte porte `(userId, workspaceId)` issus de `requireOwned` ; un test vérifie que la
  délégation ne lit **aucune** activation quand l'un des deux est absent.

---

## Dépendances

### Subfeatures bloquantes

- F-50 / SF-50-01 (le registre et le contrat) — **Done**.
- F-51 / SF-51-04 (la délégation vers les paquets actifs) — **Done**.

### Questions ouvertes impactées

Aucune.

---

## Notes et décisions

- **Pourquoi un troisième crochet alors que F-50 avait écarté `bash`** : la porte de confirmation
  n'inspecte pas le contenu d'une commande et se débraye par projet (SF-38-20). Elle ne peut donc pas
  porter une vérification mécanique. Voir l'arbitrage A1 du cadrage.
- **Placement avant la porte** : demander une autorisation pour une commande que la gouvernance va
  refuser ferait cliquer l'utilisateur pour rien.
- **Journalisation** : `DENIED` est le verdict d'audit prévu pour « refusé avant émission ». Le motif
  n'est pas journalisé — il porte le travail de l'utilisateur (règle SF-38-08 / D11).
