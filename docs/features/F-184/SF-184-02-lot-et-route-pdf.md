# Mini-spec — [F-184 / SF-184-02] La gateway assemble le lot et sert le PDF d'une page

---

## Identifiant

`F-184 / SF-184-02`

## Feature parente

`F-184` — Les pages en PDF, même charte

## Statut

`in-review`

## Date de création

2026-10-10

## Branche Git

`feat/SF-184-02-lot-et-route-pdf`

---

## Objectif

`GET /api/pages/{id}/pdf` renvoie le PDF A4 clair d'une version d'une page **du compte**. La gateway assemble le lot (HTML servi, pièces jointes, Mermaid local, ressources externes sur liste fermée) et le fait imprimer par le service de rendu (SF-184-01).

---

## Comportement attendu

### Cas nominal

1. `GET /api/pages/{id}/pdf?version=N` (version courante si absente). Authentifié.
2. Le HTML **servi** est lu par `PageService.html(userId, …)` : propriétaire vérifié, images inlinées, runtime Mermaid injecté.
3. Le lot comprend :
   - toutes les **pièces jointes** de la version, à `https://page.cg.local/<nom>` ;
   - la **bibliothèque Mermaid** du classpath, si le HTML la référence ;
   - les **ressources externes** référencées par `<script src>`, `<link href>` ou `@import`, **uniquement** sur `cdnjs.cloudflare.com`, `cdn.jsdelivr.net` et `fonts.googleapis.com`, plus les fichiers `fonts.gstatic.com` cités par une feuille Google Fonts (sous-ensembles `latin` et `latin-ext` seulement).
4. Récupération externe : HTTPS seulement, **sans suivre les redirections**, 10 s et 5 Mo par ressource, 25 Mo et 60 ressources par lot, cache LRU de 64 Mo en mémoire. Un échec de récupération ne bloque pas le PDF : la ressource manque, simplement.
5. Le lot est envoyé au service de rendu (`POST {app.diagrams.base-url}/pdf`, délai de 60 s).
6. Réponse `200 application/pdf` : `Content-Disposition: attachment; filename="<slug>-v<N>.pdf"` et `Cache-Control: private, no-store`. `X-Cg-Missing-Resources` est relayé s'il est présent.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Page inexistante ou d'un autre compte | `not_found` | 404 |
| Version non conservée | `not_found` | 404 |
| Non authentifié | Refus | 401 |
| Service de rendu non configuré, muet ou en dépassement de délai | « Le PDF n'a pas pu être produit pour le moment, réessayez. » | 503 |
| Service de rendu refusant le lot (400/413/422) | Raison du moteur relayée | 422 |
| Ressource externe hors liste, ou redirection | Non récupérée, listée manquante par le moteur | 200 |

---

## Critères d'acceptation

- [ ] CA1 — Nominal : 200 `application/pdf`, nom `<slug>-v<N>.pdf`, octets du moteur relayés tels quels.
- [ ] CA2 — **Isolation** : la page d'un autre compte renvoie 404, et aucun appel n'est fait au moteur.
- [ ] CA3 — Le lot contient les pièces jointes de la version et Mermaid quand la page le référence.
- [ ] CA4 — Seuls les quatre hôtes de la liste sont récupérés, en HTTPS, sans redirection. `http://`, `169.254.169.254`, `localhost` et tout autre hôte ne sont jamais appelés.
- [ ] CA5 — Feuille Google Fonts : seuls les fichiers `latin` / `latin-ext` sur `fonts.gstatic.com` sont joints.
- [ ] CA6 — Bornes : 5 Mo par ressource, 25 Mo et 60 ressources au total, au-delà la ressource est ignorée. Le cache évite un second téléchargement.
- [ ] CA7 — Moteur absent ou en échec → 503 ; refus du moteur → 422 avec sa raison ; `X-Cg-Missing-Resources` relayé.

---

## Périmètre

