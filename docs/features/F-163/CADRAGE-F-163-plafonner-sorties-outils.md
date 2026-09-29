# Cadrage — F-163 — Plafonner les sorties d'outils (réduire l'écriture cache)

> **⛔ ABANDONNÉE le 2026-09-30 (décision PO).** Feature jamais développée (restée au stade
> cadrage, aucun code produit). **Raison** : ce levier **échange du contexte contre du coût** et
> risque de **dégrader la justesse du raisonnement** ; il **n'aidait même pas** le projet
> « agenor », dont le coût vient de l'**accumulation de petites sorties bash** et non de gros
> dumps ; il **viole la règle absolue du PO** « aucune feature ne doit diminuer la capacité de
> raisonnement / la justesse ». **Contraste décisif** : la compaction (F-117/F-162) **range** le
> détail de façon **récupérable** par l'outil `recall`, alors que plafonner une sortie bash la
> **DÉTRUIT définitivement**. Le reste du document est conservé pour la trace.

---

> **Statut : ~~Cadrée / À faire~~ → Abandonnée (2026-09-30).** Ce document est un **cadrage** — aucune ligne de code
> applicative n'est produite ici. Il pose la feature dans `PRODUCT_SPEC.md` (règle d'existence
> CLAUDE.md) et arbitre son découpage avec le PO.
>
> **Références de code vérifiées le 2026-09-30** sur `origin/main` (les numéros de ligne cités
> ci-dessous sont ceux de cette date ; le code ayant bougé, on cite d'abord les **noms de
> constantes**, stables, puis la ligne courante).

---

## 1. Le besoin — chiffré, vérifié en prod

On a décomposé la **facture réelle** d'un projet outil-lourd, « agenor » (**313,90 $**, opus-5,
tarifs **cache-write 10 $/M**, **cache-read 0,50 $/M**, **sortie 25 $/M**) :

| Poste | Montant | Part | Nature |
|---|---|---|---|
| **Écriture cache** (cache-write) | **222 $** | **71 %** | le contenu **NOUVEAU** écrit dans le cache 1 h **chaque tour** — surtout les **grosses sorties d'outils** |
| Lecture cache (cache-read) | 70 $ | 22 % | la relecture du contexte déjà en cache |
| Sortie (génération) | 22 $ | 7 % | les tokens produits par le modèle |

**Le contre-intuitif** : le poste dominant n'est **PAS** la relecture du contexte — celle que la
compaction agressive (F-117/F-162) attaque déjà — mais **l'ÉCRITURE des grosses sorties d'outils
dans le cache**. C'est **LE levier coût non tiré**.

**Estimation à confirmer par mesure** : plafonner les sorties d'outils pourrait retirer
**~35 à 50 %** de la facture d'un projet outil-lourd comme agenor.

---

## 2. Le mécanisme — pourquoi une grosse sortie d'outil coûte cher

Chaque sortie d'outil est **insérée dans la conversation** au moment où l'outil répond. Le tour où
elle apparaît, elle est **écrite au cache 1 h** (**10 $/M**, le tarif le plus élevé), puis relue
aux tours suivants (0,50 $/M) jusqu'à ce qu'elle sorte de la fenêtre.

**Conséquence** : une sortie volumineuse = **une grosse écriture cache**, payée une fois plein
tarif. **Plafonner AVANT insertion** coupe le poste le plus cher **à la source** — on n'écrit
jamais au cache ce qu'on n'insère pas.

**Le filet** : F-162 a livré l'outil **`recall(query)`** qui fouille tout le fil durable
(`atelier_messages`, isolé `user_id` + `workspace_id`). Un détail **tronqué** aujourd'hui reste
donc **retrouvable à la demande** dans l'archive. **C'est exactement ce qui rend le plafonnement
sûr** : on peut couper franc sans perdre l'information — elle n'est pas détruite, juste sortie de
la fenêtre vive.

---

## 3. L'état actuel des plafonds (vérifié dans le code, 2026-09-30)

| Outil | Plafond actuel | Comportement | Verdict |
|---|---|---|---|
| `bash` | **`MAX_BASH_OUTPUT_BYTES = 131_072`** (128 Ko ≈ 32 000 tokens) — `AtelierChatService.java:132` | **seule la TÊTE est gardée** (`boundBashBytes`, `AtelierChatService.java:5267-5274`) : la fin est **jetée** | ❌ **le pire levier** : 128 Ko c'est énorme, et couper la **queue** jette souvent le **résultat / l'erreur** qui est en fin de sortie |
| `read_file` | **`MAX_LINES = 2_000`**, **`MAX_LINE_CHARS = 2_000`** — `AtelierFileText.java:14-16` | tronque au-delà (ligne + `…`) | ⚠️ plafond large ; pagination (`offset`/`limit`) déjà présente mais peu incitée |
| `search_files` / `grep` / `glob` / `list_files` | **aucun plafond de sortie explicite** trouvé | sortie non bornée en nombre de résultats ni en taille | ❌ trou : une recherche large peut produire une sortie massive |
| `explore` | **`MAX_ANSWER_CHARS = 4_000`** — `AtelierExploration.java:37` | tronque au-delà (`… (réponse tronquée)`) | ✅ **déjà petit — RIEN à changer** |

**Point de comparaison interne** : les **Managed Agents** plafonnent la sortie relayée à
**10 000 caractères** (`max-tool-output-chars`, `application.yml:302`,
env `APP_ATELIER_AGENT_MAX_TOOL_OUTPUT`) — soit **~13× moins** que le `bash` de la boucle maison.
La boucle maison est donc **incohérente avec sa propre discipline** côté Managed Agents.

---

## 4. La solution — découpage en subfeatures

