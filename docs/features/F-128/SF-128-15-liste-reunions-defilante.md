# Mini-spec — [F-128 / SF-128-15] La liste des réunions capturées devient défilante bornée

## Identifiant

`F-128 / SF-128-15`

## Feature parente

`F-128` — Capturer et exploiter une réunion Teams

## Statut

`ready`

## Date de création

2026-09-19

## Branche Git

`feat/SF-128-15-liste-reunions-defilante`

---

## Objectif

> En une phrase : que fait cette subfeature ?

Rendre la liste des **réunions capturées** (panneau « Réunions » de la Vigie, SF-128-01) **défilante bornée** (hauteur max + `overflow-y:auto`), pour qu'elle ne pousse plus la page à l'infini quand un poste accumule beaucoup de réunions.

---

## Comportement attendu

### Cas nominal

1. Le panneau « Réunions » affiche les captures en cours (le cas échéant) puis la section « Réunions capturées ».
2. La liste des réunions passées est contenue dans un **conteneur à hauteur maximale** avec **barre de défilement interne** (`overflow-y:auto`) : au-delà de N réunions, la liste défile à l'intérieur au lieu d'étirer la page.
3. Chaque réunion reste **cliquable** et mène à son écran de détail (`routerLink` inchangé).
4. Quand il y a peu de réunions (hauteur inférieure au plafond), aucune barre de défilement n'apparaît et le rendu est identique à avant.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Aucune réunion | L'état vide existant (`.meetings__empty`) s'affiche ; pas de conteneur défilant. |
| Lecture en échec | Le message d'erreur existant (`.meetings__error`) s'affiche ; comportement inchangé. |

---

## Critères d'acceptation

- [ ] La liste des réunions capturées est dans un conteneur à **hauteur max** + `overflow-y:auto` (défilement interne).
- [ ] Au-delà du plafond, la page ne s'étire plus : le défilement est interne à la liste.
- [ ] Chaque réunion reste cliquable et mène au détail (`routerLink` inchangé).
- [ ] Aucun changement fonctionnel des captures en cours (indicateur, pause/arrêt) ni de « Rejoindre & capturer ».
- [ ] Aucune couleur hors charte ; jetons `--cg-*` ; espacements multiples de 4px.
- [ ] `ng build` vert (budgets respectés) ; specs réunions existantes vertes.

---

## Périmètre

### Hors scope (explicite)

- Aucune modification backend, runner, endpoint, modèle ou migration (frontend pur).
- Pas de pagination serveur ni de virtualisation : simple conteneur défilant borné côté CSS.
- Pas de changement à l'écran de détail de réunion ni au flux de capture.
- Le Journal du runner (accordéon) est traité par SF-132-06.

---

## Contraintes de validation

| Champ | Obligatoire | Format / Valeurs autorisées |
|-------|-------------|----------------------------|
| Conteneur liste passée | Oui | `max-height` bornée + `overflow-y: auto`, jetons `--cg-*` |

---

## Technique

### Endpoint(s)

Aucun (frontend pur — consomme `TeamsMeetingService.list`, inchangé).

### Tables impactées

Aucune.

### Migration Liquibase

- [ ] Non applicable

### Composants Angular

- `MeetingCapturePanelComponent` (`frontend/src/app/vigie/meeting-capture/meeting-capture-panel.component.ts`) — template inline : la liste `.meetings__past` des `past-row` est enveloppée dans un conteneur défilant `.meetings__past-list` ; styles inline ajoutés (max-height + overflow-y:auto).

---

## Plan de test

### Tests unitaires (composant, Karma/Jasmine)

- [ ] `MeetingCapturePanelComponent` — quand des réunions passées existent, le conteneur `.meetings__past-list` est présent et contient les lignes `.past-row`.
- [ ] Une réunion passée porte le `routerLink` vers son détail (accès au détail préservé).
- [ ] Non-régression : les tests existants SF-128-01 (lecture, indicateur capture en cours, « Rejoindre & capturer », arrêt, erreur de lecture) restent verts.

### Tests d'intégration

- Non applicable (composant frontend isolé ; aucun nouvel appel HTTP).

### Isolation workspace / `user_id`

- [ ] Non applicable — aucune donnée nouvelle ni nouvel accès ; l'isolation `user_id`+`host_id` reste garantie côté endpoints existants (SF-128-01/10), non modifiés.

---

## Préoccupations transversales

- **Auth / Principal** : non concernée.
- **Contexte tenant** : non concernée (endpoints inchangés).
- **Plans / limites** : non concernée.
- **Navigation / routing** : le `routerLink` de chaque réunion vers `/vigie/{hostId}/reunions/{id}` est **inchangé** ; aucune route ajoutée/modifiée. Chemin de navigation existant préservé (vérifié par test).

---

## Dépendances

### Subfeatures bloquantes

- `SF-128-01` — statut : done (panneau « Réunions » existant, réutilisé).

### Questions ouvertes impactées

- Aucune.

---

## Notes et décisions

- **Choix** : bornage CSS (max-height + overflow-y:auto) sur la liste des réunions **passées** — c'est elle qui croît sans fin ; les captures en cours restent peu nombreuses et pleinement visibles.
- Même logique de densité que SF-132-06 (accordéon du Journal) : les sections de la Vigie ne font plus grandir la page sans fin.