### Hors scope (explicite)

- Bouton (SF-184-03), outil agent (SF-184-04).
- PDF depuis un lien de partage public.
- Exécution de JavaScript côté gateway (on ne suit que les références **statiques** du HTML : un script qui en charge un autre dynamiquement verra cet autre listé manquant).

---

## Valeurs initiales

| Champ | Valeur | Règle |
|-------|--------|-------|
| `app.pages.pdf.timeout` | 60 s | délai de l'appel au moteur |
| hôtes autorisés | cdnjs, jsDelivr, fonts.googleapis.com, fonts.gstatic.com | liste fermée, codée en dur (pas de configuration) |

---

## Contraintes de validation

| Champ | Obligatoire | Format | Notes |
|-------|-------------|--------|-------|
| `id` | Oui | UUID | — |
| `version` | Non | entier > 0 | courante si absent ou 0 |

---

## Technique

### Endpoint(s)

| Méthode | URL | Auth | Rôle minimum |
|---------|-----|------|-------------|
| GET | `/api/pages/{id}/pdf` | Oui | utilisateur (propriétaire de la page) |

### Tables impactées

Aucune écriture. Lecture : `pages` / `page_versions` (déjà filtrées par `user_id` dans `PageService`). Pas de migration.

### Composants backend (`fr.claudegateway.pages.pdf`)

- `PagePdfService` : assemble le lot et appelle le moteur.
- `PageLotScanner` (pur) : extrait les références externes et filtre les feuilles Google Fonts.
- `ExternalResourceFetcher` : liste fermée, HTTPS, sans redirection, bornes, cache LRU.
- `PagePdfRenderer` / `HttpPagePdfRenderer` : client du moteur, avec `PagePdfUnavailableException` (503) et `PagePdfRejectedException` (422).
- `PageService.attachments(userId, pageId, version)` : pièces jointes bornées au propriétaire.
- `PageController` : route `GET /{id}/pdf` ; `PageExceptionHandler` : 503 et 422.

### Préoccupations transversales

Aucune cochée : pas de nouveau mode d'authentification ni de résolution du tenant. La route réutilise `currentUser.requireId()`.

### Note « traitement lourd »

L'impression est **déléguée** au service de rendu et bornée à 60 s. C'est le même modèle que le rendu de diagrammes (F-142) et l'aperçu de slides (F-129) : un aller-retour borné, pas un travail long comme Textract ou les embeddings.

---

## Plan de test

### Tests unitaires

- `PageLotScannerTest` : script, link et `@import` extraits ; hôte hors liste ignoré ; `http://` ignoré ; feuille Google Fonts filtrée sur latin et latin-ext.
- `ExternalResourceFetcherTest` (client HTTP simulé) : hôte hors liste jamais appelé ; redirection refusée ; ressource de plus de 5 Mo ignorée ; cache réutilisé.
- `PagePdfServiceTest` : lot avec pièces jointes et Mermaid, bornes à 60 ressources.
- `HttpPagePdfRendererTest` : 200 relayé avec les manquants ; 413 → refus ; 500 ou IO → indisponible ; non configuré → indisponible.

### Tests d'intégration

- `PagePdfApiIntegrationTest` (moteur et récupération externe simulés) : 200 avec en-têtes ; page d'un autre compte → 404 sans appel au moteur ; moteur indisponible → 503 ; refus → 422.

### Isolation workspace

- [x] Applicable : l'utilisateur B ne peut pas obtenir le PDF d'une page de A (404).

---

## Dépendances

- SF-184-01 : done (#1109).
- Questions ouvertes : aucune.

---

## Notes et décisions

- Les redirections ne sont **pas** suivies : une URL de CDN épinglée répond directement, et suivre une redirection pourrait mener hors de la liste.
- User-Agent d'un Chrome récent pour `fonts.googleapis.com` : Google sert alors du woff2, et les sous-ensembles sont commentés (`/* latin */`).
