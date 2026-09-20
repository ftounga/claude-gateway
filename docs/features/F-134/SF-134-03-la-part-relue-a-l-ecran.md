# Mini-spec — F-134 / SF-134-03 — La part relue à l'écran

## Identifiant
`F-134 / SF-134-03`

## Feature parente
`F-134` — Le cache qui ne prend pas

## Statut
`draft` — en attente de validation PO

## Date de création
2026-09-20

## Branche Git
`feat/SF-134-03-la-part-relue`

---

## Objectif

Afficher, à côté du montant du tour, **la part de contexte relue** — pour que l'effet des
corrections se voie, et qu'une régression future se signale d'elle-même.

---

## Pourquoi ce chiffre mérite d'être à l'écran

Relire coûte **un vingtième** d'écrire. La part relue est donc le seul indicateur qui dise, d'un
coup d'œil, si le cache fait son travail.

Sans lui, il faut interroger la base pour savoir si un tour a bien caché — c'est ce qu'il a fallu
faire pour établir ce diagnostic. Avec lui, la ligne sous chaque réponse devient :

> `1 min 12 s · 34 210 tokens · 0,42 € · 91 % relu`

---

## Comportement attendu

| Situation | Affichage |
|---|---|
| Administrateur, tour ayant touché le cache | montant **et** part relue |
| Administrateur, tour sans cache | montant seul — **pas de « 0 % »**, qui se lirait comme un échec alors qu'il n'y avait rien à cacher |
| Utilisateur ordinaire | ni montant ni part : rien ne quitte le serveur |

---

## Critères d'acceptation

- [ ] La part suit le montant, séparée par le même point médian que le reste de la ligne.
- [ ] Elle est calculée sur le **cache seul** — lectures rapportées au total lu + écrit — et non sur le volume du tour.
- [ ] Un tour sans cache n'affiche **aucune** part.
- [ ] Un utilisateur non administrateur ne reçoit **ni** montant **ni** part.
- [ ] L'écran n'a rien à changer : il affiche le texte que la passerelle envoie.

---

## Périmètre

### Hors scope
- Un historique ou une courbe de la part relue : la question est « ce tour a-t-il bien caché ? »,
  pas « comment cela évolue-t-il ».
- Le chemin Managed Agents, qui ne remonte toujours pas son coût.

---

## Technique

| Classe | Changement |
|---|---|
| `TurnCostView` | `reusedPercent(tokens)` et une forme de `labelFor` qui l'ajoute |
| `AtelierChatService` | calcule la part et la porte dans son résultat |
| `AtelierChatController` | compose « montant · part » pour la réponse et le flux |

**Aucun changement frontend** : l'écran affiche déjà le texte tel que la passerelle l'envoie.

Aucun endpoint, aucune table, aucune migration.

---

## Plan de test

- [ ] 90 000 relus sur 100 000 de cache ⇒ `0,92 € · 90 % relu`.
- [ ] Tour sans cache ⇒ montant seul.
- [ ] Non-administrateur ⇒ `null`.
- [ ] La part se calcule sur le cache seul, entrée et sortie exclues.

---

## Préoccupations transversales

| Préoccupation | Cochée | Composants impactés |
|---|---|---|
| **Auth / Principal** | **oui** | `TurnCostView` — la part suit exactement la même garde que le montant : un test vérifie qu'elle ne fuit pas |
| Navigation / routing | non | aucun écran modifié |

---

## Estimation

**0,25 jour.**
