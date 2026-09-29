# Mini-spec — F-162 / SF-162-01 — L'outil `recall(query)`

> Base : `project-governance/templates/subfeature-template.md`.
> Cadrage de référence (fait foi) : `docs/features/F-162/CADRAGE-F-162-rappel-a-la-demande.md`.

---

## Identifiant

`F-162 / SF-162-01`

## Feature parente

`F-162` — Le rappel à la demande de l'historique (se souvenir sans tout rejouer)

## Statut

`ready`

## Date de création

2026-09-29

## Branche Git

`feat/SF-162-01-outil-recall`

---

## Objectif

> En une phrase : que fait cette subfeature ?

Ajouter au catalogue d'outils de la boucle d'agent de l'Atelier un outil **`recall(query)`** qui
recherche en **mot-clé** dans l'historique de la **conversation** du fil courant (`atelier_messages`),
**isolé `user_id` + `workspace_id`**, et rend au modèle **seulement les extraits pertinents bornés**,
afin qu'il retrouve un détail ancien sans qu'on rejoue tout le fil.

---

## Comportement attendu

### Cas nominal

1. Le modèle, dans la boucle principale (`AtelierChatService`), appelle l'outil `recall` avec un
   paramètre `query` (mot-clé). La description de l'outil lui apprend **quand** l'appeler : « quand il
   te manque un détail d'un échange antérieur qui n'est plus dans le contexte courant ».
2. La gateway exécute une recherche **plein-texte simple** (`LOWER(content) LIKE %:terme%`, portable
   H2 + PostgreSQL) sur `atelier_messages`, **filtrée `workspace_id` ET `user_id`**.
3. La recherche porte sur **TOUT le fil (le stock durable)** : elle n'applique **aucune** frontière de
   rejeu / compaction / « Nouveau départ ». Elle retrouve donc même les tours résumés, repliés, ou
   d'avant un « Nouveau départ ».
4. Le résultat est **borné** : au plus **N** extraits (défaut 5), chacun **tronqué** (~600 caractères,
   fenêtré autour de la première occurrence), chacun préfixé d'un **repère** exploitable — le numéro
   de **tour** (nᵉ message `USER` jusqu'à l'extrait) et la **date** — pour que le modèle puisse citer
   « tour 34 ».
5. Le contenu est rendu au modèle comme résultat d'outil (relais), il n'est pas « raisonné » côté
   gateway.

### Cas d'erreur

| Situation | Comportement attendu | Code |
|-----------|---------------------|------|
| `query` absent ou vide | Résultat d'outil en **erreur** : « Requête requise pour recall… » (le modèle se corrige) | résultat `is_error` |
| Aucun message ne matche | Résultat d'outil **neutre** (pas une erreur) : « Aucun extrait trouvé pour « … » » | résultat info |
| Fil d'un autre utilisateur / workspace | **Jamais** remonté : la requête filtre `user_id` + `workspace_id` (isolation stricte) | — |

---

## Critères d'acceptation

- [ ] Un outil **`recall`** est déclaré au modèle dans la boucle principale (schéma : `query` string,
      **obligatoire**), sur les **deux** cibles (runner et sandbox), avec une description disant QUAND
      l'appeler.
- [ ] L'exécution appelle une requête repository **filtrée `workspace_id` ET `user_id`** ; un message
      d'un autre utilisateur OU d'un autre workspace n'est **jamais** remonté (test d'isolation).
- [ ] La recherche **opère sur tout le fil**, indépendamment de la frontière de rejeu/compaction : un
      message **antérieur** à une frontière est remonté par `recall` alors qu'il est exclu par la
      requête de rejeu bornée (test dédié).
- [ ] Le résultat est **borné à N** extraits (défaut 5) et chaque extrait est **tronqué** ; chaque
      extrait porte un **repère** (tour + date).
- [ ] La recherche est **insensible à la casse** (`LOWER(...) LIKE`).
- [ ] `query` vide ⇒ résultat d'erreur ; aucun match ⇒ message neutre « aucun extrait trouvé ».
- [ ] La requête SQL est **portable** : les tests passent en **H2** (profil test) — pas de
      `to_tsvector`.

---

## Périmètre

### Hors scope (explicite)

- **Pas d'embeddings / recherche sémantique** (c'est SF-162-06). Mot-clé uniquement.
- **Pas de résumé de compaction ancré** (SF-162-02).
- **Aucune visibilité UI** — barre de progression / marqueur / indicateur « Recherche dans
  l'historique… » relèvent de **SF-162-03**. Aucun nouvel événement de flux ici.
- **Pas de bouton « compacter maintenant »** (SF-162-04) ni filet utilisateur (SF-162-05).
- **Pas de rappel inter-projets** (autres fils) ; **pas** de « recharger TOUT l'ancien contexte ».
- Aucun moteur IA maison : `recall` = recherche + relais.

---

## Valeurs initiales

Aucune entité créée. Lecture seule sur `atelier_messages`.

| Réglage | Valeur | Règle |
|---------|--------|-------|
| N extraits max | `5` | constante `RECALL_MAX_EXTRACTS` dans `AtelierChatService` |
| Taille d'un extrait | `~600` car. | constante `RECALL_EXTRACT_CHARS` (fenêtre autour de la 1ʳᵉ occurrence) |

