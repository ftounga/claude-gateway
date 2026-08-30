# Mini-spec — F-38 / SF-38-14 — La suppression d'un compte efface aussi son runner

## Identifiant
`F-38 / SF-38-14`

## Feature parente
`F-38` — Exécution sur machine connectée (runner local)

## Statut
`ready`

## Date de création
2026-08-30

## Branche Git
`feat/SF-38-14-purge-runner-suppression-compte`

---

## Objectif

> Quand un utilisateur supprime son compte, **toutes** ses données du domaine runner disparaissent
> (jetons, codes d'appairage, journal d'audit), et un jeton runner déjà émis **cesse immédiatement
> d'authentifier** — même s'il n'a été ni révoqué ni expiré.

---

## Le défaut constaté (passe de vérification du 2026-08-30)

`AccountService.deleteAccount` purge messages, conversations, fichiers, compteurs d'usage,
abonnement, clés API, jeton GitHub et templates. Il ne touche **ni `runner_tokens`, ni
`runner_pairing_codes`, ni `runner_audit`**. Le projet n'a **aucune clé étrangère vers `users`** dans
tout le changelog Liquibase (`referencedTableName="users"` : 0 occurrence), donc rien ne tombe en
cascade : toute purge est explicite, et celle-ci manque.

Deux conséquences, de gravité différente :

1. **Un jeton runner survit à son propriétaire.** `RunnerTokenAuthenticator.authenticate` ne filtre
   que sur `token.isValidAt(now)` — révocation et expiration. Rien ne vérifie que l'utilisateur
   existe encore. Un runner appairé continue donc de s'authentifier et d'ouvrir son canal **jusqu'à
   30 jours** (TTL du jeton) après la suppression du compte. Il ne peut rien exécuter — plus aucune
   session ne peut le piloter — mais des connexions authentifiées persistent au nom d'un compte effacé.
2. **Le journal d'audit survit au compte.** `runner_audit` conserve des données personnelles
   (chemins de fichiers lus, commandes exécutées) sans limite de durée après la suppression.

---

## Comportement attendu

### Cas nominal
1. `DELETE /account` : dans la **même transaction** que le reste de la purge, les lignes
   `runner_tokens`, `runner_pairing_codes` et `runner_audit` de cet `user_id` sont supprimées.
2. Un jeton de cet utilisateur présenté après la suppression est **refusé** (`401`), qu'il arrive par
   le handshake WebSocket ou par le repli long-polling.
3. Un runner encore connecté au moment de la suppression voit son canal se fermer au plus tard à sa
   prochaine trame authentifiée ; le registre ne le référence plus.
4. La suppression reste **tout ou rien** : une erreur sur une des purges annule l'ensemble.

### Cas d'erreur

| Situation | Comportement attendu |
|---|---|
| Aucun jeton / code / ligne d'audit pour cet utilisateur | Suppression sans effet, aucune erreur |
| Jeton d'un **autre** utilisateur | Intact — la purge est filtrée par `user_id` |
| Jeton présenté pendant la transaction de suppression | Refusé après commit ; jamais d'état intermédiaire visible (transaction) |
| Utilisateur absent (course entre deux suppressions) | Comportement actuel conservé : erreur propre, pas de purge partielle |
| Runner connecté au moment de la suppression | Canal fermé, pas de boucle de reconnexion authentifiée |

---

## Critères d'acceptation

- [ ] `deleteAccount` supprime `runner_tokens`, `runner_pairing_codes` et `runner_audit` de l'`user_id`,
      dans la transaction existante, **avant** la suppression de l'utilisateur.
- [ ] `RunnerTokenAuthenticator` refuse un jeton dont l'utilisateur n'existe plus (défense en
      profondeur : la purge ne suffit pas — un jeton peut être présenté avant que la purge existe,
      ou après une restauration partielle de sauvegarde).
- [ ] Le refus est **générique** (`401`, pas de corps distinguant « compte supprimé » de « jeton
      invalide ») : rien ne doit permettre d'énumérer les comptes supprimés.
- [ ] Les deux portes d'authentification runner sont couvertes : handshake WebSocket **et** endpoints
      de repli long-polling.
- [ ] Isolation : la purge d'un compte ne touche aucune donnée d'un autre `user_id` (testé).
- [ ] Le test existant `deleteAccountRemovesAllRelatedDataThenTheUser` est étendu aux trois
      repositories du domaine runner, et échoue si l'un d'eux est oublié.
- [ ] Suites vertes : backend (1101 au départ), runner (142, non concerné).

---

## Périmètre

### Hors scope (explicite, avec la raison)

