# Mini-spec — [F-121 / SF-121-15] Lecture multimodale (images / PDF)

## Identifiant

`F-121 / SF-121-15`

## Feature parente

`F-121` — Parité avec Claude Code (boucle maison)

## Statut

`ready`

## Date de création

2026-09-23

## Branche Git

`feat/SF-121-15-lecture-images-pdf`

---

## Objectif

Quand le modèle appelle `read_file` sur une image (PNG/JPEG/GIF/WebP) ou un PDF, la gateway
détecte le binaire et renvoie un `tool_result` **multimodal** (bloc `image`/`document` en Base64 +
`media_type`) que le fournisseur « voit » lui-même, au lieu du charabia UTF-8 d'aujourd'hui.

---

## Comportement attendu

### Cas nominal

1. Le modèle émet `read_file(path)` où `path` a une extension média supportée
   (`.png .jpg .jpeg .gif .webp .pdf`).
2. La gateway lit les octets du fichier **par les primitives runner déjà au contrat**
   (`read_file_bytes`, F-110 / SF-110-03) — **aucune évolution du protocole ni mise à jour runner**.
3. Elle **confirme le type par les octets d'en-tête** (magic bytes). Extension supportée + magic
   reconnu ⇒ multimodal ; sinon ⇒ lecture texte normale (comportement inchangé).
4. Provider-First : la gateway ne pose un bloc média **que si le fournisseur déclare voir ce
   `media_type`** (`AiAgentProvider.supportedMediaTypes()`). Sinon ⇒ note texte de repli.
5. Le résultat d'outil porte un `AgentContentBlock.Image`/`Document` (modèle neutre) + une légende
   texte (chemin, type, taille). `AnthropicAgentProvider.toApiBlock` encode le `tool_result` en
   `content: [ {image|document, source:{base64, media_type}}, {text} ]`.
6. Le fournisseur reçoit l'image/le PDF et peut le décrire, l'analyser, etc.

### Cas d'erreur

| Situation | Comportement attendu | Code |
|-----------|---------------------|------|
| Extension non média | Lecture texte normale (inchangée) | — |
| Extension média mais magic non reconnu | Lecture texte normale (repli, non forcé) | — |
| Fichier > borne (image 5 Mio / PDF 10 Mio) | `tool_result` texte : « trop volumineux pour être lu comme média », non-erreur | — |
| Fournisseur ne déclare pas ce `media_type` | `tool_result` texte de repli expliquant l'absence de vision | — |
| Lecture runner en échec (timeout/indispo) | Repli sur le chemin texte normal (« non concluant », SF-119-04) | — |
| Base64 illisible d'une tranche | Repli texte, jamais un bloc média corrompu | — |

---

## Critères d'acceptation

- [ ] `AgentContentBlock` porte deux variantes neutres `Image(mediaType, base64Data)` et
      `Document(mediaType, base64Data)` (sealed, exhaustif).
- [ ] `AnthropicAgentProvider.toApiBlock` encode `Image` en `{"type":"image","source":{"type":"base64",...}}`
      et `Document` en `{"type":"document","source":{"type":"base64",...}}`.
- [ ] Un `ToolResult` porteur de blocs média est encodé en `content` **tableau**
      (`[image|document, text]`) ; un `ToolResult` texte reste un `content` **chaîne** (aucune régression).
- [ ] `read_file` sur un PNG/JPEG/GIF/WebP valide et sous la borne ⇒ `tool_result` multimodal image.
- [ ] `read_file` sur un PDF valide et sous la borne ⇒ `tool_result` multimodal document.
- [ ] Un fichier texte, ou un binaire d'extension non supportée, ⇒ lecture texte inchangée.
- [ ] Un fichier média au-delà de la borne ⇒ note texte, jamais un bloc média tronqué.
- [ ] Provider-First : aucun bloc média émis si `supportedMediaTypes()` ne contient pas le type.
- [ ] Cache de prompt (F-134) préservé : `toApiBlock` ne touche ni au placement des marqueurs
      `cache_control`, ni au préfixe stable (rien de volatil ajouté à la consigne système).
- [ ] Discipline F-119 intacte : un échec de lecture reste « non concluant », pas une preuve d'absence.
- [ ] Isolation : la lecture passe par `RunnerTarget` du workspace/user courant (chemin existant).

---

## Périmètre

### Hors scope (explicite)

- **Aucune évolution du protocole runner ni du runner** : réutilisation stricte de `read_file_bytes`
  (F-110) et `readFileBytes` de la gateway (SF-121-22).
- **Aucune persistance du binaire média** dans la transcription/trace : le bloc média vit dans le
  tour vivant ; la trace inter-tours garde une **légende texte** (décision D3). Pas de RAG, pas
  d'indexation (hors périmètre V1, Provider-First).
- Génération d'images (F-142) : sans rapport.
- Pas de nouvel outil exposé au modèle : `read_file` inchangé côté schéma.
- Frontend : aucun composant (le rendu du média dans le fil relève d'une SF frontend ultérieure si
  souhaité ; ici c'est le **fournisseur** qui voit le média, pas l'écran).

