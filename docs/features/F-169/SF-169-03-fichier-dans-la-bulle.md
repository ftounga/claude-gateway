# Mini-spec — F-169 / SF-169-03 — Le fichier dans la bulle qui défile

## Identifiant

`F-169 / SF-169-03`

## Feature parente

`F-169` — Pièces jointes attachées au message

## Statut

`ready`

## Date de création

2026-09-30

## Branche Git

`feat/SF-169-03-fichier-dans-la-bulle`

---

## Objectif

> En une phrase : afficher les fichiers attachés à un message **dans la bulle de ce message**
> (à l'intérieur de `.terminal-scrollback`, donc ils **défilent** avec la conversation), à l'envoi
> et au rechargement, et joindre au corps de requête les réfs des puces en attente.

---

## Comportement attendu

### Cas nominal

1. Le PO a des puces de pièces jointes dans le composer (SF-169-01) ; à l'**envoi** (`send()` →
   `dispatchMessage`), avant la purge des puces, on capture leurs réfs : les `id` de dépôt (envoyés
   au backend dans `attachedDepositIds`, SF-169-02) et les couples `{path, size}` (pour le rendu).
2. Le message utilisateur ajouté au fil porte `files` : ils s'affichent **dans la bulle** du message
   (nom court + taille), **dans** `.terminal-scrollback` → ils défilent naturellement avec le fil.
3. Le backend (SF-169-02) associe ces dépôts au message par `attachedDepositIds` ; les puces du
   composer sont purgées à l'envoi (comportement SF-169-01 inchangé).
4. Au **rechargement** du fil (`GET /chat`), chaque message porte `files` (venus du transcript
   persistant, SF-169-02) : la bulle rend les mêmes pièces jointes.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Message sans pièce jointe | Aucun bloc de fichiers dans la bulle ; requête sans `attachedDepositIds` (rétrocompat) |
| Puces uniquement en échec / annulées | Non jointes (pas de `path`/`depositId`) : ni dans la bulle, ni dans la requête |
| Envoi = précision (steer) pendant un tour | Purge des puces (SF-169-01) ; aucune réf jointe (les pièces valent pour la 1ʳᵉ demande) |
| `files` absent d'un message d'historique (avant SF-169-02) | Bulle sans bloc de fichiers (champ optionnel) |

---

## Critères d'acceptation

- [ ] `AtelierMessage` porte `files?: DepositedFileRef[]` ; `AtelierChatRequest` porte
      `attachedDepositIds?: string[]` ; `AtelierThreadItem` porte `files?: DepositedFileRef[]`.
- [ ] `DepositedFile` (réponse de dépôt) porte `id` ; `TerminalDepositNotice` porte `depositId?` et
      `size?` (capturés dans `onFilesSelected`).
- [ ] À l'envoi, les réfs des puces réussies (path + depositId) sont **capturées avant la purge** ;
      `attachedDepositIds` part dans le corps de `POST /chat/stream` ; le message local porte `files`.
- [ ] Les fichiers d'un message s'affichent **dans la bulle** (`.terminal-question`, donc dans
      `.terminal-scrollback`) : nom court + taille, style cohérent avec les puces SF-169-01.
- [ ] Au rechargement, `toThreadItem` reporte `message.files` → `item.files` → rendus dans la bulle.
- [ ] **Design system** : jetons `--cg-*` uniquement ; pas de couleur/police hors charte ; lisible en
      thème clair/sombre. Mobile (SF-158) : les puces de la bulle passent à la ligne, sans casser la
      safe-area ni le gutter.
- [ ] Aucun changement backend, migration ou table (SF-169-02 a déjà tout livré côté serveur).

---

## Périmètre

### Hors scope (explicite)

- Tout changement backend (fait en SF-169-02).
- Rendre les pièces jointes cliquables / téléchargeables (le binaire ne repasse pas par le message).
- Attacher des réfs à une **précision** (steer) ou au chemin **managed agent** (`sendExec`) : le lien
  persistant passe par le flux `streamChat` de la première demande (SF-169-02).

---

## Contraintes de validation

| Champ | Obligatoire | Format / Valeurs | Normalisation |
|-------|-------------|------------------|---------------|
| nom affiché | Oui (dérivé de `path`) | dernier segment (`shortName`), `title` = chemin complet | découpe sur `/` |
| taille affichée | Oui | `humanFileSize(size)` (octets → « 2,3 Mo ») | — |

---

## Technique

### Endpoint(s)

