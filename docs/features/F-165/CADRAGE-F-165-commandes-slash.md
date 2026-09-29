# F-165 — Commandes slash dans le terminal (vues et actions, à la sauce claude-gateway)

> **Cadrage du 2026-09-30, validé par le PO.** Document de cadrage — **aucun code produit**.
> Chaque subfeature attend le go du PO avant développement (séquence CLAUDE.md : mini-spec →
> readiness → dev → review → push/release → merge).
> **Inspiration Claude Code (`/context`, `/cost`)**, mais **à notre sauce** : les commandes sont
> orientées vers les vraies problématiques de l'app — **coût** (préoccupation n°1 du PO), quotas,
> budget, état de compaction/recall, poste.

---

## 0. Le besoin

Offrir des **commandes slash** dans le **composer du terminal** de l'Atelier. On tape `/`, une
**autocomplétion** propose la liste des commandes ; on en choisit une ; la commande **interroge la
gateway** et **affiche un panneau** de résultat directement dans le terminal.

**Point clé, non négociable** : une commande **VUE ne consomme AUCUN tour modèle**. *Vérifier son coût
ne doit rien coûter.* C'est la raison d'être de la feature : rendre visibles, gratuitement et
instantanément, les informations que l'utilisateur veut consulter sans payer un aller-retour au modèle.

Le rendu doit être **TRÈS SOIGNÉ** (design system, jetons `--cg-*`), en particulier le vaisseau amiral
**`/cout`**, qui est la **vitrine** de la feature.

Ce n'est **pas un moteur d'IA** : ce sont des **vues sur NOS données** et des **actions déjà existantes**
rappelées par un raccourci de saisie. Gateway-First et Provider-First restent respectés (§7).

---

## 1. Ce qui existe déjà (bâtir dessus, ne pas réinventer)

### 1.1 Ce qui N'existe PAS

**Aucun mécanisme de commande slash** n'existe aujourd'hui dans le composer du terminal : ni
interception du `/`, ni autocomplétion de commandes, ni cadre de rendu de « panneau » non conversationnel.
C'est le **seul vrai manque** de la feature — le reste est de la donnée déjà disponible.

### 1.2 Les données et endpoints existent DÉJÀ (réutiliser, ne pas dupliquer)

L'audit du back confirme que la quasi-totalité de la matière est déjà servie :

- **`UsageController`** (`/usage`) — GET usage courant, `GET /usage/report`, `GET /usage/by-client`,
  `GET /usage/alert`, `POST /usage/alert/dismiss`.
- **`CostBudgetController`** (`/admin/cost`) — `GET/PUT /budget`, `GET /budget/{hostId}`,
  `GET /summary`, `GET /alerts`, `GET /projects`. *(Préfixe **`/admin/cost`** : l'accès budget est
  aujourd'hui **scopé admin** — voir §7 la note d'isolation et le point à préciser en SF-165-04.)*
- **`CostAlertController`** — les alertes de coût.
- Services de calcul : **`UsageCostEstimator`**, **`UsageLedgerService`**, **`QuotaWindowService`**,
  entité **`CostBudget`**, entité **`UsageTurn`** (table `usage_turns` — le grain « coût par tour »).
- **`AtelierChatController`** (`/workspaces/{id}/chat`) —
  `GET /resume` (turns / foldedTurns / état de compaction / mode / plan / bilan),
  `POST /compact` (compaction douce, F-162 / SF-162-04),
  `POST /restart` (Nouveau départ), `GET /turn` (état du tour courant).
- **Recall** (F-162) — package `atelier/recall` (recherche mot-clé + sémantique embeddings/pgvector,
  isolée `user_id` + `workspace_id`) : la matière de `/rappel`.
- **Statut runner** — `RunnerStatusService`, `RunnerHostOverviewService`, `RunnerHeartbeatService` :
  la matière de `/poste`.

**Conséquence de cadrage** : F-165 est **surtout frontend + agrégation légère**. On **réutilise** les
endpoints existants ; on n'ajoute un **petit endpoint d'agrégation** que là où c'est strictement
nécessaire (typiquement la **décomposition de coût par fil / par workspace** pour `/cout`, §5 SF-165-02).

