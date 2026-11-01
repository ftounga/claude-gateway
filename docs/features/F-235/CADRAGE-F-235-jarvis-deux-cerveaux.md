# CADRAGE F-235 — Jarvis à deux cerveaux

**Statut :** cadrage du 2026-11-01 · Famille « Jarvis » : F-230, F-231, F-232, F-233, F-234, **F-235**.
Cadrage **directement livrable** : le PO a donné le go et veut une livraison autonome. Toutes les décisions ont un
défaut tranché ; celles qui méritent son œil sont listées en fin de document (§ « Décisions prises par défaut »).

## Demande et décision PO (2026-11-01)

**Constat mesuré en prod** (`jarvis_turn_timings`, 2026-11-01, un tour vocal) : transcription **0,18 s**, premier
jeton d'Opus 5.5 à **8,7 s**, premier son à **9,7 s**, fin à **22,9 s**. Le PO trouve Jarvis « robotique et lent », et
« un peu simplet » : il répète sans arrêt les mêmes formules toutes faites (« Je t'écoute », « Je m'en occupe »).

**Cause** : chaque parole est un **tour d'agent normal du terminal** — même fil, Opus 5.5, effort `high`
(`app.atelier.effort`, `step-effort: medium`), ≈ 150 k jetons de contexte, consigne orale dans le message. F-233 a
rendu rapide tout ce qui **entoure** le tour (écoute en flux, clips, voix en flux, interruption) ; le premier jeton
d'Opus sur 150 k jetons reste incompressible sans toucher au raisonnement (F-233, budget de latence, ligne « Premier
jeton du cerveau »).

