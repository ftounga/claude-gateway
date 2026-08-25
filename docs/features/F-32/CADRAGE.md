# Cadrage — F-32 : Interrompre un run en cours

## Identifiant / Statut / Date

`F-32` · `cadré, décisions par défaut prises` · 2026-08-25

## Objectif

Permettre à l'utilisateur d'**arrêter une exécution en cours** depuis l'écran, au lieu d'attendre le
timeout.

## Contexte

Aujourd'hui, une commande partie de travers (`npm install` sur un dépôt cassé, boucle, build
interminable) tourne jusqu'au plafond `sessionTimeout` (10 min). L'utilisateur regarde sans pouvoir
agir, et le temps de sandbox **est facturé**.

## Ce que l'API offre

`POST /v1/sessions/{id}/events` avec `{"events": [{"type": "user.interrupt"}]}`. La session **continue
jusqu'à une frontière sûre**, puis passe `idle` — ce n'est pas un `kill`, aucune corruption d'état.

## Décisions par défaut (à contredire si besoin)

| # | Décision | Pourquoi |
|---|----------|----------|
| D1 | Un bouton **Interrompre** dans l'en-tête du terminal, visible **pendant** un run | Là où l'utilisateur regarde déjà défiler la sortie |
| D2 | Le tour interrompu **est persisté**, marqué comme interrompu, avec sa transcription partielle | Il a réellement consommé du sandbox et produit des sorties : les effacer contredirait ce que l'écran vient d'afficher — écart assumé avec SF-30-09, qui ne persiste que les runs aboutis |
| D3 | La consommation du tour interrompu **est décomptée** normalement | Elle a été réellement consommée ; ne pas la décompter fausserait la facturation |
| D4 | Endpoint `POST /workspaces/{id}/agent/interrupt`, isolé par `requireOwned` | Aligné sur `DELETE …/agent/session` (SF-30-04) |

## Découpage

| SF | Contenu |
|----|---------|
| **SF-32-01** | `interruptSession` au provider, endpoint d'interruption, arrêt propre du run (backend) |
| **SF-32-02** | Bouton dans la vue terminal + état « interrompu » dans le fil (frontend) |

## Pièges identifiés

- L'interruption est **asynchrone** : la session ne s'arrête pas au retour de l'appel. Le run en cours
  doit sortir proprement de sa boucle de polling à l'arrivée de `session.status_idle`, sans le traiter
  comme un échec.
- Le run tourne sur le **pool SSE**, l'interruption arrive sur un **autre thread** : la coordination
  passe par la session (le fournisseur), pas par un état partagé en mémoire.

## Hors scope

Interruption automatique sur seuil de coût ; reprise d'un run interrompu là où il s'est arrêté.
