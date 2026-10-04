# Cadrage — F-175 Le fil des attentes

> Cadrage PO le 2026-10-04 (« je veux que les choses en attente, les accès demandés, fassent partie
> du terminal ; qu'on ne puisse pas les louper ; qu'on voie qu'un accès requis a déjà été demandé ;
> plusieurs statuts : à faire, en cours parce que demandé, fait ; fermé par nous ou par la
> conversation, avec validation ; plus joli »). Évolution de **F-154** (Terminée), qui reste la base.
> Source de vérité produit : `docs/PROJECT.md`. Subordonné à `CLAUDE.md`.

## 1. Audit de F-154 (2026-10-04, code `origin/main` + prod)

**Usage réel** : 29 actions depuis le 25/09 (`terminal_actions`), **20 ouvertes**, sur 4 terminaux
(`agenor`, `Terminal du poste`, `gitlab-tfstate`, `cloudops-run`), certaines ouvertes depuis 9 jours.

| # | Défaut | Preuve |
|---|---|---|
| A | La pastille « N à faire » n'est relue **qu'au changement de terminal** : une action inscrite pendant un tour n'apparaît pas | `atelier-terminal.component.ts:296-306` (seul appel l. 303) |
| B | La pastille est le **seul** accès au panneau et **disparaît à zéro** : la section « Ailleurs » devient inaccessible ; sur mobile elle est cachée dans le menu ⋯ ; rien dans la mosaïque, le rail de la Forge, la liste des terminaux | `.html:124-139`, `:240-245`, `mosaique.component.html:126-128` |
| C | **L'agent ne reçoit jamais la liste** des actions ouvertes (ni bloc système ni message) : il ne peut pas savoir qu'un accès a déjà été demandé | aucune lecture de `TerminalActionService` dans `AtelierChatService` |
| D | **Doublons** : dédoublonnage par clé exacte et **par terminal** seulement. Prod : compte forge CAPFM demandé 2 fois le 30/09 depuis 2 terminaux ; dérogation SCP à Habib 2 fois (25 et 26/09) | `TerminalActionService.java:113-153` |
| E | **Pas d'état « demandé »** : `OPEN / DONE / CANCELLED`. Prod 04/10 : « Transférer le mail v3 » passe DONE, puis l'agent crée « Obtenir la réponse au mail v3 » | `TerminalActionStatus.java:6-19` |
| F | Portée = terminal (`workspace_id`), pas de `host_id` ; `subject_id` jamais rempli | `TerminalActionToolExecutor.java:112` |
| G | Fermeture par la conversation **sans validation** de l'utilisateur ; une fermeture par l'agent n'est pas « Rétablir »-able à l'écran | `closeByKey`, `closedRecently` local |
| H | Promesses non tenues : agrégation avec les engagements Radar « à faire par moi » ; « Envoyer le message » | SF-154-01, PRODUCT_SPEC l. 263 |
| I | Pas d'ajout ni d'édition manuels à l'écran (l'API `POST` existe) ; pas de relance ni d'échéance | — |

## 2. Objectif

Faire des attentes un **fil qu'on ne peut pas louper** : une liste par poste, visible en permanence
dans le terminal, avec trois états, connue de l'agent à chaque tour, qui reconnaît un accès déjà
demandé, et dont rien ne sort sans le geste de l'utilisateur.

## 3. Décisions (prises par défaut, réversibles, tracées)

