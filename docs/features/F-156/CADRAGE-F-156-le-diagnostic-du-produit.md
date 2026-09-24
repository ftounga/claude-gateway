# Cadrage — F-156 — Le diagnostic du produit par lui-même

> Demande du PO, 2026-09-24 :
> *« Ce qui m'intéresse le plus, c'est vraiment ce qu'on fait depuis plusieurs jours : des
> optimisations au niveau de l'application dans son code source même. C'est pour ça que je t'ai
> demandé de faire la parité avec Claude Code — le rajout de sous-agents, la possibilité de faire des
> agents en lecture seule, toutes les dizaines d'optimisations qu'on a faites. »*
>
> *« Ma question, c'est de savoir si l'application peut s'auto-améliorer, s'auto-diagnostiquer. À force
> de faire plusieurs sessions, est-ce qu'elle serait capable de s'améliorer avec ce genre
> d'optimisation ? »*
>
> **Arbitrage du PO** : ce diagnostic est **lourd** — il ne se lance **pas** à chaque fermeture de
> session, mais **sur demande**.

## 1. Ce qu'on automatise, exactement

Ce que nous faisons **à la main depuis une semaine** :

> le PO signale une facture → je remonte à la cause → je livre le correctif.

C'est ainsi qu'ont été trouvés le cache de prompt (**F-134**, coût ÷16), le tour à 9,52 € (**F-149**),
le cache des sources de la consigne (**SF-148-06**), l'index du dépôt (**SF-148-07**). Chacune de ces
causes **portait une trace mesurable** dans des tables que nous avons déjà.

F-156 fait démarrer ce cycle **tout seul**.

## 2. Le cœur : une même mesure, deux conclusions opposées

C'est la question que les chiffres **ne peuvent pas** trancher, et c'est pour elle qu'il faut lire le
code.

La trace dit : *31 recherches de fichiers sur le poste, 4 minutes cumulées*. Deux lectures possibles :

| Lecture | Ce que ça veut dire | Ce qu'il faut faire |
|---|---|---|
| **Capacité absente** | l'application ne sait pas servir un index depuis la base | **créer** la capacité — c'est une feature |
| **Capacité dormante** | elle sait le faire (SF-148-07 est livrée), mais elle ne s'est **pas déclenchée** sur ce poste | **rien à développer** : un branchement à réparer |

**Le second cas est probablement le plus gros gisement**, et il nous est arrivé **trois fois cette
semaine** :

- l'**index du dépôt** livré, jamais amorcé sur le poste concerné ;
- le **skill mis à jour** en base, resté **périmé** sur la machine du client (F-142 / SF-142-10) ;
- la **carte du poste** qui n'entrait pas dans le contexte des tours (F-136).

À chaque fois : capacité **payée**, capacité **inutilisée**. Aucun développement n'aurait été
nécessaire — seulement s'en apercevoir.

## 3. Ce qu'il faut construire

1. **La carte des capacités du produit** : ce que l'application sait faire, **où** c'est dans le code,
   et **à quelle condition** ça s'active. C'est elle qui permet de dire « dormante » plutôt que
   « absente ».
2. **La lecture du code**, pour confirmer plutôt que supposer — possible quand le terminal est celui du
   dépôt de l'application ; sinon le diagnostic reste sur les chiffres et le dit.
3. **L'accumulation** : un motif vu une fois est une anecdote, vu dans huit sessions sur dix il est
   structurel. Le gain se chiffre alors **en euros par semaine**, pas en pourcentage d'une session —
   c'est ce qui rend le seuil des 20 % **vérifiable** plutôt que déclaratif.
4. **La parité comme mesure** (demande explicite du PO) : une liste de capacités de référence —
   déléguer à des sous-agents, explorer en parallèle, travailler en lecture seule, compacter, réutiliser
   le cache, indexer, planifier — et pour chacune : *présente dans le produit ?* *déclenchée dans les
   sessions ?* **Le diagnostic naît de l'écart entre ces deux colonnes.**

## 4. Ce que le diagnostic rend

Pour chaque constat :

- **le symptôme**, chiffré, et **sur combien de sessions** il apparaît ;
- **le verdict** : capacité absente, ou présente mais dormante ;
- **l'endroit** : le fichier quand le code a pu être lu, la piste sinon ;
- **le gain**, calculé, en euros par semaine ou en temps ;
- et, sous le seuil, **rien** — avec le compte de ce qui a été écarté.

## 5. Ce qu'on écarte, et pourquoi

- **L'auto-modification.** L'application **se diagnostique** et **propose** ; elle ne se réécrit pas.
  Le PO décide, le développement suit. Une machine qui modifie son propre code sans décision humaine
  n'est pas un gain de productivité, c'est une perte de contrôle.
- **Le diagnostic à chaque fermeture de session.** Arbitrage du PO, et il est juste : ré-analyser les
  mêmes données dix fois pour la même conclusion, ce serait précisément le gaspillage qu'on traque.
- **Les suggestions sans preuve.** Pas de mesure, pas de suggestion.

## 6. Déclenchement

- **À la demande**, depuis l'espace d'administration.
- **Suggéré par le bilan de session (F-155)** quand un motif se répète : *« ce motif apparaît pour la
  4ᵉ fois en dix sessions — un diagnostic dirait s'il manque une capacité ou si une capacité existante
  ne se déclenche pas »*.
- **Réservé à l'administrateur.**

## 7. Découpage proposé
| SF | Objet |
|---|---|
| **SF-156-01** | La **carte des capacités** : ce que le produit sait faire, où, et à quelle condition ça s'active |
| **SF-156-02** | Les **motifs** sur plusieurs sessions : détection, fréquence, coût hebdomadaire |
| **SF-156-03** | Le verdict **absente / dormante**, avec lecture du code quand elle est possible |
| **SF-156-04** | La **parité** comme mesure : capacités de référence, présentes, déclenchées |
| **SF-156-05** | L'écran du diagnostic, à la demande, réservé à l'administrateur |

## 8. Tranché : la suggestion retenue devient une ligne de la spec

**Arbitrage du PO, 2026-09-24** : *« je veux que ça devienne une ligne dans la spec »*.

Une suggestion que le PO retient est donc **écrite dans `docs/PRODUCT_SPEC.md`**, au même endroit que
toutes les features — c'est d'ailleurs la règle du projet : *toute feature implémentée doit y être
référencée*. Trois précisions que cela impose :

1. **Un statut qui la distingue** : `Candidate — proposée par le diagnostic, non cadrée`. Sans lui, une
   idée de machine se mélangerait aux features décidées, et la spec cesserait d'être une source de
   vérité.
2. **La ligne porte sa preuve** : le constat chiffré, le nombre de sessions concernées, le gain
   calculé. Une feature candidate sans mesure n'aurait pas sa place — c'est ce qui la sépare d'une
   idée en l'air.
3. **C'est l'agent qui écrit, dans un tour, sur le dépôt** — donc depuis le terminal de
   `claude-gateway`, avec l'accord du PO. Le diagnostic **rédige** la ligne ; il ne commite rien tout
   seul. (Le PO retire une candidate comme n'importe quelle ligne : elle sort de la spec.)

## 9. Ce qui reste à trancher
- **La première carte des capacités** : on démarre avec celles qu'on connaît (sous-agents, exploration
  parallèle, lecture seule, cache de prompt, index du dépôt, compaction, plan) — laquelle manque à
  cette liste ?
