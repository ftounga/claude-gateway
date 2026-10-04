# Mini-spec — [F-173 / SF-173-03] La fiche d'une ressource

## Identifiant

`F-173 / SF-173-03`

## Feature parente

`F-173` — La carte vivante (cadrage `CADRAGE-F-173-la-carte-vivante.md`, D1→D7 validées le 2026-10-04)

## Statut

`done`

## Date de création

2026-10-04

## Branche Git

`feat/SF-173-03-fiche-ressource`

---

## Objectif

Ouvrir, à côté du plan, la **fiche** d'une ressource (niveau 3 de D4) : ce que la carte en sait, pièges en tête, faits datés et sourcés, identifiants copiables, relations cliquables.

---

## Comportement attendu

### Cas nominal

1. La fiche s'ouvre en **panneau latéral** (360 px, sous le plan en dessous de 1 024 px) dès que l'URL désigne une ressource : la ressource **sélectionnée** (`?noeud=<feuille>`), sinon la **plateforme** dont on voit l'intérieur. Vues Plan, Liste ; jamais en vue Fichiers.
2. Elle est lue par `GET /api/governance/hosts/{hostRef}/map/entities/{nodeId}` (contrat **importé de SF-173-01**).
3. En-tête : type, nom (Space Grotesk), environnement, domaine, état, « constaté le … » et « périmé » s'il l'est.
4. **Identifiants** en JetBrains Mono, chacun avec un bouton *Copier* (presse-papiers CDK, confirmation par `MatSnackBar`).
5. **Pièges en tête** (« Pièges (n) », filet rouge `--cg-error`), puis « Ce que la carte en dit (n) » : chaque fait avec son échéance, sa date de constat, « périmé », et sa **source** `fichier § section · Ln` — un clic ouvre le fichier (dialogue existant de la vue Fichiers, texte exact lu sur le poste).
6. **Relations** dites depuis la ressource (« est dans », « est atteint par »…) ; un clic sur la ressource liée y navigue (`?noeud=`).
7. *Fermer* remonte au niveau qui contient la ressource.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Fiche illisible (404 ressource disparue, 5xx) | « La fiche n'a pas pu être lue. » + *Réessayer* ; le plan reste utilisable |
| Ressource sans fait | « Aucun fait rattaché à cette ressource pour l'instant. » |
| Plus de faits que la borne serveur (80) | « 80 faits affichés sur N. » |
| Copie refusée par le navigateur | « La copie a échoué. » |
| Poste hors ligne et clic sur une source | le dialogue existant dit qu'il n'a pas pu lire (inchangé) |

---

## Critères d'acceptation

- [x] La fiche s'ouvre pour une ressource sélectionnée et pour la plateforme ouverte ; jamais en vue Fichiers.
- [x] Pièges en premier, chacun sourcé ; faits datés ; « périmé » écrit.
- [x] Un identifiant se copie en un clic.
- [x] Une relation mène à la ressource liée ; une source ouvre le fichier.
- [x] Fermer remonte d'un niveau.

---

## Contraintes de validation

| Élément | Règle |
|---------|-------|
| Largeur du panneau | 360 px ; pleine largeur < 1 024 px |
| Hauteur | ≤ 640 px, défilement interne |
| Faits affichés | ceux rendus par l'API (≤ 80) |

---

## Périmètre

### Hors scope (explicite)

- Le chemin d'accès (SF-173-05) et « Demander à la Forge » (SF-173-07) : des emplacements de projection (`[mapCardPath]`, `[mapCardActions]`) leur sont réservés.
- Toute écriture.

---

## Technique

### Contrat API

Importé de SF-173-01 (`GET …/map/entities/{nodeId}`), sans modification.

### Composants frontend

- `postes/forge-map/forge-map-card.component.ts|html|scss` (nouveau).
- `postes/forge-map/forge-map.component.*` : `cardNodeId`, `closeCard`, mise en page plan + fiche.

### Préoccupations transversales

| Préoccupation | Cochée | Composants |
|---|---|---|
| Navigation / routing | Oui | `ForgeMapComponent.goTo` / `closeCard` (seul `?noeud=` change, `queryParamsHandling: 'merge'`) ; `PostesComponent.selectTab` et `/forge/:hostRef` inchangés |
| Auth, tenant, plans | Non | — |

---

## Plan de test

- [x] `forge-map-card.component.spec.ts` : lecture et pièges en tête, copie, source → dialogue, relation → navigation, erreur, phrases.
- [x] `forge-map.component.spec.ts` : la fiche s'ouvre pour une feuille sélectionnée et pour la plateforme ouverte, jamais en vue Fichiers ; Fermer remonte.
- [x] `npm run build && npm test` verts.

## Dépendances

SF-173-01, SF-173-02 mergées.

## Notes et décisions

- **Arbitrage (réversible)** : la plateforme ouverte affiche aussi sa fiche — on voit ce qu'elle contient **et** ce qu'on sait d'elle, sans clic de plus.
- **Arbitrage (réversible)** : « lien vers le fichier et la section » = ouverture du fichier exact dans le dialogue existant ; la section est nommée dans la source (le dialogue n'a pas d'ancre).
