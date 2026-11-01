# SF-229-02 — Rejouer ce qui a été envoyé

**Feature :** F-229 · **Statut :** blocked (porte de décision après 7 jours de mesure de SF-229-01) · **Estimation :** ≤ 2 j · **Branche :** `feat/SF-229-02-rejouer-ce-qui-a-ete-envoye`

## Objectif

Le ruban du tour N+1 **prolonge** celui du tour N : ce qui était en cache est relu au lieu d'être réécrit, et le modèle ne voit jamais moins qu'aujourd'hui.

## Principe (à préciser selon les causes mesurées)

- **Mémoire bornée.** On ne tronque plus au stockage le résultat d'un tour encore « chaud ». On conserve le résultat tel qu'envoyé, sous une borne de sécurité alignée sur les plafonds d'outils. La réduction à 8 000 caractères est **reportée à une mutation déjà existante** : le glissement par paquets de la fenêtre de rejeu (`firstTracedIndex`, une mutation tous les 12 tours) ou la compaction. Le cache est alors réécrit une fois par paquet, au lieu d'une fois par tour.
- **Trajectoire.** Même traitement pour `MAX_TRACE_CHARS`.
- **Causes mineures** (`REASONING_DROPPED`, `CLEARED_IN_TURN`) : traitées seulement si la mesure leur attribue au moins 2 % de la facture.

## Porte de décision (préalable)

On présente au PO les chiffres de SF-229-01 sur 7 jours :
- les tokens perdus par cause ;
- le gain brut (écritures évitées × 2) ;
- le surcoût (relectures supplémentaires × 0,1 × nombre d'appels) ;
- le gain net en €.

SF-229-02 démarre seulement si le gain net est d'au moins 5 % de la facture **et** si le PO valide. La mini-spec est complétée à ce moment-là (critères d'acceptation et plan de test chiffrés).

## Critères d'acceptation (à compléter à la porte)

- [ ] Deux tours consécutifs avec une sortie longue : `prefix_divergence_index = -1` (ruban prolongé).
- [ ] Le modèle voit au moins le même contenu qu'avant, à chaque tour (aucune réduction de contexte).
- [ ] Sur 7 jours en prod, `first_pass_cache_write` moyen des retours en moins de 60 min passe de ~28 k à moins de 8 k tokens.

## Hors périmètre

- Tronquer les résultats pendant le tour : **exclu**, règle « justesse avant coût ».
- Les seuils de compaction : inchangés.