| # | Décision | Pourquoi |
|---|---|---|
| D1 | **États** : `A_FAIRE` → `DEMANDE` (date de demande, personne, canal) → `FAIT` ; `ANNULE` en sortie latérale. Retour `DEMANDE` → `A_FAIRE` possible (« pas de réponse, on refait »). Migration des valeurs : `OPEN` → `A_FAIRE`, `DONE` → `FAIT`, `CANCELLED` → `ANNULE`. | Demande PO ; défaut E. |
| D2 | **Portée poste** : `host_id` ajouté (déduit du terminal) ; le terminal d'origine reste noté (« né dans `gitlab-tfstate` »). La liste du terminal montre **ce sujet** d'abord puis **le reste du poste** ; un terminal « Hébergé » garde sa liste propre. | Défauts D, F : un accès appartient au client, pas à un terminal. |
| D3 | **Doublons** : avant d'inscrire, comparaison avec les attentes ouvertes du poste (clé normalisée + similarité sémantique pgvector sur la description, même fournisseur d'embeddings que F-162/F-174). Au-dessus du seuil, l'outil **n'inscrit pas** et rend l'attente existante (« déjà demandé à Zahi le 30/09, en attente depuis 4 j ») ; l'agent peut alors proposer une relance. | Demande PO « se rendre compte qu'un accès a déjà été demandé ». |
| D4 | **L'agent connaît la liste** : les attentes ouvertes du poste (`A_FAIRE`, `DEMANDE`), compactes (clé, état, âge, personne, une ligne), jointes **au message du tour**, jamais au bloc système (préfixe stable F-171). Bornées (≤ 30 lignes / 3 000 car., les plus récentes du sujet d'abord). | Défaut C. |
| D5 | **Outils** : `record_blocker` (inchangé + dédoublonnage D3), `update_blocker(key, status, note)` pour `A_FAIRE`↔`DEMANDE`, `close_blocker` devient une **proposition** : il ne ferme pas, il crée une carte de confirmation (D7). Fermeture directe uniquement par le geste de l'utilisateur. | Demande PO « en validant son retrait ». |
| D6 | **Toujours visible** : une **bande des attentes** au-dessus de la zone de saisie dès qu'il y en a au moins une sur le poste (« 2 à faire · 4 demandées · la plus ancienne 9 j »), relue **à chaque fin de tour** et à l'ouverture ; un clic ouvre le panneau. Le panneau : trois colonnes *À faire · Demandé · Fait récemment*, filtre « ce sujet / tout le poste », ajout et édition manuels, glisser ou bouton pour changer d'état, « Rétablir » sur tout ce qui a été fermé depuis 7 jours. Compteur aussi dans le rail de la Forge (par poste), la mosaïque et la barre mobile. Palette et polices du `DESIGN_SYSTEM.md` uniquement. | Défauts A, B, G, I ; « plus joli ». |
| D7 | **Cartes dans le fil** : à l'inscription (« Ajouté à tes attentes : … »), à la détection d'un doublon (« Déjà demandé le … »), et à la proposition de fermeture (« Je pense que c'est réglé : « … ». [Confirmer] [Pas encore] »). Sans réponse, l'attente reste ouverte. | Demande PO ; défaut G. |
| D8 | **Relance** : une attente `DEMANDE` depuis plus de `APP_ATELIER_FOLLOWUP_DAYS` (défaut 3 jours ouvrés) affiche « Relancer » ; le geste pré-remplit la zone de saisie (mécanisme `radarDraft`), **sans envoyer**. | Les attentes s'enlisent (prod : 9 jours). |
| D9 | **Reprise de l'existant** : à la première ouverture après déploiement, les 20 attentes ouvertes sont présentées une fois avec un état proposé (heuristique sur la description : « Demander / Relancer / Transférer » → `DEMANDE` si un message est réputé parti) ; l'utilisateur valide en bloc ou une par une. Rien n'est changé sans validation. | Ne pas hériter d'une liste fausse. |
| D10 | Isolation : `user_id` sur tout accès ; `host_id` vérifié appartenir au compte ; purge compte / poste / projet conservée. | Règle multi-tenant. |

## 4. Découpage

| SF | Titre | Contenu |
|---|---|---|
| SF-175-01 | Trois états et la portée poste | Migration (`host_id`, états D1, `requested_at`, `requested_to`, `channel`), service, API (lister par poste / sujet, changer d'état, éditer, ajouter, rétablir), isolation. |
| SF-175-02 | L'agent connaît la liste | Bloc D4 dans le message du tour, `update_blocker`, `close_blocker` en proposition (D5), doctrine mise à jour. |
| SF-175-03 | Déjà demandé | Dédoublonnage D3 (clé + pgvector), réponse de l'outil, carte « déjà demandé ». |
| SF-175-04 | La bande et le panneau | D6 : bande permanente rafraîchie à chaque fin de tour, panneau trois colonnes, filtre, ajout/édition, rétablir ; mobile. |
| SF-175-05 | Les cartes du fil | D7 : inscription, doublon, confirmation de fermeture [Confirmer] [Pas encore]. |
| SF-175-06 | Relancer et partout | D8 relance pré-remplie ; compteurs rail de la Forge, mosaïque. |
| SF-175-07 | Reprise de l'existant | D9. |

## 5. Hors périmètre

- Envoi d'un message depuis la liste (le message part toujours de l'agent, à la demande).
- Fusion avec les engagements du Radar (Vigie) : reste un chantier séparé.
- La porte de plan d'action : F-176.
