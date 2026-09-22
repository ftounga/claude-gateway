# Mini-spec — F-129 / SF-129-02 — Capturer la présentation dans l'app + téléchargement

## Identifiant

`F-129 / SF-129-02`

## Feature parente

`F-129` — Produire des présentations PPTX et les lire entièrement dans l'app

## Statut

`ready`

## Date de création

2026-09-22

## Branche Git

`feat/SF-129-02-capture-et-telechargement`

---

## Objectif

> Le `.pptx` produit par l'agent sur le terminal devient un **artefact « présentation »** rattaché
> au poste/projet, listé dans un onglet, et **téléchargeable** (le vrai fichier).

---

## Comportement attendu

### Cas nominal

1. L'agent a produit un `.pptx` sur le terminal (skill SF-129-01). Il appelle le nouvel outil
   `presentation_publish` avec un `title` et le `path` du fichier sur la machine.
2. La gateway lit les octets **là où vit le projet** — poste (`RunnerToolGateway.readFileBytes`, par
   tranches, patron `PageToolExecutor`) ou projet hébergé (`WorkspaceService.readFileBytes`) —, valide
   (extension `.pptx`, en-tête ZIP `PK`, taille ≤ borne), range dans le **stockage objet**
   (`WorkspaceStorage`, préfixe `presentations/…`), et crée une ligne `presentations`
   (isolation `user_id` + `host_id` + `space`).
3. L'utilisateur voit la présentation dans l'onglet **Présentations** (Forge côté poste, Vigie côté
   client), avec un bouton **Télécharger** qui rend le **vrai `.pptx`**.
4. Republier avec le même `presentation_id` **remplace** le fichier (nouvel `updated_at`).

L'outil est donné **sous la même garde que `page_publish`** (droit d'espace du terminal — Forge/Vigie,
ouvert d'office à l'ADMIN). Hors garde : pas d'outil, pas de guide.

### Cas d'erreur

| Situation | Comportement attendu | Code |
|-----------|---------------------|------|
| `path` absent / vide | Résultat d'outil en **erreur** nommée (le tour continue) | — (tool error) |
| Fichier introuvable / illisible sur la machine | Erreur nommée (patron `PageToolExecutor`) | — |
| Extension ≠ `.pptx` ou en-tête non-ZIP | Erreur nommée « ce n'est pas un .pptx » | — |
| Fichier trop volumineux (> borne) | Erreur nommée avec la borne | — |
| `presentation_id` inconnu | Erreur nommée « identifiant inconnu » | — |
| `GET /presentations/{id}/pptx` d'un autre utilisateur | **404** (jamais 403 qui révélerait l'existence) | 404 |
| `GET`/download sans authentification | 401 (sécurité globale) | 401 |
| Outil appelé hors garde d'espace | Erreur nommée « non ouvert dans ce terminal » | — |

---

## Critères d'acceptation

