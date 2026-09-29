# Cadrage — F-162 — Le rappel à la demande de l'historique (se souvenir sans tout rejouer)

> **Statut : Cadrée / À faire.** Ce document est un **cadrage** — aucune ligne de code
> applicative n'est produite ici. Il pose la feature dans `PRODUCT_SPEC.md` (règle d'existence
> CLAUDE.md) et arbitre son découpage avec le PO.
>
> **Référence visuelle** (maquette validée par le PO) :
> https://claude.ai/code/artifact/018b567e-9b34-4467-909a-8b529bb960c2

---

## 1. Le besoin — sortir du faux binaire

Le coût d'un tour de l'Atelier ≈ **taille du contexte × nombre d'outils × prix du modèle**.
Cette équation enferme aujourd'hui dans un **faux binaire** :

| Option | Ce qu'on gagne | Ce qu'on paie |
|---|---|---|
| (A) **Garder un fil long** | mémoire intacte : le modèle « sait » tout | contexte énorme **rejoué à chaque tour** |
| (B) **« Nouveau départ »** | contexte léger, tours peu chers | le modèle est **« largué »** — il a tout oublié |

**Mesuré** : projet « agenor » = **290 $**, un fil de **169 tours**, contexte jusqu'à
**11,3 M tokens**. **51 % de la facture CAGIP** provient des tours **> 1,5 M tokens**.

Le PO **ne sait jamais quand repartir**, et **repartir est risqué** : couper un fil, c'est parier
que le modèle n'aura pas besoin de ce qu'on vient d'effacer. Le produit fait donc porter à
l'utilisateur un arbitrage coût/mémoire qu'il n'a pas les moyens de trancher.

**L'objet de F-162** : casser le binaire. Rendre la compaction et le « Nouveau départ » **sûrs**
en donnant à l'agent le moyen de **retrouver à la demande** n'importe quel détail de son propre
passé, sans avoir à rejouer tout le contexte.

---

## 2. Le fonctionnement actuel (documenté, vérifié dans le code)

- Un **fil = un projet = un terminal** (`workspace`). Tout l'historique est stocké **durablement**
  dans `atelier_messages` — c'est le **stock de vérité**, jamais effacé par les garde-fous ci-dessous.
- La **boucle d'agent** fait **~14 appels/tour**, et **chacun rejoue le contexte**, qui **grossit à
  chaque sortie d'outil**. C'est là que naît le coût en N².
- Le rejeu des **traces d'outils** est borné aux **12 derniers tours** (`replayed-trace-turns=12`).

### Les trois garde-fous existants

| # | Garde-fou | Déclenchement | Ce qu'il fait | Conserve un résumé ? |
|---|---|---|---|---|
| a | **Édition de contexte en cours de tour** (`clear_tool_uses`) | **auto**, seuil ≈ **200 000 tokens** | garde **3** traces, efface les sorties d'outils **≥ 20 K** | n/a (nettoyage intra-tour) |
| b | **Compaction entre tours** (`AtelierCompactionService`) | **auto**, seuil **120 000 tokens estimés** | garde **6 messages récents**, **résume le reste**, déplace la frontière `chat_thread_started_at` | **oui — conserve le résumé** |
| c | **« Nouveau départ »** (F-39/SF-39-04 + F-117/SF-117-03) | **manuel** | **reset DUR** | **non — ne garde PAS de résumé** |

Précisions vérifiées :

