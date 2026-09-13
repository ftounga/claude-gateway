# Mini-spec — [F-109 / SF-109-03] L'aperçu dans le terminal

---

## Identifiant

`F-109 / SF-109-03`

## Feature parente

`F-109` — Les pages : des documents graphiques, comme dans Claude Code
(cadrage validé par le PO : `CADRAGE-F-109-les-pages.md`, §3.1, §5 — amendement F-89 tranché le 2026-09-13)

## Statut

`in-review`

## Date de création

2026-09-13

## Branche Git

`feat/SF-109-03-apercu-terminal`

---

## Objectif

Quand l'agent publie une page, le terminal pose un bloc **« Page publiée — titre »** avec une vignette,
*Ouvrir* (panneau à droite du terminal) et *Plein écran* (onglet de l'application), la page s'affichant
toujours dans une `iframe` bac à sable.

---

## Comportement attendu

### Cas nominal

1. **Backend.** Une publication réussie (`page_publish`, SF-109-02) produit un **bloc de page**
   `PageBlock(pageId, title, description, version)` :
   - relayé au fil de l'eau (événement SSE `page`, `{toolUseId, page}`) via
     `AtelierProgressListener.onPage` ;
   - écrit dans la **transcription du tour** (`AtelierTurnReport.Block.page`) — il survit au rechargement
     et au bornage des sorties, comme la carte de F-89.
2. **Frontend — le bloc** (`app-page-block`), dans **tous** les terminaux (projet, poste, Teams, tuile en
   lecture seule), à la place où il est arrivé :
   - titre « Page publiée — {titre} », version, description ;
   - **vignette** : l'`iframe` réduite (échelle ¼, `sandbox="allow-scripts allow-popups"`, `loading="lazy"`,
     inerte : `pointer-events: none`, `tabindex="-1"`, `aria-hidden`) servie par le `viewUrl` de
     `GET /pages/{id}` ;
   - boutons **Ouvrir** et **Plein écran** (masqués en lecture seule : on n'agit pas depuis une tuile —
     charte §13 ; la vignette reste).
3. **Ouvrir** : un **panneau à droite du terminal** (`app-page-panel`) — en-tête (titre, *Plein écran*,
   *Fermer*), `iframe` pleine hauteur ; **Échap** ferme ; à moins de 900 px il occupe toute la largeur du
   terminal. Un second *Ouvrir* remplace la page affichée.
4. **Plein écran** : ouvre `/pages/:id` dans un **nouvel onglet** du navigateur — le tour vit dans le flux
   du terminal, le quitter le tuerait. L'écran `/pages/:id` (`PageViewerComponent`, sous la coquille et
   l'`authGuard`) montre un bandeau (titre, version, retour) et l'`iframe` sur toute la hauteur restante.
5. **Toute `iframe` de page** porte exactement `sandbox="allow-scripts allow-popups"` —
   **jamais** `allow-same-origin`, `allow-forms`, `allow-top-navigation` —, `referrerpolicy="no-referrer"`,
   et un `title` accessible. Un seul composant la produit (`app-page-frame`) : aucune autre `iframe` de page
   ne peut dériver.
6. **Amendement à la règle de F-89** (charte §15 et nouvelle §18) : *les sorties de commande restent
   textuelles* ; le **bloc de page** est un document rendu par l'agent, admis dans tous les terminaux.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| `GET /pages/{id}` échoue (page supprimée, d'autrui) | le bloc reste, sa vignette dit « Aperçu indisponible » ; *Ouvrir* affiche la même phrase dans le panneau | 404 |
| Ticket expiré pendant que le panneau est ouvert | *Ouvrir* redemande un ticket à chaque ouverture | — |
| `/pages/:id` d'une page inconnue | message « Cette page est introuvable » + retour | 404 |
| Événement `page` sans `page` | ignoré (pas de bloc creux) | — |

---

## Critères d'acceptation

- [ ] CA1 — un appel `page_publish` réussi émet l'événement `page` et le bloc de transcription porte `page` ; un appel refusé n'émet rien.
- [ ] CA2 — le bornage d'une transcription conserve `page`.
- [ ] CA3 — le bloc « Page publiée — titre » s'affiche dans un terminal de projet **et** dans un terminal Teams, au fil de l'eau et après rechargement.
- [ ] CA4 — la vignette et les `iframe` de page ont `sandbox="allow-scripts allow-popups"`, et aucune ne contient `allow-same-origin`, `allow-forms` ou `allow-top-navigation` (test sur le DOM rendu).
- [ ] CA5 — *Ouvrir* affiche le panneau avec l'`iframe` sur le `viewUrl` ; *Fermer* et Échap le ferment.
- [ ] CA6 — *Plein écran* ouvre `/pages/{id}` dans un nouvel onglet ; `/pages/:id` affiche la page, ou « introuvable ».
- [ ] CA7 — en lecture seule, le bloc est visible sans boutons.
- [ ] CA8 — `DESIGN_SYSTEM.md` : §15 amendée, §18 « La page publiée » ; aucune couleur nouvelle.

