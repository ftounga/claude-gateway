# Note de stratégie tarifaire — 2026-09-11

> Déclenchée par une question du PO : *« est-ce qu'on ne peut pas dire que les 199 € c'est pour
> un client, et qu'il faut payer à chaque nouveau client ? »* Ce document arbitre, il ne décrit pas.

---

## 1. Ce qu'on sait, mesuré

**Coûts fournisseur** (Opus 5) : **5 $/M en entrée, 25 $/M en sortie** — la sortie coûte **cinq fois**
l'entrée.

**Ratio réel observé**, relevé dans un tour de production le 2026-09-09 :
`inputTokens: 20 764, outputTokens: 544` — soit **38 pour 1**. C'est la signature de l'agentique :
on relit beaucoup de contexte, on écrit peu.

**Coût mélangé réel** : ≈ **5,5 $/M**, et non les 9 $/M inscrits dans la configuration. Sur GOLD
(199 € ≈ 215 $ pour 12 M tokens), cela donne **≈ 66 $ de coût**, soit **69 % de marge brute** — avant
même l'effet du cache de prompt.

**La grille est donc saine.** Son défaut n'est pas son niveau, c'est sa **façon de compter**.

---

## 2. Le défaut structurel — le quota ignore le prix de la sortie

Le décompte traite un token d'entrée et un token de sortie à l'identique. Deux clients consommant
12 M tokens paient le même prix et coûtent :

| Profil | Ratio entrée/sortie | Coût réel | Marge |
|---|---|---|---|
| Agentique (usage observé) | 38:1 | 66 $ | **69 %** |
| Génération (rédaction, code long) | 3:1 | 150 $ | **30 %** |

Le second ne triche pas : il utilise le produit autrement. **Rien n'avertit, rien ne corrige.** La
marge dépend du style d'usage du client, c'est-à-dire de rien qu'on maîtrise.

**Correctif** : décompter au **coût réel** — un token de sortie pèse cinq tokens d'entrée dans le
quota. Changement de **calcul**, pas de **tarif** : aucune annonce commerciale, et l'usage agentique
y gagne.

---

## 3. L'idée du PO — facturer par client

**L'intention est juste** : un consultant qui sert trois clients gagne trois fois plus, il peut
payer davantage. Aligner le prix sur la valeur perçue est sain.

**Mais trois objections, dont une rédhibitoire.**

**(a) Nous venons de rendre le contournement gratuit.** F-48 a fait du *poste* une racine avec des
projets dessous. Facturer au poste, c'est inviter à déclarer **un seul poste** dont la racine
contient tous les clients — précisément ce que le produit permet désormais, et qu'on a livré hier.
Une règle qu'un utilisateur normal contourne sans effort n'est pas une règle.

**(b) Le prix cesserait de suivre le coût.** Notre coût est proportionnel aux **tokens**, pas aux
clients. Cinq clients dormants coûtent moins qu'un client intensif. Facturer au client, c'est
décorréler prix et coût **dans les deux sens** : on surfacture les uns, on perd sur les autres.

**(c) Cela décourage l'usage.** Un consultant hésitera à ouvrir un poste pour une mission d'une
demi-journée. Or un produit d'exécution gagne à être ouvert souvent.

---

## 4. Ce que je propose à la place

**Option A — le poste ACTIF, pas le poste créé.** Un poste ne compte que s'il a réellement consommé
dans le mois. Le plan en inclut un nombre, un poste actif supplémentaire coûte un supplément modique.
**Résiste au contournement** (un poste actif, c'est du travail réel, pas une ligne dans une liste),
**n'entrave pas l'usage** (ouvrir un poste reste gratuit), et **capte la croissance** du consultant
qui grossit. C'est la réponse la plus proche de l'intention du PO sans ses inconvénients.

**Option B — la journée active.** Le consultant paie les jours où il travaille. Le produit a déjà la
brique (`Daily`, et les codes 24 h de F-62). Aligné sur la façon dont un consultant facture
lui-même ; adapté aux usages irréguliers ; mais revenu moins prévisible.

**Option C — ne rien changer à la structure, corriger le comptage (§2) et relever GOLD.** À 69 % de
marge, la question n'est peut-être pas *« comment facturer davantage par client »* mais *« pourquoi
199 € pour un outil qui remplace des journées facturées 600 à 1 000 € »*. Le levier le plus simple
reste le prix lui-même.

**Recommandation : §2 d'abord (il protège la marge quoi qu'il arrive), puis A.** B et C restent
disponibles ; elles ne s'excluent pas.

---

## 5. Incohérence à corriger avant toute décision

Deux grilles coexistent dans le dépôt :

| Source | Contenu |
|---|---|
| `docs/marketing.md` | Hosted : Solo **29 €**, Pro **119 €**, Daily 15 €/j · BYOK : Solo 9 €, Pro 49 €, Daily 7 €/j |
| `docs/PRODUCT_SPEC.md` | Solo **24 €**, Gold **199 €** |

Ni les montants, ni les noms de plans ne concordent — `marketing.md` ignore GOLD, `PRODUCT_SPEC`
ignore BYOK et Daily. **Deux grilles publiées finissent en litige commercial.** Une seule source de
vérité, les autres y renvoient.
