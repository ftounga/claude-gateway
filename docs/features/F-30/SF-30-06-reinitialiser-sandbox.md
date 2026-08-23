# Mini-spec — [F-30 / SF-06] Réinitialiser la sandbox

---

## Identifiant

`F-30 / SF-06`

## Feature parente

`F-30` — Atelier — expérience terminal

## Statut

`ready`

## Date de création

2026-08-24

## Branche Git

`feat/SF-30-06-reinitialiser-sandbox`

---

## Objectif

> En une phrase : que fait cette subfeature ?

Donner à l'utilisateur le moyen de **repartir d'une sandbox neuve**, en exposant dans l'écran Atelier
l'endpoint de réinitialisation livré par SF-30-04.

---

## Contexte

Depuis SF-30-04, la sandbox d'un workspace **survit d'un message à l'autre**. C'est le comportement
voulu, mais il crée un besoin symétrique : quand l'environnement est dans un état bancal (dépendances
cassées, processus resté en place, expérimentation à jeter), l'utilisateur doit pouvoir le remettre à
zéro sans supprimer son projet.

`DELETE /workspaces/{id}/agent/session` existe et n'est appelé par personne.

---

## Comportement attendu

### Cas nominal

1. En mode **Terminal**, une action « Réinitialiser la sandbox » est disponible.
2. Elle demande **confirmation** (`MatDialog`) : c'est une action destructive pour l'environnement
   d'exécution, même si les fichiers du projet, eux, sont conservés.
3. Confirmée, elle appelle `DELETE /workspaces/{id}/agent/session`, puis confirme par un `MatSnackBar`.
4. Le message suivant repart d'une sandbox neuve : les fichiers du projet sont remontés.
5. Les fichiers du workspace ne sont **pas** touchés — seul l'environnement d'exécution est remis à zéro.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Dialogue fermé sans confirmer | Aucun appel réseau |
| Appel en échec (réseau, 5xx) | Message d'erreur lisible ; l'écran reste utilisable |
| Workspace introuvable (404) | Message « Projet introuvable. » |
| Action déclenchée pendant un envoi | Refusée : réinitialiser sous un run en cours n'aurait pas de sens |

---

## Critères d'acceptation

- [ ] L'action est visible en mode Terminal et absente en mode Assistant (elle n'y a aucun effet)
- [ ] Elle est désactivée pendant un envoi
- [ ] Elle demande confirmation via `MatDialog` (jamais `window.confirm`)
- [ ] Fermeture sans confirmer → **aucun appel réseau**
- [ ] Confirmation → `DELETE /workspaces/{id}/agent/session` puis `MatSnackBar` de succès
- [ ] Échec → message lisible, pas de trace technique
- [ ] Aucune couleur ni police hors `DESIGN_SYSTEM.md`
- [ ] Aucun endpoint créé, aucune table, aucune migration

---

## Périmètre

### Hors scope

- Compteur de tokens dans l'indicateur d'activité → SF-30-05
- Indication de l'ancienneté de la sandbox : sans valeur tant que la durée de vie d'une session
  n'est pas documentée (ADR-014 ⚠️)
- Réinitialisation automatique : jamais implicite — l'utilisateur décide

---

## Contraintes de validation

| Élément | Contrainte |
|---------|-----------|
| Confirmation | `MatDialog`, texte explicite sur ce qui est perdu (environnement) et conservé (fichiers) |
| Disponibilité | Mode Terminal uniquement, désactivée si `submitting()` |
| Retour | `MatSnackBar` en succès comme en échec |

---

## Technique

### Endpoint(s)

Aucun créé. Consomme `DELETE /workspaces/{id}/agent/session` (SF-30-04).

### Tables impactées / Migration

Aucune.

### Fichiers impactés

| Fichier | Nature |
|---------|--------|
| `core/services/atelier.service.ts` | + `resetAgentSession(id)` |
| `atelier/atelier.component.ts` | + `resetSandbox()` (confirmation, appel, retours) |
| `atelier/atelier.component.html` | + action dans la barre de mode |
| `atelier/atelier.component.scss` | Style de l'action |

---

## Plan de test

### Tests unitaires (frontend)

- [ ] Service : `resetAgentSession` émet un `DELETE` sur la bonne URL
- [ ] Confirmation → appel effectué + snackbar de succès
- [ ] Fermeture sans confirmer → **aucun appel**
- [ ] Échec → snackbar d'erreur lisible
- [ ] Action refusée pendant un envoi

### Tests d'intégration

Sans objet : l'endpoint est couvert côté backend (SF-30-04, dont l'isolation).

### Isolation utilisateur

- [ ] **Non applicable côté frontend** — l'isolation est garantie par `requireOwned` côté backend,
  testée en SF-30-04 (workspace d'autrui → 404, aucun appel fournisseur).

---

## Préoccupations transversales

| Préoccupation | Concernée | Analyse |
|--------------|-----------|---------|
| Auth / Principal | **Non** | Le `DELETE` passe par l'`authInterceptor` comme tous les autres appels. |
| Contexte tenant | **Non** | Aucun nouveau chemin d'accès : l'endpoint filtre déjà par propriétaire. |
| Plans / limites | **Non** | Aucun appel de quota. Réinitialiser ne consomme rien. |
| Navigation / routing | **Non** | Aucune route. |

---

## Dépendances

- **SF-30-04 (Done)** — fournit l'endpoint.

---

## Notes et décisions

- **Confirmation obligatoire** : l'action ne détruit pas les fichiers, mais elle jette un
  environnement qui a pu coûter plusieurs minutes de `npm install`. La confirmation dit explicitement
  ce qui est perdu et ce qui est conservé.
- **Mode Terminal uniquement** : en mode Assistant, aucune sandbox n'est en jeu ; y afficher l'action
  ne ferait qu'ajouter une question sans objet.
