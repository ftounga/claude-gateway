# Cadrage — F-52, le premier paquet de gouvernance

> Découpage de la feature F-52 de `docs/PRODUCT_SPEC.md`. Cadrage d'ensemble :
> `docs/features/CADRAGE-postes-et-gouvernance.md` (rang 5 de la séquence, arbitrages du 2026-09-10).
> Dépend de F-50 (les points de contrôle de la boucle) et de F-51 (le catalogue), livrées le
> 2026-09-10.

---

## 1. Le constat

F-50 a posé les crochets, F-51 a posé le catalogue, et **ni l'un ni l'autre n'apporte de contenu** :
le registre des contrôles est vide, et le catalogue publié aussi. Un utilisateur qui ouvre l'écran de
gouvernance aujourd'hui voit une liste vide et n'a rien à composer.

F-52 apporte ce contenu, et il n'est pas quelconque : c'est la gouvernance du PO, transposée. Son
principe tient en une phrase — **le travail est jetable, le savoir est durable**. Un sujet produit des
notes qui mourront avec lui ; tout élément durable qu'il fait apparaître doit être **promu** vers la
carte du projet, sinon il est perdu avec les notes.

## 2. Les trois niveaux, et ce qui manque à chacun

Le cadrage d'ensemble nomme trois niveaux de gouvernance. Le premier existe depuis F-34, le deuxième
depuis F-50/F-51, le troisième n'a jamais existé :

| Niveau | Ce que c'est | Qui le porte |
|---|---|---|
| **La convention** | On le **demande** au modèle, dans la consigne système | Les *règles* du paquet (F-51 / SF-51-04) |
| **Le verrou déterministe** | On le **refuse**, mécaniquement, sans négociation possible | Les *contrôles* du paquet (F-50, branchés en SF-51-04) |
| **Le filet sémantique** | On **regarde** ce qui vient d'être produit et on alerte | Le juge de fin de tour, apporté ici |

Toute la valeur de F-52 est dans le deuxième et le troisième. Une règle écrite en consigne système
est *déjà* réalisable aujourd'hui avec un `CLAUDE.md`. Ce qui ne l'est pas, c'est un refus.

## 3. Le point dur : où se contrôle un message de commit

La feature exige une vérification **mécanique** sur les messages de commit — « pas seulement une
consigne ». Or un commit, dans l'Atelier, se fait par `bash` : `git commit -m "…"`. Et F-50 a
explicitement **écarté** `bash` de ses crochets, au motif qu'il a « déjà sa porte (SF-38-08) et son
journal ».

Ce motif ne tient pas pour ce que F-52 doit faire, pour deux raisons :

1. **La porte de confirmation est une décision humaine, pas un verrou.** Elle demande *« autorises-tu
   cette commande ? »* et n'inspecte **rien** de son contenu. Elle ne peut pas refuser un marqueur
   dans un message de commit — elle ne le lit pas.
2. **Elle est débrayable.** SF-38-20 a rendu `agent_ask_before_bash` réglable par projet, précisément
   parce qu'une garde qu'on subit finit par être contournée. Un contrôle qui disparaît quand
   l'utilisateur décoche une case n'est pas un verrou déterministe.

F-52 ajoute donc le **troisième point d'accroche** de la boucle : **avant l'exécution d'une
commande**. C'est la seule façon d'honorer la promesse « mécanique » de la feature. Voir l'arbitrage
A1 (§6) — il est additif, sans effet tant qu'aucun contrôle n'est enregistré, et il ne desserre
aucune garde existante : il en ajoute une.

## 4. Ce que le paquet apporte

Un seul paquet, `savoir-durable`, publié par le produit au démarrage. Il apporte les quatre choses
que F-51 sait porter, et rien d'autre :

| Apport | Contenu |
|---|---|
| **Règles** | Le principe (travail jetable / savoir durable), la règle des livrables, la promotion avec dette bloquante, et la forme du marqueur de fin de tour |
| **Contrôles** | `commit-sans-trace-llm` (avant commande), `juge-fin-de-tour` et `promotion-dette-bloquante` (fin de tour) |
| **Gabarits** | `STATE.md` et `PLAN-ACTION.md` |
| **Skills** | `.claude/skills/explique.md` et `.claude/skills/plan-dashboard.md` |

## 5. Le juge de fin de tour — best-effort, jamais une autorité

C'est le point le plus délicat de la feature, et sa rédaction est contrainte par trois exigences
écrites dans `PRODUCT_SPEC` : **best-effort**, **jamais une autorité**, **verdict borné par un
marqueur**, et **repli qui alerte plutôt que de laisser passer**.

Le juge ne fait **aucun appel supplémentaire au fournisseur** (arbitrage A2). Il lit le **marqueur**
que la règle demande au modèle de poser en fin de réponse :

```
<!-- fin-de-tour: promotion=aucune; dette=0 -->
```

- `promotion=` — ce que le tour a fait apparaître de **durable** et qui n'est **pas** dans la carte du
  projet ; `aucune` s'il n'y a rien. C'est le modèle qui juge, sémantiquement : d'où *best-effort* et
  *jamais une autorité*.
