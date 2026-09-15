# F-119 — La justesse de l'agent : se tromper moins, se corriger moins

> Cadrage du 2026-09-15, à la demande du PO : *« Il se trompe très souvent… il dit "je me suis
> trompé" et corrige. Pourquoi se trompe-t-il autant en investigation/correction contrairement à
> Claude Code ? Fais un audit approfondi et cadre la solution. »*
> **Cadrage seul : la livraison attend le go du PO.**

## 0. Méthode

Audit approfondi, cinq angles en parallèle, en lecture seule : (A) configuration de raisonnement,
(B) fidélité des sorties d'outils, (C) gestion du contexte/mémoire, (D) prompt système et contrats
d'outils, (E) **preuve empirique** sur les vrais tours de production de CAGIP (`atelier_messages` +
`runner_audit`). Chaque constat est référencé `fichier:ligne` ou tiré des données de prod.

**Le résultat le plus important : ce n'est PAS le profil d'erreur « mécanique » qu'on imagine.**
Sur les 18 vraies auto-corrections analysées en prod (poste CAGIP, macOS, 15 jours) :

| Cause d'origine de l'erreur | Part | Ce que c'est |
|---|---|---|
| **(f) A affirmé / généralisé sans vérifier** | **39 %** | conclut, puis relit la source et se dédit ; « déduit d'un seul exemple » |
| **(g) A mal interprété une sortie / une erreur d'outil** | **22 %** | un outil dit « rien observé » à tort, l'agent le croit 5 tours |
| **(h) Autre** (sa propre sortie mal formée, malentendu) | **22 %** | marqueurs de fin mal ponctués, question mal comprise |
| **(d) A raisonné sur une sortie tronquée** | **11 %** | transcription coupée → procédure fausse |
| **(e) A oublié un résultat déjà obtenu** | **6 %** | re-cherche ce qui était déjà rangé |
| (a) chemin faux / (b) mauvais shell / (c) edit_file raté | **~0 %** | quasi absents |

Signal objectif (`runner_audit`, 1515 appels) : **22 % des appels d'outils échouent**, dont **~17 %
pour cause de runner distant instable** (timeout/indisponible derrière le proxy NTLM de CAGIP), et
**0** échec `edit_file` de type « texte introuvable ». Les contrats d'édition, de chemin et de shell
sont **sains** — l'audit le confirme (points ci-dessous). **Notre problème n'est pas la mécanique des
outils : c'est le jugement et la mémoire.**

> **Limite honnête** : l'échantillon empirique est un power-user unique, macOS, sur des tâches
> d'investigation/cartographie. Il caractérise *ce* poste, pas une tendance produit chiffrée. Mais les
> causes de code ci-dessous sont **structurelles** et s'appliquent quel que soit l'usage.

## 1. Les causes racines, classées

### Cause 1 — MAJEURE : le raisonnement tombe à `low` sur *tous* les tours de continuation
- **Constat** (angle A, `AtelierChatService.java:563-568`, F-118/SF-118-01) : effort `high` au premier
  tour, puis **`low` dès la 2ᵉ itération et pour toujours** (`adaptive-effort=true` par défaut,
  `application.yml:169`). Aucune ré-escalade — la mini-spec F-118 l'excluait volontairement (D-118-2).
- **Pourquoi c'est le cœur du problème** : l'hypothèse de F-118 (« 1ᵉʳ tour = réfléchir, tours suivants
  = exécuter une trajectoire tracée ») est **fausse pour l'investigation/correction**. C'est justement
  *après* chaque résultat d'outil — donc sur les tours de continuation — qu'il faut réfléchir le plus
  pour interpréter ce qu'on vient de voir. Tout ce raisonnement tourne à `low`. Cela explique
  directement (f) 39 % et (g) 22 % de la prod : jugement superficiel → affirmation → erreur vue au
  tour suivant (encore `low`) → correction → re-erreur.
- **Aggravant** : l'outil `explore` investigue avec **zéro raisonnement** (`AgentReasoning.none()`,
  `AtelierExploration.java:87`).
