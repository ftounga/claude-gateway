# F-125 — La tenue de la carte du poste : silencieuse, robuste, et jamais dans la réponse

> Cadrage du 2026-09-17, à la demande du PO, après un cas réel observé en production sur CAGIP.
> **Cadrage seul : la livraison attend le go du PO.**

## 0. Le constat (cas réel, terminal racine CAGIP, 2026-09-17)

Le PO demande une chose **de fond** : *« Ça s'est bien passé ? Tu maîtrises mieux ton environnement ? »*
L'agent répond par de la **tuyauterie interne** :
> « l'élément est déjà dans `plateformes.md` ligne 534… mon libellé était trop long, il s'est coupé en
> deux et le fragment s'est retrouvé isolé devant la destination… même cause qu'avec ma virgule il y a
> quelques tours… `enjeux.md` existe mais n'est pas déclaré dans la carte, il est hors gouvernance… »
Puis, relancé (« je n'ai pas compris ta réponse »), il **remet de la plomberie**.

**Le fond avance pourtant** (compte devtools `438699780936` rangé dans `plateformes.md`, « la sandbox ne
vaut pas comme environnement de validation » dans `exploitation.md`). Ce n'est **pas** un défaut de
raisonnement (F-119/120/121 tiennent : il vérifie, ne invente pas, se corrige). C'est une **couche de
bureaucratie de la carte du poste** qui est **fragile** et qui **fuite dans la réponse utilisateur**.

## 1. Cause racine (sous-système en code)

La mécanique vient des **contrôles de fin de tour** de la gouvernance :
- `GovernanceEndOfTurnCheckpoint` (F-50), `GovernanceMapGrowth` / `GovernanceMapDestinations` (F-52),
  `PromotionDetteBloquanteControl` + `IntegritePosteControl` (F-95).
- Ils suivent une **« dette de promotion »** (faits pas encore rangés dans la carte), exigent une
  **destination** (quelle fiche), et s'appuient sur un **marqueur `<!-- fin-de-tour: promotion=… -->`**
  que le **modèle doit émettre correctement formaté**.

Trois défauts en découlent :
1. **Ça fuite** : la promotion, les destinations, l'état « hors gouvernance », les marqueurs — tout ça
   se retrouve **dans la réponse à l'utilisateur**, qui n'en a que faire.
2. **C'est fragile** : le marqueur casse quand le **libellé est trop long** (troncature → fragment
   « isolé devant la destination ») ou contient une **virgule** (analyse cassée). L'agent **passe ses
   tours à réparer sa propre paperasse**.
3. **Un fichier de carte non déclaré bloque** : `enjeux.md` créé (sur suggestion d'un prompt) mais pas
   inscrit dans `README.md` → jugé « hors gouvernance » → l'agent tourne autour au lieu d'avancer.

À noter : l'audit F-119 avait déjà **signalé le crochet de fin de tour comme aggravant**, et F-120 §4
l'avait **explicitement laissé hors périmètre**. F-125 est l'endroit où on le traite.

## 2. Principe de la solution

> **L'utilisateur d'abord ; la carte se tient toute seule, en silence, sans jamais casser.**

La tenue de la carte est un **service**, pas un sujet de conversation. L'agent répond à la question
posée ; la promotion/le rangement se font **en coulisse** et ne s'affichent **jamais**.

## 3. Découpage

| SF | Titre | Contenu |
|---|---|---|
| **SF-125-01** | **Répondre d'abord — zéro plomberie dans la réponse** | La réponse à l'utilisateur traite **sa** question, en clair. La promotion, les destinations, l'état de gouvernance et les marqueurs `fin-de-tour` deviennent des **métadonnées invisibles** : jamais narrées, retirées de l'affichage (le marqueur `<!-- … -->` ne doit pas être « expliqué » ni laissé visible). Prompt + rendu. |
| **SF-125-02** | **Suivi de promotion robuste (côté serveur), marqueur non fragile** | Ne plus dépendre d'un marqueur que le modèle doit formater parfaitement : le suivi de la **dette de promotion** et des **destinations** se fait **côté serveur** (à partir des écritures de fichiers réelles de l'agent), pas d'un libellé en texte libre. Si un marqueur reste, format **robuste** (libellés courts imposés, aucune ponctuation interne, tolérance aux virgules/troncatures). Fin des « ma virgule / mon libellé coupé ». |
| **SF-125-03** | **Un fichier de carte non déclaré ne bloque plus** | Le cas `enjeux.md` : un fichier de carte créé mais non déclaré dans `README.md` est **auto-déclaré** (ou toléré, selon la règle du paquet), avec une note — il ne met plus l'agent « hors gouvernance » ni en boucle. `IntegritePosteControl` devient **réparateur**, pas bloquant. |
| **SF-125-04** | **Alléger le crochet de fin de tour** *(à peser)* | Le contrôle de **dette de promotion bloquante** (F-95) ne doit pas **renvoyer l'agent au travail** pour de la paperasse (rappel : le crochet END_OF_TURN peut relancer jusqu'à 3×, cf. audit F-119). Soit il devient non bloquant (nag doux, silencieux), soit la promotion est faite d'office côté serveur (SF-125-02). Décider du bon niveau de rituel. |

**Ordre** : SF-125-01 (le plus visible, faible risque) → SF-125-02 (robustesse, cœur) → SF-125-03 →
SF-125-04.

## 4. Ce qui n'est PAS en cause (ne pas « corriger »)
- Le **raisonnement** (F-119) : il vérifie, ne invente pas, se corrige — ça tient.
- La distinction **question/action** (F-120) : ici il répond bien (il n'agit pas à tort) — le défaut est
  qu'il répond **à côté** (plomberie), pas qu'il agit sans qu'on demande.
- Le **fond de la cartographie** : les faits promus sont justes et utiles ; on ne touche pas au *quoi*,
  seulement au *comment c'est tenu et raconté*.

## 5. Critères d'acceptation (vérifiables)
- À une question de fond (« ça s'est bien passé ? »), la réponse **ne contient aucun** terme de plomberie
  (« promotion », « fin-de-tour », « hors gouvernance », « libellé », nom de fiche comme destination) ;
  elle répond à la question.
- Un fait long à ranger **ne casse plus** (aucun « libellé coupé / virgule »).
- Un fichier de carte non déclaré est **auto-déclaré/toléré**, pas bloquant.
- Le crochet de fin de tour ne relance pas l'agent pour de la seule dette de promotion.

## 6. Préoccupations transversales
- **Navigation / auth / tenant** : aucune (comportement de boucle + gouvernance, pas d'endpoint/route).
- **Composants** : `GovernanceEndOfTurnCheckpoint`, `GovernanceMapGrowth`, `GovernanceMapDestinations`,
  `PromotionDetteBloquanteControl`, `IntegritePosteControl`, le prompt (`buildSystemPrompt`) et le rendu
  du fil (retrait/masquage des marqueurs), le paquet de gouvernance « savoir-durable ».
