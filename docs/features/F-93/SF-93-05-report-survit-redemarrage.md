# Mini-spec — [F-93 / SF-93-05] Le report de promotion survit à un redémarrage

---

## Identifiant

`F-93 / SF-93-05`

## Feature parente

`F-93` — La promotion a une destination (SF-93-01→04)

## Statut

`in-progress`

## Date de création

2026-09-14

## Branche Git

`feat/SF-93-05-report-survit-redemarrage`

---

## Objectif

Persister le registre des promotions reportées faute de poste (SF-93-04), aujourd'hui en mémoire du
processus, dans une table dédiée par `user_id` + `host_id` + projet, pour que la dette reportée soit
réclamée au premier tour où le runner répond, **quel que soit le pod et après un redémarrage**.

---

## Contexte

SF-93-04 a livré `PromotionReportee`, un registre **en mémoire du processus** (même choix assumé que
`IntegriteMemo` / `JugeMemo`) : quand la machine est hors ligne, un contrôle de fin de tour reporte la
promotion au lieu de refuser trois fois la clôture, et la réclame au premier tour où le poste répond.
Le prix assumé, écrit noir sur blanc dans le hors-scope de SF-93-04 : *« un redémarrage ou un autre
pod l'oublie »*. En production (plusieurs pods derrière l'équilibreur, redéploiements fréquents), ce
rappel nominatif est perdu la plupart du temps ; seule la dette physique (`STATE.md`) survit et est
recomptée par le marqueur — mais le lien « ce que tu n'as pas pu ranger, le voici » disparaît.

SF-93-05 remplace la mémoire du processus par une **persistance** : la sémantique de SF-93-04 (cumul,
durée de vie 7 j, bornes, réclamation **une fois**, isolation) est **conservée à l'identique** ; seul
le support change. La clé gagne le `host_id` — le report est fondamentalement l'affaire d'un **poste**
hors ligne.

---

## Comportement attendu

### Cas nominal

1. **Report posé (poste hors ligne)** — `JugeFinDeTourControl` / `PromotionDetteBloquanteControl`
   appellent `PromotionReportee.reporter(userId, hostId, workspaceId, éléments, dette)`. La ligne est
   écrite (ou fusionnée) en base, clé unique `(user_id, host_id, workspace_id)`.
2. **Redémarrage / autre pod** — la ligne survit : elle est en base, pas en mémoire.
3. **Poste revenu (`REACHED`) et report dû** — le premier des deux contrôles actifs appelle
   `reclamer(userId, hostId, workspaceId)` : la dette est rendue (éléments, dette, date), la
   réclamation est affichée **une seule fois**, puis la ligne est **supprimée** — de façon atomique,
   pour que deux pods concurrents ne réclament jamais deux fois.
4. **Cumul** — des reports successifs du même `(user, host, projet)` fusionnent : éléments sans
   doublon (bornés), dette la plus haute, date du premier report — comportement inchangé.
5. **Durée de vie / bornes** — un report plus vieux que 7 jours est purgé ; au-delà de 500 entrées, la
   moins récente sort — comportement inchangé.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| `userId`, `hostId` ou `workspaceId` null | Aucun report écrit, aucune réclamation (silencieux) | — |
| Poste revenu mais aucun report dû | Aucune réclamation, contrôles ordinaires | — |
| Deux pods réclament le même report simultanément | Un seul obtient la ligne (verrou), l'autre ne réclame rien | — |
| Deux reports concurrents du même triple | La fusion converge, aucune exception ne remonte à la boucle | — |
| Report d'un autre `(user, host, projet)` | Jamais réclamé ici : clé complète | — |
| Workspace supprimé | La ligne part avec lui (FK `ON DELETE CASCADE`) | — |

---

## Critères d'acceptation

- [ ] CA1 — Un report posé (poste hors ligne, promotion déclarée) est écrit en base ; une nouvelle
  instance du registre sur la même base (simulant un redémarrage / un autre pod) le retrouve.
