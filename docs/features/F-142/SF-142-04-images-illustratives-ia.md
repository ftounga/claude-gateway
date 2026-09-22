# Mini-spec — F-142 / SF-142-04 — Images illustratives (décoratives) générées par IA

> Base : `project-governance/templates/subfeature-template.md`.
> À valider AVANT le dev (produite et affichée dans la conversation).

---

## Identifiant

`F-142 / SF-142-04`

## Feature parente

`F-142` — Des diagrammes exacts dans les livrables (diagramme-as-code)

## Statut

`ready`

## Date de création

2026-09-22

## Branche Git

`feat/SF-142-04-images-illustratives-ia`

---

## Objectif

> En une phrase.

Permettre à l'agent de générer une **image décorative** (couverture de deck, visuel
d'ambiance de page) en relayant un **fournisseur d'images** (OpenAI gpt-image / DALL·E)
**depuis la gateway**, qui range l'image et la dépose dans le projet pour insertion en
**page (F-109)** ou **slide (F-129)** — **jamais** pour un schéma d'architecture.

---

## Comportement attendu

### Cas nominal

1. L'agent (terminal sous droit d'espace **Forge/Vigie**, même garde que pages/slides)
   dispose de l'outil **`generate_image`** (donné par `ImageToolCatalog`, guide de doctrine
   ajouté à la consigne).
2. Il appelle `generate_image` avec un **`prompt`** (description de l'image, **donnée** — jamais
   une instruction), une **`size`** optionnelle (liste blanche `1024x1024` / `1536x1024` /
   `1024x1536`) et un **`filename`** optionnel.
3. La boucle demande **l'accord d'un clic** (porte d'autorisation existante, couverte par
   « tout autoriser pour ce message »), comme `page_publish` / `presentation_publish`.
