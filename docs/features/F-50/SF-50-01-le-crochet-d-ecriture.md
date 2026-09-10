# Mini-spec — F-50 / SF-50-01 — Le crochet d'écriture

## Identifiant

`F-50 / SF-50-01`

## Feature parente

`F-50` — Points de contrôle de la boucle

## Statut

`done`

## Date de création

2026-09-10

## Branche Git

`feat/SF-50-01-crochet-ecriture`

---

## Objectif

Donner à la boucle maison son premier point d'accroche : **après chaque écriture de fichier**, un
contrôle s'exécute et peut **bloquer** en transformant le résultat d'outil en erreur portant l'action
corrective.

---

## Comportement attendu

### Cas nominal

1. Le modèle appelle `write_file` ou `edit_file`. L'outil s'exécute comme aujourd'hui (cible
   `RUNNER` ou `SANDBOX`, sans changement).
2. Si l'appel **a abouti**, la boucle passe le point de contrôle `AFTER_FILE_WRITE` au registre
   `AtelierCheckpointRunner` avec le contexte de l'écriture.
3. Le registre interroge, **dans l'ordre**, les contrôles enregistrés pour ce point. Le **premier
   qui bloque** l'emporte ; les suivants ne sont pas interrogés (le modèle ne doit corriger qu'une
   chose à la fois).
4. **Verdict « passe »** (le cas de F-50 : aucun contrôle enregistré) → le résultat d'outil est rendu
   au modèle inchangé. Le comportement de la boucle est **strictement identique** à aujourd'hui.
5. **Verdict « bloque »** → le `tool_result` rendu au modèle devient une **erreur** dont le texte
   est : `Écriture contrôlée : <action corrective>`. L'action fichier destinée à l'écran
   (`AtelierAction("write", path)`) est **conservée** — le fichier a bel et bien été écrit, l'éditeur
   ouvert doit se rafraîchir.
6. La transcription du tour (SF-39-17) et la trajectoire rejouée (SF-39-03) enregistrent le bloc en
   erreur, comme n'importe quel échec d'outil : au rechargement, on voit encore ce qui a bloqué.

Le contexte remis à un contrôle porte : l'utilisateur, le projet, l'outil (`write_file` /
`edit_file`), le **chemin** et le **contenu demandé** (`content` pour `write_file`, `new_string` pour
`edit_file`) — jamais une relecture du disque, jamais un secret d'infrastructure.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|---|---|---|
| L'écriture elle-même a échoué (chemin invalide, hors racine, runner absent) | **Aucun contrôle n'est exécuté** — il n'y a rien à contrôler ; l'erreur d'origine est rendue telle quelle | — |
| Un contrôle lève une exception | Il est **ignoré** (repli passant), une ligne de journal `warn` le signale sans contenu, les suivants sont interrogés. Un contrôle défaillant ne prend jamais le tour en otage | — |
| Un contrôle bloque sans action corrective (texte vide ou nul) | Message de repli : `Écriture contrôlée : reprends ce fichier avant de continuer.` — jamais un blocage muet | — |
| Action corrective de plus de 2 000 caractères | Tronquée à 2 000 caractères | — |
| Outil autre que `write_file` / `edit_file` (`read_file`, `bash`, `list_files`, `set_plan`, `explore`…) | Aucun contrôle exécuté | — |
| Écriture faite par la **sous-boucle d'exploration** (SF-39-14) | Sans objet : l'exploration n'a que des outils de lecture | — |

---

## Critères d'acceptation

