# Mini-spec — F-133 / SF-133-10 — Le coût entre dans l'écran

## Identifiant
`F-133 / SF-133-10`

## Feature parente
`F-133` — Le coût réel Anthropic : par message, par client, par semaine

## Statut
`draft` — second correctif de SF-133-02

## Date de création
2026-09-20

## Branche Git
`fix/SF-133-10-le-cout-entre-dans-l-ecran`

---

## Objectif

Faire que le montant **entre** dans l'écran : le relais SSE du navigateur le recevait et le jetait.

---

## Le défaut

Après SF-133-09, le montant était bien calculé, bien enregistré et bien **envoyé**. Mesuré en
production sur les tours du PO :

| Tour | Entrée | Cache lu | Coût enregistré | Modèle |
|---|---|---|---|---|
| 16:04 | 385 371 | 225 359 | **1,750710 $** | claude-opus-5 |
| 16:02 | 458 094 | 260 639 | **2,138405 $** | claude-opus-5 |
| 15:58 | 2 449 070 | 2 201 232 | **3,830256 $** | claude-opus-5 |

Et pourtant l'écran ne montrait rien. La cause est côté navigateur :
`AtelierService.dispatchSseEvent` reconstruit l'événement `done` **champ par champ**, et `costEur`
n'était pas dans la liste. Le montant arrivait, puis était **jeté à l'entrée**, sans trace.

C'était le dernier maillon : calcul ✅, persistance ✅, garde ✅ (SF-133-09), envoi ✅ — et une
recopie qui laissait tomber le champ.

**Ce que cette double erreur apprend** : sur un chemin à relais (serveur → SSE → parser → composant),
ajouter un champ demande de le suivre **jusqu'au bout**, maillon par maillon. Deux des quatre
maillons l'ont perdu, chacun en silence.

---

## Comportement attendu

| Situation | Comportement |
|---|---|
| La passerelle envoie `costEur` | le montant apparaît au bout de la ligne de coût |
| La passerelle ne l'envoie pas (non-administrateur) | rien, et rien d'inventé |

---

## Critères d'acceptation

- [ ] Un `done` portant `costEur` le transmet au composant — **figé par un test**.
- [ ] Un `done` sans `costEur` laisse le champ `undefined`, jamais une valeur de repli.
- [ ] Le type du payload SSE déclare le champ : un oubli de ce genre devient une **erreur de compilation**, pas un silence.
- [ ] Aucun autre champ du relais n'est modifié.

---

## Périmètre

### Hors scope
- Le parser du chemin **Managed Agents** (`dispatchAgentSseEvent`), où la passerelle n'envoie
  toujours pas de coût — limite connue de SF-133-02.

---

## Technique

| Fichier | Changement |
|---|---|
| `atelier.service.ts` | `costEur` dans le type du payload **et** dans la recopie de `done` |

Aucun endpoint, aucune table, aucune migration, aucun changement backend.

---

## Plan de test

- [ ] `streamChat` transmet le montant (le défaut, figé).
- [ ] `streamChat` laisse le montant absent quand il n'est pas envoyé.
- [ ] Suite frontend complète sans régression.

---

## Préoccupations transversales

| Préoccupation | Cochée | Composants impactés |
|---|---|---|
| **Navigation / routing** | non | aucun écran, aucune route |
| **Auth / Principal** | non | la décision reste côté serveur (SF-133-09) ; le navigateur n'en prend aucune |

---

## Estimation

**0,25 jour.**
