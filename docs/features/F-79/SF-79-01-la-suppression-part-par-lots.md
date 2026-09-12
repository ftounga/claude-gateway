# Mini-spec — F-79 / SF-79-01 — La suppression du stockage part par lots et rend compte

## Identifiant

`F-79 / SF-79-01`

## Feature parente

`F-79` — Supprimer un projet, quel que soit le nombre de fichiers

## Statut

`done` — mergée le 2026-09-12 (PR #409)

## Date de création

2026-09-12

## Branche Git

`feat/SF-79-01-suppression-par-lots`

---

## Objectif

Supprimer les fichiers d'un projet **par lots de 1 000 au plus**, ne rendre la main qu'une fois
**tout** effacé, et faire dire à l'échec **ce qui est parti et ce qui reste**.

---

## Comportement attendu

### Cas nominal

1. `DELETE /api/v1/atelier/workspaces/{id}` → `WorkspaceService.delete` (isolation `user_id` par
   `requireOwned`) → `storage.deletePrefix(atelier/{userId}/{workspaceId}/)`.
2. `deletePrefix` liste les clés du préfixe (`listKeys`, **déjà paginé** : 1 000 clés par page,
   jeton de continuation bouclé jusqu'à `isTruncated = false`).
3. Les clés sont découpées en lots de **1 000 au plus** ; un appel `DeleteObjects` **par lot**,
   en séquence. 7 288 objets → **8 appels**.
4. `deletePrefix` ne rend la main qu'après le dernier lot. La transaction efface ensuite la
   conversation, le journal runner, puis la ligne du projet. Réponse **204**, inchangée.

Préfixe vide : **aucun** appel `DeleteObjects` (rien à supprimer).

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Un lot rend des erreurs **par clé** (`DeleteObjectsResponse.errors()`, sans exception) | Les lots suivants sont **quand même** envoyés ; les clés en échec sont accumulées ; `WorkspaceStorageDeletionException` à la fin avec effacées/restantes | 500 `storage_partial_delete` |
| Un lot **lève** une `SdkException` (droits, réseau, bucket) | Arrêt immédiat (échec systémique — insister enverrait N requêtes vouées au même sort) ; `WorkspaceStorageDeletionException` avec effacées = lots précédents, restantes = le reste | 500 `storage_partial_delete` |
| Échec partiel, quel qu'il soit | La transaction est **annulée** : le projet, sa conversation et son journal **restent en base**. L'utilisateur peut réessayer, et le second passage n'a plus que le reliquat à effacer | 500 |
| Projet inexistant ou appartenant à un autre utilisateur | `WorkspaceNotFoundException`, message identique à aujourd'hui | 404 |
| Listage impossible (`listKeys` lève) | Exception propagée telle quelle, transaction annulée | 500 |

---

## Critères d'acceptation

- [ ] Aucun appel `DeleteObjects` ne porte plus de **1 000** clés.
- [ ] Un préfixe de **2 500** clés est **entièrement** effacé en **3** appels (1 000 / 1 000 / 500).
- [ ] Un préfixe vide ne déclenche **aucun** appel `DeleteObjects`.
- [ ] `listKeys` suit le jeton de continuation : 2 500 clés réparties sur 3 pages sont **toutes**
      rendues (non-régression figée par un test).
- [ ] Les erreurs **par clé** de la réponse ne sont plus ignorées : elles lèvent
      `WorkspaceStorageDeletionException` **après** que tous les lots ont été tentés.
- [ ] L'exception porte le nombre de clés **effacées** et le nombre **restantes** ; le message rendu
      au client les cite.
- [ ] Une `SdkException` sur le 2ᵉ lot arrête l'envoi (pas de 3ᵉ appel) et rend compte du 1ᵉʳ lot
      effacé.
- [ ] Les **clés** en échec ne sortent que dans le **journal serveur** (`log.warn`, échantillon
      borné) — jamais dans la réponse HTTP.
- [ ] Le contrat visible est inchangé : 204 au succès, 404 sur projet non possédé, 500 sur échec.
- [ ] Isolation `user_id` inchangée : le préfixe effacé reste `atelier/{userId}/{workspaceId}/`,
      obtenu après `requireOwned`.

---

## Périmètre

### Hors scope (explicite)

- Suppression asynchrone d'un très gros projet ; plafond sur la taille d'un projet (PRODUCT_SPEC).
- Reprise automatique d'une suppression partielle (l'utilisateur réessaie ; le projet est resté).
- Parallélisation des lots (séquentiel : la suppression d'un projet n'est pas un chemin chaud).
- Toute modification d'écran, d'endpoint, de table, de migration.
- Tout `kubectl`, tout déploiement.

---

## Valeurs initiales

Sans objet : cette subfeature ne crée aucune entité et ne modifie aucun état initial.

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format / Valeurs autorisées | Unicité | Normalisation |
|-------|-------------|-------------|----------------------------|---------|---------------|
| — | — | — | — | — | — |

Aucune entrée utilisateur nouvelle. La seule constante introduite est interne :
`DELETE_BATCH_SIZE = 1000` (plafond S3 pour `DeleteObjects`, non configurable — ce n'est pas un
réglage, c'est une limite du fournisseur).

---

## Technique

### Endpoint(s)

| Méthode | URL | Auth | Rôle minimum |
|---------|-----|------|-------------|
| DELETE | `/api/v1/atelier/workspaces/{id}` | Oui | utilisateur propriétaire (inchangé) |

### Tables impactées

Aucune.

### Migration Liquibase

- [ ] Oui
- [x] Non applicable — aucune table, aucune colonne.

### Composants Angular

Aucun. L'écran de suppression (F-69) est inchangé.

### Fichiers touchés

| Fichier | Nature |
|---|---|
| `atelier/storage/S3WorkspaceStorage.java` | Lots de 1 000 ; lecture de `response.errors()` ; constructeur package-private `(S3Client, bucket)` pour les tests |
| `atelier/storage/WorkspaceStorageDeletionException.java` | **Nouveau** — porte effacées / restantes |
| `shared/error/GlobalExceptionHandler.java` | Mappe l'exception en 500 `storage_partial_delete` (au lieu du filet `internal_error`) |
| `atelier/WorkspaceService.java` | Javadoc de `delete` : ce que devient la transaction sur échec partiel |
| `atelier/storage/WorkspaceStorage.java` | Javadoc de `deletePrefix` : lots, tout-ou-compte-rendu |

---

## Plan de test

### Tests unitaires — `S3WorkspaceStorageDeletePrefixTest` (bouchon de stockage, aucun réseau)

- [ ] 2 500 clés → **3** appels `DeleteObjects`, tailles 1 000 / 1 000 / 500, **aucune** clé
      restante dans le bouchon.
- [ ] Aucun lot ne dépasse 1 000 clés (vérifié sur chaque appel capturé).
- [ ] Préfixe vide → **0** appel `DeleteObjects`.
- [ ] 2 500 clés réparties sur 3 pages de listage → `listKeys` en rend 2 500 (pagination figée).
- [ ] Erreurs par clé sur le 2ᵉ lot → les 3 lots sont envoyés, exception finale portant
      effacées = 2 497 / restantes = 3.
- [ ] `SdkException` sur le 2ᵉ lot → **2** appels seulement, exception portant effacées = 1 000 /
      restantes = 1 500.
- [ ] Le préfixe demandé est bien celui passé (aucune clé hors préfixe envoyée à la suppression).

### Tests d'intégration

- [ ] `WorkspaceDeletionApiIntegrationTest` existant : **toujours vert** (204, ce qui part et ce qui
      reste — contrat inchangé).
- [ ] `GlobalExceptionHandler` : `WorkspaceStorageDeletionException` → 500,
      `code = storage_partial_delete`, message citant les deux compteurs (test unitaire du handler
      ou vérification du message porté par l'exception).

### Isolation utilisateur

- [x] Applicable — `deletePrefix` n'est appelé qu'avec `prefixOf(userId, id)` **après**
      `requireOwned(userId, id)`. Le découpage en lots ne touche pas au préfixe : il ne fait que
      répartir des clés déjà filtrées par lui. Couvert par
      `WorkspaceDeletionApiIntegrationTest` (404 sur projet d'un autre utilisateur).

---

## Préoccupations transversales

| Préoccupation | Concernée ? | Analyse |
|---|---|---|
| Auth / Principal | Non | Aucun changement d'authentification ni de Principal. |
| Contexte tenant | Non | Le préfixe reste construit par `prefixOf(userId, id)`, inchangé. Aucun nouveau moyen de résoudre le tenant. |
| Plans / limites | Non | Aucun quota, aucun gate touché. |
| Navigation / routing | Non | Aucune route, aucun guard. |

---

## Dépendances

### Subfeatures bloquantes

Aucune.

### Questions ouvertes impactées

Aucune (`docs/OPEN_QUESTIONS.md` non concerné).

---

## Notes et décisions

- **Pourquoi continuer après des erreurs par clé, mais s'arrêter sur une exception ?** Une erreur
  par clé est locale (une clé verrouillée, un objet en cours d'écriture) : les 6 000 suivantes
  peuvent très bien partir. Une exception sur le lot est systémique (droits IAM, bucket, réseau) :
  les sept requêtes suivantes échoueraient de la même façon, pour rien.
- **Pourquoi ne pas rendre 207 / un corps détaillé au succès ?** Le contrat visible ne doit pas
  changer : ce sont des correctifs. Le 204 reste un 204.
- **Pourquoi un constructeur package-private ?** `S3WorkspaceStorage` construisait son client dans
  son constructeur : intestable sans réseau. Le constructeur Spring est conservé à l'identique ;
  le second n'existe que pour le bouchon de test, dans le même paquet.
