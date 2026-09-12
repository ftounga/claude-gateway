# Mini-spec — F-85 / SF-85-01 — Le sélecteur ne propose que ce qui passe

## Identifiant

`F-85 / SF-85-01`

## Feature parente

`F-85` — Un fichier refusé dit pourquoi, et quoi faire

## Statut

`done`

## Date de création

2026-09-12

## Branche Git

`feat/SF-85-01-accept-derive`

---

## Objectif

Chaque sélecteur de fichier du produit porte un attribut `accept` **dérivé** de la liste blanche du
chemin concerné — lue au serveur quand elle y vit — pour que le système d'exploitation cesse de
proposer des fichiers qui seront refusés, et pour que l'écran **ne puisse pas** diverger du serveur.

---

## L'inventaire des sélecteurs (demandé au cadrage : « il y en a plus qu'on ne croit »)

| # | Chemin | Composant | `accept` aujourd'hui | Liste blanche de référence | Décision SF-85-01 |
|---|---|---|---|---|---|
| 1 | Bibliothèque / documents (OCR) | `DocumentsComponent` (`documents.component.html`) | `application/pdf,image/png,image/jpeg,image/tiff` — **recopié à la main** | `app.ocr.allowed-types` (**serveur**) | **Dérivé** : lu au serveur via `GET /api/file-formats` |
| 2 | Pièce jointe d'une conversation | `ChatComponent` (`chat.component.html`) | **aucun** — le système propose tout | `app.upload.allowed-types` (**serveur**) | **Dérivé** : lu au serveur via `GET /api/file-formats` |
| 3 | Fichier d'un projet de la Forge | `AtelierFilesComponent` (`atelier-files.component.html`) | `[accept]="workspaceTextAccept"` — **déjà dérivé** | `WORKSPACE_TEXT_EXTENSIONS` (**front**, source unique, sert aussi de garde-fou anti-binaire) | **Inchangé** — déjà dérivé d'une source unique |
| 4 | Import d'archive d'un poste | `PostesComponent` (`postes.component.html`) | `.zip,application/zip` — **en dur** | aucune liste serveur : le zip est validé en le décompressant | **Dérivé** d'une constante unique `ARCHIVE_ACCEPT` |
| — | « Depuis ma bibliothèque » | `LibraryPickerDialogComponent` | — | — | **Hors sujet** : choisit un document **déjà** accepté, pas un fichier du disque |

Il n'existe **aucun** autre sélecteur : `grep type="file"` rend ces quatre-là, et aucun
`document.createElement('input')` n'existe dans le code.

---

## Comment les listes du serveur deviennent lisibles par l'écran

Elles **ne sont pas recopiées**. Un endpoint les publie :

```
GET /api/file-formats   (authentifié, lecture seule)
→ { "documents":   { "mediaTypes": ["application/pdf","image/png","image/jpeg","image/tiff"], "maxBytes": 20971520 },
    "attachments": { "mediaTypes": [...], "maxBytes": 33554432 } }
```