- `dette=` — le nombre de cases `- [ ]` restées non cochées dans la carte du projet.

Le contrôle, lui, est **mécanique** : il lit le marqueur, pas le sens. Marqueur absent ou illisible →
il **bloque en alertant** (« termine ta réponse par le marqueur… ») au lieu de laisser passer, ce qui
est l'exigence explicite. `promotion` non vide → il bloque : promeus d'abord. `dette` non nul → il
bloque : la case non cochée empêche de clore.

Le tout reste **borné par F-50** : `MAX_END_OF_TURN_BLOCKS` rend la main au modèle après un nombre
fixe de refus. Un juge qui s'entête ne prend jamais le message de l'utilisateur en otage — c'est
exactement ce que « jamais une autorité » veut dire côté produit.

## 6. Arbitrages de découpage

| # | Sujet | Décision | Alternative écartée | Réversible |
|---|---|---|---|---|
| A1 | Où se contrôle un commit | **Nouveau point d'accroche `BEFORE_COMMAND`** dans la boucle, avant l'émission de la commande | Se contenter d'une règle en consigne système : la feature exige explicitement « pas seulement une consigne ». Ou contrôler l'écriture d'un fichier de message de commit : couvre les cas rares, laisse passer `git commit -m` qui est le cas courant | oui — additif, sans effet sans contrôle enregistré |
| A2 | Comment juge le juge | **Le modèle déclare, le contrôle lit le marqueur** | Un second appel au fournisseur pour juger : double le coût de **chaque** tour, et un juge lent est un juge qu'on décroche. Le cadrage d'ensemble a déjà écarté le même surcoût sur la vue 360 | oui |
| A3 | Portée du contrôle de commit | `git commit` (y compris `--amend`), et lui seul | Étendre à `gh pr create --body`, `git tag -m`… : le périmètre écrit dit « messages de commit ». On l'étend le jour où on le demande | oui |
| A4 | Le paquet arrive par un semeur au démarrage | **Oui**, idempotent, comme `SuperAdminBootstrap` | Demander à l'admin de le saisir à la main dans l'écran de SF-51-06 : le contenu serait absent de toute installation neuve, et non reproductible | oui |
| A5 | Un paquet dépublié par l'admin | Le semeur **ne le republie pas** | Republier à chaque démarrage : le produit reprendrait à l'admin une décision qu'il vient de prendre | oui |
| A6 | Ce que fait le contrôle des livrables hors commit | **Rien** : c'est une règle, pas un verrou | Bloquer toute écriture de fichier contenant « Claude » : le produit s'appelle claude-gateway, ses propres fichiers en parlent à chaque ligne. Un verrou qui hurle en permanence est un verrou qu'on décroche | oui |
| A7 | Aucune activation par défaut | Le paquet est **publié**, jamais **activé** d'office | L'activer partout : il écrit des fichiers sur la machine des gens et bloque leurs fins de tour. F-51 a fait de la composition un geste de l'utilisateur ; le premier paquet n'est pas une exception | oui |

## 7. Découpage

| SF | Titre | Contenu | Migration |
|---|---|---|---|
| **SF-52-01** | Le crochet de commande, et le commit sans trace | Le point d'accroche `BEFORE_COMMAND` dans la boucle, son branchement sur les paquets actifs, et le contrôle `commit-sans-trace-llm` | — |
| **SF-52-02** | Le juge de fin de tour et la dette de promotion | Le marqueur, son analyseur, et les deux contrôles de fin de tour | — |
| **SF-52-03** | Le paquet, publié au démarrage | Le semeur idempotent, les règles, les deux gabarits et les deux skills | — |

**Backend uniquement, trois SF, aucune migration.** F-52 n'a **pas d'écran propre** : un paquet, ses
règles, ses fichiers annoncés avant écriture et ses contrôles sont **déjà** rendus par les écrans de
F-51 (SF-51-05 côté utilisateur, SF-51-06 côté admin), qui lisent le catalogue et le registre des
contrôles sans rien savoir de leur contenu. Aucune SF frontend n'est donc requise, et ce n'est pas un
oubli : la règle CLAUDE.md (« subfeature backend mergée sans subfeature frontend planifiée si la
feature a une UI ») ne s'applique pas — la feature n'apporte aucune UI nouvelle.

## 8. Ce que F-52 ne fait pas

- **Ne réimplémente rien de ce que le fournisseur fait déjà** : le juge ne fait aucun appel de plus
  (A2), et rien ici ne clone une capacité de Claude. Provider-First est tenu.
- **N'exécute aucun code reçu de l'extérieur.** Les trois contrôles sont des composants du serveur,
  comme F-50 et F-51 l'ont fixé. Un paquet cite des identifiants, il n'apporte pas de code.
- **N'écrit rien sans l'annoncer.** Les fichiers du paquet passent par le dépôt idempotent de
  SF-51-03 : annoncés d'abord, créés seulement s'ils manquent, jamais écrasés.
- **Ne reprend pas l'organisation du poste personnel de l'auteur** (`~/dev/repos`, `~/poste/`,
  `~/methodo/`) ni le site de méthodologie : écartés du périmètre par le cadrage d'ensemble.
