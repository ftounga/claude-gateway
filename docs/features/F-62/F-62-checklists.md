# F-62 — Checklists de gouvernance (readiness / review / release)

Artefacts imposés par `CLAUDE.md` §« Séquence obligatoire par subfeature ».

---

## SF-62-01 — Readiness checklist — **VERDICT : PASS**

### Mini-spec
- [x] `subfeature-template.md` rempli → `SF-62-01-le-code-son-emission-sa-consommation.md`
- [x] Objectif en une phrase
- [x] Comportement nominal décrit (émission, consommation, expiration)
- [x] **9 cas d'erreur** identifiés (≥ 2 requis)
- [x] Critères d'acceptation vérifiables et non ambigus (13)
- [x] Plan de test défini : unitaires + intégration + **isolation `user_id`**
- [x] Hors-scope explicite

### Contraintes de validation
- [x] Section remplie pour tous les champs soumis à une règle (`label`, `assignedEmail`, `code`, `code_hash`)
- [x] Contraintes structurantes tranchées : durée **24 h**, validité du code non consommé **30 j**,
      grâce de tour **15 min**, alphabet et format du code, hachage SHA-256 — toutes en configuration
      réversible, aucune n'est un secret
- [x] Aucun critère indéterminé

### Architecture & dépendances
- [x] Table impactée : `access_codes` (nouvelle) ; `subscriptions` **en lecture seule**
- [x] Endpoints listés (4)
- [x] Dépendances : F-40 (done), F-20 (done)
- [x] Aucune question ouverte de `docs/OPEN_QUESTIONS.md` impactée
- [x] Cohérent `ARCHITECTURE_CANONIQUE.md` : nouvelle table → à documenter en étape 6
- [x] **Gateway-First** : aucune logique de moteur IA · **Provider Independence** : aucun couplage
      Anthropic · **Provider-First** : rien de ce que Claude fournit n'est réimplémenté
- [x] **Hors périmètre V3** : ni F-17 ni F-18 ni multi-LLM runtime

### Préoccupations transversales (règle anti-régression `CLAUDE.md`)
- [x] **Plans / limites — COCHÉE.** Composants impactés listés et vérifiés un à un :
  | Composant | Impact | Vérification |
  |---|---|---|
  | `AtelierEntitlementService` | **modifié** — le droit s'ouvre aussi par un octroi en cours | tests F-40 existants conservés verts + nouveaux cas |
  | `AtelierAccessService` | **inchangé** — consomme `isEntitled`, hérite du nouveau cas | test d'intégration Forge |
  | `AtelierOptionService` | **inchangé** — `describe()` reflétera `entitled=true` pendant l'octroi | test |
  | `EntitlementService` (quota) | **NON modifié** (décision D2) | test explicite : quota identique avant/pendant/après |
  | `QuotaService` | **NON modifié** | aucune dépendance ajoutée |
  | `SubscriptionService` / webhook Stripe | **NON modifiés**, lecture seule | test : `GET /billing/subscription` inchangé après consommation |
- [x] **Auth / Principal** : non cochée — aucun changement du principal ni du mode d'auth
- [x] **Contexte tenant** : non cochée — `user_id` résolu comme partout via `CurrentUser`
- [x] **Navigation / routing** : non cochée pour SF-62-01 (backend)

### Migration base de données
- [x] Migration planifiée : `067-access-codes.xml` (numéro libre suivant : dernier = 066)
- [x] Nommage conforme `{NNN}-{description}.xml`
- [x] Réversible : chaque changeSet porte son `<rollback>`

### Branche Git
- [x] `feat/SF-62-01-code-acces-24h` créée depuis `main` à jour (worktree isolé)
- [x] Aucune autre subfeature mélangée

### Compréhension
- [x] Coding rules et definition of done connues
- [x] En une phrase : **un code émis par l'admin ouvre la Forge 24 h à qui le saisit, sans jamais
      toucher son abonnement ; l'accès se referme tout seul, parce qu'expirer est une comparaison,
      pas un événement.**

---