Le contrôleur lit `OcrProperties` et `UploadProperties` — **les mêmes objets** que ceux dont la
validation se sert pour refuser. Pour que ce soit démontrable, `allowedTypeSet()` (utilisé par la
validation) est désormais **construit à partir de** `normalizedAllowedTypes()` (utilisé par
l'exposition) : une seule liste, deux vues. Ajouter un type à `app.ocr.allowed-types` le fait
apparaître dans l'`accept` de l'écran **sans toucher au frontend** — c'est le test qui empêche les
deux de diverger.

---

## Comportement attendu

### Cas nominal

1. Au chargement d'un écran portant un sélecteur serveur (documents, conversation), le frontend
   appelle **une fois** `GET /api/file-formats` (résultat mis en cache pour la session).
2. Le sélecteur de la bibliothèque porte `accept` = les types MIME de `documents`.
3. Le sélecteur de pièce jointe porte `accept` = les types MIME de `attachments`.
4. Le sélecteur de fichier de projet et l'import d'archive gardent leur `accept`, déjà dérivé d'une
   source unique.
5. Le système d'exploitation ne propose plus qu'un fichier acceptable ; le refus devient rare.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|---|---|---|
| `GET /api/file-formats` échoue (réseau, 500) | `accept` reste **vide** — le sélecteur propose tout, exactement comme aujourd'hui. **Aucune liste de secours recopiée** : une liste de secours serait une deuxième source, donc une divergence programmée. Le refus reste rattrapé par le message de SF-85-02 | — |
| Réponse reçue après le clic sur le bouton | Le sélecteur s'ouvre sans filtre ce coup-ci ; l'appel part au constructeur, bien avant le geste | — |
| Appel non authentifié | `401` — l'endpoint est derrière `anyRequest().authenticated()`, comme le reste | 401 |
| `app.ocr.allowed-types` vide en configuration | Le record retombe sur ses valeurs par défaut (comportement existant, inchangé) | — |

---

## Critères d'acceptation

- [ ] `GET /api/file-formats` rend les deux listes blanches et les deux plafonds, en minuscules, sans
      doublon, dans l'ordre de la configuration.
- [ ] **Le test anti-divergence** : une configuration de test qui ajoute un type à
      `app.ocr.allowed-types` le voit apparaître dans la réponse **et** accepté par la validation —
      aucune ligne de frontend n'est modifiée.
- [ ] `allowedTypeSet()` (validation) et `normalizedAllowedTypes()` (exposition) rendent **le même
      ensemble**, pour `UploadProperties` comme pour `OcrProperties`.
- [ ] L'endpoint exige un jeton : `401` sans `Authorization`.
- [ ] Le sélecteur de la bibliothèque porte un `accept` **égal à la réponse du serveur** — et non à
      une chaîne écrite dans le gabarit (la chaîne en dur disparaît du code).
- [ ] Le sélecteur de pièce jointe de conversation porte un `accept` (il n'en avait aucun).
- [ ] Le service ne fait **qu'un seul** appel HTTP même si plusieurs écrans le demandent.
- [ ] Appel en échec ⇒ `accept` vide, aucun écran cassé.
- [ ] Suites backend et frontend vertes.

---

## Périmètre

### Hors scope (explicite)

- **Changer une liste blanche**, dans un sens ou dans l'autre. Aucune valeur n'est ajoutée ni retirée.
- Le **message** de refus : c'est SF-85-02.
- La **liste visible avant l'essai** : c'est SF-85-03.
- Accepter `.docx` nativement, convertir côté serveur, le pipeline OCR.
- Déplacer `WORKSPACE_TEXT_EXTENSIONS` côté serveur : cette liste est déjà à source unique et sert de
  garde-fou anti-binaire dans le navigateur ; la déplacer serait un autre sujet, sans gain ici.

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format / Valeurs | Unicité | Normalisation |
|---|---|---|---|---|---|
| `mediaTypes` (réponse) | Oui | — | types MIME | oui (`distinct`) | minuscules |
| `maxBytes` (réponse) | Oui | — | entier > 0 | — | octets |

Aucune saisie utilisateur : l'endpoint est en lecture seule et sans paramètre.

---

## Technique

### Endpoint(s)

- `GET /api/file-formats` — **créé**. Authentifié. Sans paramètre. Réponse `FileFormatsResponse`.

### Tables impactées

Aucune.

### Migration Liquibase

- [x] Non applicable

### Classes backend

- `fr.claudegateway.fileformats.FileFormatsController` — créé.
- `fr.claudegateway.fileformats.dto.FileFormatsResponse`, `FileFormatProfileResponse` — créés.
- `fr.claudegateway.upload.UploadProperties` — ajout de `normalizedAllowedTypes()`, `allowedTypeSet()` en dérive.
- `fr.claudegateway.ocr.OcrProperties` — idem.

### Composants Angular

- `core/models/file-formats.models.ts` — créé.
- `core/services/file-formats.service.ts` — créé (appel unique, cache de session).
- `shared/file-selectors.ts` — créé : `ARCHIVE_ACCEPT`, source unique de l'`accept` d'archive.
- `DocumentsComponent`, `ChatComponent`, `PostesComponent` — `accept` dérivé.

---

## Plan de test

### Tests unitaires

- [ ] `UploadPropertiesTest` / `OcrPropertiesTest` — `allowedTypeSet()` == `normalizedAllowedTypes()`, minuscules, sans doublon.
- [ ] `file-formats.service.spec` — un seul appel HTTP pour deux demandeurs ; `accept` dérivé de la réponse ; échec ⇒ chaîne vide.

### Tests d'intégration

- [ ] `FileFormatsApiIntegrationTest` — 200 authentifié, contenu = listes blanches ; 401 sans jeton.
- [ ] `FileFormatsApiIntegrationTest` — **anti-divergence** : la configuration de test ajoute un type ⇒ il est dans la réponse **et** la validation l'accepte.
- [ ] `documents.component.spec` — l'`accept` du sélecteur vient du serveur (et change si le serveur change).
- [ ] `chat.component.spec` — le sélecteur de pièce jointe porte un `accept` non vide.

### Isolation utilisateur

- [x] Applicable — l'endpoint est authentifié mais **ne lit aucune donnée d'utilisateur** : il publie
      une configuration d'application, identique pour tous. Aucun filtre `user_id` n'a de sens ici, et
      aucune donnée d'un autre utilisateur ne peut fuir : la réponse ne contient que de la
      configuration. Vérifié par le test 401 (rien n'est servi sans authentification).

---

## Dépendances

### Subfeatures bloquantes

Aucune.

### Questions ouvertes impactées

Aucune.

---

## Préoccupations transversales

| Préoccupation | Composants vérifiés un par un | Verdict |
|---|---|---|
| **Auth / Principal** | Aucun changement : l'endpoint tombe sous `anyRequest().authenticated()` déjà en place, aucun matcher ajouté, aucun `Principal` lu | traité |
| **Contexte tenant** | Aucune donnée d'utilisateur lue ni écrite ; aucun composant de résolution du tenant touché | traité |
| **Plans / limites** | `app.upload.max-size` et `app.ocr.max-size` sont **exposés**, jamais modifiés ; aucun appel aux services de quota | traité |
| **Navigation / routing** | Aucune route Angular ajoutée ni modifiée ; aucun guard touché | traité |

---

## Notes et décisions

- **Arbitrage — pas de liste de secours côté écran.** Si `GET /api/file-formats` échoue, `accept`
  reste vide plutôt que de retomber sur une liste écrite dans le frontend. Une liste de secours est
  une deuxième source de vérité, donc exactement la divergence que la subfeature existe pour
  empêcher. Le coût du choix est le comportement d'aujourd'hui (le système propose tout), rattrapé
  par le message de SF-85-02. **Réversible** : une constante.
- **Arbitrage — un endpoint plutôt qu'un enrichissement des réponses existantes.** Publier les listes
  dans la réponse de `GET /api/documents` aurait évité un appel, mais aurait lié une configuration
  d'application à une ressource d'utilisateur, et n'aurait rien donné au composeur de conversation.
  **Réversible.**
- **`WORKSPACE_TEXT_EXTENSIONS` reste au frontend.** Le cadrage ne nomme que `app.upload.allowed-types`
  et `app.ocr.allowed-types` comme listes serveur. La liste texte du workspace est déjà à source
  unique et sert de garde-fou dans le navigateur ; la déplacer serait un autre sujet.
