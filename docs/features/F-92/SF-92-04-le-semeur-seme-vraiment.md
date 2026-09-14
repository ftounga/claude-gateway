# Mini-spec — [F-92 / SF-92-04] Le semeur de gouvernance sème vraiment

## Identifiant

`F-92 / SF-92-04`

## Feature parente

`F-92` — La carte du poste

## Statut

`in-progress`

## Date de création

2026-09-14

## Branche Git

`feat/SF-92-04-semeur-seme-vraiment`

---

## Objectif

> En une phrase : garantir que le paquet `savoir-durable` est **réellement** semé au démarrage — avec ses 6 fichiers de genre `MAP` — pour que la carte du poste puisse se peupler, et corriger le libellé trompeur affiché quand un paquet est actif mais sans carte lisible.

---

## Comportement attendu

### Cas nominal

`GovernancePackageSeeder.seedOnStartup()` (`@EventListener(ApplicationReadyEvent)`) doit exécuter la
mise à jour du paquet (save paquet + `deleteByPackageId` + save des 11 fichiers dont 6 `MAP` + report
des `known_digests`) **dans une transaction**, de façon **atomique**. Après démarrage, le paquet actif
`savoir-durable` contient ses 6 fichiers `MAP` (`README.md`, `acces.md`, `reseau.md`, `plateformes.md`,
`donnees.md`, `exploitation.md`) et sa version est à jour.

En conséquence, `GET /governance/hosts/{ref}/map` sur un poste connecté et gouverné renvoie
`governed=true` et la carte se peuple.

### Cause racine corrigée

`seedOnStartup()` (méthode **non** transactionnelle) appelait `seed()` (`@Transactional`) par
**auto-invocation** : le proxy Spring **n'applique pas** `@Transactional`. La requête modifiante
`deleteByPackageId` (qui exige une transaction) levait `InvalidDataAccessApiUsageException` à chaque
démarrage → le chemin de mise à jour échouait → le paquet déployé restait sans fichier `MAP`.

### Cas d'erreur

| Situation | Comportement attendu | Code |
|-----------|---------------------|------|
| Base indisponible au démarrage | Le produit démarre quand même ; l'exception est journalisée **avec sa stack complète** (`log.warn(msg, ex)`) | — |
| Ressource de paquet absente | Le semeur renonce entièrement (comportement inchangé), avertit | — |
| Paquet actif mais sans fichier `MAP` (semis en échec, ou paquet sans carte) | `GET /map` distingue « paquet actif sans carte lisible » de « aucun paquet actif » : ne dit **plus** « activez le paquet » | 200 |
| Aucun paquet actif sur le poste | `GET /map` dit « aucune gouvernance active » + lien « Ouvrir la gouvernance » | 200 |

---

## Critères d'acceptation

- [ ] `seedOnStartup()` exécute `seed()` **à travers le proxy transactionnel** (self-injection / TransactionTemplate) : `@Transactional` s'applique réellement.
- [ ] Un test d'intégration (contexte Spring) qui appelle `seedOnStartup()` sur un paquet existant à mettre à jour **échoue avant** le correctif et **passe après** : après démarrage, le paquet actif contient bien ses 6 fichiers `MAP` et sa version est bumpée.
- [ ] Les 6 fichiers de carte sont bien déclarés de genre `MAP` (liste confirmée dans le semeur).
- [ ] `seedOnStartup()` journalise la **stack complète** de toute exception (plus seulement `getSimpleName()`).
- [ ] `GET /map` renvoie un message distinct quand un paquet est actif mais sans carte lisible ; le frontend n'affiche alors **pas** « Aucune gouvernance active : activez le paquet », ni le lien « Ouvrir la gouvernance ».
- [ ] Isolation `user_id` inchangée : la lecture de carte reste par `user_id` + `host_id` (les tables du paquet ne portent pas de `user_id`, contenu produit).

---

## Périmètre

### Hors scope (explicite)

- Le dépôt/mise à jour de la carte sur la machine (BUG 2 / SF-96-04).
- Toute modification de schéma (aucune migration).
- La reformulation du bandeau « version plus récente » (SF-96-04).

---

## Contraintes de validation

| Champ | Obligatoire | Règle |
|-------|-------------|-------|
| genre des 6 fichiers carte | Oui | `MAP` (chemins plats, sans `/`) |
| version du paquet | Oui | bumpée si le contenu change, inchangée sinon (idempotence) |

---

## Technique

### Endpoint(s)

| Méthode | URL | Auth | Notes |
|---------|-----|------|-------|
| GET | `/governance/hosts/{ref}/map` | Oui | message distinct « actif sans carte » vs « non gouverné » |

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| `governance_packages` | UPDATE/INSERT | atomicité au semis |
| `governance_package_files` | DELETE + INSERT | atomicité au semis |

### Migration Liquibase

- [x] Non applicable (correctif de logique)

### Composants Angular

- `PostesComponent` (onglet Carte) — libellé distinct ; test de rendu.

---

## Plan de test

### Tests unitaires

- [ ] `GovernanceMapReadingServiceTest` — paquet actif sans `MAP` → message « actif sans carte », `governed=true` ; aucun paquet actif → `NOT_GOVERNED`, `governed=false`.
- [ ] `GovernancePackageSeederTest` — la stack est journalisée sur échec (comportement de `seedOnStartup` préservé).

### Tests d'intégration

- [ ] `GovernanceSeededPackageIntegrationTest` (contexte Spring) — `seedOnStartup()` sur un paquet existant à mettre à jour → le paquet actif porte ses 6 `MAP` (échoue avant le correctif).

### Frontend

- [ ] `postes.component.spec` — carte `governed=true, readable=false, message` (actif sans carte) : affiche le message, **pas** le lien « Ouvrir la gouvernance ».

### Isolation

- [x] Inchangée : carte lue par `user_id` + `host_id` ; contenu du paquet sans `user_id` (F-52).

---

## Dépendances

- SF-92-01/02/03 (Done).
- SF-96-02 (report `known_digests`) — impliqué dans le chemin de mise à jour rendu atomique.

---

## Notes et décisions

- Correctif d'auto-invocation retenu : **self-injection via `ObjectProvider<GovernancePackageSeeder>`** (champ), pour que `seedOnStartup()` appelle `seed()` **à travers le proxy** ; la signature du constructeur reste inchangée (aucun test existant à réécrire), et `seed()` conserve son `@Transactional`.
