# Mini-spec — [F-142 / SF-142-22] Le diagramme s'affiche inline (embarqué en data: URI)

## Identifiant

`F-142 / SF-142-22`

## Feature parente

`F-142` — Des diagrammes dans les livrables (schémas d'architecture rendus par la gateway)

## Statut

`ready`

## Date de création

2026-09-30

## Branche Git

`feat/SF-142-22-diagramme-inline-data-uri`

---

## Objectif

> En une phrase : faire que le diagramme d'une page publiée **s'affiche inline** dans la page — et
> plus seulement via le lien « ouvrir en grand » — en embarquant, **au moment de servir la page**,
> les `<img src="…">` qui pointent vers une pièce jointe image en **`data:` URI** (le seul schéma
> d'image autorisé par la CSP d'une page en origine opaque).

---

## Cause racine (preuve)

Une page F-109 est servie sous la politique de `PageContentPolicy` :

```
content-security-policy: sandbox allow-scripts allow-popups; … img-src data: blob: 'self'; connect-src 'none'; …
```

Le bac à sable **n'a pas** `allow-same-origin` (interdit à raison — `sandbox + allow-scripts +
allow-same-origin` = évasion du bac à sable). La page est donc en **origine opaque** : `'self'` dans
`img-src` ne correspond à **aucune** origine. Une image en pièce jointe, référencée en relatif
(`<img src="agenor-avant.svg">` → `'self'`), est donc **bloquée inline** — PNG hérité comme SVG neuf,
à l'identique. Le lien « ouvrir en grand » (`<a target="_blank">`) marche, lui, car c'est une
**navigation top-level** (autorisée par `allow-popups`), non soumise à `img-src`. `blob:` est
inutilisable : `connect-src 'none'` empêche de fetcher la pièce jointe pour en fabriquer un blob.
**Seul `data:` passe.**

---

## Comportement attendu

### Cas nominal

Au moment de **servir** le HTML d'une page (`PageService.html(...)`, la même couche qui injecte déjà
le runtime Mermaid — SF-142-01), un nouveau transform `PageImageInliner` :

1. parcourt les balises `<img …>` du HTML ;
2. pour chaque `<img src="NAME.ext">` où `NAME.ext` est un **nom plat de pièce jointe valide**
   (`PageAttachments.isValidName`) d'une **extension image** (svg, png, jpg, jpeg, gif, webp) qui
   existe **dans cette version** de la page, remplace la valeur de `src` par un
   `data:<type>;base64,<octets>` construit depuis les octets de la pièce jointe ;
3. laisse **tout le reste inchangé** — en particulier le lien `<a href="NAME.ext" target="_blank">`
   « ouvrir en grand », qui reste relatif et continue de fonctionner (navigation top-level).

Le **stockage reste pristine** (`<img src="NAME.ext">` + la pièce jointe rangée **une seule fois**) ;
l'embarquement se fait **à la volée**, à chaque lecture. La pièce jointe n'est **jamais** dupliquée.

### Cas d'erreur / limites

| Situation | Comportement attendu |
|-----------|---------------------|
| `<img>` dont `src` est déjà `data:` / `http(s):` / `blob:` / un chemin (`/…`, `../…`) | Laissé **inchangé** (idempotent ; `isValidName` rejette `:` et `/`) |
| `<img src="NAME.ext">` sans pièce jointe correspondante dans la version | Laissé inchangé (aucune invention) |
| `<img>` pointant une pièce jointe **non-image** (css/js/json/txt/md/csv) | Laissé inchangé (seul `image/*` est embarqué) |
| Page **sans** `<img>` embarquable | Rendue **octet pour octet identique** (même garantie que le runtime Mermaid) |
| Pièce jointe volumineuse (SVG ~75–350 Ko) | Embarquée telle quelle **en sortie** ; **aucun impact `PageLimits`** (voir §Contraintes) |

---

## Critères d'acceptation

- [ ] CA1 — `PageContentPolicy` expose une règle logique testable qui établit qu'une image en
      **`data:` est autorisée inline** alors qu'une image en **pièce jointe relative (`'self'`) est
      bloquée** (page en origine opaque). La CSP servie est **inchangée** (aucune touche à
      `allow-same-origin` ni à `img-src`).
- [ ] CA2 — Le HTML **servi** d'une page portant `<img src="diagram.svg">` + `<a href="diagram.svg"
      target="_blank">…</a>` contient le diagramme **en `data:image/svg+xml;base64,…`** (plus en `src`
      de fichier nu) **et conserve** le lien `href="diagram.svg"` « ouvrir en grand ».
- [ ] CA3 — Le motif **hérité** est réparé : une page PNG (`<img src="x.png">`) déjà stockée est
      servie avec le PNG embarqué en `data:image/png;base64,…` sans republication.
- [ ] CA4 — Idempotence / non-régression : un `<img src="data:…">` reste inchangé ; une page sans
      `<img>` embarquable est servie **octet pour octet identique**.
- [ ] CA5 — Compatibilité : les diagrammes Mermaid (`<pre class="mermaid">`), les images ordinaires
      et les pièces jointes non-image ne régressent pas ; l'export ZIP (fichiers côte à côte, ouverts
      en local) **garde les `src` relatifs** (non embarqués — un fichier local n'est pas en origine
      opaque).
- [ ] CA6 — Isolation `user_id` : l'embarquement lit les pièces jointes via
      `store.attachment(userId, pageId, version, name)` — jamais d'accès hors du compte propriétaire.

---

## Périmètre

### Hors scope (explicite)

- Toucher la CSP / le bac à sable (`allow-same-origin` reste **interdit**).
- Changer le contrat de l'outil `render_diagram` ou la doctrine `DiagramToolCatalog` (correction
  **sans dépendance modèle** : le fix est déterministe côté gateway).
- Embarquer autre chose que des images (les fonts/css/js `'self'` chargent déjà — `script-src`/
  `style-src`/`font-src` portent `'self'` ; seul `img-src` souffre de l'origine opaque ici).
- Embarquer à la **publication** dans le HTML **stocké** (rejeté — voir §Notes : doublerait le
  stockage et taperait dans `PageLimits`).
- Toute modification frontend Angular (la page servie est un document autonome dans une iframe).

---

## Contraintes de validation (taille / PageLimits)

Le choix **serve-time** neutralise le risque de taille :

| Grandeur | Où | Impact |
|----------|-----|--------|
| HTML **stocké** | `PageStore` / `maxPageBytes` (8 Mo) | Inchangé : `<img src="x.svg">` (tiny) |
| Pièce jointe stockée | idem, comptée une fois | 4 SVG cloud ≈ 4×350 Ko = ~1,4 Mo ≪ 8 Mo ; ≤ 20 pièces |
| HTML **servi** (inliné) | réponse HTTP, **hors** `PageLimits` | +33 % base64 → ~1–2 Mo, **non plafonné** |

`PageLimits` **n'est pas modifié** : la borne 8 Mo s'applique au **stockage**, où l'image reste
rangée **une seule fois** en pièce jointe. L'embarquement gonfle seulement la **réponse servie**, qui
n'est soumise à aucune borne de stockage. (Un embarquement à la publication, lui, aurait stocké
l'image deux fois — en `data:` dans le HTML **et** en pièce jointe pour le lien — doublant la
consommation et risquant de dépasser 8 Mo : argument décisif pour le serve-time.)

---

## Technique

### Endpoint(s)

Aucun nouvel endpoint. Le transform s'applique dans le service, en amont des routes déjà servantes :
`GET /p/{token}/` (`PagePublicController`) et l'écran authentifié (`PageController.html`). La route
pièce jointe `GET /p/{token}/{name}` (« ouvrir en grand ») est **inchangée**.

### Tables impactées

Aucune. Aucune migration Liquibase.

### Composants backend

| Composant | Opération |
|-----------|-----------|
| `PageImageInliner` (nouveau, `pages/`) | Transform serve-time : `<img src="attachment">` → `data:` URI |
| `PageService.html(...)` | Applique `PageImageInliner.inline(...)` puis `PageMermaidRuntime.render(...)` |
| `PageContentPolicy` | Ajoute la règle logique `isOpaqueOrigin()` / `allowsInlineImage(src)` (preuve CSP) ; CSP inchangée |

### Migration Liquibase

- [x] Non applicable

### Composants Angular

Aucun.

---

## Plan de test

### Tests unitaires

- [ ] `PageContentPolicyTest` — `data:` autorisé inline, pièce jointe relative (`'self'`) bloquée,
      `blob:`/`http(s):` bloqués ; `isOpaqueOrigin()` vrai ; CSP toujours `img-src data: blob: 'self'`
      et **sans** `allow-same-origin` (CA1).
- [ ] `PageImageInlinerTest` — `<img src="diagram.svg">` → `data:image/svg+xml;base64,…` ; le lien
      `<a href="diagram.svg" target="_blank">` reste relatif (CA2) ; PNG hérité (CA3) ; idempotence
      sur `data:` (CA4) ; pièce jointe non-image ignorée ; page sans `<img>` embarquable **byte-
      identique** (CA4) ; pièce jointe absente → inchangé.

### Tests d'intégration

- [ ] `PageServiceTest` (ou dédié) — `service.html(...)` d'une page publiée avec pièce jointe SVG rend
      le HTML **servi** avec le `data:` **et** conserve le lien « ouvrir en grand » (CA2, bout en bout
      via `PageStore` en mémoire).

### Isolation utilisateur

- [ ] Applicable — l'embarquement passe par `store.attachment(userId, pageId, version, name)`, tracé
      par les tests existants d'isolation de `PageService` ; un test vérifie qu'aucune pièce jointe
      d'un autre compte n'est atteignable (le résolveur est borné au `userId` du propriétaire) (CA6).

---

## Préoccupations transversales

| Préoccupation | Concernée ? | Composants impactés |
|--------------|-------------|---------------------|
| Auth / Principal | Non | La route publique et l'écran authentifié restent inchangés ; aucune nouvelle entrée. |
| Contexte tenant | Non (lecture bornée) | `PageService.html` résout déjà `userId`/`pageId`/`version` ; l'embarquement lit via `store.attachment(userId,…)`, même isolation qu'aujourd'hui. |
| Plans / limites | Non | `PageLimits` **inchangé** (serve-time, voir §Contraintes). |
| Navigation / routing | Non | Aucune route ajoutée/modifiée ; le lien « ouvrir en grand » (`/p/{token}/{name}`) est intact. |

---

## Dépendances

### Subfeatures bloquantes

- SF-142-18 (SVG cloud auto-contenu, icônes en `data:` à l'intérieur) — `done` : garantit qu'un SVG
  embarqué en `data:` s'affiche net, sans ressource externe.
- SF-109-01/02/06 (pages, pièces jointes image) — `done`.

### Questions ouvertes impactées

- Aucune (`docs/OPEN_QUESTIONS.md` non impacté).

---

## Notes et décisions

**Mécanisme retenu : embarquement à la volée (serve-time), pas à la publication.** Deux options
étaient possibles :

1. **Serve-time (retenu)** — inliner dans `PageService.html(...)`, exactement là où le runtime Mermaid
   est déjà injecté (SF-142-01). Déterministe, **sans dépendance modèle**, s'applique à **toute** page
   et **répare le motif hérité** sans republication. Le stockage reste pristine (image rangée **une
   fois**), donc **aucun impact `PageLimits`**. C'est la voie Gateway-First : la correction est un fait
   du serveur, pas une consigne au modèle.
2. **Publish-time** (inliner dans le HTML stocké) — rejeté : stockerait l'image **deux fois** (en
   `data:` dans le HTML **et** en pièce jointe pour le lien), doublant la consommation et risquant de
   dépasser la borne 8 Mo, et ne réparerait pas les pages déjà publiées.
3. **Via `render_diagram` / doctrine** (le modèle embarque lui-même le `data:`) — rejeté : dépendance
   modèle, non déterministe, ne répare pas l'hérité, et alourdit le HTML stocké.

`DiagramToolCatalog` et le renderer ne sont **pas touchés** → **redéploiement backend seul**.