- La compaction (b) est **auto uniquement** : elle **n'est pas déclenchable manuellement
  aujourd'hui**. Un **filet réactif** existe : `compactNow` se déclenche sur un `400 « prompt too
  long »`.
- Le « Nouveau départ » (c) est un **reset dur** ; l'ancien fil est **replié « Voir l'historique »**
  (SF-117-05/06/07), consultable mais hors contexte.

### 3. Le trou central

L'agent sait **fouiller les FICHIERS** (`read_file`, `grep`, `search_files`, `explore`) et le
**web**. Mais **AUCUN outil ne fouille sa propre CONVERSATION passée.**

Et la compaction est **invisible** (vérifié : **aucun signal front, aucun événement de flux**). Le
modèle — et l'utilisateur — ne savent pas qu'un résumé a remplacé des tours, ni que du détail est
récupérable ailleurs que dans le contexte vif.

**Conséquence** : dès qu'un détail sort de la fenêtre vive (résumé, repli, ou « Nouveau départ »),
il est **perdu pour le raisonnement** alors qu'il est **toujours en base**. C'est ce trou que
F-162 comble.

---

## 4. La solution — découpage en subfeatures

### SF-162-01 (cœur) — l'outil `recall(query)`

Exposer au **catalogue d'outils de la boucle** un outil `recall(query)` qui **recherche
mot-clé / plein-texte** sur `atelier_messages`, **isolé `user_id` + `workspace_id`**, **borné**
(extraits + un nombre maximal de résultats). Une **consigne système** apprend au modèle **QUAND**
l'appeler.

**Point clé à écrire noir sur blanc** : `recall` **opère sur TOUT le fil (le stock durable)** —
**la compaction n'est PAS un prérequis**. Il retrouve **même les tours résumés, repliés, ou
d'avant un « Nouveau départ »**. **C'est exactement ce qui rend la compaction et le reset sûrs** :
on peut désormais alléger le contexte vif sans craindre de « perdre » quoi que ce soit, puisque le
détail reste rappelable à la demande.

### SF-162-02 — le résumé de compaction **ancré**

Faire évoluer `AtelierCompactionService` pour que le résumé porte des **repères vers les tours
d'origine** (ex. « décision réseau → tour 34 »), afin que `recall` **vise juste** : le modèle sait
non seulement *qu'*une décision a été prise, mais *où* la retrouver en détail.

### SF-162-03 — la VISIBILITÉ (parité Claude Code, exigée par le PO)

Rendre visible ce qui est aujourd'hui invisible. Trois affichages :

1. Pendant la compaction : une **barre de progression** (« Compaction de la conversation… », façon
   Claude Code).
2. Après : un **marqueur persistant** « Conversation compactée · N tours résumés ».
3. Pendant `recall` : un **indicateur bien visible** — « Recherche dans l'historique… » puis
   « Détail rappelé · tour 34 ».

Nécessite un **événement de flux** (back) **+ un rendu terminal** (front). *(Préoccupation
transversale « Navigation / routing » et flux SSE : la mini-spec de cette SF listera les composants
de flux et de rendu impactés.)*

### SF-162-04 — « compacter maintenant »

Un bouton **« compacter maintenant »** : **compaction douce à la demande**, **distincte du
« Nouveau départ » dur** (elle conserve le résumé, lui non). Plus une **suggestion de checkpoint**
lorsqu'un **changement de sujet** est détecté.

### SF-162-05 (option) — le filet utilisateur

Sous une réponse, un filet : **« Claude semble avoir perdu un détail — lui faire rechercher le
contexte pertinent ? »** → déclenche un `recall` **ciblé**. C'est un **rappel chirurgical initié
par l'utilisateur**, **PAS un « recharge tout »**.

### SF-162-06 (plus tard) — la recherche sémantique

Recherche **sémantique** (embeddings / pgvector) pour **rappeler par le sens**, au-delà du
mot-clé. Repoussée : le jour 1 démarre en **mot-clé** (voir §6).

---

## 5. Impact coût attendu

Avec le rappel, on peut **compacter beaucoup plus tôt** (contexte vif plus court = **moins cher**)
**TOUT EN mémorisant mieux** (le détail reste rappelable). **Gain sur les deux tableaux** —
contrairement au levier « Opus → Sonnet », qui était un **compromis qualité**. Ici, on ne troque
pas de la mémoire ni de la qualité contre du coût : on supprime le rejeu inutile **sans** perdre
l'information.

---

## 6. Périmètre / hors-périmètre / architecture

### Dans le périmètre

- Le rappel sur **le PROPRE fil de l'utilisateur** — **isolation stricte** `user_id` +
  `workspace_id`.
- **RAG sur nos données propres = capacité *gateway*** : c'est **dans le scope**. **ADR-011** a
  **réactivé RAG / pgvector**.
- **Démarrage en mot-clé** (plein-texte) — **pas d'embeddings au jour 1** (les embeddings sont
  SF-162-06, « plus tard »).

### Hors périmètre

- Le bouton **« recharger TOUT l'ancien contexte »** — il **recrée le coût qu'on fuit**.
- Le **rappel inter-projets** (chercher dans d'**autres** fils) — **sujet séparé**.
- **Tout moteur IA maison** : `recall` reste **recherche + relais** — il **ne raisonne pas**,
  il retrouve.

### Cohérence architecturale (vérifiée)

- **Gateway-First** (`PROJECT.md` §3.2) : `recall` est une **capacité de gateway** — recherche
  dans nos données, relais au modèle. Le backend **n'implémente aucun moteur d'IA** ni clone de
  Claude. ✅
- **Provider-First / Provider Independence** (`ARCHITECTURE.md` Principe 2) : `recall` ne dépend
  pas d'Anthropic ; il s'ajoute au **catalogue d'outils de la boucle**, indépendant du fournisseur
  concret via `AIProvider`. ✅
- **ADR-011** : le périmètre documentaire (RAG / pgvector) est **dans le scope** ; le rappel
  sémantique (SF-162-06) s'y adosse sans nouvelle décision. ✅
- **Isolation multi-tenant** (CLAUDE.md) : **filtre `user_id` obligatoire** — ici renforcé par
  `workspace_id`. ✅
- **Traitements lourds asynchrones** : l'indexation d'embeddings de SF-162-06, si elle est
  ouverte, sera **asynchrone** (workers), conformément à la règle. La recherche mot-clé de
  SF-162-01 est synchrone et bornée (un simple appel d'outil dans la boucle). ✅

**Incohérence avec `ARCHITECTURE_CANONIQUE.md` ?** Aucune détectée : `recall` s'inscrit dans le
RAG « sur nos données propres » que l'ADR-011 a réhabilité, sans réintroduire d'OCR ni de pipeline
documentaire externe. Les tables et migrations éventuelles (index plein-texte, colonnes d'ancrage
de résumé) seront cadrées **par subfeature** et, si de nouvelles tables sont créées,
`ARCHITECTURE_CANONIQUE.md` sera mis à jour à l'étape 6 du cycle.

---

## 7. Séquence de livraison

Ordre recommandé : **SF-162-01** (le cœur, qui débloque la sûreté de la compaction) →
**SF-162-03** (la visibilité, exigée par le PO) → **SF-162-02** (résumé ancré, qui affûte le
rappel) → **SF-162-04** (compacter maintenant) → **SF-162-05** (filet utilisateur, option) →
**SF-162-06** (sémantique, plus tard).

Chaque subfeature suivra la **séquence obligatoire** CLAUDE.md (mini-spec → readiness → dev →
review → push/release → doc). **Aucune SF n'est développée dans le présent cadrage.**
