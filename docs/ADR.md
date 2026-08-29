# ADR.md — Architecture Decision Records

Ce document enregistre les décisions architecturales majeures de Claude Gateway. Chaque ADR explique le contexte, la décision, la justification et les conséquences. Toute évolution architecturale future met à jour ce document plutôt que de changer silencieusement la direction du projet.

---

## ADR-001 — Gateway Architecture
**Status** : Accepted

**Context** — L'objectif est de fournir un accès professionnel à Claude tout en ajoutant des capacités plateforme. Deux approches étaient envisagées : (1) construire une nouvelle plateforme IA ; (2) construire une Gateway devant des fournisseurs IA existants.

**Decision** — Claude Gateway est implémenté comme une Gateway. Il ne remplace pas les fournisseurs IA ; il les orchestre.

**Consequences** — La logique métier reste séparée des capacités IA. Les futurs fournisseurs s'intègrent sans re-concevoir la plateforme.

---

## ADR-002 — Provider Abstraction
**Status** : Accepted

**Context** — Les versions futures supporteront plusieurs fournisseurs IA. Une dépendance directe à Anthropic rendrait l'évolution difficile.

**Decision** — Chaque fournisseur IA implémente une interface commune. Les modules métier ne communiquent qu'avec cette abstraction.

**Consequences** — L'ajout de nouveaux fournisseurs devient simple. La logique métier reste indépendante du fournisseur.

---

## ADR-003 — Cloud-Native Architecture
**Status** : Accepted

**Context** — La plateforme est destinée à tourner sur Kubernetes.

**Decision** — Tous les services sont conçus cloud-native. Les instances restent stateless. La configuration est externalisée.

**Consequences** — Scaling simplifié, résilience améliorée, déploiements automatisés.

---

## ADR-004 — Version 1 Scope
**Status** : Accepted

**Context** — Plusieurs capacités IA avancées étaient initialement envisagées pour la V1 : OCR, Textract, RAG, embeddings, bases vectorielles.

**Decision** — La V1 se concentre exclusivement sur la reproduction de l'expérience Claude tout en ajoutant les capacités plateforme. Les fonctionnalités IA avancées sont reportées.

**Consequences** — Complexité réduite, livraison plus rapide, coûts opérationnels plus bas, maintenance simplifiée.

---

## ADR-005 — Hosted and BYOK
**Status** : Accepted

**Context** — Les utilisateurs ont des préférences de facturation différentes. Certains préfèrent leur propre compte Anthropic ; d'autres un abonnement plateforme tout compris.

**Decision** — Supporter les modes Hosted et BYOK.

**Consequences** — Flexibilité accrue, adoption élargie, meilleur positionnement commercial.

---

## ADR-006 — Modular Architecture
**Status** : Accepted

**Context** — La plateforme évoluera sur plusieurs années. De gros modules monolithiques deviendraient difficiles à maintenir.

**Decision** — La plateforme est divisée en modules métier indépendants, chacun avec une responsabilité unique.

**Consequences** — Maintenabilité améliorée, tests simplifiés, évolution indépendante.

---

## ADR-007 — PostgreSQL
**Status** : Accepted

**Context** — La V1 requiert une persistance transactionnelle.

**Decision** — Utiliser PostgreSQL comme base relationnelle principale.

**Consequences** — Persistance fiable, garanties transactionnelles fortes, compatibilité avec un service managé cloud-native.

---

## ADR-008 — Angular Frontend
**Status** : Accepted

**Context** — Le frontend doit supporter une application d'entreprise à longue durée de vie.

**Decision** — Angular est sélectionné comme framework frontend.

**Consequences** — Forte modularité, outillage orienté entreprise, maintenabilité long terme.

---

## ADR-009 — Spring Boot Backend
**Status** : Accepted

**Context** — Le backend requiert un framework d'entreprise mature.

**Decision** — Spring Boot est sélectionné.

**Consequences** — Large écosystème, excellent support cloud-native, fortes capacités de test.

---

## ADR-010 — Documentation as Code
**Status** : Accepted

**Context** — Claude Gateway est intentionnellement développé avec assistance IA. L'IA requiert une documentation stable et structurée.

