# Cadrage — F-63 — Le quota compte au coût réel

> Source produit : `docs/PRODUCT_SPEC.md` (F-63), `docs/STRATEGIE-TARIFAIRE.md` §2 et §6,
> `docs/TARIFS.md` §8.1 (place réservée) et §9.
> **Aucun prix, aucun quota, aucun montant commercial n'est décidé ici.** Le mécanisme est livré ;
> les valeurs restent en configuration, avec leurs défauts actuels préservés.

---

## 1. Le défaut, tel qu'il est dans le code

Le décompte du quota traite **tous les tokens à l'identique** : `QuotaService.recordUsage`
additionne entrée et sortie, et `assertWithinQuota` compare cette somme au quota du plan. Chez le
fournisseur, la **sortie coûte cinq fois l'entrée** (Opus 5 : 5 $/M contre 25 $/M) et une **lecture
de cache** coûte un **dixième** de l'entrée. Trois conséquences, toutes lisibles dans le dépôt :

| # | Où | Ce qui se passe aujourd'hui |
|---|---|---|
| 1 | `/chat`, `/ask`, boucle Atelier (F-39) | un token de sortie pèse autant qu'un token d'entrée → la marge dépend du **style d'usage** du client (69 % en agentique, 30 % en génération, `STRATEGIE-TARIFAIRE.md` §2) |
| 2 | `AnthropicAgentProvider` (le moteur du terminal) | `input_tokens + cache_creation + cache_read` sont additionnés et décomptés **au plein tarif d'entrée** (décision D3 de SF-39-01, « le quota mesure ce qui a été traité »). Un tour de 30 itérations pèse ≈ 1,35 M tokens traités pour ≈ 1,27 $ réellement facturés : le client paie au plein tarif des tokens relus au dixième |
| 3 | `AtelierSessionService.billedFromCost` (Managed Agents) | ce chemin-là décompte **déjà** au coût réel rapporté par le fournisseur, mais le convertit au taux **« blended » 9 $/M**. Les deux chemins du même produit ne comptent donc pas la même chose pour les mêmes tokens |

La ligne 2 est la plus coûteuse pour le client, la ligne 1 la plus coûteuse pour nous.

## 2. Ce que F-63 livre

Une seule notion nouvelle : les **tokens facturés**, à côté des **tokens traités**.

- **Tokens traités** (`usage_counters.input_tokens` / `output_tokens`, `usage_turns`) : inchangés.
  Ce sont des **volumes**, et c'est d'eux que vivent le rapport d'usage (F-16), la consommation par
  client et la console d'administration (F-61).
- **Tokens facturés** (`usage_counters.billed_tokens`, colonne neuve) : ce que le quota oppose.
  Chaque nature de token y entre **au prix de sa nature**.

```
coût du tour ($)  = (entrée×Pe + sortie×Ps + lecture_cache×Pc + écriture_cache×Pw) ÷ 1 000 000
                    ou, si le fournisseur rapporte lui-même le coût (Managed Agents), CE coût
tokens facturés   = coût du tour × markup ÷ Pq × 1 000 000
```

`Pe`, `Ps`, `Pc`, `Pw` sont les **tarifs du fournisseur**, `Pq` la **valeur d'un token de quota** et
`markup` le levier commercial — **tous en configuration** (`app.atelier.agent.cost.*`), aucun en
dur, parce qu'ils changent quand le fournisseur change ses tarifs.

## 3. Les arbitrages

### A-1 — La valeur d'un token de quota reste 9,00 $/M (le défaut actuel, inchangé)

`cost-per-million-tokens: 9.00` était documenté comme le « coût blended » du fournisseur. Cette
justification-là disparaît : une fois l'entrée et la sortie distinguées, il n'y a plus de coût moyen
à approximer. Le paramètre, lui, ne disparaît pas — il change de **rôle** et garde sa **valeur** :
il dit désormais **ce que vaut un token de quota**, c'est-à-dire le coût fournisseur qu'un token de
quota représente. Il est renommé `quota-token-cost-per-million-tokens`, l'ancienne variable
d'environnement reste honorée en repli, et les deux lectures qui en dépendent (`max-run-cost` via
`sessionBudget`, la conversion d'un coût en tokens) continuent de fonctionner à l'identique.

