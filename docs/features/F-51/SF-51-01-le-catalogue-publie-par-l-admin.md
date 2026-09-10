# Mini-spec — F-51 / SF-51-01 — Le catalogue publié par l'admin

## Identifiant

`F-51 / SF-51-01`

## Feature parente

`F-51` — Catalogue de gouvernance

## Statut

`ready`

## Date de création

2026-09-10

## Branche Git

`feat/SF-51-01-catalogue-publie`

---

## Objectif

Donner au produit l'objet **paquet de gouvernance** — des règles, des identifiants de contrôles, des
gabarits et des skills — que **seul l'admin** rédige et publie, et que tout utilisateur connecté peut
lire une fois publié.

---

## Comportement attendu

### Cas nominal

1. L'admin (`ROLE_ADMIN` ou l'e-mail super-admin configuré) crée un paquet : `slug`, `nom`,
   `résumé`, `règles` (texte injecté plus tard dans la consigne système), `contrôles` (liste
   d'identifiants), `fichiers` (chemin + genre `SKILL`/`TEMPLATE` + contenu).
2. Le paquet naît **non publié** en version `1`. Il n'apparaît dans aucun catalogue utilisateur.
3. L'admin le modifie : le contenu est **remplacé intégralement** (fichiers compris) et la
   **version est incrémentée**. Le `slug` est immuable.
4. L'admin le **publie** : `published = true`, `published_at` horodaté. Il apparaît alors dans
   `GET /governance/packages` pour tout utilisateur connecté.
5. L'admin peut le **dépublier** : il disparaît du catalogue. (Les activations existantes ne sont pas
   touchées — décision D6 du cadrage ; elles n'existent qu'à partir de SF-51-02.)
6. Tout utilisateur connecté lit le catalogue publié : identité, résumé, version, **les chemins et
   genres des fichiers** (pas leur contenu) et les libellés des contrôles.

### Validation d'un paquet

| Champ | Règle |
|---|---|
| `slug` | obligatoire, unique, 3 à 64 car., `[a-z0-9-]+`, immuable après création |
| `name` | obligatoire, ≤ 120 car. |
| `summary` | facultatif, ≤ 500 car. |
| `rules` | facultatif, ≤ 8 000 car. |
| `controlIds` | ≤ 20 identifiants, **tous connus du registre serveur**, sans doublon |
| `files` | ≤ 50 fichiers ; chemins **relatifs, sans `..`, sans racine, sans lettre de lecteur**, uniques ; contenu ≤ 64 000 car. ; total ≤ 256 000 car. |
| `files[].kind` | `SKILL` ou `TEMPLATE` |

Un paquet **vide de tout apport** (ni règles, ni contrôles, ni fichiers) est refusé : il n'apporterait
rien et l'écran l'annoncerait comme n'écrivant rien.

### Cas d'erreur

