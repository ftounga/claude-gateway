# Cadrage F-195 — Plusieurs sujets à la fois

**Demande PO (2026-10-12)** : « si je lui donne plusieurs sujets, je m'attends à voir plusieurs tâches qui se déclenchent ; il doit paralléliser quand c'est parallélisable » ; et un 2e message envoyé pendant un tour ne doit pas faire abandonner le 1er — comme Claude Code, qui traite les deux (en parallèle s'ils sont indépendants, sinon l'un après l'autre).
**Livraison** : autonome, tout livrer (PO). **Règle absolue** : justesse avant coût — rien ne doit dégrader le raisonnement.

## Constat (mesuré le 2026-10-12, `origin/main` 3736a58f)
- Prod, 14 jours : **14 appels d'outils sur 5 074** se sont chevauchés dans le temps. Le parallélisme n'existe pas en pratique.
- Test PO (3 suites cochées via Cap, 19:02) : bash l'un après l'autre, aucune délégation.
- Causes :
  1. Cap (SF-192-04) compose « Enchaîne ces suites, **dans l'ordre** » ⇒ consigne séquentielle.
  2. Aucune consigne « un sous-agent par sujet indépendant » ; « Choix des outils » pousse à « agir TOI-MÊME » (`AtelierChatService` l.446). Le texte « copies de toi-même en parallèle » n'existe que côté Managed Agents (`AgentSystemPrompt.java:49`).
  3. La consigne promet « GROUPE les appels indépendants : ils partent ensemble » (l.426) mais le moteur n'exécute en parallèle que les délégations en lecture (`readDelegationsConcurrently`, pool de 3) ; tout le reste est un `for` séquentiel (l.3133).
  4. `task` écrivain est sérialisé (l.3210) alors que chacun a son worktree isolé.
  5. **Le runner n'accepte qu'UNE commande bash à la fois** (`runner/.../BashTool.java:150`, sémaphore `slot`, `BUSY_MESSAGE`, attente gateway F-188-02). Sans lever cette borne, aucun parallélisme côté gateway ne sert (95 % du travail = bash).
  6. Interjection : arrive en `[Interjection de l'utilisateur — …]` collée aux `tool_result` **sans aucune consigne** ⇒ le modèle la lit comme la nouvelle consigne. `SYNTHESIS_PROMPT` (l.238) dit « réponds à **la dernière demande** ». Budgets (10 min, 30 étapes, 5 délégations) partagés entre les sujets.

## Décisions (PO, 2026-10-12)
- Tout est actif par défaut ; bornes configurables avec coupe-circuit.
- Comportement cible = Claude Code : sujets indépendants ⇒ en parallèle ; dépendants ⇒ l'un après l'autre ; un message en cours de tour **s'ajoute**, il ne remplace pas ; la réponse finale couvre toutes les demandes.

## Découpage
| SF | Objet | Risque |
|---|---|---|
| **SF-195-01** | **Consignes** (préfixe stable, rien de volatil) : (a) doctrine « plusieurs sujets » : décomposer la demande ; sujets indépendants ⇒ une délégation par sujet émise dans le MÊME tour (explore / task), puis synthèse commune qui rend compte de chaque sujet ; dépendants ⇒ dans l'ordre. (b) doctrine « interjection » : un message reçu en cours de tour s'AJOUTE ; poursuivre la demande en cours ; sujet indépendant ⇒ le déléguer en parallèle, sinon le traiter après ; la réponse finale couvre toutes les demandes ; seule une consigne explicite (« arrête », « laisse tomber », « à la place ») remplace. (c) `SYNTHESIS_PROMPT` : « réponds à TOUTES les demandes de ce tour (la première comme les précisions) ». (d) ligne « ils partent ensemble » rendue vraie (alignée sur SF-195-03). (e) « Choix des outils » `task` : ne plus décourager la délégation quand il y a plusieurs sujets. (f) Cap : texte composé « Traite ces suites — en parallèle celles qui sont indépendantes : 1) … ; 2) … » (frontend). Tests : présence des doctrines dans le bloc stable, stabilité du préfixe, Cap. | 🟢 |
| **SF-195-02** | **Runner : plusieurs commandes à la fois.** Le sémaphore `slot` passe de 1 à N (défaut 4, réglable côté runner), `BUSY_MESSAGE` inchangé au-delà de N (contrat F-188 préservé, test de contrat) ; sorties diffusées par `call_id` sans mélange ; annonce de la capacité (ex. `bash_concurrency`) pour que la gateway sache qu'un vieux runner reste à 1. Version runner incrémentée (mise à jour d'un clic). | 🟠 |
| **SF-195-03** | **Moteur : exécuter réellement en parallèle les appels groupés** d'une même réponse quand ils sont sûrs en concurrence — modèle Claude Code : outils en lecture (read_file, grep, glob, governance_map_*, screen_list_*, web…) et `bash` dont la commande est classée lecture seule par le classificateur existant (à réutiliser, sinon règle prudente) ; tout appel qui écrit reste séquentiel et sert de barrière ; ordre des `tool_result` conservé ; borne = capacité annoncée du poste (1 pour un vieux runner) ; journal/F-191 inchangés. | 🟠 |
| **SF-195-04** | **Sous-agents écrivains en parallèle** : plusieurs `task` écrivains d'une même réponse tournent ensemble (chacun son worktree), borne `max-concurrent` (défaut 3) ; intégration des résultats (synthèses / branches) dans l'ordre d'émission, conflit ⇒ signalé dans la synthèse, jamais écrasé. Délégations par message : la limite de 5 devient par demande (première + chaque interjection) ou relevée, au choix du dev justifié. | 🟠 |
| **SF-195-05** | **Budget par sujet** : une interjection acceptée prolonge le budget du tour (temps et étapes, montants configurables, plafond absolu conservé) au lieu de consommer celui du premier sujet ; la cause d'arrêt dit quel sujet n'a pas abouti. Le plafond de dépense (quota) reste inchangé. | 🟠 |

Ordre : 01 → 02 → 03 → 04 → 05 (02 avant 03 : la gateway lit la capacité annoncée).

## Critère de réussite (mesure prod après déploiement)
- Une demande à 3 sujets indépendants produit des appels qui se chevauchent (`runner_audit`, intervalles [début, fin]) ; un 2e message en cours de tour reçoit une réponse sans que le 1er sujet disparaisse de la synthèse.

## Hors périmètre
- Interface nouvelle (F-191 montre déjà tours et sous-agents).
- Exécution multi-postes ; Managed Agents (déjà sa propre consigne).
