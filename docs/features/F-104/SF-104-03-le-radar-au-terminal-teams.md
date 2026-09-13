# Mini-spec — F-104 / SF-104-03 — Le Radar au terminal Teams

> Base : `docs/features/F-99/CADRAGE-le-radar.md` §2 (« un écran pour l'état, le chat pour le nourrir »), §9
> (« les mêmes outils sont ajoutés au catalogue du terminal Teams (F-89) : « où en est le MFA ? » y trouve sa
> réponse dans le registre, sans relire Teams »), §12 bis SF-104-03. Cadrage validé par le PO.

## Identifiant

`F-104 / SF-104-03`

## Feature parente

`F-104` — Le Radar : le nourrir

## Statut

`in-progress`

## Date de création

2026-09-13

## Branche Git

`feat/SF-104-03-radar-terminal-teams`

---

## Objectif

Faire du terminal Teams d'un client suivi par la Vigie un lieu où l'on **interroge et nourrit le Radar** :
l'agent sait que le registre existe, y cherche **d'abord** la réponse à « où en est … ? », n'écrit que ce que
l'utilisateur lui dit, et chaque appel Radar se lit en clair dans le terminal.

---

## Comportement attendu

### Cas nominal

1. Les outils `radar_*` sont donnés au terminal Teams sous la garde de SF-104-01 (terminal Teams + poste
   activé dans la Vigie + droit Vigie). SF-104-03 ajoute ce qui en fait un usage juste :
2. **La consigne système** du terminal gagne, **seulement quand la garde est ouverte**, un bloc
   `--- Radar du client ---` (`RadarToolCatalog.TERMINAL_NOTICE`) :
   - pour « où en est tel sujet ? », « qu'est-ce que j'attends de X ? », « quelles relances sont dues ? » :
     chercher **d'abord dans le registre** (`radar_find_subject`), dont la réponse est sourcée et ne relit
     pas Teams ; ne relire Teams que si le registre ne sait pas ou si l'utilisateur le demande, et dire d'où
     vient la réponse ;
   - les écritures ont pour preuve **le message de l'utilisateur** : n'écrire que ce qu'il dit lui-même,
     **jamais** ce qui a été lu dans Teams ni ce qui est déduit ; une question n'est pas une nouvelle ;
   - dire en une phrase ce qui est compris avant d'écrire (« Je note : … »), rappeler que c'est annulable
     depuis la chronologie du sujet ;
   - rien n'est écrit dans Teams (cadrage §4.5).
3. **Le terminal montre chaque appel Radar en clair** : l'étape et le bloc de transcription portent une
   cible lisible, sans identifiant technique ni contenu de message :
   - `radar_find_subject` → « Radar · recherche « MFA » » (sans requête : « Radar · sujets ouverts ») ;
   - `radar_update_subject` → « Radar · nouveau sujet « … » » ou « Radar · sujet : état, prochaine étape » ;
   - `radar_close_subject` → « Radar · clôture d'un sujet » ;
   - `radar_add_engagement` → « Radar · engagement « … » » (description tronquée) ;
   - `radar_mark_engagement` → « Radar · engagement tenu / abandonné / reporté / rouvert / pas le mien » ;
   - `radar_merge_subjects` → « Radar · fusion de deux sujets ».
   Le résultat de l'outil (ce qui a été écrit, « annulable depuis la chronologie ») s'affiche sous l'étape,
   comme pour tout outil.
4. **Écran** : la conversion des étapes (`chat-steps.ts`) rend un outil `radar_*` sans cible par « Radar »
   plutôt que par son nom technique.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Terminal Teams sans droit Vigie, ou poste hors Vigie | aucun bloc Radar dans la consigne, aucun outil `radar_*` (SF-104-01) | — |
| Terminal de projet | ni bloc, ni outil | — |
| Paramètres absents ou illisibles dans un appel | cible « Radar · … » générique, jamais d'exception | — |
| Échec d'un outil Radar | bloc d'étape en erreur avec la phrase de l'exécuteur | — |

---

## Critères d'acceptation

- [ ] Terminal Teams, garde ouverte : la consigne contient `--- Radar du client ---` ; garde fermée (sans
      droit, hors Vigie, terminal de projet) : elle ne le contient pas.
- [ ] Les cibles lisibles des six outils sont celles ci-dessus ; aucune ne contient d'identifiant UUID.
- [ ] Un tour qui appelle `radar_find_subject` relaie l'étape « Radar · recherche « MFA » » et persiste le
      bloc avec cette cible.
- [ ] `chat-steps.ts` : un `radar_*` sans cible s'affiche « Radar ».

---

## Périmètre

### Hors scope (explicite)

- Les outils eux-mêmes et leur garde (SF-104-01) ; *Donner la nouvelle* (SF-104-02).
- Des blocs riches Radar (cartes) dans le fil : le texte et le lien vers la Vigie suffisent.
- Écrire dans Teams.

---

## Valeurs initiales

| Champ | Valeur initiale | Règle |
|-------|----------------|-------|
| longueur d'une cible d'étape | borne existante `AUDIT_TARGET_CHARS` | tronquée |

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format / Valeurs autorisées | Unicité | Normalisation |
|-------|-------------|-------------|----------------------------|---------|---------------|
| cible d'étape Radar | — | `AUDIT_TARGET_CHARS` | texte lisible, sans UUID | — | tronquée |

---

## Technique

### Endpoint(s)

Aucun (flux de conversation existant `chat/stream`).

### Tables impactées

Aucune.

### Migration Liquibase

- [x] Non applicable

### Composants

- Backend : `RadarToolCatalog.TERMINAL_NOTICE`, `RadarToolCatalog.stepTarget(tool, input)`,
  `AtelierChatService.buildSystemPrompt` (bloc sous garde), `AtelierChatService.auditTarget` (cibles Radar).
- Frontend : `atelier/terminal/chat-steps.ts` (libellé par défaut « Radar »).

### Préoccupations transversales

- **Plans / limites : oui (lecture de la garde).** Composants vérifiés : `RadarToolCatalog.isOpenFor`
  (même garde que les outils), `TeamsToolCatalog.isClosedFor` (bloc « volet non actif » inchangé et
  exclusif : sans droit, aucun bloc Radar).
- Contexte tenant : non (aucune lecture nouvelle). Navigation : non. Auth / Principal : non.

---

## Plan de test

### Tests unitaires

- [ ] `RadarToolCatalogTest` — cibles lisibles des six outils, sans UUID, paramètres absents.
- [ ] `AtelierChatServiceRadarToolsTest` — consigne avec / sans bloc selon la garde ; étape relayée et bloc
      persisté avec la cible lisible.
- [ ] `chat-steps.spec.ts` — `radar_*` sans cible → « Radar ».

### Tests d'intégration

- [ ] Couverts par `AtelierChatServiceRadarToolsTest` (tour complet, fournisseur simulé) et, pour
      l'exécution sur le registre réel, par `RadarToolExecutorIntegrationTest` (SF-104-01).

### Isolation workspace

- [x] Non applicable en propre — aucune lecture nouvelle ; la garde et le périmètre sont ceux de SF-104-01.

---

## Dépendances

### Subfeatures bloquantes

- SF-104-01 (PR #544) — `done`.

### Questions ouvertes impactées

- Aucune.

---

## Notes et décisions

- **Registre d'abord** : c'est la promesse du cadrage (« sans relire Teams ») et la mesure d'économie du
  Radar — une réponse du registre ne coûte ni collecte ni lecture de fil.
- **La preuve reste la parole de l'utilisateur** : la consigne l'interdit explicitement parce que, dans ce
  terminal, l'agent lit aussi Teams ; écrire une lecture de Teams sous la preuve du message de l'utilisateur
  lui attribuerait des mots qu'il n'a pas dits. La synchro du soir est le seul chemin des lectures Teams vers
  le registre.
