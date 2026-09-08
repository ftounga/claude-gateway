# Mini-spec — F-47 / SF-47-01 — L'invite est peinte, et rappelée tant qu'elle attend

## Identifiant

`F-47 / SF-47-01`

## Feature parente

`F-47` — L'autorisation qu'on ne peut pas manquer

## Statut

`done` — PR #304, mergée le 2026-09-09

## Date de création

2026-09-09

## Branche Git

`feat/SF-47-01-invite-peinte-et-rappelee`

---

## Objectif

> Qu'une demande d'autorisation soit **peinte à l'instant où elle arrive** — même quand le flux se
> tait juste après — et qu'un **rappel persistant**, visible par-dessus tout panneau superposé, la
> signale tant qu'aucune décision n'est prise.

---

## Déclencheur

Cause racine établie le 2026-09-09 (voir `F-47-cadrage.md`) : après `pendingConfirmation.set(...)`,
**aucun cycle de détection de changement** n'est déclenché, parce que le flux SSE se tait ensuite —
le serveur attend la décision. Angular en mode zone ne relit les gabarits qu'à la fin d'un lot de
microtâches ; l'invite est le **seul** événement du flux suivi d'un silence. L'utilisateur attend
deux minutes devant un écran qui ne lui a rien demandé.

---

## Comportement attendu

### Cas nominal — l'invite est peinte immédiatement

1. Le flux relaie `confirm_request` (moteur `LOCAL_MACHINE` **ou** `HOSTED_SANDBOX`).
2. `showConfirmation()` positionne `pendingConfirmation`, comme aujourd'hui.
3. **Nouveau** : un cycle de rendu est **forcé** dans la foulée (`ApplicationRef.tick()`), sans
   attendre un événement suivant. L'invite est visible sans qu'aucune microtâche ne survienne.
4. Le même forçage est appliqué à la **résolution** (`clearConfirmation()`) : quand la porte expire
   ou qu'un autre onglet tranche, le message d'expiration arrive lui aussi dans un silence.

### Cas nominal — le rappel persistant

Tant qu'une décision est attendue (`pendingConfirmation() !== null`), une **bande de rappel** est
affichée :

- **fixée en haut de l'écran**, indépendante du défilement du flux ;
- **au-dessus de tout panneau superposé** — explorateur de fichiers de SF-39-18 compris
  (`z-index` strictement supérieur à celui de `.files-overlay`) ;
- texte : « Claude attend votre autorisation pour exécuter une commande. » ;
- un bouton **« Voir la demande »** qui ferme le panneau superposé s'il est ouvert, puis ramène
  l'invite dans le champ de vision (`scrollIntoView`) ;
- elle **disparaît** dès que `pendingConfirmation` repasse à `null` — décision prise, expiration,
  fin de tour ou erreur de flux.

Le rappel ne **remplace pas** l'invite : il ne porte aucun bouton « Autoriser ». Autoriser reste un
geste pris **devant la commande**, dans le flux, à côté de ce qui permet de juger (F-33 / SF-33-03).

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Un cycle de rendu est déjà en cours quand le forçage survient (`tick` ré-entrant) | Le forçage est sans effet et **n'interrompt rien** : le cycle en cours lira le signal de toute façon. Aucune erreur remontée à l'utilisateur | — |
| Le composant terminal n'est pas monté quand « Voir la demande » est cliqué | Aucun défilement, aucune exception ; le panneau superposé est tout de même fermé | — |
| Le tour se termine ou échoue alors qu'une invite est en attente | Le rappel disparaît en même temps que l'invite (comportement existant conservé) | — |
| Deux demandes se succèdent sur le même tour | Le rappel reste affiché sans clignoter ; il vise toujours la demande en cours | — |

---

## Critères d'acceptation

- [ ] Après `onConfirmRequest`, un cycle de rendu est déclenché **sans** qu'aucun autre événement du
      flux ne soit nécessaire (vérifié par espionnage de `ApplicationRef.tick`).
- [ ] Idem après `onConfirmResolved`.
- [ ] Le forçage vaut pour les **deux** moteurs (`LOCAL_MACHINE` et `HOSTED_SANDBOX`).
- [ ] Un `tick()` ré-entrant ne casse pas l'écran : l'exception est absorbée, l'invite reste posée.
- [ ] La bande de rappel est présente dans le DOM tant que `pendingConfirmation() !== null`, absente
      sinon.
- [ ] La bande de rappel a un `z-index` strictement supérieur à `.files-overlay` (900).
- [ ] « Voir la demande » ferme l'explorateur de fichiers s'il est ouvert.
- [ ] « Voir la demande » appelle le défilement vers l'invite du composant terminal.
- [ ] Aucun bouton de décision (« Autoriser » / « Refuser ») n'est ajouté hors du flux.
- [ ] Aucun accès à des données : isolation `user_id` inchangée (aucun appel réseau ajouté).

