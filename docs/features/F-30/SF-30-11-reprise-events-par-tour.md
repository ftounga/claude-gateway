# Mini-spec — [F-30 / SF-30-11] Le second message d'une session rejouait la réponse du premier

---

## Identifiant

`F-30 / SF-30-11`

## Feature parente

`F-30` — Atelier, expérience terminal. Correctif d'une régression introduite par **SF-30-04**
(session persistante entre les messages).

## Statut

`done` — livrée le 2026-08-29 (PR #186)

## Date de création

2026-08-29

## Branche Git

`fix/SF-30-11-reprise-events-par-tour`

---

## Objectif

> En une phrase : que fait cette subfeature ?

Faire qu'un tour ne lise que **ses propres** events, au lieu de relire la session depuis le début et
de rejouer la réponse du tour précédent sans jamais exécuter la nouvelle demande.

---

## Contexte

### Constat de production (2026-08-29, mesuré)

Sur le projet `scrm`, trois demandes successives — une description, puis deux « lance un `clean
install` » — ont produit en base **trois réponses identiques au caractère près** (4220 caractères,
même texte « Je vais explorer le dépôt pour comprendre. Voici ce que fait cette application… ») :

| `created_at` | rôle | contenu |
|---|---|---|
| 18:13:37 | USER | *(demande de description)* |
| 18:13:37 | ASSISTANT | « Je vais explorer le dépôt… » — **4220** car. |
| 18:16:22 | USER | « Lance un build maven clean install. Skip les test » |
| 18:16:22 | ASSISTANT | **le même texte** — **4220** car. |
| 18:16:49 | USER | « Lance une commande clean install. Mais skip les test » |
| 18:16:49 | ASSISTANT | **le même texte** — **4220** car. |

Aucune commande n'a été exécutée. Vu de l'utilisateur : « il ne lance rien, on dirait que le terminal
ne répond même pas ».

### Cause racine

`AnthropicManagedAgentProvider.awaitCompletion()` (lignes 386–408) :

```java
StringBuilder reply = new StringBuilder();
Set<String> seen = new HashSet<>();          // ← vide à CHAQUE tour
…
String cursor = null;                        // ← relit depuis la 1re page
```

La déduplication par `seen` est **locale à l'appel**. Elle protège les itérations de polling d'un
**même** run — c'est d'ailleurs ce que dit le commentaire (« event déjà traité lors d'un tour
précédent », entendu comme *tour de polling*). Mais depuis **SF-30-04**, la session **survit d'un
message à l'autre** : au deuxième message, la session contient déjà tous les events du premier.

Le tour 2 relit donc la page 0, réémet l'`agent.message` du tour 1 dans le flux SSE (d'où la réponse
identique), puis rencontre l'event de fin du tour 1 — `session.status_idle` non `requires_action` —
et **conclut que le run est terminé**. Le nouveau message vient à peine d'être posté ; l'agent n'a
rien eu le temps de faire, et son travail réel n'est jamais lu.

Le défaut ne pouvait se voir qu'à partir du **deuxième** message d'une même session : tant que la
sandbox mourait à chaque tour (avant SF-30-04), chaque run repartait d'une session vierge et la
relecture depuis la page 0 était correcte.

### Ce que le fournisseur offre pour s'en sortir

Vérifié contre l'API le 2026-08-29 — `POST /v1/sessions/{id}/events` **renvoie l'event créé, avec son
identifiant** :

```json
{"data":[{"id":"sevt_01AQjL6jT6SS6CFxngLkBNMj","type":"user.message",
          "content":[{"text":"dis bonjour","type":"text"}]}]}
```

Et chaque event listé porte `id` et `processed_at`, dans l'ordre chronologique de la page.

---

## Comportement attendu

### Cas nominal

1. Le backend poste le message de l'utilisateur et **retient l'identifiant** de l'event `user.message`
   ainsi créé.
2. `awaitCompletion` parcourt les events et **ignore tout ce qui précède cet identifiant** : ni texte
   réémis, ni action rejouée, ni fin de run héritée du tour précédent.
3. À partir de cet event, le tour se déroule comme aujourd'hui : texte, actions, sorties, diffs, coût.
4. Le deuxième message d'une session produit donc **sa propre** réponse, et les commandes demandées
   s'exécutent réellement.
5. Un troisième message repart du sien, et ainsi de suite.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Le fournisseur ne renvoie pas d'identifiant d'event à la publication | **Repli sûr** : le provider relit immédiatement les events et retient le **dernier `user.message`** de la session — celui qui vient d'être posté. Jamais de retour au comportement « relire depuis le début », qui est le défaut corrigé |
| L'identifiant retenu n'apparaît jamais dans les pages lues (event purgé, session recréée) | Le tour n'invente rien : il attend la fin normale ou le délai, et échoue franchement en `timeout` plutôt que de rejouer un ancien tour |
| Session **neuve** (premier message) | Aucun event antérieur : comportement strictement identique à aujourd'hui — non-régression testée |
| Deux onglets postent chacun un message | Chaque tour suit **son** identifiant ; aucun ne s'attribue la réponse de l'autre |

---

