# Mini-spec — F-28 / SF-28-18 — Un tour tronqué se dit, et ne condamne plus le projet

## Identifiant

`F-28 / SF-28-18`

## Feature parente

`F-28` — Atelier (Claude Code Lite)

## Statut

`ready`

## Date de création

2026-09-06

## Branche Git

`feat/SF-28-18-tour-tronque-et-memoire-vide`

---

## Objectif

> Faire que la boucle tool-use maison **dise** quand la réponse du modèle a été coupée au lieu de la
> prendre pour une réponse finie, et qu'elle ne puisse plus écrire dans l'historique un message vide
> que l'API refusera ensuite de rejouer — ce qui rend aujourd'hui le projet définitivement muet.

---

## Déclencheur

Audit `docs/features/F-28/AUDIT-parite-claude-code.md` (2026-09-06), défauts §2.1 et R1/R2, avant le
banc d'essai du runner. Deux comportements y sont **vérifiés par appel réel à l'API**, pas déduits :

1. `POST /v1/messages` avec `max_tokens` atteint pendant un tour d'outil renvoie
   `stop_reason: "max_tokens"` et un contenu **incomplet** — souvent une phrase d'intention seule
   (« Je vais créer ce fichier… »), sans le bloc `tool_use` qui allait suivre.
2. Un message assistant dont le bloc texte est vide est refusé :
   `400 invalid_request_error — "messages: text content blocks must be non-empty"`.

Enchaînés par le code actuel, ils produisent une panne en deux temps : le tour paraît réussi mais
n'a rien fait, puis **tous les messages suivants de ce projet échouent**, sans issue depuis l'écran.

---

## Comportement attendu

### Cas nominal

Inchangé. Un tour qui se termine normalement (`end_turn`) ou qui demande des outils (`tool_use`) se
comporte exactement comme aujourd'hui — zéro régression attendue sur le chemin courant.

### Cas « réponse tronquée » (nouveau)

1. Le fournisseur renvoie `stop_reason: "max_tokens"`.
2. La boucle **n'exécute aucun outil de ce tour** : les blocs sont potentiellement incomplets, et
   exécuter une écriture dont le contenu a été coupé corromprait le fichier.
3. La boucle s'arrête et rend un message explicite, qui **nomme la cause et l'issue** :
   > « Ma réponse a dépassé la taille maximale autorisée et a été coupée : rien n'a été exécuté.
   > Demande-moi une modification plus courte, ou de travailler fichier par fichier. »
4. Ce message est persisté comme réponse assistant — il est non vide, donc rejouable.

### Cas « réponse vide » (nouveau)

