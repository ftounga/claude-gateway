# Mini-spec — F-38 / SF-38-20 — Ne plus cliquer à chaque commande

## Identifiant

`F-38 / SF-38-20`

## Feature parente

`F-38` — Exécution sur machine connectée (runner local)

## Statut

`ready`

## Date de création

2026-09-06

## Branche Git

`feat/SF-38-20-autorisations-groupees`

---

## Objectif

> Donner deux façons de ne plus être interrompu à chaque commande — l'une bornée au **message**,
> l'autre au **projet** — sans rien retirer à ce qui protège réellement.

---

## Déclencheur

Banc d'essai. La construction d'une application fullstack demande **treize étapes de procédure**,
chacune faite de plusieurs commandes : `git init`, `cp -r`, `curl start.spring.io`, `npm install`,
deux builds. Chacune ouvre une demande d'autorisation, et il faut cliquer.

Demande du product owner, en deux temps : *« je veux aussi pouvoir lui donner l'autorisation pour
l'exécution de toutes les commandes et ne pas avoir à cliquer sur autoriser tout le temps »*, puis
*« mais je veux aussi l'option : ne plus jamais demander sur ce projet »*.

---

## Ce que cela amende, et pourquoi

**SF-38-08 (décision D7)** avait rendu la validation **non désactivable** en cible `RUNNER` :

> *« En cible RUNNER, l'exécution de commandes l'est toujours : le réglage `agent_ask_before_bash`
> n'est pas consulté ici, il n'est pas désactivable. »*

Le raisonnement était bon — une garde qui protège une vraie machine — mais il n'avait pas été
confronté à l'usage. Le banc d'essai a montré son prix : **des dizaines de clics pour une seule
tâche**, et une garde qu'on subit finit par être contournée plutôt que respectée. Le réglage existait
déjà en base (`workspaces.agent_ask_before_bash`), avec son endpoint ; il n'était simplement pas
consulté.

**Ce qui disparaît est le clic, jamais la trace.** Le journal d'audit continue de consigner chaque
commande — autorisée, refusée, expirée — et le coupe-circuit reste immédiat. Ce sont eux qui
répondent à « qu'est-ce qui a tourné sur ma machine », et ils ne bougent pas.

---

## Comportement attendu

### Deux gestes, deux portées

| Geste | Portée | Ce qu'il faut pour revenir en arrière |
|---|---|---|
| **« Tout autoriser pour ce message »** | le **tour** en cours | rien : le message suivant redemande |
| **« Ne plus demander sur ce projet »** | le **projet**, jusqu'à nouvel ordre | rebasculer le réglage |

Le premier est le raccourci ; le second est une décision. Les distinguer est l'essentiel de cette
subfeature : on autorise ce qu'on a **commencé à voir**, ou bien on renonce à voir — ce n'est pas le
même geste, et ça ne doit pas être le même bouton.

### « Tout autoriser pour ce message »

1. La **première** commande demande toujours l'autorisation : c'est là qu'on choisit, et on a vu de
   quoi il s'agit.
2. Le choix vaut pour toutes les commandes **suivantes du même tour**.
3. La marque est effacée à l'ouverture du message suivant — comme celle d'interruption.
4. Elle est **diffusée aux pods pairs** : la boucle tourne peut-être ailleurs que là où le clic
   atterrit (même mécanique que la décision elle-même, SF-38-13).
5. Si la demande qui l'a déclenchée vient d'expirer, la marque reste valable : c'est elle
   l'essentiel, pas la résolution de cette demande-là.

### « Ne plus demander sur ce projet »

Le réglage `agent_ask_before_bash` redevient modifiable en cible `RUNNER`, et il est **consulté** par
la boucle. Il reste posé à `true` à la bascule vers `RUNNER` (SF-38-05) et à la création d'un projet
local (SF-38-15) : le défaut demande toujours, c'est le choix explicite qui le lève.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| « Tout autoriser » sans demande en attente | La marque est posée quand même ; aucune erreur |
| « Tout autoriser » avec une décision de refus | Ignoré : on n'autorise pas tout en refusant |
| Projet en cible `SANDBOX` | Inchangé — la porte n'y a jamais été obligatoire |
| Nouveau message après « tout autoriser » | La porte redemande, comme si de rien n'était |

---

## Critères d'acceptation

