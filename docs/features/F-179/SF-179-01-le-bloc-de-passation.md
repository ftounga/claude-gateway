# Mini-spec — F-179 / SF-179-01 — Le bloc de passation

## Identifiant
`F-179 / SF-179-01` — feature parente `F-179` *Ouvrir le sujet d'un « go »* (cadrage
`CADRAGE-F-179-ouvrir-le-sujet-d-un-go.md`, décisions D1 et D5). Suite de SF-141-06.
Branche : `feat/SF-179-01-bloc-de-passation`. Statut : `in-progress`. Date : 2026-10-06.

## Objectif
Que le terminal du poste puisse **désigner le sujet à ouvrir et sa phrase de démarrage** sous la forme
d'un bloc structuré (`handoff`), que l'écran (SF-179-02) saura transformer en ouverture du sujet.

## Comportement attendu

### Cas nominal
1. **Outil `ouvrir_sujet(sujet, phrase)`** — déclaré **uniquement au terminal du poste**
   (`Workspace.isHostTerminal()`), comme `create_subject`.
   - `sujet` : l'**id** rendu par `create_subject`, ou le **nom / chemin** du dossier du sujet.
   - `phrase` : la phrase de démarrage (bornée à 2 000 caractères, non vide).
   - Résolution **côté gateway**, isolée : par id → `requireOwned(userId, id)` puis contrôle que le
     sujet est un **projet du même poste** (`hostId` égal, ni terminal de poste ni terminal Teams) ;
     par nom → `listByHost(userId, hostId)` (déjà filtré `user_id` + `host_id`, projets seuls), égalité
     insensible à la casse sur le nom ou le chemin normalisé (sans `/` final). Plusieurs candidats →
     erreur « ambigu, donne l'id ».
2. **Bloc de passation** `SubjectHandoff {workspaceId, name, phrase}` :
   - relayé au fil de l'eau : événement SSE **`handoff`** `{toolUseId, handoff}` (même modèle que
     `attente`, F-175 / SF-175-05) ;
   - rangé dans le bloc de transcription de l'appel (champ `handoff`), conservé par le bornage — la carte
     [Ouvrir le sujet] survit au rechargement.
3. **`create_subject` rend l'id** du sujet créé dans son résultat (« id : <uuid> »), pour que l'agent
   puisse cibler `ouvrir_sujet` sans ambiguïté.
4. **Doctrine `SUBJECT_HANDOFF_DOCTRINE`** (D5) : la « phrase prête à coller » devient « après le go de
   l'utilisateur, mets à jour les .md du sujet puis appelle `ouvrir_sujet` » ; la question « demander »
   de SF-141-06 reste pour PROPOSER la passation ; la phrase reste montrée en clair si l'outil échoue.
   Littéral stable (cache F-134), injecté host-only comme avant.

### Cas d'erreur
| Situation | Comportement |
|---|---|
| Hors terminal du poste | outil non déclaré ; appel forcé → erreur outil, aucun bloc |
| `sujet` ou `phrase` vides | erreur outil, aucun bloc |
| Id d'un autre utilisateur | `requireOwned` → introuvable → erreur outil, aucun bloc |
| Id d'un autre poste / terminal du poste / Teams | erreur « pas un sujet de ce poste », aucun bloc |
| Nom inconnu / ambigu | erreur outil (liste courte des sujets si inconnu), aucun bloc |

## Critères d'acceptation
- [ ] `ouvrir_sujet` n'est offert qu'au terminal du poste.
- [ ] Un appel valide relaie `onHandoff` et range `handoff` dans la transcription JSON (bornage compris).
- [ ] Un sujet d'un autre poste ou d'un autre utilisateur ne peut pas être ciblé (aucun bloc).
- [ ] `create_subject` rend l'id créé.
- [ ] La doctrine host-only demande `ouvrir_sujet` après le go ; absente ailleurs.

## Plan de test
- **Service** (`AtelierChatServiceHandoffTest`) : outil offert / absent ; résolution par id et par nom ;
  refus autre poste, autre utilisateur, terminal du poste, nom ambigu, phrase vide ; relais `onHandoff`
  + transcription JSON ; bornage conserve `handoff` ; `create_subject` rend l'id.
- **Non-régression** : `AtelierChatServiceSystemPromptTest` (doctrine SF-141-06 adaptée), suite atelier.
- **Isolation utilisateur** : test « id d'un autre utilisateur » (requireOwned) et « autre poste ».

## Impacts
Backend : `SubjectHandoff` (nouveau record), `AtelierTurnReport.Block.handoff` (+ forme d'avant
conservée), `AtelierProgressListener.onHandoff`, `AtelierChatController` (événement `handoff`),
`AtelierChatService` (outil, résolution, relais, transcription, doctrine, id de `create_subject`).
Aucune table, aucune migration, aucun endpoint REST nouveau (événement SSE ajouté au flux existant).

### Préoccupations transversales
- **Contexte tenant** : ✔ — composants qui résolvent le sujet : `WorkspaceService.requireOwned`
  (user_id) et `WorkspaceService.listByHost` (user_id + host_id). Aucun identifiant de compte lu dans
  l'entrée de l'outil ; le poste est celui du terminal déjà possédé.
- Auth / Plans / Navigation : non (la navigation est SF-179-02).

## Hors périmètre
Navigation, carte, bandeau (SF-179-02) ; passation sujet → sujet ; envoi automatique de la phrase.

## Arbitrages (réversibles)
- Nom d'outil français `ouvrir_sujet` (cadrage), à côté de `demander`.
- `sujet` accepte id **ou** nom (l'agent n'a pas toujours l'id d'un sujet existant retenu à la racine).
- Phrase bornée à 2 000 caractères.
