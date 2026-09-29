# Mini-spec — F-162 / SF-162-05 — Le filet HUMAIN de rappel (« il manque un détail ? »)

> Base : `project-governance/templates/subfeature-template.md`.
> Cadrage de référence (fait foi) : `docs/features/F-162/CADRAGE-F-162-rappel-a-la-demande.md` (§4, SF-162-05).
> SF précédentes livrées : SF-162-01 (`recall`), SF-162-02 (résumé ancré), SF-162-03 (visibilité), SF-162-04 (« compacter maintenant »).

---

## Identifiant

`F-162 / SF-162-05`

## Feature parente

`F-162` — Le rappel à la demande de l'historique (se souvenir sans tout rejouer)

## Statut

`ready`

## Date de création

2026-09-29

## Branche Git

`feat/SF-162-05-filet-humain`

---

## Objectif

> En une phrase : que fait cette subfeature ?

Ajouter, sous une réponse de l'assistant (hors lecture seule, aucun tour en cours), une **action
discrète** « Il manque un détail ? Chercher dans l'historique » qui, au tap, **relance un tour ciblé**
en invitant explicitement le modèle à appeler l'outil **`recall`** (SF-162-01) sur le sujet de la
question précédente — un **rappel chirurgical initié par l'utilisateur**, PAS un « recharge tout ».

---

## Comportement attendu

### Cas nominal

1. Un tour est terminé : la réponse de l'assistant est affichée dans le fil. L'utilisateur perçoit
   que le modèle a « perdu un détail » d'un échange ancien.
2. Sous la **dernière** réponse de l'assistant (celle qui a du contenu), une **affordance discrète**
   (bouton texte `mat-button`, registre neutre, cible ≥ 44 px) affiche « Il manque un détail ?
   Chercher dans l'historique ». Elle est masquée en lecture seule et pendant qu'un tour tourne
   (`submitting`).
3. Au tap, le terminal émet l'événement `recallContext`. Le composant parent (`AtelierComponent`)
   **réutilise le chemin d'envoi existant** (`startTurn`, le même que « Rejouer » via `launchReplay`) :
   il compose un **message utilisateur** court qui **guide** le modèle —
   « Il te manque peut-être un détail d'un échange antérieur. Utilise d'abord l'outil recall pour
   retrouver dans l'historique le contexte pertinent à ma demande précédente («\<question\>»), puis
   réponds. » — où `<question>` est la **dernière question utilisateur** du fil (la question à laquelle
   se rattache la réponse concernée), et lance un nouveau tour.
4. Le tour se déroule normalement : le modèle, guidé, appelle `recall` (dont la **visibilité**
   « Recherche dans l'historique… » / « Détail rappelé · tour N » de SF-162-03 s'affiche déjà), puis
   répond. Le moteur (LOCAL/SANDBOX) décide du transport — l'affordance ne le connaît pas.

### Cible du rappel (décision documentée)

Le cadrage demande de viser **la question utilisateur à laquelle se rattache la réponse**. La
structure du fil (`messages`, liste plate ordonnée USER/ASSISTANT) et la contrainte « affordance
discrète, pas sur chaque message » convergent vers un placement **sur la dernière réponse de
l'assistant** uniquement. La question rattachée est alors **la dernière question utilisateur** du fil.
C'est le cas « viser le dernier tour » explicitement autorisé par le cadrage (« si la structure ne
permet de viser que le dernier tour, cible le dernier — mais documente-le »). Le message guidé cite
cette question entre guillemets pour que `recall` **vise juste**.

### Cas d'erreur / limites

| Situation | Comportement attendu |
|-----------|---------------------|
| Un tour tourne déjà (`submitting`) | L'affordance n'est **pas** affichée ; si `requestContextRecall` est appelé quand même, il **refuse** (snackbar « un tour est encore en cours ») — jamais deux tours en parallèle |
| Lecture seule (`readOnly`) | Aucune affordance (comme « Rejouer ») |
| Aucun workspace actif / aucune question utilisateur dans le fil | Aucune relance (garde silencieuse) |
| Plafond de place atteint (`liveTerminals.limitReached()`) | Aucune relance (même garde que `send`/`replay`) |
| Message assistant sans contenu (bloc terminal seul) | Pas d'affordance (rien à « approfondir ») |

