# Mini-spec — F-147 / SF-147-03 — La promotion vers la carte : proposée, et rappelée

## Identifiant
`F-147 / SF-147-03` — feature parente `F-147`

## Objectif
Qu'une réunion dont le texte est arrivé **propose** de ranger ses faits durables dans la carte du
poste, et le **rappelle** tant que ce n'est pas fait — sans jamais le faire à la place de l'utilisateur.

## Le défaut
> *« Proposée et rappelée »* — arbitrage du PO, 2026-09-22.

Le geste existe déjà : « Ranger dans la carte du poste » (F-128 / SF-128-11), qui extrait les faits
**durables** et les écrit dans les bons fichiers de la carte. Mais :

1. **Rien ne dit qu'il reste à faire.** Aucune trace de promotion n'est gardée : une réunion rangée
   et une réunion jamais rangée se ressemblent **exactement**.
2. **Rien ne le rappelle.** Le bouton n'existe que sur l'écran d'une réunion, qu'il faut avoir ouverte.

Le savoir d'une réunion déposée n'entre donc dans la boucle (F-136 : carte → contexte des tours
suivants) que si quelqu'un y pense.

## Ce qu'on ne fait pas
**La promotion reste un geste de l'utilisateur.** Elle n'est pas automatisée : elle coûte un appel au
fournisseur, elle écrit dans des fichiers du client, et le PO l'a tranché — *proposée*, pas *faite*.

## Comportement attendu
1. Une promotion réussie est **horodatée** sur la réunion, avec le nombre de faits écrits.
2. L'écran d'une réunion **porteuse de texte et jamais rangée** propose le geste, visiblement.
3. La liste des réunions du poste **rappelle** combien attendent d'être rangées.
4. Une réunion **déjà rangée** ne le propose plus ; elle dit quand elle l'a été, et combien de faits.
5. Une promotion **sans rien de durable** compte comme faite : sinon elle se rappellerait pour rien,
   indéfiniment — c'est exactement la boucle que F-125 a supprimée.

| Cas d'erreur | Comportement |
|---|---|
| Promotion en échec (poste hors ligne, carte illisible) | rien n'est horodaté : le rappel **reste**, et c'est juste |
| Aucune carte active sur le poste | rien n'est horodaté non plus — il n'y a nulle part où ranger ; la note le dit au clic, et le rappel invite à activer un paquet |
| Réunion sans transcript | pas de proposition — il n'y a rien à ranger |
| Réunion d'un autre compte / poste | **404 indiscernable**, comme tout le reste de la Vigie |

## Critères d'acceptation
- [x] Une promotion réussie horodate la réunion et retient le nombre de faits écrits.
- [x] Une promotion **sans fait durable** compte comme faite (pas de rappel perpétuel).
- [x] Une promotion **en échec** ne compte pas.
- [x] L'écran d'une réunion transcrite et non rangée **propose** le geste ; une réunion rangée dit quand.
- [x] La liste des réunions **rappelle** le nombre de réunions en attente.
- [x] **ISOLATION** : lecture et écriture filtrées `user_id` **et** `host_id`.

## Hors scope
L'outil `meeting_transcript` (**SF-147-04**) · le retrait de la surveillance périodique (**SF-147-05**)
· le rattrapage d'un texte resté sur un poste hors ligne (**SF-147-06**) · toute **promotion
automatique** — écartée par le PO, et écartée ici.

## Technique
| Élément | Changement |
|---|---|
| Migration `125-meetings-card-promotion.xml` | `card_promoted_at (timestamptz, nullable)`, `card_facts_written (int, nullable)` |
| `Meeting` | les deux colonnes |
| `MeetingCardPromotionService.promote` | horodate à la fin, y compris « rien de durable » |
| `MeetingResponse` | `cardPromotedAt`, `cardFactsWritten` |
| `meeting-detail-page` | propose, ou dit quand ça a été fait |
| `meeting-capture-panel` | le rappel : combien attendent d'être rangées |

**Aucune route nouvelle** : le rappel se calcule sur la liste des réunions **déjà chargée** par le
panneau. Une route de plus ne dirait rien que la liste ne dise déjà, et coûterait un appel par affichage.

## Plan de test
### Gateway — `MeetingCardPromotionServiceTest`, 13 verts
- [x] Une promotion réussie horodate et retient le nombre de faits (2 faits → `cardFactsWritten = 2`).
- [x] « Rien de durable » horodate aussi, à 0 — pas de rappel perpétuel.
- [x] **Rien d'écrit** (poste injoignable) ⇒ **rien d'horodaté** ; **aucune carte active** ⇒ rien non plus.
- [x] `MeetingResponse` porte les deux champs.
- [x] **ISOLATION** : l'accès passe par `findByIdAndUserIdAndHostId`, inchangé.

### Écran — `meeting-detail-page` (26) + `meeting-capture-panel` (13)
- [x] Réunion transcrite non rangée : la proposition est visible.
- [x] Réunion rangée : la date et le nombre de faits ; « rien de durable » se dit aussi.
- [x] La liste rappelle le nombre en attente ; zéro en attente ⇒ **aucun rappel**.

## Préoccupations transversales
| Préoccupation | Cochée | Composants impactés |
|---|---|---|
| Auth / Principal | non | — |
| **Contexte tenant** | **oui** | `TeamsMeetingCardController` → `RadarScope` déjà résolu ; `MeetingRepository` filtré `user_id`+`host_id`. Aucun nouveau chemin d'accès. |
| Plans / limites | **oui** | La promotion **coûte un appel fournisseur** et reste décomptée comme aujourd'hui (`QuotaService`) : proposer n'appelle rien, seul le clic appelle. |
| **Navigation / routing** | **oui** *(deux écrans)* | `meeting-detail-page` et `meeting-capture-panel` ; aucune route d'écran nouvelle. |