## Critères d'acceptation

- [ ] Le deuxième message d'une session ne réémet **aucun** texte du premier
- [ ] Le deuxième message ne se termine pas sur l'event de fin du premier
- [ ] Les actions (`tool_use`, `tool_result`) du tour précédent ne sont pas rejouées dans le flux
- [ ] Sur une session neuve, le comportement est inchangé (non-régression explicite)
- [ ] Si l'identifiant d'event n'est pas obtenu, le repli neutralise quand même les events antérieurs
- [ ] Un test reproduit le scénario de production : deux messages d'affilée, la réponse du second ne
      contient pas le texte du premier
- [ ] Suite backend verte

---

## Périmètre

### Hors scope (explicite)

- **Persister un curseur d'events en base.** Le point de reprise ne vit que le temps d'un tour ;
  l'écrire sur `workspaces` ajouterait une migration et un état à maintenir cohérent entre répliques,
  pour une information qui n'a de sens que dans l'appel en cours.
- La **relecture d'un tour interrompu après rechargement de page** : F-32/SF-30-09 traitent la
  persistance des tours, et ne changent pas ici.
- L'affichage (`terminal`), inchangé — c'est la source des events qui était fausse, pas leur rendu.
- Le rejeu volontaire d'un tour ancien (aucune fonctionnalité ne le demande).

---

## Valeurs initiales

Sans objet — aucune donnée créée, aucune valeur par défaut introduite.

---

## Contraintes de validation

| Élément | Contrainte |
|---|---|
| Identifiant d'event | Chaîne opaque du fournisseur (`sevt_…`), jamais interprétée ni construite côté Gateway |
| Repli | En l'absence d'identifiant dans la réponse, borne = identifiant du **dernier** `user.message` lu juste après publication |
| Ordre | Les events sont traités dans l'ordre des pages, comme aujourd'hui ; seule la **borne de départ** change |

---

## Technique

### Endpoint(s)

Aucun endpoint de la Gateway ne change. Deux appels fournisseur déjà utilisés :
`POST /v1/sessions/{id}/events` (dont la réponse cesse d'être ignorée) et `GET /v1/sessions/{id}/events`.

### Tables impactées

Aucune.

### Migration Liquibase

Aucune.

### Composants Angular (si applicable)

Aucun.

---

## Plan de test

### Tests unitaires

- **Scénario de production** : une session contenant déjà les events d'un tour précédent ; le tour
  courant démarre à l'identifiant du nouveau `user.message` — la réponse produite ne contient pas le
  texte du tour précédent, et ses actions ne sont pas notifiées.
- L'event de fin (`session.status_idle`) **antérieur** au message courant ne termine pas le run.
- Session neuve, aucun event antérieur : réponse et actions identiques au comportement d'avant.
- Repli : le fournisseur renvoie une publication sans identifiant → la borne est retrouvée sur le
  dernier `user.message` de la session, et les events antérieurs sont quand même ignorés.
- Identifiant jamais rencontré → `AgentSessionTimeoutException`, aucun texte ancien rendu.

### Tests d'intégration

Le serveur HTTP de test de `AnthropicManagedAgentProviderTest` sert les pages d'events : les
assertions portent sur ce que le provider **lit et notifie** réellement, pas sur un double.

### Isolation workspace

Sans objet pour l'accès aux données : aucune requête n'est ajoutée. L'isolation reste celle
d'`AtelierSessionService`, qui résout le workspace par `requireOwned` avant toute session (inchangé).
Le correctif la **renforce** indirectement : deux tours ne peuvent plus se voir attribuer le contenu
l'un de l'autre.

---

## Dépendances

### Subfeatures bloquantes

Aucune. SF-30-04 (session persistante) est livrée : c'est elle qui a rendu le défaut atteignable.

### Questions ouvertes impactées

Aucune.

---

## Notes et décisions

**Décision — borne par identifiant d'event, pas par horodatage.** `processed_at` est renseigné, mais
deux events peuvent porter la même microseconde (c'est déjà le cas dans les pages observées :
`session.status_running` et `session.thread_status_running` partagent l'instant). Une borne
temporelle laisserait passer ou couperait arbitrairement des events de la même milliseconde ;
l'identifiant est exact.

**Décision — repli qui neutralise, jamais qui restaure l'ancien comportement.** Si l'identifiant
manque, la borne est retrouvée sur le **dernier `user.message`** de la session — celui qu'on vient de
poster — plutôt qu'en relisant depuis la page 0 : un repli qui rétablirait le bug corrigé n'est pas un
repli.

**Décision — la borne est réévaluée à chaque tour de polling.** Chaque poll relit depuis la page 0
(c'est ainsi que le provider couvre les events arrivés entre-temps) : l'état « borne franchie » est
donc local au poll, et la déduplication par identifiant continue d'empêcher qu'un event soit traité
deux fois.

**Constat — un test qui rejoue le scénario réel.** Le défaut a traversé toute la suite parce que les
tests du provider partent tous d'une session vierge. Le test ajouté commence par une session **déjà
peuplée** : c'est la seule forme qui aurait attrapé la régression de SF-30-04.
