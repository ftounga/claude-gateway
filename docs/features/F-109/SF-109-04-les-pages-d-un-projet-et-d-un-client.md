# Mini-spec — [F-109 / SF-109-04] Les pages d'un projet et d'un client

---

## Identifiant

`F-109 / SF-109-04`

## Feature parente

`F-109` — Les pages : des documents graphiques, comme dans Claude Code
(cadrage validé par le PO : `CADRAGE-F-109-les-pages.md`, §5, §7)

## Statut

`done` — mergée le 2026-09-14 (PR #574)

## Date de création

2026-09-13

## Branche Git

`feat/SF-109-04-onglet-pages`

---

## Objectif

Un onglet **Pages** sur le poste de la Forge et sur le client de la Vigie range les pages publiées là —
vignette, titre, projet, date, version — avec *Ouvrir*, *Renommer*, *Versions précédentes*, *Télécharger*
et *Supprimer* ; clôturer la mission d'un client propose de télécharger ses pages avant de les supprimer.

---

## Comportement attendu

### Cas nominal

1. **Backend** (`/pages`, JWT, isolation `user_id`) :
   - `GET /pages?hostId=&space=` → les pages du compte **à ce lieu**, la plus récemment modifiée d'abord,
     chacune avec son `viewUrl` (ticket) et `workspaceId` ;
   - `GET /pages/{id}?version=N` → comme SF-109-01, `viewUrl` sur la **version N** (404 si absente) ;
   - `GET /pages/{id}/versions` → `[{version, sizeBytes, attachmentCount, createdAt}]`, la plus récente d'abord ;
   - `PATCH /pages/{id}` `{title}` → renomme (mêmes règles que la publication) ;
   - `DELETE /pages/{id}` → efface la page, ses versions et ses objets (204) ;
   - `GET /pages/export?hostId=&space=` → **ZIP** des versions courantes : `{titre-slug}/index.html` et
     `{titre-slug}/{pièce jointe}`, nom `pages-{space}-{date}.zip` ;
   - `DELETE /pages?hostId=&space=` → efface toutes les pages du lieu (204) — la purge de la clôture ;
   - la **suppression du compte** efface les objets des pages (les lignes partent en cascade).
2. **Frontend — `app-host-pages`** (entrées `hostId`, `space`, noms des projets) :
   - grille de cartes (vignette `app-page-frame` inerte, titre, projet d'origine en Forge, « modifiée le … »,
     `v3`), `mat-paginator` (12 par page) ;
   - menu de carte : **Ouvrir** (plein écran, nouvel onglet), **Renommer** (`TextPromptDialog`),
     **Versions précédentes** (dialogue : chaque version avec *Voir* — `/pages/:id?version=N` — et
     *Télécharger*), **Télécharger le fichier HTML** (`/pages/{id}/content?download=true`),
     **Supprimer** (`ConfirmDialog`, `warn`) ;
   - liste vide : « Aucune page ici. Demandez à l'agent du terminal : « fais-en une page ». »
3. **Onglet Pages** : ajouté aux onglets d'un **poste machine** de la Forge (pas au poste « Hébergé », qui
   n'a pas l'outil) et à ceux d'un **client** de la Vigie ; `?onglet=pages`.
4. **Clôture de mission** (dialogue de la Vigie, F-99) : une offre « Ses pages peuvent être gardées :
   téléchargez-les (ZIP) » et une case décochée **« Supprimer aussi ses pages »** ; la purge ne part qu'après
   la clôture confirmée, comme celle du Radar.
5. `/pages/:id?version=N` ouvre la version N.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Page d'un autre compte / inconnue (lecture, versions, renommer, supprimer) | indiscernables | 404 |
| Renommer avec un titre vide ou > 120 | refus avec le motif | 400 |
| `space` invalide | refus | 400 |
| `hostId` manquant sur la liste, l'export ou la purge | refus | 400 |
| Export d'un lieu sans page | ZIP vide valide | 200 |
| Échec réseau à l'écran | SnackBar « … n'a pas pu … », rien n'est retiré de la liste | — |

---

## Critères d'acceptation

- [ ] CA1 — la liste d'un lieu ne contient que les pages du compte à ce `(hostId, space)`, triées par modification.
- [ ] CA2 — renommer change le titre ; titre invalide → 400 ; page d'autrui → 404.
- [ ] CA3 — supprimer efface les lignes **et** les objets ; page d'autrui → 404, rien d'effacé.
- [ ] CA4 — versions : liste décroissante ; `GET /pages/{id}?version=N` rend un ticket de la version N.
- [ ] CA5 — l'export ZIP contient `index.html` de la version courante de chaque page du lieu (et ses pièces jointes), aucune page d'autrui ni d'un autre lieu.
- [ ] CA6 — la purge du lieu n'efface que ce lieu et ce compte.
- [ ] CA7 — la suppression du compte efface les objets des pages.
- [ ] CA8 — onglet Pages présent sur un poste machine (Forge) et un client (Vigie), absent du poste Hébergé.
- [ ] CA9 — la carte montre vignette sandboxée, titre, version ; les cinq gestes appellent le bon service ; supprimer passe par une confirmation.
- [ ] CA10 — clôture : case décochée par défaut ; cochée, la purge des pages part **après** la clôture.

---

## Périmètre

### Hors scope (explicite)