> Principe transversal à toutes les SF : **externaliser chaque plafond en variable
> d'environnement** (ajustable sans livraison, mesurable), avec un **défaut prudent**. **Piège
> connu à éviter** : **pas de 2e constructeur** sur un record `@ConfigurationProperties` (mémoire
> projet « deux constructeurs cassent le contexte ») → si des propriétés de config sont ajoutées,
> utiliser une **fabrique statique**, jamais un second constructeur.

### SF-163-01 (cœur) — `bash` : abaisser le plafond ET garder tête + queue

Le **plus gros gain, risque faible**. Deux changements :

1. **Abaisser** `MAX_BASH_OUTPUT_BYTES` (ex. 128 Ko → **~32 Ko**), **externalisé** en variable
   d'environnement pour ajuster sans livraison.
2. **Garder TÊTE + QUEUE** au lieu de la tête seule, avec une **mention explicite** au milieu :
   « … (sortie tronquée, N Ko coupés au milieu) ». La queue est souvent là où se trouvent le
   **résultat** et l'**erreur** — les jeter est le défaut le plus coûteux en qualité.

### SF-163-02 — `read_file` : plafond plus mordant + pagination claire

Rendre le plafond **plus mordant** (externalisé) et surtout **inciter à lire par morceaux** :
message de pagination clair s'appuyant sur les `offset`/`limit` **déjà existants**
(`AtelierFileText`), pour que le modèle lise **une tranche pertinente** plutôt que le fichier
entier.

### SF-163-03 — `search_files` / `grep` / `glob` / `list_files` : borner résultats et taille

Combler le trou : **borner le nombre de résultats** ET **la taille totale de la sortie**
(externalisés), avec une mention « **N résultats de plus non affichés** » (comme
`explore` mentionne déjà la troncature). Incite à **affiner la requête** plutôt qu'à ramener un
mur de résultats.

---

## 5. Impact coût attendu

En coupant **à la source** l'insertion des grosses sorties, on **n'écrit plus au cache** (10 $/M)
ce qu'on n'insère pas — on attaque **directement le poste à 71 %** de la facture agenor. Estimation
**~35-50 %** de baisse sur un projet outil-lourd, **à confirmer par mesure** (voir §7).

Ce n'est **pas un compromis qualité** : le détail coupé reste **rappelable via `recall`** (F-162).
On supprime de l'**écriture cache inutile**, pas de l'information.

---

## 6. Périmètre / hors-périmètre / architecture

### Dans le périmètre

- Les **plafonds de sortie des outils de la boucle maison** : `bash`, `read_file`,
  `search_files` / `grep` / `glob` / `list_files`.
- La stratégie **tête + queue** (SF-163-01) au lieu de tête seule.
- L'**externalisation** de tous les plafonds en variables d'environnement, défauts prudents.

### Hors périmètre

- **`explore`** — déjà à 4 000 caractères, **rien à changer**.
- La **compaction** (F-117 / F-162) — levier distinct, sur un autre poste (la **relecture** du
  contexte, pas l'**écriture** des sorties).
- La **recherche `recall`** (F-162) — déjà livrée ; ici elle est le **filet**, pas l'objet.
- Tout **moteur IA maison** — ces SF sont un **simple bornage de sortie**, aucune intelligence.

### Le filet `recall` (F-162)

À mentionner explicitement dans chaque mini-spec : c'est `recall` qui **rend le plafonnement sûr**.
Un détail coupé par un plafond n'est **jamais perdu** — il reste dans `atelier_messages` et se
**rappelle à la demande**. Sans ce filet, plafonner serait risqué ; avec lui, c'est un gain net.

### Cohérence architecturale (vérifiée)

- **Gateway-First** (`PROJECT.md` §3.2) : borner une sortie d'outil est une **discipline de
  passerelle** (ce qu'on relaie au modèle), **aucun moteur d'IA**, aucun clone de Claude. ✅
- **Provider-First / Provider Independence** (`ARCHITECTURE.md` Principe 2) : le bornage est en
  amont du modèle, indépendant du fournisseur concret (`AIProvider`). ✅
- **Isolation multi-tenant** (CLAUDE.md) : ces SF ne touchent **pas** l'accès aux données ; le
  filet `recall` reste, lui, filtré `user_id` + `workspace_id`. ✅
- **Piège des deux constructeurs** (mémoire projet) : si config `@ConfigurationProperties`, fabrique
  statique, jamais de second constructeur. ✅

**Incohérence avec `ARCHITECTURE_CANONIQUE.md` ?** **Aucune détectée.** F-163 ne crée ni table, ni
endpoint, ni pipeline documentaire : ce sont des ajustements de bornage interne à la boucle
d'agent. Si une SF ajoutait néanmoins une table (non prévu), `ARCHITECTURE_CANONIQUE.md` serait mis
à jour à l'étape 6 du cycle.

---

## 7. À mesurer (après livraison)

- L'effet réel sur `usage_turns` : **part cache-write en baisse** sur un projet outil-lourd.
- L'interaction avec la **compaction agressive** (env posé le 2026-09-29 : **trigger 45000 /
  keep 4**) — vérifier que les deux leviers se **cumulent** sans se gêner.
- Réglage fin des défauts de plafond via les **variables d'environnement** (d'où l'externalisation),
  sans nouvelle livraison.

---

## 8. Séquence de livraison

Ordre recommandé : **SF-163-01** (`bash`, le plus gros gain / risque faible) → **SF-163-03**
(recherche/grep/glob/list, le trou béant) → **SF-163-02** (`read_file`, affinage + pagination).

Chaque subfeature suivra la **séquence obligatoire** CLAUDE.md (mini-spec → readiness → dev →
review → push/release → doc). **Aucune SF n'est développée dans le présent cadrage.**