---

## Contraintes de validation

| Champ | Obligatoire | Borne | Format / Valeurs |
|-------|-------------|-------|------------------|
| media_type image | — | image ≤ 5 Mio | `image/png`, `image/jpeg`, `image/gif`, `image/webp` |
| media_type document | — | pdf ≤ 10 Mio | `application/pdf` |
| base64Data | Oui (si bloc) | — | Base64 standard, jamais tronqué |

Notes :
- Bornes strictes ; la borne PDF est en outre plafonnée par le cap dur du runner
  `read_file_bytes` (`MAX_BYTES_FILE` = 10 Mio).
- Détection = **extension supportée ET magic bytes reconnus** (défense : une extension ne suffit pas).

---

## Technique

### Endpoint(s)

Aucun. Chemin interne de la boucle d'agent (`AtelierChatService`) + mapping fournisseur.

### Tables impactées

Aucune.

### Migration Liquibase

- [x] Non applicable

### Composants Angular

Aucun.

---

## Plan de test

### Tests unitaires

- [ ] `AnthropicAgentProviderTest` — `Image` encodé en bloc `image`/`source`/`base64`/`media_type`.
- [ ] `AnthropicAgentProviderTest` — `Document` encodé en bloc `document`.
- [ ] `AnthropicAgentProviderTest` — `ToolResult` avec blocs média ⇒ `content` tableau ; sans ⇒ chaîne.
- [ ] `AnthropicAgentProviderTest` — le marqueur `cache_control` reste posé correctement (non-régression).
- [ ] `AnthropicAgentProvider.supportedMediaTypes()` contient les 5 types ; défaut interface = vide.
- [ ] `AtelierMediaRead` — détection par extension + magic (PNG/JPEG/GIF/WebP/PDF), rejets.
- [ ] `AtelierMediaRead` — bornes de taille par type.

### Tests d'intégration

- [ ] `AtelierChatServiceTest` (ou test ciblé) — `read_file` sur PNG ⇒ résultat porteur d'un bloc image.
- [ ] `read_file` sur fichier texte `.md` ⇒ lecture texte inchangée (non-régression).
- [ ] `read_file` sur `.png` au-delà de la borne ⇒ note texte, pas de bloc.

### Isolation workspace

- [x] Applicable — la lecture passe par le `RunnerTarget` du workspace/user courant (chemin existant,
      non modifié) ; aucun accès hors `user_id`.

---

## Dépendances

### Subfeatures bloquantes

- `SF-110-03` (read_file_bytes runner) — Done.
- `SF-121-22` (lecture gros fichiers par tranches) — Done (réutilise le même mécanisme).

### Préoccupations transversales

- **Auth / Principal** : aucune (chemin d'exécution existant, `RunnerTarget` inchangé).
- **Contexte tenant** : aucun nouveau moyen de résoudre le tenant — `RunnerTargets.of(workspace)` réutilisé.
- **Plans / limites** : aucun nouveau gate.
- **Navigation / routing** : aucune route.

Composants touchés (liste explicite) :
- `backend .../agent/AgentContentBlock.java` (variantes Image/Document + ToolResult.blocks)
- `backend .../agent/AiAgentProvider.java` (supportedMediaTypes, défaut)
- `backend .../agent/AnthropicAgentProvider.java` (toApiBlock)
- `backend .../atelier/AtelierMediaRead.java` (nouveau — détection + assemblage)
- `backend .../atelier/AtelierChatService.java` (interception read_file média, ToolOutcome.mediaBlocks, ToolResult)

---

## Notes et décisions

- **D1 — Runner-free.** Réutilise `read_file_bytes`/`readFileBytes` (déjà au contrat F-110, comme
  SF-121-22 l'a fait pour l'édition de gros fichiers). **Aucune mise à jour runner.**
- **D2 — Provider-First / Provider Independence.** La gateway n'assume pas la vision : elle demande
  au fournisseur ce qu'il sait voir (`supportedMediaTypes()`, défaut vide sur l'interface). Le mapping
  `image`/`document` est confiné à `AnthropicAgentProvider`.
- **D3 — Le média vit dans le tour, la trace garde une légende.** Le bloc Base64 est présent dans le
  `tool_result` du tour vivant (le fournisseur le voit) ; la trace/transcription inter-tours ne
  persiste qu'une légende texte — ne pas gonfler l'historique ni le cache avec des mégaoctets d'image
  rejoués à chaque tour. Cohérent F-134 (préfixe stable, rien de volatil).
- **D4 — Cache préservé.** `toApiBlock` mappe des blocs ; il ne déplace aucun marqueur `cache_control`
  (posés par `toApiMessages`/`toApiTools`, inchangés).
- **D5 — Détection extension + magic.** Extension gate le coût (pas de sniff sur tout `read_file`) ;
  magic confirme (une extension peut mentir). Mismatch ⇒ repli texte, jamais d'échec.
