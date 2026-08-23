# Mini-spec — [F-30 / SF-02] Rendu terminal de l'exécution

---

## Identifiant

`F-30 / SF-02`

## Feature parente

`F-30` — Atelier — expérience terminal

## Statut

`ready`

## Date de création

2026-08-24

## Branche Git

`feat/SF-30-02-rendu-terminal`

---

## Objectif

> En une phrase : que fait cette subfeature ?

Afficher l'exécution comme un **terminal** — commande, puis sortie de la commande — au lieu de la liste d'étapes sans retour actuelle, et **conserver cette transcription** dans le fil une fois le run terminé.

---

## Contexte

SF-30-01 relaie désormais la sortie des commandes (`action_result`). Côté frontend, rien ne la consomme :
`dispatchAgentSseEvent` ignore l'événement, et l'écran affiche une puce `bash: npm test` sans jamais
montrer ce que la commande a produit.

Deux défauts s'ajoutent au manque de sortie :
- **la transcription disparaît** : `onDone` fait `execStreaming.set(null)`, donc les étapes affichées
  pendant le run s'effacent et le fil ne garde que la réponse finale ;
- **aucun repère de progression** : sur une tâche longue (`npm install`), l'écran ne dit pas depuis
  combien de temps la session tourne.

---

## Comportement attendu

### Cas nominal

1. En mode Exécution, chaque commande ouvre un **bloc terminal** : en-tête `$ npm test` (invite orange,
   JetBrains Mono, fond navy).
2. L'événement `action_result` correspondant attache sa **sortie** sous cette commande.
3. L'appariement se fait par `toolUseId` ; à défaut (`null`), la sortie est rattachée à la **dernière
   commande sans sortie**.
4. Une sortie en échec (`error: true`) est signalée visuellement (bordure/puce rouge de la charte).
5. Une sortie longue est **repliée** au-delà d'un seuil d'affichage, avec un bouton « Afficher tout /
   Replier » indiquant le nombre de lignes masquées.
6. Pendant le run, un **indicateur d'activité** affiche l'état de session et la **durée écoulée**
   (chronomètre, mise à jour à la seconde).
7. À la fin du run, la transcription **reste affichée** dans le tour assistant du fil, avec la réponse
   finale et les fichiers modifiés.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| `action_result` sans commande préalable | Bloc terminal **orphelin** créé avec le seul nom d'outil — la sortie n'est jamais perdue |
| `toolUseId` inconnu (aucune commande correspondante) | Rattachement à la dernière commande sans sortie ; à défaut, bloc orphelin |
| Sortie vide | En-tête de commande affiché seul, sans zone de sortie vide |
| Plusieurs `action_result` pour une même commande | Concaténés dans l'ordre d'arrivée sous la même commande |
| Run en erreur (`onError`) | Comportement inchangé : tour utilisateur optimiste retiré, message lisible ; aucune transcription conservée |

---

## Critères d'acceptation

- [ ] `AtelierService` route l'événement SSE `action_result` vers un nouveau callback `onActionResult`
- [ ] Les événements déjà consommés (`agent`, `action`, `status`, `done`, `error`) sont **inchangés**
- [ ] Le mode **Édition** (Phase 1, `streamChat`) est strictement inchangé
- [ ] Une commande et sa sortie s'affichent dans un même bloc terminal, appariés par `toolUseId` avec repli sur la dernière commande sans sortie
- [ ] Une sortie en échec est visuellement distincte d'une sortie réussie
- [ ] Une sortie dépassant le seuil est repliée, dépliable, avec le nombre de lignes masquées
- [ ] Un indicateur de durée écoulée est affiché pendant le run et s'arrête à la fin
- [ ] La transcription est **conservée** dans le tour assistant après `onDone`
- [ ] Aucune couleur ni police hors `DESIGN_SYSTEM.md` (navy `--cg-primary`, orange `--cg-accent`, rouge `--cg-error`, vert `--cg-success`, JetBrains Mono)
- [ ] Aucun endpoint appelé en plus, aucune table, aucune migration

---

## Périmètre

### Hors scope

