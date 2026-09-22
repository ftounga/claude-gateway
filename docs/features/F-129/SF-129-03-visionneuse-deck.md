# Mini-spec — F-129 / SF-129-03 — Visionneuse de deck (lisible entièrement)

## Identifiant

`F-129 / SF-129-03`

## Feature parente

`F-129` — Produire des présentations PPTX et les lire entièrement dans l'app

## Statut

`ready`

## Date de création

2026-09-22

## Branche Git

`feat/SF-129-03-visionneuse-deck`

---

## Objectif

> Rendre une présentation **lisible entièrement dans l'app** : une image par slide, une visionneuse
> (slide en grand + miniatures de toutes les slides + navigation clavier + plein écran).

---

## Comportement attendu

### Cas nominal — où tourne la conversion (DÉCISION D'INFRA)

**La conversion `.pptx` → images tourne dans le SANDBOX / sur le terminal, pas sur le cluster.** Le
cluster `legalcase-shared` est à capacité (incident récent) : **aucun** pod LibreOffice permanent n'y
est ajouté. L'agent, qui a déjà produit le `.pptx` (skill `pptx`, SF-129-01), le **rend en images**
au même endroit — dans le sandbox il peut installer/lancer LibreOffice
(`soffice --headless --convert-to pdf` puis `pdftoppm -png`), sur un poste si l'outil y est. Il envoie
ensuite les images avec le `.pptx` via `presentation_publish` (paramètre `slides` : chemins des PNG,
dans l'ordre). La gateway les lit (patron `read_file_bytes`), les range et pose `slide_count`.

La visionneuse in-app affiche : la **slide courante en grand**, un **rail de miniatures** de toutes les
slides, une **navigation** (flèches ‹ ›, clavier ← →, clic sur une miniature), le **plein écran**
(overlay + Échap), et le bouton **Télécharger le .pptx** (SF-129-02) toujours présent.

### Bornes

- Nombre de slides plafonné (`app.presentations.max-slides`, défaut **100**) — au-delà : refus nommé.
- Taille d'une image plafonnée (`app.presentations.max-slide-bytes`, défaut **5 Mo**) — au-delà : refus.
- Format des images : **PNG** (sortie standard de LibreOffice/pdftoppm). Vidéo pleine : hors sujet.

### Cas d'erreur

| Situation | Comportement attendu | Code |
|-----------|---------------------|------|
| Plus de `max-slides` images | Résultat d'outil en **erreur** nommée, rien n'est rangé | tool error |
| Une image > `max-slide-bytes` | Erreur nommée | tool error |
| Une image d'extension ≠ png | Erreur nommée | tool error |
| Une image illisible sur la machine | Erreur nommée (patron `PageToolExecutor`) | tool error |
| `GET …/slides/{n}` d'un autre utilisateur | **404** | 404 |
| `GET …/slides/{n}` hors bornes (0, > slideCount, non rendu) | **404** | 404 |

---

## Critères d'acceptation

- [ ] CA1 — `presentation_publish` accepte un paramètre `slides` (chemins PNG ordonnés) ; la gateway
      lit chaque image **là où vit le projet** (poste/hébergé), la range et pose `slide_count`.
- [ ] CA2 — Les bornes sont appliquées : refus nommé au-delà de `max-slides`, au-delà de
      `max-slide-bytes`, ou pour une extension non-PNG.
- [ ] CA3 — `GET /api/presentations/{id}/slides/{n}` (1-based) renvoie l'image PNG, scellée par
      `user_id` ; hors bornes ou non rendu → **404** ; autre utilisateur → **404**.
- [ ] CA4 — Republier le `.pptx` (SF-129-02) **efface** les slides et remet `slide_count` à `null`
      (le rendu est à refaire) — déjà assuré par `PresentationService.publish` (SF-129-02).
- [ ] CA5 — **Visionneuse** (composant Angular) : slide courante en grand, **miniatures de toutes les
      slides**, navigation (boutons + clavier ← →), plein écran (overlay + Échap), bouton Télécharger.
- [ ] CA6 — Une présentation **sans rendu** (`slide_count` null) affiche un état « aperçu pas encore
      disponible » et **le téléchargement reste possible** (dégradation gracieuse).
- [ ] CA7 — Le rendu tourne dans le **sandbox/terminal** : **aucun** nouveau composant cluster.

---

## Périmètre

### Hors scope (explicite)

- Édition du deck dans l'app (on produit et on lit).
- Animations/transitions (python-pptx/rendu ne les portent pas).
- `docx`/`xlsx` → SF-129-05.

---

## Technique

### Endpoint(s)

| Méthode | URL | Auth | Notes |
|---------|-----|------|-------|
| GET | `/api/presentations/{id}/slides/{index}` | Oui | image PNG (1-based), scellée `user_id` |

Outil agent : `presentation_publish` étendu avec `slides` (array de chemins).

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| `presentations` | UPDATE `slide_count` | **Aucune migration** : `slide_count` existe déjà (migration 123, dormant). Les images vivent dans le stockage objet (`presentations/{userId}/{id}/slides/NNNN.png`). |

### Migration Liquibase

- [ ] Non applicable (colonne `slide_count` déjà créée en SF-129-02).

### Composants Angular

- `DeckViewerComponent` (`app-deck-viewer`) — overlay plein écran : slide en grand, rail de
  miniatures, navigation clavier/boutons, Télécharger, Échap pour fermer.
- `PresentationsPanelComponent` (SF-129-02) — ajout d'un geste **Ouvrir/Aperçu** qui lance le viewer.

### Runner / composant cluster (drapeau)

- Mise à jour runner : **NON** (lecture via `read_file_bytes`, outil runner **existant**).
- **Nouveau composant cluster : NON** — conversion dans le **sandbox/terminal** (décision d'infra).
- Migration : **NON**.

---

## Préoccupations transversales (analyse d'impact)

- **Navigation / routing** : la visionneuse est un **overlay** (pas de route), lancé depuis le panneau
  Présentations (Forge + Vigie). Aucun chemin de navigation existant modifié, aucun guard. Composants
  impactés : `presentations-panel.component.ts` (geste Ouvrir), nouveau `deck-viewer.component.ts`.
- **Plans / limites** : rendu + stockage des images consomment de l'espace ; bornes `max-slides` /
  `max-slide-bytes` (déjà dans `PresentationLimits`, SF-129-02). Aucun nouveau gate.
- **Auth / Principal** : aucun nouveau type d'auth. Endpoint slides scellé par `CurrentUser` ; outil
  agent utilise le `userId` du tour.

---

## Plan de test

### Tests unitaires

- [ ] `PresentationToolExecutor` — `slides` lues et rangées (mock hébergé), `slide_count` posé ;
      au-delà de `max-slides` → erreur ; image trop grande → erreur ; extension non-png → erreur.
- [ ] `PresentationService.attachSlides` — range les images (efface d'abord), pose `slide_count`,
      isolation `findByIdAndUserId`.

### Tests d'intégration

- [ ] `GET /api/presentations/{id}/slides/1` → 200 image/png après rendu.
- [ ] `GET …/slides/{n}` hors bornes → 404 ; autre utilisateur → 404 (isolation).

### Isolation utilisateur

- [x] Applicable — un utilisateur B n'accède jamais aux slides de A → 404.

### Frontend

- [ ] `DeckViewerComponent` — rend N miniatures + la slide courante ; ← → changent de slide ; clic
      miniature sélectionne ; Échap ferme ; slideCount null → message « aperçu pas encore disponible ».
- [ ] `PresentationService.slide(id, index)` — URL + `responseType: 'blob'` (couvert SF-129-02, revu).

---

## Dépendances

### Subfeatures bloquantes

- SF-129-02 (artefact + capture + `slide_count`) — **Done**.

### Questions ouvertes impactées

- Aucune.

---

## Notes et décisions

- **Amendement au cadrage D2, porté par l'instruction PO du lot** : le cadrage prévoyait un **worker
  LibreOffice serveur** ; le cluster étant à capacité, la conversion se fait **dans le sandbox /
  terminal** (l'agent rend les images là où il a produit le `.pptx`), capturées comme les images de
  réunion (F-128). **Aucun composant cluster permanent ajouté.** Si un jour un worker serveur devient
  nécessaire, il devra être **scale-to-zero et strictement borné**, et validé par le PO pour la
  capacité (drapeau) — hors de ce lot.
- Réutilise le patron « capturer des images → stocker → servir → afficher » de F-128 (deck d'images) et
  la lecture par path de F-109.
