# CADRAGE F-234 — L'écran vivant de Jarvis

**Statut :** cadrage du 2026-11-01 (go PO du 2026-11-01), précisions et arbitrages PO du même jour intégrés
(§0). Famille « Jarvis » : F-230, F-231, F-232, F-233, **F-234**.

**Demande PO (2026-11-01)** : l'écran `/jarvis` (F-231) ne montre qu'un « cerveau » (l'orbe neuronal). Il doit
**traduire ce qui se passe dans l'application** pendant qu'on parle à Jarvis : allumer le client, le sujet, le
chantier, l'étape dont Jarvis parle ; **montrer les actions** (un mail qui se prépare pour une personne, des tickets
consultés, une commande sur le poste…). « Plus joli, plus futuriste, un vrai coup de peps. »

**Proposition validée par le PO** : une **constellation** de son univers autour de l'orbe (clients → sujets →
chantiers → étapes ; le nœud dont il est question s'allume, un fil lumineux le relie à l'orbe, son nom en
surbrillance) et des **scènes** par type d'action.

**Règles qui bornent ce cadrage (non négociables)** :
- **Zéro coût IA, zéro impact sur le raisonnement** : tout est **déduit à l'écran** des événements déjà diffusés
  pendant un tour, des relevés déjà faits par l'application (notifications, terminaux vivants, état du poste) et du
  rapprochement de noms déjà connus. Aucun appel modèle ; aucune consigne, aucune description d'outil modifiée ⇒
  **préfixe de cache intact** (« justesse avant coût », « préfixe stable »).
- **Isolation `user_id`** : l'univers affiché ne vient que de lectures filtrées par l'utilisateur courant.
- **Rien ne sort de l'appareil** : le rapprochement de noms se fait dans le navigateur.
- **Ne pas reproposer l'existant** : la carte vivante (F-173/174) dessine l'infra d'un dépôt, pas l'univers du PO ;
  le tableau du client (F-208/F-220) liste les chantiers d'un client, sans lien avec la voix. Rien de livré ne fait
  ce que demande F-234 : `grep -i "constellation|surbrillance|scène" docs/PRODUCT_SPEC.md` → aucune occurrence
  pertinente ; dans `frontend/src/app/jarvis/jarvis.component.ts`, `streamChat(..., { onAction: () => undefined,
  onText: () => undefined, … })` — Jarvis **ignore** aujourd'hui les étapes et les deltas.

---

## 0. Précisions et arbitrages du PO (2026-11-01), intégrés

| # | Demande | Où c'est traité |
|---|---|---|
| P1 | Prendre **toute l'application** en jeu : inventaire exhaustif des features Terminées et des outils de l'agent, chacun reçoit une scène ou une famille, les écartés sont listés avec leur raison ; matrice « feature/outil → scène → données ». | §5 (outils), §6 (features), §6.3 (écartées) |
| P2 | **Priorité par usage réel**, mesuré en prod. Radar, Vigie, Teams et réunions (pas encore utilisés) en **dernière vague**, cadrés mais **développement facultatif**. | §2 (mesure), §11 (vagues) |
| P3 | **Une scène générique par défaut**, pilotée par un **registre** (outil ou famille → scène) : tout outil sans scène dédiée s'anime quand même (nom lisible, entité en surbrillance, pulsation). | §4, D3 |
| P4 | **Règle pour l'avenir** : toute feature qui ajoute un outil déclare sa scène dans le registre ou accepte explicitement la générique ; ligne de `review-checklist.md` proposée et rattachée à une SF. | §12, SF-234-02 |

---

## 1. Coordination avec F-233 (Jarvis temps réel)

Une vague F-233 modifie **en ce moment** `frontend/src/app/jarvis/` (`jarvis.component.ts`, orbe, écoute, voix).