- [ ] « Tout autoriser pour ce message » couvre les commandes suivantes **du même tour**.
- [ ] La marque ne survit pas au message : le suivant redemande.
- [ ] Elle est posée même si aucune demande n'attend, et **diffusée** aux pods pairs.
- [ ] Une décision de refus accompagnée de « tout autoriser » n'autorise rien.
- [ ] Le réglage `agent_ask_before_bash` est **consulté** en cible `RUNNER`.
- [ ] Le désactiver ne renvoie plus `409` mais réussit.
- [ ] Le défaut reste « demander » : posé à la bascule `RUNNER` et à la création d'un projet local.
- [ ] **L'audit consigne chaque commande dans les deux cas** — c'est le clic qui disparaît, pas la
      trace.
- [ ] Isolation `user_id` inchangée : `requireOwned` reste le premier geste.

---

## Périmètre

### Hors scope

- Une liste d'autorisations par motif (« toujours autoriser `npm *` ») : plus fin, plus complexe, et
  personne ne l'a demandé.
- La confirmation des **écritures de fichier** : elles n'ont jamais été soumises à la porte, et ce
  n'est pas ce lot qui doit rouvrir ce sujet.

---

## Technique

### Endpoint(s)

| Méthode | URL | Changement |
|---|---|---|
| POST | `/api/workspaces/{id}/chat/confirm` | Champ additif `allowAll` |
| PUT | `/api/workspaces/{id}/agent/confirmation` | **Existant** ; ne refuse plus en cible `RUNNER` |

### Tables impactées

Aucune : `workspaces.agent_ask_before_bash` existe depuis F-33.

### Migration Liquibase

- [x] **Non applicable.**

### Classes impactées

| Classe | Changement |
|--------|-----------|
| `atelier/dto/AgentConfirmRequest` | Champ `allowAll` |
| `atelier/AtelierChatService` | Registre des tours couverts ; `requiresConfirmation` consulte le projet |
| `atelier/agent/AtelierSessionService` | Le refus de désactivation en cible `RUNNER` est levé |

---

## Préoccupations transversales

| Préoccupation | Concernée | Composants impactés |
|--------------|-----------|--------------------|
| Auth / Principal | Non | — |
| **Contexte tenant** | **Oui** | La marque de tour est clefée par `userId:workspaceId`, jamais par le seul workspace — comme celle d'interruption. `requireOwned` reste le premier geste de `confirmToolUse`. |
| Plans / limites | Non | Aucune consommation, aucun gate ajouté |
| Navigation / routing | Non | — |
| **Sécurité** | **Oui** | Ce qui protège ne change pas : journal d'audit exhaustif, coupe-circuit immédiat, exclusions `.runnerignore`, confinement des outils fichiers. Ce qui change est le nombre de clics. À noter, et à dire dans la documentation : **`bash` n'est pas confiné à la racine** — désactiver la demande sur un projet donne à l'agent la machine, dans la limite des droits du compte qui a lancé le runner (SF-38-18). |

---

## Plan de test

### Tests unitaires

- [ ] « Tout autoriser » : les commandes suivantes du tour ne posent plus de demande.
- [ ] La marque ne survit pas au message suivant.
- [ ] Posée sans demande en attente : aucune erreur.
- [ ] Refus + `allowAll` : rien n'est autorisé.
- [ ] Projet qui ne demande plus : aucune demande, **et l'audit consigne quand même**.
- [ ] Projet qui demande (défaut) : comportement inchangé.

### Tests d'intégration

- [ ] `PUT /agent/confirmation` avec `enabled=false` sur un projet runner → **200** (était 409).

### Isolation workspace

- [x] Applicable — couverte par les tests existants de la boucle.

---

## Notes et décisions

**D1 — Deux gestes, pas un réglage à trois valeurs.** « Pour ce message » et « pour ce projet » ne
sont pas deux crans de la même échelle : l'un est un raccourci pris en connaissance de cause sur un
travail qu'on regarde, l'autre est un renoncement durable. Les fondre dans un seul réglage rendrait
le premier aussi lourd à choisir que le second.

**D2 — La première commande demande toujours.** Ce n'est pas une limite technique : c'est ce qui rend
le raccourci honnête. On autorise la suite d'un travail dont on vient de voir le premier geste.

**D3 — Le défaut reste « demander ».** Rien n'est désactivé pour personne : le projet neuf demande,
la bascule vers `RUNNER` repose la garde. C'est le choix explicite qui la lève, et il reste
réversible d'un clic.

**D4 — Ce qui disparaît est le clic, jamais la trace.** L'audit consigne dans tous les cas. C'est ce
qui permet de dire « ne me demande plus » sans dire « ne note plus rien » — et c'est la raison pour
laquelle amender D7 est acceptable.
