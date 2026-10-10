# Cadrage F-188 — Les gros tours

**Origine** : recommandation 4 de l'audit J+7 du 2026-10-10 (« prochain levier de coût : les très gros tours »), validée par le PO. Analyse complète : `ANALYSE-F-188-gros-tours.md`.

## Constat

- Un gros tour est **long, pas lourd** : environ 20 appels au modèle contre 3, avec le même contexte (environ 150 k par appel, dont environ 92 k fixes).
- **Cause principale : un bug de justesse.** `AnthropicAgentProvider` ne garde que le texte, le raisonnement et les appels d'outils. Les blocs d'outils serveur (`server_tool_use`, `web_search_tool_result`, `web_fetch_tool_result`) sont jetés d'une étape à l'autre, et `pause_turn` est pris pour une fin de tour.
  - L'agent ne voit plus les preuves qu'il vient de lire. Fidèle à la doctrine de vérification, il conclut que rien n'a été vérifié, recherche de nouveau, puis réécrit.
  - Exemples : le 10-04, une même commande 23 fois avec 104 recherches et 8 « continue » du PO ; le 10-05, 57 recherches et 12 réécritures, puis interruption par le PO.
- 8 tours avec recherche web coûtent 14 % de la facture et comptent 5 des 6 tours au-delà de 5 M tokens.
- Le plafond de 100 étapes n'a été atteint qu'une fois, et c'était cette boucle. La compaction ne s'est jamais déclenchée au milieu d'un tour.
- Seconde cause, mineure : un poste « occupé » (une commande déjà en cours) est renvoyé au modèle comme un refus, et le modèle relance à l'identique (20 refus le 10-04).

## Décisions

- **D1 — Justesse d'abord** (règle absolue du PO) : on corrige le bug ; le gain de coût n'en est qu'une conséquence.
- **D2 — Plafond d'étapes inchangé (100)** : le baisser aurait coupé un chantier légitime (10-06 : 13 tickets).
- **D3 — Pas de compaction plus tôt dans le tour, pas de délégation forcée** : gain faible, risque de perdre des preuves.
- **D4 — Les résultats web restent dans le contexte du tour**, comme dans Claude Code. Entre deux messages, rien ne change : seul le texte est persisté.

## Découpage

| SF | Titre | Estimation |
|---|---|---|
| SF-188-01 | **Les preuves web gardées** : blocs d'outils serveur rejoués à l'identique dans le tour, `pause_turn` traité comme une reprise | 1,5 j |
| SF-188-02 | **Poste occupé : attendre au lieu de refuser** (attente bornée côté serveur) | 0,5 j |
| SF-188-03 | **Mesure par tour** : étapes, motif d'arrêt, plus gros contexte, écritures de cache, persistés pour prouver les leviers suivants | 1 j |

À décider avec le PO **après** la mesure de SF-188-03 (pas avant) :

- détection des répétitions (note au modèle ou arrêt, et seuils) ;
- réglage de l'effacement des vieilles sorties d'outils (`clear_tool_uses`) ;
- point d'étape non bloquant au-delà de N étapes.

## Préoccupations transversales

Aucune côté auth, tenant, limites ou navigation. Le changement porte sur la boucle d'agent et le fournisseur, derrière l'interface `AiAgentProvider` (Provider Independence).

## Hors périmètre

Changement de modèle, baisse du plafond d'étapes, compaction intra-tour.