- **Le dev de F-234 démarre après F-233**, au minimum après **SF-233-03** (lecture des deltas `onText`, file de
  phrases) et **SF-233-08/09** (interruption). F-234 **s'appuie sur ses événements** : la phrase en cours de
  diction (pour caler la surbrillance sur ce qui est **dit**), la coupure (la scène se fige puis s'efface), la
  reprise après acquiescement.
- F-234 **n'ouvre pas un second lecteur** des deltas : il s'abonne au tampon de texte que pose SF-233-03 (un seul
  endroit découpe la ligne 🎙 en phrases). Si SF-233-03 n'était pas livrée, SF-234-02 tiendrait son propre tampon,
  pur et testé, à fusionner ensuite.
- L'orbe reste celui de F-231 (Canvas 2D) ; F-234 l'entoure sans le réécrire. Toute retouche de
  `jarvis-orb.component.ts` par F-233 est reprise telle quelle.
- Readiness de chaque SF-234 : `git log origin/main -- frontend/src/app/jarvis/` et rebase sur l'état F-233 du
  moment.

---

## 2. Usage réel mesuré en prod (lecture seule, 2026-11-01)

Source : `atelier_messages.tool_trace` (appels d'outils des tours de la boucle maison) et `runner_audit` (commandes
exécutées sur les postes), **30 derniers jours** ; requêtes en lecture seule (`q-prod-ro.sh`). 1 058 réponses de
l'agent, dont 880 avec outils, sur 14 sujets. Jarvis n'est pas encore déployé (0 message `via='JARVIS'`).

| Famille | Outils (appels sur 30 j) | Total | Rang |
|---|---|---|---|
| Commande sur le poste | `bash` 3 668, `bash_output` 164, `kill_shell` 2 | **3 834** | 1 |
| … dont Jira / Confluence | commandes `bash` visant `jira`, `atlassian`, `confluence`, `acli`, `/rest/api/` (runner_audit) | **777** (≈ 14 % des `bash` du poste) | — |
| … dont git / AWS / Kubernetes | 912 / 868 / 420 (runner_audit) | — | — |
| Fichiers et recherche | `read_file` 408, `edit_file` 205, `multi_edit` 138, `write_file` 110, `grep` 79, `glob` 11, `list_files`, `search_files` | **≈ 950** | 2 |
| Chantier et étapes | `update_plan_step` 410, `set_subject_plan` 85, `submit_diagnosis` 25, `propose_guided_mode` 20, `set_plan` 17, `update_sub_steps` 6, `reopen_investigation` 1 | **564** | 3 |
| Questions et « oui » | `suivre_question` 133, `demander` 115, `decouper_demande` 22 | **270** | 4 |
| Attentes | `record_blocker` 132, `close_blocker` 64, `update_blocker` 58 | **254** | 5 |
| Messages à envoyer | blocs ` ```message ` 169, ` ```mail ` 24 (réponses), `email_me` 24 | **217** | 6 |
| Création | `page_publish` 78, `page_pdf` 9, `render_diagram` 30, vidéo (`video_render*`, `video_publier`, `video_images*`, `video_briques`) 86, `voix_off` 1, `build_spreadsheet` 3 | **≈ 207** | 7 |
| Web (outils serveur) | `web_search` : 127 requêtes (`atelier_turn_measures.web_search_requests`) | **127** | 8 |
| Mémoire et savoir | `recall` 89, `carte_chercher` 23, `gouvernance_proposer` 34, `skill` 7 | **153** | 8 bis |
| Délégation | `explore` 75, `task` 12 | **87** | 9 |
| Passation / aiguillage | `ouvrir_sujet` 12, `create_subject` 6 | **18** | 10 |
| Preuves, besoins, divers | `capturer_preuves` 6, `signaler_besoin` 5, `sujets_etat` 1 | 12 | 11 |
| **Radar, Teams, Vigie, réunions** | `radar_*` 0, `teams_*` 0 dans les tours (seule la collecte de nuit `teams_radar_collect` tourne : 30) | **0** | dernier |
| Veilles, routines | `veille_poser`, `routine_proposer` : 0 (F-230 non déployée) | 0 | — |

**Lecture** : le PO vit dans le **terminal du poste** : commandes (dont Jira/Confluence), fichiers, chantiers et
étapes, questions, attentes, messages à envoyer. C'est l'ordre des vagues (§11).

> Les lignes `governance_map_read`, `bootstrap`, `screen_list_*` de `runner_audit` sont des appels **du harnais**
> (carte injectée, écran Fichiers), pas des outils de l'agent : aucune scène.

---

## 3. Cartographie : ce que l'écran peut savoir sans rien demander à l'IA

### 3.1 Entités affichables (l'« univers »)

| Entité | Ce qu'on affiche | D'où elle vient aujourd'hui | Isolation |
|---|---|---|---|
| **Client** = poste (runner host) | nom, pastille d'identité §9, état de mission (F-60), connecté ou non | `GET /api/runner-hosts/overview` ; `WorkspaceSummary.hostName/hostId/hostMissionStatus` ; présence `HostPresenceService` | filtré `user_id` |
| **Terminal du poste** (F-74) | nœud central du client | `WorkspaceSummary` (`hostTerminal`) | filtré `user_id` |
| **Sujet** = workspace | nom, client | `GET /api/workspaces` (déjà chargé par `/jarvis`) | filtré `user_id` |
| **Chantier** | « Chantier N — titre », phase, `done/total`, focus, `live`, qui débloque, autonomie (F-219) | `GET /api/runner-hosts/{hostId}/chantiers` (`ClientBoard`, F-208/F-220) | `service.board(currentUser.requireId(), hostId)` |
| **Étape** | étape courante (`nextStep.title`, rang) ; étapes du chantier | `ClientBoardChantier.nextStep` ; `GET /api/workspaces/{id}/journey` | filtré `user_id` |
| **Interlocuteur** | nom + variantes (rapprochement), rôle | `GET /api/workspaces/{id}/interlocuteurs/board` (F-180) | filtré `user_id` |
| **Attente** | personne, catégorie, chantier | `ClientBoard.chantiers[].unblocker`, `horsChantier[]` (F-175, F-220) | filtré `user_id` |
| **À envoyer (n)** | compteur + destinataires non envoyés | `GET /api/jarvis/to-send?hostId=` (SF-231-06) | filtré `user_id` |
| **Veille** | plateforme, client, sujet | `/veilles` (F-230), alerte `GET /api/jarvis/alerts/{id}` | filtré `user_id` |

Charger cela avec l'existant coûterait des dizaines d'appels sur téléphone ⇒ **D4** : un agrégat en lecture seule.

### 3.2 Ce que contient exactement un événement de tour (vérifié dans le code)

- `action` : `AtelierStepEvent(type, path, question)` (`AtelierProgressListener.java:21`), produit par
  `AtelierChatService.stepFor(call)` (`~l.7680`) **avant** l'exécution. `type` = type d'étape (`read`, `write`,
  `list`, `search`, `recall`, `bash`, `explore`, `task`, `video`) **ou le nom brut de l'outil** (cas `default`) ;
  `path` = chemin, motif, commande tronquée à **200 caractères** (`STEP_COMMAND_CHARS`) ou **cible d'audit**
  (`auditTarget`, `~l.9137`) — **`null` pour la plupart des outils métier**. Ni identifiant d'appel, ni entrée brute.
  Côté écran : `AtelierStreamAction { type, path?, output?, question? }` (`atelier.models.ts:593`).
- `text` : deltas de la réponse (ligne 🎙, blocs ` ```mail ` / ` ```message ` de F-203 avec « À : … » et
  « Objet : … » en tête, balises `attente=` et `preuves=` — `MESSAGE_BLOCK_DOCTRINE`).
- `output` : sortie en flux d'une commande `bash` (F-38).
- `confirm_request` (`tool`, `detail`, `timeoutMs`) / `confirm_resolved` ; `question_request` / `question_state` /
  `question_resolved` (F-164) ; `questions` (F-210) ; `plan` (F-39) ; `card` (F-89, blocs Teams) ; `compaction`,
  `recall` (F-162) ; `progress` (jetons cumulés) ; `started` ; `done` (`nextSteps` F-192, `nextStepsChantier`
  F-196/F-209, `costEur` F-133 réservé à l'administrateur, `planSubmitted`).
- **Outils serveur** `web_search` / `web_fetch` (`AnthropicAgentProvider.java:78`) : **aucun événement `action`**
  (ils ne passent pas par `stepFor`) ⇒ invisibles aujourd'hui.

### 3.3 Sources hors tour, déjà relevées par l'application (aucun appel nouveau)

| Source | Relevé existant | Ce qu'on en tire |
|---|---|---|
| Notifications (F-153, F-185, F-204, F-217) | `NotificationCenterService` : relevé toutes les 30 s, onglet visible ; `ACTION_EVENTS` (`AUTHORIZATION_REQUESTED`, `QUESTION_ASKED`, `PLAN_AWAITING`, `VALIDATION_AWAITING`…) | un autre sujet appelle : son nœud clignote une fois, cloche |
| Terminaux vivants (F-70, F-76, F-84, F-195) | `LiveTerminalService` → `GET /api/terminals/live` | les sujets qui travaillent **en parallèle** respirent |
| Présence du poste (F-38, F-97, F-161, F-194) | `HostPresenceService` ; `onError(code, reason)` de la porte du runner (F-161) | client allumé / éteint ; « le poste ne répond pas » |
| Alerte de veille (F-230) | `/jarvis?alerte=` + `GET /api/jarvis/alerts/{id}` | la veille sonne sur son client |

---

## 4. Le registre des scènes et la scène générique (P3)

- **Un registre unique** `frontend/src/app/jarvis/scenes/jarvis-scene-registry.json` (fichier **JSON**, lisible
  aussi par un test passerelle, cf. §12) : `outil` **ou** `préfixe de famille` (`teams_`, `radar_`, `video_`…) **ou**
  `type d'étape` (`read`, `bash`…) → `{ scene, libelle }`. `scene` vaut un identifiant de scène dédiée **ou**
  `GENERIQUE`. Le lecteur cherche : nom exact → préfixe → type d'étape → **générique**.
- **Scène générique (S-GEN)** — appliquée automatiquement à tout outil absent du registre ou déclaré `GENERIQUE` :
  1. **nom lisible** : `libelle` du registre, sinon le nom de l'outil « humanisé » (`update_blocker` → « update
  blocker ») — jamais un nom technique quand un libellé existe ;
  2. **entité en surbrillance** : la cible (`path`) rapprochée de l'univers (D2), sinon le **sujet courant** ;
  3. **pulsation** : un anneau autour de l'orbe pulse une fois par appel ; les rafales du même outil se regroupent
  (« 4 × Radar »).
  ⇒ une fonction activée plus tard (Radar, Vigie, Teams, réunions, tout outil futur) **s'anime dès le premier jour,
  sans développement**.
- Une scène dédiée **remplace** la générique pour son outil ; si elle échoue (donnée absente), elle **retombe**
  sur la générique, jamais sur un écran vide.

---

## 5. Matrice outils de l'agent → scène → données

Légende « Données dans l'événement » : **oui** · **partiel** · **non** · **à enrichir** (SF-234-04, étiquettes
passerelle). Usage = appels sur 30 j (§2). Inventaire tiré de `stepFor`, des `*ToolCatalog` et des `AgentTool`
déclarés dans `AtelierChatService`.