- [ ] CA1 — `presentation_publish` (nom d'outil) est donné à l'agent **sous la garde d'espace** du
      terminal (patron `PageToolCatalog`) ; un guide court rejoint la consigne sous la même garde.
- [ ] CA2 — Un `.pptx` produit sur le terminal est lu, validé et rangé ; une ligne `presentations`
      est créée avec `user_id` (du tour), `space`, `host_id`, `workspace_id`, `title`, `pptx_key`,
      `pptx_bytes`.
- [ ] CA3 — `GET /api/presentations?hostId=&space=` liste les présentations du couple
      `user_id`+`host_id`+`space`, triées par date décroissante.
- [ ] CA4 — `GET /api/presentations/{id}/pptx` renvoie le **vrai `.pptx`** (content-type
      `application/vnd.openxmlformats-officedocument.presentationml.presentation`,
      `Content-Disposition: attachment`), scellé par `user_id`.
- [ ] CA5 — **Isolation** : un utilisateur B ne peut ni lister, ni télécharger, ni voir la
      présentation d'un utilisateur A → **404** (test cross-user).
- [ ] CA6 — Republier avec `presentation_id` remplace le fichier et l'entrée (pas de doublon).
- [ ] CA7 — Validation refusée nommée pour non-`.pptx`, trop volumineux, `path` absent.
- [ ] CA8 — **Frontend** : un onglet **Présentations** (Vigie + Forge) liste les présentations et
      offre le bouton **Télécharger** (blob via `ExportService.triggerDownload`, JWT porté).

---

## Périmètre

### Hors scope (explicite)

- Le **rendu par slides** (images) et la **visionneuse** → **SF-129-03** (le tab n'affiche qu'une
  liste + téléchargement en 02 ; l'aperçu vient en 03).
- Le partage par lien (pas dans le cadrage v1 des présentations).
- Les versions multiples conservées (on **remplace** ; pas d'historique de versions comme les pages).
- `docx`/`xlsx` → SF-129-05.

---

## Valeurs initiales

| Champ | Valeur initiale | Règle |
|-------|----------------|-------|
| user_id | utilisateur du tour | jamais un paramètre client (contexte de sécurité) |
| space | FORGE ou VIGIE | déduit du terminal (`isTeamsTerminal()` → VIGIE) |
| host_id / workspace_id | du terminal | peuvent être `null` (projet sans poste, patron `Page`) |
| slide_count | `null` | rempli par SF-129-03 |

## Contraintes de validation

| Champ | Obligatoire | Longueur / Taille max | Format |
|-------|-------------|----------------------|--------|
| title | Oui | 120 | non vide après trim |
| description | Non | 300 | texte libre |
| pptx (fichier) | Oui | `app.presentations.max-pptx-bytes` (défaut **25 Mo**) | extension `.pptx` + en-tête ZIP `PK\x03\x04` |

---

## Technique

### Endpoint(s)

| Méthode | URL | Auth | Rôle |
|---------|-----|------|------|
| GET | `/api/presentations?hostId=&space=` | Oui (JWT) | tout utilisateur (ses données) |
| GET | `/api/presentations/{id}` | Oui | propriétaire |
| GET | `/api/presentations/{id}/pptx` | Oui | propriétaire |
| DELETE | `/api/presentations/{id}` | Oui | propriétaire |

Outil agent (pas un endpoint REST) : `presentation_publish` (`title`, `description`, `path`,
`presentation_id`).

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| `presentations` | CREATE (migration) | `user_id`, `space`, `host_id`, `workspace_id`, `title`, `description`, `pptx_key`, `pptx_bytes`, `slide_count` (nullable, SF-129-03), `created_at`, `updated_at` ; index sur `user_id`. |

### Migration Liquibase

- [x] Oui — `123-presentations.xml` (numéro re-vérifié après rebase ; renommé si collision).

### Composants Angular

- `PresentationService` (`/api/presentations`), `PresentationsPanelComponent`
  (`app-presentations-panel`, liste + Télécharger), onglet `presentations` dans `vigie-fleet.ts` /
  `forge-tabs.ts` + panneaux Vigie/Forge.

### Runner / composant cluster (drapeau)

- Mise à jour runner : **NON** (lecture via `read_file_bytes`, outil runner **existant**).
- Nouveau composant cluster : **NON**.
- Migration : **OUI** (123).

---

## Préoccupations transversales (analyse d'impact)

- **Navigation / routing** : ajout d'un **onglet** (query-param `?onglet=presentations`), pas de
  route. Composants impactés : `frontend/src/app/vigie/vigie-fleet.ts` (+ `.component.ts/.html`),
  `frontend/src/app/postes/forge-tabs.ts` (+ `postes.component.ts/.html`). Les chemins existants
  restent inchangés (onglet inconnu → repli `radar`/défaut, logique existante). Pas de guard modifié.
- **Auth / Principal** : aucun nouveau type d'auth. Les endpoints REST utilisent `CurrentUser`
  existant. L'outil agent utilise le `userId` du tour (jamais un paramètre client). AtelierChatService
  reçoit deux dépendances de plus (catalogue + exécuteur), défaut `null`/`none()` dans les surcharges
  courtes — **3 tests** appellent le constructeur canonique et sont mis à jour.
- **Plans / limites** : la garde d'espace (`SpaceEntitlementService`) — **réutilisée telle quelle**,
  aucun nouveau gate. La borne de taille est une propriété (`PresentationLimits`).

---

## Plan de test

### Tests unitaires

- [ ] `PresentationService` — création (nominal), remplacement par id, isolation `findByIdAndUserId`,
      refus non-`.pptx`, refus trop volumineux, refus title vide.
- [ ] `PresentationToolExecutor` — path absent → erreur ; lecture poste (mock runner) → rangé ;
      lecture hébergé (mock WorkspaceService) → rangé ; non-`.pptx` → erreur.
- [ ] `PresentationToolCatalog` — outil donné sous garde, vide hors garde.

### Tests d'intégration

- [ ] `GET /api/presentations` → liste du couple, tri.
- [ ] `GET /api/presentations/{id}/pptx` → 200 + bon content-type + Content-Disposition.
- [ ] `GET /api/presentations/{id}/pptx` (autre utilisateur) → **404** (isolation).
- [ ] `DELETE /api/presentations/{id}` → 204 + fichier objet supprimé ; autre utilisateur → 404.

### Isolation utilisateur

- [x] Applicable — un utilisateur B n'accède jamais aux présentations de A (liste, get, download,
      delete) → 404. Test explicite.

### Frontend

- [ ] `PresentationService` (HttpTestingController) — URLs + `responseType`/`observe`.
- [ ] `PresentationsPanelComponent` — rend la liste, bouton Télécharger appelle le service.
- [ ] `vigie-fleet.spec` — l'onglet `presentations` est résolu.

---

## Dépendances

### Subfeatures bloquantes

- SF-129-01 (skill) — **Done**. Le tool de 02 est indépendant de la skill (l'agent peut produire
  autrement) mais la chaîne de valeur la suppose.

### Questions ouvertes impactées

- Aucune.

---

## Notes et décisions

- **Patron F-109 réutilisé au maximum** : `PageToolExecutor` (lecture par path, poste/hébergé),
  `PageToolCatalog` (garde d'espace + guide), `PageStore`/`WorkspaceStorage` (stockage objet),
  `Page` (entité rattachée user/space/host/workspace). La présentation est un artefact **plus simple**
  (pas de versions, pas de partage, pas de rendu HTML sandboxé — c'est un fichier binaire).
- **Gateway-First** : le backend lit/valide/range/sert. Aucun moteur IA. `AIProvider` non requis
  (production de fichier, pas d'IA).
- **Divergence cadrage D2** (rendu images) traitée en SF-129-03 (sandbox, pas de pod cluster).