**Decision** — La documentation fait partie de l'architecture logicielle. `PROJECT.md` est la source de vérité primaire ; les documents de support définissent les détails d'implémentation.

**Consequences** — Meilleure collaboration IA, onboarding amélioré, dérive architecturale réduite, évolution cohérente long terme.

---

## ADR-011 — Extension du périmètre au traitement documentaire (supersède ADR-004)
**Status** : Accepted (2026-07-01) — **supersède ADR-004**

**Context** — ADR-004 excluait de la V1 l'OCR, le RAG, les embeddings et les bases vectorielles pour livrer vite une passerelle pure. La passerelle (F-01→F-12) étant livrée, le PO décide d'intégrer le traitement documentaire au produit.

**Decision** — Le traitement documentaire **entre dans le périmètre** via **F-05 (OCR Textract), F-06 (Ingestion RAG : chunking + embeddings + pgvector), F-07 (Q&A documenté), F-08 (Statut documents)**, plus **F-13 (Templates), F-14 (Export), F-15 (Embeddings locaux), F-16 (Rapports d'usage)**. Restent hors périmètre : V3 (F-17 équipes, F-18 on-prem).

**Contraintes conservées** — Gateway-First (le backend orchestre, ne devient pas un LLM) ; Provider Independence (interface `AIProvider`) ; isolation `user_id` ; traitements lourds asynchrones (workers) ; secrets hors code. pgvector et l'IRSA Textract, provisionnés puis laissés dormants, sont réactivés.

**Consequences** — Complexité et coûts en hausse (pgvector, Textract, workers async) assumés pour la valeur documentaire. `PROJECT.md` amendé en conséquence (§Amendement).

---

## ADR-012 — Claude Code Lite (« Atelier ») — offre Gold / premium
**Status** : Proposed (2026-07-10) — **amende le périmètre V3**

**Context** — Offrir un espace de travail hébergé « façon Claude Code » : l'utilisateur uploade un projet (`.zip`) + `CLAUDE.md` + skills, et fait travailler Claude dessus dans un **flux unique** (fidèle à Claude Code ; panneau Fichiers repliable en bonus). Objectif : différencier une offre **Gold / premium +++**. Principe directeur : **Gateway-First / Provider-First** — on **relaie** la capacité d'Anthropic, on ne construit ni moteur d'agent ni sandbox maison.

**Decision**
- **Périmètre** : Claude Code Lite entre comme **offre Gold distincte** (au-dessus de V1/V2). Restent hors scope : multi-LLM runtime, on-prem (F-18), espaces d'équipe (F-17).
- **Clé / fournisseur : les deux modes, BYOK par défaut sur l'Atelier.** L'utilisateur choisit : **BYOK** (sa clé Anthropic → il porte tokens **et** sandbox ; défaut recommandé) ou **Hosted** (clé plateforme, **compté + marqué + plafonné**). Réutilise `AIProvider` + BYOK chiffré KMS (existant).
- **Tarification (rentabilité garantie)** — 3 briques : (1) **Accès Gold** (abonnement fixe = marge fixe couvrant le socle) ; (2) **Conso Hosted** facturée ~**2× le coût Anthropic** (tokens) + **frais par run / minute de sandbox** ; (3) **BYOK** = accès Gold seul (coût conso nul). Garde-fous obligatoires : **plafond de dépense par user et par tâche**, ceilings tokens/minutes par run, imputation sur le quota F-10 + surcompteur sandbox.
- **Architecture** : workspace **par user + projet** en **S3 isolé** ; **Phase 1** = tool-runner backend d'**opérations fichiers** (`list/read/write/search`) via tool-use, **sans exécution**, UI flux unique ; **Phase 2** = relais des **Managed Agents / code execution d'Anthropic** (sandbox + boucle hébergés) via une abstraction `AgentProvider` (parallèle d'`AIProvider`).
- **Sécurité** : protection **zip-slip** + **zip-bomb** + plafonds taille/nb fichiers ; outils Phase 1 = fichiers only (pas de réseau/shell) ; sandbox Phase 2 = celui d'Anthropic ; clés BYOK chiffrées KMS ; isolation `user_id` stricte, workspaces éphémères.
- **Phasage** : **Phase 1 d'abord** (faisable, sûr, gros ROI) ; **Phase 2** conditionnée à la confirmation, côté compte Anthropic, de la disponibilité Managed Agents / code execution et de la **grille de coût sandbox**.

**Consequences** — Aligné Gateway-First (relais) ; BYOK et quota réutilisés. Variance de coût (agents) maîtrisée par plafonds + markup + BYOK. Dépendance API Anthropic assumée (Provider-First). À lever avant Phase 2 : accès Managed Agents + tarif sandbox. Grille exacte (accès Gold €, markup, prix sandbox) figée au branchement Stripe de l'offre Gold.

---

## ADR-013 — Claude Code Lite Phase 2 : exécution via Managed Agents d'Anthropic (précise ADR-012)

**Status** : Proposed (2026-07-11) — **précise la Phase 2 d'ADR-012**. Cadrage détaillé : `docs/features/F-28/PHASE-2-cadrage.md`.

**Context** — La Phase 1 (F-28, SF-28-01→07, livrée) offre un Atelier où Claude lit/édite les fichiers **sans exécution** (boucle tool-use orchestrée par notre backend). La Phase 2 doit apporter l'**exécution réelle** (bash, tests, build, exécution de code) pour faire de l'Atelier un véritable agent de développement (offre Gold premium +++). Deux verrous étaient ouverts dans ADR-012 : disponibilité Managed Agents et grille de coût sandbox.

**Verrous levés** — Managed Agents est **disponible en β sur l'API Anthropic first-party** (notre clé `ANTHROPIC_API_KEY`) et Claude Platform on AWS (❌ Bedrock/Vertex/Foundry — non pertinent). SDK Java officiel (`client.beta().agents()/environments()/sessions()/files()`). Pont fichiers natif (Files API + `resources` montés + `/mnt/session/outputs/`).

**Decision**
- **Relais, pas de sandbox maison** (Gateway-First/Provider-First) : la boucle d'agent ET le conteneur d'exécution sont **hébergés par Anthropic** (Managed Agents, environment `config.type: "cloud"`). On ne construit ni moteur d'agent ni sandbox.
- **Abstraction `AgentProvider`** (parallèle d'`AIProvider`) : impl. `AnthropicManagedAgentProvider` via le SDK Java beta (header `managed-agents-2026-04-01`). Le code métier de l'Atelier ne dépend jamais directement d'Anthropic.
- **Flux Agent→Session** respecté : **Environment** (template cloud, `networking: limited` + `allow_package_managers`) et **Agent** (config versionnée : model, system = `CLAUDE.md`+skills, tools exécution, skills) créés **une fois** au bootstrap ; une **Session éphémère** par tâche, référençant l'agent, avec le workspace S3 **monté** en `resources` et le resync des sorties (`/mnt/session/outputs/`) vers S3.
- **Streaming** : events de session (SSE Anthropic) relayés au frontend via le patron SSE existant (SF-28-05), avec **reconnexion consolidée** (pas de replay côté Anthropic).
- **Économie** : 3 briques (accès Gold fixe déjà livré ; Hosted = tokens ~2× coût **+ minutes de sandbox** ; BYOK = accès seul). **Garde-fous obligatoires** : plafonds dépense/utilisateur et /tâche, ceilings minutes+tokens/session, timeout dur, **surcompteur sandbox** distinct du quota tokens.
- **Sécurité** : sandbox Anthropic éphémère, `networking limited` (deny-by-default), isolation `user_id` (session créée pour un workspace `requireOwned`, fichiers du seul préfixe S3 du user), secrets via **Vaults** (jamais dans le conteneur), tokens Git via git-proxy.
- **Découpage** : SF-28-08 (`AgentProvider` + bootstrap env/agent) → SF-28-09 (cycle session + pont fichiers) → SF-28-10 (streaming events UI) → SF-28-11 (surcompteur sandbox + plafonds + billing — ZONE ARGENT) → SF-28-12 (bascule Phase 1↔2 par workspace, flag + Gold).

**Coût sandbox — CONFIRMÉ** — Grille officielle Managed Agents (pricing Anthropic, 2026-07) : **tokens au tarif standard** (Opus 4.8 = 5/25 $/M) **+ runtime de session à 0,08 $/heure**, facturé uniquement pendant `running` (idle non facturé), remplaçant la facturation conteneur du code-execution. Le runtime est **marginal** vs les tokens → offre Gold très rentable ; le surcompteur sandbox sert de garde-fou. **Décisions owner restantes (non bloquantes pour la fondation SF-28-08→10)** : valider le markup Hosted (~2×) + plafonds par défaut avant **SF-28-11**, et confirmer l'accès β Managed Agents **sur le compte mutualisé legalcase**.

**Consequences** — Réutilise BYOK/quota/S3/SSE existants ; l'abstraction `AgentProvider` isole le risque beta. Nouvelle dimension de coût (minutes sandbox) maîtrisée par plafonds + markup + BYOK par défaut. Dépendance API Anthropic assumée (Provider-First). Aucun dev Phase 2 ne démarre avant la levée du verrou coût sandbox.

---

## Maintaining ADRs
Chaque décision architecturale significative est documentée avant l'implémentation. Les décisions historiques ne sont jamais supprimées : de nouveaux ADR supersèdent les précédents tout en préservant l'historique du projet. La connaissance architecturale fait partie du logiciel lui-même.

---

## ADR-014 — Atelier : session persistante et rendu terminal (révise ADR-013)

**Status** : **Accepted (2026-08-24)** — implémenté **intégralement** et déployé en production via F-30 SF-30-01→06 (PR #129, #130, #131, #132, #133, #134). **Révise** ADR-013 §Decision (« Session éphémère par tâche »). ADR-012 §Architecture (dualité de modes) est **confirmé, pas révisé** : les deux modes sont conservés (arbitrage du 2026-08-23).

**Context** — L'objectif énoncé dès l'origine était « Claude Code dans le navigateur ». Ce qui est livré (F-28, SF-28-01→15) fonctionne : l'exécution réelle est active en production, Claude lance bash, tests et build dans une sandbox Anthropic. Mais l'expérience ne ressemble pas à Claude Code, pour trois raisons cumulées, toutes issues de décisions antérieures :

1. **Session éphémère** (ADR-013) : une session Managed Agents est créée **par message**, les fichiers y sont montés, l'exécution a lieu, les sorties sont resynchronisées, puis la session est détruite. Chaque message repart d'une sandbox vierge : `npm install` fait au message *n* est perdu au message *n+1*.
2. **Nommage et hiérarchie des modes** (ADR-012, phasage Phase 1 / Phase 2) : les libellés « Édition » et « Exécution » décrivent une mécanique interne, pas une promesse utilisateur, et rien ne signale que l'exécution est la capacité premium. La conservation des deux modes est **maintenue** (un mode sans exécution reste utile et accessible hors Gold) ; c'est leur présentation qui change.
3. **Sorties d'outils non relayées** : `ManagedEventListener` n'expose que `onAgentText`, `onAction` (`agent.tool_use`) et `onStatus`. Les events **`agent.tool_result` ne sont jamais transmis** au frontend. L'UI affiche donc le nom de la commande, jamais son `stdout`/`stderr` — alors que dans Claude Code, voir le retour des commandes *est* l'expérience.

**Fait nouveau qui justifie la révision** — la justification économique de la session éphémère (« une session maintenue ouverte coûte du temps de sandbox facturé ») est **erronée**. La documentation de l'API est explicite :

- le runtime est mesuré en `active_seconds` = *« time with ≥1 thread running »* : une session **`idle` n'est pas facturée** ;
- une session en pause **conserve son historique et sa sandbox** ;
- les événements sont acceptés **quand la session est `running` ou `idle`** : on peut continuer à dialoguer dans la même session.

L'éphémère ne fait donc économiser aucun runtime. Il en **coûte** : réinstaller les dépendances à chaque message consomme du temps de sandbox facturé et des tokens.

**Decision**

- **Session persistante par workspace** (révise ADR-013) : une session Managed Agents est ouverte au premier message d'un workspace, son `session_id` est persisté, et les messages suivants sont envoyés **dans cette même session**. La sandbox, l'état du système de fichiers et l'historique survivent d'un message à l'autre. Reprise transparente ; ouverture d'une nouvelle session si l'ancienne est terminée ou irrécupérable.
- **Deux modes conservés, renommés** (confirme ADR-012) : « Édition » → **« Assistant »** (Claude lit et écrit les fichiers, sans exécution) et « Exécution » → **« Terminal »** (Claude exécute réellement). La boucle tool-use Phase 1 est **conservée** : elle sert le mode Assistant, accessible hors Gold.
- **Le mode Terminal est mis en valeur comme capacité Gold** : badge « Gold » et accent orange de la charte sur le bouton. Le gating reste inchangé (Gold/ADMIN côté backend) ; seule sa lisibilité commerciale change.
- **Nommage : pas de reprise de la marque « Claude Code »** dans l'interface. « Terminal » décrit le comportement sans emprunter un nom de produit d'Anthropic — précaution renforcée par l'épisode « Claude Proxy » (F-29).
- **Rendu terminal** : relais des events `agent.tool_result` en plus de `agent.tool_use`, et affichage de chaque commande **avec son en-tête et sa sortie** (`stdout`/`stderr`, code de retour) dans une présentation de type terminal — fond sombre, police monospace, puce colorée par type d'événement, sortie **tronquée et dépliable** (sans quoi un `npm install` noierait le fil), et indicateur d'activité portant la durée écoulée et les tokens consommés.
- **Hors périmètre explicite** : les commandes slash (`/init`, `/clear`…), les plugins et les skills natifs de Claude Code. On reproduit le **comportement de terminal**, pas l'écosystème du CLI.
- **Inchangé** : Gateway-First / Provider-First (la sandbox et la boucle d'agent restent celles d'Anthropic, on ne construit rien), abstraction `AgentProvider`, isolation `user_id`, `networking: limited`, plafonds et surcompteur sandbox.

**Consequences**

- ✅ Enchaîner les tâches sans tout refaire : `npm install` une fois, puis les tests réutilisent l'installation. C'est la boucle de travail réelle de Claude Code.
- ✅ **Coût en baisse** à usage égal : moins de réinstallations, moins de montages de fichiers, moins de tokens de re-contexte.
- ✅ Lisibilité commerciale : la capacité premium est nommée et signalée, au lieu d'être un onglet parmi deux.
- ✅ **Arbitrage commercial tranché le 2026-08-23** : les deux modes sont conservés. Un mode unique avec exécution aurait fait basculer tout l'Atelier sous Gold et privé les non-Gold de l'Atelier. Le mode Assistant reste leur porte d'entrée, et le mode Terminal devient l'argument de conversion, explicitement identifié comme tel.
- ⚠️ **La durée de vie maximale d'une session n'est pas documentée** dans les sources consultées. À vérifier avant implémentation : si une limite existe, prévoir la reprise sur session expirée (ce que le point « reprise transparente » couvre déjà par conception).
- ⚠️ Le resync des fichiers ne peut plus se faire « à la fin de la session » puisqu'il n'y a plus de fin : il devient **incrémental**, après chaque tour.
- ⚠️ Une sandbox longue-vie détenant l'état d'un projet accroît la valeur d'une éventuelle évasion. Atténuation : périmètre inchangé (sandbox Anthropic, `networking: limited`, isolation `user_id`), plus une fin de vie explicite.
- ℹ️ **Aucun code retiré** : la boucle tool-use Phase 1 reste en service pour le mode Assistant.

**Alternatives écartées**

- *Statu quo* : conserve un produit qui ne tient pas la promesse initiale, pour une économie inexistante.
- *Terminal interactif réel* (l'utilisateur tape ses commandes) : l'API Managed Agents ne l'expose pas — les seuls événements client sont `user.message`, `user.interrupt`, `user.tool_confirmation`, `user.custom_tool_result`, `user.define_outcome` ; aucun n'exécute une commande arbitraire. Le construire imposerait d'héberger nos propres conteneurs, ce qui contredit frontalement Gateway-First.
- *Mode unique avec exécution* (envisagé, écarté le 2026-08-23) : ferait basculer tout l'Atelier sous Gold et supprimerait la porte d'entrée des utilisateurs non-Gold.
- *Reprendre le nom « Claude Code » dans l'interface* : marque d'Anthropic ; risque disproportionné pour un libellé.

---

## ADR-015 — Atelier sur dépôt Git, et entrée du MCP dans le périmètre (complète ADR-014)

**Status** : Accepted (2026-08-25) — **complète** ADR-014 sans le contredire : la sandbox et la boucle
d'agent restent celles d'Anthropic. Arbitrages D1/D2/D3 rendus par l'owner le 2026-08-25.

**Context** — L'Atelier travaille sur un `.zip` téléversé. Pour utiliser Claude sur un projet réel, il
faut exporter son dépôt, le téléverser, puis réintégrer à la main les fichiers modifiés. C'est l'écart
le plus visible avec Claude Code, et il disqualifie l'outil pour l'usage qu'il vise.

Ce contournement **contredit Provider-First** (`PROJECT.md` §3.3) : l'API Managed Agents monte
nativement un dépôt GitHub (`resources: [{type: "github_repository", …}]`) depuis le départ, et nous
lui avons substitué un mécanisme plus pauvre. Vérification faite : zéro occurrence dans le code.

**Fait qui lève l'objection de sécurité** — le `authorization_token` **n'entre jamais dans le
sandbox** : un proxy git côté Anthropic l'injecte après que la requête a quitté le conteneur. Le code
exécuté dans le sandbox — y compris ce que l'agent écrit — ne peut ni le lire ni l'exfiltrer, et l'API
ne le renvoie jamais. Ce qui reste vrai : le **contenu** du dépôt transite chez Anthropic, comme celui
des archives aujourd'hui — le périmètre de confiance ne change pas, seul l'acheminement change.

**Decision**

- **Le workspace gagne une source** : `ARCHIVE` (actuel) ou `GIT`. Arborescence, explorateur, mode
  Terminal, session persistante et historique s'appliquent sans modification — seul l'approvisionnement
  des fichiers change (D3).
- **Authentification par PAT fine-grained**, saisi par l'utilisateur et **chiffré au repos** en
  réutilisant le mécanisme BYOK (F-03 : `ByokKeyCipher`, chiffrement enveloppe, affichage masqué). Aucune
  brique cryptographique nouvelle (D2).
- **L'agent va jusqu'à la pull request** (D1) : édition dans le dépôt monté → push d'une branche via le
  proxy git → création de la PR.
- **Le MCP entre donc dans le périmètre**, pour ce seul usage. ADR-014 écartait « les commandes slash,
  les plugins et les skills natifs de Claude Code » ; MCP n'y figurait pas explicitement, mais son
  arrivée est une extension de périmètre et doit être tracée. **Les skills et les commandes slash
  restent hors périmètre.**
- **Inchangé** : Gateway-First (on relaie, on ne construit pas), abstraction `AgentProvider`, isolation
  `user_id`, `networking: limited`, plafonds et surcompteur sandbox.

**Consequences**

- ✅ L'Atelier devient utilisable sur un projet réel : plus d'export/réimport manuel.
- ✅ Le plafond de **300 fichiers** par session (`maxSessionFiles`) disparaît pour les workspaces Git —
  un clone remplace N téléversements. Les projets volumineux deviennent traitables.
- ✅ Coût neutre à favorable : moins de téléversements, moins de tokens de re-contexte.
- ✅ **Incertitude sur la PR : LEVÉE le 2026-08-25.** Vérification empirique avec un PAT réel : le
  serveur MCP GitHub répond **200** à `initialize` avec le PAT en `Authorization: Bearer`, et expose
  44 outils dont `create_pull_request`. L'avertissement de la documentation vaut pour d'autres services,
  pas pour GitHub. **D2 reste sur le PAT**, aucune bascule GitHub App nécessaire, SF-31-05 est
  débloquée. Détail dans OQ-11.
- ✅ **SF-31-05 livrée le 2026-08-26** (PR #159 backend, #161 écran, #162 suppression de compte), ce
  qui **clôt F-31**. Le vault de credentials est **par utilisateur** (le fournisseur n'accepte qu'une
  credential par `mcp_server_url` et par vault, et mélanger les jetons violerait l'isolation
  `user_id`), **créé paresseusement** à la première session Git et **détruit à la révocation** du
  jeton — remplacement, retrait, ou suppression du compte (F-11). Son secret n'entre pas plus dans le
  conteneur que le jeton du proxy git : un proxy MCP côté fournisseur l'injecte en sortie de sandbox.
  Migration `045`, sans aucun secret en base (identifiants opaques seuls).
- ℹ️ **Contrainte assumée** : `vault_ids` n'est accepté qu'à la **création** d'une session. Une session
  ouverte avant SF-31-05 n'a donc pas l'outil ; « Réinitialiser la sandbox » en rouvre une équipée. On
  ne bascule pas de session en douce — l'utilisateur perdrait son contexte de travail.
- ⚠️ Une session détient désormais un accès en écriture à un dépôt réel. Atténuations : jeton hors
  sandbox (proxy), portée du PAT choisie par l'utilisateur, push sur **branche dédiée** — jamais sur la
  branche par défaut.
- ✅ Les trois questions laissées aux subfeatures sont traitées : **pas de `git pull` automatique** (il
  écraserait le travail en cours — le clone se rafraîchit en réinitialisant la sandbox), le **workspace
  survit** au retrait du jeton (c'est le montage qui échoue), et la **taille du dépôt** est bornée par
  la sandbox du fournisseur. Détail dans `docs/features/F-31/CADRAGE.md`.

**Alternatives écartées**

- *Statu quo (archive `.zip`)* : conserve un produit inutilisable sur un projet réel, en contradiction
  avec Provider-First.
- *GitHub App / OAuth en première version* : meilleure expérience, mais chantier entier (enregistrement,
  callback, rafraîchissement, installations) qui retarderait la première version utilisable. Reste la
  cible si la friction du PAT le justifie — ou si le risque MCP l'impose.
- *Entité « projet Git » séparée du workspace* : dupliquerait des écrans entiers pour une différence qui
  ne concerne que la provenance des fichiers.
- *S'arrêter au push de branche* : envisagé et recommandé au cadrage, **écarté par l'owner** au profit
  de la PR complète.

## ADR-016 — Runner d'exécution sur machine connectée (F-38)

**Statut** : accepté (2026-08-29). **Contexte** : l'Atelier (F-28 Phase 2) exécute les outils dans le
sandbox d'Anthropic, aveugle à un projet/cluster/environnement qui vit sur une machine précise.
D'où le double transbordement manuel (zip aller/retour + copier-coller des sorties) quand on
travaille depuis le navigateur sur un projet distant.

**Décision** :
- **Runner en connexion sortante** (option A) : un `.jar` posé sur la machine ouvre lui-même une
  connexion **sortante** WSS/443 vers la gateway (aucun port entrant). La gateway route les appels
  d'outils de la **boucle tool-use maison** (SF-28-02) vers le runner au lieu du sandbox — les
  Managed Agents exécutant chez Anthropic ne sont pas rerouteables. Écartée : l'option B (File System
  Access API du navigateur), qui réglait les fichiers mais jamais l'exécution.
- **RunnerRegistry derrière une interface** (`InMemory` dev/tests, `PgNotify` prod) : le backend
  tourne à 2 replicas ; runner (WSS) et navigateur (SSE) atterrissent sur des pods différents.
  Postgres `LISTEN`/`NOTIFY` évite d'introduire Redis sans perdre la HA. Réversible.
- **Chaîne de sécurité Spring dédiée** `@Order(1)` `securityMatcher("/runner/**")` : le jeton runner
  (second type de porteur d'identité, par jeton et non par JWT) ne peut jamais authentifier un
  endpoint utilisateur ; la chaîne principale reste inchangée. Secrets (code d'appairage, jeton)
  stockés hachés (SHA-256), jamais en clair.

**Conséquences** : la validation d'action (F-33) devient obligatoire en mode runner ; exclusions
`.runnerignore` côté runner (les secrets ne quittent pas la machine) ; audit des lectures et des
commandes. Livraison SF-38-01→10 ; SF-38-01 pose l'identité (appairage + jetons).