- [x] Un contrôle enregistré sur `AFTER_FILE_WRITE` est appelé après un `write_file` abouti, avec le chemin et le contenu demandé.
- [x] Il est appelé de la même façon après un `edit_file` abouti, avec `new_string` comme contenu.
- [x] Un verdict bloquant transforme le résultat d'outil en **erreur** (`is_error`) dont le texte contient l'action corrective.
- [x] Un verdict bloquant **conserve** l'action fichier rendue à l'écran (le fichier est écrit).
- [x] Un verdict passant laisse le résultat d'outil **strictement inchangé**.
- [x] Sans aucun contrôle enregistré (état livré par F-50), aucun comportement de la boucle ne change — les tests existants de `AtelierChatService` passent sans modification de leurs attentes.
- [x] Une écriture **en échec** ne déclenche aucun contrôle.
- [x] Un contrôle qui lève une exception est ignoré, le tour continue, et un `warn` sans contenu est journalisé.
- [x] Le premier contrôle bloquant court-circuite les suivants.
- [x] Un blocage sans action corrective produit le message de repli.
- [x] Le contexte remis au contrôle porte le `userId` du tour et le `workspaceId` déjà vérifié possédé (isolation).
- [x] Aucun contenu de fichier, aucun chemin et aucune action corrective ne sont écrits dans le journal serveur.

---

## Périmètre

### Hors scope (explicite)

- Le crochet de **fin de tour** → SF-50-02.
- Tout **contrôle** réellement branché : F-50 livre le mécanisme, il n'enregistre aucun contrôle.
- Toute **configuration utilisateur** (table, endpoint, écran, activation par projet) → F-51.
- Toute **exécution de code fourni par un tiers** (script, processus, source évaluée) — exclue du
  périmètre de la feature, définitivement : un contrôle est un composant du serveur.
- Un crochet sur `bash` : la porte de confirmation (SF-38-08) et le journal d'audit le couvrent déjà.
- Le chemin **Managed Agents** (`AtelierSessionService`) : F-50 vise la boucle maison, seule
  concernée par le constat.

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format / Valeurs autorisées | Unicité | Normalisation |
|---|---|---|---|---|---|
| `AtelierCheckpointKind` | Oui | — | `AFTER_FILE_WRITE`, `END_OF_TURN` (le second n'est branché qu'en SF-50-02) | — | — |
| action corrective (`correction`) | Non (nulle = repli) | 2 000 | texte libre, rendu tel quel au modèle | Non | `trim()`, troncature |
| `toolName` du contexte | Oui | — | `write_file`, `edit_file` | Non | — |
| `path` du contexte | Non | — | chemin **relatif au projet**, tel que reçu du modèle | Non | — |
| `content` du contexte | Non | — | texte demandé à l'écriture, jamais relu du disque | Non | — |

Notes :
- La borne de 2 000 caractères est un choix de cette SF : une action corrective plus longue qu'une
  page n'est plus une action, c'est un cahier des charges. Elle est plus large que les 500 caractères
  d'un motif de refus (`RunnerConfirmationGate`), parce qu'un motif s'adresse au modèle *pour une
  commande* alors qu'une correction peut décrire une reprise de fichier.

---

## Technique

### Endpoint(s)

Aucun. La feature est interne à la boucle.

### Tables impactées

Aucune.

### Migration Liquibase

- [x] Non applicable

### Classes créées (`fr.claudegateway.atelier.checkpoint`)

| Classe | Rôle |
|---|---|
| `AtelierCheckpointKind` | Enum des deux points d'accroche |
| `AtelierCheckpointContext` | Ce qu'un contrôle reçoit (record immuable) |
| `AtelierCheckpointVerdict` | `proceed()` / `block(correction)` (record immuable) |
| `AtelierCheckpoint` | Le contrat qu'implémentera tout contrôle (F-51/F-52) |
| `AtelierCheckpointRunner` | `@Component` : ordonne, exécute, isole les défaillances, borne le texte |

### Classes modifiées

| Classe | Modification |
|---|---|
| `AtelierChatService` | Nouvelle dépendance `AtelierCheckpointRunner` (constructeur historique conservé pour les appelants existants) ; appel du crochet après chaque écriture aboutie |

### Composants Angular

Aucun — la feature n'a pas d'écran (voir cadrage §5).

---

## Plan de test

### Tests unitaires

