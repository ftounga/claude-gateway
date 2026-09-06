# Mini-spec — F-39 / SF-39-05 — `bash` fait déjà mieux : retrait de `list_files` et `search_files`

## Identifiant

`F-39 / SF-39-05`

## Feature parente

`F-39` — L'Atelier comme harnais

## Statut

`ready`

## Date de création

2026-09-06

## Branche Git

`feat/SF-39-05-outillage-bash-first`

---

## Objectif

> Ne plus déclarer `list_files` ni `search_files` **là où `bash` existe** : `ls` et `grep` font
> mieux, et deux outils de moins, c'est un préfixe plus court et plus stable.

---

## Déclencheur

Décision **D4** du cadrage F-39, appuyée sur l'usage réel mesuré : **1 630 des 1 714 appels
d'outils (95 %) sont des `bash`**. `list_files` rend une arborescence entière sans filtre ni
profondeur ; `search_files` fait une recherche de sous-chaîne sans expression régulière, sans
filtre de chemin et sans contexte. `ls`, `find` et `grep -n` font tout cela mieux, et l'agent les
connaît déjà.

Deux outils déclarés en moins, ce sont leurs définitions retirées du préfixe caché — donc payées ni
en écriture ni en lecture de cache, à chaque itération de chaque tour.

---

## Comportement attendu

### Cas nominal — projet relié à une machine (cible `RUNNER`)

Trois outils déclarés : `read_file`, `write_file`, `bash`. La consigne système dit explicitement
que **l'exploration passe par `bash`** (`ls`, `find`, `grep -n`), et que `read_file` sert à lire un
fichier qu'on a l'intention d'utiliser.

### Cible `SANDBOX` — rien ne change

`list_files` et `search_files` restent déclarés : **il n'y a pas de `bash` là-bas**. Les retirer
priverait le modèle de tout moyen d'explorer, sans rien lui donner en échange. Leur sort est celui
de la cible `SANDBOX` elle-même, traité en SF-39-16 (décision D7 du cadrage).

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Le modèle appelle `list_files` en cible `RUNNER` malgré tout | L'appel est exécuté comme avant (le relais est conservé) : mieux vaut une réponse utile qu'une erreur | 200 |
| Le modèle appelle un outil inexistant | « Outil inconnu : … » renvoyé comme résultat d'erreur, le tour continue | 200 |

---

## Critères d'acceptation

- [ ] En cible `RUNNER`, les outils déclarés sont exactement `read_file`, `write_file`, `bash`.
- [ ] En cible `SANDBOX`, les outils déclarés sont inchangés (`list_files`, `read_file`,
      `write_file`, `search_files`) et `bash` reste absent.
- [ ] La consigne système en cible `RUNNER` oriente l'exploration vers `bash`.
- [ ] La consigne système en cible `SANDBOX` continue de citer les outils fichiers.
- [ ] Un appel `list_files` / `search_files` reçu en cible `RUNNER` reste exécuté (relais conservé).
- [ ] Isolation `user_id` inchangée.

---

## Périmètre

### Hors scope (explicite)

- La lecture numérotée et paginée, et l'édition ciblée — **SF-39-06**.
- Le retrait du code d'exécution de `list_files` / `search_files` : il sert encore la cible
  `SANDBOX`, et son sort dépend de SF-39-16.
- Le chemin Managed Agents, dont l'outillage est celui du fournisseur.

---

## Contraintes de validation

| Champ | Obligatoire | Valeurs | Normalisation |
|-------|-------------|---------|---------------|
| Outils déclarés en `RUNNER` | — | `read_file`, `write_file`, `bash` | — |
| Outils déclarés en `SANDBOX` | — | `list_files`, `read_file`, `write_file`, `search_files` | — |

---

## Technique

### Endpoint(s)

Aucun.

### Tables impactées

Aucune.

### Migration Liquibase

- [x] **Non applicable.**

### Classes impactées

| Classe | Changement |
|--------|-----------|
| `atelier/AtelierChatService` | `buildTools` dépend de la cible ; consigne système adaptée |

### Composants Angular

Aucun. Le journal d'activité affiche déjà les outils par leur nom, et les entrées passées restent
lisibles.

---

## Préoccupations transversales

| Préoccupation | Concernée | Composants impactés et vérification |
|--------------|-----------|-----------------------------------|
| Auth / Principal | Non | — |
| Contexte tenant | Non | Aucun chemin d'accès modifié |
| Plans / limites | Non | Le préfixe rétrécit : le décompte baisse, la règle ne change pas |
| Navigation / routing | Non | — |

---

## Plan de test

### Tests unitaires

- [ ] `AtelierChatServiceTest` — en cible `SANDBOX`, la liste d'outils est inchangée.
- [ ] `AtelierChatServiceRunnerTargetTest` — en cible `RUNNER`, `list_files` et `search_files` ne
      sont plus déclarés, et `bash` l'est.
- [ ] `AtelierChatServiceRunnerTargetTest` — un `list_files` reçu malgré tout est toujours relayé.
- [ ] `AtelierChatServiceSystemPromptTest` — la consigne `RUNNER` oriente vers `bash`.

### Tests d'intégration

- [ ] Couvert par `AtelierChatApiIntegrationTest` (cible `SANDBOX`) : contrat inchangé.

### Isolation workspace

- [x] Applicable — aucun nouveau chemin d'accès.

---

## Dépendances

### Subfeatures bloquantes

- `SF-39-04` — done.

### Questions ouvertes impactées

- [ ] Aucune.

---

## Notes et décisions

**D1 — Retrait par cible, pas retrait absolu.** Le cadrage dit « retirer `list_files` et
`search_files` » ; le faire partout casserait la cible `SANDBOX`, qui n'a pas de `bash` pour
prendre le relais. Le retrait suit donc la capacité réelle, et la question du sort de `SANDBOX`
reste posée là où le cadrage la pose : en SF-39-16.

**D2 — Le relais d'exécution est conservé.** Un modèle peut appeler un outil qu'on ne lui a pas
déclaré. Répondre « outil inconnu » à un `list_files` alors que le code sait parfaitement le faire
serait une régression gratuite. On retire la **déclaration** — ce qui coûte des tokens — pas la
capacité.
