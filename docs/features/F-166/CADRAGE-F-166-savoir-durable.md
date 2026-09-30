# Cadrage F-166 — Savoir durable

> Cadrage PO le 2026-09-30. Ce document trace la décision produit ; l'implémentation vit
> dans le prompt système (doctrine active), la gouvernance ne fait que la consigner.

---

## 1. Intention (décidée par le PO le 2026-09-30)

Doter le terminal d'une **doctrine de savoir durable** : sur un travail substantiel dans un
repo / un sujet, l'agent **propose et entretient des artefacts de connaissance persistants** —
`REPO-MAP.md` (structure du dépôt) et `STATE.md` (état courant, décisions, conclusions) — au lieu
de **re-scanner et re-dériver** la même chose à chaque tour.

Le bénéfice est **double** :

- **Coût** : moins de relecture / re-exploration à chaque tour (cf. le sujet gitlab-tfstate, où le
  même repo était redécouvert tour après tour). Le coût d'un tour croît en N² sans cache ; ré-explorer
  à vide est exactement le genre de dépense évitable.
- **Justesse** : un raisonnement stable, ancré sur un état écrit et daté, plutôt que redérivé de
  mémoire à chaque fois.

## 2. Placement

**Doctrine produit dans le prompt système** (`AtelierChatService.buildSystemPrompt`), **active** pour
tout usage dans le périmètre (voir §5). C'est exactement le modèle des doctrines récemment livrées —
`SUBJECT_ROUTING_DOCTRINE`, `SUBJECT_HANDOFF_DOCTRINE` (SF-141), `ASK_QUESTION_DOCTRINE` (F-164),
`DESTINATION_ANNOUNCE_DOCTRINE` (SF-141-01) : un **littéral stable** injecté dans le préfixe, scopé
au périmètre pertinent. La gouvernance (ce document, PRODUCT_SPEC) ne fait que **tracer** la décision.

## 3. Comportement nominal

- L'agent **LIT l'artefact existant d'abord** (`REPO-MAP.md` / `STATE.md`) au lieu de re-scanner le
  dépôt pour s'orienter.
- Si l'artefact **n'existe pas**, l'agent **PROPOSE** d'en créer un aux **moments clés** — première
  exploration substantielle, décision importante, avant une passation — **jamais en douce à chaque
  tour** (anti-spam, cohérent avec la carte silencieuse F-125 et la retenue F-120).
- La **construction est BORNÉE** : `git ls-files` (ou l'équivalent d'inventaire) + les points
  d'entrée, **pas** une relecture complète du dépôt. Une carte se dresse d'un survol, pas d'un audit.
- L'artefact porte une **ligne « à revérifier avant de s'y fier »** (carte datée, pointeur, pas source
  de vérité gelée).

## 4. Garde-fous — RÈGLE ABSOLUE PO : justesse avant coût

- La carte est un **POINTEUR À REVÉRIFIER**, **jamais un substitut** à la lecture du fichier réel
  quand la précision compte (nom exact d'un symbole, écriture de code, signature). On lit la carte
  pour s'**orienter** ; on ouvre le fichier pour **agir**.
- La carte est **rafraîchie quand le repo bouge** — une carte périmée qu'on croit fraîche est pire que
  pas de carte.
- La doctrine est **STRICTEMENT ADDITIVE** : elle **ne retire rien** du contexte utile, ne réduit
  aucun autre comportement, ne remplace aucune lecture nécessaire. Elle ne peut pas dégrader le
  raisonnement (règle absolue PO 2026-09-30 : aucune feature ne diminue la justesse).

## 5. Périmètre

**Dans le périmètre :**

- Doctrine système-prompt injectée sur le **poste** — terminal du poste (racine) **et** terminaux de
  sujet (projet) —, c'est-à-dire les workspaces `isRunnerTarget()` (« host + sujet »). C'est là que
  vivent des dépôts réels sur lesquels un `REPO-MAP.md`/`STATE.md` a du sens.
- Réutilise l'**outil d'écriture de fichier DÉJÀ existant** de l'agent (`write_file`) — aucun nouvel
  outil.
- **Littéral stable** (cache F-134 préservé), scope host + sujet.

**Hors périmètre :**

- **Aucune table, aucun endpoint, aucune migration.**
- **Pas d'index persistant en base ni d'embeddings dédiés** : le sémantique existe déjà (F-162 recall).
  Gateway-First / Provider-First : ne pas réimplémenter un moteur de connaissance.
- **Pas d'automatisation qui écrit sans proposer** : l'agent propose, il n'écrit pas les artefacts en
  silence.
- Workspaces **hébergés (SANDBOX)** et **terminaux Teams** : hors scope (pas de dépôt-poste ; préfixe
  plus court = cache préservé).

## 6. Découpage

- **SF-166-01 — La doctrine « savoir durable »** (À LIVRER MAINTENANT) : le littéral de doctrine et son
  injection scopée dans `buildSystemPrompt`, plus le test qui verrouille sa présence dans le périmètre
  et son absence hors périmètre, sans casser la stabilité du préfixe (cache F-134).
- **SF-166-02 — Déclencheur léger après N lectures sans artefact** (RÉSERVE, différé) : un signal
  conversationnel qui, après N re-scans du même repo sans artefact, rappelle la proposition. Non
  décidé, non chiffré ; reste en réserve.

## 7. Cohérence architecturale

- **Gateway-First** respecté : aucune logique de « moteur IA », uniquement une consigne de conduite.
- **Provider-First / Provider Independence** respectés : la doctrine passe par le prompt système
  construit pour l'`AiAgentProvider` abstrait ; rien de spécifique à Anthropic.
- **Aucune incohérence avec `ARCHITECTURE_CANONIQUE.md`** (aucune table, aucun schéma).
- **Isolation `user_id`** inchangée : la doctrine ne touche à aucun accès aux données.