## SF-62-02 — Readiness checklist — **VERDICT : PASS**

- [x] Mini-spec produite, objectif en une phrase, nominal + **7 cas d'erreur**, 8 critères
      d'acceptation vérifiables, hors-scope explicite
- [x] Plan de test : tests de composant (succès, chaque refus, section masquée, échec non bloquant)
- [x] Contraintes de validation remplies (`code`)
- [x] **Écran produit** : conforme `DESIGN_SYSTEM.md` (jetons `--cg-*`, Material déjà employé) ;
      n'annule ni la passe F-56 ni l'identité SF-49-03
- [x] Préoccupation **Navigation / routing** : non cochée — aucune route ajoutée ni modifiée, la
      section vit dans `/billing` existante
- [x] Dépendance bloquante `SF-62-01` : mergée avant démarrage
- [x] Aucune migration
- [x] Branche `feat/SF-62-02-saisie-code-ecran-plan` depuis `main` à jour

---

## SF-62-03 — Readiness checklist — **VERDICT : PASS**

- [x] Mini-spec produite, objectif en une phrase, nominal + **3 cas d'erreur**, 7 critères
      d'acceptation vérifiables, hors-scope explicite
- [x] Plan de test : tests de composant (liste, création, échecs)
- [x] Contraintes de validation remplies (`label`, `assignedEmail`)
- [x] **Écran produit** : conforme `DESIGN_SYSTEM.md`, aligné sur la section Gouvernance de `/admin`
- [x] Préoccupation **Navigation / routing** : non cochée — la section s'ajoute dans `/admin`, aucune
      route créée (arbitrage D9)
- [x] Dépendance bloquante `SF-62-01` : mergée avant démarrage
- [x] Aucune migration
- [x] Branche `feat/SF-62-03-console-codes-acces` depuis `main` à jour

---

## SF-62-01 — Review checklist — **VERDICT : PASS** (aucun bloquant rouge)

### Prérequis
- [x] Mini-spec lue avant le code
- [x] Template PR rempli (corps de la PR)
- [x] Branche à jour avec `main` (créée depuis `origin/main` = 452cfa5)

### Sécurité — BLOQUANT
- [x] **Isolation utilisateur** : toute lecture d'un droit passe par
      `findFirstByRedeemedByUserIdOrderByGrantedUntilDesc(userId)`, avec le `user_id` du
      `SecurityContext` ; il n'existe **aucune** méthode de repository lisant un droit sans le
      nommer. `AccessGrantService` refuse même de requêter avec un `userId` nul (test dédié).
- [x] **Aucune donnée sensible en clair** : le code est stocké en SHA-256, renvoyé une seule fois à
      l'émission ; **aucun log ne contient le code** (les traces nomment l'`id` de la ligne). Test :
      la réponse de la liste admin ne contient pas le code.
- [x] **Rôles** : émission et liste passent par `AdminService.assertAdmin()` — la garde unique du
      produit, jamais une seconde définition. Tests 403 (USER) et 401 (anonyme).
- [x] **Aucune stacktrace exposée** : les cinq refus passent par `GlobalExceptionHandler` et rendent
      un `ErrorResponse` typé.

### Cohérence mini-spec — BLOQUANT
- [x] Le code correspond à la mini-spec : surcouche, hachage, usage unique atomique, grâce de tour.
- [x] Les 13 critères d'acceptation sont couverts par des tests nommés.
- [x] Hors périmètre respecté : aucun cumul (refusé explicitement), aucune remise, aucune révocation,
      aucune écriture dans `subscriptions`, aucun appel Stripe, aucune saisie à la connexion.

### Tests — BLOQUANT
- [x] Unitaires : `AccessGrantServiceTest` (7), `AccessCodeServiceTest` (13),
      `AccessCodeSecretTest` (5), `AtelierEntitlementServiceTest` (+4 cas F-62).
