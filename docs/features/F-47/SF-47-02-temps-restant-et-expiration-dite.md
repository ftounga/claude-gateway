# Mini-spec — F-47 / SF-47-02 — Le temps restant, et l'expiration dite pour ce qu'elle est

## Identifiant

`F-47 / SF-47-02`

## Feature parente

`F-47` — L'autorisation qu'on ne peut pas manquer

## Statut

`ready`

## Date de création

2026-09-09

## Branche Git

`feat/SF-47-02-temps-restant-et-expiration-dite`

---

## Objectif

> Que l'invite d'autorisation affiche **le temps qui lui reste**, que l'expiration soit dite pour ce
> qu'elle est — *« personne n'a répondu »*, et non *« commande refusée »* —, et que la gateway
> journalise l'**émission** de la demande, pas seulement son expiration.

---

## Déclencheur

Trois manques constatés sur l'incident du 2026-09-08 :

1. **Aucun compte à rebours n'existe** : les deux minutes s'écoulent en silence, et rien ne dit à
   l'utilisateur qu'un délai court.
2. **Le message d'expiration dit « Commande refusée »**, ce qui se lit comme un refus **du système** —
   c'est exactement l'interprétation qu'en a faite l'utilisateur, qui a parlé d'un « problème de
   permissions ». Ce n'était pas un refus : c'était une question restée sans réponse.