---

## Périmètre

### Hors scope (explicite)

- L'onglet Pages, renommer, supprimer, versions, télécharger → SF-109-04.
- Le partage → SF-109-05.
- Un rendu serveur de la vignette (aucun navigateur sans tête).
- Les tours des projets hébergés (Managed Agents) : pas d'outil, donc pas de bloc.

---

## Valeurs initiales

| Champ | Valeur initiale | Règle |
|-------|----------------|-------|
| `Block.page` | `null` | tout bloc antérieur reste inchangé |
| panneau | fermé | ouvert par *Ouvrir*, état d'écran (jamais une adresse) |

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format / Valeurs autorisées | Unicité | Normalisation |
|-------|-------------|-------------|----------------------------|---------|---------------|
| `page.pageId` | Oui | 36 | UUID | — | — |
| `page.title` | Oui | 120 | déjà validé par SF-109-01 | — | — |
| `page.version` | Oui | — | ≥ 1 | — | — |
| `viewUrl` | Oui | — | commence par `/api/p/` (sinon l'`iframe` n'est pas posée) | — | — |

---

## Technique

### Endpoint(s)

Aucun nouveau (`GET /pages/{id}` de SF-109-01).

### Tables impactées

Aucune (la transcription vit dans `atelier_messages.terminal_json`, colonne existante).

### Migration Liquibase

- [x] Non applicable

### Composants

- Backend : `pages/PageBlock`, `AtelierTurnReport.Block` (+`page`), `AtelierProgressListener.onPage`,
  `AtelierChatController` (SSE `page`), `AtelierChatService` (bloc de page du tour).
- Frontend : `core/models/pages.models.ts`, `core/services/pages.service.ts`, `shared/pages/page-frame`,
  `atelier/terminal/page-block`, `atelier/terminal/page-panel`, `pages/page-viewer` (route `/pages/:id`),
  `atelier.service.ts` (événement `page`), `atelier.component.ts` (blocs de page du tour),
  `atelier-terminal.component.*` (bloc + panneau), `app.routes.ts`.

### Préoccupations transversales

- **Navigation / routing : OUI.** Composants : `app.routes.ts` — route `pages/:id` ajoutée **sous** la
  coquille authentifiée (`authGuard` du parent) ; préfixe `pages` disjoint de toutes les routes
  existantes (`forge`, `vigie`, `atelier`, `p` à venir en SF-109-05) ; `app.routes.spec.ts` vérifie
  l'ordre et la garde. Aucun guard modifié, aucune redirection ajoutée.
- **Auth / Principal : non** (appel JWT existant). **Contexte tenant : non** (lecture filtrée en
  SF-109-01). **Plans / limites : non.**

---

## Plan de test

### Tests unitaires

- [ ] `AtelierTurnReportTest` (ajout) — `page` survit au bornage et à la sérialisation.
- [ ] `page-frame.component.spec.ts` — attribut `sandbox` exact, pas d'`iframe` sans `viewUrl` `/api/p/`.
- [ ] `page-block.component.spec.ts` — titre, vignette, boutons ; lecture seule sans boutons ; erreur → « Aperçu indisponible ».
- [ ] `page-panel.component.spec.ts` — ouverture, Fermer, Échap.
- [ ] `page-viewer.component.spec.ts` — page chargée, page introuvable.
- [ ] `atelier.service.spec.ts` (ajout) — événement `page` → `onPage`.

### Tests d'intégration

- [ ] `AtelierChatServicePageToolTest` (ajout) — succès → `onPage` + bloc de transcription ; refus → rien.
- [ ] `terminal-page-block.spec.ts` — le terminal rend le bloc en projet et en Teams, ouvre le panneau, et **aucune** `iframe` rendue ne porte un jeton interdit.

### Isolation utilisateur

- [x] Non applicable — aucune donnée nouvelle lue ; la page est relue par `GET /pages/{id}`, isolé en SF-109-01.

---

## Dépendances

### Subfeatures bloquantes

- SF-109-01 (mergée), SF-109-02 (mergée).

### Questions ouvertes impactées

- Aucune. Amendement F-89 tranché par le PO le 2026-09-13.

---

## Notes et décisions

- **D1 — Une seule fabrique d'`iframe`** (`app-page-frame`) : la politique §3.1 côté écran tient en un
  endroit, testé ; les vignettes, le panneau et le plein écran l'emploient.
- **D2 — Plein écran dans un nouvel onglet** : naviguer dans le même onglet quitterait le terminal, et
  « le tour vit dans le flux ».
- **D3 — Le bloc de page n'est pas une carte** : il ne passe pas par `cardOf` (règle Teams) ; il est admis
  partout, conformément à l'amendement.