---

## Critères d'acceptation

- [ ] Sous la **dernière** réponse assistant **avec contenu**, hors lecture seule et hors tour en
      cours, une action discrète « Chercher dans l'historique » est rendue ; elle n'apparaît **pas**
      sous une réponse assistant antérieure, ni en `readOnly`, ni pendant `submitting`.
- [ ] Le tap émet `recallContext` (une fois) ; le parent lance un **nouveau tour** via le chemin
      d'envoi existant (`startTurn`), **sans** nouvel appel réseau maison ni nouvel endpoint.
- [ ] Le message envoyé au modèle **contient une consigne d'appel de `recall`** et **cite la dernière
      question utilisateur** du fil.
- [ ] `requestContextRecall` **refuse** si un tour tourne (`submitting`) et si le plafond est atteint.
- [ ] **Design system** : jetons `--cg-*` uniquement, aucune couleur/police nouvelle, cible tactile
      ≥ 44 px, responsive 390 px (ne casse ni SF-158-22 rail, ni SF-158-23 auto-scroll).
- [ ] **Non-régression** : `send`/`steer`/« Rejouer » (F-131), `recall` (SF-162-01), visibilité
      (SF-162-03), compaction (SF-162-02/04), repli (SF-117), rail/auto-scroll — tests existants verts.
- [ ] **Préfixe stable / cache** : la consigne de relance est un **message utilisateur**, pas un ajout
      volatil à la consigne système ; aucun contenu système modifié.

---

## Périmètre

### Hors scope (explicite)

- **Aucun backend** : pas de nouvel endpoint, pas de nouvel événement de flux, pas de flag serveur —
  `recall` existe déjà (SF-162-01) et sa visibilité aussi (SF-162-03). La relance est un message
  utilisateur guidé.
