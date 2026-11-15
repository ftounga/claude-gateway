# Cadrage F-262 — Jarvis répond même poste éteint

**Origine** : audit de la séance Jarvis du PO du 2026-11-15, 09:29-09:40 (heure de Paris), postes clients éteints, prod `staging-5c16b4d7`.

## Constat (causes prouvées sur le code d'`origin/main` et l'API)

1. **Cinq fois « Le poste est éteint ; je vous réponds avec ce que je sais » puis « Je ne peux pas vous en dire davantage »** : `JarvisFastBrainService.answerOffline` appelle Opus 5.5 avec `AgentReasoning.off`, traduit par `AnthropicAgentProvider` en `thinking: {type: "disabled"}` (seul Sonnet 5.5 reçoit `between_tools`). Réponse réelle de l'API, rejouée le 15/11 : `400 invalid_request_error — "thinking.type.disabled" is not supported for this model. Use "thinking.type.adaptive" and "output_config.effort"…`. Le journal ne gardait que « Appel agent… en échec (modèle=claude-opus-5-5) » : le motif était perdu. La réponse « poste éteint » n'a donc **jamais** fonctionné depuis F-253.
2. **(6a) Délégations générales muettes** : sujet « jarvis » → fil Jarvis, cible `SANDBOX` ; la boucle maison en bac à sable est fermée par défaut depuis F-39 (`AtelierChatService`, `StorageExecutionClosedException`) → aucun tour, aucune réponse, question non enregistrée.
3. **(6b) Lignes « ↗ … en cours » figées** : `settle` route (ligne-lien + registre) **avant** de tester le poste éteint.
4. **(6e) « Allons dans alarm4tech, qu'en est-il ? »** délégué alors que l'état était au contexte.

## Décision

- **Réflexion par modèle** (provider) : « éteint » devient, selon le modèle, `disabled` (Haiku 5.5…), `between_tools` (Sonnet 5.5) ou **rien + effort** (Opus 5.5, Fable 5/5.1, Mythos : la réflexion ne s'y éteint pas ; l'effort bas la borne). Le motif d'un refus fournisseur (statut, type, message tronqué ; jamais la clé ni le contenu) est journalisé.
- **Réponse côté passerelle** : réflexion adaptative à l'effort du cerveau rapide, place de réflexion dans le plafond, blocs de réflexion rejoués dans la boucle d'outils, lectures sur le **poste visé**. Si le modèle échoue : **les faits relevés** de ce poste (registre d'état, datés) plutôt que la phrase vide.
- **Poste testé avant le routage** : plus aucune ligne-lien ni délégation enregistrée pour un poste éteint. Les lignes déjà figées en prod ne sont **pas** corrigées en base (signalé au PO).
- **Demandes générales** : répondues côté passerelle, même chemin lecture seule, jamais de silence.
- **Consigne** : une question d'état sur un sujet nommé se répond depuis le contexte (préfixe stable, rien de volatil).
- **Action demandée poste éteint** (« avance le chantier 2 ») : question produit **non tranchée**. Pour l'instant Jarvis dit que le poste est éteint, qu'il ne peut pas agir, puis ce qu'il sait.

## Découpage

- **SF-262-01** — réflexion par modèle, motif du refus, réponse poste éteint réparée et repli factuel, poste testé avant le routage (≈ 1 j). Backend.
- **SF-262-02** — demandes générales répondues côté passerelle ; consigne « répondre depuis le contexte » (≈ 1 j). Backend + bancs.

## Hors périmètre

- Agir sur un poste éteint (file d'attente, réveil) : question PO.
- Rouvrir la boucle en bac à sable du fil Jarvis.
- Clore en base les lignes « en cours » déjà figées.