- **Les `workspaces` de l'Atelier.** Le même trou existe pour eux — `deleteAccount` ne les supprime
  pas — mais il est **antérieur à F-38** et déborde largement : sessions, tours, transcriptions,
  fichiers du workspace dans S3, et la question du coût de suppression du stockage. C'est une
  subfeature de **F-28**, à ouvrir séparément ; l'inclure ici ferait dépasser les 2 jours et
  mélangerait deux périmètres. **À signaler au PO** — cette mini-spec ne la referme pas.
- **L'export RGPD** (`AccountExport`) : le journal d'audit runner est une donnée personnelle et
  devrait sans doute y figurer, mais l'export est un sujet distinct (F-24) avec son propre format.
- La **rétention** du journal d'audit pour les comptes vivants (purge par ancienneté) : dette déjà
  recensée en SF-38-08, sans rapport avec la suppression de compte.
- Toute révocation côté runner lui-même : le binaire découvre le refus à sa prochaine tentative,
  c'est le comportement déjà livré en SF-38-03.

---

## Technique

### Fichiers impactés

| Fichier | Nature |
|---|---|
| `account/AccountService.java` | trois suppressions de plus dans `deleteAccount` |
| `runner/RunnerTokenRepository.java` | `deleteByUserId` |
| `runner/RunnerPairingCodeRepository.java` | `deleteByUserId` |
| `runner/audit/RunnerAuditRepository.java` | `deleteByUserId` |
| `runner/RunnerTokenAuthenticator.java` | contrôle d'existence de l'utilisateur |
| `account/AccountServiceTest.java` | extension du test de purge |

### Tables impactées
`runner_tokens`, `runner_pairing_codes`, `runner_audit` — **aucune modification de schéma**.

### Migration Liquibase
- [x] Non applicable

### Composants Angular
Aucun.

---

## Plan de test

### Tests unitaires
- [ ] `deleteAccount` appelle les trois `deleteByUserId` du domaine runner, dans la transaction.
- [ ] `RunnerTokenAuthenticator` : jeton valide + utilisateur présent → identité ; jeton valide +
      utilisateur absent → vide ; jeton révoqué ou expiré → vide (non-régression).

### Tests d'intégration
- [ ] Après `DELETE /account`, un jeton de ce compte est refusé au **handshake WebSocket** (`401`).
- [ ] Après `DELETE /account`, le même jeton est refusé sur les **endpoints de repli** (`POST /runner/poll`,
      `/runner/send`, `/runner/disconnect`), avec la même réponse générique.
- [ ] Après `DELETE /account`, plus aucune ligne `runner_tokens` / `runner_pairing_codes` /
      `runner_audit` pour cet `user_id`.
- [ ] Non-régression : `/me`, `/workspaces` et `POST /runner/pair` se comportent comme avant.

### Isolation
- [x] **Applicable** : deux utilisateurs, chacun avec jetons, codes et lignes d'audit ; la suppression
      de l'un laisse ceux de l'autre strictement intacts, et le jeton de l'autre continue d'authentifier.

---

## Dépendances

### Subfeatures bloquantes
`SF-38-01` (jetons), `SF-38-08` (audit), `SF-38-09` (endpoints de repli) — toutes **done**.

### Questions ouvertes impactées
Aucune de `docs/OPEN_QUESTIONS.md`.

---

## Préoccupation transversale — Auth / Principal

**Cochée** : le contrôle d'existence de l'utilisateur modifie le résultat de l'authentification par
jeton runner. Composants impactés, vérifiés par recherche sur `RunnerTokenAuthenticator` :

| Composant | Impact | Vérification |
|---|---|---|
| `RunnerTokenAuthenticator` | **modifié** — une condition de plus | tests unitaires ci-dessus |
| `RunnerHandshakeInterceptor` (WebSocket, SF-38-02) | consommateur — comportement inchangé pour un utilisateur vivant | test d'intégration handshake |
| `RunnerPollController` (repli, SF-38-09 — 3 appels) | consommateur — idem | test d'intégration sur les 3 endpoints |
| `RunnerHeartbeatService` | consommateur — idem | couvert par les tests existants |
| `SecurityConfig` (chaîne principale) | **non touché** | non-régression `/me`, `/workspaces` |
| `RunnerSecurityConfig` / `RelaySecurityConfig` | **non touchées** — aucune route ni matcher modifié | suites existantes |

Aucun nouveau type de porteur, aucun `AuthenticatedUser` posé dans le `SecurityContext` : la
discipline D9 (chaîne runner isolée de la chaîne utilisateur) reste intacte.
