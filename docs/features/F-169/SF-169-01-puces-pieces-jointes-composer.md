# Mini-spec — F-169 / SF-169-01 — Puces de pièces jointes dans le composer

## Identifiant

`F-169 / SF-169-01`

## Feature parente

`F-169` — Pièces jointes attachées au message

## Statut

`ready`

## Date de création

2026-09-30

## Branche Git

`feat/SF-169-01-puces-composer`

---

## Objectif

> En une phrase : afficher les fichiers ajoutés au terminal comme des **puces compactes,
> supprimables, DANS le composer** (« joint au message »), **purgées à l'envoi**, à la place de
> l'ancienne bande de notices empilée hors-scrollback jamais purgée.

---

## Comportement attendu

### Cas nominal

1. Le PO ajoute un ou plusieurs fichiers (glisser / coller / trombone). Le dépôt HTTP part (parent,
   inchangé) ; la **barre de progression** annulable s'affiche pendant le transfert.
2. À la réponse du dépôt, chaque fichier reçu devient une **puce compacte dans le composer** :
   **nom court** + **taille** (tabular-nums) + **croix de suppression**. Les puces sont regroupées
   sous un **libellé clair** « Joint au message — sera envoyé avec ta demande ».
3. Le PO peut **retirer une puce** individuellement (croix) : la référence est retirée de l'état
   front (le fichier n'apparaît plus comme joint).
4. À l'**envoi** du message (`send()`), une fois le message parti, **les puces sont purgées**. Le
   flux de consommation backend au tour suivant est **inchangé** (dépôt « chemin uniquement »,
   l'agent lit par `read_file`) : le comportement d'envoi ne régresse pas.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Dépôt refusé par l'endpoint (poste hors ligne, type/plafond) | Une puce d'**échec nommé** (« Dépôt refusé : … ») s'affiche dans le composer, **supprimable**, purgée à l'envoi — plus jamais une bande figée hors-scrollback | 4xx/5xx (relayé) |
| Dépôt annulé par le PO (bouton Annuler pendant la progression) | Une puce **« Dépôt annulé »** s'affiche, supprimable, purgée à l'envoi | — |
| Envoi d'un message sans aucune pièce jointe | Comportement d'envoi inchangé ; aucune puce à purger | — |
| Suppression d'une puce alors qu'aucun endpoint de suppression backend n'existe | La référence est **retirée côté front uniquement** (comportement documenté) ; aucun appel réseau inventé | — |

---

## Critères d'acceptation

- [ ] Les fichiers ajoutés s'affichent comme des **puces dans le composer** (`.terminal-input`), et
      **plus** dans une bande hors-scrollback ; l'ancien `@for` de `.terminal-deposit-notice` rendu
      après `.terminal-scrollback` est **supprimé**.
- [ ] Chaque puce de succès montre **nom court + taille + croix**. La taille utilise `tabular-nums`.
- [ ] Un **libellé de groupe** « joint au message / sera envoyé avec ta demande » est présent dès
      qu'au moins une pièce est jointe.
- [ ] La **barre de progression** annulable pendant le dépôt est **conservée**.
- [ ] Cliquer la croix d'une puce **retire** cette puce (émission d'un `depositRemove` → le parent
      retire la notice de son signal `depositNotices`).
- [ ] À `send()`, une fois le message dispatché, `depositNotices` est **vidé** (puces purgées) ; les
      tests d'envoi existants restent verts (aucune régression du contrat d'envoi).
- [ ] **Design system** : uniquement des jetons `--cg-*` ; puce ≥ 44 px de cible tactile pour la
      croix ; responsive ; sur mobile (SF-158) les puces vivent dans le composer sticky en
      `flex-wrap`, sans casser la safe-area ni le gutter.
- [ ] Aucune logique backend, modèle de message ou table touchée.

---

## Périmètre

### Hors scope (explicite — SF-169-02/03)

- Ajouter `message_id` / migration / table de liaison.
- Changer le contrat d'envoi (le message envoyé ne porte pas la liste des fichiers).
- Rendre les fichiers dans la **bulle** du message.
- Persister le lien fichier ↔ message.
- Tout endpoint backend de suppression de fichier déposé.

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format / Valeurs | Unicité | Normalisation |
|-------|-------------|-------------|------------------|---------|---------------|
| nom affiché de la puce | Oui (dérivé de `path`) | — | dernier segment du chemin (`basename`), `title` = chemin complet | Non | découpe sur `/` |
| taille affichée | Non | — | `sizeLabel` déjà humanisé par le parent (`humanFileSize`) | Non | — |

Notes :
- Le modèle `TerminalDepositNotice` (`core/models/atelier.models.ts`) est **réutilisé tel quel**
  (`id`, `path?`, `sizeLabel?`, `error?`, `cancelled?`) — pas de changement de modèle.

---

## Technique

### Endpoint(s)

Aucun. Frontend seul. Le dépôt existant (`atelier.deposit(...)`, F-115) est inchangé.

### Tables impactées

Aucune.