> **Décision** : N et la taille sont des **constantes** dans `AtelierChatService`, **pas** dans
> `AtelierProperties`. Motif : le record `@ConfigurationProperties` porte déjà un constructeur canonique
> et plusieurs constructeurs de compatibilité ; y ajouter un champ rouvrirait le piège connu « deux
> constructeurs cassent le contexte Spring ». Externalisation reportée si un besoin de réglage à chaud
> apparaît (via fabrique statique / ajout propre au record).

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format / Valeurs | Normalisation |
|-------|-------------|-------------|------------------|---------------|
| `query` | Oui | — | texte libre non vide | `trim`, `toLowerCase(Locale.ROOT)` pour le `LIKE` |

Notes :
- `query` vide/blanc ⇒ résultat d'outil en erreur (pas d'appel base).
- Le terme est échappé du strict nécessaire pour un `LIKE` (encadré de `%…%`).

---

## Technique

### Endpoint(s)

Aucun endpoint HTTP nouveau. L'outil vit **dans la boucle d'agent**, exécuté côté gateway.

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| `atelier_messages` | SELECT | Recherche `LOWER(content) LIKE` + comptage de tours, toujours filtré `workspace_id` + `user_id` |

### Migration Liquibase

- [x] Non applicable — aucune table ni colonne nouvelle (recherche `LIKE` sur colonne `content`
      existante ; pas d'index plein-texte au jour 1).

### Composants Angular

Aucun (backend seul ; la visibilité UI est SF-162-03).

### Points de câblage (patron des outils existants)

- **Déclaration du schéma** : `AtelierChatService.buildToolsFull(...)` — ajout d'un `AgentTool("recall", …)`
  déclaré sur les deux cibles ; ajout de `"recall"` à la liste blanche `ANSWER_PLAN_TOOLS`
  (outil de lecture, admis en mode Réponse/Plan).
- **Dispatch** : boucle principale (~ligne 1457, à côté de `explore` / radar / email / pages) — branche
  `else if ("recall".equals(call.name()))` avant le routage runner/sandbox, car `recall` opère sur nos
  données, indépendamment de la cible d'exécution.
- **Repère d'étape** : `stepFor(...)` — mapping `recall` → étape « search » (parité minimale ; aucun
  nouvel événement de flux, la visibilité riche est SF-162-03).
- **Repository** : `AtelierMessageRepository` — `@Query` `searchByContent(workspaceId, userId, term, Pageable)`
  (portable H2 + PG) + requête dérivée de comptage de tours filtrée `workspace_id` + `user_id`.

---

## Plan de test

### Tests unitaires (service)

- [ ] `recall` nominal : renvoie les extraits attendus avec repère (tour + date), tronqués.
- [ ] Bornage : plus de N matches ⇒ au plus N extraits.
- [ ] `query` vide ⇒ résultat d'erreur, aucun appel base.
- [ ] Aucun match ⇒ message neutre « aucun extrait trouvé ».
- [ ] Câblage isolation : le service appelle `searchByContent` avec **exactement** le `workspace_id`
      et le `user_id` du tour (ArgumentCaptor).
- [ ] Déclaration : `buildTools` contient `recall` (runner **et** sandbox), `query` requis.

### Tests d'intégration / data-layer (H2 réel)

- [ ] `searchByContent` insensible à la casse trouve un message par mot-clé.
- [ ] Bornage `Pageable` respecté.

### Isolation workspace / utilisateur

- [x] Applicable — data-layer : messages d'Alice/ws1, Alice/ws2 et Bob/ws1 ; `searchByContent(ws1, alice, …)`
      ne remonte **que** ceux d'Alice-ws1 (ni Bob, ni ws2). Idem pour la requête de comptage.
- [x] **Full-fil / compaction-indépendant** : un message antérieur à une frontière est remonté par
      `recall` alors que la requête de rejeu bornée (`findBy…CreatedAtGreaterThanEqual…`) l'exclut.

---

## Dépendances

### Subfeatures bloquantes

- Aucune. SF-162-01 est le cœur et démarre la feature.

### Questions ouvertes impactées

- Aucune (OQ RAG deviennent sans objet ; mot-clé, pas d'embeddings — cohérent ADR-011).

---

## Notes et décisions

- **Gateway-First / Provider-First** : `recall` = recherche + relais sur nos données ; aucun moteur IA,
  aucune dépendance directe à Anthropic. Indépendant de la cible d'exécution (runner/sandbox).
- **Isolation** : le filtre `user_id` + `workspace_id` vit **dans la requête JPQL** — un blocage
  CLAUDE.md serait déclenché sinon. Test d'isolation obligatoire fourni.
- **Full-fil** : `recall` n'utilise **pas** `findBy…CreatedAtGreaterThanEqual…` (la requête de rejeu
  post-frontière) ; il lit le stock durable entier. C'est ce qui rend compaction et « Nouveau départ »
  sûrs (le détail reste rappelable).
- **Piège des constructeurs** : N et taille restent des constantes du service, pas des champs du record
  `@ConfigurationProperties` (voir Valeurs initiales).
