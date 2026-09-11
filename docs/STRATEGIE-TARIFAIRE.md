# Note de stratégie tarifaire — 2026-09-11

> Déclenchée par une question du PO : *« est-ce qu'on ne peut pas dire que les 199 € c'est pour
> un client, et qu'il faut payer à chaque nouveau client ? »* Ce document arbitre, il ne décrit pas.
>
> ⚠️ **Note datée : les chiffres qu'elle contient servent le raisonnement, ils ne font pas autorité.**
> La grille tarifaire en vigueur — plans, montants, quotas, recharges, périodicité, essai — est dans
> **[`docs/TARIFS.md`](TARIFS.md)**, source de vérité unique (F-64). En cas d'écart, c'est elle qui
> fait foi.

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

**(a) Objection corrigée le 2026-09-11, après contestation du PO — elle ne vaut que pour un mode
d'usage, pas pour le sien.** J'avais écrit que F-48 rendait le contournement gratuit : déclarer un
seul poste dont la racine contient tous les clients. **C'est faux en régie.** Un poste est
physiquement une **machine** : intervenir chez un client, c'est sa machine, son réseau, son proxy,
son runner. Deux clients ne partagent ni disque ni réseau — on ne peut pas les réunir sous un poste.

L'objection ne tient que pour le consultant travaillant **depuis sa propre machine** sur plusieurs
projets clients : là, `~/dev` contient `client-a/` et `client-b/`, un seul runner, un seul poste, et
la facturation au poste ne capte rien. Ce cas existe, mais il ne peut pas fonder la règle à lui seul.

**Conséquence** : la facturation au poste est **légitime en régie**, et **contournable hors régie**.
Le choix dépend donc de la clientèle visée — et c'est précisément ce que l'option A ci-dessous
résout, en facturant l'**activité** plutôt que la déclaration.

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

**Pourquoi A plutôt que la facturation au poste déclaré, maintenant que l'objection (a) est
corrigée** : les deux se valent en régie — un poste actif y *est* un client. A ne devient supérieure
que hors régie, où elle continue de facturer le travail réel là où le poste déclaré ne capterait
rien. Elle est donc le **sur-ensemble** de l'idée du PO : même résultat dans son cas, plus robuste
dans l'autre. Si la clientèle visée est exclusivement la régie, facturer au poste déclaré est plus
simple à expliquer et à vendre — et c'est un argument qui compte.

---

## 5. Incohérence à corriger avant toute décision — **levée le 2026-09-11 (F-64)**

Deux grilles coexistaient dans le dépôt, sans concorder ni sur les montants ni sur les plans :
`marketing.md` ignorait GOLD, `PRODUCT_SPEC` ignorait BYOK et les recharges. **Deux grilles publiées
finissent en litige commercial le jour où un client cite celle qui l'arrange.**

**Réglé** : la grille vit désormais dans **[`docs/TARIFS.md`](TARIFS.md)**, source de vérité unique,
alignée sur ce que le code et la configuration appliquent réellement ; tous les autres documents y
renvoient au lieu de recopier des montants. La grille de `marketing.md` était **appliquée nulle
part** — c'est la configuration qui a tranché, et ce n'est pas un arbitrage de prix mais le constat
de ce qui est facturé.

**Ce que F-64 n'a pas tranché**, faute de source : le prix de la recharge 1 M, la concordance
montants affichés ↔ prix Stripe, et l'écart entre l'essai **annoncé** (14 jours) et l'essai
**appliqué** (5 jours). Ces points sont listés dans `TARIFS.md` §7 et suivis en **OQ-16** — un
chiffre inventé dans une grille tarifaire est pire que son absence.


---

## 6. Stratégie retenue — 2026-09-11

**Le prix suit deux axes, et un seul d'entre eux est nouveau.**

| Axe | Ce qu'il mesure | Comment il est facturé |
|---|---|---|
| **Largeur** | Combien de **missions** en parallèle | L'abonnement couvre **un poste** ; chaque poste supplémentaire ajoute un **supplément mensuel**, qui **apporte sa part de quota** |
| **Profondeur** | Combien on **travaille** sur chacune | La consommation, décomptée au **coût réel** (F-63) et bornée par le quota ; au-delà, recharges |

**Calibrage recommandé du supplément** : le **même nombre de tokens par euro** que le plan de base —
si 199 € donnent 12 M tokens, 70 € en donnent ≈ 4,2 M. Simple à expliquer, marge identique à celle
du plan, aucune surprise sur la facture. Le premier poste porte seul les coûts fixes (plateforme,
support), ce qui laisse la latitude d'être **plus généreux** sur les suivants si l'on veut favoriser
les consultants à plusieurs missions — décision commerciale, pas technique.

**Pourquoi cet édifice tient :**

1. **En régie, la règle n'est pas contournable.** Un poste est une machine chez un client, avec son
   réseau et son proxy : on ne peut pas réunir deux clients sous un poste.
2. **Le prix suit le coût.** F-63 fait payer la sortie à son prix (cinq fois l'entrée) : la marge
   cesse de dépendre du style d'usage du client. C'est la pièce qui protège tout le reste.
3. **La sortie du modèle est un geste, pas une négociation.** Un poste **clôturé** (F-60, livré la
   veille) ne se facture pas. Le consultant qui termine une mission la clôt, et cesse de payer.
4. **Rien ne décourage l'usage.** Ouvrir un poste reste gratuit ; c'est le **maintenir actif** qui se
   facture — ce qui correspond à une mission réellement en cours, donc facturée au client final.

**Ce qui reste à trancher** : proratisation d'un poste ouvert en cours de mois, dégressivité au-delà
de quelques postes (un consultant à six missions n'acceptera pas six fois le supplément plein), et
le traitement d'une **réouverture** dans le même mois — refacturer serait un piège, ne rien facturer
une faille.