**Décision PO — C2 « deux cerveaux »** : un modèle **rapide** tient la conversation vocale avec un contexte utile et
répond en 1-2 s ; dès qu'il faut **agir** (outils, poste, Jira…) ou **réfléchir profondément**, il **délègue à Opus**
(le tour d'agent actuel du terminal, inchangé) en tâche de fond — « Je regarde les tickets bloqués d'Edenred,
Monsieur » — et le résultat est **annoncé à voix haute** à la fin (mécanisme d'annonce SF-231-03 / SF-232-03).

### Décision existante amendée (à lire avant tout)

**F-231 D1 « Le même cerveau »** (Jarvis = interface vocale sur le terminal du sujet, ni second agent ni modèle
vocal) et **F-233 D1** (« le cerveau ne change pas ») sont **amendées par le PO le 2026-11-01** :
- ce qui **reste vrai** : toute action, tout outil, toute réflexion profonde passent par **le tour d'agent Opus du
  terminal, inchangé** (même fil, mêmes outils, même modèle, même effort, même consigne système) ; aucun modèle
  parole-à-parole ;
- ce qui **change** : la **conversation** (répondre à ce qui est déjà connu, reformuler, orienter, annoncer) est tenue
  par un second modèle, **sans aucun outil d'action**, dont toute réponse est **tracée dans le fil** du sujet.

## Règles qui bornent ce cadrage (non négociables)

- **Justesse avant coût** (mémoire « justesse avant coût — règle absolue ») : le cerveau rapide ne fait **rien**
  qu'Opus ferait mieux. **Dans le doute, il délègue.** Il n'invente jamais un fait absent de son contexte : il délègue
  ou dit qu'il vérifie (et délègue).
- **Aucun outil d'action** pour le cerveau rapide : un seul outil, `deleguer`. Une action qui engage passe **toujours**
  par Opus et par la confirmation orale (« Vous confirmez ? » + « oui » net, SF-231-04).
- **Préfixe stable** (mémoire « préfixe stable = rien de volatil ») : la consigne système du cerveau rapide ne contient
  **rien de volatil** (ni heure, ni état, ni sujet) ; tout le volatil va **dans le message**. La consigne système du
  terminal (Opus) n'est **pas touchée**.
- **Provider Independence** : le cerveau rapide passe par `AiAgentProvider` (l'interface qui porte les outils et le
  flux, `agent/AiAgentProvider.java`) ; le modèle est un réglage, jamais un `if` sur Anthropic dans le domaine.
- **Isolation `user_id`** sur toute lecture du contexte et toute écriture du fil.
- **Coupe-circuit** : un réglage remet Jarvis exactement dans le mode actuel (tout va à Opus).

## Existant vérifié (code, 2026-11-01)

| Sujet | Existant | Où |
|---|---|---|
| Interface agent à outils + flux | `AiAgentProvider.nextTurn(request, textListener)` ; `AgentTurnRequest(model, system, messages, tools, apiKey, reasoning…)` | `agent/AiAgentProvider.java`, `AgentTurnRequest.java` |
| Raisonnement | `AgentReasoning(adaptive, effort)` : **pas d'état « désactivé »** ; `adaptive=false` = paramètre `thinking` omis | `agent/AgentReasoning.java`, `AnthropicAgentProvider.applyReasoning` |
| Capacités relayées par modèle | `fallback-models`, `progress-update-models`, `explicit-effort-models`, `binding-control-models` : **Haiku 5.5 dans aucune liste** ⇒ requête inchangée | `application.yml` `app.agent.api-features` |
| Tarifs | `claude-haiku-4-5`, `claude-sonnet-5-5` présents ; **`claude-haiku-5-5` absent** | `application.yml` (grille de tarifs) |
| Journal d'usage hors tour | `UsageLedgerService.recordAnnex(userId, workspaceId, model, in, out, …)` ; `QuotaService.assertWithinQuota`, `costOf` | `quota/` |
| Précédent « petit appel modèle + budget € » | phrase d'alerte de veille par `AIProvider`, budget mensuel, garde de quota, repli déterministe | `atelier/veille/JarvisWatchAlerter.java` (SF-230-03) |
| Univers | `GET /api/jarvis/universe` : clients → sujets → chantiers → étape, interlocuteurs, « à envoyer » (filtré `user_id`, sans contenu) | `jarvis/universe/` (SF-234-01) |
| Attentes, à envoyer, tours via Jarvis | `JarvisReturnService.absence(...)` / `toSend(...)` | `jarvis/JarvisReturnService.java` (SF-231-06) |
| Marque d'origine | `atelier_messages.via = 'JARVIS'` | `AtelierMessage.VIA_JARVIS` (migration 191) |
| Consigne orale | `JarvisOralNote.TEXT` **jointe au message**, `appendCut` (note « coupée après ») | `jarvis/JarvisOralNote.java`, `AtelierChatService` l. ≈ 3366 |
| Tour vivant / précision | `LiveTurnRegistry.openOrSteer` : un envoi sur un fil dont le tour vit devient une **précision** ; un tour vivant **par fil** (F-221) | `atelier/live/LiveTurnRegistry.java` |
| Annonces téléphone verrouillé | fins de tour via Jarvis (phrase 🎙) + alertes de veille, fenêtre 10 min | `jarvis/companion/JarvisAnnouncementService.java` (SF-232-03) |
| Formules fixes à l'oral | clips `« Oui, Monsieur ? »`, `« Je regarde. »`, `« Une seconde. »` (alternés) ; accusé `« Je m'en occupe. »` à 2 s ; préfixe `« C'est terminé. »` au-delà de 30 s | `JarvisClipService.clips()`, `jarvis-oral.ts` (`ACK_PHRASE`, `REACTION_CLIPS`, `withEndAnnouncement`) |
| Chronométrage | `jarvis_turn_timings` (sans contenu, purge 90 j), `GET /api/admin/jarvis/latency` | `jarvis/timing/` (SF-233-01, migration 196) |
| Écran | `jarvis.component.ts` `send()` → `atelier.streamChat(..., 'JARVIS')`, `OralStream` (phrases 🎙 au fil des deltas), confirmation `jarvis-confirm.ts`, interruption `jarvis-barge-in.ts` | `frontend/src/app/jarvis/` |
| Mains libres | **SF-233-11 en cours de livraison par un autre agent** (conversation sans appui). « Je t'écoute » n'existe pas sur `main` : formule probablement portée par SF-233-11 (et en tutoiement). | — |

## Choix du modèle rapide (D1)

Faits (référence Claude API, novembre 2026 ; prix en $ convertis à 0,92 €/$, `APP_COST_USD_TO_EUR`) :

| | **Claude Haiku 5.5** `claude-haiku-5-5` | Claude Sonnet 5.5 `claude-sonnet-5-5` |
|---|---|---|
| Prix entrée / sortie | **0,10 $ / 0,50 $** par M jetons (prompt ≤ 100 k ; 0,50 / 2,50 au-delà) | 2 $ / 10 $ |
| Lecture de cache / écriture (TTL 5 min) | 0,01 $ / 0,125 $ | 0,20 $ / 2,50 $ |
| Cache minimum | 512 jetons | 512 jetons |
| Raisonnement coupé | `thinking: {type: "disabled"}` **accepté** à effort `low`/`medium`/`high` ; **omis = adaptatif** (allumé par défaut) | `disabled` = 400 ; seul `between_tools` (effort ≤ `high`) |
| `tool_choice` forcé | accepté | 400 |
| Repli serveur sur refus | **aucun** (refus = `stop_reason: refusal`) | `fallbacks: "default"` |
| Messages système en cours de conversation | oui | oui |
| Premier jeton | **non documenté** — attendu le plus bas de la gamme ; **à mesurer** (SF-235-07) | non documenté, attendu plus haut |
| Positionnement | « haut volume, sensible à la latence : classification, routage, extraction, sous-agents » ; nettement meilleur que Haiku 4.5 en suivi de consigne | qualité supérieure |

**Défaut retenu : Claude Haiku 5.5, raisonnement désactivé, effort `low`, réponse en flux, `max_tokens` 600.**
Raisons : son métier ici est exactement le routage + une réponse courte **à partir d'un contexte fourni** ; latence la
plus basse ; coût négligeable (≈ 2-3 €/mois). La justesse n'est pas jouée sur le modèle mais sur **la délégation par
défaut + le contrôle d'ancrage** (D9) : le cerveau rapide n'a pas à savoir, il a à **reconnaître qu'il ne sait pas**.
**Sonnet 5.5 est le repli par configuration** (`APP_JARVIS_FAST_MODEL=claude-sonnet-5-5`, `thinking: between_tools`,
effort `low`), retenu automatiquement si Haiku 5.5 échoue la porte du banc (SF-235-08).

Garde-fous propres au raisonnement désactivé (référence Claude API) : la consigne système dit « quand tu utilises
l'outil, tu peux dire une courte phrase avant ; si tu ne peux pas répondre avec ce que tu as, délègue au lieu de
deviner ; n'écris jamais de balise interne » ; un texte qui **ressemble à un appel d'outil** (JSON, `deleguer(`) est
traité comme une délégation, jamais dit.

## Décisions

| # | Décision (défaut retenu) | Pourquoi |
|---|---|---|
| **D1** | **Modèle rapide = Claude Haiku 5.5**, raisonnement désactivé, effort `low`, flux, `max_tokens` 600, derrière `AiAgentProvider`. Réglages `app.jarvis.fast-brain.model` (`APP_JARVIS_FAST_MODEL`), `effort`, `max-tokens`. Repli Sonnet 5.5 par configuration, choisi par le banc (D16). | Latence, coût, métier de routage ; justesse portée par D7-D9 |
| **D2** | **`AgentReasoning` gagne un état « désactivé »** (`AgentReasoning.off()`), traduit en `thinking: {type: "disabled"}` par `AnthropicAgentProvider` **uniquement** quand il est demandé ; rien ne change pour les appels existants. Sur Haiku 5.5, omettre `thinking` = raisonnement adaptatif : l'omission n'est donc pas « éteint ». Sonnet 5.5 : `between_tools`. Le mapping modèle → forme reste confiné au fournisseur. | Provider Independence ; un paramètre dont le sens dépend du modèle n'en est pas un (D-L5-2) |
| **D3** | **Tarif `claude-haiku-5-5`** ajouté à la grille (entrée 0,10 ; sortie 0,50 ; lecture 0,01 ; écriture 0,20 — convention TTL 1 h de la grille), grille ≤ 100 k uniquement (le contexte rapide est borné bien en dessous, D5). | Coût juste au journal et en € |
| **D4** | **Nouvel endpoint `POST /api/jarvis/converse`** (SSE, authentifié) : `{subjectId, text, chantier?, heardBeforeCut?, turnSeq}` → événements `text` (deltas), `delegated` (`{delegationId, subjectId, chantier, label, announce}`), `done` (`{brain, spokenText}`), `error`. Le sujet est vérifié `findByIdAndUserId`. **Dormant** si `app.jarvis.fast-brain.enabled=false` (503 ⇒ l'écran retombe sur `streamChat`, le mode actuel). | Un chemin à part, le tour Opus intouché |
| **D5** | **Contexte compact (dans le message, jamais dans la consigne système)** — ≤ 6 000 jetons, construit côté serveur en ≤ 150 ms, sans appel modèle, sous `user_id` : (a) date et heure, sujet et chantier courants ; (b) univers condensé (`JarvisUniverseService` : clients, sujets, chantiers, étape en cours, connectés, interlocuteurs) ; (c) attentes ouvertes et « À envoyer (n) » avec titres (`JarvisReturnService`) ; (d) **délégations en cours** (sujet, libellé, depuis quand) ; (e) **derniers échanges du fil** du sujet courant : 6 derniers messages, chacun tronqué à 400 caractères, réponses d'Opus réduites à leur ligne 🎙 + début ; (f) alertes de veille des dernières 24 h (phrase F-230) ; (g) « Jarvis a été coupé après : … » si `heardBeforeCut`. Chaque bloc porte son horodatage (« état à 14:32 »). Ce qui n'y est pas, le cerveau rapide **ne le sait pas**. | Utile et vrai, pas 150 k |
| **D6** | **Consigne système stable du cerveau rapide** (texte fixe versionné, ≈ 2-3 k jetons, cachée avec l'outil) : identité (Jarvis, majordome posé), adresse (vouvoiement, « Monsieur » avec parcimonie : début d'échange, annonce, interpellation), règles de route (D7), règles d'ancrage (« un fait = une ligne du contexte »), style oral (D11), formules interdites (D11), exemples fixes. **Rien de volatil.** Mémoire de conversation : les échanges rapides de la session (30 min, ≤ 12 tours) en messages, mots seuls ; le contexte D5 attaché au **dernier** message seulement. Pré-chauffage du cache (`max_tokens: 0`, non streamé) à l'ouverture de `/jarvis` et au mot d'éveil, au plus toutes les 4 min. | Préfixe stable, premier jeton bas |
| **D7** | **Le routage est décidé par le cerveau rapide, avec un seul outil `deleguer`** (`strict`, `tool_choice: auto`) : `{demande, label, annonce, subjectId, chantier?}` — `demande` = reformulation fidèle et complète pour Opus ; `label` = 3-6 mots (« tickets bloqués d'Edenred ») ; `annonce` = la phrase dite (« Je regarde les tickets bloqués d'Edenred, Monsieur. »). **Il délègue** dès que : une action est demandée ou implicite (créer, lancer, envoyer, valider, modifier, publier, pousser, relancer…) ; il faut un outil (poste, fichiers, Jira/Confluence, web, mails, commandes) ; la réponse exige un fait absent du contexte ; la question demande analyse, conception, diagnostic, rédaction longue ou avis engageant ; il a un doute. **Il répond lui-même** seulement : état déjà dans le contexte (« où en est le chantier 3 ? », « qu'est-ce que j'ai à envoyer ? »), rappel de ce qui vient d'être dit, reformulation, orientation (« passe sur Edenred »), politesse, clarification courte (« Vous parlez du ticket d'hier ou de celui de ce matin ? »), « Montrer » (ligne `🖥 montrer:` de F-234 SF-234-13). | Justesse : en cas de doute, Opus |
| **D8** | **Aucune action par le cerveau rapide.** Il n'a **aucun** autre outil (pas de lecture du poste, pas d'écriture, pas d'annulation ciblée). Une confirmation en attente (« Vous confirmez ? » d'Opus, ou outil `ask` vivant) **court-circuite le cerveau rapide** : la parole suivante va **directement** au tour Opus par le chemin actuel (`classifyConfirmation`, SF-231-04). « Stop / annule » (liste fermée `isStopCommand`) interrompt la **dernière** délégation vivante (`interruptChat`, chemin existant) et le dit (« J'arrête la recherche sur les tickets d'Edenred. »). | Une action qui engage passe toujours par Opus + « oui » |
| **D9** | **Garde-fous de justesse, déterministes, sans appel modèle** — appliqués **phrase par phrase avant la synthèse** (la phrase est complète avant d'être dite : aucune latence ajoutée) : (a) **contrôle d'ancrage** : tout nombre (≥ 2 chiffres, montant, pourcentage, date, heure), toute clé de ticket (`ABC-123`), tout nom propre absent **à la fois** du contexte D5 et de la parole du PO ⇒ la phrase n'est **pas dite**, la réponse est convertie en **délégation automatique** (« Je vérifie, Monsieur. ») avec la question d'origine ; (b) refus (`stop_reason: refusal`), erreur, quota, budget, délai > 3 s sans premier jeton ⇒ délégation automatique (jamais de silence, jamais d'invention) ; (c) texte qui imite un appel d'outil ⇒ délégation ; (d) le PO dit « vérifie », « demande à Opus », « réfléchis-y », « c'est faux » ⇒ délégation forcée de la question précédente (liste fermée, classement local). Chaque déclenchement est compté (D14). | Ne jamais dire un fait inventé |
| **D10** | **Délégation = un tour Opus normal dans le fil** : `AtelierChatService` par le chemin de `streamChat` (via `JARVIS`), sur le sujet/chantier cible (`openOrSteer`). Message du tour = **parole du PO mot pour mot** + note fixe `[Jarvis — demande reformulée : « … »]` + note des échanges rapides (D12) + `JarvisOralNote` (inchangée) — tout **dans le message**, même régime que `heardBeforeCut` (SF-233-09). Fil déjà vivant ⇒ **précision** du tour en cours (comportement F-195 / F-84, rien d'abandonné) et l'annonce le dit (« J'ajoute ça à la recherche en cours. ») ; sujets ou chantiers différents ⇒ **tours parallèles** (un tour vivant par fil, F-221) ; à l'intérieur d'un tour, Opus parallélise lui-même (F-195). Le PO **continue de converser** avec le cerveau rapide pendant qu'Opus travaille. | Le tour Opus ne change pas |
| **D11** | **Parler comme quelqu'un, pas comme un répondeur** (retour PO « un peu simplet ») : (a) **contenu réel** : quand le contexte permet de répondre, la réponse **est** la réponse (pas d'accusé avant) ; (b) **délégation précise** : l'`annonce` nomme ce qui est lancé (verbe + objet + sujet), jamais générique ; (c) **variété** : jamais deux fois de suite la même phrase de remplissage, et aucune phrase identique (normalisée) à l'une des 5 précédentes réponses de la session ; (d) **formules interdites** (liste fermée dans la consigne ET contrôle serveur) : « Je m'en occupe », « Je t'écoute », « Je vous écoute », « Bien reçu », « D'accord, je regarde », « Très bien », « Je regarde. », « Une seconde. », « Laissez-moi vérifier » — en phrase **seule** (dans une phrase porteuse de contenu, elles ne sont pas bloquées) ; une phrase interdite ou répétée est **retirée** avant synthèse (et comptée) ; si la réponse est vide après retrait, `annonce` ou délégation ; (e) vouvoiement toujours, « Monsieur » au plus une fois par réponse et pas à chaque réponse ; (f) 1-3 phrases orales, ni liste ni Markdown, sauf demande. Côté écran : **l'accusé `« Je m'en occupe. »` est supprimé** en mode rapide (l'annonce le remplace) ; les **clips de réaction** ne sont joués **que si** aucun son n'est parti 1,2 s après la fin de parole, tirés d'un jeu élargi (≥ 6 clips vouvoyés, régénérés par `JarvisClipService`) **sans répétition consécutive** ; le préfixe `« C'est terminé. »` est remplacé par le `label` de la délégation (« Pour les tickets bloqués d'Edenred : … »). Toute formule fixe de SF-233-11 (« Je t'écoute ») passe au même jeu varié et au vouvoiement. | Exigence PO du 2026-11-01 |
| **D12** | **Trace dans le fil** : chaque échange rapide (parole + réponse) est écrit dans le fil du sujet courant (`atelier_messages`, `via='JARVIS'`, **nouvelle colonne `brain`** = `FAST`), affiché « 🎙 via Jarvis · réponse rapide » (et « → délégué à Opus : <label> » avec lien vers le tour). **Les échanges rapides ne sont pas rejoués comme des tours** dans le contexte d'Opus (ce serait insérer des messages au milieu d'un historique déjà envoyé : rejeu non append-only, cache cassé, blocs de raisonnement écartés) ; ils lui parviennent par une **note jointe au message du tour Opus suivant** du même fil (délégation ou saisie au terminal) : `[Échanges avec Jarvis depuis ton dernier tour — réponses rapides, non vérifiées : …]`, bornée à 1 500 caractères, plus récents d'abord. Le compagnon Android (SF-232-03) **n'annonce pas** les réponses rapides (déjà dites), seulement les fins de délégation. Purge : celle du fil. | Le terminal et Opus voient tout ; append-only |
| **D13** | **Annonce des résultats** : à la fin d'une délégation, l'écran dit la **ligne 🎙 de la réponse finale** d'Opus précédée du `label` ; en mode délégué, les lignes 🎙 **intermédiaires** (« annonce avant outils », D-PO-7 F-233) **ne sont pas dites** (le cerveau rapide a déjà annoncé). **File d'annonces** : FIFO par heure de fin ; jamais pendant que le PO parle ni pendant une autre phrase de Jarvis ; dite à la pause suivante (≥ 0,8 s de silence) ; plusieurs fins proches ⇒ dites l'une après l'autre, chacune avec son `label` ; une annonce interrompue par le PO reste disponible (« redis-moi »). Une délégation qui finit sur « Vous confirmez ? » arme la confirmation (D8). Scènes F-234 inchangées (la délégation joue la scène du tour Opus ; le cerveau rapide ne déclenche que « Montrer »). | Ordre clair, pas de double réponse |
| **D14** | **Mesure** : `jarvis_turn_timings` gagne `brain` (`FAST`, `FAST_DELEGATED`, `OPUS`), `fast_model`, `fast_first_token_ms`, `grounding_trips`, `fillers_removed`, `repeats_removed`, `forced_delegation` ; **sans contenu**. `GET /api/admin/jarvis/fast-brain/measure` (J+7/J+14) : p50/p90 du premier son par cerveau, **taux de délégation**, délégations automatiques (ancrage, refus, délai), **corrections du PO** (parole dans les 30 s suivant une réponse rapide classée « non / c'est faux / vérifie / demande à Opus », liste fermée), **part de réponses creuses ou répétées** (avant retrait = qualité du modèle ; après retrait = ce que le PO entend, cible 0), coût € du cerveau rapide et tours Opus évités. | Mesurer avant de croire |
| **D15** | **Coûts et plafonds** : chaque appel rapide passe `QuotaService.assertWithinQuota` puis `UsageLedgerService.recordAnnex` (modèle, jetons, cache) ⇒ coût au journal et en € ; **plafond mensuel propre** `app.jarvis.fast-brain.monthly-budget-eur` (défaut **20 €**) ; atteint ⇒ mode actuel (tout à Opus), sans erreur à l'oral. Le plafond voix F-233 (100 €) est inchangé. | Visible et borné |
| **D16** | **Porte d'activation par banc (dry-run)** : `POST /api/admin/jarvis/fast-brain/bench` rejoue sur le cerveau rapide les **60 dernières demandes vocales réelles du compte** (`atelier_messages.via='JARVIS'`, lues en base sous `user_id`, **jamais copiées dans le dépôt**) avec leur contexte reconstruit, sans synthèse ni écriture ; un juge **Opus 5.5** note chaque cas : route attendue (répondre/déléguer), fait non ancré, action non déléguée, formule creuse. **Porte** : 0 action non déléguée ; 0 fait non ancré **dit** (après D9) ; accord de route ≥ 90 % ; ≤ 5 % de réponses creuses/répétées avant retrait. Haiku 5.5 échoue ⇒ Sonnet 5.5 passe au banc ; les deux échouent ⇒ pas d'activation, rapport au PO. | Justesse prouvée avant mise en service |
| **D17** | **Coupe-circuit** : `app.jarvis.fast-brain.enabled` (`APP_JARVIS_FAST_BRAIN_ENABLED`) — `false` ⇒ `/converse` en 503 et l'écran fait exactement ce qu'il fait aujourd'hui (tout à Opus, accusé, clips) ; sans redéploiement de l'écran. Défaut `false` dans le code jusqu'à SF-235-08, qui le passe à `true` si la porte D16 est franchie. | Retour arrière immédiat |
| **D18** | **La personnalité de Jarvis** (demande PO du 2026-11-01 : le style du majordome d'Iron Man — un style, pas une copie). **Ton** : courtois, posé, concis, précis ; il **anticipe** (« J'ai pris la liberté de préparer le point sur Edenred. ») — l'anticipation ne porte que sur ce qui n'engage rien (préparer, vérifier, rappeler) ; une action qui engage reste reformulée puis confirmée (D8, SF-231-04) ; jamais servile ni bavard. **Humour** : ironie britannique sèche, pince-sans-rire, par petites touches — **au plus une note par échange**, seulement quand la situation s'y prête ; **jamais** sur une alerte de veille, un incident, une erreur, un échec de délégation ou un sujet client sensible ; jamais aux dépens du PO ni d'un tiers nommé. **Adresse** : vouvoiement et « Monsieur » avec parcimonie (D-PO-8 F-233, D11e). **Où l'écrire** : (a) cerveau rapide — **dans sa propre consigne système** (stable, cachée) : bloc « Personnalité » fixe, en **deux variantes figées** selon le réglage humour (deux préfixes stables, deux entrées de cache ; changer le réglage coûte une écriture de cache) ; (b) tours Opus délégués et tours vocaux actuels — la ligne 🎙 reprend le style par **`JarvisOralNote`, donc dans le message** (deux variantes figées du texte selon le réglage), **jamais dans la consigne système du terminal** ; le détail écrit à l'écran reste **sobre et professionnel** (la consigne le dit). **Sobriété forcée** : quand le contexte du tour porte une alerte, un incident, une erreur de tour ou un échec, le message du tour reçoit une note fixe `[Jarvis — ton sobre, sans humour]` (cerveau rapide et Opus). **Aucune imitation de l'œuvre** : ni réplique ni phrase culte du film, aucune mention de Stark, Tony Stark, Iron Man, Marvel, Avengers ; contrôle serveur : une phrase qui contient l'un de ces noms **absent du contexte** est retirée avant synthèse (et comptée). **Accent britannique** : porté par la voix (candidates britanniques de l'audition SF-233-07, Daniel / George), pas par le texte — rien à écrire. **Réglage par compte** « Humour : aucun / léger », **« léger » par défaut**, table `jarvis_preferences` (`user_id`, `humour`), `GET/PUT /api/jarvis/preferences`, interrupteur dans l'écran Jarvis. | Demande PO ; préfixe stable ; marque et droits |

## Budget de latence cible

Depuis la fin réelle de la parole ; mesuré par `jarvis_turn_timings` (D14). « Aujourd'hui » = mesure prod du
2026-11-01 (un tour) et budget F-233.

| Étape | Aujourd'hui (Opus) | Cible cerveau rapide |
|---|---|---|
| Fin de parole détectée | 0,3-0,7 s (F-233) | inchangé |
| Transcription disponible | **0,18 s** mesuré | inchangé |
| Contexte construit (serveur, sans modèle) | — | **≤ 0,15 s** |
| Premier jeton du cerveau | **8,7 s** mesuré | **≤ 0,6 s p50**, ≤ 1,0 s p90 (Haiku 5.5, préfixe en cache — à mesurer, non documenté) |
| Première phrase complète (+ contrôle D9) | — | **+0,2-0,4 s** |
| Premier octet audio (ElevenLabs Flash en flux) | ≈ 1 s après la phrase | **≤ 0,3 s** |
| **Premier son utile — réponse directe** | **9,7 s** | **≤ 1,5 s p50, ≤ 2,5 s p90** |
| **Premier son — délégation** (annonce précise) | 9,7 s (et générique) | **≤ 1,5 s p50** ; résultat = durée du tour Opus (inchangée), annoncé à la pause |
| Fin de réponse directe | 22,9 s | **≤ 4 s** (1-3 phrases) |
| Clip de réaction | systématique | seulement si rien n'est parti à **1,2 s** |

## Coût mensuel estimé (euros)

Hypothèse **usage soutenu** (celle de F-233) : 100 échanges vocaux/jour × 30 = **3 000/mois** ; par appel rapide :
≈ 3 000 jetons de consigne + outil **en cache**, ≈ 5 000 jetons de contexte et d'échanges **hors cache**, ≈ 120 jetons
de sortie. Taux de délégation supposé **50 %** (à mesurer).

| Poste | Haiku 5.5 (défaut) | Sonnet 5.5 (repli) |
|---|---|---|
| Par appel rapide | ≈ 0,0006 $ (≈ 0,00055 €) | ≈ 0,012 $ (≈ 0,011 €) |
| Cerveau rapide, 3 000 appels + pré-chauffages | **≈ 2-3 €/mois** | **≈ 33-38 €/mois** |
| Tours Opus | ≈ 1 500 délégués (inchangés, + ≈ 300 jetons de notes) | idem |
| Tours Opus **évités** (≈ 1 500 × 0,10-0,30 € estimés par tour vocal à 150 k jetons en cache) | **≈ 150-450 €/mois économisés** | idem |
| Voix (F-233) | ≈ +5-15 € (réponses plus nombreuses, plus courtes) | idem |
| Banc D16 (une fois, 60 cas jugés par Opus) | ≈ 2-5 € | ≈ 3-6 € |

**Net : +≈ 10-20 € de cerveau rapide et de voix, contre une économie estimée de 150-450 € de tours Opus.** L'économie
est un effet de bord, pas l'objectif (justesse avant coût) ; elle sera remplacée par la mesure (D14). Plafond propre
20 €/mois (D15), à porter à 50 € si Sonnet 5.5 est retenu.

## Risques et parades

| Risque | Parade |
|---|---|
| **Réponse fausse** du cerveau rapide | Délégation par défaut (D7) ; contrôle d'ancrage phrase par phrase (D9a) ; contexte horodaté et borné (D5) ; « vérifie » force Opus (D9d) ; banc avant activation (D16) ; corrections du PO mesurées (D14) ; échanges marqués « non vérifiés » pour Opus (D12) |
| **Action faite sans confirmation** | Aucun outil d'action (D8) ; confirmation en attente ⇒ route directe Opus (D8) ; banc : 0 action non déléguée (D16) |
| **Double réponse** (cerveau rapide + Opus disent la même chose) | Délégation ⇒ seule l'`annonce` est dite ; lignes 🎙 intermédiaires d'Opus muettes en mode délégué ; seule la 🎙 finale annoncée (D13) |
| **Ordre des annonces** (plusieurs délégations, PO qui parle) | File FIFO, à la pause, préfixe `label` ; jamais par-dessus le PO (D13) |
| **Contexte d'Opus pollué ou cache cassé** | Échanges rapides jamais rejoués comme tours ; note dans le message suivant, append-only (D12) ; consigne système d'Opus intouchée |
| **Cache du cerveau rapide inefficace** | Consigne + outil stables (≥ 512 jetons) ; volatil dans le dernier message (D6) ; pré-chauffage ; `cache_read_input_tokens` suivi au journal |
| **Raisonnement désactivé : appel d'outil écrit en texte** | Consigne recommandée par la référence API ; détection ⇒ délégation (D9c) |
| **Refus du modèle (Haiku : pas de repli serveur)** | Refus ⇒ délégation automatique (D9b) |
| **Délégation sur le mauvais sujet** | Sujet cible = sujet courant par défaut ; autre sujet seulement s'il est **nommé** dans la parole et présent dans l'univers ; l'`annonce` le dit (« Sur Edenred, je regarde… ») ; interruption « annule » (D8) |
| **Humour mal placé** (alerte, incident, client sensible, aux dépens de quelqu'un) | Au plus une touche, interdits explicites, note « ton sobre » posée par le serveur sur alerte/incident/erreur (D18) ; jeu de 20 questions (critère 12) ; réglage « aucun » par compte |
| **Imitation de l'œuvre** (marque, droits) | Aucune réplique ni nom (Stark, Iron Man, Marvel, Avengers) ; filtre serveur (D18) |
| **Personnalité qui casse le cache d'Opus** | Style dans `JarvisOralNote` (message), jamais dans la consigne système du terminal ; test d'égalité (critère 12) |
| **Formules creuses / répétitions** | Consigne + liste interdite + retrait serveur + anti-répétition (D11) ; mesure (D14) ; porte (D16) |
| **Interaction avec SF-233-11 (mains libres)** en cours | SF-235-05 démarre après le merge de SF-233-11 et s'y branche (chaque énoncé finalisé ⇒ `/converse`) ; vérifier `origin/main` avant |

## Découpage (≤ 2 j chacune)

Ordre : 01 → 02 → 03 → 09 → 04 → 05 → 06 → 07 → 08. Le mode actuel reste servi tant que `enabled=false` (01-07, 09).

| SF | Objet | Estim. |
|---|---|---|
| **SF-235-01** | **Le cerveau rapide derrière `AiAgentProvider`** : `AgentReasoning.off()` + mapping fournisseur (`disabled` Haiku 5.5, `between_tools` Sonnet 5.5) ; tarif `claude-haiku-5-5` ; `JarvisFastBrainProperties` (`enabled=false`, `model`, `effort`, `max-tokens`, `monthly-budget-eur`, `first-token-timeout`) ; `JarvisFastBrainService` (consigne stable versionnée D6, outil `deleguer` strict, flux) ; `POST /api/jarvis/converse` SSE (D4) ; quota + `recordAnnex` + plafond € (D15) ; 503 si éteint. Tests : unitaires du mapping (aucune requête existante modifiée), service (route réponse/délégation sur fournisseur simulé), isolation (sujet d'un autre compte ⇒ 404), plafond. | 2 j |
| **SF-235-02** | **Le contexte compact** : `JarvisFastContextBuilder` (D5 a-g) sous `user_id`, borné en jetons et en caractères, horodaté, ≤ 150 ms ; mémoire de session (30 min, 12 tours, mots seuls) ; pré-chauffage (D6). Tests : contenu par bloc, bornes, aucune donnée d'un autre compte, consigne système **identique octet pour octet** d'un appel à l'autre. | 1,5 j |
| **SF-235-03** | **Les garde-fous** : contrôle d'ancrage phrase par phrase (D9a), délégation automatique sur refus/erreur/délai/texte-outil (D9b-c), délégation forcée « vérifie / demande à Opus / c'est faux » (D9d), formules interdites + anti-répétition + retrait serveur (D11 a-f), compteurs. Tests : nombre/clé/nom inventé ⇒ délégation, nom présent ⇒ dit ; formule seule retirée, dans une phrase pleine conservée ; jamais deux remplissages identiques consécutifs. | 1,5 j |
| **SF-235-04** | **La délégation dans le fil** : `deleguer` ⇒ tour Opus via le chemin `streamChat` (`via=JARVIS`, sujet/chantier cible, `openOrSteer` : précision si vivant, parallèle entre fils) ; message = parole verbatim + reformulation + note des échanges rapides + `JarvisOralNote` (D10, D12) ; migration `atelier_messages.brain` ; échanges rapides écrits dans le fil, **exclus du rejeu**, note au tour suivant (aussi depuis le terminal) ; compagnon : pas d'annonce des réponses rapides. Tests : consigne système d'Opus inchangée, rejeu append-only (deux tours successifs : préfixe identique), précision vs parallèle, isolation. | 2 j |
| **SF-235-05** | **L'écran Jarvis à deux cerveaux** : `send()` → `/converse` (repli `streamChat` sur 503) ; réponses rapides dites au fil des phrases (`OralStream`) ; annonce de délégation ; **file d'annonces** de fin (FIFO, à la pause, `label`, « redis-moi ») ; 🎙 intermédiaires muettes en délégué ; confirmation en attente ⇒ route Opus directe ; « annule » ⇒ dernière délégation ; accusé supprimé, clips conditionnels (1,2 s) et variés sans répétition (jeu ≥ 6 clips vouvoyés côté `JarvisClipService`) ; `heardBeforeCut` transmis ; branchement mains libres SF-233-11 ; « Montrer » (`🖥 montrer:`) ; délégations vivantes visibles sous l'orbe. Tests : spec composant (ordre des annonces, pas de double réponse, confirmation, repli 503). | 2 j |
| **SF-235-06** | **Le fil, côté terminal** : badge « 🎙 via Jarvis · réponse rapide », « → délégué à Opus : <label> » avec lien vers le tour, encart « Pendant ton absence » (SF-231-06) qui distingue réponses rapides et délégations ; `DESIGN_SYSTEM` respecté. Tests : rendu, navigation. | 1 j |
| **SF-235-07** | **Mesure** : colonnes D14 sur `jarvis_turn_timings` (migration), marques écran (`brain`, `fast_first_token`), classement local des corrections, `GET /api/admin/jarvis/fast-brain/measure` (p50/p90 par cerveau, délégation, corrections, creuses/répétées avant/après retrait, coût €, tours Opus évités), purge 90 j existante. Tests : agrégats, filtre `user_id` sur l'écriture, accès ADMIN. | 1,5 j |
| **SF-235-09** | **La personnalité** (D18) — livrée **après SF-235-03, avant SF-235-05** : bloc « Personnalité » de la consigne système du cerveau rapide (deux variantes figées, aucun / léger) ; `JarvisOralNote` étendue (style de la ligne 🎙, deux variantes, écran sobre) — dans le message, consigne système du terminal intouchée ; note fixe « ton sobre » sur alerte / incident / erreur / échec ; filtre « aucune imitation » (Stark, Iron Man, Marvel, Avengers absents du contexte ⇒ phrase retirée) ; table `jarvis_preferences` + `GET/PUT /api/jarvis/preferences` (filtre `user_id`, défaut « léger ») + interrupteur dans `/jarvis` ; **jeu de 20 questions types** synthétiques (aucune donnée client, versionné en ressource de test) passé au cerveau rapide et jugé par Opus 5.5 (endpoint admin réutilisé par le banc D16). Tests : consignes système identiques octet pour octet par variante ; consigne du terminal inchangée ; note sobre posée sur alerte ; filtre ; isolation des préférences. | 1,5 j |
| **SF-235-08** | **Banc et mise en service** : `POST /api/admin/jarvis/fast-brain/bench` (D16 : 60 dernières demandes vocales du compte, dry-run, juge Opus 5.5, rapport) ; exécution sur Haiku 5.5 (puis Sonnet 5.5 si échec) ; si porte franchie, `enabled=true` et modèle retenu par défaut dans `application.yml` ; note d'exploitation (coupe-circuit, plafond, mesure J+7). Tests : banc sans écriture ni synthèse, jeu jamais persisté hors base. | 1,5 j |

**Total : 9 SF, ≈ 14,5 j.** Ordre de livraison : 01, 02, 03, **09**, 04, 05, 06, 07, 08.

## Critères d'acceptation (feature)

1. **Latence** : sur 20 tours vocaux réels après activation, premier son utile d'une réponse directe **≤ 1,5 s p50 et
   ≤ 2,5 s p90** depuis la fin de parole ; annonce d'une délégation **≤ 1,5 s p50** (`/api/admin/jarvis/fast-brain/measure`).
2. **Aucune action** n'est exécutée sans tour Opus : le cerveau rapide n'expose que `deleguer` (test sur la liste
   d'outils envoyée) ; banc D16 : 0 action non déléguée.
3. **Aucun fait non ancré dit** : tout nombre, clé de ticket ou nom propre absent du contexte et de la parole ⇒
   délégation (tests SF-235-03) ; banc : 0 après garde-fous.
4. **Délégation** : crée un tour Opus normal dans le fil du sujet cible, `via=JARVIS` ; consigne système d'Opus et
   modèle/effort **inchangés** (test d'égalité) ; un fil vivant reçoit une précision ; deux sujets ⇒ deux tours
   parallèles ; le PO peut parler pendant ce temps et obtient des réponses rapides.
5. **Annonces** : chaque fin de délégation est dite une fois, avec son `label`, jamais pendant que le PO parle ; deux
   fins proches sont dites dans l'ordre de fin ; aucune ligne 🎙 intermédiaire d'Opus n'est dite en délégué.
6. **Traçabilité** : chaque échange rapide est visible dans le fil (« réponse rapide ») et parvient à Opus au tour
   suivant par la note ; deux tours Opus successifs ont un préfixe rejoué identique (append-only).
7. **Pas simplet** (retour PO) : (a) aucune des formules interdites (D11d) n'est **dite seule** — 0 occurrence sur le
   banc et en prod après retrait ; (b) **taux de répétition** (phrase normalisée identique à l'une des 5 réponses
   précédentes de la session) **< 5 %** avant retrait sur le banc, **0 %** entendu ; jamais deux remplissages
   identiques consécutifs ; (c) toute `annonce` de délégation contient un objet de la demande (≥ 1 mot plein de la
   parole ou du contexte) ; (d) vouvoiement 100 %, « Monsieur » ≤ 1 par réponse.
8. **Mesure en prod** : la part des réponses rapides **jugées creuses ou répétées** (compteurs `fillers_removed` +
   `repeats_removed` rapportés aux réponses rapides) et le taux de **corrections du PO** sont lisibles à J+7 ;
   cibles : creuses/répétées **≤ 5 %** avant retrait, corrections **≤ 5 %** des réponses rapides.
9. **Coût** : chaque appel rapide au journal d'usage avec son modèle et son coût en € ; plafond 20 € atteint ⇒ mode
   actuel sans erreur orale.
10. **Coupe-circuit** : `APP_JARVIS_FAST_BRAIN_ENABLED=false` ⇒ comportement strictement identique à aujourd'hui
    (test e2e de l'écran sur 503).
11. **Isolation** : `/converse`, le contexte, la délégation et la mesure ne lisent ni n'écrivent rien d'un autre
    `user_id` (tests d'isolation par SF).
12. **Personnalité** (D18), sur le **jeu de 20 questions types** (dont ≥ 5 alertes / incidents / erreurs, ≥ 3 sujets
    client sensibles, ≥ 4 demandes d'action), réglage « léger », jugé par Opus 5.5 avec grille fixe :
    (a) vouvoiement **20/20** ; (b) **au plus une touche d'humour** par réponse, 20/20 ; (c) **zéro humour** sur les
    alertes, incidents, erreurs et sujets sensibles ; (d) réponses courtes : **≤ 3 phrases et ≤ 300 caractères**
    à l'oral, 20/20 ; (e) aucune mention de Stark / Iron Man / Marvel / Avengers ni réplique du film ; (f) réglage
    « aucun » ⇒ **zéro** touche d'humour sur les 20. Les tours Opus vocaux portent le style dans la ligne 🎙 et la
    consigne système du terminal est **identique** avant et après (test d'égalité).

## Préoccupations transversales

- **Plans / limites** : nouveau coût modèle hors tour. Composants impactés : `QuotaService.assertWithinQuota`,
  `UsageLedgerService.recordAnnex`, `ProviderCostCalculator` / grille de tarifs (`claude-haiku-5-5`),
  `JarvisFastBrainProperties.monthlyBudgetEur` (nouveau), `JarvisUsageService` (lecture du coût du mois),
  `CostBudget` (non bloquant, inchangé), plafond voix `JarvisVoiceProperties.monthlyBudgetEur` (inchangé).
- **Contexte tenant** : `/converse` et la délégation résolvent le sujet par `findByIdAndUserId` ; le constructeur de
  contexte appelle `JarvisUniverseService`, `JarvisReturnService`, les alertes F-230 et le fil, **tous** sous le
  `user_id` du principal ; le banc ne lit que les messages du compte appelant. Composants : `JarvisFastBrainService`,
  `JarvisFastContextBuilder`, `AtelierChatService` (chemin JARVIS), `LiveTurnRegistry.openOrSteer`,
  `JarvisAnnouncementService` (filtre `brain`).
- **Auth / Principal** : nouveaux endpoints authentifiés `POST /api/jarvis/converse`, `GET/PUT /api/jarvis/preferences` ; ADMIN :
  `GET /api/admin/jarvis/fast-brain/measure`, `POST /api/admin/jarvis/fast-brain/bench`. Aucun changement du
  Principal ; le jeton du compagnon (`X-Jarvis-Token`) n'ouvre toujours que les annonces.
- **Navigation / routing** : aucune nouvelle route ; vérifier `/jarvis`, `/jarvis?alerte=`, `/jarvis?source=compagnon`,
  lien « → délégué à Opus » vers `/atelier/{sujet}` (ancre du tour).

## Tables / endpoints / composants impactés

- **Tables** : `atelier_messages.brain` (nouvelle colonne, nullable) ; `jarvis_turn_timings` (colonnes D14) ;
  **`jarvis_preferences`** (nouvelle table, D18 : `user_id` clé, `humour`, `updated_at` ; purge à la suppression du
  compte). À reporter dans `ARCHITECTURE_CANONIQUE.md` au merge.
- **Endpoints** : `POST /api/jarvis/converse` ; `GET/PUT /api/jarvis/preferences` ; `GET /api/admin/jarvis/fast-brain/measure` ;
  `POST /api/admin/jarvis/fast-brain/bench`.
- **Backend** : `agent/AgentReasoning`, `agent/AnthropicAgentProvider` (mapping « désactivé »), `jarvis/fastbrain/*`
  (nouveau paquet), `AtelierChatService` (notes de message, exclusion du rejeu), `JarvisClipService` (jeu élargi),
  `JarvisAnnouncementService`, `jarvis/timing/*`, `application.yml`.
- **Frontend** : `jarvis.component.ts`, `jarvis-oral.ts` (accusé, clips, annonces), file d'annonces (nouveau),
  `atelier` (badge du fil), encart « Pendant ton absence ».

## Hors périmètre

- Changer le tour Opus (modèle, effort, consigne système, outils) — inchangé.
- Donner au cerveau rapide un outil de lecture (poste, fichiers, Jira) ou d'action — tout passe par `deleguer`.
- Annulation **ciblée** d'une délégation parmi plusieurs (« arrête la recherche Edenred ») : V1 = « annule » arrête la
  dernière ; le terminal reste disponible.
- Modèle parole-à-parole.
- Cerveau rapide pour le terminal écrit (le terminal reste 100 % Opus).
- iPhone.

## Décisions prises par défaut qui méritent l'œil du PO

| # | Défaut retenu | Alternative |
|---|---|---|
| D1 | **Haiku 5.5** (≈ 2-3 €/mois), Sonnet 5.5 en repli si le banc échoue | Sonnet 5.5 d'emblée (≈ 35 €/mois, latence plus haute, qualité supérieure) |
| D7 | Le cerveau rapide **répond seul** aux questions d'état déjà dans le contexte (où en est un chantier, ce qu'il y a à envoyer, ce qu'Opus vient de dire) | Tout déléguer sauf politesse et orientation (plus sûr, moins fluide) |
| D9 | Contrôle d'ancrage **strict** (nombre/nom absent ⇒ délégation) | Plus souple (risque de fait inventé) |
| D12 | Échanges rapides vus par Opus en **note** au tour suivant, pas rejoués comme des tours | Les rejouer (cache cassé, raisonnement écarté) |
| D13 | Lignes 🎙 intermédiaires d'Opus **muettes** en délégation | Les dire aussi (plus de vie, risque de doublon) |
| D15 | Plafond propre **20 €/mois** | Autre montant |
| D18 | Humour **« léger » par défaut** ; variantes figées (deux préfixes cachés) plutôt qu'un réglage dans le message ; « ton sobre » forcé par le serveur sur alerte/incident/erreur | « Aucun » par défaut ; humour laissé au seul jugement du modèle |
| D16 | Mise en service **conditionnée au banc** (juge Opus sur 60 demandes réelles) | Activation directe |
