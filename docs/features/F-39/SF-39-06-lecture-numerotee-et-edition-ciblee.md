# Mini-spec — F-39 / SF-39-06 — Lire par pages numérotées, éditer au bon endroit

## Identifiant

`F-39 / SF-39-06`

## Feature parente

`F-39` — L'Atelier comme harnais

## Statut

`ready`

## Date de création

2026-09-06

## Branches Git

- `feat/SF-39-06-lecture-et-edition-ciblees` — backend (livrée en premier)
- `feat/SF-39-06-front-journal-edition` — frontend (libellé du journal d'activité)

---

## Objectif

> `read_file` rend des **lignes numérotées et paginées**, et un nouvel outil `edit_file` remplace un
> passage **exact** au lieu de réécrire tout le fichier.

---

## Déclencheur

Décision **D4** du cadrage F-39, seconde moitié. Aujourd'hui :

- `read_file` rend le fichier **entier**, sans numéro de ligne. Un fichier de 3 000 lignes entre en
  bloc dans le contexte, y reste pour toute la suite du tour, et rien ne permet à l'agent de dire
  *où* il a vu quelque chose.
- `write_file` est le **seul** moyen d'éditer : changer trois caractères impose de réémettre le
  fichier complet. Le coût est en tokens de sortie — les plus chers — et le risque est réel : une
  réponse coupée au plafond de sortie réécrivait un fichier tronqué (c'est SF-28-18 qui a dû
  l'empêcher, en refusant d'exécuter les outils d'un tour tronqué).

Un prompt plus court est aussi un préfixe plus stable, donc mieux caché : les deux moitiés du lot 3
servent le même levier.

---

## Comportement attendu

### `read_file` — numéroté et paginé

```
     1→package fr.claudegateway.atelier;
     2→
     3→public class Workspace {
… (lignes 1 à 2000 sur 3412 ; relance read_file avec offset=2001)
```

- `offset` : première ligne rendue, **1 par défaut** ;
- `limit` : nombre de lignes, **2 000 par défaut et au maximum** ;
- une ligne de plus de **2 000 caractères** est coupée avec `…` — un minifié ne doit pas noyer le tour ;
- le pied de page n'apparaît que s'il reste des lignes, et dit **comment** demander la suite.

### `edit_file` — remplacement exact

`edit_file(path, old_string, new_string, replace_all?)` remplace un passage **littéral** :

| Situation | Résultat |
|---|---|
| Une occurrence | Remplacée ; le fichier est réécrit |
| Plusieurs occurrences, `replace_all` absent | **Refus** : « trouvé N fois — donne un extrait plus large, ou utilise `replace_all` » |
| Plusieurs occurrences, `replace_all = true` | Toutes remplacées |
| Aucune occurrence | **Refus** : le texte est introuvable |
| `old_string` = `new_string` | **Refus** : aucune modification demandée |
| Lecture **tronquée** (fichier trop gros) | **Refus** — réécrire à partir d'une lecture partielle détruirait la fin du fichier |

Le refus est un résultat d'outil en erreur, pas une exception : l'agent lit le motif et se corrige.

### Ce qui ne change pas

`write_file` reste : créer un fichier, ou le remplacer intégralement, reste légitime. `edit_file`
s'ajoute, il ne remplace rien.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| `offset` au-delà de la fin | Résultat d'erreur nommant le nombre de lignes réel | 200 |
| `offset` / `limit` négatifs ou non numériques | Ramenés à leurs valeurs par défaut | 200 |
| Fichier vide | `(fichier vide)` — jamais une sortie muette | 200 |
| Fichier introuvable | Résultat d'erreur, tour poursuivi | 200 |
| Édition qui dépasse la borne d'écriture du runner | Refus du relais, message explicite | 200 |

---

## Critères d'acceptation

- [ ] `read_file` rend des lignes numérotées, séparateur `→`, numéro cadré à droite.
- [ ] `offset` et `limit` bornent la lecture ; défauts 1 et 2 000.
- [ ] Le pied de page n'apparaît que s'il reste des lignes et indique l'`offset` suivant.
- [ ] Une ligne de plus de 2 000 caractères est coupée avec `…`.
- [ ] Un `offset` au-delà de la fin donne un résultat d'erreur nommant le nombre de lignes.
- [ ] Un fichier vide rend `(fichier vide)`.
- [ ] `edit_file` remplace une occurrence unique et réécrit le fichier.
- [ ] `edit_file` refuse une occurrence multiple sans `replace_all`, et remplace tout avec.
- [ ] `edit_file` refuse un texte introuvable, et une édition sans changement.
- [ ] `edit_file` refuse d'écrire à partir d'une lecture tronquée.
- [ ] `edit_file` produit une action `write` — l'éditeur ouvert se rafraîchit comme après `write_file`.
- [ ] `edit_file` est déclaré sur les deux cibles, et journalisé avec son chemin (F-38 / SF-38-08).
- [ ] Isolation `user_id` : lecture et écriture passent par les chemins existants, déjà filtrés.

---

## Périmètre

### Hors scope (explicite)

- Toute modification du **protocole runner** : la numérotation, la pagination et l'édition sont
  calculées côté gateway à partir des primitives existantes (`read_file` / `write_file`). Un runner
  déjà installé fonctionne sans mise à jour.
- Le remplacement par expression régulière : trop de façons de se tromper pour le gain.
- L'édition par numéros de ligne : les numéros bougent à la première édition ; le texte exact, non.

---

## Contraintes de validation

| Champ | Obligatoire | Valeurs | Normalisation |
|-------|-------------|---------|---------------|
| `offset` | Non | ≥ 1 | Valeur invalide ⇒ 1 |
| `limit` | Non | 1 → 2 000 | Valeur invalide ou hors borne ⇒ 2 000 |
| Ligne rendue | — | ≤ 2 000 caractères | Coupée avec `…` |
| `old_string` | Oui | non vide | — |
| `new_string` | Oui | peut être vide (suppression) | — |
| `replace_all` | Non | booléen, défaut `false` | — |

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
| `atelier/AtelierFileText` | **Nouveau** — numérotation, pagination, remplacement exact (logique pure) |
| `atelier/AtelierChatService` | Déclaration et exécution de `read_file` paginé et de `edit_file`, sur les deux cibles ; audit |

### Composants Angular

| Composant | Changement |
|---|---|
| `atelier/runner/runner-audit-dialog.component.ts` | Libellé et icône de `edit_file` dans le journal d'activité |

---

## Préoccupations transversales

| Préoccupation | Concernée | Composants impactés et vérification |
|--------------|-----------|-----------------------------------|
| Auth / Principal | Non | — |
| **Contexte tenant** | **Oui** | `edit_file` lit puis écrit. Composants revus : `WorkspaceService.readFile` / `writeFile` (les deux appellent `requireOwned`), `RunnerToolGateway.readFile` / `writeFile` (routés par `workspaceId`, chemin normalisé, le runner revérifie), `AtelierChatService.executeTool` (cible résolue depuis le workspace possédé). Aucun nouveau chemin d'accès. |
| Plans / limites | Non | Le volume baisse ; la règle de décompte ne change pas |
| Navigation / routing | Non | — |

---

## Plan de test

### Tests unitaires

- [ ] `AtelierFileTextTest` — numérotation, largeur du numéro, séparateur.
- [ ] `AtelierFileTextTest` — pagination : `offset`, `limit`, pied de page, dernière page sans pied.
- [ ] `AtelierFileTextTest` — `offset` au-delà de la fin, valeurs invalides ramenées aux défauts.
- [ ] `AtelierFileTextTest` — ligne trop longue coupée ; fichier vide.
- [ ] `AtelierFileTextTest` — remplacement unique, multiple refusé, `replace_all`, texte absent,
      édition sans changement.
- [ ] `AtelierChatServiceTest` — `read_file` rend un contenu numéroté (cible stockage).
- [ ] `AtelierChatServiceTest` — `edit_file` réécrit le fichier et produit une action `write`.
- [ ] `AtelierChatServiceRunnerTargetTest` — `read_file` numéroté sur la machine, marqueur de
      troncature conservé.
- [ ] `AtelierChatServiceRunnerTargetTest` — `edit_file` lit puis écrit sur la machine, et refuse
      d'écrire après une lecture tronquée.
- [ ] `AtelierChatServiceRunnerTargetTest` — `edit_file` est déclaré et journalisé avec son chemin.

### Tests d'intégration

- [ ] `AtelierChatApiIntegrationTest` — un tour `edit_file` modifie réellement le fichier du
      workspace et l'annonce comme une écriture.

### Isolation workspace

- [x] Applicable — chemins d'accès existants, déjà couverts par les tests d'isolation.

---

## Dépendances

### Subfeatures bloquantes

- `SF-39-05` — done.

### Questions ouvertes impactées

- [ ] Aucune.

---

## Notes et décisions

**D1 — Tout se calcule côté gateway.** Numérotation, pagination et édition n'exigent aucune
évolution du protocole runner : un runner déjà installé chez un utilisateur en bénéficie sans rien
mettre à jour. Le prix est une lecture complète du fichier pour une édition ; c'est le même prix
qu'aujourd'hui, où toute édition passe par une réécriture complète.

**D2 — Refuser d'éditer après une lecture tronquée.** Le runner peut couper un contenu trop
volumineux. Appliquer un remplacement sur ce fragment puis le réécrire **détruirait la fin du
fichier**, silencieusement. C'est le seul cas où l'outil refuse une opération que le modèle croit
possible, et il le dit clairement.

**D3 — Une occurrence multiple est un refus, pas un choix.** Remplacer « la première » quand il y en
a trois, c'est éditer au hasard. Le message demande un extrait plus large ou `replace_all` — deux
gestes que le modèle sait faire.

**D4 — `edit_file` produit une action `write`.** L'écran sait déjà rafraîchir un fichier ouvert
quand une action `write` porte son chemin (SF-28-05). Inventer un type d'action « edit » casserait
ce rafraîchissement pour n'ajouter qu'un mot. Le journal d'activité, lui, distingue bien les deux :
il journalise le nom d'outil réel.
