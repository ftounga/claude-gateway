# Mini-spec — F-47 / SF-47-04 — L'exécution est autorisée par défaut

## Identifiant

`F-47 / SF-47-04`

## Feature parente

`F-47` — L'autorisation qu'on ne peut pas manquer

## Statut

`in-progress`

## Date de création

2026-09-10

## Branche Git

`feat/SF-47-04-execution-autorisee-par-defaut`

---

## Objectif

> Qu'un projet nouvellement créé **exécute sans demander** : `agent_ask_before_bash` vaut `false` à
> la création, la porte restant activable projet par projet, journal d'audit et coupe-circuit
> inchangés.

---

## Déclencheur

**Décision du product owner du 2026-09-10**, qui tranche `OQ-14` — *« En cible `RUNNER`, la porte de
confirmation doit-elle rester activée par défaut ? »*, ouverte depuis le cadrage F-47 du 2026-09-08.
La réponse est **non** : sur une machine que l'utilisateur a lui-même connectée, avec son propre
appairage, dans un dossier qu'il a lui-même désigné, la première commande n'attend plus un clic.

L'asymétrie que la question relevait disparaît : SF-38-19 avait déjà jugé que l'**exécution** devait
être activée par défaut ; la **porte**, elle, restait fermée par défaut. Les deux défauts s'alignent.

Cette décision ne compense **pas** un défaut d'affichage : F-47 a d'abord rendu l'invite impossible à
manquer (SF-47-01, SF-47-02, SF-47-03). C'est parce que le coût de la porte est devenu visible —
deux minutes qu'on voit courir plutôt que deux minutes de silence — que la question était posable.

---

## Comportement attendu

### Cas nominal — un projet neuf exécute sans demander

1. `WorkspaceService.createLocal()` crée le projet avec `agentAskBeforeBash = false`.
2. Les autres chemins de création (archive, dépôt Git) ne posaient déjà rien : ils héritent du même
   défaut. Les trois sources de projet sont désormais cohérentes.
3. Le premier tour exécute sa première commande sans invite. La sortie, elle, reste affichée
   commande par commande : on **voit** ce qui s'exécute, on n'a plus à l'autoriser.

### Cas nominal — le réglage reste celui de l'utilisateur

4. `PATCH` du réglage (`AtelierSessionService.setAskBeforeBash`, F-33 / SF-33-01) inchangé : la
   porte s'active et se désactive par projet, depuis l'en-tête du terminal.
5. **Changé** : basculer la cible d'exécution sur `RUNNER` ne **réarme** plus la porte.
   `WorkspaceService.setExecutionTarget()` imposait `agentAskBeforeBash = true` à chaque bascule
   (SF-38-08, décision D7) ; le laisser en place rendrait le nouveau défaut inopérant et
   ré-activerait la porte **dans le dos** d'un utilisateur qui l'avait éteinte — au retour d'un
   coupe-circuit, par exemple, qui repasse le projet en `SANDBOX` puis en `RUNNER`.

### Cas nominal — ce qui ne bouge pas

6. Les projets **existants** ne sont pas modifiés : aucune donnée n'est réécrite, chacun garde le
   réglage qu'il porte.
7. Le **journal d'audit** (`runner_audit`, SF-38-08) reste écrit pour chaque commande, non
   désactivable.
8. Le **coupe-circuit** (SF-38-08) reste disponible et non désactivable.
9. Les **exclusions de secrets** côté runner (`.runnerignore`) sont inchangées.
10. L'invite, quand la porte est armée, garde tout ce que F-47 lui a donné : peinte à l'instant où
    elle arrive, rappelée tant qu'elle attend, compte à rebours, expiration dite pour ce qu'elle est.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Un projet dont l'utilisateur a armé la porte reçoit une commande | L'invite est posée comme aujourd'hui : le défaut change, le mécanisme non | — |
| Bascule de cible sur un projet dont la porte est armée | La porte **reste armée** : on ne la désarme pas plus qu'on ne l'arme dans le dos de l'utilisateur | — |
| Projet d'un autre utilisateur | `requireOwned(userId, id)` inchangé : rien n'est lisible ni modifiable hors de son propriétaire | 404 |

---

## Critères d'acceptation

