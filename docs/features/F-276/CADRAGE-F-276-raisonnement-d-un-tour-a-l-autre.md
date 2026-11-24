# CADRAGE F-276 — Le raisonnement survit d'un tour à l'autre

**Statut :** cadrage du 2026-11-24 · **Demande PO (2026-11-24)** : « Oui, cadre et livre F-276. C'est grave. »

**Règle qui gouverne :** justesse avant coût. Le modèle ne voit jamais moins qu'aujourd'hui ; ici, il retrouve ce qu'il a réellement vu et pensé.

## Constat prouvé (prod, 2026-11-17 → 2026-11-23)

- Journal « Raisonnement écarté par le fournisseur » (CloudWatch `/aws/eks/legalcase-shared/claude-gateway`) : environ **2 370 appels sur 7 jours**, avec une moyenne d'environ **28 blocs écartés par appel**, tous pour la raison `prefix_binding_mismatch`. Les chemins partent de `messages.1.content.0`.
- Conséquence : **aucun raisonnement d'un tour passé n'atteint le modèle**. Seul celui du tour en cours survit.
- SF-134-04 (« le raisonnement survit d'un tour à l'autre ») est donc **sans effet depuis F-172**, qui a posé `block_binding: drop_block` le 2026-10-02.
- `atelier_turn_measures` (F-229) : sur 7 jours, le ruban du 1er appel quitte celui du tour précédent au bloc **128** en médiane, c'est-à-dire au **début du tour précédent**, et pas en tête. Sur 14 jours, le rangement retire en moyenne **12 100 caractères par tour** (p90 34 000, p99 208 000).
- Le cache réécrit environ **23 000 tokens au 1er appel de chaque tour**, même quand le PO revient en moins de 5 minutes.

## Cause (lue dans le code, cohérente avec les deux mesures)

Le fournisseur lie chaque bloc de raisonnement à **tout ce qui le précédait quand il a été produit** : `system`, `tools`, messages. Notre rejeu ne renvoie pas ce qui a été envoyé, mais **le reconstruit depuis la base** (`buildReplayMessages` → `replayableHistory`) :

1. **La consigne augmentée** (contexte du sujet F-171, faits F-137, rappel F-148, plan reporté, attentes, messages envoyés, parcours, notes Jarvis) est envoyée, mais seule la **parole nue** est rejouée.
2. Les **résultats d'outils sont coupés à 8 000 caractères**, et la trajectoire est plafonnée à 60 000 (`AtelierToolTrace`).
3. Les **consignes d'effort** glissées dans la conversation (F-134 / SF-134-05) ne sont pas rejouées.
4. Les **précisions** du tour sont rejouées comme des messages utilisateur à part, et non comme le message étiqueté réellement envoyé (F-121 / SF-121-11).
5. Le **texte final** rejoué est le « texte de fond » retenu (F-125), et non la dernière réponse réelle. L'ordre des blocs est recomposé (raisonnement, texte, puis appels).

Chaque écart est une édition d'un tour déjà envoyé. Le guide officiel la classe en **tier 1-2** (accidentelle ou réparable), et la forme correcte est **append-only** : rejouer le transcript tel qu'il est parti.

Les autres causes possibles (`system` ou `tools` qui changent d'un tour à l'autre) ne sont **pas prouvées**. SF-276-01 les mesure avec l'outil officiel au lieu de les deviner (leçon F-134 : une cause trouvée, quatre réelles).

## Effet attendu (honnête)

- **Justesse** : le modèle retrouve le raisonnement de ses tours passés, ce qui est la parité avec Claude Code. Il retrouve aussi ce qu'il a réellement lu : consigne complète et résultats entiers, sous les bornes actuelles.
- **Coût**, avec deux effets de sens contraire et un solde inconnu avant mesure :
  - **à la hausse** : relectures supplémentaires, soit environ 3 500 tokens de résultats de plus par tour rejoué (moyenne), plus les blocs de raisonnement qui ne sont plus écartés. Les blocs écartés n'étaient pas facturés.
  - **à la baisse** : la réécriture d'environ 23 000 tokens au 1er appel d'un tour rapproché doit tomber à quelques milliers. L'écriture coûte 40 fois la lecture.
- Rien n'est promis en euros. Le bilan à J+7 tranche.

## Périmètre

| SF | Objet | Estimation |
|---|---|---|
| **SF-276-01** | Mesurer avec les outils du fournisseur : diagnostic de cache officiel à chaque appel, et raisonnement envoyé ou écarté, rangés par tour | ≤ 1 j |
| **SF-276-02** | Rejouer le ruban envoyé : chaque tour est rangé tel qu'il est parti, puis rejoué à l'identique | ≤ 2 j |
| SF-276-03 *(conditionnelle)* | Stabiliser `system` ou `tools` **si** SF-276-01 mesure `system_changed` ou `tools_changed` d'un tour à l'autre | à chiffrer |

**F-229 / SF-229-02** (« rejouer ce qui a été envoyé », gardée par une porte de gain ≥ 5 %) est **absorbée** par SF-276-02. Le motif a changé : c'est la justesse, et non le coût, donc la porte de gain ne s'applique plus. F-229 est close.

## Bilan à J+7 (à présenter au PO)

- Part des appels qui ont des blocs écartés ; blocs écartés au 1er appel d'un tour. Cible : 0, hors paliers de la fenêtre et compactions.
- Diagnostic de cache des premiers appels, par cause (`messages_changed`, `system_changed`, `tools_changed`…).
- `first_pass_cache_write` des retours en moins de 60 min, avant et après.
- Coût par tour en €, avant et après, à activité comparable.
- Coupe-circuit : `APP_ATELIER_REPLAY_SENT_RIBBON=false` rétablit le rejeu d'avant sans livraison, et `APP_AGENT_CACHE_DIAGNOSTICS=false` coupe le diagnostic.

## Contraintes

- **Justesse** : aucune réduction de contexte. Le seuil de compaction et son estimateur sont **inchangés** : compacter plus tôt retirerait du contexte. Le volume réellement rejoué est mesuré.
- **Préfixe stable** : rien de volatil n'entre dans `system`.
- **Mesure** : des nombres et des types, jamais de contenu (règle F-188).
- **Isolation** : le ruban vit sur le message de l'utilisateur, filtré par `user_id` et par terminal comme le rejeu actuel.
- **Provider Independence** : le ruban est en types `AgentMessage` neutres. Le diagnostic voyage en champs neutres (`previousResponseId`, `AgentCallDiagnostics`) ; seul `AnthropicAgentProvider` parle `diagnostics` et `input_transformations`.
- **Préoccupations transversales** (auth, tenant, plans, navigation) : aucune.