Toute réponse finale vide ou blanche, quelle qu'en soit la cause, est remplacée à la persistance par
un texte explicite (« Je n'ai pas produit de réponse pour ce message. ») : **l'historique ne peut
plus contenir de message vide**.

### Cas « historique déjà pollué » (nouveau — projets existants en production)

À la reconstruction de l'historique envoyé au fournisseur, tout message au contenu blanc est
**ignoré**. Les projets déjà condamnés en production redeviennent utilisables sans intervention en
base, et sans réécrire l'historique affiché à l'écran (l'écran continue de montrer ce qui s'est
passé).

Deux précautions sur le résultat de ce filtrage, vérifiées contre l'API :
- deux messages `user` consécutifs sont **acceptés** (testé : 200) — sauter un assistant vide ne
  casse donc pas l'alternance ;
- un historique qui commencerait par un message `assistant` serait refusé : les messages assistant
  en tête sont donc écartés.

### Plafond de sortie (R2)

`max_tokens` de la boucle agent passe de **4 096** à **16 384**, via une propriété **dédiée à
l'agent** — le chat (F-02) garde la sienne, inchangée. Justification en §Notes et décisions.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| `stop_reason: max_tokens` | Message explicite, aucun outil exécuté, tour clos proprement | 200 |
| Réponse finale vide | Remplacée par un texte explicite avant persistance | 200 |
| Historique contenant des messages vides | Ignorés à l'envoi ; l'écran reste inchangé | 200 |
| `agent-max-tokens` absent ou ≤ 0 de la configuration | Retombe sur le défaut 16 384 | — |
| Panne fournisseur (429/529/timeout) | Inchangé pour cette SF (traité par R8, hors périmètre) | 502 |

---

## Critères d'acceptation

- [ ] Un tour dont le `stop_reason` est `max_tokens` **n'exécute aucun outil**, même si des blocs
      `tool_use` sont présents dans la réponse.
- [ ] Ce tour rend et persiste un message qui nomme la troncature ; il n'est jamais vide.
- [ ] Une réponse finale vide ou blanche n'est **jamais** persistée telle quelle, ni par le mode
      Assistant (`AtelierChatService`), ni par le mode Terminal (`AtelierSessionService`).
- [ ] Un message d'historique au contenu blanc n'est **pas** envoyé au fournisseur.
- [ ] Un historique dont les premiers messages sont des assistant n'envoie pas d'assistant en tête.
- [ ] `AgentTurn` porte l'information de troncature ; `AnthropicAgentProvider` la renseigne depuis
      `stop_reason`.
- [ ] Le plafond de sortie de l'agent est **16 384** par défaut, surchargeable par configuration,
      et le plafond du chat reste **4 096**.
- [ ] Isolation `user_id` inchangée : `requireOwned` reste le premier geste de la boucle.
- [ ] Aucune régression sur le chemin nominal (tours `end_turn` et `tool_use`).

---

## Périmètre

### Hors scope (explicite)

- **R3** — outil `edit_file` (remplacement de chaîne exacte). C'est la cause racine du besoin
  d'écrire des fichiers entiers ; subfeature à part entière.
- **R4** — rejeu de la trajectoire (`tool_use`/`tool_result`) dans l'historique.
- **R5** — `thinking` adaptatif, `effort`, passage à `claude-opus-5` sur la boucle maison.
- **R6/R7** — cache de prompt, compaction, mesure de contexte.
- **R8** — retry 429/529 et câblage du timeout HTTP.
- **R9** — relèvement de `MAX_ITERATIONS`.
- Aucun changement d'écran : le frontend affiche le message rendu par le backend, tel quel.

---

## Valeurs initiales

Sans objet — aucune entité créée, aucun champ d'état ajouté.

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format / Valeurs autorisées | Unicité | Normalisation |
|-------|-------------|-------------|----------------------------|---------|---------------|
| `app.ai.anthropic.agent-max-tokens` | Non | — | entier > 0 ; défaut 16 384 | Non | valeur ≤ 0 ou absente ⇒ défaut |
| `atelier_messages.content` | Oui | text | **non blanc** (nouvelle garantie applicative) | Non | `trim()` au contrôle |

Notes :
- La contrainte « non blanc » est **applicative**, pas une contrainte de base : les lignes vides déjà
  présentes en production restent lisibles à l'écran et sont simplement ignorées à l'envoi.

---

## Technique

### Endpoint(s)

Aucun endpoint ajouté ni modifié.

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| `atelier_messages` | INSERT / SELECT | Aucune colonne ajoutée ; garantie applicative sur `content` |

### Migration Liquibase

- [ ] Oui
- [x] **Non applicable** — aucun changement de schéma.

### Classes impactées (backend)

| Classe | Changement |
|--------|-----------|
| `agent/AgentTurn` | Nouveau composant `truncated` |
| `agent/AnthropicAgentProvider` | Renseigne `truncated` depuis `stop_reason` ; utilise le nouveau plafond |
| `ai/AnthropicProperties` | Nouvelle propriété `agentMaxTokens` (défaut 16 384) |
| `atelier/AtelierChatService` | Arrêt sur tour tronqué ; filtrage de l'historique ; garde anti-vide |
| `atelier/agent/AtelierSessionService` | Garde anti-vide sur `persistTurn` |
| `application.yml` | `agent-max-tokens: ${ANTHROPIC_AGENT_MAX_TOKENS:16384}` |

### Composants Angular

Aucun.

---

## Préoccupations transversales

| Préoccupation | Concernée | Composants impactés |
|--------------|-----------|--------------------|
| Auth / Principal | Non | Aucun changement d'authentification ni de Principal |
| Contexte tenant | Non | `requireOwned` inchangé, toujours premier geste ; aucun nouveau chemin d'accès aux données |
| Plans / limites | Non | Le quota est contrôlé et décompté aux mêmes endroits ; `max_tokens` est un plafond **par appel**, déjà couvert par le quota en tokens et le budget de session |
| Navigation / routing | Non | Aucune route, aucun guard, aucun écran touché |

`AgentTurn` est un record public : tous ses appelants sont recensés et corrigés
(`AnthropicAgentProvider`, `StubAiAgentProvider` de test, tests de `AtelierChatService`). Le
compilateur garantit l'exhaustivité.

---

## Plan de test

### Tests unitaires

- [ ] `AnthropicAgentProviderTest` — `stop_reason: "max_tokens"` ⇒ `truncated == true`,
      `finished == true`.
- [ ] `AnthropicAgentProviderTest` — `stop_reason: "end_turn"` et `"tool_use"` ⇒ `truncated == false`.
- [ ] `AnthropicAgentProviderTest` — le corps envoyé porte le plafond **agent** (16 384), pas celui
      du chat.
- [ ] `AnthropicPropertiesTest` — `agentMaxTokens` : défaut, valeur nulle, valeur ≤ 0, surcharge.
- [ ] `AtelierChatServiceTest` — tour tronqué **avec** un `tool_use` présent : aucun outil exécuté
      (le stub de workspace n'enregistre aucune écriture), réponse contenant la mention de coupure.
- [ ] `AtelierChatServiceTest` — tour tronqué : le message persisté est non vide.
- [ ] `AtelierChatServiceTest` — réponse finale vide ⇒ message persisté non vide.
- [ ] `AtelierChatServiceTest` — historique contenant un message blanc ⇒ absent des messages
      transmis au provider (vérifié sur le stub).
- [ ] `AtelierChatServiceTest` — historique commençant par des messages assistant ⇒ ceux-ci sont
      écartés, le premier message transmis est un `user`.
- [ ] `AtelierChatServiceTest` — non-régression : tour `end_turn` et tour `tool_use` inchangés.
- [ ] `AtelierSessionServiceTest` — run dont la réponse est vide ⇒ message persisté non vide.

### Tests d'intégration

- [ ] `AtelierChatApiIntegrationTest` — un tour tronqué renvoie 200 avec le message explicite (pas
      une 502, pas une réponse vide).

### Isolation workspace

- [x] Applicable — les tests existants d'isolation de `AtelierChatService` (404 sur workspace
      d'autrui) doivent rester verts ; aucun nouveau chemin d'accès aux données n'est introduit.

---

## Dépendances

### Subfeatures bloquantes

- `SF-28-02` (boucle tool-use) — statut : done
- `SF-38-07` (outil bash sur runner) — statut : done

### Questions ouvertes impactées

- [ ] Aucune question de `docs/OPEN_QUESTIONS.md` n'est touchée.

---

## Notes et décisions

**D1 — Un plafond dédié à l'agent plutôt qu'un plafond global relevé.** `app.ai.anthropic.max-tokens`
est partagé avec le chat (F-02). Le relever globalement changerait le comportement d'une feature
livrée et stable, pour un besoin qui n'est pas le sien : c'est l'agent, et lui seul, qui doit
transporter le contenu entier d'un fichier dans sa sortie (`write_file`). Une propriété séparée
laisse le chat strictement inchangé — non-régression par construction.

**D2 — 16 384 et non 64 000.** Les valeurs au-delà exigent le streaming vers le fournisseur pour ne
pas heurter les délais HTTP, or `AnthropicAgentProvider` appelle en **non-streamé**. 16 384 est le
maximum sûr sans changer le mode d'appel — et il quadruple déjà la capacité d'écriture. Le passage à
64 K viendra avec le streaming provider, hors de cette SF.

**D3 — Un tour tronqué n'exécute aucun outil, plutôt que d'exécuter ceux qui semblent complets.**
Rien ne distingue de façon fiable un bloc `tool_use` complet d'un bloc coupé au bon endroit. Exécuter
une écriture dont le contenu a été tronqué produirait un fichier corrompu que personne n'a demandé —
un échec silencieux remplacé par un dégât silencieux. On refuse le tour entier, et on le dit.

**D4 — Filtrer à la lecture plutôt que nettoyer la base.** Une migration effaçant les messages vides
réécrirait l'historique visible par l'utilisateur — et perdrait la trace de ce qui s'est passé. Le
filtrage à l'envoi répare le symptôme là où il fait mal (l'appel au fournisseur) sans toucher à la
mémoire du projet. Il protège aussi contre toute source future de message vide.

**D5 — La garde anti-vide est posée dans les deux modes.** Les deux moteurs écrivent dans la même
table `atelier_messages` (audit §2.9) : un run Terminal à réponse vide contaminerait le mode
Assistant. Corriger un seul point d'écriture laisserait la porte ouverte.
