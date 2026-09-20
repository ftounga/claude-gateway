# Mini-spec — F-134 / SF-134-02 — Des marqueurs à portée

## Identifiant
`F-134 / SF-134-02`

## Feature parente
`F-134` — Le cache qui ne prend pas

## Statut
`draft` — en attente de validation PO

## Date de création
2026-09-20

## Branche Git
`feat/SF-134-02-des-marqueurs-a-portee`

---

## Objectif

Poser les marqueurs de cache là où le fournisseur peut les retrouver — y compris sur les tours
longs, où il ne remonte pas assez loin pour voir le précédent.

---

## Le défaut

Pour retrouver le ruban précédent, le fournisseur part de notre **marqueur** et **remonte au plus
vingt positions**. Au-delà, il ne trouve rien et **réécrit tout, sans rien signaler**.

Un tour d'agent à quinze étapes ajoute une trentaine de positions. Le repère du tour précédent est
alors hors de portée.

Nous posions **deux** marqueurs :

| Segment | Marqueur | Position dans le ruban |
|---|---|---|
| `tools` | **aucun** | **en tête** |
| `system` | oui | après les outils |
| messages | un seul, sur le tout dernier bloc | à la fin |

La panoplie d'outils ouvre le ruban et n'était protégée par rien.

---

## La correction

Le fournisseur accepte **quatre** marqueurs par requête. On les dépense aux quatre endroits utiles :

1. **sur le dernier outil** — le segment le plus en amont, jusqu'ici nu ;
2. sur la consigne système (inchangé) ;
3. **sur un message intermédiaire**, une quinzaine de positions avant la fin, pour qu'un repère
   reste toujours à portée des vingt ;
4. sur le dernier bloc (inchangé).

Rien du **contenu** ne change : on ajoute des repères, c'est tout.

---

## Comportement attendu

### Cas nominal
1. La panoplie d'outils porte un marqueur sur son dernier outil.
2. Les messages en portent deux : un intermédiaire et le dernier.
3. La requête n'en compte **jamais plus de quatre** au total.

### Cas d'erreur

| Situation | Comportement attendu |
|---|---|
| Aucun outil déclaré | aucun marqueur d'outil, pas d'erreur |
| Conversation trop courte pour un intermédiaire | un seul marqueur de message, comme aujourd'hui |
| Consigne système absente | aucun marqueur système, comme aujourd'hui |

---

## Critères d'acceptation

- [ ] Le **dernier outil** déclaré porte un marqueur de cache.
- [ ] Une conversation de plus de seize messages porte **deux** marqueurs : un intermédiaire et le dernier.
- [ ] Une conversation courte n'en porte qu'**un**, comme aujourd'hui.
- [ ] Le total de marqueurs d'une requête ne dépasse **jamais quatre**.
- [ ] Le marqueur intermédiaire ne tombe **jamais** sur une consigne d'effort — elle ne porte aucun contenu et le refuserait.
- [ ] Le **contenu** envoyé est inchangé, marqueurs mis à part.

---

## Périmètre

### Hors scope
- Le TTL des marqueurs : il reste d'une heure (F-130 / SF-130-01).
- L'affichage (SF-134-03).

---

## Technique

| Classe | Changement |
|---|---|
| `AnthropicAgentProvider.toApiTools` | marqueur sur le dernier outil |
| `AnthropicAgentProvider.toApiMessages` | marqueur intermédiaire, en plus du dernier |

Aucun endpoint, aucune table, aucune migration.

---

## Plan de test

### Tests unitaires
- [ ] Le dernier outil porte un marqueur ; les autres non.
- [ ] Une longue conversation porte deux marqueurs de message, aux bons endroits.
- [ ] Une conversation courte n'en porte qu'un.
- [ ] Le total ne dépasse jamais quatre.
- [ ] Une consigne d'effort n'est jamais marquée.
- [ ] Sans outil, la requête reste valide.

---

## Préoccupations transversales

| Préoccupation | Cochée | Composants impactés |
|---|---|---|
| **Plans / limites** | **oui** | Le fournisseur plafonne à quatre marqueurs : un cinquième ferait **échouer la requête**. Le décompte est donc vérifié par test |
| Auth / Principal | non | aucun changement |
| Navigation / routing | non | aucun écran |

---

## Estimation

**0,5 jour.**