- [x] Intégration : `AccessCodeApiIntegrationTest` (10) sur les 4 endpoints.
- [x] **Isolation testée** : `eachUserOnlySeesTheirOwnGrant` (Bob ne voit pas le droit d'Alice).
- [x] **Tous les tests passent** : `1883 tests, 0 failure, 0 error` sur l'ensemble du backend.
- [x] Cas d'erreur du plan de test couverts : inconnu, déjà utilisé, périmé, nominatif, cumul,
      libellé vide, course perdue (0 ligne modifiée), 403, 401.

### Architecture — BLOQUANT
- [x] Aucune logique métier dans les controllers (`AccessCodeController` et
      `AccessCodeAdminController` délèguent) ni dans l'entité.
- [x] Migration Liquibase présente : `067-access-codes.xml`, PostgreSQL + H2, chaque changeSet avec
      son `<rollback>`. Rejouée à blanc par les tests d'intégration (H2 propre à chaque contexte).
- [x] Aucun traitement IA synchrone ajouté ; aucun couplage Anthropic (Provider Independence
      intacte, le paquet n'a aucune dépendance `ai`).
- [x] Build vert (`./mvnw test`).

### Design System
- [ ] **Sans objet** — SF-62-01 est un lot backend, aucun composant frontend.

### Qualité — non bloquant
- [x] Nommage conforme, DTO requête/réponse distincts (`IssueAccessCodeRequest` /
      `IssuedAccessCodeResponse`, `RedeemAccessCodeRequest` / `AccessGrantResponse`).
- [x] La logique non évidente est commentée là où elle l'est vraiment : pourquoi un `UPDATE`
      conditionnel plutôt qu'un `if`, pourquoi une grâce, pourquoi aucun job.
- [x] Aucun warning de compilation.

### Documentation — non bloquant
- [x] `docs/ARCHITECTURE_CANONIQUE.md` mis à jour (nouvelle table `access_codes` + isolation).
- [x] Aucune question ouverte tranchée → `OPEN_QUESTIONS.md` inchangé.
- [x] Décisions D1→D6 tracées dans `F-62-cadrage.md` et la mini-spec ; pas d'ADR distinct (aucune
      décision d'architecture transverse — la règle reste locale au droit d'Atelier).

**Aucun item bloquant rouge → push autorisé.**

---

## SF-62-01 — Release checklist — **VERDICT : PASS**

### Validation technique
- [x] Review passée ci-dessus, aucun bloquant rouge
- [x] Build + tous les tests verts : `1883 tests, 0 failure, 0 error`
- [x] Aucun conflit avec `main` (branche créée depuis `origin/main` à jour, aucun commit entrant)
- [x] Branche à jour avec `main`

### Definition of Done
- [x] Mini-spec respectée intégralement
- [x] Tous les critères d'acceptation validés par un test nommé

### Base de données
- [x] Migration `067-access-codes.xml` rejouée sur base propre (H2 neuve à chaque contexte de test
      d'intégration) — 10 tests d'intégration verts
- [x] Ne casse aucune donnée existante : **création pure**, aucune table existante modifiée, aucune
      colonne supprimée ou renommée
- [x] Index en place : unicité sur `code_hash`, index `(redeemed_by_user_id, granted_until)` pour la
      lecture d'isolation. Pas de FK, cohérent avec le reste du schéma (les tables portent des
      `user_id` non contraints — choix existant du projet, non rejoué ici)

### Sécurité
- [x] Aucun secret dans le diff (aucune clé, aucun token ; le code d'accès n'est jamais persisté en
      clair)
- [x] Isolation garantie sur les nouvelles routes : `/access-code/grant` et `/access-code/redeem`
      prennent l'identité du JWT ; `/admin/access-codes` est gardée par `assertAdmin()`

### Documentation
- [x] Contrat API des 4 endpoints décrit dans la mini-spec
- [x] `ARCHITECTURE_CANONIQUE.md` mis à jour (table créée)
- [x] `OPEN_QUESTIONS.md` : sans objet
- [x] ADR : sans objet

### Post-merge
- [x] CI post-merge à vérifier sur `main`
- [x] Débloque **SF-62-02** et **SF-62-03** (contrat d'API figé)
- [x] Statut F-62 mis à jour une fois les trois subfeatures livrées
