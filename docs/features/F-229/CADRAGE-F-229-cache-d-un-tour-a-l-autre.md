# CADRAGE F-229 — Le cache survit d'un tour à l'autre

**Statut :** cadrage du 2026-11-01 · **Demande PO (2026-11-01)** : « Le 2 m'intéresse » (réduire la facture sans toucher au raisonnement), puis « oui cadre ».

**Règle qui gouverne tout le cadrage :** justesse avant coût (le modèle ne doit jamais voir moins que ce qu'il voit aujourd'hui).

## Constat mesuré (prod, `atelier_turn_measures`, 30 jours au 2026-11-01, 778 tours, Opus 5.5)

- **Socle** : chaque appel relit environ **147 000 tokens** de contexte, que le tour fasse 1 étape ou 80 (médiane : 4 étapes). Un « gros tour » est un tour à beaucoup d'étapes multipliées par ce socle. Moyenne hebdomadaire du contexte maximal : 104 k (semaine du 05/10) → 170 à 180 k depuis.
- **Facture** (estimée à partir des écarts de prix standards) : écriture du cache ≈ **52 %**, relecture ≈ 32 %, sortie ≈ 16 %. L'écriture coûte environ 20 fois la relecture.
- **Écriture au premier appel d'un tour** : 37 M de tokens, soit la moitié des écritures. Ventilation selon le délai depuis le tour précédent du même fil :

| Délai | Tours | Tokens écrits au 1er appel (moyenne) |
|---|---|---|
| < 5 min | 463 | ~28 000 |
| 5 à 60 min | 181 | ~33 000 |
| > 60 min (TTL expiré) | 116 | ~143 000 (tout le contexte) |

Quand le PO enchaîne dans l'heure, le cache est vivant. Seuls devraient alors s'écrire son nouveau message et la dernière réponse, soit quelques milliers de tokens. **On en écrit environ 28 000** : le ruban rejoué au tour N+1 ne coïncide pas avec celui qui a été mis en cache au tour N.

## Cause probable, lue dans le code (à prouver par la mesure)

`buildReplayMessages` reconstruit l'historique depuis la base, et cette version **diffère de ce qui a été envoyé pendant le tour** :

1. **Résultats d'outils tronqués à 8 000 caractères** au stockage (`AtelierToolTrace.MAX_RESULT_CHARS`), alors que le tour les a envoyés en entier (bash jusqu'à 128 Ko). Le ruban diverge dès le premier résultat long du tour précédent, et tout ce qui suit est réécrit.
2. **Trajectoire plafonnée à 60 000 caractères** (`MAX_TRACE_CHARS`) : les étapes les plus anciennes du tour sont abandonnées.
3. **Raisonnement non rejouable** quand la réponse l'entrelaçait avec des outils serveur (`reasoningReplayable`).
4. **`clear_tool_uses`** appliqué pendant le tour, et la compaction (le résumé change).

F-134 a corrigé un cas de la même famille (le raisonnement omis au rejeu). Sa mesure, sur un fil de deux tours aux sorties courtes, ne déclenchait aucune troncature.

Leçon de F-134 (une cause trouvée, quatre réelles) : on mesure **le point de divergence**, sans présumer de la cause.

## Écarté par la mesure : garder le cache au chaud par appels périodiques

Délai de retour dans un fil : 639 retours en moins de 55 min, 43 entre 1 et 4 h, 78 au-delà de 8 h, 18 jamais.

Un appel de relance (environ 0,1 × le contexte toutes les 55 min) ne sauverait que les retours entre 1 et 4 h. Il coûterait aussi sur tous les fils abandonnés ou repris le lendemain. **Gain net estimé : 1 à 2 %.** Ne pas le reproposer sans nouvelle mesure.

## Périmètre

| SF | Objet | Estimation |
|---|---|---|
| **SF-229-01** | Mesurer où et pourquoi le ruban diverge d'un tour à l'autre (empreintes de blocs, aucun contenu) | ≤ 1 j |
| **SF-229-02** | Rejouer ce qui a été envoyé : le ruban du tour N+1 prolonge celui du tour N. **Conditionnel** au verdict de SF-229-01 et à la porte de décision. | ≤ 2 j |

**Porte de décision entre SF-01 et SF-02** : après 7 jours de mesure, présenter au PO :
- la part des tokens perdus par cause ;
- le gain net estimé de la correction (écritures évitées moins relectures supplémentaires, en €).

SF-229-02 ne démarre que si ce gain net est d'au moins **5 % de la facture** et si le PO valide.

**Gain visé** : jusqu'à ~11 à 14 % de la facture (~130 à 165 €/mois), à confirmer par SF-229-01.

## Contraintes

- **Justesse** : la correction ne retire jamais rien au modèle. L'option « tronquer aussi pendant le tour » est **exclue** (échange contexte contre coût, cf. F-163 abandonnée).
- **Préfixe stable** : rien de volatil ne doit entrer dans la consigne système.
- **Mesure** : uniquement des nombres et des empreintes, jamais de contenu (règle de F-188).
- **Isolation** : la mesure est rattachée au couple `user_id` / terminal / chantier, comme `atelier_turn_measures`.
- **Préoccupations transversales** (auth, tenant, plans, navigation) : aucune.