| Situation | Comportement | Code |
|---|---|---|
| Appelant non ADMIN sur `/admin/governance/**` | Accès refusé, message sans détail | 403 |
| Appelant non authentifié | Refus de la chaîne de sécurité | 401 |
| `slug` déjà pris | « Un paquet porte déjà cet identifiant. » | 409 |
| Champ invalide (longueur, motif, chemin, genre) | Message nommant le champ fautif | 400 |
| Identifiant de contrôle inconnu | « Contrôle inconnu : `x`. » — la publication n'invente aucun crochet | 400 |
| Paquet inexistant (`PUT`, `DELETE`, `publish`) | « Paquet introuvable. » | 404 |
| `DELETE` d'un paquet **publié** | « Dépubliez le paquet avant de le supprimer. » | 409 |
| Lecture du catalogue par un utilisateur : paquet non publié | Absent de la réponse (jamais 403 : il n'existe pas pour lui) | 200 |

---

## Critères d'acceptation

- [ ] Un paquet créé par l'admin est stocké avec ses fichiers, en version 1, non publié.
- [ ] `PUT` remplace intégralement le contenu et **incrémente** la version ; le `slug` ne change pas.
- [ ] `POST .../publish` rend le paquet visible de `GET /governance/packages` ; `unpublish` l'en retire.
- [ ] Un utilisateur **non admin** reçoit 403 sur tout `/admin/governance/**`.
- [ ] Un utilisateur connecté lit le catalogue publié **sans** le contenu des fichiers.
- [ ] Un `controlId` absent du registre serveur fait échouer la création en 400.
- [ ] Un chemin de fichier hors du projet (`../x`, `/etc/x`, `C:\x`) fait échouer la création en 400.
- [ ] Deux fichiers de même chemin dans un paquet font échouer la création en 400.
- [ ] Un `slug` déjà pris rend 409.
- [ ] Le registre de contrôles rend la liste des contrôles **du serveur** ; il est vide tant que F-52
      n'en apporte pas, et cela n'empêche pas de publier un paquet sans contrôle.

---

## Périmètre

### Hors scope (explicite)

- La **sélection personnelle** et l'**activation par projet** (SF-51-02).
- L'**aperçu** et le **dépôt** des fichiers (SF-51-03).
- L'injection des règles et le branchement des contrôles (SF-51-04).
- Tout **écran** (SF-51-05 / SF-51-06).
- Toute exécution de code fourni par un tiers : un contrôle reste un composant du serveur.

---

## Impacts

### Tables

| Table | Changement |
|---|---|
| `governance_packages` | **créée** — `id`, `slug`, `name`, `summary`, `rules`, `control_ids`, `version`, `published`, `published_at`, `created_at`, `updated_at` |
| `governance_package_files` | **créée** — `id`, `package_id`, `position`, `path`, `kind`, `content` |

Migration Liquibase `065-governance-packages.xml` (PostgreSQL + H2, chaque changeSet avec son
rollback).

**Isolation `user_id`** : ces deux tables sont un **contenu produit**, comme les plans tarifaires —
elles n'appartiennent à aucun dossier utilisateur et ne portent donc pas `user_id`. Tout ce qui
appartient à un utilisateur (sélection, activation) arrive en SF-51-02 et portera `user_id` sur
chaque lecture. L'écriture est réservée à l'admin ; la lecture publique est bornée aux paquets
**publiés**.

### Endpoints

| Méthode | Chemin | Rôle |
|---|---|---|
| `GET` | `/admin/governance/packages` | ADMIN — tous les paquets, contenus compris |
| `POST` | `/admin/governance/packages` | ADMIN — crée |
| `PUT` | `/admin/governance/packages/{id}` | ADMIN — remplace, version + 1 |
| `POST` | `/admin/governance/packages/{id}/publish` | ADMIN |
| `POST` | `/admin/governance/packages/{id}/unpublish` | ADMIN |
| `DELETE` | `/admin/governance/packages/{id}` | ADMIN — refusé si publié |
| `GET` | `/admin/governance/controls` | ADMIN — contrôles disponibles |
| `GET` | `/governance/packages` | JWT — catalogue **publié**, sans contenu de fichier |

### Composants

- `fr.claudegateway.governance` : `GovernancePackage`, `GovernancePackageFile`,
  `GovernancePackageKind`, repositories, `GovernancePackageService`, `GovernanceAdminController`,
  `GovernanceCatalogController`, `GovernancePath`, `GovernanceControl`,
  `GovernanceControlRegistry`, exceptions + branchement dans `GlobalExceptionHandler`.

---

## Plan de test minimal

### Unitaires

- `GovernancePathTest` : chemins acceptés / refusés (`..`, `/abs`, `C:\`, vide, `.`, backslash).
- `GovernanceControlRegistryTest` : recherche par identifiant, identifiant inconnu, doublon ignoré.
- `GovernancePackageServiceTest` : version incrémentée, slug immuable, refus de paquet vide,
  contrôle inconnu, fichiers en double, bornes de taille.

### Intégration (MockMvc, base H2)

- Création → publication → lecture par un utilisateur non admin (catalogue visible, sans contenu).
- 403 pour un utilisateur non admin sur chaque endpoint d'administration.
- 409 sur slug dupliqué et sur suppression d'un paquet publié.
- 404 sur paquet inexistant.
- 400 sur contrôle inconnu et sur chemin de fichier dangereux.

### Isolation

Le catalogue publié est identique pour tous (contenu produit) ; **aucune donnée utilisateur** n'est
lue ni écrite par cette subfeature. Le test d'isolation porte sur le fait qu'un utilisateur ordinaire
ne peut **rien écrire** (403 sur tout `/admin/governance/**`).

---

## Contraintes de validation

Toutes tranchées dans le tableau « Validation d'un paquet » ci-dessus. Aucune question ouverte de
`docs/OPEN_QUESTIONS.md` n'est impactée.

---

## Préoccupations transversales

| Préoccupation | Touchée ? | Analyse |
|---|---|---|
| Auth / Principal | **oui** (rôle ADMIN) | Aucun changement du Principal ni de la chaîne : réutilisation de la garde existante `AdminService.assertAdmin()` (F-20), déjà appliquée par `/admin/users`. Composants concernés : `AdminService` (aucune modification), nouveau `GovernanceAdminController`. Test de non-régression : `/admin/users` reste 403 pour un non-admin |
| Contexte tenant | non | Aucune table de cette SF ne porte `user_id` ; rien ne résout de tenant |
| Plans / limites | non | Aucun quota, aucun gate |
| Navigation / routing | non | Aucun écran |