| Outil(s) | Usage | Scène | Données dans l'événement |
|---|---|---|---|
| `bash`, `bash_output`, `kill_shell` | 3 834 | **S3 Terminal en filigrane** | oui : commande (200 c.) + `output` en flux |
| `bash` visant Jira / Confluence (`jira`, `atlassian`, `confluence`, `acli`, `/rest/api/`) | 777 | **S4 Cartes de tickets** | partiel : clés `ABC-123` dans la commande ou le texte (heuristique) |
| `read_file`, `list_files` | ≈ 410 | **S5 Feuillets** (lecture) | oui : chemin |
| `write_file`, `edit_file`, `multi_edit` | 453 | **S5 Feuillets** (écriture, F-37) | oui : chemin |
| `grep`, `glob`, `search_files` | ≈ 95 | **S5 Loupe** | oui : motif |
| `update_plan_step`, `update_sub_steps` | 416 | **S9 L'étape qui avance** | **à enrichir** (`path=null`) ; oui en fin de tour (`done.nextStepsChantier`) |
| `set_subject_plan`, `set_plan`, `valider_plan`, `exit_plan_mode` | 102 | **S9 Le plan se pose** | `plan` : oui (`onPlan`) ; `set_subject_plan` : à enrichir |
| `submit_diagnosis`, `confirmer_diagnostic`, `reopen_investigation`, `propose_guided_mode` | 46 | **S9 Phase du chantier** (investigation → plan → exécution → vérification, F-176) | à enrichir |
| `lancer_chantier_autonome` | — | **S9 Autonomie** (le losange part en orbite propre, F-219) | à enrichir |
| `demander` (+ `question_request`) | 115 | **S2 Question posée** | oui |
| `confirm_request` (autorisation F-33/F-47, actions qui engagent SF-231-04) | — | **S2 Le oui attendu** (orange) | oui : `tool`, `detail`, `timeoutMs` |
| `decouper_demande`, `suivre_question` (+ `questions`) | 155 | **S14 Points de route** | oui (`onQuestions`) |
| `record_blocker`, `update_blocker`, `close_blocker` | 254 | **S11 Attente posée / levée** | **à enrichir** (personne, catégorie) |
| blocs ` ```mail ` / ` ```message ` (texte) | 193 | **S1 Enveloppe / bulle** | oui (texte : À, Objet, fin de bloc, `attente=`, `preuves=`) |
| `email_me` | 24 | **S1 Enveloppe « pour toi »** | oui : `Courriel · <objet>` |
| `capturer_preuves` (+ `proof_run`) | 6 | **S1 Preuve jointe** (F-212 : l'objectif photographie) | partiel : titre |
| `page_publish`, `page_pdf`, `page_lire`, `pages_lister` | 87 | **S7 Atelier de création — page** | oui : titre |
| `render_diagram`, `generate_image` | 30 | **S7 — diagramme / visuel** (F-142) | image : oui ; diagramme : à enrichir |
| `build_presentation`, `presentation_publish`, `build_document`, `build_spreadsheet` | 3 | **S7 — document Office** | à enrichir (titre) |
| `video_briques`, `video_render`, `video_render_status`, `video_render_check`, `video_publier`, `audio_publier`, `video_devis`, `video_retours`, `voix_off` | 86 | **S7 — salle de montage** (F-213/214/215/216) | oui pour `video_*` et `voix_off` (étiquette existante) ; `video_devis`/`video_retours` : générique |
| `video_images`, `video_images_status` | 15 | **S5b Regarder une vidéo** (F-190 : pellicule qui défile) | oui : mode, fichier, son |
| `web_search`, `web_fetch` (serveur) | 127 | **S6 Page web** | **non → à enrichir** (requête ; domaine seul) |
| `recall` (+ `recall`, `compaction`) | 89 | **S12 Mémoire** (« tour 34 » remonte) | oui |
| `carte_chercher`, `reclass_entry` | 23 | **S12 Carte du savoir** (F-173/174) | `carte_chercher` : oui ; `reclass_entry` : générique |
| `gouvernance_proposer`, `skill` | 41 | **S12 Gouvernance** (règle/skill/gabarit proposés, F-177) | à enrichir (nom du skill, type) |
| `explore`, `task` (+ agents nommés F-182) | 87 | **S13 Essaim** (satellites de l'orbe) | oui : question / consigne |
| `ouvrir_sujet`, `create_subject` | 18 | **S10 Passation** (le fil glisse vers l'autre sujet) | **à enrichir** (nom du sujet) |
| `sujets_etat`, `compte_consommation`, `radar_resume`, `radar_sujets` (outils « poste », F-178) | 1 | **S15 Le point** (tour d'horizon : tous les sujets s'allument en vague) | oui (type) |
| `signaler_besoin` | 5 | **S16 Besoin noté** (F-205) | générique + type |
| `routine_proposer` | 0 | **S17 Routine proposée** (F-183) | générique |
| `veille_poser` | 0 | **S17 Veille posée** (F-230) | à enrichir (plateforme) |
| `radar_*` (9 outils : sujets, engagements, transcription, fusion…) | 0 | **S18 Radar** (balayage) — **vague D** | oui (`RadarToolCatalog.stepTarget`) ; générique en attendant |
| `teams_*` (30 outils : lecture, recherche, réunions, captures, fichiers M365 F-108) | 0 | **S19 Teams / Vigie / réunions** — **vague D** | partiel (`champ=valeur`, identifiants) ; générique en attendant |
| tout outil futur ou inconnu | — | **S-GEN** | oui (nom + `path`) |

> **Hors agent** : les outils MCP de `fr.claudegateway.mcp.tool.*` (`radar_resume`, `terminal_ecrire`,
> `courriel_m_envoyer`…, F-112) servent des **clients MCP externes** ; ils n'émettent rien vers `/jarvis`. Écartés.

---

## 6. Matrice des features → scène

Toutes les features de `docs/PRODUCT_SPEC.md` au 2026-11-01. « Données » : oui / non / à enrichir, au sens du §5 ;
« hors tour » = relevé existant du §3.3.

### 6.1 Features qui reçoivent une scène ou alimentent la constellation

| Features | Scène / rôle | Données |
|---|---|---|
| F-48, F-49, F-60, F-68, F-71, F-72, F-74, F-98 (postes, missions, Hébergé, terminal du poste, Forge) | **Constellation** : clients (postes), état de mission, terminal du poste au centre | oui (univers, D4) |
| F-141, F-195, F-198, F-199 (aiguilleur, plusieurs sujets, changer de sujet, fil d'un autre client) | **Constellation** : sujets ; toucher un sujet = changer de sujet | oui |
| F-206, F-208, F-209, F-220, F-221, F-226, F-227 (chantiers multiples, tableau des chantiers, suites, tableau du client, split, chantier parallèle, actions sous leur chantier) | **Constellation** : chantiers et étapes ; **S9** | oui (univers) ; en tour : à enrichir |
| F-176, F-196, F-219 (parcours du sujet, Cap au service du chantier, autonome) | **S9** phase / étape / autonomie | à enrichir ; `done` : oui |
| F-192 (Cap : suites) | **S9** : les suites proposées s'inscrivent sous le chantier en fin de tour | oui (`done.nextSteps`) |
| F-39 (harnais : plan), F-121 (plan soumis) | **S9 Le plan se pose** | oui (`plan`, `planSubmitted`) |
| F-38, F-31, F-73, F-121 (commandes, git, runner non confiné, commandes de fond) | **S3** | oui |
| F-180 (accès et interlocuteurs) | **S4** (accès Jira/Confluence) + **constellation** (interlocuteurs) | partiel / oui |
| F-28, F-30, F-37, F-86 (Atelier, terminal, voir les modifications, Word) | **S5** | oui |
| F-33, F-47, F-164, F-231 (SF-231-04) (validation, autorisation impossible à manquer, questions structurées, oui oral) | **S2** | oui |
| F-210 (deux questions, deux fils) | **S14** | oui |
| F-154, F-175, F-191 (actions à faire, fil des attentes, Fil) | **S11** + nœud de la personne | à enrichir |
| F-110, F-203, F-211, F-212, F-231 (SF-231-06) (courriel, encart Copier, marqué envoyé, preuves, À envoyer) | **S1** ; « Marquer envoyé » vu au retour (compteur À envoyer décrémenté au rafraîchissement de l'univers) | oui |
| F-109, F-184, F-142, F-129 (pages, PDF, diagrammes et visuels, présentations) | **S7** | oui / à enrichir |
| F-213, F-214, F-215, F-216 (vidéos, retours à la seconde, cours vidéo et voix off, coût d'une vidéo) | **S7 salle de montage** | oui |
| F-190 (regarder une vidéo) | **S5b** | oui |
| F-121 (outils serveur web) | **S6** | à enrichir |
| F-162, F-117 (recall, compaction, passation de contexte) | **S12 Mémoire** ; « passation » du fil : **S12** (le résumé se plie) | oui (`compaction`, `recall`) |
| F-92, F-125, F-135, F-136, F-137, F-139, F-140, F-166, F-173, F-174 (carte du poste, faits, savoir durable, carte vivante et qui répond) | **S12 Carte du savoir** | partiel (`carte_chercher` oui ; injection de carte : harnais, aucune scène) |
| F-51, F-52, F-75, F-96, F-165, F-177 (gouvernance, slash/skills) | **S12 Gouvernance** | à enrichir |
| F-179 (ouvrir le sujet d'un « go ») | **S10 Passation** | à enrichir |
| F-35, F-84, F-149, F-150, F-182 (sous-agents, délégation, `task` isolé, agents nommés) | **S13 Essaim** | oui |
| F-70, F-76, F-83, F-195 (terminaux vivants, mosaïque, parallélisme) | **A2 hors tour** : les autres sujets qui travaillent respirent | oui (hors tour) |
| F-38, F-44, F-45, F-46, F-82, F-97, F-111, F-161, F-194 (runner, mise en service, arrêt, statut, mise à jour, porte, jeton glissant) | **A1 hors tour** : client connecté / éteint ; « poste injoignable » à la porte | oui (hors tour + `onError`) |
| F-153, F-185, F-204, F-217 (notifications, cloche, sonnerie) | **A3 hors tour** : un autre sujet appelle | oui (hors tour) |
| F-16, F-36, F-42, F-61, F-63, F-133, F-143, F-224 (coût en €, plafonds, quota) | **A4** : en fin de tour, « ≈ 0,42 € » discret sous l'orbe (administrateur seulement, `costEur`) ; quota atteint = refus déjà dit par Jarvis | oui (admin) |
| F-155, F-225, F-178 (bilan, point de situation, terminal central voit tout le poste) | **S15 Le point** | oui (type d'outil) |
| F-156, F-157 (auto-diagnostic du produit, lecture du code par le diagnostic), F-205 (registre des besoins) | **S16** (générique libellée) | générique |
| F-183 (routines), F-230 (veilles) | **S17** posée ; **A5 La veille sonne** | générique / à enrichir ; alerte : oui |
| F-231, F-232, F-233 (Jarvis, compagnon, temps réel) | **socle** : l'écran lui-même ; synchronisation voix (SF-234-11) | — |
| F-99, F-100, F-101, F-102, F-103, F-104 (Radar et résumé du matin) | **S18 Radar et brief** — vague D | oui (étiquette existante) ; générique d'ici là |
| F-87, F-88, F-89, F-90, F-91, F-106, F-108, F-122, F-128, F-147, F-113 (Teams, Vigie, M365, réunions capturées et déposées, banc d'essai) | **S19 Teams / Vigie / réunions** — vague D | partiel ; générique d'ici là |

### 6.2 Récapitulatif des scènes

| Scène | Une ligne | Vague |
|---|---|---|
| **Constellation** | clients → sujets → chantiers → étapes autour de l'orbe ; le nœud cité s'allume, fil lumineux, nom en surbrillance | A |
| **S-GEN Générique** | nom lisible de l'outil, entité en surbrillance, pulsation d'anneau ; tout outil sans scène | A |
| **S3 Terminal en filigrane** | la commande et ses 2 dernières lignes défilent en mono, secrets masqués | B |
| **S4 Cartes de tickets** | cartes Jira/Confluence en éventail avec leurs clés | B |
| **S5 Feuillets / loupe** | les fichiers lus/écrits s'empilent (« 6 fichiers lus »), le motif cherché sous une loupe | B |
| **S9 L'étape qui avance** | le losange du chantier s'allume, l'étape se coche, la phase change, le plan se pose | B |
| **S11 Attente posée / levée** | un nœud de personne s'attache au chantier (ou s'en détache) | B |
| **S2 Le oui attendu / question** | bandeau orange, compte à rebours, l'orbe se fige : la seule scène qui demande | B |
| **S14 Points de route** | une puce par question du tour, qui se remplit quand elle est traitée | B |
| **S1 Enveloppe / bulle** | l'enveloppe se forme, destinataire et objet s'écrivent, elle file dans « À envoyer (n) » | B |
| **S6 Page web** | une barre d'adresse stylisée affiche la requête ou le domaine | C |
| **S7 Atelier de création** | la page, le diagramme, le document ou la vidéo prend forme sous son titre | C |
| **S5b Regarder une vidéo** | une pellicule défile, plans extraits | C |
| **S10 Passation** | le fil lumineux glisse d'un sujet à l'autre | C |
| **S12 Mémoire / savoir / gouvernance** | « tour 34 » remonte ; la carte du savoir s'ouvre ; une règle proposée s'inscrit | C |
| **S13 Essaim** | une orbe satellite par sous-agent, avec sa question | C |
| **S15 Le point** | tous les sujets du client s'allument en vague | C |
| **S16 Besoin / diagnostic** | générique libellée « Besoin noté » / « Diagnostic » | C |
| **S17 Veille ou routine posée** | une horloge s'attache au sujet | C |
| **A1 Poste** | le client s'allume s'il est connecté, s'assombrit sinon | C |
| **A2 Terminaux vivants** | les autres sujets qui travaillent respirent | C |
| **A3 Cloche** | un autre sujet appelle : il clignote une fois | C |
| **A4 Coût du tour** | montant en € discret en fin de tour (administrateur) | C |
| **A5 La veille sonne** | onde du client vers l'orbe à l'ouverture `?alerte=` | C |
| **S18 Radar et brief** | balayage radar sur le client, sujets Radar en écho | D (facultatif) |
| **S19 Teams / Vigie / réunions** | bulles de conversation, réunion en cours (point rouge), transcription qui défile | D (facultatif) |

### 6.3 Features écartées (aucune scène), avec la raison

| Features | Raison |
|---|---|
| F-01, F-03, F-11, F-12, F-29, F-53, F-62, F-189 | Compte, clé, accueil, identité publique, codes d'accès, marque : rien ne se passe pendant un tour. |
| F-02, F-04, F-23, F-24, F-25, F-13, F-14, F-26 | Chat classique (autre écran) : upload, dossier, bibliothèque, gabarits, export, copie de bloc — hors du flux du terminal. |
| F-05, F-06, F-07, F-08, F-15 | Pipeline documentaire (OCR/RAG) : écran Documents, asynchrone, pas d'événement de tour. |
| F-09, F-10, F-21, F-40, F-41, F-43, F-64, F-65, F-66, F-67, F-107, F-124 | Abonnement, quotas, facturation, offres, TJM/CRA : écrans de gestion ; le coût du tour reste couvert par A4. |
| F-19, F-20, F-27, F-56, F-58, F-151, F-152, F-158, F-159, F-160, F-197, F-200, F-207, F-223 | Coquille, admin, charte, responsive, PWA, lisibilité : règles **appliquées** par F-234, pas des scènes. |
| F-22, F-105, F-144, F-163, F-181 | Refondue, retirée, remplacée ou abandonnée. |
| F-17, F-18 | V3, hors périmètre. |
| F-34, F-138, F-218, F-222 | Instructions, profils métier, mode/profil offensif : **réglages** de l'agent, aucun événement propre. |
| F-50, F-57, F-59, F-69, F-77, F-78, F-79, F-80, F-81, F-85, F-93, F-95, F-132, F-170, F-186 | Points de contrôle, transparence, relais, suppression, santé, registre, proxy TLS, contrat, fichier refusé, promotion, intégrité, observabilité, résilience du flux, journaux : mécanique interne ou écrans de gestion. |
| F-94, F-118, F-119, F-120, F-130, F-134, F-148, F-167, F-168, F-171, F-172, F-188, F-229 | Juge, effort, justesse, cache, préfixe, modèle, gros tours : **qualité du raisonnement** — invisible par nature ; les rendre visibles inventerait une information. |
| F-32, F-116, F-126, F-131, F-145, F-146 | Interrompre, mot à mot, réponse essentielle, rejouer, dicter, long collage : **mécanismes** que Jarvis utilise déjà (F-231/F-233), pas des scènes. |
| F-54, F-55, F-112 | Chatbot d'aide, assistant proxy, serveur MCP : hors du terminal. |
| F-115, F-169, F-201, F-228 | Pièces jointes : Jarvis n'en joint pas (la voix ne dépose pas de fichier). |
| F-193, F-202 | Plume : enrichit une demande **tapée** avant envoi ; le flux vocal ne passe pas par Plume. |

---

## 7. Décisions

| # | Décision | Pourquoi |
|---|---|---|
| D1 | **Tout est déduit à l'écran.** Un **lecteur de scène** pur (`JarvisSceneReader`) consomme les événements du tour et les relevés existants (§3.3), consulte le **registre** (§4), et produit un état `{ focus: nœuds allumés, scène, libellé, détail }`. Aucun appel modèle ; aucun appel réseau pendant le tour. | Zéro coût IA, zéro impact raisonnement |
| D2 | **Rapprochement de noms prudent.** Normalisation (casse, accents, apostrophes), **frontière de mot**, longueur ≥ 3, variantes connues (F-180), « chantier N » reconnu ; un nom **ambigu** (deux nœuds) ou **exclu** (mots courants : « web », « api », « test »…) n'allume rien. **Priorité** : action en cours > ligne 🎙 > corps. **Au plus 3 nœuds allumés**, extinction douce après ~6 s. Un faux positif coûte une lueur, un faux négatif rien. | Justesse de l'affichage, sobriété |
| D3 | **Registre + scène générique** (§4) : JSON unique, recherche nom → préfixe → type → `GENERIQUE` ; une scène dédiée retombe sur la générique si sa donnée manque. | P3 : rien ne reste muet, rien à développer pour animer un outil neuf |
| D4 | **Un agrégat en lecture seule** `GET /api/jarvis/universe` : clients (état de mission, connecté) → sujets → chantiers ouverts (numéro, titre, phase, étape courante, `done/total`, `live`, attentes : personne) + interlocuteurs (noms, variantes) + destinataires « À envoyer » ; **construit sur les services existants**, filtre `user_id`, bornes (≤ 12 clients, ≤ 40 sujets/client, ≤ 10 chantiers/sujet), **aucun contenu** (ni message, ni corps, ni sortie). Chargé à l'ouverture, rafraîchi à chaque `done`. | 1 appel au lieu de dizaines sur téléphone |
| D5 | **Étiquettes de scène côté passerelle (additif)** : `stepFor` remplit `path` pour les outils aujourd'hui muets (§5 « à enrichir ») avec une **étiquette** courte — « chantier 3 · étape 2 », phase, nom du sujet ouvert, nom du skill, plateforme d'une veille, titre d'un document ou d'un diagramme, personne et catégorie d'une attente — et émet une étape `web` pour `web_search` (requête) / `web_fetch` (domaine seul). **`auditTarget` (journal d'audit) inchangé** ; aucune consigne, aucune description d'outil touchée ⇒ préfixe byte-stable (test F-134). Le terminal en profite aussi. | Rend visibles les actions les plus fréquentes (chantier, attentes) en cours de tour |
| D6 | **Rendu : Canvas 2D pour l'orbe (inchangé), SVG + CSS pour la constellation et les scènes. Pas de WebGL.** À ≤ ~60 nœuds visibles, le SVG garde des noms nets, accessibles, et anime `transform`/`opacity`/`stroke-dashoffset` sur le compositeur ; WebGL coûterait batterie, poids et complexité sans gain visible. Disposition **radiale déterministe** (calculée une fois, pas de simulation de forces) : le PO apprend sa carte. Boucles suspendues onglet caché. | Performance mobile, lisibilité |
| D7 | **Téléphone d'abord, focus + contexte.** < 768 px : seul le **client du sujet courant** déploie ses sujets en arc ; les autres clients sont des points nommés en lisière ; un chantier ou une étape n'apparaît que lorsqu'il est cité. Desktop : tous les clients en couronne. Un **fil d'Ariane** texte (« CAGIP › Migration Forge › Chantier 3 › Étape 2 ») dit toujours la même chose que la constellation. | Sobriété, lisibilité |
| D8 | **Charte : aucune couleur nouvelle.** Fond navy existant de `/jarvis` ; nœuds au repos `--cg-cream` à 35 % ; nœud cité et fil `--cg-accent` / `--cg-orange-2` ; client = pastille d'identité §9 (seul usage autorisé de ces tons : qualifier le poste) ; noms Inter, titres de scène Space Grotesk, commandes et clés JetBrains Mono. **La couleur ne porte jamais seule l'information** : la forme dit le type, le nom est écrit. Nouvelle section **DESIGN_SYSTEM §22 « Jarvis »** (contrastes mesurés sur navy). | `DESIGN_SYSTEM.md` §2, §3, §9 |
| D9 | **Mouvement réduit** (`prefers-reduced-motion`) : aucune animation de déplacement ni de tracé ; le nœud passe allumé d'un coup, la scène apparaît fixe ; l'orbe garde son comportement F-231. **Mode sobre** (interrupteur par appareil, `localStorage` protégé par try/catch) : orbe + fil d'Ariane seuls. | Accessibilité, choix du PO |
| D10 | **Accessibilité** : constellation `aria-hidden` ; l'équivalent accessible est le fil d'Ariane + une région `aria-live="polite"` **limitée** (au plus une annonce toutes les ~4 s, jamais pendant que Jarvis parle) ; nœuds de sujet atteignables au clavier ; cible tactile ≥ 44 px. | WCAG |
| D11 | **Scène de commande prudente** : la commande s'affiche (déjà visible au terminal) ; sortie limitée aux **2 dernières lignes**, 80 c., **masquage** des motifs de secret (jetons, `Authorization:`, `password=`, clés `sk-…`) ; le mode sobre la retire. | Écran de téléphone vu par d'autres |
| D12 | **Toucher un nœud de sujet = « passe sur X »** (`chooseSubject` existant, aucune route nouvelle) ; client et chantier non touchables (D-PO-1). | Pas de nouvelle navigation |
| D13 | **Synchronisation avec la voix (F-233)** : la constellation suit le texte **écrit** (dès qu'un nom passe) ; la surbrillance du nom suit la phrase **dite** (file de SF-233-03) ; à une coupure (SF-233-08), la scène se fige 1 s puis s'efface. | Ce qu'on voit = ce qu'on entend |
| D14 | **Une seule scène à la fois** ; la plus récente gagne, sauf **S2** qui reste devant tant qu'elle est ouverte. Durée minimale ~1,2 s ; rafales du même outil regroupées. Les scènes hors tour (A1-A5) ne s'affichent **qu'au repos** (aucun tour en cours), sauf A5 à l'ouverture par alerte. | Sobriété |
| D15 | **Priorité par usage réel** (§2) : vagues A (socle) → B (usage fort) → C (usage moyen + ambiance) → D (Radar, Vigie, Teams, réunions : **facultative**, la générique les couvre en attendant). | P2 |
| D16 | **Mesure d'intérêt sans contenu** : compteurs par jour ajoutés à `jarvis_usage_days` (migration additive) — scènes montrées par famille, part de la générique, nœuds allumés, touchers de nœud, mode sobre. `GET /api/admin/jarvis/measure` étendu. J+7/J+14 : une famille jamais montrée n'est pas enrichie ; une générique très fréquente sur un outil = candidat à une scène dédiée. | Proposer depuis l'usage observé |
| D17 | **Règle pour l'avenir** (§12) : tout outil ajouté à l'agent déclare sa scène dans le registre ou accepte `GENERIQUE` ; une **garde automatique** (test passerelle) échoue si un outil offert à l'agent est absent du registre. | P4 |

### Décisions à faire trancher par le PO

| # | Question | Défaut proposé |
|---|---|---|
| **D-PO-1** | Toucher un **client** ou un **chantier** : rien, ou ouvrir le tableau du client / le terminal du chantier (quitte Jarvis) ? | Rien ; seul le sujet est touchable (D12) |
| **D-PO-2** | **Sortie de commande** sur l'écran Jarvis : 2 dernières lignes masquées (D11), ou la commande seule ? | 2 lignes masquées ; mode sobre pour tout retirer |
| **D-PO-3** | **Univers complet** en couronne ou **client courant seul**, sur téléphone ? | Client courant déployé + autres en lisière (D7) |
| **D-PO-4** | Accepter l'**enrichissement passerelle** D5 — sans lui, chantier/étape et attentes (les familles 3 et 5 en usage) ne s'animent qu'en fin de tour, web et passation jamais. | Oui (SF-234-04) |
| **D-PO-5** | **Effets sonores** sur les scènes ? | **Non** : la seule voix est celle de Jarvis |
| **D-PO-6** | **Coût du tour en €** (A4) affiché sur l'écran Jarvis ? | Oui, discret, administrateur seulement (règle F-133) |
| **D-PO-7** | **Vague D** (Radar, Vigie, Teams, réunions) : la livrer avec F-234, ou attendre que ces fonctions soient utilisées (la générique les anime déjà) ? | Attendre (facultative) |

---

## 8. Design

### 8.1 Composition (téléphone, 390 × 844)

```
┌──────────────────────────────────────┐
│ ←   📁 Migration Forge ▾         ⌨   │  en-tête F-231 inchangé
│        · Edenred          · Perso    │  autres clients : points nommés en lisière
│                                      │
│   ○ Doc archi        ● Migration     │  sujets du client courant, en arc
│           ╲            Forge ◉───┐   │  ◉ = nœud cité (orange), nom en surbrillance
│             ╭──────────╮         │   │  fil lumineux nœud → orbe
│             │  ░▒▓▓▒░  │◀────────┘   │
│   ○ Runner  │ ▒ ORBE ▒ │  ○ Teams    │  ○ qui respire = terminal vivant (A2)
│             ╰──────────╯             │
│    ◇ Chantier 3 — Bascule DNS        │  chantier cité (losange)
│      └ étape 2/5 · Valider le TTL    │  étape courante
│  CAGIP › Migration Forge › Ch. 3 › 2 │  fil d'Ariane (équivalent accessible)
│ ┌──────────────────────────────────┐ │
│ │  SCÈNE (une à la fois)           │ │
│ └──────────────────────────────────┘ │
│   « Je prépare le mail pour Zahi. »  │  phrase dite (F-231)
│          [ 🎙  Parler ]              │
└──────────────────────────────────────┘
```

Formes : client = pastille ronde à liseré §9 ; sujet = cercle ; chantier = losange ; étape = trait court ; personne
= petit carré arrondi. Le nom est **toujours écrit** quand le nœud est allumé.

### 8.2 Scènes principales

**S-GEN — générique** (tout outil sans scène dédiée) :
```
┌──────────────────────────────────────┐
│        ◌ ◌ ( ORBE ) ◌ ◌              │  un anneau pulse une fois par appel
│   Radar · sujets du client     × 3   │  libellé du registre (ou nom humanisé), rafale
│   ● CAGIP                             │  entité rapprochée (ou sujet courant) allumée
└──────────────────────────────────────┘
```

**S3 — Commande sur le poste** :
```
┌──────────────────────────────────────┐
│ $ kubectl get pods -n forge          │  JetBrains Mono, opacité 0,6
│   forge-api-7d…   Running   0  3d    │  2 dernières lignes, secrets masqués (D11)
│   forge-web-5c…   Running   0  3d    │  défilement vers le haut, filigrane
└──────────────────────────────────────┘
```

**S4 — Tickets Jira / Confluence** :
```
┌──────────────────────────────────────┐
│  ┌────────┐ ┌────────┐ ┌────────┐     │  cartes en éventail
│  │FORGE-41│ │FORGE-42│ │ OPS-7  │     │  clés trouvées dans la commande / le texte
│  └────────┘ └────────┘ └────────┘     │
│   Jira · consultation                 │  « Confluence · page » si l'URL l'indique
└──────────────────────────────────────┘
```

**S9 — L'étape qui avance** :
```
   ◇ Chantier 3 — Bascule DNS   [exécution]      phase écrite (F-176)
     ✓ 1 Inventaire
     ✓ 2 Valider le TTL   ← se coche (tracé)     « chantier 3 · étape 2 » (D5)
     ○ 3 Basculer                                 done/total 2/5 → 3/5
```

**S11 — Attente posée** : un petit carré « Zahi · validation » s'attache au losange du chantier par un trait
pointillé ; levée = le trait se rompt et le carré s'efface.

**S2 — Le oui attendu** :
```
┌──────────────────────────────────────┐
│  ⏳  J'attends ton « oui »       0:42  │  orange --cg-accent, anneau qui se vide
│  Envoyer la note au CODIR ?           │  `detail`
│  Dis « oui » — sinon je ne fais rien  │
└──────────────────────────────────────┘
```

**S1 — Mail en préparation** :
```
┌──────────────────────────────────────┐
│   ╱‾‾‾‾‾‾‾‾‾‾‾‾‾‾╲    À : Zahi▌       │  l'enveloppe se forme pendant le bloc
│  │  ✉            │    Objet : Compte  │  destinataire puis objet s'écrivent
│   ╲______________╱       forge         │
│  fin du bloc → [ À envoyer (3) ] +1   │  compteur SF-231-06
└──────────────────────────────────────┘
```
Le nœud **Zahi** s'allume s'il est connu ; le corps n'est jamais rendu (il est dans « Voir le détail »).

**S13 — Essaim** : une orbe satellite par `explore`/`task`, en orbite, question tronquée ; elle rentre dans l'orbe à
la fin. **S10 — Passation** : le fil lumineux quitte le sujet courant et rejoint le sujet ouvert. **A5 — La veille
sonne** : le client s'allume, une onde part vers l'orbe, « Veille Jira · CAGIP — 2 changements ».

**S19 — Teams / réunion (vague D)** : point rouge « réunion en cours » sur le client, bulles qui montent ; tant que
la vague D n'est pas livrée, ces outils passent par **S-GEN** (« Teams · lecture d'un fil »).

### 8.3 Performance mobile

- SVG unique, ≤ ~60 nœuds visibles (bornes D4), positions calculées une fois ; animations CSS sur `transform`,
  `opacity`, `stroke-dashoffset` uniquement ; pas de `filter: blur` animé — la lueur est un dégradé radial statique
  dont on anime l'opacité.
- Le lecteur de scène tourne hors détection de changements Angular (comme l'orbe) et ne pousse un signal qu'au
  changement d'état ; les deltas sont rapprochés par fenêtre glissante (jamais de rebalayage du texte entier).
- Budget : **≤ 4 ms par image** sur un Android milieu de gamme (profileur Chrome, SF-234-03), aucune image perdue
  sur l'orbe.

---

## 9. Préoccupations transversales

| Préoccupation | Concernée ? | Composants impactés et vérification |
|---|---|---|
| **Contexte tenant** | **Oui** (nouvel endpoint D4) | `GET /api/jarvis/universe` bâti sur `ClientBoardService.board(userId, hostId)`, la liste des workspaces, `InterlocuteurBoard` (F-180), le service « à envoyer » de SF-231-06, la présence des postes — tous appelés avec `currentUser.requireId()` ; **test d'isolation** A/B ; `hostId` d'un autre utilisateur ignoré. |
| **Navigation / routing** | **Oui** (faible) | Aucune route nouvelle ; `/jarvis` (authGuard) inchangée ; toucher un sujet → `chooseSubject` existant ; entrées `?alerte=` (SF-231-05) et `?source=compagnon` (SF-232-02) non régressées (tests). |
| **Auth / Principal** | Non | — |
| **Plans / limites** | Non | Aucun appel IA ni quota consommé. |
| **Préfixe de cache / consigne** | Non (garde explicite) | SF-234-04 ne touche que `stepFor` ; test byte-stable du préfixe (F-134) rejoué. |
| **Journal d'audit** | Non (garde explicite) | `auditTarget` inchangé ; test sur `update_plan_step`, `record_blocker`, `ouvrir_sujet`. |
| **Gouvernance (checklist)** | **Oui** | `project-governance/checklists/review-checklist.md` : une ligne ajoutée (§12, SF-234-02). |

---

## 10. Ce qui demande d'enrichir les événements côté passerelle

Uniquement **SF-234-04** (D5) : étiquettes dans `stepFor` + étape `web` pour les outils serveur. **Aucune
modification** de la consigne système, des descriptions d'outils, du message, ni du journal d'audit. Sans elle, les
outils concernés passent par **S-GEN** (nom lisible + sujet courant) — rien ne reste muet.

---

## 11. Découpage en vagues (≤ 2 jours par SF)

**Démarrage après F-233 (SF-233-03, -08, -09 au minimum).** Ordre par usage réel (§2).

### Vague A — Socle (obligatoire)

| SF | Contenu | Critères d'acceptation vérifiables | Durée |
|---|---|---|---|
| **SF-234-01** | **L'univers (passerelle)** : `GET /api/jarvis/universe` (D4), DTO sans contenu, bornes, services existants. | Réponse = clients (mission, connecté) → sujets → chantiers ouverts (numéro, titre, phase, étape courante, `done/total`, `live`, personnes en attente) + interlocuteurs (nom, variantes) + destinataires à envoyer ; **test d'isolation** A/B ; 13 clients ⇒ 12 ; aucun champ de corps/message/sortie (test de forme) ; un seul aller-retour depuis `/jarvis`. | 1,5 j |
| **SF-234-02** | **Lecteur de scène + registre + scène générique** (D1, D2, D3, D14) : `JarvisSceneReader` pur, registre JSON initial (toutes les entrées du §5, dédiées marquées « à venir » = `GENERIQUE` en attendant), S-GEN rendue, rapprochement de noms, détection en flux des blocs ` ```mail `/` ```message `. **+ ligne de `review-checklist.md` (§12).** | Tests : outil absent du registre ⇒ S-GEN avec nom humanisé ; `teams_read_conversation` ⇒ S-GEN « Teams · … » par préfixe ; « CAGIP » allume le client ; « web » (exclu) n'allume rien ; nom présent dans deux sujets ⇒ rien ; « chantier 3 » allume le bon losange ; bloc mail sur 5 deltas ⇒ À puis Objet puis fin ; 10 `read` en 1 s ⇒ une scène « 10 fichiers lus » ; aucun appel HTTP pendant le tour (espion) ; la ligne de checklist est présente. | 2 j |
| **SF-234-03** | **La constellation** (D6, D7, D8, D9, D10, D12) : SVG autour de l'orbe, disposition déterministe, téléphone = client courant, fil lumineux, nom en surbrillance, fil d'Ariane, toucher un sujet, mouvement réduit ; **DESIGN_SYSTEM §22**. | Même univers ⇒ mêmes positions ; < 768 px : sujets des autres clients non rendus ; `prefers-reduced-motion` ⇒ aucune animation de tracé ; contraste des noms ≥ 4,5:1 sur navy ; toucher un sujet = même effet que le menu ; `?alerte=` et `?source=compagnon` intacts ; ≤ 4 ms/image (profil Android). | 2 j |
| **SF-234-04** | **Étiquettes de scène (passerelle, additif — D5)** + **garde du registre (D17)** : `stepFor` nomme les outils muets (journey, attentes, `ouvrir_sujet`/`create_subject`, `skill`, `gouvernance_proposer`, `veille_poser`, `build_*`, `render_diagram`) ; étape `web` pour `web_search`/`web_fetch` ; test passerelle : chaque outil offert à l'agent figure dans `jarvis-scene-registry.json`. | `update_plan_step` ⇒ `path="chantier 3 · étape 2"` ; `record_blocker` ⇒ personne + catégorie ; `web_fetch` ⇒ étape `web`, domaine seul ; `auditTarget` inchangé (test) ; **préfixe byte-stable** (test F-134) ; un outil ajouté sans entrée de registre fait échouer le test ; terminal sans régression (`chat-steps.ts`). | 1,5 j |

### Vague B — Usage fort (familles 1 à 6 du §2)

| SF | Contenu | Critères d'acceptation | Durée |
|---|---|---|---|
| **SF-234-05** | **S3 commande, S4 tickets, S5 feuillets/loupe** (D11). | Sortie avec `Authorization: Bearer abc` ⇒ masquée ; `curl …/rest/api/2/issue/FORGE-41` ⇒ carte FORGE-41 ; URL `…/wiki/…` ⇒ « Confluence » ; 2 lignes max ; mode sobre ⇒ rien. | 2 j |
| **SF-234-06** | **S9 étape qui avance / plan / phase / autonomie, S11 attentes, S2 oui et question, S14 points de route.** | `update_plan_step` étiqueté ⇒ étape cochée en cours de tour ; `done` 3/5 ⇒ compteur mis à jour ; `record_blocker` ⇒ personne attachée au chantier, `close_blocker` ⇒ détachée ; `confirm_request` ⇒ S2 devant toute autre scène jusqu'à `confirm_resolved` ou expiration ; `questions` ⇒ une puce par question. | 2 j |
| **SF-234-07** | **S1 messages à envoyer** : enveloppe/bulle, `email_me`, preuves jointes (F-212), compteur « À envoyer (n) ». | Bloc ` ```mail ` avec « À : Zahi » ⇒ enveloppe + nœud Zahi + compteur +1 à la fin du bloc ; corps jamais rendu (test) ; `preuves=` ⇒ objectif photo ; `email_me` ⇒ « pour toi ». | 1,5 j |

### Vague C — Usage moyen, ambiance et voix

| SF | Contenu | Critères d'acceptation | Durée |
|---|---|---|---|
| **SF-234-08** | **S6 web, S10 passation, S12 mémoire/savoir/gouvernance, S13 essaim, S15 le point, S16 besoin/diagnostic, S17 veille/routine posée.** | `web` ⇒ barre d'adresse avec domaine ; `ouvrir_sujet` étiqueté ⇒ fil glisse vers ce sujet ; `recall` + événement `recall` ⇒ « tour 34 » ; 2 `explore` ⇒ 2 satellites ; `sujets_etat` ⇒ vague sur les sujets du client. | 2 j |
| **SF-234-09** | **S7 atelier de création** (page, PDF, diagramme, visuel, Office, salle de montage vidéo, voix off) **et S5b regarder une vidéo.** | `page_publish` ⇒ titre de la page qui se compose ; `video_render_status` répété ⇒ une seule scène avec avancement ; `video_images` ⇒ pellicule + nom du fichier. | 1,5 j |
| **SF-234-10** | **Ambiance hors tour** : A1 poste connecté/éteint (+ porte F-161), A2 terminaux vivants, A3 cloche, A4 coût du tour en € (admin), A5 la veille sonne — **au repos seulement** (D14). | Aucune scène A pendant un tour ; `/api/terminals/live` montre un autre sujet actif ⇒ il respire ; notification `QUESTION_ASKED` d'un autre sujet ⇒ clignote une fois ; `costEur` absent (non-admin) ⇒ rien ; `?alerte=` ⇒ A5 sur le bon client ; aucun relevé **nouveau** (espion HTTP : seuls les relevés existants). | 2 j |
| **SF-234-11** | **Synchronisation voix (F-233) + mode sobre + mesure** (D9, D13, D16). | Coupure ⇒ scène figée ≤ 1 s puis effacée ; nom surligné pendant la phrase qui le contient ; mode sobre persistant (et rendu correct si `localStorage` lève) ; compteurs (dont part de S-GEN) incrémentés sans contenu ; `GET /api/admin/jarvis/measure` les expose. | 1,5 j |

### Vague D — Radar, Vigie, Teams, réunions (facultative, D-PO-7)

| SF | Contenu | Critères d'acceptation | Durée |
|---|---|---|---|
| **SF-234-12** | **S18 Radar et brief** (balayage, sujets Radar en écho, résumé du matin) et **S19 Teams / Vigie / réunions** (bulles, réunion en cours, capture, transcription, fichiers M365) ; les entrées du registre passent de `GENERIQUE` à dédiées. | `radar_find_subject` ⇒ balayage sur le client ; `teams_meeting_capture_start` ⇒ point rouge « réunion en cours » ; `teams_read_conversation` ⇒ bulles ; sans cette SF, ces outils restent sur S-GEN (test de non-régression). | 2 j |

**Durée** : vague A 7 j · vague B 5,5 j · vague C 7 j · vague D 2 j ⇒ **≈ 19,5 j au total, ≈ 17,5 j sans la vague D**
(12 SF). Lancer la vague C après la mesure J+7 de la vague B (D16) si le PO le souhaite.

---

## 12. Règle pour l'avenir (P4)

Ligne à ajouter à `project-governance/checklists/review-checklist.md`, section **Architecture — BLOQUANT**
(modification portée par **SF-234-02**) :

```markdown
- [ ] Si la PR ajoute un outil à l'agent (ou un nouveau `type` d'étape) : sa scène Jarvis est déclarée dans `frontend/src/app/jarvis/scenes/jarvis-scene-registry.json` — scène dédiée, ou `"scene": "GENERIQUE"` acceptée explicitement avec un `libelle` lisible (F-234, D17) (**BLOQUANT** si absente)
```

La garde automatique de **SF-234-04** (test passerelle : tout outil offert à l'agent figure au registre) rend
l'oubli impossible à merger ; la ligne de checklist dit au relecteur **quoi vérifier** (le libellé est lisible, le
choix générique est assumé).

---

## 13. Hors périmètre

- Toute génération (image, texte) pour décorer l'écran ; tout appel modèle.
- Les scènes dans le terminal (`/atelier`) : il bénéficie des étiquettes de SF-234-04, rien de plus.
- L'APK Android (F-232) : il ouvre `/jarvis`, qui porte les scènes ; rien de natif.
- Une carte interactive de tout l'univers (zoom, recherche) : la constellation est un **témoin**, pas un navigateur.
- Rendre visible la qualité du raisonnement (effort, cache, juge) : ce serait inventer une information (§6.3).

## 14. Mesure d'intérêt

J+7 / J+14 via `GET /api/admin/jarvis/measure` (D16) : scènes montrées par famille, part de la générique, nœuds
allumés par tour, touchers de sujet, taux de mode sobre. Un mode sobre activé durablement par le PO = signal de
surcharge ⇒ revoir la densité avant toute nouvelle scène ; une générique fréquente sur un outil = candidat à une
scène dédiée.
