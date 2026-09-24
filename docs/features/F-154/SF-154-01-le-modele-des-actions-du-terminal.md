# Mini-spec — F-154 / SF-154-01 — Le modèle des actions du terminal

## Identifiant
`F-154 / SF-154-01` — feature parente `F-154`

## Objectif
Qu'une action à faire, née dans un terminal de projet, **survive au tour** qui l'a produite.

## Le défaut
> PO : *« Je commence un sujet, bim, je suis coincé. Il est question de contacter telle personne. Je
> voudrais que ces actions se mettent dans un menu du terminal. »*

Aujourd'hui, quand l'agent bute sur une dépendance humaine (« demande l'accès réseau à Karim »), il
l'écrit dans sa réponse. Au tour suivant, il n'en reste rien.

## La décision de conception, et pourquoi

**Le modèle existe** — les engagements du Radar (`radar_commitments`) : direction, description,
échéance, statut, souveraineté de la parole de l'utilisateur, tout annulable. **Mais** `subject_id` y
est **NOT NULL** : un engagement appartient à un **sujet du Radar**, et un terminal de projet n'en a
pas toujours un.

Deux voies possibles :

| Voie | Ce qu'elle coûte |
|---|---|
| Rendre `subject_id` nullable | toucher le **cœur** du Radar — extraction, relances, corrections, purge — pour un besoin qui n'est pas le sien. Risque de régression sur une feature livrée et utilisée. |
| **Une table dédiée**, et **une seule liste à l'écran** | une table de plus, mais le Radar reste intact |

**Voie retenue : la seconde.** L'utilisateur ne doit pas voir deux listes — c'est ce qui compte — mais
cela ne l'oblige pas à n'y avoir qu'une seule table. Le menu du terminal **agrège** : les actions du
terminal **et**, quand le terminal porte un sujet du Radar, les engagements « à faire par moi » de ce
sujet. Une liste pour l'œil, deux sources pour le code.

## Comportement attendu
1. Une action porte : **ce qu'il faut faire**, **ce que ça débloque**, **qui** est concerné, et **quand**
   elle est née.
2. Elle appartient au **projet** où elle est née, et au **sujet** quand le terminal en a un.
3. Trois états, et trois seulement : **ouverte**, **faite**, **annulée**.
4. Une action **fermée garde sa raison** — la phrase de l'utilisateur qui l'a close. Sans elle, on ne
   saurait plus **pourquoi** elle a disparu.
5. **Isolation** : toute lecture et toute écriture sont filtrées `user_id` **et** `workspace_id`.

| Cas d'erreur | Comportement |
|---|---|
| Action d'un autre compte ou d'un autre projet | **404 indiscernable** |
| Description vide | refus nommé — une action sans énoncé n'est pas une action |
| Fermer une action déjà fermée | sans effet, et dit ; jamais une erreur qui casse un tour |

## Critères d'acceptation
- [ ] Une action se crée avec sa description, ce qu'elle débloque, la personne, le projet.
- [ ] Elle se liste pour un projet, les plus anciennes d'abord (l'ancienneté est le signal utile).
- [ ] Elle s'**annule** et se **ferme**, avec la raison conservée.
- [ ] Les bornes sont appliquées (longueurs, nombre d'actions ouvertes par projet).
- [ ] **ISOLATION** : `user_id` + `workspace_id` sur chaque accès ; le projet d'un autre est introuvable.
- [ ] Le Radar est **inchangé** — aucune migration sur `radar_commitments`.

## Hors scope
L'**inscription automatique** par l'agent (**SF-154-02**) · le **menu** (**SF-154-03**) · la **fermeture
par la conversation** (**SF-154-04**) · l'envoi du message depuis la liste (plus tard, F-110 existe).

## Technique
| Élément | Changement |
|---|---|
| Migration `130-terminal-actions.xml` | table `terminal_actions` |
| `TerminalAction` + repository | l'entité, filtrée `user_id` + `workspace_id` |
| `TerminalActionService` | créer, lister, annuler, fermer — bornes et isolation |
| `TerminalActionController` | `GET/POST /atelier/workspaces/{id}/actions`, `POST …/{actionId}/close`, `…/cancel` |

**Bornes** : description 300 caractères · « ce que ça débloque » 200 · personne 120 · **50 actions
ouvertes** par projet (au-delà, c'est que rien n'est traité — on le dit plutôt que d'empiler).

## Plan de test
- [ ] Création, liste ordonnée, fermeture avec raison, annulation.
- [ ] Description vide → 400 ; fermeture d'une action déjà fermée → sans effet, dit.
- [ ] Bornes dépassées → refus nommé.
- [ ] **ISOLATION** : l'action d'un autre compte et celle d'un autre projet sont **introuvables** (404).

## Préoccupations transversales
| Préoccupation | Cochée | Composants impactés |
|---|---|---|
| Auth / Principal | non | routes utilisateur ordinaires, `CurrentUser` |
| **Contexte tenant** | **oui** | `TerminalActionService` et son repository filtrent `user_id` **et** `workspace_id` ; le workspace est celui du tour ou de la requête, **vérifié comme possédé** (`WorkspaceService.requireOwned`). |
| Plans / limites | non | aucun appel fournisseur |
| **Navigation / routing** | **oui** *(API seulement ici)* | aucune route d'écran ; le menu arrive en SF-154-03 |