- Renommage des modes et mise en valeur Gold → SF-30-03
- Session persistante → SF-30-04
- **Compteur de tokens dans l'indicateur d'activité** : le flux SSE ne transporte pas la consommation
  (elle est relevée post-run côté backend, SF-28-12). L'afficher exigerait d'enrichir l'événement
  `done` → traité séparément (SF-30-05), pas ici. L'indicateur porte donc la **durée** seule.
- Coloration ANSI de la sortie, sélection/copie par bloc, défilement automatique fin

---

## Contraintes de validation

| Élément | Contrainte |
|---------|-----------|
| Seuil de repli | **20 lignes** visibles ; au-delà, repli + « Afficher tout (N lignes) » |
| Durée | Format `m:ss`, rafraîchie chaque seconde, arrêtée à `onDone`/`onError` |
| Appariement | `toolUseId` prioritaire ; repli = dernière commande sans sortie ; sinon bloc orphelin |
| Police | JetBrains Mono pour commandes et sorties uniquement |

---

## Technique

### Endpoint(s)

Aucun. Consomme l'événement `action_result` déjà émis par `POST /workspaces/{id}/agent/stream` (SF-30-01).

### Tables impactées / Migration

Aucune.

### Fichiers impactés

| Fichier | Nature |
|---------|--------|
| `core/models/atelier.models.ts` | + `AtelierAgentStreamActionResult`, + `onActionResult` dans les handlers, + `AtelierTerminalBlock` |
| `core/services/atelier.service.ts` | Routage de l'événement `action_result` |
| `atelier/atelier.component.ts` | Construction des blocs terminal (appariement), chronomètre, conservation de la transcription |
| `atelier/atelier.component.html` | Rendu du terminal (en-tête commande + sortie repliable) |
| `atelier/atelier.component.scss` | Styles terminal (jetons `--cg-*` uniquement) |

---

## Plan de test

### Tests unitaires (frontend)

- [ ] Service : `event:action_result` → `onActionResult` avec `tool`, `toolUseId`, `output`, `error`
- [ ] Service : les événements existants restent routés à l'identique (non-régression)
- [ ] Composant : commande + sortie appariées par `toolUseId` dans un même bloc
- [ ] Composant : `toolUseId` null → rattachement à la dernière commande sans sortie
- [ ] Composant : sortie sans commande → bloc orphelin (sortie jamais perdue)
- [ ] Composant : sortie en échec marquée `error`
- [ ] Composant : sortie > 20 lignes → repliée, dépliable, nombre de lignes masquées correct
- [ ] Composant : transcription conservée dans le tour assistant après `onDone`
- [ ] Composant : mode Édition inchangé (non-régression explicite)

### Tests d'intégration

Sans objet : aucune API appelée en plus. La couverture d'intégration du flux est faite côté backend (SF-30-01).

### Isolation utilisateur

- [ ] **Non applicable** — aucun accès aux données ajouté : le composant consomme un flux déjà borné
  par `requireOwned` côté backend. Aucun nouvel appel réseau.

---

## Préoccupations transversales

| Préoccupation | Concernée | Analyse |
|--------------|-----------|---------|
| Auth / Principal | **Non** | Aucun changement ; le flux porte déjà le JWT. |
| Contexte tenant | **Non** | Aucun accès aux données ajouté. |
| Plans / limites | **Non** | Aucun appel de quota ; le gating Gold reste côté backend et SF-30-03. |
| Navigation / routing | **Non** | Aucune route ajoutée ou modifiée. |

---

## Dépendances

- **SF-30-01 (Done)** — sans l'événement `action_result`, il n'y a rien à afficher.

---

## Notes et décisions

- **Transcription conservée après le run** : c'est le vrai défaut d'usage aujourd'hui — on regarde
  défiler des étapes puis tout disparaît. Le tour assistant porte donc la transcription complète.
- **Repli plutôt que troncature** : la sortie est déjà bornée côté backend (10 000 caractères) ; ici
  on ne perd rien, on masque au-delà de 20 lignes et on laisse déplier.
- **Bloc orphelin** : une sortie sans commande identifiable est affichée quand même. Perdre la sortie
  serait pire que l'afficher sans son en-tête.
