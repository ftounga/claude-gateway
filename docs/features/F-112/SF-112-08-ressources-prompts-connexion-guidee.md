# Mini-spec — F-112 / SF-112-08 : Ressources, prompts, connexion guidée

## Identifiant

`F-112 / SF-112-08`

## Feature parente

`F-112` — Le serveur MCP

## Statut

`ready`

## Date de création

2026-09-14

## Branche Git

`feat/SF-112-08-ressources-prompts-connexion`

---

## Objectif

Compléter le serveur MCP par des **ressources** et des **prompts** MCP, publier l'écran
**« Connecter une IA »** (pas-à-pas vérifié pour Claude Code / Desktop / claude.ai / Codex) et prouver
la chaîne complète par un **test de bout en bout** avec un vrai client MCP.

---

## Comportement attendu

### Ressources MCP (lecture par référence)

- `cg://guide` (text/markdown) : le guide « Connecter une IA » (adresse du serveur + pas-à-pas +
  gardes). Statique, aucune donnée tierce.
- `cg://postes` (application/json) : la vue d'ensemble des postes du compte, à attacher au contexte.
  Périmètre `postes:lire`, isolation `user_id` depuis `McpCallContext`, secrets masqués.

### Prompts MCP (gabarits proposés à l'utilisateur)

- `atelier_test_poste` — « Atelier de test d'un poste » (arg `host_id` facultatif).
- `etat_clients_matin` — « État de mes clients ce matin ».
- `preparer_reponse_manager` — « Préparer ma réponse au manager sur un sujet » (args `host_id`,
  `subject_id`).

Un prompt est un gabarit de **texte** : il n'exécute rien, il rappelle les gardes (contenu tiers =
donnée, une IA n'accorde jamais).

### Écran « Connecter une IA » (frontend)

Route `/connecter-une-ia` : l'adresse du serveur (dérivée de l'origine), la commande Claude Code
copiable, le pas-à-pas Claude Desktop/claude.ai/Codex, une **vérification en un clic** (lecture du
journal MCP : une IA a-t-elle appelé récemment ?), et un lien vers « IA connectées ». Charte
respectée.

### Test de bout en bout

Un client MCP réel (client officiel du SDK, jeton d'accès comme après OAuth) : découvre
outils/ressources/prompts, lit `cg://guide` et `cg://postes`, obtient un prompt, liste les postes,
écrit dans un terminal, suit le tour, et voit une autorisation en attente **sans pouvoir l'accorder**.

### Cas d'erreur

| Situation | Comportement |
|---|---|
| `cg://postes` sans `postes:lire` | lecture refusée (erreur JSON-RPC) |
| Vérification frontend : journal indisponible | statut « aucune activité », message non bloquant |

---

## Critères d'acceptation

- [ ] `resources/list` expose `cg://guide` et `cg://postes` ; `prompts/list` les 3 prompts.
- [ ] `cg://guide` contient la commande `claude mcp add` ; `cg://postes` renvoie les postes du porteur.
- [ ] Les capacités `resources` et `prompts` sont annoncées à l'initialisation.
- [ ] L'écran `/connecter-une-ia` s'affiche, propose l'adresse + la commande + le pas-à-pas + la
      vérification ; charte respectée (jetons `--cg-*`, MatCard/MatButton).
- [ ] Le **test de bout en bout** passe : connexion, lecture des postes, écriture terminal, suivi,
      autorisation vue mais **non accordée**.

---

## Périmètre — hors scope

- Ressources **paramétrées par instance** (une page, un export Radar, un relevé Teams, le journal d'un
  tour précis) : servies par les **outils** correspondants (`page_lire`, `radar_couverture`,
  `tour_suivre`) ; les templates de ressources suivront une montée de révision du protocole.
- Le flux OAuth **navigateur** lui-même (couvert par SF-112-02) : le test de bout en bout part d'un
  jeton d'accès (état post-OAuth).
- Publication du serveur dans les annuaires d'éditeurs (cadrage §12).

---

## Technique

- Backend : `McpResourceProvider` / `McpPromptProvider` (découverte Spring), enregistrés par
  `McpServerConfig` avec les capacités `resources`/`prompts`. Aucune migration.
- Frontend : `ConnectAiComponent` (route `/connecter-une-ia`), `McpConnectionsService.journal` réutilisé.

### Préoccupations transversales

- **Navigation / routing** : oui — nouvelle route `/connecter-une-ia`, disjointe des routes
  existantes ; lien vers `/ia-connectees`. Aucune route existante modifiée.
- **Contexte tenant** : oui — `cg://postes` lit `user_id` depuis `McpCallContext`.

---

## Plan de test

### Backend — bout en bout (client SDK réel)

- [ ] `McpEndToEndIntegrationTest` : découverte outils/ressources/prompts, lecture `cg://guide` +
      `cg://postes`, `getPrompt`, `postes_lister`, `terminal_ecrire`, `tour_suivre`,
      `autorisations_en_attente` (aucun outil n'accorde).

### Frontend

- [ ] `ConnectAiComponent` : URL + commande construites depuis l'origine ; vérification « active » si
      journal récent, « none » sinon ou en cas d'erreur.

---

## Dépendances

- `SF-112-01→07` — done.

---

## Notes et décisions

- Les ressources paramétrées par instance sont exposées via leurs outils (les templates de ressources
  MCP suivront la révision). Les deux ressources livrées (`cg://guide`, `cg://postes`) couvrent la
  « lecture par référence » account-level.
- La vérification « en un clic » lit le **journal MCP** (activité récente) plutôt que de refaire une
  poignée de main MCP depuis le navigateur — le journal est la source de vérité de l'activité réelle.