- **Écart Claude Code** : raisonne à effort plein *après chaque résultat d'outil*.
- **Nuance à ne pas oublier** : F-118 a baissé l'effort pour la **vitesse/coût** (autre plainte du PO).
  La réponse n'est donc PAS « remettre `high` partout » (régression de vitesse), mais **ré-escalader
  sur signal** — exactement l'heuristique fine que F-118 avait écartée.

### Cause 2 — MAJEURE : le prompt système n'a aucune discipline d'investigation
- **Constat** (angle D, `AtelierChatService.java:2374-2482`) : le prompt ne porte **qu'une** consigne
  d'investigation (« ne suppose rien sur un fichier sans l'avoir lu »). Aucune trace de : *vérifier /
  tester avant d'affirmer*, *prouver avant de conclure*, *ne pas généraliser d'un seul cas*, *corriger
  tôt*, *chemins*. C'est un prompt **descriptif**, pas **disciplinaire**.
- **Pourquoi** : rien ne pousse l'agent à se vérifier avant d'affirmer → il improvise et se rattrape
  après coup. C'est le miroir exact de (f) 39 % (« déduit d'un seul exemple », « c'était faux »).
- **Contrat `edit_file`** (`:2329-2331`) incomplet : ne dit pas « copier `old_string` exactement,
  indentation comprise ; lire d'abord ». Peu impactant *ici* (0 échec en prod) mais à durcir.

### Cause 3 — MAJEURE : l'agent perd ses preuves plus vite que ses affirmations
- **Constat** (angles C + B) : trois mécanismes cumulés —
  1. **Traces d'outils jetées au-delà de 5 tours** (`REPLAYED_TRACE_TURNS=5`,
     `AtelierChatService.java:162,1138-1165`) : au 6ᵉ tour, le contenu de fichier / la sortie de test
     lus plus tôt disparaissent.
  2. **Rejeu asymétrique** (`:1125-1148`) : on garde **tout son texte** (« le test passe ») mais plus
     les `tool_result` qui le prouvaient → il **croit ses vieilles affirmations sans la donnée pour les
     réviser** → contradictions.
  3. **Compaction sur le texte seul** (`AtelierCompactionService.java:200-232`) : le résumé exclut par
     conception les sorties d'outils → raisonnement sur un digest imprécis, valeurs réinventées.
  - **Incohérence tête/queue** (angle B) : en direct l'agent voit le **début** d'une sortie ; au rejeu,
    seulement la **fin** (4 000 car., `AtelierToolTrace.java:29-66`). Sa mémoire d'un même résultat
    bascule → conclusions incohérentes.
- **Pourquoi** : explique (e), (d), et le pattern « affirme puis se contredit ». La prod le montre en
  creux : l'agent externalise tout dans des fichiers et les relit des dizaines de fois
  (`PLAN-ACTION.md` relu 46×) — c'est sa mémoire de secours faute de mémoire d'outils durable.
- **Écart Claude Code** : garde le **couplage affirmation↔preuve** et un **état de fichiers** explicite.

### Cause 4 — CONTRIBUTIVE : le bruit d'environnement est mal encaissé
- **Constat** : ~17 % des appels échouent (runner CAGIP instable derrière proxy) ; et un **bash en
  erreur ou timeout jette sa sortie partielle** (`AtelierChatService.java:2055-2056`) — l'agent ne voit
  que « le runner n'a pas répondu », alors qu'il y avait des diagnostics.
- **Pourquoi** : il conclut sur une info partielle (première conclusion), puis se rétracte quand l'outil
  remarche (cas prod corp 10:56). Un « échec/indisponible » est trop souvent lu comme un « négatif »
  au lieu d'un « non concluant ». Explique une part de (g)/(d).

