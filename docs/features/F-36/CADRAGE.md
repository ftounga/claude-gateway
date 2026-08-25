# Cadrage — F-36 : Plafond de dépense et facturation au coût réel

## Identifiant / Statut / Date

`F-36` · `cadré, décisions par défaut prises` · 2026-08-25

## Objectif

Garantir qu'**aucune session ne peut coûter plus que ce que l'utilisateur a payé**, et facturer sur le
**coût réel** plutôt que sur un décompte de tokens qui ne le capture pas.

## Le problème

Le quota est en **tokens** ; le coût est en **dollars**. Les deux ne coïncident pas : le tarif dépend
du modèle servi, et le décompte ignore les recherches web (10 $ / 1 000) et le temps de sandbox.

Surtout, **le contrôle est post-run** : `assertWithinQuota` bloque le run *suivant*. Un seul run peut
donc dépasser le quota mensuel entier, et le dépassement n'est constaté qu'après — quand la dépense
est déjà engagée et irrécupérable.

Marge au quota plein, coût blended Opus ≈ 8,3 €/M :

| Plan | Prix | Quota | Marge | Markup |
|------|------|-------|-------|--------|
| SOLO | 24 € | 1 M | 65 % | 2,9× |
| PRO | 99 € | 5 M | 58 % | 2,4× |
| **GOLD** | **199 €** | **12 M** | **50 %** | **2,0×** |

Gold est le plan **le moins margé**, et c'est celui qui porte l'Atelier — l'usage le plus vorace.

## Ce que le fournisseur offre déjà (et que nous n'utilisons pas)

**Les budgets de session** — un plafond de dépense **dur**, posé à la création :

```json
"budget": { "type": "limit", "max_list_cost": { "amount": "500", "currency": "USD" } }
```

- La plateforme facture en continu au **tarif public** : tokens au prix du modèle servi, recherches web
  à 10 $/1 000, temps de session à 0,08 $/h.
- **L'application est un verrou pré-requête** : avant chaque appel au modèle, la plateforme vérifie le
  cumul et met le thread en pause. Il **empêche**, il ne constate pas.
- Dépassement maximal : **une requête modèle par thread en vol**.
- Montant en **unités mineures, en chaîne** (`"500"` = 5,00 $) ; les formes décimales sont rejetées.
- **Création uniquement** : ajouter un budget après coup renvoie 400. Le modifier est possible, à
  condition que la nouvelle valeur dépasse le coût déjà consommé.
- **Multi-agents** : un seul budget partagé entre tous les threads — donc les sous-agents (F-35) sont
  bornés par construction.

## Décisions par défaut (à contredire si besoin)

| # | Décision | Pourquoi |
|---|----------|----------|
| D1 | **Chaque session reçoit un budget** = min(quota restant converti en dollars, plafond par run) | Le quota devient une garantie structurelle, plus une surveillance a posteriori |
| D2 | Plafond par run configurable, défaut **2,00 $** ; **5,00 $** quand la délégation est active | Borne le pire cas d'un run unique, indépendamment du quota restant |
| D3 | Le décompte utilise le **coût réel** (`list_cost`) quand il est disponible, avec repli sur les tokens | Le coût réel capture ce que les tokens ignorent : modèle servi, recherches web, temps de sandbox |
| D4 | **Markup configurable**, défaut **2,0×** sur le coût réel | Levier de marge ajustable par configuration, sans redéploiement |
| D5 | Tarifs de référence du rapport d'usage corrigés : **5 $/25 $** (Opus) au lieu de 3/15 (Sonnet) | Le rapport sous-estimait le coût d'environ 40 % — il décrivait un modèle que l'Atelier n'utilise pas |
| D6 | Budget atteint → message dédié, distinct du quota épuisé, invitant au rachat (F-21) | « Ce run a atteint son plafond » n'est pas « votre quota mensuel est épuisé » |

## Découpage

| SF | Contenu |
|----|---------|
| **SF-36-01** | Budget de session dérivé du quota restant + plafond par run (backend) |
| **SF-36-02** | Décompte au coût réel avec markup configurable ; repli tokens (backend) |
| **SF-36-03** | Correction des tarifs de référence du rapport d'usage (backend, configuration) |
| **SF-36-04** | Message dédié « plafond du run atteint » et lien vers le rachat (frontend) |

## Pièges identifiés

- **Le budget est création-seule** : impossible d'en ajouter un à une session existante. Or la session
  est **persistante** (SF-30-04) — une session ouverte avant cette feature n'aura jamais de budget. Il
  faut donc soit accepter cette transition, soit forcer une réouverture. **Décision par défaut** :
  accepter — les sessions existantes finiront par être réinitialisées, et le quota post-run continue de
  s'appliquer entre-temps.
- **Un budget partagé, pas par thread** : avec F-35, un thread peut se mettre en pause pendant qu'un
  autre finit. C'est le comportement voulu, mais l'écran doit savoir le dire.
- **Modèle sans tarif public** → une création budgétée est refusée (400). Le modèle de l'agent doit
  toujours être un modèle tarifé.
- **`list_cost` est arrondi au cent** alors que l'application compare des montants exacts : ne pas
  déduire l'état « en pause » du montant affiché, mais de `stop_reason: budget_reached`.

## Hors scope

Facturation à l'usage monétisée (OQ-08, toujours ouverte) ; refonte de la grille tarifaire ; plafonds
par utilisateur pilotés depuis la console d'administration.

## Effet attendu

Le pire cas d'un run passe d'**illimité** à **2 $** (5 $ avec délégation). F-35 devient livrable sans
risque de dépense non bornée : c'est le préalable, et il doit être livré **avant**.