Aucun nouveau. `POST /chat/stream` reçoit `attachedDepositIds` (contrat SF-169-02) ; `GET /chat`
rend `files` (SF-169-02).

### Composants Angular

- `core/models/atelier.models.ts` — `DepositedFileRef` (nouveau) ; `files?` sur `AtelierMessage` ;
  `attachedDepositIds?` sur `AtelierChatRequest` ; `id` sur `DepositedFile` ; `depositId?` + `size?`
  sur `TerminalDepositNotice`.
- `atelier/atelier.types.ts` — `files?: DepositedFileRef[]` sur `AtelierThreadItem`.
- `core/services/atelier.service.ts` — `streamChat(..., attachedDepositIds?)` : réfs dans le corps
  quand non vide.
- `atelier/atelier.component.ts` — `onFilesSelected` capture `id`/`size` dans la notice ;
  `dispatchMessage`/`startTurn` capturent les réfs des puces **avant** la purge, posent `item.files`
  et passent `attachedDepositIds` à `streamChat` ; `toThreadItem` reporte `message.files`.
- `atelier/terminal/atelier-terminal.component.*` — rendu de `message.files` **dans la bulle**
  (`.terminal-qbody`) ; helper `fileSizeLabel`.
- `atelier/terminal/atelier-terminal-deposit.component.scss` — variante « dans la bulle » des puces
  (lecture seule, sans croix), jetons `--cg-*`, wrap mobile.

---

## Préoccupations transversales

- [x] **Navigation / rendu du fil** — cochée.

**Composants impactés (liste obligatoire) :**
1. **Rendu du fil** — `atelier-terminal.component.html` (`.terminal-question` dans
   `.terminal-scrollback`) : ajout du bloc `message.files`. Les puces défilent avec le fil.
2. **Composer** — `atelier-terminal.component.html` (`.terminal-attachments`) : inchangé (SF-169-01) ;
   réutilisation des classes `.terminal-attachment` pour la bulle.
3. **Mobile (SF-158)** — `atelier-terminal-deposit.component.scss` : les puces de la bulle en
   `flex-wrap`, safe-area / gutter inchangés.
4. **Mosaïque (lecture seule)** — le fil rendu par le même composant en `readOnly` affiche `files`
   sans croix : cohérent, aucune régression (pas de composer, pas d'envoi).

---

## Plan de test (Karma)

### `atelier-terminal.component.spec`

- [ ] Un `message` USER avec `files` rend des puces **dans la bulle** (`.terminal-question
      .terminal-attachment`) : nom court + taille.
- [ ] Un `message` USER sans `files` ne rend aucun bloc de fichiers dans la bulle.
- [ ] Le rendu de la bulle ne montre **pas** de croix de suppression (lecture, pas composition).

### `atelier.component.spec`

- [ ] À l'envoi avec des puces réussies, `startTurn` pose `item.files` (path + size) sur le message
      local, et `streamChat` reçoit `attachedDepositIds` (les `depositId` des puces).
- [ ] À l'envoi sans pièce jointe, `streamChat` est appelé **sans** `attachedDepositIds`.
- [ ] `onFilesSelected` capture `depositId` et `size` dans la notice depuis la réponse de dépôt.
- [ ] `toThreadItem` reporte `message.files` → `item.files` (rechargement).

### `atelier.service.spec`

- [ ] `streamChat` inclut `attachedDepositIds` dans le corps quand fourni ; l'omet sinon.

### Isolation utilisateur

- [x] Non applicable — frontend seul ; l'isolation `user_id`+`workspace_id` est garantie au backend
      (SF-169-02).

---

## Dépendances

### Subfeatures bloquantes

- `SF-169-01` (puces composer) — Done. `SF-169-02` (backend `attachedDepositIds` + `files` + `id`
  dépôt) — Done (PR #1032, squash `6670d369`).

### Questions ouvertes impactées

- Aucune (`docs/OPEN_QUESTIONS.md`).

---

## Notes et décisions

- **Réutilisation du style SF-169-01** : mêmes classes `.terminal-attachment` pour la cohérence
  visuelle composer ↔ bulle ; variante « dans la bulle » = lecture seule (sans croix).
- **Capture avant purge** : la purge des puces vit dans `dispatchMessage` **avant** `startTurn` ; les
  réfs sont donc capturées en amont et passées en paramètre à `startTurn`.
- **Réfs = id de dépôt** (aligné SF-169-02) ; `size` (octets bruts) est aussi capturé pour le rendu.