### Cause 5 — CONTRIBUTIVE : aucun suivi d'état de fichier
- **Constat** (angle C) : pas d'obligation de lecture récente avant édition côté **modèle**, pas de
  signal « le fichier a changé depuis ta dernière lecture ». Le disque évite la corruption (relecture à
  l'édition), pas l'**erreur de raisonnement**.
- **Priorité moindre** : 0 échec `edit_file` en prod ; c'est surtout une aide mémoire.

### Ce qui N'EST PAS en cause (à ne pas « corriger »)
- Le **modèle** : `claude-opus-5` partout, aucun downgrade (angle A).
- Le **cache de prompt** : ne retire rien du contexte (angle A + C).
- La **fidélité intra-tour** : sorties complètes, numéros de ligne, troncatures marquées, appariement
  `tool_use`/`tool_result` strict et séquentiel (angle B).
- Les **contrats edit_file / chemins / shell** : sains et bien gardés (SF-39-20 explore, SF-38-27
  shell) (angles B + D).

## 2. Le découpage proposé (sous-features)

| SF | Titre | Cible | Cause | Priorité |
|---|---|---|---|---|
| **SF-119-01** | **Effort de raisonnement soutenu là où on investigue** | ré-escalade l'effort sur signal (résultat d'outil en erreur, test/commande en échec, edit raté, auto-contradiction détectée) au lieu de rester à `low` ; l'outil `explore` reçoit un raisonnement adaptatif ; lever le bridage `xhigh` périmé (streaming livré). **Garde la vitesse** : effort bas par défaut sur une trajectoire qui roule, plein dès qu'un signal de difficulté apparaît. | Cause 1 | **1** |
| **SF-119-02** | **Discipline d'investigation dans le prompt** | consignes non négociables : vérifier/tester avant d'affirmer, prouver avant de conclure, ne jamais généraliser d'un seul exemple, corriger tôt ; contrat `edit_file` complété (copier exact, lire d'abord). | Cause 2 | **1** |
| **SF-119-03** | **La preuve survit à l'affirmation** | coupler affirmation↔preuve : élargir/repenser la fenêtre de traces (au-delà de 5 tours pour les résultats *cités*), rejeu cohérent (même bout tête/queue qu'en direct, moins agressif que 4 000 car.), inclure un digest structuré des résultats d'outils (pas seulement le texte) dans la compaction. | Cause 3 | **1** |
| **SF-119-04** | **Encaisser le bruit sans conclure faux** | bash en erreur/timeout rend sa **sortie partielle** + la note ; un échec d'outil (runner indisponible/timeout) est présenté comme **« non concluant »**, pas comme un résultat négatif, pour couper les conclusions hâtives. | Cause 4 | 2 |
| **SF-119-05** | **Suivi d'état de fichier** | signal au modèle « lu récemment / modifié depuis ta lecture » ; incitation lecture-avant-édition côté modèle. | Cause 5 | 3 |

**Ordre** : SF-119-01 et SF-119-02 d'abord (leviers majeurs, faible risque, effet immédiat sur (f)+(g)
= 61 % des cas). Puis SF-119-03 (plus profond, touche contexte/compaction). Puis SF-119-04, SF-119-05.

## 3. Levier immédiat, sans livraison (diagnostic + soulagement)
`APP_ATELIER_ADAPTIVE_EFFORT=false` (ou `APP_ATELIER_STEP_EFFORT=high`/`xhigh`) rétablit l'effort plein
sur les continuations **par simple config** (`AtelierChatService.java:506`). Deux usages : (1) **tester**
si l'effort est bien la cause (le PO juge la qualité avant/après), (2) **soulager** tout de suite en
attendant SF-119-01. Coût : plus de jetons et un peu plus de latence sur chaque tour — c'est le
compromis exact que SF-119-01 rendra intelligent (plein *seulement* quand c'est utile).

## 4. Hors périmètre
- Refondre le modèle ou le cache (hors de cause).
- Toucher aux contrats edit_file/chemins/shell au-delà d'un complément de description (sains).
- Le bug de reconnaissance Teams (SF-89-12/13) : distinct, déjà en cours ; il alimente (g) mais n'est
  qu'un cas particulier.

## 5. Préoccupations transversales
- **Auth / tenant / navigation** : non (boucle d'agent, aucun endpoint ni route nouveaux).
- **Plans / limites** : SF-119-01 touche la consommation de jetons (effort) → à surveiller vs quotas et
  vs les plafonds par tour (F-118) ; c'est précisément l'arbitrage vitesse/justesse à trancher.
- **Composants** : `AtelierChatService` (effort, prompt, rejeu), `AnthropicAgentProvider` (thinking),
  `AtelierExploration` (effort explore), `AtelierToolTrace` (rejeu tête/queue), `AtelierCompactionService`
  (digest d'outils), `AtelierProperties`/`application.yml` (réglages).