### 1.3 Briques réutilisables

- **DESIGN_SYSTEM.md** — jetons `--cg-*` (obligatoire pour tout rendu), composants Material.
- **Version mobile** (F-151 / F-152, **SF-158**) — panneaux responsives, cibles **≥ 44 px**, à ne pas
  régresser.
- **F-162** — recall (mot-clé + sémantique) et état de compaction, surfacés par `/rappel` et `/contexte`.
- **F-70** — budget de dépense du projet, surfacé par `/budget`.

---

## 2. Cas d'usage (où ça sert dans notre application)

1. **« Combien ça me coûte ? »** — le cas roi : consulter en un `/cout` l'économie du fil, **sans payer
   un tour**.
2. **Comprendre pourquoi un fil coûte cher** — décomposition écriture cache / lecture cache / sortie,
   part compactée vs live, taille du contexte vivant.
3. **Suivre l'état mémoire** — `/contexte` : tours live vs résumés, résumé ancré, couverture du recall.
4. **Vérifier quota / budget** — `/quota`, `/budget` avant de lancer un gros travail.
5. **Savoir si le poste répond** — `/poste` : runner connecté, dernière fois vu, OS, shell.
6. **Se repérer dans le projet** — `/sujet` : carte (client, poste, nb tours, coût total, plan).
7. **Agir vite** — `/compacter`, `/nouveau`, `/rappel <terme>` sans quitter le composer.

---

## 3. Principes de design

- **Interception du `/`** en début de composer + **autocomplétion** de la liste (filtrée à la frappe).
- **Deux familles de commandes** :
  - **VUES** — **lecture seule**, **gratuites**, **AUCUN tour modèle**. Elles interrogent la gateway
    (endpoints existants + agrégation légère) et **affichent un panneau**.
  - **ACTIONS** — **réutilisent des endpoints existants** (compaction, restart, recall). Une action ne
    déclenche un traitement que là où l'endpoint existant l'implique **déjà** (voir §7 le point à
    préciser).
- **Rendu joli, instantané, responsive mobile** — jetons `--cg-*`, cibles **≥ 44 px**, **ne pas
  régresser SF-158**. Le panneau **`/cout`** est la **vitrine** : soin graphique maximal.
- **Langage « classeur »** — vocabulaire métier accessible (pages, rangé/vivant, chaud/froid), pas de
  jargon de facturation brut.

---

## 4. La liste des commandes (validée PO)

### VUES (lecture seule, gratuites, aucun tour modèle)

- **`/cout`** ⭐ *(vaisseau amiral)* — **l'économie du fil** :
  - coût **cumulé** et coût du **dernier tour** ;
  - **décomposition écriture cache vs lecture cache vs sortie** (montants **et %**) ;
  - **% de cache chaud** ;
  - **taille du contexte vivant** (en tokens ≈ « pages ») ;
  - **part compactée (rangée) vs live** ;
  - **budget restant** ;
  - **mini-tendance** coût/tour sur les derniers tours.

  En **langage « classeur »**. S'appuie sur **`usage_turns`** (agrégés par workspace) + `GET /resume`
  + budget. **C'est la vitrine — soin particulier au rendu.**
- **`/contexte`** — **état mémoire** : tours **live vs résumés**, présence d'un **résumé ancré**, nombre
  de tours repliés, « le recall couvre tout le fil ». **Rend visible ce que F-162 fait.**
- **`/quota`** — conso vs **plafond du plan**, restant, **reset** (fenêtre `QuotaWindowService`).
- **`/budget`** — **plafond de dépense du projet** (F-70), position, alertes.
- **`/poste`** — **état du runner** : connecté / vu il y a X, OS, shell.
- **`/sujet`** — **carte du projet** : client, poste, nb tours, coût total, plan.

### ACTIONS (réutilisent des endpoints existants)

- **`/compacter`** — **compaction douce maintenant** (`POST /compact`, F-162 / SF-162-04).
- **`/nouveau`** — **nouveau départ** (`POST /restart`).
- **`/rappel <terme>`** — lance un **recall dans l'historique** et **affiche les extraits** (surface le
  recall F-162 **directement**, **sans tour modèle**).

