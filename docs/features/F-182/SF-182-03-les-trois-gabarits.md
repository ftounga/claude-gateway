# Mini-spec — F-182 / SF-182-03 Les trois gabarits

## Identifiant

`F-182 / SF-182-03`

## Feature parente

`F-182` — Les agents nommés (cadrage `CADRAGE-F-182-les-agents-nommes.md`, décision D4)

## Statut

`done`

## Date de création

2026-10-16

## Branche Git

`feat/SF-182-03-les-trois-gabarits`

---

## Objectif

Livrer trois agents prêts à l'emploi (`relecteur`, `testeur`, `auditeur-infra`) dans le paquet `savoir-durable`,
et permettre d'en créer un depuis le terminal par `gouvernance_proposer(type: AGENT)` (F-177).

---

## Comportement attendu

### Cas nominal

1. **Gabarits (D4)** — le paquet `savoir-durable` (`GovernancePackageSeeder`) apporte trois fichiers d'un genre
   nouveau **`AGENT`**, déposés comme les skills dans chaque sujet du poste : `.claude/agents/relecteur.md`
   (lecture : constats classés avant une MR — généralise le juge indépendant), `.claude/agents/testeur.md`
   (écriture : écrit et lance les tests dans le worktree isolé), `.claude/agents/auditeur-infra.md` (lecture + bash
   en lecture : `terraform plan`/`helm template`/`kubectl diff`, jamais `apply`). Aucun ne fixe de modèle : Opus
   (D3). Le paquet passe en version suivante (idempotence du semeur inchangée) ; un poste activé les reçoit au
   prochain dépôt (« Remettre à jour », F-177 / SF-177-04) — rien n'est écrit chez le client dans son dos (D5 F-96).
2. **Genre `AGENT`** — `GovernanceFileKind.AGENT` : déposé dans chaque projet (comme `SKILL`/`TEMPLATE`) ;
   accepté par l'édition de paquet admin ; l'écran l'annonce « agent ».
3. **Création depuis le terminal** — `gouvernance_proposer` accepte `type: AGENT` → fichier
   `.claude/agents/<slug>.md` (portée POSTE = racine, SUJET = ce sujet). Le contenu est **validé** avec le
   lecteur d'agents (SF-182-01) : une erreur bloquante (consigne vide, aucun outil reconnu) refuse la proposition
   avec le motif ; un avertissement (modèle ignoré faute de justification, outil inconnu) est rendu à l'agent avec
   la proposition. Un contenu sans entête reçoit `name`/`description`/`outils: lecture` (moindre privilège).
   Rien n'est écrit avant [Appliquer] (D3 de F-177) ; après application, le cache de consigne voit l'agent au tour
   suivant (sujet : fichier + arborescence ; poste : index des agents du poste).
4. **Doctrine** — la doctrine de gouvernance (littéral stable) et la description de l'outil disent le type AGENT ;
   un `write_file` sous `.claude/agents/` reçoit le même rappel que pour les skills.
