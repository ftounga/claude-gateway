# Mini-spec — [F-109 / SF-109-06] Les pages depuis un projet hébergé, et leurs images

---

## Identifiant

`F-109 / SF-109-06`

## Feature parente

`F-109` — Les pages : des documents graphiques, comme dans Claude Code
(cadrage validé par le PO : `CADRAGE-F-109-les-pages.md` ; reliquat cadré : `RELIQUATS-2026-09-14.md` §B)

## Statut

`in-progress`

## Date de création

2026-09-14

## Branche Git

`feat/SF-109-06-pages-hebergees-et-images`

---

## Objectif

Ouvrir `page_publish` aux **projets hébergés** (cible `SANDBOX`, bac à sable Managed Agents) et accepter
des **images lues sur la machine** comme pièces jointes d'une page (types d'image, taille bornée), servies
avec la **même politique de sécurité** que le reste d'une page.

---

## Comportement attendu

### Cas nominal

1. **La garde ouvre aussi les projets hébergés.** `PageToolCatalog.isOpenFor` ne demande plus que le
   terminal s'exécute sur un poste : il suffit d'avoir le **droit de l'espace du terminal** (Forge, ou
   Vigie pour un terminal Teams — `SpaceEntitlementService`, ADMIN d'office). L'outil et le guide sont
   donnés en cible `RUNNER` **comme** en cible `SANDBOX`.
2. **Les lectures suivent la cible du terminal**, sans que l'agent ait à le savoir :
   - cible `RUNNER` : le fichier `path`, les pièces **texte** et les **images** sont lus **sur le poste**
     par le runner et tracés dans son journal — le texte par `read_file`, les images en binaire par
     `read_file_bytes` (par tranches, comme F-110 / SF-110-03) ;
   - cible `SANDBOX` : les mêmes lectures passent par le **stockage objet** du projet
     (`WorkspaceService.readFile` pour le texte, `WorkspaceService.readFileBytes` pour les images),
     toujours sous l'isolation `user_id` (`requireOwned`).
3. **Les images sont des pièces jointes comme les autres**, une fois lues : rangées par
   `PageService.publish` (SF-109-01) dans la version, servies par les routes de lecture existantes
   (`/pages/{id}/content` et `/p/{token}/{name}`) avec `PageContentPolicy` — l'**origine opaque**, le
   `nosniff`, la CSP `sandbox` et `connect-src 'none'` s'appliquent à l'image comme au HTML. Le HTML de
   la page les référence par leur nom relatif (`<img src="capture-1.png">`).
