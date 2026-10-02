# Audit F-172 — Un modèle plus fort aurait-il grandement aidé ?

> Audit du 2026-10-02, en lecture seule sur la prod (`atelier_messages`, `usage_turns`).
> Question du PO : « est-ce que Fable 5.1 vaut le coût de sa mise en place ? »

## 1. Matériau

- **1 335 messages** (696 du PO), 13 espaces, du 2026-08-29 au 2026-10-02, un seul utilisateur (le PO).
- **412 tours chiffrés** (depuis le 2026-09-20), tous sur `claude-opus-5`, pour **661,78 $**.
- Méthode : six lots relus **en entier** par six relecteurs indépendants, avec une grille fermée.
  Chaque incident (relance ou correction du PO, « continue », auto-correction de l'agent) est cité
  mot pour mot et rattaché à **une** cause racine. Pour chacun, le relecteur dit si un modèle plus
  fort l'aurait évité. Consolidation faite sur les fichiers JSON, pas sur les résumés.

## 2. Ce que disent nos sessions

| Messages du PO | Nb | Part |
|---|---|---|
| Nouvelle demande / suite normale | 438 | 63 % |
| Relance ou correction | 115 | 17 % |
| « continue » / reprise après coupure | 105 | 15 % |
| Test du produit (méta) | 38 | 5 % |

**194 incidents.** Répartition par cause :

| Cause | Nb | Un modèle plus fort l'évite ? |
|---|---|---|
| H2 Harnais (plafonds, limite d'étapes, réponses vides, contrôle de fin de tour qui remplace la réponse, perte de contexte) | 63 | non |
| H1 Vérification (affirme sans vérifier, délègue à l'humain) | 56 | partiellement |
| M2 Compréhension de la demande (surtout longueur et forme) | 33 | partiellement |
| M1 Raisonnement | 22 | 10 oui probable, 12 partiellement |
| I Infra (runner, proxy, SSO) | 12 | non |
| U Demande | 8 | non |

**Gravité :** 132 mineurs, 58 moyens, 4 graves. Sur les 4 graves, **un seul** est un défaut de
raisonnement qu'un modèle plus fort aurait probablement évité : la sortie par la transit gateway
conseillée alors que les nœuds sont dans la plage CG-NAT (2026-09-26). Les trois autres sont des
défauts de vérification avant d'agir : `tccutil reset` sous MDM, push forcé d'un rebase
inachevé, apply d'un plan Atlantis périmé.

**Verdict des six relecteurs, unanime :** « aide modérée, pas grande ». Le premier gisement de
friction est le **harnais**. À lui seul, il produit 63 incidents et la plupart des 105 « continue ».

## 3. Ce que disent les sources officielles

Annonce Opus 5.5 (22/09/2026), Terminal-Bench 4.0 :
**Opus 5.5 = 66,4 %**, **Fable 5.1 = 55,8 %**, **Opus 5 (actuel) = 52,3 %**. Aucun benchmark publié
ne place Fable 5.1 devant Opus 5.5, et Anthropic précise que l'écart réel est plus étroit que les
scores.

Rapporté au taux d'échec, Fable 5.1 retire **≈ 7 %** des échecs d'Opus 5, et Opus 5.5 **≈ 30 %**.

## 4. Gain attendu (estimation, bornée)

Part sensible au modèle : 10 incidents « oui probable » + 100 « partiel » comptés pour moitié, soit
**≈ 60 équivalents-incidents sur 194**.

| Modèle | Incidents évités (estimation centrale, borne haute ×2) | Coût sur nos tours réels |
|---|---|---|
| Opus 5 (actuel) | — | 661,78 $ |
| **Fable 5.1** | **≈ 4 (2 %)**, au plus ≈ 8 (4 %) | **≈ 1 106 $ (+67 %)** |
| **Opus 5.5** | **≈ 18 (9 %)**, au plus ≈ 36 (19 %) | **≈ 471 $ (−29 %)** |

Les coûts sont calculés à volume de tokens constant. Fable 5.1 fait en outre moins d'appels
d'outils en parallèle, donc plus d'allers-retours : son surcoût réel serait **plutôt supérieur**.

## 5. Conclusion

- **Fable 5.1 ne vaut pas le coût.** Il est 67 % plus cher, sous Opus 5.5 sur tous les benchmarks
  publiés, et le gain attendu sur nos sessions est marginal.
- **Opus 5.5 est le bon changement de modèle.** Il est moins cher et meilleur. Le gain de qualité
  reste modéré, de l'ordre de 10 à 20 % des incidents. Le gain de coût, lui, est acquis.
- **Le grand levier n'est pas le modèle.** C'est le harnais (H2) et la discipline de vérification
  (H1) : 119 incidents sur 194, et les 3 graves restants.

## 6. Ce qu'un passage à Opus 5.5 exige (changements cassants)

- **Refus :** Opus 5.5 porte les mêmes classifieurs cyber que Fable 5.1, plus larges que ceux
  d'Opus 5. Le travail infra et sécurité du PO y est exposé. SF-172-01/02 (refus visible, repli
  serveur) devient un **prérequis**.
- **Narration :** le texte écrit entre deux appels d'outils arrive désormais dans des blocs
  `thinking`, vides par défaut. Le terminal deviendrait muet entre deux outils tant qu'on ne pose pas
  `thinking.display`.
- **Raisonnement :** il est toujours actif, et les blocs de raisonnement sont liés au modèle et à la
  conversation. La compaction F-117 réécrit l'historique ; on doit retirer les blocs `thinking` des
  tours résumés.
- **Choix d'outil forcé :** `tool_choice` `any` / `tool` est refusé. Le code n'en utilise pas
  (vérifié).

## 7. Limites de l'audit

- La classification a été faite par des relecteurs IA en un seul passage, sans double codage. Les
  frontières H1/M1 et H2/M1 sont parfois discutables ; deux relecteurs l'ont signalé.
- Les longues réponses de l'agent sont tronquées au milieu dans l'extraction.
- Un seul utilisateur, sur cinq semaines.
- L'effet d'un autre modèle est **estimé** à partir des benchmarks publics, pas mesuré sur nos
  sujets.
- Une réponse vide inexpliquée peut cacher un **refus** que le code actuel ne distingue pas
  (`stop_reason` non journalisé). Leur nombre réel est inconnu tant que SF-172-01 n'est pas livrée.