- **Pas de « recharger TOUT l'ancien contexte »** (recrée le coût qu'on fuit — hors cadrage).
- **Pas de détection automatique** que le modèle est « largué » (c'est le filet AUTO côté modèle,
  déjà porté par la description d'outil de SF-162-01) : ici c'est **l'utilisateur** qui décide.
- **Pas d'affordance sur chaque message** ni de rappel inter-projets.
- **Pas de recherche sémantique** (SF-162-06).

---

## Valeurs initiales

Aucune entité, aucune table, aucune colonne, aucune migration.

| Réglage | Valeur | Règle |
|---------|--------|-------|
| Placement de l'affordance | dernière réponse assistant avec contenu | helper `isLastAssistantMessage` (miroir de `isLastUserMessage`) |
| Question ciblée | dernière question `USER` du fil | dernière entrée `role === 'USER'` non vide de `messages()` |
| Consigne de relance | message utilisateur (constante de gabarit) | composée dans `requestContextRecall` |

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format / Valeurs | Normalisation |
|-------|-------------|-------------|------------------|---------------|
| question ciblée | Oui (sinon garde silencieuse) | — | texte de la dernière question `USER` | `trim` ; ignorée si vide |

---

## Technique

### Endpoint(s)

Aucun endpoint HTTP nouveau. Aucun événement SSE nouveau. La relance passe par `startTurn` existant
(qui choisit `sendExec` / `streamChat` selon le moteur).

### Tables impactées

Aucune. (La lecture de l'historique par `recall` est déjà isolée `user_id` + `workspace_id`, SF-162-01.)

### Migration Liquibase

- [x] Non applicable — frontend seul, aucune table ni colonne.

### Composants Angular impactés

- `atelier/terminal/atelier-terminal.component.ts` — `@Output() recallContext` ;
  helper `isLastAssistantMessage(message)`.
- `atelier/terminal/atelier-terminal.component.html` — l'affordance discrète sous la dernière réponse
  assistant.
- `atelier/terminal/atelier-terminal-compaction.component.scss` (feuille F-162 existante) — style
  `terminal-recall-net`, registre neutre `--cg-*`, cible ≥ 44 px, responsive.
- `atelier/atelier.component.ts` — `requestContextRecall()` (compose la consigne, réutilise `startTurn`)
  + helper `lastUserQuestion()`.
- `atelier/atelier.component.html` — câblage `(recallContext)="requestContextRecall()"`.

### Points de câblage

- Émission : bouton HTML terminal → `recallContext.emit()`.
- Réception : `AtelierComponent.requestContextRecall()` → `startTurn(id, consigne)` (chemin « Rejouer »).

---

## Plan de test

### Tests unitaires — frontend (terminal)

- [ ] L'affordance « Chercher dans l'historique » est rendue sous la **dernière** réponse assistant
      avec contenu ; absente sous une réponse antérieure.
- [ ] Elle est **absente** en `readOnly` et pendant `submitting`.
- [ ] Le clic émet `recallContext` exactement une fois.
- [ ] `isLastAssistantMessage` : vrai pour la dernière réponse assistant, faux sinon.

### Tests unitaires — frontend (parent)

- [ ] `requestContextRecall` lance un tour (`streamChat`) avec un message qui **contient « recall »**
      et **cite la dernière question utilisateur**.
- [ ] Refus si `submitting` (aucun `streamChat`, snackbar émise).
- [ ] Refus si `liveTerminals.limitReached()` (aucun `streamChat`).

### Tests d'intégration / non-régression

- [ ] Suites terminal + parent existantes vertes (Rejouer, recall visibilité, compaction, rail,
      auto-scroll, lecture seule).

### Isolation workspace / utilisateur

- [x] Applicable indirectement — aucun **nouvel** accès données. La relance déclenche `recall`, déjà
      isolé `user_id` + `workspace_id` (SF-162-01). Aucun chemin d'accès nouveau à isoler.

---

## Préoccupations transversales

| Préoccupation | Concernée ? | Composants impactés |
|--------------|-------------|---------------------|
| Auth / Principal | Non | — |
| Contexte tenant | Non (aucun nouvel accès données ; `recall` réutilise l'isolation existante) | — |
| Plans / limites | **Oui (réutilisation)** — la relance passe la même garde que `send`/`replay` : `liveTerminals.limitReached()`. Aucun nouveau gate. Composant : `AtelierComponent.requestContextRecall`. |
| Navigation / routing | Non — aucune route, aucun guard, aucun événement de flux nouveaux ; réutilise `startTurn`. |

---

## Dépendances

### Subfeatures bloquantes

- **SF-162-01** (livrée) : l'outil `recall` que la relance guide.
- **SF-162-03** (livrée) : la visibilité du `recall` déclenché.
- **F-131 / SF-131-01** (livrée) : le chemin `startTurn`/« Rejouer » réutilisé.

### Questions ouvertes impactées

- Aucune.

---

## Notes et décisions

- **Frontend-seul, aucun backend** : le cadrage l'espérait (« idéalement AUCUN nouveau backend ») ;
  c'est atteint — `recall` et sa visibilité existent déjà, la relance est un simple message guidé.
- **Réutilisation maximale** : `startTurn` (chemin « Rejouer »/`send`), garde du plafond, snackbar.
- **Gateway-First / Provider-First** : rien de neuf côté moteur ; la consigne guide un outil de gateway
  déjà exposé, indépendant du fournisseur.
- **Préfixe stable / cache** : la consigne est un **message utilisateur** (volatil, à sa place dans le
  message), jamais un ajout à la consigne système — la règle mémoire est respectée par construction.
- **Discrétion** : l'affordance ne s'affiche que sous la **dernière** réponse assistant, jamais sur
  chaque message — ce n'est pas le geste principal (le modèle appelle `recall` seul dans le cas courant).
