# Cadrage F-148 — Performance du raisonnement (affinages)

> Né de l'audit `docs/audits/AUDIT-2026-09-23-raisonnement-tours-et-contexte.md` (PO 2026-09-23 :
> *« résultats justes, mais beaucoup de tours »*). **Complète** F-134 (cache) et F-135→140 (le savoir
> entre dans le contexte), **déjà livrées** — F-148 n'invente pas un moteur, il **affine** la boucle
> pour réduire le nombre de tours / la taille de contexte.
>
> **Deux règles cadre :** (1) **aucun changement d'infra** — tout est prompt/contexte/config dans le
> backend existant, **aucun nouveau composant cluster** (`legalcase-shared` à capacité) ; (2) **honnêteté
> sur l'existant** — chaque SF dit si elle **étend** une feature livrée ou est net-neuf. On ne re-cadre
> pas F-136/137/138.
>
> **Ce qu'on ne touche pas :** la discipline « lire avant d'agir » (F-119) — c'est elle qui rend les
> résultats justes. On rend chaque lecture **moins chère / précalculée**, on ne la supprime pas.
>
> **9 subfeatures.** Le levier « embeddings/RAG » est **retiré du découpage**, en **réserve** : son
> **déclencheur n'est pas un compteur de faits mais une preuve mesurée** que le rappel par mots-clés rate
> (voir §Hors périmètre). Le seuil « ~20 000 faits » de l'audit 09-21 n'était qu'un ordre de grandeur.

## Découpage — 9 leviers

Classés en 3 familles. Chaque SF : *constat · existant · reliquat · mécanisme · effet attendu · effort ·
infra · garde-fous.*

### Famille A — Gratuit / hygiène (config ou prompt, aucun stockage)

**SF-148-01 — `stepEffort` low → medium (mesuré)** *(net-neuf, réglage)*
- Constat : effort de continuation `low` (`AtelierProperties.java:172`, `reasoningForIteration:1074`) →
  sous-raisonnement possible → auto-corrections → tours de rattrapage (le garde-fou F-119 réagit *après*).
- Mécanisme : passer le défaut à `medium` via `app.atelier.step-effort`, **mesurer** (itérations/tour,
  `reusedPercent`) avant/après. Inclut l'essai `maxDelegations` 3→5 pour investigations multi-zones.
- Effet : moins d'allers-retours de rattrapage. Effort **XS**. Infra **nulle** (coût tokens en légère
  hausse, assumé). Garde-fou : réversible, décidé sur mesure, pas au doigt mouillé.

**SF-148-02 — Un profil métier actif *cadre* le raisonnement (et on le teste enfin)** *(étend F-138)*
- Constat : F-138 a livré 4 profils, mais l'amorce « Tu es un assistant de développement »
  (`AtelierChatService.java:4251`) **reste la 1re phrase** même en profil infra/sécu → effet dilué ; et
  **aucun profil n'a été activé/testé** en prod (le PO l'a dit).
- Mécanisme : quand un profil est actif, il **remplace l'amorce de rôle** (pas seulement s'y ajoute) ;
  activer un profil sur CAGIP et **mesurer** l'effet sur les tours de cadrage.
- Effet : le bon cadre dès la 1re phrase → moins d'itérations de cadrage. Effort **S**. Infra **nulle**.
- Garde-fou : un profil **ne desserre jamais** une règle de plateforme ni la discipline d'investigation
  (garde-fou F-138 maintenu) ; **décision produit** (les profils sont un choix explicite) — à valider.

**SF-148-03 — Alléger le catalogue de skills annoncé** *(net-neuf, latence)*
- Constat : jusqu'à **50 skills** relus par message (`MAX_SKILLS_ANNOUNCED=50`, `:405`, `:4393-4416`),
  round-trips runner synchrones **avant le 1er token**.
- Mécanisme : plafond ~15 les plus pertinents (ou annonce compacte). Effet : préfixe plus court/stable +
  démarrage plus rapide (**latence perçue**, pas tours). Effort **S**. Infra **nulle**.

**SF-148-04 — Renforcer le groupement des `explore` indépendants** *(étend SF-39-22)*
- Constat : l'exécution parallèle existe (`exploreConcurrently:1659`) mais dépend du modèle qui groupe.
- Mécanisme : durcir la consigne de groupement (`:4042-4045`). Effet : recouvre le temps de mur des
  explorations (ne réduit pas les tours logiques). Effort **XS**. Infra **nulle**.

### Famille B — Stocker / précalculer plus (le PO y est ouvert ; DB existante, pas d'infra)

**SF-148-05 — Injecter le contenu borné de `STATE.md`/`PLAN-ACTION.md` du sujet courant** *(étend F-136)*
- Constat : `HostMapOutline` n'injecte que des **titres** (`HostMapOutline.java:44-78`) ; le contenu est
  **déjà en base** (`HostMapStore`). L'agent rouvre encore ces fichiers.