### META

- **`/aide`** — liste des commandes (et autocomplétion dès qu'on tape `/`).

---

## 5. Découpage (validé PO)

| SF | Intitulé | Contenu |
|----|----------|---------|
| **SF-165-01** | Le **mécanisme** (cœur) + `/aide` | Interception du `/` dans le composer, **autocomplétion** de la liste, **dispatch** des commandes, **cadre de rendu des panneaux** (design system, jetons `--cg-*`, responsive ≥ 44 px). **Garantie structurelle : une VUE ne passe JAMAIS par la boucle modèle (aucun tour).** Inclut **`/aide`** (liste + autocomplétion). Aucune donnée métier ici : c'est la tuyauterie et le cadre visuel commun. |
| **SF-165-02** | **`/cout`** ⭐ (vaisseau amiral) | Panneau **économie du fil** : coût cumulé / dernier tour, décomposition **écriture cache / lecture cache / sortie** (% inclus), % cache chaud, taille du contexte vivant (pages), part **compactée vs live**, budget restant, mini-tendance. **Petit endpoint d'agrégation de coût par workspace** depuis `usage_turns` **si nécessaire** (sinon composition des endpoints existants). **Soin graphique maximal** — c'est la vitrine. Isolation `user_id` + `workspace_id`. |
| **SF-165-03** | **`/contexte`** | État mémoire / compaction depuis `GET /resume` + données de compaction (live vs résumés, résumé ancré, tours repliés, couverture recall). Rend visible F-162. |
| **SF-165-04** | **`/quota`** + **`/budget`** | Câblage des endpoints existants : `UsageController` / `QuotaWindowService` pour le quota du plan ; `CostBudgetController` (F-70) pour le budget. **Point à trancher** : le budget est aujourd'hui sous `/admin/cost` (scopé admin) — préciser la vue **non-admin par projet** ou exposer une lecture projet isolée `user_id` (voir §7). |
| **SF-165-05** | **`/poste`** + **`/sujet`** | `/poste` : statut runner (`RunnerStatusService` / `RunnerHostOverviewService` — connecté, vu il y a X, OS, shell). `/sujet` : carte du projet (client, poste, nb tours, coût total, plan). |
| **SF-165-06** | Actions **`/compacter`** + **`/nouveau`** + **`/rappel`** | Câblage des endpoints existants : `POST /compact` (SF-162-04), `POST /restart`, et **recall** (F-162) pour `/rappel <terme>` avec **affichage des extraits, sans tour modèle**. |

**Ordre** : SF-165-01 (mécanisme, prérequis de tout le reste) → SF-165-02 (`/cout`, la vitrine) →
SF-165-03 → SF-165-04 → SF-165-05 → SF-165-06.

**Dépendances** : SF-165-02 s'appuie sur `usage_turns` + `GET /resume` + budget ; SF-165-03 sur F-162
(compaction/recall) ; SF-165-04 sur `UsageController`/`QuotaWindowService`/`CostBudgetController` (F-70) ;
SF-165-05 sur le statut runner ; SF-165-06 sur `/compact`, `/restart` et recall F-162. Tout le rendu
dépend de **DESIGN_SYSTEM** et de la **version mobile** (SF-158, ≥ 44 px).

---

## 6. Périmètre

### Dans le périmètre
- Des **commandes slash** tapées par l'utilisateur dans le composer, exécutées **côté client / gateway**,
  avec **rendu terminal** en panneau.
- **VUES gratuites** (aucun tour modèle) et **ACTIONS** réutilisant des endpoints existants.
- **Autocomplétion** + `/aide`.
- **Isolation `user_id`** (et `workspace_id`) sur **tout accès aux données**.

### Hors périmètre
- **Refonte du composer** (F-165 ajoute une interception + un cadre de panneau, ne réécrit pas l'écran).
- **Commandes d'administration multi-utilisateurs** (gestion d'autres comptes, réglages globaux).
- **Exposer le coût d'AUTRES utilisateurs** — isolation stricte : on ne montre jamais que **ses** données
  et **son** workspace.
- Toute logique de **« moteur IA »** (aucune commande ne génère de raisonnement modèle).

---

## 7. Architecture & garde-fous (conformité CLAUDE.md)

- **Gateway-First** — F-165 **présente des vues sur NOS données** et **rappelle des actions déjà
  existantes**. Le backend **ne devient pas un moteur d'IA** : aucune commande n'implémente de logique de
  génération.
- **Provider-First** — la matière (usage, coût, quota, budget, compaction, recall, statut runner) est
  déjà produite par la gateway ; **aucune capacité fournie par Claude n'est réimplémentée**. On **relaie
  et compose** ce qui existe.
- **Provider Independence** — rien de nouveau côté fournisseur ; aucun code métier introduit par F-165 ne
  dépend directement d'Anthropic.
- **« Vérifier son coût ne doit rien coûter »** — **règle structurelle** : les commandes **VUES ne
  déclenchent AUCUN tour modèle**. SF-165-01 doit **garantir par construction** qu'une vue court-circuite
  la boucle modèle (elle interroge des endpoints REST, jamais `runLoop`).
- **Isolation multi-tenant** — **toute** commande filtre **`user_id`** (et `workspace_id` là où le grain
  est le fil). **Point à préciser en SF-165-04** : `CostBudgetController` est sous **`/admin/cost`** (scopé
  admin aujourd'hui). Deux options à trancher au moment de la mini-spec : (a) exposer une **lecture budget
  par projet** isolée `user_id` pour le non-admin, ou (b) réserver `/budget` aux profils qui ont déjà le
  droit. **Ne jamais** exposer le budget/coût d'un **autre** utilisateur.
- **Actions et tour modèle** — les VUES ne coûtent rien ; les **ACTIONS** ne coûtent que ce que leur
  endpoint existant coûte **déjà** (`/compact` produit une synthèse serveur ; `/restart` ne relance pas de
  tour ; `/rappel` fait une **recherche**, **pas** un tour modèle). **À préciser explicitement** par SF, au
  cas par cas, dans chaque mini-spec — mais **le principe reste : aucune commande ne déclenche un tour
  modèle**, sauf éventuellement là où l'action l'implique déjà (à documenter alors nommément).
- **Design system** — tous les panneaux : jetons `--cg-*` uniquement, composants Material, responsive,
  cibles **≥ 44 px**, **aucune régression SF-158**, **aucune couleur / police hors DESIGN_SYSTEM**.

### Note de cohérence `ARCHITECTURE_CANONIQUE.md`
Le cadrage **ne crée aucune table**. F-165 lit des données existantes (`usage_turns`, budgets, messages
d'atelier, statut runner) et n'ajoute, au plus, qu'un **endpoint d'agrégation en lecture** (SF-165-02, si
nécessaire). **Aucune incohérence détectée** avec `ARCHITECTURE_CANONIQUE.md` : F-165 est un ensemble de
**vues et de raccourcis d'actions**, cohérent avec Gateway-First, n'introduisant aucune capacité de moteur
IA. Si une SF venait à créer une table (non prévu à ce stade), elle serait reflétée à l'étape 6 CLAUDE.md.

---

## 8. Ce qu'on ne fait PAS
- Une commande VUE qui **consommerait un tour modèle** (viole la règle fondatrice §0/§7).
- **Refondre** le composer ou l'écran du terminal (§6, hors périmètre).
- Des **commandes d'administration multi-utilisateurs** ou l'**exposition du coût d'autrui** (§6).
- **Réimplémenter** une capacité déjà fournie (usage, recall, compaction, budget existent) — on **relaie**.
- Créer une table sans nécessité (le cadrage n'en prévoit **aucune**).

---

## 9. Statut
**Cadrée / À faire.** Inscrite à `docs/PRODUCT_SPEC.md` (règle d'existence CLAUDE.md). Découpage validé PO
(SF-165-01 → 06). **Aucun développement engagé** — chaque SF suivra la séquence obligatoire.