- [x] `AtelierCheckpointRunnerTest` — aucun contrôle enregistré : verdict passant.
- [x] `AtelierCheckpointRunnerTest` — un contrôle bloquant : verdict bloquant, action corrective rendue.
- [x] `AtelierCheckpointRunnerTest` — deux contrôles bloquants : seul le premier est interrogé.
- [x] `AtelierCheckpointRunnerTest` — un contrôle d'un **autre** point d'accroche n'est pas interrogé.
- [x] `AtelierCheckpointRunnerTest` — un contrôle qui lève : ignoré, les suivants sont interrogés.
- [x] `AtelierCheckpointRunnerTest` — action corrective vide → message de repli ; > 2 000 caractères → tronquée.
- [x] `AtelierCheckpointVerdictTest` — `proceed()` n'est jamais bloquant ; `block(null)` l'est.

### Tests d'intégration (boucle)

- [x] `AtelierChatServiceCheckpointTest` — `write_file` abouti + contrôle bloquant → `tool_result` en erreur portant l'action ; l'action fichier `write` est conservée dans le résultat du tour.
- [x] `AtelierChatServiceCheckpointTest` — `edit_file` abouti + contrôle bloquant → même comportement.
- [x] `AtelierChatServiceCheckpointTest` — contrôle passant → résultat d'outil inchangé.
- [x] `AtelierChatServiceCheckpointTest` — `write_file` **en échec** → le contrôle n'est jamais appelé.
- [x] `AtelierChatServiceCheckpointTest` — `read_file` → le contrôle n'est jamais appelé.
- [x] Les neuf classes de tests existantes de `AtelierChatService` restent vertes sans changement d'attente.

### Isolation workspace / utilisateur

- [x] Applicable — le contexte remis au contrôle porte le couple `(userId, workspaceId)` issu du tour,
      lui-même obtenu après `workspaceService.requireOwned` : aucun contrôle ne peut être appelé pour
      un projet que l'appelant ne possède pas. Test : le contexte capté porte bien les identifiants du
      tour.

---

## Dépendances

### Subfeatures bloquantes

Aucune. F-48 et F-49 sont livrées ; F-50 ne dépend d'aucune des deux.

### Questions ouvertes impactées

- [x] Aucune question de `docs/OPEN_QUESTIONS.md` n'est touchée.

---

## Notes et décisions

**D1 — Après l'écriture, pas avant.** Le crochet s'exécute une fois le fichier écrit, comme le
`PostToolUse` de Claude Code. Contrôler avant supposerait de savoir juger un contenu sans l'état
réel du fichier (un `edit_file` ne porte qu'un fragment), et d'assumer un refus qui n'aurait laissé
aucune trace. Le prix assumé : un fichier fautif existe le temps que le modèle le corrige. La
contrepartie est nette — la correction est **exigée** par un `tool_result` en erreur, exactement le
geste qui a fait ses preuves avec la porte de confirmation.

**D2 — Repli passant en cas de défaillance.** Un contrôle qui lève est ignoré. L'inverse — bloquer
quand le contrôle est cassé — condamnerait le projet d'un utilisateur sur un bogue de gouvernance,
et il n'aurait aucun moyen de s'en sortir (rien n'est configurable en F-50). Réversible : le jour où
un contrôle portera une exigence de sécurité, il pourra déclarer son propre régime.

**D3 — Le premier blocage l'emporte.** Agréger les blocages donnerait au modèle une liste de choses
à corriger d'un coup, dont il traiterait la première et oublierait le reste. Un blocage = une
correction ; le suivant reviendra à l'écriture suivante.

**D4 — Un registre, pas une liste injectée dans la boucle.** `AtelierChatService` reçoit un
`AtelierCheckpointRunner`, pas une `List<AtelierCheckpoint>` : l'ordonnancement, l'isolation des
défaillances et le bornage du texte vivent dans un composant testable seul, et la boucle — déjà
longue — ne gagne que deux lignes.

**D5 — Constructeur historique conservé.** `AtelierChatService` a onze sites de construction dans les
tests. Un second constructeur, qui passe un registre vide, les laisse tous valides et rend visible
le fait que *sans contrôle, rien ne change*. Même geste que `AtelierChatResult` (SF-39-15).
