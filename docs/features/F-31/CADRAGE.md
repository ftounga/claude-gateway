# Cadrage — F-31 : Atelier sur dépôt Git

---

## Identifiant

`F-31`

## Statut

`livrée (5/5)` — décisions D1/D2/D3 **arbitrées par l'owner le 2026-08-25**. SF-31-05 débloquée par la
levée d'**OQ-11** (2026-08-25 : un PAT fine-grained est accepté comme credential `static_bearer` du serveur
MCP GitHub) et livrée le 2026-08-26 — D2 reste sur le PAT chiffré, aucune bascule GitHub App.
**SF-31-01→04 livrées et mergées** le 2026-08-25 (PR #138/#139, #141/#142, #143/#144, #145/#146),
**SF-31-05 le 2026-08-26** (PR #159/#161, correctif #162). Le repli prévu par ce cadrage reste offert
à l'écran, à côté du bouton de création : si la pull request n'aboutit pas, l'utilisateur l'ouvre
depuis le lien de comparaison renvoyé par SF-31-04. Voir § *État d'avancement* en fin de document.

## Date

2026-08-25

---

## Le problème

L'Atelier travaille aujourd'hui sur un **`.zip` téléversé**. Pour utiliser Claude sur un projet réel,
un consultant doit : exporter son dépôt en archive, la téléverser, laisser l'agent travailler, puis
re-télécharger les fichiers modifiés et les réintégrer à la main dans son dépôt.

Personne ne travaille comme ça. C'est l'écart le plus visible entre l'Atelier et Claude Code, et il
disqualifie l'outil pour l'usage même qu'il vise.

**Ce contournement contredit la règle du projet.** `PROJECT.md` §3.3 (Provider-First) impose de se
demander « Claude fournit-il déjà cette capacité ? » avant d'implémenter. Ici la réponse est oui —
depuis le départ — et nous lui avons substitué un mécanisme plus pauvre. Vérification faite sur le
code : zéro occurrence de `github_repository`.

---

## Ce que le fournisseur offre déjà

Vérifié dans la documentation de l'API Managed Agents (2026-08-25) :

| Capacité | Détail |
|----------|--------|
| **Montage d'un dépôt** | `resources: [{type: "github_repository", url, authorization_token, mount_path, checkout}]` à la création de session |
| **Choix de la révision** | `checkout: {type: "branch", name}` ou `{type: "commit", sha}` ; défaut = branche par défaut |
| **`git pull` / `git push`** | Fonctionnent depuis le sandbox, routés par un **proxy git côté Anthropic** |
| **Création de PR** | **Non couvert** par le montage : exige en plus le serveur **MCP GitHub** et un vault |

### Le point de sécurité, qui change l'analyse

Le `authorization_token` **n'est jamais placé dans le conteneur**. Le proxy git d'Anthropic l'injecte
*après* que la requête a quitté le sandbox. Le code qui s'exécute dans le conteneur — y compris ce que
l'agent écrit lui-même — **ne peut ni le lire ni l'exfiltrer**. Le token n'est pas non plus renvoyé
dans les réponses de l'API.

C'était l'objection principale attendue (« confier un accès au dépôt d'un client à un tiers ») ; elle
tombe en grande partie. Ce qui reste vrai et doit être assumé : le **contenu du dépôt** transite chez
Anthropic, exactement comme le contenu des `.zip` aujourd'hui — le périmètre de confiance ne change
pas, seul le mode d'acheminement change.

### Permissions du jeton (PAT fine-grained)

| Portée | Ce qu'elle permet |
|--------|-------------------|
| `Contents: Read` | Cloner uniquement |
| `Contents: Read and write` | Pousser une branche, préparer une PR |

---

## Décisions à arbitrer

### D1 — Jusqu'où va l'écriture ?

| Option | Ce que l'utilisateur obtient | Coût | Jeton requis |
|--------|------------------------------|------|--------------|
| **A. Lecture seule** | Le dépôt est cloné, l'agent travaille, les fichiers modifiés sont **téléchargeables** (comme aujourd'hui) | Faible | `Contents: Read` |
| **B. Push d'une branche** *(recommandé)* | L'agent pousse son travail sur une **branche dédiée** ; l'utilisateur ouvre la PR lui-même sur GitHub | Moyen | `Contents: Read and write` |
| **C. Pull request complète** | L'agent crée la PR de bout en bout | Élevé — exige le **MCP GitHub** + vault, surface nouvelle | `Contents: Read and write` |

**Recommandation initiale : B.** **Arbitrage de l'owner (2026-08-25) : C — pull request complète.**

Conséquence assumée : le **MCP entre dans le produit**. ADR-014 avait écarté « les commandes slash, les
plugins et les skills natifs de Claude Code » ; MCP n'y figurait pas explicitement, mais c'est une
extension de périmètre qui mérite d'être tracée → **ADR-015**.

⚠️ **Incertitude à lever avant SF-31-05** (voir § Risque MCP).

### D2 — Comment l'utilisateur s'authentifie-t-il ?

| Option | Avantages | Inconvénients |
|--------|-----------|---------------|
| **A. PAT saisi par l'utilisateur** *(recommandé)* | Réutilise **tel quel** le mécanisme BYOK (F-03) : `ByokKeyCipher`, chiffrement enveloppe KMS, affichage masqué. Aucune brique nouvelle. | L'utilisateur doit créer un PAT sur GitHub (friction) |
| **B. GitHub App / OAuth** | Aucune friction, révocation propre, granularité par dépôt | Chantier à part entière : enregistrement d'app, callback OAuth, rafraîchissement de jetons, gestion d'installation |

**Recommandation : A.** **Arbitrage de l'owner (2026-08-25) : A — PAT chiffré.** Le chiffrement au
repos existe déjà et a été audité pour la clé Claude ; le PAT suit exactement le même chemin. B reste
la cible si la friction se révèle bloquante — ou si le § Risque MCP l'impose.

### D3 — Que devient le workspace ?

| Option | Description |
|--------|-------------|
| **A. Nouveau type de workspace** *(recommandé)* | Un workspace a une **source** : `ARCHIVE` (actuel) ou `GIT`. Les écrans, l'explorateur et le mode Terminal restent communs. |
| **B. Entité séparée** | Un « projet Git » distinct du workspace, avec ses propres écrans |

**Recommandation : A.** **Arbitrage de l'owner (2026-08-25) : A — source du workspace.** Tout ce qui
est déjà construit (arborescence, explorateur, terminal, session persistante, historique) s'applique
sans modification.

---

## Risque MCP — à lever avant de s'engager sur SF-31-05

D1 = C et D2 = A se combinent mal **sur un point précis**, découvert en cadrant :

- Le **montage du dépôt** (clone, `git pull`, `git push`) s'authentifie avec le PAT via le proxy git.
  Aucun doute : c'est exactement ce que documente l'API.
- La **création de PR** passe par le serveur MCP GitHub, qui s'authentifie **par un vault**, pas par le
  jeton du dépôt. Or la documentation avertit : *« les serveurs MCP hébergés exigent typiquement des
  jetons bearer OAuth, pas les clés d'API natives du service »* — l'exemple donné (un jeton
  d'intégration Notion inopérant comme credential MCP) montre que ce sont deux systèmes distincts.

**Ce qui n'est pas tranché** : un PAT GitHub est-il accepté comme credential `static_bearer` du serveur
MCP GitHub, ou faut-il un jeton OAuth — ce qui ramènerait à D2 option B (GitHub App) ?

**Atténuation retenue** : SF-31-01→04 (jeton, clone, explorateur, push de branche) **ne dépendent pas**
de cette réponse et livrent déjà l'essentiel du gain. La question se vérifie empiriquement — une
requête de test avec un PAT en `static_bearer` — **avant** d'engager SF-31-05. Si la réponse est
négative, deux options : basculer D2 sur GitHub App, ou s'arrêter à SF-31-04 (l'utilisateur ouvre la PR
depuis le lien de comparaison GitHub, qui s'affiche après le push).

Cette vérification est le **premier acte** de SF-31-05, pas une inconnue laissée en suspens.

---

## Découpage retenu

| Subfeature | Contenu | Dépend de |
|------------|---------|-----------|
| **SF-31-01** | Stockage du jeton GitHub (chiffré, réutilise `ByokKeyCipher`), écrans d'ajout/suppression, jeton masqué | — |
| **SF-31-02** | Création d'un workspace depuis une URL de dépôt : source `GIT`, branche, montage à la session | SF-31-01 |
| **SF-31-03** | Arborescence et explorateur sur un workspace Git (lecture des fichiers clonés) | SF-31-02 |
| **SF-31-04** | Push du travail sur une branche dédiée + lien vers la comparaison GitHub | SF-31-02 |
| **SF-31-05** | Création de PR via MCP GitHub + vault — **débute par la levée du § Risque MCP** | SF-31-04 |

Chaque subfeature reste sous les deux jours et suit le cycle habituel (mini-spec → readiness → dev →
review → release).

---

## Impacts

### Sécurité

- Le jeton est **chiffré au repos** (mécanisme F-03 déjà en place) et n'est déchiffré qu'au moment du
  montage de session.
- Il **n'entre jamais dans le sandbox** (proxy git côté fournisseur) — l'agent ne peut pas l'exfiltrer.
- Isolation `user_id` : un jeton appartient à un utilisateur, un workspace Git appartient à un
  utilisateur ; les règles existantes (`requireOwned`) s'appliquent sans exception nouvelle.
- **À documenter explicitement pour l'utilisateur** : le contenu du dépôt est transmis à Anthropic,
  comme l'est aujourd'hui le contenu des archives.

### Architecture

- `Workspace` gagne une **source** (`ARCHIVE` / `GIT`) + URL, branche, jeton associé → migration.
- `AtelierSessionService` monte un `github_repository` au lieu de téléverser les fichiers, quand la
  source est `GIT`. Le reste du flux (session persistante, resync, terminal) est **inchangé**.
- Aucune atteinte à Gateway-First : on relaie une capacité du fournisseur, on n'en construit aucune.

### Coût

Neutre à favorable : un clone remplace N téléversements de fichiers, et supprime le plafond
`maxSessionFiles` (300) qui limite aujourd'hui les projets volumineux.

---

## Hors périmètre

- Autres forges (GitLab, Bitbucket) : l'API ne monte que GitHub
- Résolution de conflits, rebase, gestion de l'historique Git par l'agent
- Revue de code automatisée sur PR entrantes (ce serait une autre feature)
- Webhooks GitHub / déclenchement automatique

---

## Questions ouvertes

| # | Question | Impact si non tranchée |
|---|----------|------------------------|
| OQ-A | Un workspace Git est-il **rafraîchi** (`git pull`) au début de chaque session, ou reste-t-il figé à son clone initial ? | Un dépôt qui bouge côté équipe diverge silencieusement de la sandbox |
| OQ-B | Que se passe-t-il si le jeton expire alors qu'un workspace Git existe ? | Le workspace devient inutilisable sans message clair |
| OQ-C | Limite de taille du dépôt cloné ? | Un monorepo pourrait saturer la sandbox et faire exploser le temps de session facturé |

Ces trois questions sont **traitables au niveau des subfeatures**, elles ne bloquent pas l'arbitrage
de D1/D2/D3.

---

## Écarts restants avec Claude Code (hors F-31)

Relevé le 2026-08-25 en comparant l'Atelier à Claude Code, **vérifié dans le code** (pas de mémoire).
Ces écarts sont des **features candidates distinctes** : chacune exige son propre cadrage et sa propre
entrée au `PRODUCT_SPEC` avant tout développement. Elles sont listées ici parce qu'elles sont apparues
dans la même analyse que F-31, **pas** pour être traitées avec elle.

| Écart | Chez nous | Offert par l'API | Candidate | Effort |
|-------|-----------|------------------|-----------|--------|
| **Interrompre un run en cours** | ❌ aucune — un `npm install` parti de travers tourne jusqu'au timeout | ✅ `user.interrupt` | **F-32** | ~1 subfeature |
| **Valider une action avant exécution** | ❌ `always_allow` : l'agent peut supprimer un fichier sans demander | ✅ `user.tool_confirmation` | **F-33** | ~2 subfeatures |
| **Instructions par projet** | ❌ un system prompt **global**, identique pour tous les workspaces | ✅ `agent_with_overrides` (par session) | **F-34** | ~1–2 subfeatures |
| **Sous-agents / parallélisme** | ❌ | ✅ `multiagent` | **F-35** | ~1 subfeature, **fort impact coût** |
| Skills, commandes slash | ❌ | ✅ | **hors périmètre** — ADR-014, maintenu | — |
| ~~MCP~~ | ❌ | ✅ | **entre par F-31 SF-31-05** (ADR-015) | — |

### Pourquoi cet ordre

1. **F-32 (interruption)** — le meilleur rapport gain/effort. Aujourd'hui, une commande partie de
   travers consomme du temps de sandbox facturé jusqu'au timeout, et l'utilisateur regarde sans
   pouvoir agir. L'API expose l'événement, le flux SSE existe déjà.
2. **F-34 (instructions par projet)** — ce qui fait passer les réponses de génériques à pertinentes.
   Un `CLAUDE.md` lu dans le workspace et injecté via `agent_with_overrides` au moment de la session :
   pas besoin de toucher à l'agent global. C'est d'ailleurs le mécanisme que Claude Code utilise.
3. **F-33 (validation d'action)** — l'agent est en `always_allow`. C'était acceptable quand la sandbox
   était jetable à chaque message ; depuis SF-30-04 elle **vit entre les messages**, et une suppression
   malheureuse n'est plus annulée par la fin de session. Le coût vient du flux, qui devient
   **bidirectionnel** : il faut renvoyer une confirmation dans une session en cours.
4. **F-35 (sous-agents)** — puissant, mais multiplie la consommation. À n'ouvrir qu'une fois les
   plafonds et le décompte éprouvés en usage réel.

### Correction au relevé initial

Le premier relevé classait **MCP** en « hors périmètre assumé (ADR-014) ». C'est exact pour les skills
et les commandes slash, **plus pour MCP** : l'arbitrage D1 = C (pull request complète) le fait entrer
dans le produit, ce que trace **ADR-015**.

---

## État d'avancement (mise à jour 2026-08-25 — feature close)

| Subfeature | État | Livraison |
|------------|------|-----------|
| SF-31-01 — jeton GitHub chiffré | ✅ livrée | PR #138 (back), #139 (front) |
| SF-31-02 — workspace depuis un dépôt | ✅ livrée | PR #141 (back), #142 (front) |
| SF-31-03 — arborescence et explorateur | ✅ livrée | PR #143 (back), #144 (front) |
| SF-31-04 — push d'une branche dédiée | ✅ livrée | PR #145 (back), #146 (front) |
| SF-31-05 — création de PR via MCP | ✅ livrée | PR #159 (back), #161 (front), #162 (correctif RGPD) |

### Le § *Risque MCP* est levé

La question posée par ce cadrage — *un PAT GitHub est-il accepté comme credential `static_bearer` du
serveur MCP GitHub, ou faut-il un jeton bearer OAuth ?* — a été tranchée le 2026-08-25 par
**vérification empirique** avec un PAT réel :

| Test | Résultat |
|------|----------|
| `GET https://api.github.com/user` | **200** — jeton valide |
| `POST https://api.githubcopilot.com/mcp/` (`initialize`), PAT en `Authorization: Bearer` | **200** |
| `tools/list` | **200** — 44 outils, dont `create_pull_request` |

L'avertissement de la documentation (« les serveurs MCP hébergés attendent typiquement des jetons
OAuth ») vaut pour d'autres services — l'exemple cité était Notion — **pas pour GitHub**.

**Conséquence** : **D2 reste sur le PAT chiffré** livré en SF-31-01. La bascule GitHub App (D2
option B), qui aurait ouvert un chantier à part entière, n'est pas nécessaire. `OQ-11` est close.

### Ce que SF-31-05 a livré

- `POST /api/workspaces/{id}/git/pull-request` : l'agent appelle `create_pull_request` du serveur MCP
  GitHub ; le backend **constate** ensuite l'existence de la pull request auprès de GitHub avant
  d'annoncer une URL. Même règle qu'au push — ce que l'agent déclare ne suffit pas.
- **Migration `045`** : `user_git_credentials` gagne `mcp_vault_id` et `mcp_credential_id`, nullables,
  sans aucun secret. Réversible, Postgres + H2.
- **Un vault par utilisateur**, créé paresseusement à la première session Git et **détruit à la
  révocation** du jeton — remplacement, retrait, ou suppression du compte (F-11).
- Le **repli reste offert à l'écran** : le lien de comparaison de SF-31-04 vit à côté du bouton de
  création. Si le MCP n'aboutit pas, l'utilisateur ouvre sa pull request lui-même.

### Ce qui reste connu et assumé

Le fournisseur n'accepte `vault_ids` qu'**à la création** de session. Une session ouverte avant cette
version n'a donc pas l'outil : l'agent le dira, `created` vaudra `false`, et « Réinitialiser la
sandbox » (SF-30-06) rouvre une session équipée. On ne bascule pas de session en douce — l'utilisateur
perdrait son contexte de travail.

**OQ-A** (pas de `git pull` automatique), **OQ-B** (le workspace survit au retrait du jeton) et
**OQ-C** (taille du dépôt, bornée par la sandbox du fournisseur) ont été traitées au fil des
subfeatures, comme prévu.

---

## Prochaine étape

Aucune : **F-31 est close**. Les cinq subfeatures sont livrées, mergées et vérifiées sur `main`
(**759 tests backend + 418 frontend verts** au 2026-08-25), et les branches distantes ont été
supprimées après merge.

Les écarts restants avec Claude Code recensés plus haut sont désormais des features à part entière —
F-32, F-33 et F-34 sont livrées ; F-35 (sous-agents) reste au backlog, derrière son flag et son
plafond de coût.
