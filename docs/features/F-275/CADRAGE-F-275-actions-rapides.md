# Cadrage — F-275 Actions rapides (lancer un workflow configuré d'un clic)

> Besoin du registre `app_requests` `4c382e1a-b4d1-4ca2-9b75-4b074818f9a9` (EVOLUTION, réservé admin), reformulation
> validée : « Ajouter au terminal (poste et sujets) un bouton « Actions rapides » qui déroule une liste de workflows
> configurés ; un clic lance le workflow (phrase de démarrage) dans le bon terminal, sans se souvenir du déclencheur. »
> Contexte réel : poste EDENRED, skill de poste `suivi-hebdo`, sujet `reporting-securite`, plusieurs sujets/clients.

## 1. Ce qui existe déjà (vérifié sur `origin/main` 5383754a, 2026-11-21)

| Brique | Où | Ce qu'on en garde |
|---|---|---|
| Catalogue des skills d'un terminal (sujet puis poste, le sujet masque le poste), lecture déterministe | F-177 / SF-177-03 — `SkillCatalogService`, `GET /api/workspaces/{id}/skills` | **Source (a)** : on y lit l'en-tête complet du skill pour en tirer ses déclencheurs. Aucune seconde lecture du poste. |
| En-tête réel du skill `suivi-hebdo` (lu en prod, lecture seule, `prompt_source_files`) | `.claude/skills/suivi-hebdo.md` | `description: … Déclenché par « point hebdo », « suivi du jour » ou « prépare le comité ».` — la forme à reconnaître **sans configuration**. |
| Passation vers un sujet : le terminal du sujet s'ouvre, la phrase est déposée dans la saisie | F-179 — `ouvrir_sujet`, `AtelierComponent.openHandoff` | **Sujet cible** : le clic réutilise `openHandoff` (même ouverture, même bandeau « Revenir »). |
| Résolution d'un sujet par nom ou id **sur le poste du terminal** | `AtelierChatService.resolveSubjectOfHost`, `WorkspaceService.listByHost` | Même règle pour l'outil (nom ou chemin, casse ignorée, ambiguïté refusée). |
| Routines F-183 (tâche programmée exécutée seule) | `subject_routines` | **Pas réutilisées** : une action rapide ne s'exécute jamais seule (exigence 4) ; une routine est un tour sans humain avec budget. Les deux coexistent. |
| Gabarits F-177 (`.claude/gabarits/`) | modèles de documents | Sans rapport (ce ne sont pas des workflows). |
| Agents nommés F-182 | `.claude/agents/` | Sans rapport (délégation, pas déclenchement). |
| Portée COMPTE | F-266 — `account_governance_rules` | Même vocabulaire de portée : SUJET / POSTE / COMPTE. |
| Espace de travail F-207 | barre d'en-tête repliée, barre d'état unique, saisie | **Contrainte** : un seul bouton-icône dans la saisie, pas de bande ni de ligne en plus. |

Aucun « bouton de workflow » n'existe : le `/` (F-121 / F-177) exige de connaître le nom du skill, et ne vise que le
terminal courant.

## 2. Décisions (par défaut, réversibles)

