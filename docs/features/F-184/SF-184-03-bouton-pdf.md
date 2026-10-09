# Mini-spec — [F-184 / SF-184-03] Bouton « Télécharger en PDF »

---

## Identifiant

`F-184 / SF-184-03`

## Feature parente

`F-184` — Les pages en PDF, même charte

## Statut

`in-review`

## Date de création

2026-10-10

## Branche Git

`feat/SF-184-03-bouton-pdf`

---

## Objectif

Pouvoir télécharger le PDF d'une page depuis le panneau d'aperçu du terminal, le plein écran et l'onglet Pages.

---

## Comportement attendu

### Cas nominal

1. **Panneau d'aperçu** (`app-page-panel`) : bouton-icône `picture_as_pdf`, infobulle « Télécharger en PDF », version courante.
2. **Plein écran** (`app-page-viewer`) : bouton « PDF » à côté de « Partager ». Il télécharge **la version affichée** (`?version=N` respecté).
3. **Onglet Pages** (`app-host-pages`) : entrée de menu « Télécharger en PDF », sous « Télécharger le fichier HTML ».
4. Un service partagé, `PagePdfDownloadService`, appelle `GET /api/pages/{id}/pdf`. Le fichier est téléchargé sous le nom donné par le serveur (`<slug>-v<N>.pdf`).
5. Pendant l'impression (quelques secondes), le bouton est **désactivé** et le message « Préparation du PDF… » s'affiche. Un second clic ne relance rien.
6. Si `X-Cg-Missing-Resources` n'est pas vide, une notification d'information s'affiche : « PDF téléchargé. Certains éléments externes n'ont pas pu être inclus. »

### Cas d'erreur

| Situation | Message (snack-error) |
|-----------|----------------------|
| 503 (moteur absent ou muet) | « Le PDF n'a pas pu être produit pour le moment. Réessayez dans un instant. » |
| 422 (refus du moteur) | « Cette page n'a pas pu être imprimée en PDF (trop lourde ou trop complexe). » |
| 404 | « Page introuvable : elle a peut-être été supprimée. » |
| Autre | « Le PDF n'a pas pu être téléchargé. » |

---

## Critères d'acceptation

- [ ] CA1 — Les trois points d'accès déclenchent le téléchargement ; le plein écran transmet la version affichée.
- [ ] CA2 — Pendant l'impression, le bouton est désactivé et ne relance pas la requête.
- [ ] CA3 — Ressources manquantes → snack d'information ; erreurs 503/422/404/autre → messages ci-dessus.
- [ ] CA4 — Charte : Angular Material, jetons `--cg-*` ; pas d'`alert`. Cible tactile ≥ 44 px dans le menu (`mat-menu-item`).

---

## Périmètre

### Hors scope

- L'outil agent (SF-184-04).
- La liste des versions (dialogue « Versions précédentes ») : le plein écran d'une version précédente suffit.

---

## Valeurs initiales

Sans objet.

## Contraintes de validation

Sans objet.

---

## Technique

### Endpoint(s)

Consommé : `GET /api/pages/{id}/pdf?version=N` (SF-184-02).

### Tables impactées

Aucune.

### Composants Angular

- `PagesService.pdf(pageId, version?)`.
- `PagePdfDownloadService` (nouveau, `shared/pages/`) : appel, état « en cours » par page, messages.
- `PagePanelComponent`, `PageViewerComponent`, `HostPagesComponent` : boutons.

### Préoccupations transversales

Aucune cochée : pas de nouvelle route ni de guard.

---

## Plan de test

### Tests unitaires (Karma)

- `page-pdf-download.service.spec` : succès (téléchargement, nom du serveur) ; manquants → snack info ; 503/422/404/0 → messages ; double appel pendant l'impression ignoré.
- `pages.service.spec` : URL et paramètre `version`.
- Specs des trois composants : présence du bouton et appel avec la bonne version.

### Tests d'intégration

Suite frontend complète + build.

### Isolation workspace

Portée par la route (SF-184-02) ; non applicable côté écran.

---

## Dépendances

- SF-184-02 : done (#1112).
