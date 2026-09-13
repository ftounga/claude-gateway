# Mini-spec — F-106 / SF-106-01 — Les espaces d'un client

## Identifiant

`F-106 / SF-106-01`

## Feature parente

`F-106` — La Vigie, l'espace du pilotage (cadrage : `CADRAGE-F-106-la-vigie.md` §3, §6, §7)

## Statut

`in-progress`

## Date de création

2026-09-13

## Branche Git

`feat/SF-106-01-espaces-d-un-client`

---

## Objectif

Donner à chaque poste (client) la liste des **espaces** où il est activé — `FORGE`, `VIGIE` — sans
jamais le dupliquer : activer, retirer, lire, et filtrer les API par espace, isolé par `user_id`.

---

## Comportement attendu

### Cas nominal

1. **Table `host_spaces`** (migration `091`) : une ligne par couple *(poste, espace)*, avec le
   propriétaire et la date d'activation. La migration active **tous les postes existants dans la
   Forge**, aucun dans la Vigie. Aucun jeton, appairage ni runner n'est touché.
2. **Création d'un poste** : `POST /runner-hosts` accepte un champ facultatif `space`
   (`FORGE` par défaut, compatible avec tout client existant). Le poste naît activé dans cet espace
   seulement — « connecter un client depuis la Vigie » ne le fait pas apparaître dans la Forge.
3. **Lire les espaces** : `GET /runner-hosts/spaces` rend tous les postes possédés avec leur nom,
   leur état de mission et leurs espaces — c'est la liste d'où l'écran propose « activer dans
   l'autre espace ».
4. **Activer** : `PUT /runner-hosts/{hostId}/spaces/{space}` — idempotent, aucun appairage ; rend
   le poste et ses espaces.
5. **Retirer** : `DELETE /runner-hosts/{hostId}/spaces/{space}` — le poste disparaît de cet espace,
   **rien n'est supprimé** (projets, terminaux, Radar, jetons restent). Rend le poste et ses espaces.
6. **Vue d'ensemble filtrée** : `GET /runner-hosts/overview?space=FORGE|VIGIE` (défaut `FORGE`) ne
   rend que les postes activés dans cet espace ; le poste virtuel « Hébergé » n'existe que dans la
   Forge. Chaque poste porte désormais `spaces` (ex. `["FORGE","VIGIE"]`).
7. **Les API de la Vigie** (Radar : `/radar/hosts/{hostId}/…` lecture, corrections, vérification
   guidée) exigent que le poste soit **activé dans la Vigie** ; export et purge restent ouverts à
   la seule possession (on récupère et on efface ses données même après retrait).
8. **Clôture de mission commune** : l'état de mission reste une colonne du poste ; clôturer depuis
   un espace se lit dans l'autre (même valeur dans les deux vues d'ensemble).
9. **Suppression** : la suppression d'un poste et celle du compte effacent ses lignes d'espace.
10. **Poste sans aucune ligne** (créé par un pod de la version précédente pendant un déploiement
    progressif, ou jeu de données antérieur) : il est **lu comme activé dans la Forge**. Le premier
    geste d'activation ou de retrait matérialise cette ligne avant d'agir, pour qu'aucun poste ne
    disparaisse de la Forge par effet de bord.

### Cas d'erreur

| Situation | Comportement attendu | Code |
|-----------|---------------------|------|
| Poste inconnu ou d'autrui (lecture, activer, retirer) | « Poste introuvable », rien n'est écrit | 404 |
| Espace inconnu dans le chemin ou `?space=` | refus de la valeur | 400 |
| Retirer le **dernier** espace d'un poste | refus : un client vit dans au moins un espace ; clôturer ou supprimer reste possible | 409 `host_last_space` |
| Activer / créer / lire la vue dans `VIGIE` sans droit Teams (compte non admin) | refus, rien n'est écrit | 403 |
| Sans accès Forge (Atelier) | refus, comme toute route `/runner-hosts` | 403 |
| Radar lu sur un poste possédé mais non activé dans la Vigie | refus explicite | 409 `host_not_in_space` |
| Radar lu sur un poste d'autrui | introuvable (possession vérifiée d'abord) | 404 |

---

## Critères d'acceptation