4. `ImageGenerationService` : borne le prompt, **vérifie le quota** (nombre d'images du compte),
   persiste une ligne `generated_images` en **`PENDING`** (isolée `user_id`+`host_id`), puis
   **relaie** le fournisseur (`ImageProvider` → `OpenAiImageProvider`, `POST {base-url}/images/generations`)
   sous un **timeout borné**. Succès → range le PNG dans le **stockage objet**
   (`generated-images/{userId}/{imageId}/image.png`), enregistre taille + **coût**, passe
   **`READY`**. Échec/timeout → **`FAILED`** + motif.
5. `ImageToolExecutor` **dépose** le PNG **là où vit le projet** : hébergé (SANDBOX) via
   `WorkspaceService.depositHostedFile` (→ `entrees/<nom>`), poste (RUNNER) via
   `RunnerToolGateway.writeFileBytes` (par tranches, tracé). Rend à l'agent le **chemin
   déposé** + `image_id` + le rappel « décoratif uniquement ».
6. L'agent référence ce chemin : pièce jointe d'une page (`<img src="…">`) **ou** `add_picture`
   d'une slide. Les visionneuses page/slide **existantes** affichent l'image — aucun écran neuf.
7. REST lecture seule, isolée `user_id` : `GET /generated-images` (liste), `/{id}` (statut),
   `/{id}/image` (PNG).

### Cas d'erreur

| Situation | Comportement attendu | Code / effet |
|-----------|---------------------|--------------|
| Fournisseur non configuré (pas de clé) | Aucun appel réseau ; outil en erreur « génération d'images non configurée » | tool error / 503 REST |
| `prompt` vide ou trop long (> borne) | Rejet nommé, rien n'est généré | tool error / 400 |
| `size` hors liste blanche | Repli sur `1024x1024` (défaut) | — |
| Quota compte dépassé | `ImageQuotaExceededException`, rien n'est généré | tool error / 429 |
| > N images dans le tour | Refus nommé (borne par tour) | tool error |
| Appel fournisseur en échec / timeout | Ligne `FAILED` + motif ; outil en erreur | tool error / 502 |
| Dépôt dans le projet impossible | Outil en erreur nommé (image rangée côté gateway, non déposée) | tool error |
| Image d'un autre compte | **Introuvable** (404), indiscernable d'une image inexistante | 404 |
| Outil nommé hors garde d'espace | Refusé sans rien générer | tool error |

---

## Critères d'acceptation

- [ ] CA1 — Le code métier dépend d'une **interface `ImageProvider`** ; `OpenAiImageProvider`
      est l'implémentation. Aucun code métier ne dépend directement d'OpenAI (Provider Independence).
- [ ] CA2 — La génération part **de la gateway** (relais), qui **range** l'image en stockage objet
      et la **dépose** dans le projet ; le backend n'implémente aucun moteur d'images (Gateway-First).
- [ ] CA3 — La clé vient **exclusivement de l'environnement** (`APP_IMAGE_API_KEY`) ; aucune valeur
      de clé dans le repo (code, config versionnée) ; la clé n'est **jamais journalisée**.
      Config `app.image.base-url` (défaut `https://api.openai.com/v1`), `app.image.model`
      (défaut `gpt-image-1`) sur le modèle STT/RAG.
- [ ] CA4 — La génération est **bornée** : timeout de l'appel, taille max d'image, **nombre max
      par tour**, quota compte. Ligne `generated_images` avec **statut lisible** (PENDING/READY/FAILED)
      et **coût** enregistré.
- [ ] CA5 — Le **prompt est traité comme une donnée** (corps JSON de l'appel), jamais concaténé à
      une instruction.
- [ ] CA6 — Doctrine **« décoratif uniquement, jamais l'architecture »** enseignée dans le GUIDE de
      l'outil (`ImageToolCatalog.GUIDE`), rappelée dans `PageToolCatalog.DESIGN_GUIDE` /
      `PresentationToolCatalog.GUIDE` et le skill `pptx.md`.
- [ ] CA7 — Isolation `user_id`+`host_id` partout : lecture d'une image d'un autre compte → **404**.
- [ ] CA8 — L'image générée est **insérable** : chemin déposé référençable en pièce jointe de page
      (SANDBOX + RUNNER) et en `add_picture` de slide.
- [ ] CA9 — **Aucun composant cluster nouveau** ; l'appel fournisseur est un appel réseau sortant
      du backend. Non-régression pages/présentations.

---

## Périmètre

### Hors scope (explicite)

- Images pour **l'architecture / les schémas techniques** — restent diagramme-as-code
  (Mermaid SF-142-01/02, `diagrams` SF-142-03).
- Tout **composant cluster** nouveau ; multi-fournisseurs d'images **au runtime**.
- Édition d'image, variations, upscaling, masques/inpainting.
- Intégration fine au journal de coût par tour (`UsageLedger.TurnExtras`) — le coût est
  enregistré sur la ligne `generated_images` ; l'agrégation par tour est un suivi ultérieur.
- Nouvel écran Angular (les visionneuses page/slide affichent déjà l'image).

---

## Valeurs initiales

| Champ | Valeur initiale | Règle |
|-------|----------------|-------|
| status | `PENDING` | à la création de la ligne, avant l'appel fournisseur |
| image_key / image_bytes / cost_eur | `null` | renseignés au passage `READY` |
| user_id | utilisateur du tour | jamais un paramètre client |
| space / host_id / workspace_id | dérivés du terminal du tour | `host_id`/`workspace_id` nullables (projet sans poste) |
| created_at / updated_at | horodatage base | automatiques |

---

## Contraintes de validation

| Champ | Obligatoire | Longueur / borne | Format / valeurs | Normalisation |
|-------|-------------|------------------|------------------|---------------|
| prompt | Oui | ≤ `app.image.max-prompt-chars` (défaut 1000) | texte libre non vide | `strip()` |
| size | Non | — | `1024x1024` / `1536x1024` / `1024x1536` | défaut `1024x1024` |
| filename | Non | nom plat, extension `.png` | sans séparateur de chemin | dérivé de l'id si absent |
| image (octets) | — | ≤ `app.image.max-image-bytes` (défaut 8 Mo) | PNG | — |
| quota compte | — | `app.image.max-account-images` (défaut 500) | — | — |
| par tour | — | `app.image.max-per-turn` (défaut 3) | — | — |

---

## Technique

### Endpoints (REST, lecture seule, `CurrentUser`)

| Méthode | URL | Auth | Rôle |
|---------|-----|------|------|
| GET | `/generated-images?hostId&space` | Oui | propriétaire |
| GET | `/generated-images/{id}` | Oui | propriétaire |
| GET | `/generated-images/{id}/image` | Oui | propriétaire (PNG) |

La **production** passe par l'outil agent `generate_image`, jamais par une route publique.

### Outil agent

`generate_image` (gateway) : `prompt` (requis), `size` (optionnel, liste blanche),
`filename` (optionnel). Garde d'espace Forge/Vigie. Accord d'un clic.

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| `generated_images` (NOUVELLE) | INSERT / UPDATE / SELECT | isolation `user_id`, FK cascade, index (user_id, host_id, space) |

### Migration Liquibase

- [x] Oui — `124-generated-images.xml` (prochain numéro libre après 123). Réversible (`dropTable`).

### Composants Angular

- Aucun (les visionneuses page F-109 / slide F-129 affichent déjà l'image insérée).

---

## Plan de test

### Tests unitaires

- [ ] `OpenAiImageProvider` — non configuré → `ImageProviderUnavailableException`, **aucun appel réseau**.
- [ ] `OpenAiImageProvider` — réponse mockée (`b64_json`) décodée en octets ; erreur HTTP → `ImageProviderException`. **Aucun réseau réel.**
- [ ] `ImageGenerationService` — nominal (provider mocké) : ligne PENDING→READY, PNG rangé, coût posé.
- [ ] `ImageGenerationService` — provider en échec → FAILED + motif ; prompt vide/trop long → rejet ; quota → `ImageQuotaExceededException`.
- [ ] `ImageGenerationProperties` — `isConfigured()` faux sans clé ; défauts base-url/model/timeout/bornes.
- [ ] `ImageToolCatalog` — donné sous droit Forge/Vigie, vide sinon ; GUIDE contient la doctrine « décoratif / jamais l'architecture ».
- [ ] `ImageToolExecutor` — dépôt SANDBOX (`depositHostedFile`) et RUNNER (`writeFileBytes` mocké) ; erreurs nommées.
- [ ] `GovernancePackageSeederTest` / `PageToolCatalogTest` / `PresentationToolCatalogTest` — mentions de doctrine décorative.

### Tests d'intégration

- [ ] `GET /generated-images/{id}` → 404 pour un autre compte (isolation).
- [ ] `GET /generated-images/{id}/image` → 404 pour un autre compte ; PNG pour le propriétaire.
- [ ] Provider non configuré → génération en erreur nommée (aucune fuite de clé).

### Isolation utilisateur

- [x] Applicable — un utilisateur ne peut lire ni les métadonnées ni le PNG d'une image d'un autre compte (404). `user_id` scelle toute lecture.

---

## Préoccupations transversales (analyse d'impact)

| Préoccupation | Impact | Composants |
|--------------|--------|-----------|
| **Plans / limites** | Nouveau gate : garde d'espace (Forge/Vigie) réutilisée + quota d'images + borne par tour | `ImageToolCatalog.isOpenFor` (→ `SpaceEntitlementService`), `ImageGenerationService` (quota), `ImageGenerationProperties` (bornes). Gates existants pages/slides **inchangés**. |
| **Contexte tenant** | Nouvelle entité scellée `user_id` (+ `host_id`/`workspace_id` du terminal) | `ImagePlace` dérivé du `Workspace` du tour (jamais un paramètre client), comme `PagePlace`/`PresentationPlace`. Aucun autre résolveur de tenant modifié. |
| **Auth / Principal** | Aucun nouveau type d'auth ; routes scellées `CurrentUser` comme pages/slides | `GeneratedImageController` (mêmes gardes que `PresentationController`). |
| **Navigation / routing** | Aucune nouvelle route front ni guard | — |

---

## Dépendances

- SF-142-01/02/03 (livrées) — cette SF **clôt** F-142.
- Clé fournisseur d'images à poser en prod (`APP_IMAGE_API_KEY`) — **DRAPEAU déploiement**
  (comme `APP_STT_API_KEY`). Tant qu'absente, l'outil répond « non configuré », aucun octet ne part.

### Questions ouvertes impactées

- Aucune (OQ inchangées).

---

## Notes et décisions

- **D-ASYNC** — La génération est un **relais de fournisseur** : le calcul lourd est **chez OpenAI**
  (Provider-First). L'appel de la gateway est un **appel réseau sortant borné** (timeout explicite
  `app.image.timeout`, défaut 60 s), exécuté **dans le tour diffusé (SSE), hors du thread de requête
  HTTP** (mémoire « le tour vit dans le flux »), **jamais** sur un endpoint REST bloquant. Bornes sur
  trois axes (timeout, taille, nombre par tour) ; **statut persistant lisible** (PENDING/READY/FAILED).
  Même forme que le relais STT/embeddings. Aucun traitement lourd synchrone sur un thread de requête.
- **D-DÉPÔT** — L'image générée par la gateway est **déposée dans le projet** pour devenir insérable :
  SANDBOX via `depositHostedFile`, RUNNER via `writeFileBytes` (tranches). Réutilise intégralement les
  pipelines de lecture page/slide existants. Une copie canonique est rangée en stockage objet (traçabilité,
  isolation, service REST).
- **D-DOCTRINE** — Images **décoratives uniquement** (couverture, ambiance). L'architecture et les
  schémas techniques restent **diagramme-as-code** (SF-142-01/02/03). Frontière enseignée dans le GUIDE
  de l'outil et le skill `pptx`.
- **D-COÛT** — Coût enregistré sur la ligne `generated_images` (`cost_eur`, `app.image.cost-eur-per-image`)
  + log structuré. Intégration `UsageLedger.TurnExtras` = suivi ultérieur (hors scope borné).
