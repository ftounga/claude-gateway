# Mini-spec — F-133 / SF-133-13 — Tous les clients budgétables

## Identifiant
`F-133 / SF-133-13`

## Feature parente
`F-133` — Le coût réel Anthropic

## Statut
`draft` — demandé par le PO le 2026-09-20

## Branche Git
`feat/SF-133-12-alerte-dans-la-forge` *(livrée avec SF-133-12 et SF-133-14 : même écran, même PR)*

---

## Objectif

Pouvoir poser un budget hebdomadaire sur **n'importe quel client**, et pas seulement sur ceux qui
ont déjà dépensé cette semaine.

---

## Le défaut

Le PO : *« Pourquoi le seul client sur qui je peux mettre le budget de la semaine c'est CAGIP ? »*

La liste de l'écran vient de `HostCostService`, c'est-à-dire de `usage_turns` **sur la fenêtre**.
Un client qui n'a pas travaillé cette semaine n'a aucune ligne, donc n'apparaît pas, donc n'a pas
de champ « Budget ». Or c'est exactement au moment où un client **n'a pas encore dépensé** qu'on
veut lui poser un plafond. Le budget est une décision d'avance ; l'écran ne le permettait qu'après
coup.

SF-133-07 avait déjà traité la moitié du problème — un client **budgété** sans dépense apparaît à
zéro — mais il fallait déjà avoir posé le budget pour le voir. Le serpent se mordait la queue.

---

## Comportement attendu

### Cas nominal
1. L'écran liste **tous les postes de l'administrateur**, qu'ils aient dépensé ou non.
2. Un poste sans dépense sur la période apparaît à **0,00 €**, avec son **nom**, et son champ
   « Budget ».
3. Un poste qui a dépensé garde exactement l'affichage d'aujourd'hui.
4. Le seau « hors client » (dépense sans poste rattaché) reste sans budget : ce n'est pas un client.

### Cas d'erreur

| Situation | Comportement |
|---|---|
| Aucun poste | « Aucun client » — l'écran tient |
| Poste d'un autre compte | invisible (isolation `user_id`) |

---

## Critères d'acceptation

- [ ] Un poste **sans aucune dépense** apparaît dans la liste, à 0,00 €, avec **son nom**.
- [ ] On peut lui poser un budget hebdomadaire, et le budget est bien enregistré.
- [ ] Un poste budgété sans dépense n'apparaît **qu'une fois** (pas de doublon avec SF-133-07).
- [ ] Les postes d'un autre compte n'apparaissent jamais.
- [ ] Le total budgété de la période tient compte de tous les budgets, sans double comptage.
- [ ] L'ordre reste lisible : les clients qui dépensent d'abord, les inactifs ensuite.

---

## Périmètre

### Hors scope
- La création d'un client depuis cet écran (elle se fait dans la Forge).
- Le budget mensuel : F-133 budgète **la semaine** (SF-133-04).

---

## Technique

| Classe | Changement |
|---|---|
| `CostSummaryService` | complète la liste avec **tous** les postes de l'utilisateur, à zéro ; le nom vient désormais de `RunnerHostRepository`, y compris pour les postes budgétés sans dépense |

Aucune table, aucune migration, aucun changement d'API (le contrat `CostSummary` ne bouge pas).

---

## Plan de test

### Backend
- [ ] Un poste sans dépense ni budget est présent dans le résumé, à zéro, **nommé**.
- [ ] Un poste budgété sans dépense n'est pas dupliqué.
- [ ] Un poste d'un autre utilisateur est absent.
- [ ] Le total budgété ne compte chaque budget qu'une fois.

### Frontend
- [ ] Le champ « Budget » est proposé pour un client à zéro (déjà couvert : le gabarit ne teste que
      `hostId !== null`).

---

## Préoccupations transversales

| Préoccupation | Cochée | Composants impactés |
|---|---|---|
| Auth / Principal | non | — |
| **Contexte tenant** | **oui** | `CostSummaryService` lit désormais `RunnerHostRepository.findByUserId(userId)` — **filtré par `user_id`**, comme toutes les autres lectures de postes. Aucun autre composant ne change de source de vérité. |
| Plans / limites | non | le budget ne bloque rien (SF-133-04) |
| Navigation / routing | non | — |