**Pourquoi ne pas prendre 5,00 (« un token de quota = un token d'entrée »)**, formulation littérale
de `STRATEGIE-TARIFAIRE.md` §2 : parce que ce serait **décider un montant**. Le chemin Managed
Agents décompte déjà `coût ÷ 9` ; passer à `÷ 5` multiplierait par 1,8 la vitesse de consommation
de tout utilisateur de la Forge — une coupe de 44 % du quota effectif, décidée par un agent, sans
annonce. Conserver 9,00 préserve exactement ce chemin et **ne bouge aucune valeur**. Le PO garde la
main : c'est un réglage, à une ligne, documenté dans `TARIFS.md`.

**Ce que le choix de 9,00 produit mécaniquement** : le ratio 5:1 entre sortie et entrée est
respecté (c'est lui que la stratégie exige) ; l'échelle absolue, elle, est celle d'hier. Le point de
bascule tombe à **4:1** — au-delà (usage agentique, 38:1 relevé en production) le client consomme
son quota **moins vite** qu'avant, en deçà (génération, 3:1) **plus vite**. Et surtout la marge
cesse de dépendre du style : le coût fournisseur maximal d'un quota devient `quota × 9 $/M`, quel
que soit l'usage. C'est la promesse de la feature, littéralement.

*Réversible* : une valeur de configuration.

### A-2 — Deux compteurs, pas un seul réinterprété

Écrire le décompte pondéré **dans** `input_tokens`/`output_tokens` aurait été plus court, et aurait
cassé trois écrans : le coût estimé de F-16 et F-61 se calcule à partir de ces colonnes (5 $/M et
25 $/M), il aurait été appliqué à des tokens déjà pondérés — un coût au carré. Une colonne neuve
sépare **ce qu'on a traité** de **ce qu'on facture**. Les deux chiffres existent désormais et sont
tous deux justes ; l'écran doit dire lequel il montre (SF-63-03).

*Réversible* : une colonne additive, jamais lue par l'ancien code.

### A-3 — Reprise : `billed_tokens = input_tokens + output_tokens` sur les lignes existantes

La migration recopie le total brut dans la colonne neuve. Partir de zéro offrirait un quota gratuit
à toute période en cours ; recalculer rétroactivement fabriquerait une consommation que personne n'a
faite (et les natures de tokens du passé ne sont pas connues). **Le changement de comptage ne vaut
que pour les tours à venir.**

*Réversible au sens des données* : c'est une copie, rien n'est perdu.

### A-4 — Le chemin Managed Agents cesse de gonfler les volumes

`billedFromCost` convertissait le coût en « équivalent tokens » puis rangeait cet équivalent dans
les compteurs bruts, au prorata. Les volumes affichés par F-16/F-61 n'étaient donc, sur ce chemin,
ni des tokens traités ni rien d'autre. Désormais : les compteurs bruts reçoivent les **deltas réels**
rapportés par le fournisseur, et le coût réel n'alimente plus que `billed_tokens`. Le relevé de tour
affiché à l'écran (F-30) montre lui aussi les tokens réels du tour.

*Réversible* : logique, aucune donnée détruite.

### A-5 — Le prix du cache est un tarif fournisseur, pas une décision

Les lectures de cache valent **0,1×** l'entrée et les écritures **1,25×** (TTL 5 min, celui que pose
`AnthropicAgentProvider`) — ratios publiés par le fournisseur, soit 0,50 $/M et 6,25 $/M au tarif
Opus 5. Ce sont des **constats**, inscrits comme défauts de configuration et non en dur, au même
titre que les 5 $/M et 25 $/M déjà présents dans `app.usage.report.*`.

## 4. Hors périmètre (rappel `PRODUCT_SPEC.md`)

Changer un prix, un quota ou le `markup` ; toucher à Stripe ; déployer. La grille (`docs/TARIFS.md`)
ne change d'aucun montant : F-63 en remplit la **place réservée §8.1**, qui décrit une règle de
calcul, pas un tarif.

## 5. Découpage

| SF | Titre | Portée |
|---|---|---|
| SF-63-01 | Le décompte au coût réel | Backend — tarifs en configuration, calculateur, colonne `billed_tokens` (069), quota/alerte/`GET /usage`, chemin Managed Agents |
| SF-63-02 | Chaque nature de token jusqu'au décompte | Backend — le cache voyage séparément de l'entrée depuis les chemins qui appellent le fournisseur |
| SF-63-03 | L'écran dit comment il compte | Frontend — le décompte pondéré est nommé là où un quota est montré ; les volumes restent des volumes |