- [ ] `createLocal()` rend un workspace dont `isAgentAskBeforeBash()` est `false`.
- [ ] `setExecutionTarget(..., RUNNER)` ne modifie plus `agentAskBeforeBash`.
- [ ] `setExecutionTarget(..., RUNNER)` sur un projet dont la porte est armée la laisse armée.
- [ ] `setAskBeforeBash(userId, id, true)` arme toujours la porte, et `false` la désarme.
- [ ] Quand la porte est armée, `AtelierChatService` demande toujours l'autorisation avant `bash`.
- [ ] Aucun projet existant n'est réécrit (aucune migration de données).
- [ ] Tout accès reste filtré par `user_id` (`requireOwned`).
- [ ] `OQ-14` est close dans `docs/OPEN_QUESTIONS.md`, avec la décision et sa date.
- [ ] Un ADR enregistre l'arbitrage sécurité / adoption (`docs/ADR.md`).

---

## Périmètre

### Hors scope (explicite)

- Modifier les projets existants (le PO n'a pas demandé de reprise de données).
- Toucher au journal d'audit, au coupe-circuit ou aux exclusions de secrets.
- Toucher au délai de 120 s, à l'invite, au rappel ou au compte à rebours (F-47 / SF-47-01→03).
- Retirer le réglage : il reste activable **par projet**.
- Le diagnostic du défaut d'affichage encore ouvert → question Q1 de `SF-47-03`.

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format / Valeurs autorisées | Unicité | Normalisation |
|-------|-------------|-------------|----------------------------|---------|---------------|
| `workspaces.agent_ask_before_bash` | oui | — | booléen, **non nul**, **`false` par défaut** | — | — |

Notes :
- La colonne existe depuis la migration `044` et y est déjà déclarée
  `defaultValueBoolean="false"` avec `nullable="false"`. Le défaut **base** était donc déjà celui
  que le PO demande ; c'est le **code applicatif** qui en divergeait.

---

## Technique

### Endpoint(s)

Aucun nouvel endpoint. Comportement modifié derrière :

| Méthode | Chemin | Changement |
|---|---|---|
| `POST` | `/api/workspaces/local` | le projet créé porte `askBeforeBash: false` |
| `PUT` | `/api/workspaces/{id}/execution-target` | ne réarme plus la porte |

### Tables impactées

`workspaces` — **aucun changement de schéma**, aucune donnée réécrite.

### Migration Liquibase

- [ ] Oui
- [x] Non applicable — voir **A2** dans les notes : la colonne porte déjà
      `DEFAULT false` depuis `044-workspaces-agent-confirmation.xml`, et les lignes sont insérées par
      JPA avec la valeur de l'entité — le défaut base n'est jamais celui qui s'applique. Une
      changeset qui ré-affirmerait un défaut déjà posé serait du DDL sans effet.

### Composants Angular

Aucun. L'écran lit `askBeforeBash` depuis `WorkspaceDetailResponse` et affiche l'état réel du
projet ; il affichera « désactivé » sans qu'une ligne change.

---

## Plan de test

### Tests unitaires

- [ ] `WorkspaceServiceLocalTest` — `createLocal()` rend `agentAskBeforeBash == false`.
- [ ] `WorkspaceServiceLocalTest` — `setExecutionTarget(RUNNER)` laisse la porte **désarmée** quand
      elle l'était.
- [ ] `WorkspaceServiceLocalTest` — `setExecutionTarget(RUNNER)` laisse la porte **armée** quand
      elle l'était.
- [ ] `AtelierSessionServiceTest` — `setAskBeforeBash` arme et désarme toujours (non-régression).
- [ ] `AtelierChatServiceRunnerGuardTest` — porte armée ⇒ l'autorisation est toujours demandée avant
      `bash` (non-régression).

### Tests d'intégration

- [ ] `RunnerGuardrailsApiIntegrationTest` — la suite existante reste verte : le journal d'audit et
      le coupe-circuit ne dépendent pas du réglage.

### Isolation workspace

- [x] Testée — `requireOwned(userId, id)` est le seul chemin d'accès, inchangé ; la suite existante
      couvre le refus sur un projet d'un autre utilisateur.

---

## Dépendances

### Subfeatures bloquantes

- `F-33 / SF-33-01` — statut : done (le réglage par projet).
- `F-38 / SF-38-08` — statut : done (la porte en cible runner, D7 amendée ici).
- `F-38 / SF-38-20` — statut : done (autorisations groupées, réglage persistant).
- `F-47 / SF-47-01→03` — statut : done (l'invite rendue impossible à manquer, **préalable** à ce
  desserrement).

### Questions ouvertes impactées

- [x] `OQ-14` — **tranchée par le PO le 2026-09-10** : la porte n'est plus armée par défaut. Close
      dans `docs/OPEN_QUESTIONS.md`, ADR-018 créé.

---

## Préoccupations transversales

**Plans / limites — cochée.** Un défaut de gate change de valeur ; les composants qui le lisent ont
tous été relus :

| Composant | Lit quoi | Effet du changement |
|---|---|---|
| `WorkspaceService.createLocal` | pose la valeur | **modifié** : `false` |
| `WorkspaceService.setExecutionTarget` | posait `true` à chaque bascule vers `RUNNER` | **modifié** : ne pose plus rien |
| `AtelierSessionService.setAskBeforeBash` | écrit la valeur sur demande de l'utilisateur | inchangé |
| `AtelierSessionService` (ouverture de session) | `SessionPermissions.of(workspace.isAgentAskBeforeBash())` | inchangé — suit la valeur du projet |
| `AtelierChatService` (boucle maison) | `"bash".equals(tool) && workspace.isAgentAskBeforeBash()` | inchangé — suit la valeur du projet |
| `WorkspaceDetailResponse` | expose `askBeforeBash` à l'écran | inchangé |
| `RunnerConfirmationGate` | la porte elle-même | inchangée |
| `RunnerAuditService` / coupe-circuit | ne lisent pas le réglage | inchangés, non désactivables |

Aucun autre composant ne lit `agentAskBeforeBash`.

---

## Notes et décisions

- **A1 — La bascule de cible ne réarme plus la porte (arbitrage, réversible).** SF-38-08 / D7 posait
  `agentAskBeforeBash = true` à chaque passage en cible `RUNNER`, au motif que `always_allow` était
  acceptable dans un conteneur jetable mais pas sur une vraie machine. Ce motif est exactement celui
  que le PO vient de trancher dans l'autre sens. Le laisser en place aurait deux effets, tous deux
  mauvais : le nouveau défaut serait sans effet dès la première bascule, et la porte se réarmerait
  **dans le dos** d'un utilisateur qui l'avait éteinte — notamment au retour d'un coupe-circuit, qui
  repasse le projet en `SANDBOX` puis, au ré-appairage, en `RUNNER`.
  **Alternative écartée** : garder D7 et n'appliquer le nouveau défaut qu'à la création. Écartée
  parce qu'elle produit deux projets identiques avec deux comportements, selon qu'ils ont ou non
  changé de cible une fois. **Réversible** : une ligne, au même endroit.
- **A2 — Pas de migration Liquibase (arbitrage, réversible).** La demande initiale prévoyait une
  migration « pour le défaut ». Lecture faite, `044-workspaces-agent-confirmation.xml` déclare déjà
  la colonne `defaultValueBoolean="false"` : le défaut **base** est celui que le PO demande, et il
  l'a toujours été. La divergence vivait uniquement dans `WorkspaceService`. Comme les lignes sont
  insérées par JPA avec la valeur portée par l'entité, le défaut base ne s'applique de toute façon
  jamais. Une changeset qui le ré-affirmerait serait du DDL sans effet, et une migration de plus à
  rejouer sur chaque environnement. **Réversible** : si un chemin d'insertion hors JPA apparaît, la
  changeset s'ajoute au numéro libre suivant.
- **A3 — Les projets existants ne sont pas touchés.** Conforme à la demande. Un projet qui portait la
  porte armée la garde ; c'est un réglage que son propriétaire peut avoir voulu.
- **A4 — Ce qui reste, reste.** Journal d'audit, coupe-circuit et exclusions de secrets ne sont pas
  des réglages : ils constatent et ils coupent. Le desserrement porte sur la **question posée avant**
  la commande, pas sur la trace qu'elle laisse ni sur le moyen de tout arrêter.