4. **Types d'image acceptés depuis la machine** : `png`, `jpg`, `jpeg`, `gif`, `webp`. Le `svg` reste lu
   comme du **texte** (c'est du XML). Le type servi reste **déduit de l'extension** (`PageAttachments`),
   jamais du contenu ni d'une déclaration du modèle.
5. **Taille bornée** : une image lue de la machine ne dépasse pas la taille d'une page
   (`PageLimits.maxPageBytes`, 8 Mo) ; la borne des 8 Mo par version, pièces comprises, reste tenue par
   `PageService.publish`.

### Cas d'erreur

| Situation | Résultat d'outil (erreur, le tour continue) |
|-----------|---------------------|
| Outil appelé hors garde (sans droit de l'espace) — **second verrou** | refus, rien n'est lu ni rangé |
| Pièce jointe d'extension non servie (`.pdf`, `.zip`, `.exe`…) | refus : liste des extensions acceptées |
| Image annoncée > 8 Mo (`read_file_bytes` / stockage) | refus : « image trop volumineuse, 8 Mo au plus » |
| Runner trop ancien (pas de `read_file_bytes`) pour une image | refus nommé : « mets à jour le runner depuis la Forge » |
| Réponse binaire du runner illisible (Base64 invalide) | refus : réponse du runner invalide |
| Fichier illisible / absent (poste ou stockage) | le motif de la source |
| `path` illisible en `SANDBOX` (fichier introuvable) | motif du stockage |
| Les autres cas de SF-109-02 (ni html ni path, page_id inconnu, quota…) | inchangés |

---

## Critères d'acceptation

- [ ] CA1 — projet **hébergé** (cible `SANDBOX`) + droit Forge : `page_publish` est dans la panoplie et le guide est dans la consigne (avant : fermé).
- [ ] CA2 — sans droit de l'espace : ni outil ni guide, quelle que soit la cible.
- [ ] CA3 — `ADMIN` sans abonnement : l'outil est donné sur `SANDBOX` comme sur `RUNNER`.
- [ ] CA4 — cible `RUNNER` : une pièce jointe `capture.png` est lue **en binaire** par le runner (`read_file_bytes`), tracée, et rangée telle quelle (octets intacts).
- [ ] CA5 — cible `SANDBOX` : `html` fourni + accord range la page depuis le stockage, sans jamais appeler le runner.
- [ ] CA6 — cible `SANDBOX` : une pièce jointe image est lue depuis le **stockage** (`WorkspaceService.readFileBytes`), pas depuis le runner.
- [ ] CA7 — une image `.png` de la machine est servie avec `image/png` et la CSP `sandbox` (route de lecture), déduit de l'extension.
- [ ] CA8 — une pièce jointe d'extension non servie (`.pdf`) est refusée avec la liste des extensions acceptées.
- [ ] CA9 — image annoncée > 8 Mo : refus, rien n'est rangé.
- [ ] CA10 — runner sans `read_file_bytes` pour une image : refus nommé (mise à jour du runner) ; le texte continue de passer.
- [ ] CA11 — **le critère du reliquat** : une page avec **deux captures** de la machine se range (deux pièces jointes images) et son HTML les référence — elle s'affiche dans le terminal (bloc « Page publiée » de SF-109-03, inchangé).

---

## Périmètre

### Hors scope (explicite)

- Le bloc « Page publiée », le panneau, le plein écran (SF-109-03) : inchangés — la page servie porte
  désormais des images, mais la mécanique d'affichage ne change pas.
- Les **pièces jointes binaires non-image** (PDF, docx, zip) dans une page : hors scope (une page n'est
  pas un porte-documents ; le courriel F-110 les porte).
- Redimensionner ou recompresser une image : elle est rangée telle quelle, sous la borne des 8 Mo.
- Les Managed Agents comme exécuteur d'outils personnalisés au sens large : ici on n'ouvre **que**
  `page_publish`, dont l'exécution reste dans la gateway.

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format / Valeurs autorisées | Unicité | Normalisation |
|-------|-------------|-------------|----------------------------|---------|---------------|
| `attachments[].name` | Oui (par pièce) | 100 | nom plat, extension servie (`PageAttachments`) | nom unique | — |
| image (octets) | — | 8 Mo | png, jpg, jpeg, gif, webp | — | déduit de l'extension |
| pièce texte (octets) | — | 512 Kio (borne runner) | css, js, mjs, json, svg, csv, txt, md | — | — |

---

## Technique

### Endpoint(s)

Aucun nouveau. Les images sont servies par les routes de lecture existantes de SF-109-01/04
(`/pages/{id}/content`, `/p/{token}/{name}`).

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| `pages`, `page_versions` | INSERT / UPDATE | via `PageService` (SF-109-01), inchangé |
| `runner_audit` | INSERT | lecture d'image sur le poste tracée |

### Migration Liquibase

- [x] Non applicable — aucun changement de schéma ; le stockage des pièces (octets) et les types image
  (`png/jpg/jpeg/gif/webp`) existent déjà (`PageAttachments`, `PageStore`).

### Composants impactés

- `pages/PageToolCatalog` : garde `isOpenFor` (retrait de la condition « sur un poste ») ; schéma et
  description de l'outil, `DESIGN_GUIDE` (les images de la machine peuvent être jointes).
- `pages/PageToolExecutor` : lecture selon la cible (poste **ou** stockage) ; lecture binaire des images
  (par tranches côté runner) ; bornes ; dépendances ajoutées (`WorkspaceService`, `PageLimits`).
- `atelier/WorkspaceService` : ajout de `readFileBytes(userId, id, path)` (octets d'un fichier du projet).
- Tests : `PageToolCatalogTest`, `PageToolExecutorTest`, `PageToolPublishIntegrationTest`,
  `atelier/AtelierChatServicePageToolTest` (la garde SANDBOX s'ouvre).

### Préoccupations transversales

- **Plans / limites : OUI.** Composants touchant les limites : `PageToolCatalog.isOpenFor` →
  `SpaceEntitlementService.isEntitled` (règle ADMIN incluse), inchangé sinon ; bornes de taille de
  `PageLimits` (8 Mo) réutilisées ; quota de stockage de SF-109-01 inchangé ; génération sur le quota du
  tour inchangée. **Aucun** service de limite modifié.
- **Contexte tenant : OUI.** Composants qui résolvent le tenant, tous vérifiés : cible `RUNNER` →
  `RunnerTargets.of(workspace)` (poste possédé, `userId` du tour) ; cible `SANDBOX` →
  `WorkspaceService.requireOwned(userId, id)` (isolation `user_id`) et `readFileBytes` sous la même
  garde ; rangement `PageService.publish` au `userId` du tour ; republication `findByIdAndUserId`.
- **Auth / Principal : non** — aucune route nouvelle, aucune modification de la chaîne de filtres. Les
  routes de lecture d'image existent déjà (SF-109-01/04) et sont authentifiées / par ticket.
- **Navigation : non** — aucune route front nouvelle.

---

## Plan de test

### Tests unitaires

- [ ] `PageToolCatalogTest` — la garde ouvre `SANDBOX` avec le droit de l'espace (CA1), reste fermée sans
  droit (CA2), ADMIN d'office sur `SANDBOX` (CA3) ; le guide mentionne les images de la machine.
- [ ] `PageToolExecutorTest` — RUNNER : image lue en binaire (`read_file_bytes`), tracée, octets intacts
  (CA4) ; extension non servie refusée (CA8) ; image > 8 Mo refusée (CA9) ; runner sans `read_file_bytes`
  → refus nommé, le texte passe encore (CA10) ; Base64 invalide → refus ; SANDBOX : html sans runner
  (CA5), image lue depuis le stockage (CA6).

### Tests d'intégration

- [ ] `PageToolPublishIntegrationTest` (contexte Spring) — une page avec **deux captures** est rangée en
  v1 (deux pièces images) et servie avec `image/png` sous la CSP `sandbox` (CA7, CA11) ; en cible
  `SANDBOX`, la publication range réellement depuis le stockage.
- [ ] `AtelierChatServicePageToolTest` — buildTools/consigne ouverts sur `SANDBOX` avec le droit (CA1-CA3).

### Isolation utilisateur

- [x] Applicable — en `SANDBOX`, la lecture des octets passe par `requireOwned(userId, id)` ; en `RUNNER`,
  par le poste possédé ; le rangement et la republication restent au `userId` du tour.

---

## Dépendances

### Subfeatures bloquantes

- SF-109-01 (rangement, service, CSP) — mergée. SF-109-02 (outil) — mergée. SF-109-03 (aperçu) — mergée.

### Questions ouvertes impactées

- Aucune.

---

## Notes et décisions

- **D1 — Le `svg` reste du texte.** C'est du XML lisible ; `read_file` le rend sans le corrompre, et le
  faire passer par la voie binaire n'apporterait rien. Seuls les rasters (`png/jpg/jpeg/gif/webp`) passent
  par `read_file_bytes`.
- **D2 — Aucune nouvelle route ni CSP.** La sécurité d'une image servie est déjà celle d'une pièce jointe
  de page (SF-109-01) : origine opaque, `nosniff`, `connect-src 'none'`. L'image hérite de la politique
  sans une ligne de plus côté service.
- **D3 — Lecture par la cible du terminal, décidée par la gateway.** L'agent donne un `path` ; c'est la
  gateway qui sait si ce chemin vit sur le poste (RUNNER) ou dans le stockage (SANDBOX). Un seul schéma
  d'outil, deux sources — jamais au modèle de choisir.
- **D4 — Runner ancien.** `read_file_bytes` (F-110 / SF-110-03) peut manquer sur un vieux runner : une
  image demande alors la mise à jour, dit clairement ; le texte continue par `read_file`.