- [ ] CA2 — Après « redémarrage », le premier tour où le poste répond réclame la dette **une seule
  fois** (la ligne est supprimée), et le tour suivant ne la réclame plus.
- [ ] CA3 — Deux réclamations concurrentes du même report n'en aboutissent qu'à une (claim-once).
- [ ] CA4 — Isolation : un report de `(A, host1, projet1)` n'est réclamé ni pour `(B, host1,
  projet1)`, ni pour `(A, host1, projet2)`, ni pour `(A, host2, projet1)`.
- [ ] CA5 — Cumul conservé : deux reports du même triple fusionnent (éléments sans doublon, dette
  maximale, premier report).
- [ ] CA6 — Durée de vie 7 j et bornes (500 entrées, 10 éléments) conservées.
- [ ] CA7 — Les unités et l'intégration existantes de SF-93-04 (offline → reporté, revenu → réclamé
  une fois, inconnu → rien, isolation) restent vertes avec la nouvelle clé à trois composants.

---

## Périmètre

### Hors scope (explicite)

- Toute modification de la logique de décision des contrôles (offline → reporté, marqueur exigé, juge
  indépendant / intégrité qui ne lisent pas la machine) : inchangée.
- Sonde active de connectivité du runner : toujours l'état observé du tour.
- Affichage dédié à l'écran : la mention et la réclamation voyagent dans la réponse, comme en
  SF-93-04.
- `MAX_END_OF_TURN_BLOCKS`, marqueur, règles F-52/F-93 poste joignable : inchangés.

---

## Valeurs initiales

| Champ | Valeur initiale | Règle |
|-------|----------------|-------|
| id | UUID | généré par Hibernate à l'insertion |
| reported_at | now (UTC) | date du **premier** report du triple, conservée au cumul |
| dette | ≥ 0 | max des dettes reportées |

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format / Valeurs autorisées | Unicité | Normalisation |
|-------|-------------|-------------|----------------------------|---------|---------------|
| user_id | Oui | — | UUID | (triple) | — |
| host_id | Oui | — | UUID | (triple) | — |
| workspace_id | Oui | — | UUID | (triple) | — |
| elements | Non | 10 éléments, 300 car. cités | texte du marqueur, à plat | par triple | trim, sans doublon, sans saut de ligne |
| dette | Non | — | entier ≥ 0 | par triple | max des reports |

Unicité : contrainte unique `(user_id, host_id, workspace_id)`.

---

## Technique

### Endpoint(s)

Aucun.

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| `promotion_reportee` (nouvelle) | INSERT / SELECT / DELETE | clé unique `(user_id, host_id, workspace_id)` ; FK `workspace_id → workspaces(id)` `ON DELETE CASCADE` |

### Migration Liquibase

- [x] Oui — `106-promotion-reportee.xml` (premier numéro libre : 105 pris par SF-112-03 au rebase).
- Réversible : `rollback` = `dropTable`.

### Composants

- `governance/control` : `PromotionReportee` (délègue désormais à un `PromotionReporteeStore` ; API
  gagne `hostId`), `PromotionReporteeStore` (interface), `InMemoryPromotionReporteeStore` (mémoire,
  tests + repli), `JpaPromotionReporteeStore` (persistance, `@Primary`), `PromotionReporteeEntity`,
  `PromotionReporteeRepository`.
- `atelier/checkpoint` : `AtelierCheckpointContext` gagne un composant `hostId` (nullable ; fabriques
  d'écriture/commande à `null`, fin de tour le porte).
- `atelier/AtelierChatService` : passe `workspace.getHostId()` au contexte de fin de tour.
- `governance/control` : `JugeFinDeTourControl`, `PromotionDetteBloquanteControl` utilisent
  `context.hostId()`.

### Préoccupations transversales

- [ ] Auth / Principal — non.
- [x] Contexte tenant — **oui, analysé** : la clé passe de `(userId, workspaceId)` à `(userId,
  hostId, workspaceId)`. Composants qui résolvent / portent le tenant impactés et vérifiés :
  - `AtelierCheckpointContext` (porte désormais `hostId`, nullable, rétro-compatible) ;
  - `AtelierChatService.runLoop` (seul producteur du contexte de fin de tour ; `hostId =
    workspace.getHostId()`, `workspace` déjà `requireOwned`) ;
  - `JugeFinDeTourControl`, `PromotionDetteBloquanteControl` (seuls appelants de
    `reporter`/`reclamer`/`estDue`) ;
  - `PromotionReportee` et ses stores (mémoire + JPA) : filtre sur le triple complet, jamais partiel.
  Aucun autre composant ne lit ce registre. `user_id` reste porté partout et le report n'est jamais
  réclamé hors de son triple (tests CA4).
- [ ] Plans / limites — non.
- [ ] Navigation / routing — non.

---

## Plan de test

### Tests unitaires

- [ ] `PromotionReporteeTest` (in-memory store) — report/réclamation une fois ; cumul ; isolation sur
  le triple (user, host, projet) ; durée de vie ; bornes ; réclamation nomme quoi/où.
- [ ] `PromotionReporteeStoreJpaTest` (`@DataJpaTest`) — report écrit et relu par une **nouvelle**
  instance du store sur la même base (survit au redémarrage) ; réclamation une fois puis vide ;
  isolation sur le triple ; cumul ; purge par durée de vie ; borne d'entrées.
- [ ] `OfflineEndOfTurnControlsTest` — mis à jour pour la clé à trois composants (comportement
  inchangé).

### Tests d'intégration

- [ ] Contexte Spring : `JpaPromotionReporteeStore` et `PromotionReportee` sont des beans (le contexte
  démarre) — couvert par la suite d'intégration existante + le `@DataJpaTest`.
- [ ] Boucle `AtelierChatServiceEndOfTurnCheckpointTest` : inchangée (le contexte porte `hostId`
  null quand le workspace n'en a pas), reste verte.

### Isolation workspace

- [x] Applicable — le report est rangé et réclamé sous `(userId, hostId, workspaceId)` ; tests CA4 en
  mémoire et JPA.

---

## Dépendances

### Subfeatures bloquantes

SF-93-04 (Done).

### Questions ouvertes impactées

Aucune.

---

## Notes et décisions

- **D1 — Sémantique conservée, support changé** : la fusion, la durée de vie (7 j), les bornes (500
  entrées, 10 éléments), la réclamation une fois et l'isolation sont celles de SF-93-04 ; SF-93-05 ne
  fait que déplacer le registre en base et ajouter `host_id` à la clé.
- **D2 — `host_id` dans la clé** : le report est l'affaire d'un **poste** hors ligne. Un projet
  appartient à un poste, mais nommer le poste dans la clé rend le rappel exact et suit le cadrage.
  Le `host_id` vient du `workspace` déjà possédé, porté par le contexte de fin de tour.
- **D3 — Store abstrait** : `PromotionReportee` délègue à un `PromotionReporteeStore`. L'implémentation
  mémoire garde les tests unitaires rapides et sert de repli ; l'implémentation JPA (`@Primary`) est
  le bean de production. Les helpers de fusion/bornes sont partagés (statiques) pour éviter la
  divergence.
- **D4 — Claim-once multi-pods** : `reclamer` lit la ligne sous **verrou pessimiste** puis la
  supprime dans la même transaction ; un second pod attend le commit, puis ne trouve plus rien. H2 et
  PostgreSQL supportent `SELECT … FOR UPDATE`.
- **D5 — Stockage à plat des éléments** : liste courte, bornée, lue en bloc, jamais interrogée par
  élément — un `text` à plat (comme `GovernancePackage.control_ids`), séparateur saut de ligne, les
  sauts internes remplacés par une espace à l'écriture. Pas de table de jointure.