| # | Décision | Pourquoi |
|---|---|---|
| D1 | **Deux sources fusionnées** dans une seule liste : (a) les **déclencheurs déclarés par les skills** du terminal — en-tête `declencheurs:` / `triggers:` (liste) **ou** phrase « Déclenché par « … », « … » » de la `description` ; (b) les **actions personnalisées** rangées en base (`quick_actions`). Une action personnalisée dont la phrase égale un déclencheur de skill le **masque** (pas de doublon). | Zéro double saisie pour (a) ; (b) pour tout le reste. |
| D2 | Un skill peut aussi dire **où** et **comment** : en-tête facultatif `sujet:` (nom du sujet du poste) et `clic: envoyer`. Sans eux : terminal courant, phrase déposée. | « point hebdo » dans `reporting-securite` depuis n'importe quel terminal du poste, sans configuration en base. |
| D3 | **Action personnalisée** = `{libellé, phrase, sujet cible facultatif, portée SUJET / POSTE / COMPTE, clic DEPOSER / ENVOYER, suggestion facultative : jours de la semaine + moment MATIN / APRES_MIDI / JOURNEE}`. Créée, listée, modifiée, supprimée par **l'outil agent `action_rapide`** (et par l'API, mêmes règles). Portée par défaut : POSTE si le terminal a un poste, sinon SUJET. | Exigence 1(b). |
| D4 | **Visibilité** dans un terminal T : portée COMPTE partout ; POSTE si `host_id = T.host_id` ; SUJET si `workspace_id = T.id`. Jamais dans un terminal Teams ni le fil Jarvis. Toujours sous `user_id`. | Isolation, et « depuis n'importe quel terminal ». |
| D5 | **Le clic** : sujet cible ≠ terminal affiché ⇒ ouverture du sujet comme `ouvrir_sujet` (phrase déposée ; envoyée si `ENVOYER`). Même terminal ⇒ phrase déposée (défaut) ou envoyée. **Jamais d'envoi qui écrase un brouillon** : saisie non vide ou tour en cours ⇒ dépôt, et l'écran le dit. | Exigences 2 et 3, sans perte. |
| D6 | **Suggestion** : une action dont le jour et le moment correspondent à l'heure de l'utilisateur, et pas encore lancée aujourd'hui, remonte en tête du menu (« Suggéré ») et allume un point sur le bouton. **Rien n'est exécuté.** Calcul à l'écran (heure du navigateur). | Exigence 4. |
| D7 | **Dernière exécution** = dernier lancement **depuis le bouton** (table `quick_action_launches`, clé stable par action, y compris celles des skills). Affichée « il y a 2 j » sous chaque action. | Exigence 5 ; un lancement tapé à la main n'est pas compté (hors périmètre, dit). |
| D8 | **Légèreté** : la liste complète (skills compris, lectures du poste bornées à celles du catalogue F-177) est lue **à l'ouverture du menu** ; au chargement du terminal, seule la liste des actions personnalisées (sans lecture du poste) est lue pour la suggestion. Un seul bouton-icône `playlist_play` dans la saisie. | F-207 ; « apparaît sans recharger » = relu à chaque ouverture. |

## 3. Découpage

| SF | Titre | Contenu | Estim. |
|---|---|---|---|
| SF-275-01 | Les actions et leurs lancements (backend) | D1, D2, D4, D7 : déclencheurs des skills, tables `quick_actions` + `quick_action_launches` (migration 275-01), service, `GET/POST/PUT/DELETE /api/workspaces/{id}/quick-actions`, `POST …/launches`, purge du compte. | 1,5 j |
| SF-275-02 | L'outil `action_rapide` (backend) | D3 : ajouter / lister / modifier / supprimer depuis le terminal, résolution du sujet cible sur le poste, scène Jarvis. | 1 j |
| SF-275-03 | Le bouton « Actions rapides » (frontend) | D5, D6, D8 : bouton + menu (Suggéré / Mes actions / Depuis les skills), lancement dans le bon terminal, dernière exécution, captures 1366×768 et téléphone. | 1,5 j |

## 4. Préoccupations transversales

- **Navigation / routing** : ✔ — le lancement vers un autre sujet passe par `openHandoff` (route `/atelier/:id`). Composants impactés : `AtelierComponent.openHandoff`, `returnFromHandoff`, `followRouteWorkspace`, `HandoffOriginComponent`. Non-régression : la passation `ouvrir_sujet` garde son comportement (dépôt seul, refus pendant un tour).
- **Contexte tenant** : ✔ — résolution du sujet cible. Composants : `WorkspaceService.requireOwned`, `listByHost` ; toute lecture de `quick_actions` porte `user_id`.
- **Auth / Principal** : ✘ (aucun changement). **Plans / limites** : ✘ (un lancement est un message ordinaire, soumis aux gardes existantes).

## 5. Hors périmètre

Exécution automatique ou programmée (c'est F-183) ; compter un lancement tapé à la main ; éditer les actions depuis un
formulaire à l'écran (l'outil et l'API suffisent) ; partage entre comptes ; actions du terminal Teams et du fil Jarvis.