- Mécanisme : un bloc « état courant » **borné** dans le préfixe, **ré-injecté seulement au changement de
  digest** (discipline `HostMapOutline`, sinon casse le cache). Vérifier d'abord ce que F-137 couvre déjà
  pour ne pas doublonner.
- Effet : reprise du fil sans tour `read_file` d'amorçage. Effort **S-M**. Infra **nulle** (texte déjà en
  base). Garde-fou : bornage strict (`SYSTEM_MAX_CHARS=40 000`), stabilité du cache.

**SF-148-06 — Cache gateway de `CLAUDE.md` + catalogue de skills par (host, digest)** *(net-neuf, latence)*
- Constat : `readOptional`/`safeTree` lisent du runner à chaque message (`:4370`, `:4389`, `:4518-4543`).
- Mécanisme : copie DB throttlée sur le modèle **exact** de `HostMapStore` (digest SHA-256 + refresh
  async). Effet : supprime jusqu'à ~50 round-trips runner/message (latence). Effort **M**. Infra **nulle**.

**SF-148-07 — Index de repo persistant (chemins + symboles)** *(net-neuf)*
- Constat : `grep`/`glob` sur le runner à chaque appel (`:4181-4203`) → itérations « trouver le fichier ».
- Mécanisme : index côté gateway, rafraîchi après tour ; servir la recherche depuis la DB. Effet : moins
  d'itérations de localisation. Effort **M-L**. Infra **nulle** (DB existante).

**SF-148-08 — Mémoire de résolutions (question → conclusion/fichiers) par poste** *(net-neuf)*
- Constat : rien ne réutilise un problème déjà tranché → on re-raisonne.
- Mécanisme : stocker les paires « question → conclusion » et les proposer sur question similaire,
  réinjectées comme F-137. Effet : évite de re-raisonner. Effort **L**. Infra **nulle** (DB).
- Statut : **plus tard**, après SF-148-05.

### Famille C — Défaut mesuré à corriger

**SF-148-09 — Root-cause + correction des 25 % d'échec de `governance_map_read`** *(net-neuf, défaut)*
- Constat : 214/839 lectures échouent (gateway-side, juge/intégrité/écran).
- Mécanisme : diagnostiquer via `runner_audit.error_code` (hypothèse à falsifier : lecture de
  sections/fichiers absents ? course de refresh `HostMapStore` ?), puis corriger. Effet : supprime des
  lectures gâchées + du bruit. Effort **S** (diagnostic d'abord). Infra **nulle**.

## Séquencement recommandé
1. **SF-148-09** (défaut mesuré) + **SF-148-01** (réglage mesuré) + **SF-148-04** — quasi gratuits.
2. **SF-148-02** (profil qui cadre + **test réel** des profils métier) — répond directement au « pas testé ».
3. **SF-148-05** (contenu STATE/PLAN injecté) — le gain de contexte le plus net après F-136.
4. **SF-148-03 / 06** — latence de démarrage.
5. **SF-148-07 / 08** — plus tard, selon mesure.

## Hors périmètre (dont le levier retiré)
- **Embeddings / recherche vectorielle sur la carte (F-137 v2)** — **en réserve**, pas dans ce découpage.
  Rappel `HostFactLookup` keyword-only (`HostFactLookup.java:81-100`). **Déclencheur = preuve mesurée, pas
  un compteur de faits** : on l'ouvre quand on **mesure** que le rappel rate — c.-à-d. que l'agent
  **ré-explore un fait pourtant présent** dans la carte — et **non** à « ~20 000 faits » (ce nombre de
  l'audit 09-21 n'était qu'un ordre de grandeur). **La mesure existe déjà en partie** : F-140 suit le
  **nombre d'appels d'exploration par tour** ; s'il **ne baisse pas** à mesure que la carte grossit, c'est
  le signal que le keyword ne suffit plus → on avance les embeddings, quel que soit le nombre de faits.
  Complément possible (petite instrumentation) : compter les questions où un fait **existant** n'a pas été
  rappelé. Le jour venu, ça vit dans le **Postgres existant** (pgvector, ADR-011) — donc **sans infra**.
- Moteur de raisonnement maison (Provider-First) ; refonte sur Claude Agent SDK (décision 2026-09-15) ;
  sous-agents *écrivains* parallèles (impossible proprement sur poste unique) ; tout composant cluster.

## Préoccupations transversales
- **Cache de prompt** : toute SF touchant le préfixe (02, 03, 05, 06) doit **préserver la stabilité**
  (ré-injection seulement au changement de digest) — sinon on annule le gain F-134. Composant :
  `buildSystemPrompt` (`AtelierChatService.java:4242`).
- **Isolation** `user_id`+`host_id` : inchangée sur toute lecture/stockage.
- **Plans/limites** : `stepEffort`, `maxDelegations` → coût ; à mesurer, pas à supposer.