5. **Carte** — la carte de proposition affiche « Agent ».

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| `type: AGENT` avec consigne vide | proposition refusée : « Agent invalide : Consigne vide… » | — (erreur d'outil) |
| `type: AGENT` sans outil reconnu | refusée avec le motif | — |
| `modele` sans `justification` | proposition posée + avertissement rendu à l'agent | — |
| `portee: POSTE` sans poste | refus existant (F-177) | — |
| genre de fichier inconnu dans l'édition admin | 400 existant, message listant SKILL, TEMPLATE ou AGENT | 400 |

---

## Critères d'acceptation

- [ ] CA1 — Le paquet semé contient les trois agents (genre AGENT, chemins `.claude/agents/…`), chacun **utilisable** selon le lecteur d'agents, sans modèle fixé.
- [ ] CA2 — `auditeur-infra` interdit explicitement toute commande qui applique (apply/destroy/delete).
- [ ] CA3 — `gouvernance_proposer(type: AGENT)` pose une carte vers `.claude/agents/<slug>.md` ; entête ajoutée si absente.
- [ ] CA4 — Un agent invalide est refusé avec le motif ; un avertissement accompagne la proposition.
- [ ] CA5 — Après [Appliquer], le cache de consigne contient l'agent (sujet et poste).
- [ ] CA6 — Le genre AGENT est accepté par l'admin et affiché « agent » dans l'écran Gouvernance / l'aperçu de dépôt.
- [ ] CA7 — La carte de proposition affiche « Agent ».

---

## Périmètre

### Hors scope

- Dépôt automatique chez les postes déjà activés (règle D5 : la mise à jour reste un geste).
- Agents exécutés côté serveur, workflows multi-agents.

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format | Normalisation |
|-------|-------------|-------------|--------|---------------|
| `type` | Oui | — | REGLE, SKILL, GABARIT, AGENT | majuscules |
| `nom` | Oui | 120 (slug 60) | lettres/chiffres → slug | slug |
| `contenu` (AGENT) | Oui | 20 000 | entête facultative + consigne non vide | strip |

---

## Technique

### Endpoints

Aucun nouveau. `POST /api/workspaces/{id}/governance-proposals/{pid}/apply` (F-177) inchangé côté contrat.

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| `governance_package_files` | INSERT (semeur) | `kind = 'AGENT'` (varchar(16), aucune contrainte CHECK) |
| `governance_proposals` | INSERT | `type = 'AGENT'` (varchar(16), aucune contrainte CHECK) |
| `prompt_source_files` | UPSERT | cache après application |

### Migration Liquibase

- [x] Non applicable (colonnes `varchar(16)` sans contrainte d'énumération).

### Composants

- Backend : `GovernanceFileKind`, `GovernancePackageSeeder` (+ 3 ressources), `GovernancePackageService` (genre),
  `GovernanceProposalService` (type AGENT, validation, cache), `PromptSourceStore` (ajout à l'arborescence /
  à l'index du poste), `AtelierChatService` (description d'outil, doctrine, rappel write_file).
- Angular : `governance.models.ts` (genre), écran Gouvernance / aperçu / visionneuse / éditeur admin (libellé),
  `proposal-card` (« Agent »), `atelier.models.ts` (type).

---

## Plan de test

### Tests unitaires

- [ ] `GovernancePackageSeederTest` — les trois agents présents, genre AGENT, utilisables, sans modèle.
- [ ] `GovernanceProposalServiceTest` — AGENT : chemin, entête ajoutée, refus d'un agent invalide, avertissement.
- [ ] `PromptSourceStoreTest` — `putAgent` ajoute à l'arborescence / à l'index.
- [ ] Specs Angular — libellé « Agent » de la carte ; genre « agent » à l'écran.

### Tests d'intégration

- [ ] `GovernanceSeededPackageIntegrationTest` — le paquet semé en base porte les 3 agents.
- [ ] `GovernanceProposalApiIntegrationTest` (ou existant) — application d'une proposition AGENT.

### Isolation

- [x] Applicable — inchangée : proposition relue par `id + user_id + workspace_id`, poste du projet.

---

## Dépendances

- SF-182-01, SF-182-02 — done. F-177 (gouvernance pilotée du terminal) — Terminée.

## Préoccupations transversales

Aucune.

---

## Notes et décisions

- **A8 (réversible)** — Nouveau genre de fichier `AGENT` plutôt que de ranger les agents parmi les skills : l'écran
  dit « 3 agents » et le dépôt reste le même chemin (projet).
- **A9 (réversible)** — `auditeur-infra` reçoit `bash` (donc passe par `task`, worktree isolé + porte de
  confirmation) avec une consigne qui n'autorise que des commandes sans effet ; Opus par défaut.
- **A10 (réversible)** — Un contenu d'agent sans entête reçoit `outils: lecture` (moindre privilège).