### Migration Liquibase

- [ ] Oui
- [x] Non applicable

### Composants Angular

- `AtelierTerminalComponent` (`atelier-terminal.component.ts/.html`) — nouveau `@Output depositRemove
  = EventEmitter<string>()` (id de notice) ; rendu des puces dans le composer ; suppression du rendu
  hors-scrollback.
- Feuille `atelier-terminal-pastes.component.scss` (ou une feuille dédiée) — styles des puces de
  pièces jointes, sur le modèle des puces de texte collé (F-146), jetons `--cg-*` uniquement, budget
  de style par feuille (12 ko, `angular.json`).
- Feuille `atelier-terminal-mobile.component.scss` — le composer passe déjà `flex-wrap: wrap` ; la
  ligne des puces prend `flex: 1 1 100%` sans casser la safe-area.
- `AtelierComponent` (`atelier.component.ts`) — `onDepositRemove(id)` retire la notice du signal ;
  purge de `depositNotices` à l'envoi (`dispatchMessage`).

---

## Préoccupations transversales

- [x] **Navigation / rendu — composer + rendu du fil + mobile** — préoccupation cochée.

**Composants impactés (liste obligatoire) :**
1. **Composer** — `atelier-terminal.component.html` `<form class="terminal-input">` : accueille
   désormais les puces (auparavant rendues hors composer). Wrap desktop + wrap mobile vérifiés.
2. **Rendu du fil** — `.terminal-scrollback` (`atelier-terminal.component.html` ~645–1348) : le bloc
   `@for (notice of depositNotices …)` rendu **après** la scrollback (~1404–1419) et le bloc de
   progression associé (~1420–1431) sont **retirés** de leur position hors-scrollback et déplacés
   dans le composer. Le fil n'est plus grignoté par une bande figée.
3. **Mobile (SF-158)** — `atelier-terminal-mobile.component.scss` : le composer sticky
   `flex-wrap: wrap` ; la ligne de puces `flex: 1 1 100%` ; safe-area (`env(safe-area-inset-bottom)`)
   et gutter 16 px inchangés.
4. **Mosaïque (lecture seule)** — `mosaique.component.html` : ne lie pas `depositNotices`, terminaux
   `readOnly` → **aucune régression** (aucune puce n'y était affichée). Vérifié.

---

## Plan de test (Karma — composant terminal)

### Tests unitaires / composant (`atelier-terminal.component`)

- [ ] `depositNotices` non vides → des **puces** `.terminal-attachment` sont rendues **dans le
      composer** (`.terminal-input .terminal-attachment` présent).
- [ ] **Aucune** bande `.terminal-deposit-notice` n'est rendue hors du composer (l'ancien rendu
      empilé est supprimé).
- [ ] Une puce de succès affiche le **nom court** (basename), la **taille**, et une **croix**.
- [ ] Une notice `error` rend une puce d'**échec** (`--error`) ; une notice `cancelled` une puce
      **annulée** (`--cancelled`).
- [ ] Le **libellé de groupe** « joint au message » est présent quand au moins une pièce est jointe.
- [ ] Cliquer la croix émet `depositRemove` avec l'**id** de la notice.
- [ ] La **barre de progression** annulable (`.terminal-deposit-progress`) est toujours rendue
      pendant `depositing`, et son bouton émet `depositCancel`.

### Tests parent (`atelier.component`)

- [ ] `onDepositRemove(id)` retire la notice correspondante du signal `depositNotices`.
- [ ] Après `send()` d'un message non vide, `depositNotices()` est **vide** (purge à l'envoi).
- [ ] Les tests d'envoi existants (`send()` → `startTurn`/`streamChat`/`streamAgent`) restent verts.

### Style (CSSOM, patron SF-158, indépendant du viewport)

- [ ] Sous 819 px, les puces de pièces jointes wrap dans le composer (`flex: 1 1 100%`), cible
      tactile de la croix ≥ 44 px, safe-area préservée.

### Isolation utilisateur

- [x] Non applicable — raison : frontend seul, aucun accès données ; le dépôt existant porte déjà le
      contexte workspace, non modifié ici.

---

## Dépendances

### Subfeatures bloquantes

- Aucune. S'appuie sur F-115 (dépôt) et F-146 (patron de puces) déjà livrés.

### Questions ouvertes impactées

- Aucune (`docs/OPEN_QUESTIONS.md`).

---

## Notes et décisions

- **Réutilisation du patron F-146** (puces de texte collé) : même geste visuel (puce arrondie
  `--cg-divider`, croix `--cg-error` au survol), pour la cohérence du composer.
- **Purge à l'envoi dans `dispatchMessage`** (choke point unique de tous les envois : `startTurn`,
  `steer`, avec ou sans @-mentions) plutôt que dans `send()`, pour couvrir tous les chemins.
- **Décision par défaut (flaguée)** : suppression de la référence d'une puce **côté front
  uniquement** — aucun endpoint backend de suppression n'est inventé (SF-169-02 traitera le lien
  persistant).
