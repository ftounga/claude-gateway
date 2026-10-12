# SF-195-03 — Moteur : exécuter réellement en parallèle les appels groupés

**Feature parente :** F-195 Plusieurs sujets à la fois · **Type :** backend · **Estimation :** 1 j

## Objectif
Les appels de lecture émis ensemble dans une même réponse du modèle partent ensemble sur le poste, comme dans Claude Code ; tout ce qui écrit reste séquentiel.

## Comportement nominal
- Dans la boucle d'outils, à chaque appel non encore exécuté, la gateway prend la **suite d'appels consécutifs sûrs** qui commence là et l'exécute d'un bloc (pool borné) :
  - sûr = outil de lecture (`read_file`, `grep`, `glob`, `list_files`, `search_files`) ou `bash` de premier plan classé **LECTURE** par `JourneyRiskClassifier` (classificateur existant, prudent : l'inconnu est une modification) ;
  - **et** sans invite d'autorisation (politique ALLOW, ou « tout autoriser pour ce message ») ; un DENY reste au chemin séquentiel (refus tracé comme avant).
- Tout autre appel (écriture, `bash` qui modifie, outil de volet, délégation écrivaine…) est une **barrière** : rien de ce qui le suit ne part avant lui.
- Bornes : `app.atelier.parallel-tools` appels par groupe (défaut 4 ; 0/1 = coupe-circuit, tout séquentiel) ; au plus `bashConcurrency` commandes `bash` par groupe (capacité annoncée SF-195-02 ; vieux runner = 1).
- Chaque appel passe par le chemin ordinaire (`executeTool` : point de contrôle, politique, audit, réessai, attente « occupé ») avec une écoute tampon ; la boucle rattache ensuite les résultats **dans l'ordre des appels** : `tool_result`, trace, transcription, journal, interjections, gardes inchangés. La sortie captée de chaque commande est relayée à l'écran après son intention d'étape — jamais entremêlée.

## Cas limites / erreurs
- Terminal Teams : jamais de parallèle (ses verrous de lecture s'évaluent appel après appel).
- Cible SANDBOX : inchangé (séquentiel).
- Panne d'un appel du groupe ⇒ SON résultat en erreur, les autres intacts ; interruption ⇒ « L'appel a été interrompu. ».
- `agent_ask_before_bash` actif ⇒ les `bash` ne sont pas groupés (jamais deux invites ensemble).

## Critères d'acceptation
1. 3 `bash` lecture d'une même réponse : 3 en vol simultanément, durée < 3 × durée unitaire, `tool_result` dans l'ordre t1, t2, t3.
2. `[lecture, lecture, write_file, lecture, lecture]` : 2 au plus en vol ; rien après l'écriture ne démarre avant elle.
3. Vieux runner (`bashConcurrency` = 1) ⇒ une commande à la fois.
4. `mvn test`, `npm install` ⇒ jamais groupés.
5. Ask-before-bash ⇒ jamais groupés.
6. `parallel-tools: 0` ⇒ tout séquentiel.
7. Les sorties diffusées suivent l'ordre des appels.

## Plan de test
- Unitaires (`AtelierChatServiceParallelToolsTest`, 7 cas ci-dessus) ; non-régression `fr.claudegateway.atelier.**` + `runner.**`.
- Isolation utilisateur : chaque appel garde `userId`/workspace du tour (aucun nouvel accès données).

## Impacts
- `AtelierChatService` (boucle, `ParallelRun`, `BufferingListener`, réglage `app.atelier.parallel-tools`). Lit `RunnerHostService.bashConcurrency` (SF-195-02).
- Aucune table, aucun endpoint. Préoccupations transversales : aucune (porte d'autorisation inchangée, les appels qui la demanderaient ne sont pas groupés).

## Hors périmètre
- Sous-agents écrivains en parallèle (SF-195-04) ; SANDBOX.
