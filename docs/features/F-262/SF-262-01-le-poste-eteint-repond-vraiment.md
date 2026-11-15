# Mini-spec — F-262 / SF-262-01 Le poste éteint répond vraiment

## Identifiant

`F-262 / SF-262-01`

## Feature parente

`F-262` — Jarvis répond même poste éteint (cadrage `CADRAGE-F-262-jarvis-repond-poste-eteint.md`)

## Statut

`done`

## Date de création

2026-11-15

## Branche Git

`feat/SF-262-01-jarvis-repond-poste-eteint`

---

## Objectif

Quand le poste visé est éteint, Jarvis dit ce qu'il sait vraiment (Opus en lecture seule, ou à défaut les faits relevés), sans jamais retomber sur une phrase vide à cause d'une forme de réflexion que le modèle refuse.

---

## Comportement attendu

### Cas nominal

1. Le cerveau rapide délègue vers un sujet dont le poste est éteint. **Avant tout routage**, le poste est testé : aucune ligne « ↗ … en cours » n'est ouverte, aucune délégation n'est enregistrée.
2. Jarvis dit : « Le poste de CAGIP est éteint ; je ne peux pas y agir, je vous dis ce que je sais. »
3. Opus (modèle Atelier, `claude-opus-5-5` en prod) répond côté passerelle, en lecture seule (pièces, agenda, état, conversations), **réflexion adaptative à l'effort du cerveau rapide** (`low`), plafond de sortie + 4 000 jetons de place pour la réflexion ; dans sa boucle d'outils, les blocs de réflexion du tour sont rejoués tels quels avant les `tool_use`. Les lectures `etat`, `pieces`, `conversations` portent sur le **poste visé**, pas sur le sujet à l'écran.
4. Provider : « réflexion éteinte » se traduit par modèle — `between_tools` (Sonnet 5.5), **rien + effort** (Opus 5.5, Fable 5 / 5.1 / 5.2, Mythos 5 / 5.1 / 5.2 : ces modèles refusent toute extinction — vérifié dans la documentation de l API), `disabled` sinon.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Le fournisseur refuse la requête (4xx/5xx) | journal `warn` avec le modèle, le statut HTTP, le type et le message d'erreur du fournisseur (tronqué à 300 caractères ; ni clé, ni contenu de requête) ; l'exception neutre reste celle d'avant |
| Opus échoue ou rend un texte vide | Jarvis dit les **faits relevés** du poste visé (deux au plus, datés, tirés du registre d'état) : « Dernier relevé : … » ; sans aucun fait : « Je n'ai aucun relevé récent de ce poste, Monsieur. » |
| Poste connecté, sujet hébergé, sujet d'un autre compte | délégation inchangée, routée comme avant (ligne-lien comprise) |
| Lecture du poste en échec | délégation inchangée (comme avant) |

---

## Critères d'acceptation

- [x] CA1 — `AgentReasoning.off` vers `claude-opus-5-5` : corps **sans** `thinking.type` `disabled`/`between_tools`, `output_config.effort` = l'effort demandé ; un serveur simulé qui rend la vraie erreur de l'API (400 `"thinking.type.disabled" is not supported…`) à toute requête `disabled` sur ce modèle n'est **pas** atteint en erreur. Sonnet 5.5 garde `between_tools`, Haiku 5.5 `disabled`.
- [x] CA2 — Un refus fournisseur est journalisé avec statut, type et message (test sur le journal).
- [x] CA3 — `answerOffline` : requête adaptative (`reasoning().adaptive()`, effort `low`), plafond 600 + 4 000 ; avec un faux fournisseur **fidèle à l'API** (refuse « éteint » sur Opus 5.5 comme l'API), la réponse d'Opus est dite (rouge sur `main`).
- [x] CA4 — Boucle d'outils : la réflexion du 1er appel est rejouée en tête du message assistant du 2e ; `etat` lit le poste visé.
- [x] CA5 — Échec du modèle : les faits relevés du poste visé sont dits ; sans fait, la phrase « aucun relevé ».
- [x] CA6 — Poste éteint : `links.open` et `delegations.offer` ne sont **jamais** appelés ; poste connecté : appelés comme avant.
- [x] CA7 — L'annonce dit « je ne peux pas y agir, je vous dis ce que je sais ».

---

## Contraintes de validation

| Champ | Règle |
|---|---|
| Message d'erreur journalisé | ≤ 300 caractères, sauts de ligne retirés |
| Faits du repli | 2 au plus |

---

## Périmètre

### Inclus

- `AnthropicAgentProvider` : traduction de « éteint » par modèle, journal du motif d'un refus.
- `JarvisFastBrainService` : `settle` (poste testé avant `route`), `offlineInstead`, `answerOffline`, `offlineNotice`, repli factuel.

### Hors scope

- Demandes générales (fil Jarvis) : SF-262-02. Consigne du cerveau rapide : SF-262-02.
- Agir sur un poste éteint (question PO ouverte).
- Clore en base les lignes « en cours » déjà figées en prod (signalé au PO).

---

## Préoccupations transversales

- [ ] Auth / Principal — non
- [ ] Contexte tenant — non : lectures existantes, toutes filtrées par `user_id` (`findByIdAndUserId`, registre d'état par compte)
- [x] Plans / limites — **composants impactés** : `JarvisFastBrainService.account()` (chaque appel Opus du chemin éteint est compté au budget Jarvis, comme avant ; la réflexion compte en sortie). Aucun gate modifié.
- [ ] Navigation / routing — non

## Tables / endpoints / composants impactés

- Tables : aucune (lecture de `jarvis_state_facts` via le service existant). Aucune migration.
- Endpoints : aucun nouveau ; `POST /api/jarvis/converse` change de comportement poste éteint.
- Composants : `AnthropicAgentProvider`, `JarvisFastBrainService`.

## Plan de test

- Unitaires `AnthropicAgentProviderTest` : CA1 (serveur simulé fidèle), CA2.
- Unitaires `JarvisOfflineTest` : CA3 (faux fournisseur fidèle), CA4, CA5, CA6, CA7.
- Non-régression : `JarvisFastBrainServiceTest`, `JarvisFastBrainStateTest`, tests de routage existants.

### Isolation

- Sujet d'un autre compte : `findByIdAndUserId` vide → délégation inchangée, aucun modèle appelé (test existant gardé).

## Dépendances

Aucune.