- [ ] Après migration, chaque poste existant a exactement une ligne `FORGE` et aucune `VIGIE`
      (vérifié sur H2 ; changesets PostgreSQL et H2 distincts pour la génération d'UUID).
- [ ] `POST /runner-hosts {name, space:"VIGIE"}` crée un poste absent de `overview` (Forge) et
      présent dans `overview?space=VIGIE`.
- [ ] `PUT …/spaces/VIGIE` rend `["FORGE","VIGIE"]`, deux fois de suite sans doublon.
- [ ] `DELETE …/spaces/VIGIE` rend `["FORGE"]` ; projets, terminaux et Radar du poste intacts.
- [ ] Retirer le dernier espace répond 409 et ne change rien.
- [ ] Le poste d'autrui répond 404 à la lecture, l'activation et le retrait ; aucune ligne écrite.
- [ ] `overview?space=VIGIE` n'inclut jamais « Hébergé » ; `overview` sans paramètre est inchangé
      pour un compte dont tous les postes sont dans la Forge.
- [ ] Le Radar d'un poste non activé dans la Vigie répond 409 ; l'export et la purge répondent.
- [ ] Clôturer la mission d'un poste activé dans les deux espaces se lit dans les deux vues.
- [ ] Supprimer le poste ou le compte ne laisse aucune ligne `host_spaces`.

---

## Périmètre

### Hors scope (explicite)

- Tout écran : la Vigie (SF-106-02), le déménagement de Teams (SF-106-03), les passerelles
  (SF-106-04), la page d'un espace non souscrit (SF-106-05).
- L'exigence de l'espace Vigie pour **ouvrir le terminal Teams** (SF-106-03).
- Le droit **par espace** et la facturation par espace (F-107) : ici la Vigie est gardée par le
  droit Teams existant (`TeamsAccessService` → `TeamsEntitlementService`, bypass admin compris).
- La purge automatique du Radar au retrait de la Vigie : l'écran la **propose** (SF-106-02), la
  route `POST /radar/hosts/{id}/purge` (`VIGIE_REMOVED`) existe déjà.
- La gouvernance (`/governance/hosts`) et « Voir travailler » ne changent pas de source : ils
  restent des écrans de la Forge (la vue d'ensemble par défaut est `FORGE`).

---

## Valeurs initiales

| Champ | Valeur initiale | Règle |
|-------|----------------|-------|
| espace à la création | `FORGE` | `space` absent ou nul ⇒ `FORGE` |
| `?space=` de la vue d'ensemble | `FORGE` | absent ⇒ `FORGE` |
| postes existants à la migration | `FORGE` | `activated_at` = `created_at` du poste |

---

## Contraintes de validation

| Champ | Obligatoire | Format / Valeurs autorisées | Normalisation |
|-------|-------------|----------------------------|---------------|
| `space` (corps, chemin, requête) | Non (corps, requête) / Oui (chemin) | `FORGE`, `VIGIE` | insensible à la casse ; autre ⇒ 400 |

---

## Technique

### Endpoint(s)

| Méthode | URL | Auth | Description |
|---------|-----|------|-------------|
| `GET` | `/api/runner-hosts/spaces` | JWT + Atelier | postes possédés et leurs espaces |
| `PUT` | `/api/runner-hosts/{hostId}/spaces/{space}` | JWT + Atelier (+ Teams si `VIGIE`) | activer |
| `DELETE` | `/api/runner-hosts/{hostId}/spaces/{space}` | JWT + Atelier | retirer |
| `GET` | `/api/runner-hosts/overview?space=` | JWT + Atelier (+ Teams si `VIGIE`) | vue filtrée (modifié, additif) |
| `POST` | `/api/runner-hosts` | JWT + Atelier (+ Teams si `VIGIE`) | champ `space` facultatif (modifié, additif) |
| `*` | `/api/radar/hosts/{hostId}/…` (hors export/purge) | inchangé + poste activé dans la Vigie | modifié |

### Tables impactées

- `host_spaces` (nouvelle) : `id uuid PK`, `user_id uuid NOT NULL`, `host_id uuid NOT NULL`,
  `space varchar(16) NOT NULL`, `activated_at timestamptz NOT NULL` ; index unique
  `(host_id, space)`, index `(user_id, space)`. Aucune clé étrangère (même choix que
  `host_seat_months`) : purge explicite à la suppression du poste et du compte.

### Migration Liquibase

- [x] Oui — `091-host-spaces.xml` : création (commun) + reprise des postes existants (un changeset
      PostgreSQL `gen_random_uuid()`, un changeset H2 `RANDOM_UUID()`), rollback = `dropTable`.

### Composants backend

- `runner/host/ClientSpace` (enum), `HostSpace` (entité), `HostSpaceRepository`,
  `HostSpaceService` (lecture, activation, retrait, filtre, purge), `HostLastSpaceException`,
  `HostNotInSpaceException`, `dto/HostSpacesResponse`.
- `RunnerHostService.create(userId, name, space)` écrit la première ligne dans la transaction de
  création ; `RunnerHostRequest` gagne `space`.
- `RunnerHostOverviewService.overview(userId, space)` ; `RunnerHostOverviewResponse.spaces`.
- `RunnerHostController` : trois routes, `?space=`, garde Teams sur `VIGIE`.
- `RadarScopeResolver.requireInVigie` ; `RadarController.scope`, `RadarSyncController.scope`.
- Purge : `HostSpaceService` écoute `RunnerHostLifecycleEvent.DELETED` ; `AccountService.deleteAccount`.
- `GlobalExceptionHandler` : 409 `host_last_space`, 409 `host_not_in_space`, 400 espace inconnu.

### Préoccupations transversales

- **Contexte tenant : oui.** Composants qui résolvent un poste et sont vérifiés :
  `RunnerHostService.requireOwned` (appelé avant toute écriture d'espace),
  `RunnerHostOverviewService.overview` (filtre `user_id` puis espace), `RadarScopeResolver`
  (possession puis espace), `AccountService.deleteAccount` (purge `user_id`),
  `HostSpaceRepository` (toutes les méthodes prennent `user_id`, sauf la purge d'un poste déjà
  vérifié). Tests d'isolation Alice/Bob sur les trois routes.
- **Plans / limites : oui (garde provisoire).** Appels au droit : `TeamsAccessService.requireAccess`
  ajouté sur `VIGIE` (création, activation, vue) ; inchangés : `AtelierAccessService` sur toutes les
  routes `/runner-hosts`, `RadarController` / `RadarSyncController` (droit Teams), export et purge
  sans droit. Aucun quota ni gate de plan nouveau.
- Auth / Principal : non. Navigation / routing : non (aucun écran).

---

## Plan de test

### Tests unitaires

- [ ] `HostSpaceServiceTest` — espaces d'un poste sans ligne (= Forge), activation idempotente
      avec matérialisation, retrait avec matérialisation, refus du dernier espace, filtre par
      espace (poste sans ligne compté dans la Forge seulement), `ClientSpace.parse`.

### Tests d'intégration

- [ ] `HostSpacesApiIntegrationTest` — création Vigie absente de la Forge ; activer / retirer ;
      409 dernier espace ; 404 poste d'autrui (aucune ligne) ; 400 espace inconnu ; 403 Vigie
      sans droit Teams (compte non admin) ; `overview?space=VIGIE` sans « Hébergé » et avec
      `spaces` ; mission clôturée lue dans les deux vues ; suppression du poste purge les lignes.
- [ ] `HostSpacesMigrationTest` — le changeset de reprise sur H2 : une ligne `FORGE` par poste
      (rejoué en SQL sur des postes insérés).
- [ ] Radar : `RadarIntegrationTestBase` active la Vigie sur ses postes ; test 409
      `host_not_in_space` après retrait, export et purge toujours servis.
- [ ] Non-régression : `RunnerHostOverviewApiIntegrationTest`, `HostDeletionApiIntegrationTest`,
      suite Radar, `AccountService` (suppression de compte).

### Isolation workspace

- [ ] Alice ne lit, n'active ni ne retire un espace d'un poste de Bob (404, aucune ligne écrite).

---

## Dépendances

### Subfeatures bloquantes

- F-98 (forme maître–détail) — `done` ; F-99 (Radar backend) — `done`.

### Questions ouvertes impactées

- Aucune.

---

## Notes et décisions

- **Refus du dernier espace** : un poste retiré de ses deux espaces deviendrait invisible tout en
  restant facturable (la place compte les postes non clôturés). Réversible : si le PO veut un
  « nulle part », il suffit de lever la garde.
- **Poste sans ligne = Forge** : protège le déploiement progressif (un pod de la version
  précédente crée des postes sans ligne après la migration) et tous les jeux de données de test
  existants, sans double écriture.
- **409 plutôt que 404** pour le Radar hors Vigie : la possession est déjà vérifiée (404 d'abord),
  le 409 dit à l'écran ce qu'il faut faire (activer dans la Vigie), sans oracle sur autrui.
- **Droit Vigie = droit Teams** en attendant F-107, bypass administrateur compris, comme le Radar.