3. **Le journal ne trace que l'expiration** (`Aucune décision d'autorisation dans le délai`). Le
   diagnostic a dû **déduire** que la demande avait été émise, faute d'une ligne qui le dise.

---

## Comportement attendu

### Cas nominal — le backend dit combien de temps il attend

`RunnerConfirmationGate` connaît son délai (`app.runner.confirmation.timeout-ms`, 120 000 ms par
défaut) mais ne le dit à personne. Il l'expose désormais (`timeoutMs()`), et
`AtelierChatService.askPermission` le joint à la demande relayée à l'écran.

L'événement `confirm_request` du flux **de la boucle maison** (cible `RUNNER`,
`POST /api/workspaces/{id}/chat/stream`) porte donc un champ de plus :

```
event: confirm_request
data: {"toolUseId":"toolu_1","tool":"bash","detail":"npm test","timeoutMs":120000}
```

**Champ additif** : le flux du bac à sable (F-33, `AtelierAgentController`) ne le porte pas — le
délai y est tenu par le fournisseur, et inventer une valeur serait pire que de n'en donner aucune.
Un écran qui ne le reçoit pas n'affiche simplement aucun compte à rebours.

### Cas nominal — le journal dit l'émission

À l'enregistrement d'une demande, **avant** de la relayer à l'écran, la gateway journalise en
`INFO` :

```
Autorisation demandée (workspace=<uuid>, call=<callId>) : décision attendue sous 120000 ms
```

**Rien de la commande n'est journalisé** — ni `detail`, ni argument : une commande peut contenir un
secret, et un journal de production n'est pas le bon endroit pour l'apprendre. La ligne existante
d'expiration est conservée telle quelle : les deux se répondent.

### Cas nominal — le temps restant à l'écran

À la réception d'une `confirm_request` **portant** un `timeoutMs`, l'écran calcule une échéance
(`Date.now() + timeoutMs`) et affiche, sur l'invite **et** sur le rappel persistant de SF-47-01,
le temps restant, rafraîchi **chaque seconde** :

- au-dessus d'une minute : `Il reste 1 min 47 s pour répondre` ;
- en dessous : `Il reste 12 s pour répondre` ;
- à zéro : `Le délai est écoulé` (l'invite est retirée par la résolution qui suit).

Le compte à rebours **s'arrête** dès que la décision est prise, à la fermeture de l'écran
(`ngOnDestroy`), et n'est **jamais** démarré quand `timeoutMs` est absent.

### Cas nominal — l'expiration dite pour ce qu'elle est

Quand `confirm_resolved` porte `timeout`, le message affiché devient :

> **Personne n'a répondu à la demande d'autorisation dans les 2 minutes : la commande n'a pas été
> exécutée.**

La durée est **celle qui a réellement couru** (dérivée du `timeoutMs` reçu) ; sans elle, le message
se replie sur « dans le délai imparti ». Le mot « refusée » disparaît de ce message : rien n'a été
refusé, personne n'a répondu.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| `timeoutMs` absent du flux (bac à sable, backend antérieur) | Aucun compte à rebours ; message d'expiration replié sur « dans le délai imparti » | — |
| `timeoutMs` nul, négatif ou non numérique | Traité comme absent — jamais de compte à rebours à rebours négatif | — |
| L'échéance est dépassée avant la résolution | Affiche « Le délai est écoulé » et **cesse** de décompter (aucune valeur négative) | — |
| Deux demandes successives dans le même tour | Le compte à rebours repart de l'échéance de la **nouvelle** demande, l'ancien minuteur est arrêté | — |
| L'écran est fermé pendant l'attente | Le minuteur est arrêté (`ngOnDestroy`) : aucun rappel périodique orphelin | — |

---

## Critères d'acceptation

- [ ] `RunnerConfirmationGate` expose son délai (`timeoutMs()`), aligné sur la configuration.
- [ ] Une ligne `INFO` est journalisée à l'**enregistrement** de la demande, avec workspace, callId
      et délai — et **sans** la commande.
- [ ] La ligne `INFO` existante d'expiration est conservée.
- [ ] L'événement `confirm_request` de la boucle maison porte `timeoutMs`.
- [ ] Le flux du bac à sable est inchangé (aucun champ ajouté).
- [ ] Le client conserve l'échéance et affiche le temps restant sur l'invite, rafraîchi chaque seconde.
- [ ] Le rappel persistant de SF-47-01 affiche le même temps restant.
- [ ] Aucun compte à rebours n'est affiché quand `timeoutMs` est absent ou invalide.
- [ ] Le compte à rebours ne descend jamais sous zéro.
- [ ] Le minuteur est arrêté à la décision, à une nouvelle demande, et à la destruction du composant.
- [ ] Le message d'expiration ne contient plus « Commande refusée » et dit que personne n'a répondu.
- [ ] La décision reste vérifiée sur le propriétaire du workspace : `resolve()` inchangé (isolation).

---

## Périmètre

### Hors scope (explicite)

- Changer la valeur du délai (120 s reste 120 s).
- Prolonger le délai depuis l'écran (« donnez-moi plus de temps »).
- Changer la valeur par défaut de la porte de confirmation (question PO, non tranchée).
- Ajouter un compte à rebours au flux du bac à sable (le délai y appartient au fournisseur).
- Le message rendu **au modèle** en cas d'expiration : il est déjà exact pour lui, et le changer
  toucherait au prompt sans bénéfice utilisateur.

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format / Valeurs autorisées | Unicité | Normalisation |
|-------|-------------|-------------|----------------------------|---------|---------------|
| `timeoutMs` (sortant) | Non (additif) | — | entier > 0, millisecondes | — | Ignoré côté client s'il n'est pas un nombre > 0 |

Notes :
- `timeoutMs` est une donnée **sortante** : elle n'est jamais lue d'une requête utilisateur, donc
  aucune validation d'entrée n'est requise côté serveur.

---

## Technique

### Contrat API (figé)

**Événement SSE `confirm_request`** — `POST /api/workspaces/{id}/chat/stream` (cible `RUNNER`) :

| Champ | Type | Obligatoire | Description |
|-------|------|-------------|-------------|
| `toolUseId` | string | oui | identifiant de corrélation (inchangé) |
| `tool` | string | oui | outil concerné, `bash` (inchangé) |
| `detail` | string | oui | commande soumise à décision (inchangé) |
| `timeoutMs` | number | **non — nouveau** | délai au bout duquel la demande expire, en ms |

Aucun autre événement, endpoint, code HTTP ou corps de requête n'est modifié.
`POST /api/workspaces/{id}/chat/confirm` est inchangé.

### Tables impactées

Aucune.

### Migration Liquibase

- [ ] Oui
- [x] Non applicable

### Composants Angular

- `AtelierComponent` — échéance conservée sur `pendingConfirmation`, minuteur d'une seconde, libellé
  du temps restant, message d'expiration reformulé.
- `AtelierTerminalComponent` — affichage du temps restant sur l'invite.
- `atelier.models.ts` / `atelier.types.ts` / `atelier.service.ts` — champ `timeoutMs` additif.

### Classes backend

- `RunnerConfirmationGate` — `timeoutMs()`, journal d'émission.
- `AtelierProgressListener.AtelierConfirmRequest` — composant `timeoutMs`.
- `AtelierChatService.askPermission` — joint le délai à la demande relayée.

---

## Plan de test

### Tests unitaires

- [ ] `RunnerConfirmationGate` — `timeoutMs()` rend la valeur configurée.
- [ ] `RunnerConfirmationGate` — un délai configuré ≤ 0 se replie sur 120 000 ms (non-régression).
- [ ] `AtelierChatService` — la demande relayée à l'écran porte le délai de la porte.
- [ ] `AtelierComponent` — le temps restant est calculé depuis `timeoutMs` et décroît.
- [ ] `AtelierComponent` — aucun compte à rebours sans `timeoutMs`.
- [ ] `AtelierComponent` — le minuteur s'arrête à la résolution et à la destruction.
- [ ] `AtelierComponent` — le message d'expiration dit que personne n'a répondu, avec la durée.
- [ ] `AtelierService` — `confirm_request` sans `timeoutMs` ne pose pas d'échéance ; avec, la pose.
- [ ] `AtelierTerminalComponent` — l'invite affiche le temps restant quand il est fourni, rien sinon.

### Tests d'intégration

- [ ] `AtelierChatServiceRunnerGuardTest` — non-régression : la demande est toujours relayée, la
      décision toujours attendue, et le refus par expiration inchangé côté modèle.

### Isolation workspace

- [x] Applicable — la porte n'accepte une décision que du **propriétaire** du workspace qui a posé la
      demande (`RunnerConfirmationGate.resolve`). Ce test existe (`RunnerConfirmationGateTest`) et
      doit rester vert : aucun champ ajouté ne contourne cette vérification.

---

## Dépendances

### Subfeatures bloquantes

- `F-47 / SF-47-01` — statut : **done** (le rappel persistant, sur lequel le temps restant s'affiche).
- `F-38 / SF-38-08` — statut : done (la porte).

### Questions ouvertes impactées

- [ ] Porte activée par défaut en cible `RUNNER` — **non tranchée**, et **non touchée**.

---

## Notes et décisions

- **D1 — Le délai voyage dans l'événement, il n'est pas deviné côté écran.** Coder 120 000 ms dans le
  client ferait mentir l'écran le jour où la configuration change ; c'est le serveur qui tient le
  délai, c'est lui qui le dit.
- **D2 — Champ additif, jamais obligatoire.** Un écran servi pendant un déploiement peut recevoir
  l'ancien format ; l'absence de compte à rebours est un manque, pas une panne.
- **D3 — Le bac à sable ne reçoit pas de compte à rebours.** Le délai y est celui du fournisseur
  Managed Agent, que la gateway ne connaît pas. Afficher un chiffre inventé serait pire que rien.
- **D4 — Rien de la commande n'entre dans le journal.** Une commande peut porter un secret ; le
  journal ne trace que ce qui permet de suivre la demande (workspace, corrélation, délai).
- **D5 — Le message au modèle est laissé tel quel.** « Commande refusée : aucune autorisation n'a
  été donnée dans le délai imparti » est exact du point de vue du modèle, qui doit savoir qu'il n'a
  pas le droit de continuer. Le défaut constaté était **humain** : c'est le message à l'écran qui
  change.