- Le partage → SF-109-05.
- Supprimer une version isolée ; restaurer une version comme courante.
- Pages d'un poste supprimé (restent rattachées à l'ancien `host_id`, visibles par l'export du compte) — non listées.
- Clôture de mission côté Forge : la clôture vit dans la Vigie (F-99 / SF-99-07).

---

## Valeurs initiales

| Champ | Valeur initiale | Règle |
|-------|----------------|-------|
| onglet | Projets (Forge), Radar (Vigie) | Pages s'ouvre par clic ou `?onglet=pages` |
| case « Supprimer aussi ses pages » | décochée | l'effacement n'est jamais présumé |

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format / Valeurs autorisées | Unicité | Normalisation |
|-------|-------------|-------------|----------------------------|---------|---------------|
| `title` (renommer) | Oui | 120 | texte une ligne | Non | `strip`, espaces repliés |
| `hostId` | Oui (liste, export, purge) | — | UUID | — | — |
| `space` | Oui | — | `FORGE`, `VIGIE` | — | majuscules |
| `version` | Non | — | entier ≥ 1 | — | — |

---

## Technique

### Endpoint(s)

| Méthode | URL (sous `/api`) | Auth | Rôle minimum |
|---------|-----|------|-------------|
| GET | `/pages?hostId=&space=` | JWT | propriétaire |
| GET | `/pages/{id}?version=` | JWT | propriétaire |
| GET | `/pages/{id}/versions` | JWT | propriétaire |
| PATCH | `/pages/{id}` | JWT | propriétaire |
| DELETE | `/pages/{id}` | JWT | propriétaire |
| GET | `/pages/export?hostId=&space=` | JWT | propriétaire |
| DELETE | `/pages?hostId=&space=` | JWT | propriétaire |

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| `pages` | SELECT / UPDATE / DELETE | filtre `user_id` (+ `host_id`, `space`) |
| `page_versions` | SELECT / DELETE | filtre `user_id` |

### Migration Liquibase

- [x] Non applicable (index `(user_id, host_id, space)` déjà posé en `098`)

### Composants

- Backend : `PageRepository` (+ requêtes de lieu), `PageService` (list, rename, delete, deletePlace, export, purgeUser, ticket de version), `PageController`, `dto/PageVersionResponse`, `dto/RenamePageRequest`, `AccountService` (purge des objets, injection par mutateur).
- Frontend : `PagesService` (+ list, versions, rename, remove, removePlace, exportPlace, download), `shared/pages/host-pages.component`, `shared/pages/page-versions-dialog.component`, `postes/forge-tabs.ts` + `postes.component.html`, `vigie/vigie-fleet.ts` + `vigie.component.html`, `vigie/close-mission-dialog`, `vigie.component.ts`, `pages/page-viewer.component.ts` (`?version=`).

### Préoccupations transversales

- **Navigation / routing : OUI.** Composants : onglets `?onglet=pages` de `/forge/:hostRef` (`effectiveTab`,
  `tabsFor`) et de `/vigie/:hostRef` (`effectiveVigieTab`) ; `/pages/:id?version=` (route inchangée). Aucun
  guard ni redirection. Non-régression : onglets existants inchangés et dans le même ordre, onglet par
  défaut inchangé.
- **Contexte tenant : OUI.** Composants : `PageService` (toutes les requêtes filtrent `user_id`),
  `AccountService.deleteAccount` (purge par `user_id`).
- **Auth / Principal : non.** **Plans / limites : non** (lire, renommer, supprimer, exporter ses pages ne
  demande aucun droit : un compte qui a résilié garde l'accès à ce qu'il a produit — même doctrine que les
  moments de F-89).

---

## Plan de test

### Tests unitaires

- [ ] `PageServiceTest` (ajouts) — list par lieu, rename (+ invalide), delete (objets), deletePlace, export ZIP (entrées), purgeUser, isolation.
- [ ] `forge-tabs.spec.ts`, `vigie-fleet.spec.ts` (ajouts) — onglet Pages.
- [ ] `host-pages.component.spec.ts` — rendu, vide, gestes, confirmation, erreur.
- [ ] `page-versions-dialog.component.spec.ts` — liste, voir, télécharger.
- [ ] `close-mission-dialog.component.spec.ts` (ajout) — case décochée, résultat `purgePages`.

### Tests d'intégration

- [ ] `PageManagementApiIntegrationTest` — les sept endpoints, 400/404, isolation Bob, ZIP lu.
- [ ] `vigie.component.spec.ts` (ajout) — purge des pages après clôture confirmée seulement.

### Isolation utilisateur

- [x] Applicable — Bob ne liste, ne renomme, ne supprime, n'exporte ni ne purge une page d'Alice (même `hostId`).

---

## Dépendances

### Subfeatures bloquantes

- SF-109-01, SF-109-02, SF-109-03 — mergées.

### Questions ouvertes impactées

- Aucune.

---

## Notes et décisions

- **D1 — « Dans le projet (Forge) »** : l'onglet vit sur le **poste** ouvert (seul écran à onglets de la Forge),
  et chaque carte dit **le projet** d'où la page vient. Un onglet par projet dupliquerait la liste dans
  vingt tuiles.
- **D2 — Aucun droit pour relire ses pages** : le droit ouvre la capacité de **produire** (SF-109-02).
- **D3 — ZIP écrit au fil de l'eau** dans la réponse, page par page (8 Mo au plus en mémoire à la fois) :
  aucune archive entière n'est tenue en mémoire, et aucun traitement lourd asynchrone n'est requis.