---

## Périmètre

### Hors scope (explicite)

- Déplacer l'invite hors du flux (modale) — décision F-33 / SF-33-03 maintenue.
- Le compte à rebours et la reformulation du message d'expiration → **SF-47-02**.
- Toucher au délai de 120 s ou à la valeur par défaut de la porte.
- Notification système / son / titre d'onglet clignotant (non demandé, coûteux à doser).

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format / Valeurs autorisées | Unicité | Normalisation |
|-------|-------------|-------------|----------------------------|---------|---------------|
| — | — | — | Aucune donnée saisie n'est ajoutée par cette SF | — | — |

Notes :
- Aucune entrée utilisateur nouvelle, aucun champ persisté, aucun endpoint.

---

## Technique

### Endpoint(s)

Aucun. Subfeature **frontend uniquement**.

### Tables impactées

Aucune.

### Migration Liquibase

- [ ] Oui
- [x] Non applicable

### Composants Angular

- `AtelierComponent` (`frontend/src/app/atelier/atelier.component.ts` / `.html` / `.scss`) —
  injection de `ApplicationRef`, méthode privée `nudgeRender()`, méthode publique
  `focusPendingConfirmation()`, bande de rappel dans le gabarit + style.
- `AtelierTerminalComponent`
  (`frontend/src/app/atelier/terminal/atelier-terminal.component.ts` / `.html`) — ancre
  `#askBlock` sur l'invite et méthode publique `revealPendingAsk()`.

---

## Plan de test

### Tests unitaires

- [ ] `AtelierComponent` — `onConfirmRequest` (moteur machine connectée) déclenche `tick()`.
- [ ] `AtelierComponent` — `onConfirmRequest` (moteur bac à sable) déclenche `tick()`.
- [ ] `AtelierComponent` — `onConfirmResolved` déclenche `tick()`.
- [ ] `AtelierComponent` — un `tick()` qui lève ne fait pas échouer la pose de l'invite.
- [ ] `AtelierComponent` — `focusPendingConfirmation()` ferme l'explorateur ouvert.
- [ ] `AtelierComponent` — `focusPendingConfirmation()` sans terminal monté ne lève pas.
- [ ] `AtelierTerminalComponent` — `revealPendingAsk()` appelle `scrollIntoView` sur l'invite quand
      elle est affichée, et ne lève pas quand elle ne l'est pas.

### Tests d'intégration

Sans objet (aucun endpoint). Le rendu de la bande est couvert par un test de gabarit :

- [ ] Le DOM contient `.atelier-ask-recall` quand une invite est en attente, et ne le contient pas
      sinon.

### Isolation workspace

- [x] Non applicable — raison : aucune lecture ni écriture de données, aucun appel réseau ajouté.

---

## Dépendances

### Subfeatures bloquantes

- `F-33 / SF-33-03` — statut : done (l'invite dans le flux).
- `F-38 / SF-38-08` — statut : done (la porte de confirmation en cible runner).
- `F-39 / SF-39-18` — statut : done (l'explorateur en surcouche, au-dessus duquel le rappel vit).

### Questions ouvertes impactées

- [ ] Porte activée par défaut en cible `RUNNER` — **non tranchée**, et **non touchée** par cette SF.

---

## Notes et décisions

- **D1 — `ApplicationRef.tick()` plutôt qu'un `setTimeout(0)`.** Les deux relancent un cycle ; la
  mesure de production a été faite sur `tick()` (1297 → 1298), c'est donc le geste dont on sait
  qu'il corrige le cas observé. Un `setTimeout` reposerait à nouveau sur zone.js, c'est-à-dire sur
  le mécanisme qui vient précisément de ne pas se déclencher.
- **D2 — Le forçage est posé à un seul endroit par événement** (`showConfirmation`,
  `clearConfirmation`), et non dispersé dans chaque `zone.run`. Les autres événements du flux sont
  toujours suivis d'un autre événement ; les couvrir tous coûterait un cycle de rendu par fragment
  de texte relayé, pour rien.
- **D3 — Le rappel est affiché en permanence tant qu'une décision est attendue**, sans chercher à
  savoir si l'invite est déjà visible à l'écran. Un rappel conditionnel dépendrait d'une mesure de
  visibilité (`IntersectionObserver`) qui ajouterait un mécanisme fragile pour supprimer une
  redondance sans danger. **Réversible** : le jour où la redondance gêne, la condition s'ajoute au
  même endroit.
- **D4 — Le rappel ne porte pas de bouton de décision.** Autoriser sans voir la commande serait
  exactement l'automatisme que la porte cherche à empêcher.
